package io.github.trevarj.motd.xmppbackend

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.XmppAccountEntity
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.delay.DelayInformationManager
import org.jxmpp.jid.parts.Resourcepart

/** Verbatim credential-failure message, shown by the account UI (carried over from fork/xmpp-support). */
internal const val XMPP_AUTH_FAILURE_REASON = "Wrong address or password"

/**
 * Smack-backed [XmppSession] (docs/backend-neutral-xmpp-rollout.md "PR 2"). Carries over the
 * fork/xmpp-support prototype's TLS/SASL connect mechanics, its [SanHostnameVerifier] fix for the
 * STARTTLS path, and its parsing-exception/clean-teardown reconnect-stability fixes. Reimplemented
 * against the new seam: state is published as this package's [XmppSessionState], never a wire type,
 * and every Smack import stays inside `xmppbackend/` (enforced by `ImportBoundaryTest`).
 *
 * Only [XmppAccountEntity.jid]/[XmppAccountEntity.password]/[XmppAccountEntity.resource] are read.
 * Host/port are intentionally left unset so Smack resolves them via the standard XMPP SRV lookup
 * against the JID's domain: the `networks` row's host/port columns are IRC-shaped and grandfathered
 * to the IRC adapter (docs/backend-neutral-xmpp-rollout.md "Persistence and writer ownership"), so
 * the XMPP adapter must not read them.
 *
 * One instance = one connection attempt, per the [XmppSession] contract; [XmppAccountActor] creates
 * a fresh instance for every (re)connect.
 */
internal class SmackXmppSession(private val account: XmppAccountEntity) : XmppSession {
    private val _state = MutableStateFlow<XmppSessionState>(XmppSessionState.Disconnected)
    override val state: StateFlow<XmppSessionState> = _state.asStateFlow()

    // Buffered rather than rendezvous: Smack's chat listener callback (registerIncomingMessageListener)
    // is a plain synchronous callback and cannot suspend to wait for a slow collector. A dropped
    // emission past the buffer is an accepted v1 tradeoff (see [XmppSession.incomingMessages]).
    private val _incomingMessages = MutableSharedFlow<XmppIncomingMessage>(extraBufferCapacity = INCOMING_MESSAGE_BUFFER)
    override val incomingMessages: Flow<XmppIncomingMessage> = _incomingMessages.asSharedFlow()

    /**
     * Per-session XMPP resource. Two devices signed into one account must NOT present an identical
     * resource: a server's conflict policy (RFC 6120 §7.7.2.2) typically kills the older session
     * when a newer one binds the same resource, so a fixed fallback resource would make two devices
     * repeatedly kick each other in a reconnect loop. When the account has no configured resource,
     * fall back to a random per-attempt suffix (carried over from the prototype's fix).
     */
    private val resource: Resourcepart = account.resource?.takeIf(String::isNotBlank)
        ?.let(Resourcepart::from)
        ?: Resourcepart.from("motd-" + UUID.randomUUID().toString().take(4))

