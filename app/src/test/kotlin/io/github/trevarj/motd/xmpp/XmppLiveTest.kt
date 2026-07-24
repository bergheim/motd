package io.github.trevarj.motd.xmpp

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Live-network acceptance tests for [SmackXmppSession] against a **real** ejabberd instance.
 * This is the Smack adapter's acceptance gate — Task 4's implementation isn't considered done
 * until these pass against real infrastructure. It is deliberately not a unit test: no fakes,
 * no Robolectric, just a plain JVM test driving Smack over a real TCP connection.
 *
 * ## Env contract
 *
 * Every test in this class self-skips (via [org.junit.Assume]) unless all five variables below
 * are set. This makes the class safe to run unconditionally in CI: with no env vars present the
 * whole suite reports as skipped, never failed.
 *
 * - `MOTD_XMPP_LIVE_DOMAIN` — e.g. `xmpp.glvortex.net`
 * - `MOTD_XMPP_LIVE_USER1` / `MOTD_XMPP_LIVE_PASS1` — first test account (localpart + password)
 * - `MOTD_XMPP_LIVE_USER2` / `MOTD_XMPP_LIVE_PASS2` — second test account, used as the "peer" for
 *   one-to-one and MUC round-trips
 *
 * The MUC room used by [mucRoundtrip] is `motd-e2e@conference.$MOTD_XMPP_LIVE_DOMAIN` and is
 * expected to auto-create on first join (ejabberd's default MUC service behavior).
 *
 * ## Running against a real server
 *
 * ```bash
 * MOTD_XMPP_LIVE_DOMAIN=xmpp.glvortex.net \
 * MOTD_XMPP_LIVE_USER1=motd-test MOTD_XMPP_LIVE_PASS1=... \
 * MOTD_XMPP_LIVE_USER2=motd-peer MOTD_XMPP_LIVE_PASS2=... \
 * nix develop -c ./gradlew :app:testFossDebugUnitTest --tests "io.github.trevarj.motd.xmpp.XmppLiveTest" --stacktrace
 * ```
 *
 * ## Implementation note
 *
 * Each test wraps real network I/O in [runBlocking] + [withTimeout] rather than
 * `kotlinx-coroutines-test`'s `runTest`. `runTest`'s virtual-time scheduler is built for
 * fake/mocked suspension and will not advance real socket I/O — using it here would either
 * hang or auto-skip the delays we're actually trying to wait through. Deadlines are still
 * enforced via `withTimeout`, so a hung server still fails the test instead of the build.
 */
class XmppLiveTest {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private val domain: String? get() = env("MOTD_XMPP_LIVE_DOMAIN")

    private fun liveConfig(
        userVar: String,
        passVar: String,
        directTls: Boolean = false,
        port: Int = 5222,
    ): XmppAccountConfig? {
        val d = domain ?: return null
        val u = env(userVar) ?: return null
        val p = env(passVar) ?: return null
        return XmppAccountConfig(
            bareJid = "$u@$d",
            password = p,
            host = d,
            port = port,
            directTls = directTls,
            mucNick = u,
        )
    }

    private val account1: XmppAccountConfig? get() = liveConfig("MOTD_XMPP_LIVE_USER1", "MOTD_XMPP_LIVE_PASS1")
    private val account2: XmppAccountConfig? get() = liveConfig("MOTD_XMPP_LIVE_USER2", "MOTD_XMPP_LIVE_PASS2")

    @Before
    fun gate() {
        assumeTrue(
            "Live XMPP tests require MOTD_XMPP_LIVE_DOMAIN/USER1/PASS1/USER2/PASS2 to be set; skipping.",
            account1 != null && account2 != null,
        )
    }

    /**
     * Drains [channel] until an event of type [T] matching [predicate] arrives, or returns null
     * past [deadline]. Matching by type alone is not enough here: a reused MUC room replays
     * discussion history on join, and either account may have offline-queued messages left over
     * from a previous run of this same test — both would otherwise be consumed as a false match
     * ahead of the live message we're actually waiting for. Every call site therefore filters on
     * the unique payload/originId generated for that assertion.
     */
    private suspend inline fun <reified T : XmppEvent> receiveUntil(
        channel: ReceiveChannel<XmppEvent>,
        deadline: Duration = 20.seconds,
        predicate: (T) -> Boolean = { true },
    ): T? = withTimeoutOrNull(deadline) {
        var found: T? = null
        while (found == null) {
            val event = channel.receive()
            if (event is T && predicate(event)) found = event
        }
        found
    }

    @Test
    fun loginAndReady() = runBlocking {
        withTimeout(30.seconds) {
            val session = SmackXmppSession(account1!!)
            try {
                session.connectAndLogin()
                val ready = receiveUntil<XmppEvent.Ready>(session.events)
                assertNotNull("expected a Ready event after STARTTLS login", ready)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun oneToOneRoundtrip() = runBlocking {
        withTimeout(60.seconds) {
            var testSession: SmackXmppSession? = null
            var peerSession: SmackXmppSession? = null
            try {
                testSession = SmackXmppSession(account1!!)
                peerSession = SmackXmppSession(account2!!)

                testSession.connectAndLogin()
                assertNotNull(receiveUntil<XmppEvent.Ready>(testSession.events))
                peerSession.connectAndLogin()
                assertNotNull(receiveUntil<XmppEvent.Ready>(peerSession.events))

                val payload = "ping-${UUID.randomUUID()}"
                val originId = UUID.randomUUID().toString()
                peerSession.sendChat(account1!!.bareJid, payload, originId)

                val received = receiveUntil<XmppEvent.ChatMessage>(testSession.events) { it.text == payload }
                assertNotNull("expected the receiver to see our ChatMessage (matched by payload)", received)
                assertEquals(payload, received!!.text)

                val confirmed =
                    receiveUntil<XmppEvent.SendConfirmed>(peerSession.events) { it.originId == originId }
                assertNotNull("expected the sender to see our SendConfirmed (matched by originId)", confirmed)
                assertEquals(originId, confirmed!!.originId)
            } finally {
                testSession?.close()
                peerSession?.close()
            }
        }
    }

    @Test
    fun mucRoundtrip() = runBlocking {
        withTimeout(120.seconds) {
            val roomJid = "motd-e2e@conference.$domain"
            var testSession: SmackXmppSession? = null
            var peerSession: SmackXmppSession? = null
            try {
                testSession = SmackXmppSession(account1!!)
                peerSession = SmackXmppSession(account2!!)

                testSession.connectAndLogin()
                assertNotNull(receiveUntil<XmppEvent.Ready>(testSession.events))
                peerSession.connectAndLogin()
                assertNotNull(receiveUntil<XmppEvent.Ready>(peerSession.events))

                testSession.joinMuc(roomJid, account1!!.mucNick)
                assertNotNull(receiveUntil<XmppEvent.MucSelfJoined>(testSession.events))
                peerSession.joinMuc(roomJid, account2!!.mucNick)
                assertNotNull(receiveUntil<XmppEvent.MucSelfJoined>(peerSession.events))

                val payload = "muc-ping-${UUID.randomUUID()}"
                val originId = UUID.randomUUID().toString()
                peerSession.sendMuc(roomJid, payload, originId)

                val received = receiveUntil<XmppEvent.MucMessage>(testSession.events) { it.text == payload }
                assertNotNull("expected the other occupant to see our MucMessage (matched by payload)", received)
                assertEquals(payload, received!!.text)

                // The sender also receives its own message reflected back by the room, tagged
                // with its own occupant nick — match on both so stale room history can't satisfy it.
                val peerNick = account2!!.mucNick
                val reflected = receiveUntil<XmppEvent.MucMessage>(peerSession.events) {
                    it.text == payload && it.occupantNick == peerNick
                }
                assertNotNull("expected the sender's own reflection (matched by payload + occupant nick)", reflected)
                assertEquals(payload, reflected!!.text)
                assertEquals(peerNick, reflected.occupantNick)
            } finally {
                runCatching { testSession?.leaveMuc(roomJid) }
                runCatching { peerSession?.leaveMuc(roomJid) }
                testSession?.close()
                peerSession?.close()
            }
        }
    }

    @Test
    fun directTls5223_alsoWorks() = runBlocking {
        withTimeout(30.seconds) {
            val config = liveConfig(
                "MOTD_XMPP_LIVE_USER1",
                "MOTD_XMPP_LIVE_PASS1",
                directTls = true,
                port = 5223,
            )!!
            val session = SmackXmppSession(config)
            try {
                session.connectAndLogin()
                val ready = receiveUntil<XmppEvent.Ready>(session.events)
                assertNotNull("expected a Ready event over direct TLS on 5223", ready)
            } finally {
                session.close()
            }
        }
    }
}
