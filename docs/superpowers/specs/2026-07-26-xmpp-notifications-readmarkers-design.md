# Tier-1 XMPP parity: notifications + read markers

**Status:** approved design (codex pre-implementation review folded in). Part of the
[XMPP parity roadmap](2026-07-26-xmpp-parity-roadmap.md), Tier 1.

**Goal:** XMPP DMs and MUC mentions raise notifications like IRC does, and reading an XMPP
buffer clears its unread/mention badge (and cancels the notification). One PR — the two are
coupled: `onRead` is what cancels a notification *and* clears the badge.

## Background

XMPP inherits all shared storage/query/UI (unread computation, mention-badge rendering,
sort, pin, mute). Two behavioral hooks were only wired for IRC:

- **Notifications:** `XmppEventProcessor` injects `MessageNotifier` but never calls it. IRC
  notifies via `EventProcessor.maybeNotify → notifier.onCanonicalIncoming(...)`, wrapped in
  a durable `presentNotification` claim→post→complete cycle.
- **Read markers:** `RoutingConnectionManager.markRead` runs `if (protocol == IRC)`, so XMPP
  buffers' read anchors never advance — badges grow forever and can't be cleared.

The notifier's suppression/identity logic (mute, foreground, fool, friend) is buffer/sender
based and already protocol-neutral, with an `IrcIdentityRules()` fallback for networks with
no `network_identity` row (XMPP has none).

## Implementation

### A. Notifications

1. **Shared gate.** Extract the notify predicate as one pure function:
   ```kotlin
   fun shouldNotify(isSelf: Boolean, type: BufferType, hasMention: Boolean): Boolean =
       !isSelf && type != BufferType.SERVER && (type == BufferType.QUERY || hasMention)
   ```
   Replace the inline rule in `EventProcessor.maybeNotify` with a call to it (behaviour
   identical). `origin.notifies` / live-history checks stay *outside* the shared fn (they are
   protocol-specific), per review.

2. **Shared presentation wrapper.** `EventProcessor.presentNotification(eventId){…}` (claim →
   present → complete via `canonicalTimeline.claim/complete/releaseNotification`) is currently
   private. Extract it to a small shared helper both processors call, so XMPP posts through the
   same durable claim/complete cycle (no double-post; marks `notificationHandled=1`).

3. **Wire XMPP.** `insertDedupedMessage(...)` already returns `TimelineEventId?` (null on
   dedup). At each incoming call site (`handleChatMessage` DM, `handleMucMessage`), **after the
   insert transaction commits** and only when the id is non-null, `delayedAtMs == null` (live,
   not replayed), and `shouldNotify(isSelf, type, hasMention)`:
   ```kotlin
   presenter.present(eventId) {
       notifier.onCanonicalIncoming(networkId, bufferId, type, hasMention, eventId,
           IrcEvent.ChatMessage(
               ctx = MessageContext(msgid = stanzaId, serverTime = serverTime, account = null,
                   batchId = null, label = null, serverTimeSource = ServerTimeSource.LOCAL),
               kind = kind.toChatKind(), source = Prefix(sender), target = /* buffer target */,
               text = text, isSelf = false, replyToMsgid = null))
   }
   ```
   - `Prefix(sender)` directly (data class, no `@`-splitting): DM sender = bare JID, MUC sender
     = occupant nick — both display correctly.
   - `IrcEvent.ChatMessage` is the notifier's de-facto neutral currency (its own recovery path
     reconstructs the same from DB rows). No new type (YAGNI).

4. **Live/history gate.** `delayedAtMs != null` (XEP-0203 delay) ⇒ skip. Prevents a
   notification flood on MUC join (replays ~50 messages). *Tradeoff:* offline catch-up DMs that
   arrive delay-stamped after reconnect are also suppressed — acceptable for "live-socket
   notifications only" and revisited in the history/MAM tier. Explicitly tested.

### B. Read markers (placement (b), per review)

1. Add `markReadLocal(bufferId: Long, anchor: TimelineAnchor)` to `XmppConnectionSurface`
   (+ no-op in `NoopXmppConnectionSurface`).
2. `XmppConnectionManager` implements it by delegating to a processor-owned method that runs
   the protocol-neutral local advance:
   ```kotlin
   val target = resolveAndAdvanceCurrentReadTarget(db, bufferId, anchor) ?: return
   notifier.onRead(target.buffer.id, target.anchor)
   ```
   No wire send (XEP-0333 read sync is deferred). Preserves the XMPP sole-writer invariant.
3. `RoutingConnectionManager.markRead`: route `Protocol.XMPP -> xmpp.markReadLocal(...)`; keep
   `Protocol.IRC -> irc.markRead(...)`. Update the `:224` "IRC-only" comment.

## Tests

- `shouldNotify` pure-fn table (self, SERVER, QUERY, CHANNEL ± mention).
- `XmppEventProcessor` notify: DM notifies; MUC mention notifies; MUC non-mention doesn't;
  self doesn't; `delayedAtMs != null` doesn't; dedup (null id) doesn't; present called once.
- XMPP `markReadLocal`: advancing the anchor clears the unread count and calls `onRead`.
- One env-gated live smoke against ejabberd.

## Deferred (explicit v1 limits)

- No observation-backed notification **recovery** for XMPP (a notification in flight when the
  process dies is not reposted on restart; IRC is). Full `event_observations(LIVE/HISTORY/PUSH)`
  parity is foundational to the history/MAM tier and is done there.
- Fool/friend on XMPP uses default `IrcIdentityRules` + nick match against the occupant nick
  (not JID-aware). Incidental, non-crashing.
- No wire read sync (XEP-0333) — read state stays device-local.
