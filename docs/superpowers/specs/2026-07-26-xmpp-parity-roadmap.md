# XMPP ↔ IRC Feature-Parity Roadmap

**Status:** planning reference. Records the parity gap between native XMPP and the
established IRC feature set, and the order we intend to close it. Grounded in a
five-domain code audit (history/lifecycle, presence/roster, notifications/chat-list,
media/rich-content, connection/rooms/commands) of the `xmpp-support` branch.

Each gap notes: what the user actually experiences, the root cause in code, rough size
(S ≤ ~1 day, M ≤ ~3 days, L multi-day), and any relevant XEP. This is a map, not a
contract — each tier still gets its own spec + plan before implementation.

## Principle

XMPP already inherits everything that lives in shared, buffer-agnostic storage / query /
UI: activity-sort, pin, mute, unread *computation*, mention-badge *rendering*, inline
image + link previews, linkification, the emoji picker, and the paste-service image
upload. Every remaining gap is a *behavioral hook* or *wire extension* that was only ever
wired for IRC. We close them by protocol-generalizing existing mechanisms, not by building
parallel XMPP-only stacks.

## Not XMPP's fault — out of scope here

Missing on **both** protocols (product-wide, track separately): message formatting (mIRC
colors / XEP-0393 / XHTML-IM), spoilers (XEP-0382), message edit (XEP-0308), retraction
(XEP-0424), custom emoji / stickers. Reaching parity does not require these.

---

## Tier 1 — Feels broken (ship first, one PR)

The "this app is half-finished on XMPP" tier. Notifications and read-markers are coupled
(reading a chat is what cancels a notification *and* clears its badge), so they ship
together.

| Gap | User experience | Root cause | Size |
|---|---|---|---|
| Notifications | XMPP DMs/mentions are fully silent when backgrounded | `XmppEventProcessor` never calls the injected `MessageNotifier` | S |
| Read markers never clear | Unread + mention badges only grow; open/scroll/"mark all read" do nothing | `RoutingConnectionManager.markRead` gates on `protocol == IRC` | S |

Design: `docs/superpowers/specs/2026-07-26-xmpp-notifications-readmarkers-design.md`.

---

## Tier 2 — Expected chat-app features that are absent

Ordered by value-to-effort. Each is its own PR.

### 2a. History / MAM (XEP-0313) — **L, highest value in this tier**
Today: no scrollback beyond locally stored rows; 1:1 messages received while offline are
permanently lost; reconnect misses 1:1 messages (stream resumption is off); MUC gets only
a ~50-message join backlog with no pagination. Fix: implement MAM archive queries — initial
catch-up on connect, paged backfill on scroll-up, and a reconnect catch-up keyed off the
last archived id. Touches the sync/paging path; the shared `history_cursors` machinery is a
model to follow. Pairs naturally with re-enabling XEP-0198 stream resumption (Tier 3) to
shrink the reconnect gap.

### 2b. Presence (available / away / dnd / xa / offline) — **M**
Today: Smack fires `presenceChanged` but we keep only `bareJid` + `name`; a contact's
online state is never stored or shown. Fix: carry presence mode/status on `RosterContact`,
persist to `UserEntity` (extend the existing `away` concept), surface in the chat/roster UI
the way IRC away state already is.

### 2c. Roster / contact management — **M**
Today: roster is read-only; can't add or remove a contact; incoming subscription requests
are silently dropped (can't even accept someone adding *you*). Fix: add/remove roster entry,
outgoing subscribe, and an approve/deny surface for inbound subscription requests.

### 2d. MUC invitations (XEP-0249 direct + mediated) — **M**
Today: room invites are dropped entirely — no event type, no listener, no handler; no way to
accept an XMPP room invite in-app. Fix: parse invites into the existing invitation model
(IRC already has `MessageKind.INVITE` + `onInvitation`/`onInvitationResolved` notifier hooks
to reuse) and render accept/dismiss.

### 2e. Moderation + blocked slash commands — **M–L**
Today: no set-topic, kick, ban, invite, or op/voice/affiliation; `/topic /nick /kick /ban
/away /whois /list` are rejected on XMPP at `ProtocolCapabilities.xmppAllowed`. Fix: add the
corresponding `XmppSession` methods (subject, kick/affiliation, role) and open the commands
per-protocol. Sub-scope aggressively — set-topic and kick are the common cases.

### 2f. Nick change — **S–M**
Today: XMPP nick is fixed at join; editing the account nick doesn't rename the live occupant
and only affects local rendering "until process restart". Fix: MUC nick-change (presence to
new nick) + roster nickname; make `/nick` protocol-aware.

---

## Tier 3 — Rich messaging (nice, not blocking parity)

| Gap | Note | XEP |
|---|---|---|
| Reactions | `reactions` table exists, XMPP never emits/parses | 0444 |
| Replies | `replyToMsgid` column exists, XMPP never populates | 0461 |
| Real avatars | Sprite-only today; fetch actual contact avatars | 0084 / vCard |
| Native file transfer + inbound OOB media | Paste-link upload already works; add first-class upload + render OOB-only media | 0363 / 0066 |
| Delivery + read receipts | True delivered/read state (shared row has only pending/failed) | 0184 / 0333 |
| Stream resumption | Reconnect is full re-login today; resume to shrink the gap (pairs with 2a) | 0198 |
| Server bookmarks / autojoin sync | Auto-join is local-DB only; sync rooms across clients | 0048 |

---

## Suggested sequence

1. **Tier 1** (notifications + read-markers) — in progress.
2. **2a History/MAM** + re-enable **3 stream resumption** — biggest correctness win.
3. **2b Presence** — high visibility, self-contained.
4. **2c Roster** and **2d Invites** — social basics.
5. **2e Moderation** / **2f Nick** — power-user parity.
6. **Tier 3** rich-messaging items as demand dictates.
