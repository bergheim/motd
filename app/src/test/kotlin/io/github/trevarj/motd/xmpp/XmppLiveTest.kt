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

    /** Drains this channel until an event of type [T] arrives, or returns null past [deadline]. */
    private suspend inline fun <reified T : XmppEvent> ReceiveChannel<XmppEvent>.receiveUntil(
        deadline: Duration = 20.seconds,
    ): T? = withTimeoutOrNull(deadline) {
        var found: T? = null
        while (found == null) {
            val event = receive()
            if (event is T) found = event
        }
        found
    }

    @Test
    fun loginAndReady() = runBlocking {
        withTimeout(30.seconds) {
            val session = SmackXmppSession(account1!!)
            try {
                session.connectAndLogin()
                val ready = session.events.receiveUntil<XmppEvent.Ready>()
                assertNotNull("expected a Ready event after STARTTLS login", ready)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun oneToOneRoundtrip() = runBlocking {
        withTimeout(60.seconds) {
            val testSession = SmackXmppSession(account1!!)
            val peerSession = SmackXmppSession(account2!!)
            try {
                testSession.connectAndLogin()
                assertNotNull(testSession.events.receiveUntil<XmppEvent.Ready>())
                peerSession.connectAndLogin()
                assertNotNull(peerSession.events.receiveUntil<XmppEvent.Ready>())

                val payload = "ping-${UUID.randomUUID()}"
                val originId = UUID.randomUUID().toString()
                peerSession.sendChat(account1!!.bareJid, payload, originId)

                val received = testSession.events.receiveUntil<XmppEvent.ChatMessage>()
                assertNotNull("expected the receiver to see a ChatMessage", received)
                assertEquals(payload, received!!.text)

                val confirmed = peerSession.events.receiveUntil<XmppEvent.SendConfirmed>()
                assertNotNull("expected the sender to see a SendConfirmed ack", confirmed)
                assertEquals(originId, confirmed!!.originId)
            } finally {
                testSession.close()
                peerSession.close()
            }
        }
    }

    @Test
    fun mucRoundtrip() = runBlocking {
        withTimeout(60.seconds) {
            val roomJid = "motd-e2e@conference.$domain"
            val testSession = SmackXmppSession(account1!!)
            val peerSession = SmackXmppSession(account2!!)
            try {
                testSession.connectAndLogin()
                assertNotNull(testSession.events.receiveUntil<XmppEvent.Ready>())
                peerSession.connectAndLogin()
                assertNotNull(peerSession.events.receiveUntil<XmppEvent.Ready>())

                testSession.joinMuc(roomJid, account1!!.mucNick)
                assertNotNull(testSession.events.receiveUntil<XmppEvent.MucSelfJoined>())
                peerSession.joinMuc(roomJid, account2!!.mucNick)
                assertNotNull(peerSession.events.receiveUntil<XmppEvent.MucSelfJoined>())

                val payload = "muc-ping-${UUID.randomUUID()}"
                val originId = UUID.randomUUID().toString()
                peerSession.sendMuc(roomJid, payload, originId)

                val received = testSession.events.receiveUntil<XmppEvent.MucMessage>()
                assertNotNull("expected the other occupant to see a MucMessage", received)
                assertEquals(payload, received!!.text)

                // The sender also receives its own message reflected back by the room, tagged
                // with its own occupant nick.
                val reflected = peerSession.events.receiveUntil<XmppEvent.MucMessage>()
                assertNotNull("expected the sender's own reflection", reflected)
                assertEquals(payload, reflected!!.text)
                assertEquals(account2!!.mucNick, reflected.occupantNick)
            } finally {
                testSession.close()
                peerSession.close()
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
                val ready = session.events.receiveUntil<XmppEvent.Ready>()
                assertNotNull("expected a Ready event over direct TLS on 5223", ready)
            } finally {
                session.close()
            }
        }
    }
}
