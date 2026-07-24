package io.github.trevarj.motd.xmpp

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jivesoftware.smack.util.stringencoder.Base64
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

    /**
     * `smack-android` (pulled in transitively by `:app`) service-loads `AndroidBase64Encoder` as
     * Smack's default [Base64.Encoder], which delegates to `android.util.Base64`. On the plain
     * JVM these live tests run on, AGP's unit-test stub of that class throws
     * `"Method encodeToString ... not mocked"` the moment Smack needs it — e.g.
     * `EntityCapsManager.generateVerificationString` while building the initial presence/caps
     * hash — which kills the connection pre-auth. Installing this encoder before any session is
     * created replaces it with one backed by `java.util.Base64`, mirroring Smack's own
     * `Java7Base64Encoder` (smack-java8): standard alphabet, padded for [encodeToString],
     * unpadded for [encodeToStringWithoutPadding], no MIME line wrapping — equivalent to what
     * Android's `Base64.NO_WRAP` would have produced.
     */
    private object JvmBase64Encoder : Base64.Encoder {
        private val encoder = java.util.Base64.getEncoder()
        private val encoderWithoutPadding = encoder.withoutPadding()
        private val decoder = java.util.Base64.getDecoder()

        override fun decode(string: String): ByteArray = decoder.decode(string)
        override fun encodeToString(input: ByteArray): String = encoder.encodeToString(input)
        override fun encodeToStringWithoutPadding(input: ByteArray): String =
            encoderWithoutPadding.encodeToString(input)
        override fun encode(input: ByteArray): ByteArray = encoder.encode(input)
    }

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
        // Force Smack's lazy static initialization FIRST — it service-loads smack-android's
        // initializer, which would otherwise overwrite the encoder installed below.
        org.jivesoftware.smack.SmackConfiguration.getVersion()
        // Must run before any SmackXmppSession/XMPPTCPConnection is created — see JvmBase64Encoder KDoc.
        Base64.setEncoder(JvmBase64Encoder)
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
        crossinline predicate: (T) -> Boolean = { true },
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
        // Four sequential 20s receiveUntil budgets + network time for connect/login/send.
        withTimeout(100.seconds) {
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
        // Six sequential 20s receiveUntil budgets + network time for connect/login/join/send.
        withTimeout(150.seconds) {
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
