package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.service.ConnectionManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every [ConnectionManager] member is either implemented by [XmppConnectionManager] or recorded in
 * [intentionallyInherited] with the reason its inherited default is the right answer for XMPP.
 *
 * The seam hands out defaults — an empty flow, `false`, `true`, a no-op — that read to shared code
 * as "nothing is connected", "the operation finished", or "the write failed" rather than failing
 * loudly. A backend that simply never overrides a member therefore looks correct: the app compiles,
 * the suite passes (fakes implement the interface directly), and only the running app is wrong.
 * That is exactly how this backend shipped without `connectionActivity` — `ChatViewModel` reads
 * connection state from that flow *exclusively*, so every XMPP buffer rendered as disconnected
 * while this manager's own state map said Ready — and without `partChannelForClose`, whose default
 * reported a durable channel close as accepted that nothing on the XMPP side ever completed.
 *
 * Unlike `service.CompositeSeamCoverageTest`, a long allowlist is expected and fine here: many
 * defaults genuinely are correct for this backend (no cert prompts, no lag measurement, no
 * server-side push). The point is that each omission is a recorded decision with a reason, not an
 * accident — so adding a seam member, or an XMPP capability, forces a deliberate choice.
 */
class XmppSeamCoverageTest {

    /**
     * Seam members [XmppConnectionManager] deliberately inherits, by JVM method name (a property
     * member appears as its `getX` accessor), each with the reason its default is correct for XMPP
     * today. An entry here is a claim that the inherited default is *right*, not that the work is
     * merely unfinished-but-harmless; anything genuinely missing belongs in the implementation.
     */
    private val intentionallyInherited = mapOf(
        "getPresenceStates" to
            "XMPP presence is not modeled in this baseline; the empty map means UNKNOWN for every " +
                "key, which is exactly what shared UI should render for a backend with no " +
                "presence signal.",
        "getLagStates" to
            "no round-trip latency measurement (XEP-0199 ping is not wired); absent means " +
                "'unknown', which is what the lag indicator already renders for a network that " +
                "reports nothing.",
        "getChannelJoinOutcomes" to
            "no join-rejection signal exists on this backend yet (see joinChannel's KDoc: a " +
                "rejected/timed-out MUC join leaves memberLoadStates at LOADING). An empty flow is " +
                "the honest representation of 'this backend never reports a rejection'.",
        "getServerPushAvailable" to
            "no server-side push registration: XEP-0357 is on the deferred cross-device list " +
                "(docs/backend-neutral-xmpp-rollout.md), so false is literally true here.",
        "getAttachmentUploadEndpoints" to
            "no HTTP File Upload (XEP-0363) in this baseline, so no network offers an endpoint; " +
                "the empty map disables attachment upload for XMPP buffers, which is correct.",
        "getReactionCapabilities" to
            "reactions are not implemented (sendReact is an explicit no-op); an absent entry is " +
                "how the seam spells 'reactions are unavailable right now'.",
        "liveIdentityRules" to
            "IRC casemapping rules negotiated over ISUPPORT. A bare JID needs no such live " +
                "normalization contract, and null is the seam's documented 'no live rules; keep " +
                "your persisted fallback'.",
        "historyAvailability" to
            "server-side history: XEP-0313 MAM is on the deferred cross-device list, so this " +
                "backend has no availability to report and null is correct.",
        "protocolCommands" to
            "no protocol-command/moderation capability in this baseline; null is the seam's " +
                "documented 'this backend has no such capability at all'.",
        "roomTargetSyntax" to
            "null is the documented XMPP answer, not an omission: a join target is a bare room " +
                "JID used verbatim, with no IRC-style channel-name transform to apply.",
        "supportsRoomDiscovery" to
            "false is the documented XMPP answer for this baseline: there is no MUC service " +
                "discovery yet, and the seam pins false to mean 'no such capability', which is " +
                "what keeps the shared channel browser from waiting out a timeout on an XMPP row.",
        "acceptInvite" to
            "MUC invitations are not ingested in this baseline, so no persisted invitation row " +
                "can exist for this backend to claim.",
        "dismissInvite" to
            "the symmetric half of acceptInvite: nothing to resolve while invitations are not " +
                "ingested.",
        "setChannelTopic" to
            "MUC subjects are read-only here (XmppProcessor.onMucSubject ingests them; there is " +
                "no send path), and the default's conservative false correctly reports the write " +
                "as not accepted rather than silently claiming success.",
    )

    private fun methodNames(type: Class<*>, declaredOnly: Boolean): Set<String> =
        (if (declaredOnly) type.declaredMethods else type.methods)
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name.substringBefore('$') }
            .filterNot { it.startsWith("access") }
            .toSortedSet()

    @Test
    fun `XmppConnectionManager implements or documents every seam member`() {
        val seamMembers = methodNames(ConnectionManager::class.java, declaredOnly = false)
        val implemented = methodNames(XmppConnectionManager::class.java, declaredOnly = true)

        val missing = seamMembers - implemented - intentionallyInherited.keys
        assertEquals(
            "XmppConnectionManager neither implements nor documents these ConnectionManager " +
                "members, so XMPP networks silently get the interface default — an empty flow, " +
                "false, true, or a no-op — which shared code reads as 'nothing is connected' or " +
                "'the operation finished'. Implement them, or record them in " +
                "intentionallyInherited with the reason the default is correct for XMPP. " +
                "Missing: $missing",
            emptySet<String>(),
            missing,
        )
    }

    @Test
    fun `the allowlist stays honest in both directions`() {
        val seamMembers = methodNames(ConnectionManager::class.java, declaredOnly = false)
        val implemented = methodNames(XmppConnectionManager::class.java, declaredOnly = true)

        val nowImplemented = intentionallyInherited.keys intersect implemented
        assertEquals(
            "These members are allowlisted as intentionally inherited but XmppConnectionManager " +
                "now implements them; drop the stale entries so the allowlist keeps shrinking as " +
                "the backend grows: $nowImplemented",
            emptySet<String>(),
            nowImplemented,
        )

        val notOnTheSeam = intentionallyInherited.keys - seamMembers
        assertEquals(
            "These allowlist entries name no ConnectionManager member at all (renamed or " +
                "removed from the seam); delete them rather than leaving an entry that excuses " +
                "nothing: $notOnTheSeam",
            emptySet<String>(),
            notOnTheSeam,
        )
    }
}
