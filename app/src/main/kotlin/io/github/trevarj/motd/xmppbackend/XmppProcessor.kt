package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.TimelineObservation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The XMPP backend's single processor (docs/backend-neutral-xmpp-rollout.md "Persistence and writer
 * ownership"): the only path that turns a live [XmppSession]'s wire events into canonical Room facts.
 * "Each backend has exactly one processor that turns its wire events into canonical facts, and every
 * processor persists those facts only through the shared canonical repositories; no backend adds a
 * private Room write path" — this class writes exclusively through [BufferStore] and
 * [CanonicalTimelineStore], the same shared surface
 * [io.github.trevarj.motd.data.sync.EventProcessor] uses for IRC, and owns no table of its own.
 * [XmppConnectionManager]/[XmppAccountActor] hand this processor each live session's
 * [XmppIncomingMessage]s; neither touches Room directly (docs/backend-neutral-xmpp-rollout.md
 * "Required boundary").
 */
@Singleton
class XmppProcessor @Inject constructor(
    db: MotdDatabase,
    private val bufferStore: BufferStore = BufferStore(db),
    private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
) {
    /**
     * Persist one incoming direct message from [message.fromBareJid][XmppIncomingMessage.fromBareJid]
     * on [networkId]'s live session.
     *
     * Finds-or-creates the QUERY buffer for (networkId, bare JID) through [BufferStore.getOrCreate] —
     * the same shared, network-scoped find-or-create idiom
     * [io.github.trevarj.motd.data.sync.EventProcessor] uses for IRC queries — named and displayed
     * as the bare JID (XMPP has no separate roster-name concept wired in this slice; a friendly
     * display name arrives with roster support). Two networks sharing an identical bare JID stay
     * separate conversations because every buffer/alias lookup here is scoped by [networkId].
     *
     * Dedup/alias identity: [XmppIncomingMessage.stanzaId] becomes the canonical event's `msgid`, so
     * [CanonicalTimelineStore] derives an [io.github.trevarj.motd.data.db.EventAliasNamespace.MSGID]
     * alias from it exactly like an IRC `msgid` — the rollout doc's "generic backend-scoped identity"
     * direction rather than a protocol-named `XMPP_MSGID` alias. A stanza id is sender-supplied, not
     * archive-assigned (no XMPP MAM/XEP-0359 stanza-id in this slice), which matches the MSGID
     * namespace's existing IRC semantics (also sender/server-assigned per-message, not a paging
     * cursor) rather than requiring a new namespace. Redelivery with the same stanza id therefore
     * dedups to the same row; a message with no stanza id lands with `msgid = null` and no alias,
     * exactly like an IRC message with no msgid.
     */
    suspend fun onIncomingDirectMessage(
        networkId: Long,
        message: XmppIncomingMessage,
        connectionGeneration: Long?,
    ) {
        if (message.isCarbonOrSelf) return // carbons/self-echo land with a later slice
        val buffer = bufferStore.getOrCreate(
            networkId = networkId,
            normalizedName = message.fromBareJid,
            displayName = message.fromBareJid,
            type = BufferType.QUERY,
        )
        val delayStamp = message.delayStampMillis
        val timeProvenance = if (delayStamp != null) TimeProvenance.SERVER_TAG else TimeProvenance.LOCAL_CLOCK
        val serverTime = delayStamp ?: System.currentTimeMillis()
        val event = TimelineEventEntity(
            bufferId = buffer.id,
            msgid = message.stanzaId,
            serverTime = serverTime,
            sender = message.fromBareJid,
            kind = MessageKind.PRIVMSG,
            text = message.body,
            isSelf = false,
            dedupKey = message.stanzaId
                ?: "xmpp-dm:$networkId:${buffer.id}:$serverTime:${message.fromBareJid}:${message.body}",
        )
        canonicalTimeline.ingest(
            TimelineObservation(
                networkId = networkId,
                event = event,
                origin = ObservationOrigin.LIVE,
                connectionGeneration = connectionGeneration,
                batchId = null,
                timeProvenance = timeProvenance,
            ),
        )
        // notification policy arrives with a later slice
    }
}