    private val connection: XMPPTCPConnection = XMPPTCPConnection(
        XMPPTCPConnectionConfiguration.builder()
            .setXmppAddressAndPassword(account.jid, account.password)
            .setResource(resource)
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required) // STARTTLS mandatory
            // Smack's default hostname verifier comes from legacy Apache HTTP classes that are
            // absent on modern Android, and the JVM/Android TLS stack's own default is deny-all;
            // neither works here, so verify SAN dNSNames directly (carried over from the prototype).
            .setHostnameVerifier(SanHostnameVerifier)
            .build(),
    ).apply {
        // Smack's default callback disconnects the whole stream on any stanza it cannot parse (e.g.
        // a disco form field type unknown to this Smack version). Drop just that stanza and keep the
        // stream alive instead — carried over from the prototype as a reconnect-stability fix — but
        // log it at WARN so a silent drop stays observable. Never log stanza content: it can hold
        // private message bodies that must not leak into logcat.
        setParsingExceptionCallback { unparseable ->
            val length = unparseable.content?.length ?: 0
            LOGGER.log(
                Level.WARNING,
                "Dropping unparsable XMPP stanza ($length chars)",
                unparseable.parsingException,
            )
        }
    }

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            _state.value = XmppSessionState.Connecting
            // XmppAccountActor owns the reconnect loop; Smack's own reconnection manager staying on
            // would race it, so it must be disabled before connecting (carried over from the
            // prototype, same invariant as :irc's actor-owned reconnect).
            ReconnectionManager.getInstanceFor(connection).disableAutomaticReconnection()
            registerConnectionListener() // BEFORE connect/login — spec invariant, per the prototype.
            registerIncomingMessageListener() // Same ordering invariant: attach before stanzas can flow.
            try {
                connection.connect()
                _state.value = XmppSessionState.Authenticating
                connection.login()
                _state.value = XmppSessionState.Ready(connection.user.asBareJid().toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: SASLErrorException) {
                // Rejected credentials never fix themselves: fatal, no auto-retry.
                _state.value = XmppSessionState.Failed(XMPP_AUTH_FAILURE_REASON, fatal = true)
            } catch (e: Exception) {
                // Transport/TLS/timeout/etc: retryable.
                _state.value = XmppSessionState.Failed(e.message ?: "connection failed", fatal = false)
            }
        }
    }

    private fun registerConnectionListener() {
        connection.addConnectionListener(object : ConnectionListener {
            override fun connectionClosed() {
                _state.value = XmppSessionState.Disconnected
            }

            override fun connectionClosedOnError(e: Exception) {
                val auth = e is SASLErrorException
                _state.value = XmppSessionState.Failed(
                    reason = if (auth) XMPP_AUTH_FAILURE_REASON else (e.message ?: "connection closed"),
                    fatal = auth,
                )
            }
        })
    }

    /**
     * Bridge Smack's one-to-one chat callback onto [incomingMessages]. `ChatManager`'s listener
     * already narrows delivery to 1:1 DM-shaped stanzas addressed to us (not MUC, not our own sends —
     * carbons are not enabled in this slice, so there is no reflection to filter here); a stanza with
     * no `<body/>` (e.g. a bare chat-state notification, XEP-0085) carries no message to persist and
     * is dropped rather than landing as a blank row. Never logs stanza content (same privacy
     * invariant as the parsing-exception callback above).
     */
    private fun registerIncomingMessageListener() {
        ChatManager.getInstanceFor(connection).addIncomingListener { from, message, _ ->
            val body = message.body ?: return@addIncomingListener
            _incomingMessages.tryEmit(
                XmppIncomingMessage(
                    fromBareJid = from.toString(),
                    body = body,
                    stanzaId = message.stanzaId,
                    delayStampMillis = DelayInformationManager.getDelayTimestamp(message)?.time,
                ),
            )
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching { connection.disconnect(Presence(Presence.Type.unavailable)) }
        }
        _state.value = XmppSessionState.Disconnected
    }

    private companion object {
        val LOGGER: Logger = Logger.getLogger(SmackXmppSession::class.java.name)

        /** Generous headroom for a burst of offline-storage redelivery; see [incomingMessages]. */
        const val INCOMING_MESSAGE_BUFFER = 64
    }
}

/**
 * Real [XmppSessionFactory] (docs/backend-neutral-xmpp-rollout.md "PR 2"). Registers Smack's
 * Android providers/DNS resolver once per process on first injection — required for Smack to work
 * on Android, no-op-safe under Robolectric — carried over from the prototype's manager-level init,
 * relocated here so [XmppConnectionManager] stays Smack-agnostic.
 *
 * Not `internal`: [XmppBackendModule]'s `@Binds` function takes this as a parameter type, and a
 * public Dagger binding cannot expose an internal type (Kotlin visibility rule) — same reason
 * [SmackXmppSession] itself stays unexposed by never appearing in a public signature.
 */
class SmackXmppSessionFactory @Inject constructor(
    @ApplicationContext context: Context,
) : XmppSessionFactory {
    init {
        runCatching { AndroidSmackInitializer.initialize(context) }
    }

    override fun create(account: XmppAccountEntity): XmppSession = SmackXmppSession(account)
}

/**
 * RFC 6125-style verification of the peer certificate's subjectAltName dNSName entries against the
 * connect hostname. Carried over verbatim from the fork/xmpp-support prototype: deliberately strict
 * (no CN fallback — SAN-less certs fail; a wildcard matches only a single leftmost label, so
 * `*.example.net` matches `a.example.net` but never `example.net` or `a.b.example.net`). TLS chain
 * trust is already enforced by the socket layer; this only binds the validated chain to the
 * expected host, which Smack's own default verifier does not reliably do on Android.
 */
internal object SanHostnameVerifier : HostnameVerifier {
    private const val SAN_DNS_NAME = 2

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val host = hostname.lowercase()
        val sans = try {
            cert.subjectAlternativeNames ?: return false
        } catch (_: CertificateParsingException) {
            return false
        }
        return sans.any { entry ->
            entry != null && entry.size >= 2 && entry[0] == SAN_DNS_NAME &&
                (entry[1] as? String)?.lowercase()?.let { matches(host, it) } == true
        }
    }

    internal fun matches(host: String, pattern: String): Boolean {
        if (!pattern.startsWith("*.")) return host == pattern
        val suffix = pattern.substring(1) // ".example.net"
        if (!host.endsWith(suffix)) return false
        val label = host.dropLast(suffix.length)
        return label.isNotEmpty() && !label.contains('.')
    }
}
