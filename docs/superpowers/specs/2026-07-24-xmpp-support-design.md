# XMPP support in MOTD — design spec

Date: 2026-07-24
Status: approved design, pre-implementation
Reviewed by: maintainer (section-by-section) + codex second-opinion reviews of Sections 1 and 2

## 1. Goal and scope

Add native XMPP support to MOTD alongside the existing IRC support. Both protocols coexist
permanently: existing IRC networks are untouched, and IRC and XMPP accounts share one unified
chat list.

**Milestone scope (v1):** login (TLS + SASL), roster, 1:1 chats, MUC group chats, and 1:1
typing indicators (XEP-0085) — live messaging only.

**Explicitly deferred:** MAM (server history), carbons, OMEMO, XEP-0444 reactions, XEP-0461
replies, HTTP file upload, push, XEP-0198 *stream resumption* (stanza acks are in scope), MUC
real-JID/affiliation metadata, XMPP in the onboarding flow.

**Known v1 limitation:** without carbons, messages sent from another client of the same account
do not appear in MOTD. Inbound 1:1 messages from any resource of a bare JID land in that JID's
single QUERY buffer.

**Non-goals:** no server-side gateway/bridge, no container fixtures, no emulator E2E. Live
verification happens against the maintainer's ejabberd at `xmpp.glvortex.net` (test accounts
`motd-test` / `motd-peer`, MUC via the server's conference service).

## 2. Approach

**Parallel vertical slice.** The IRC pipeline is not modified. A parallel XMPP stack —
`XmppConnectionManager` + `XmppEventProcessor` — mirrors the IRC invariants (sole writer of
XMPP-derived state, ordered event processing) and shares the same Room tables and UI. A
protocol abstraction is *not* built now; it may be extracted later once two concrete
implementations exist.

**Protocol layer: Smack.** Added to `gradle/libs.versions.toml`: `smack-android` (platform
initialization + minidns resolution), `smack-tcp` (XMPPTCPConnection), and `smack-extensions`
(MUC, chat states). Release minification is disabled repo-wide, so no proguard rules are
needed. Rationale: mature, MUC/roster
built in, and deferred XEPs (MAM, carbons, OMEMO) become incremental additions later. This is a
deliberate exception to the hand-rolled `:irc` philosophy, accepted by the maintainer.

## 3. Data model & migration (schema v15 → v16)

XMPP data lives in the existing tables, discriminated at the network level.

- `networks` gains `protocol TEXT NOT NULL DEFAULT 'IRC'` (`IRC` | `XMPP`) and nullable `jid`.
  XMPP account row mapping: `jid` = bare JID; `host`/`port` = server endpoint. For XMPP rows the
  existing `tls` boolean means *direct TLS* (`true`, default port 5223) vs *STARTTLS* (`false`,
  default port 5222); TLS is mandatory either way — plaintext XMPP is never offered. **New XMPP
  accounts default to STARTTLS on 5222** (the universal client-to-server mode); direct TLS is
  the advanced opt-in.
  `saslPassword` = account password
  (Smack negotiates SCRAM/PLAIN); `nick` = preferred MUC nickname / display handle;
  `role` = `DIRECT` always. Bouncer fields, `wsUrl`, and proxy/obfuscation columns remain
  NULL/IRC-only in v1.
- `buffers`: unchanged. `CHANNEL` = MUC room (`name` = room JID), `QUERY` = 1:1 chat
  (`name` = bare JID), `SERVER` = account status buffer.
- `messages`: unchanged. `msgid` carries the XMPP origin-id (stanza `id` fallback);
  `MessageKind.PRIVMSG` for chat, `JOIN`/`PART` for MUC occupancy, `TOPIC` for MUC subject.
- `users`: unchanged; `nick` = bare JID for roster contacts (protocol-local identity — call
  sites must not assume IRC nick display semantics).
- `members`: unchanged; `nick` = MUC occupant nickname, keyed `(bufferId, nick)` only.
- Migration 15→16 is purely additive `ALTER TABLE`; existing rows become `protocol='IRC'`.
  Documented and tested in the style of the existing migrations; schema export committed.

### Identity invariants (from review)

1. **Dedup identity:** origin-id (or stanza `id`) scoped by buffer + sender bare JID, stored in
   a new `XMPP_MSGID` event-alias namespace. The IRC `MSGID` namespace is never used for XMPP
   (XMPP client-generated ids are only unique per sender).
2. **JID policy:** all routing, identity, and persistence normalize to bare JID
   (XmppStringUtils). Resources are transient (presence/typing routing only) and never
   persisted as identity.
3. **Send confirmation:** 1:1 sends confirm via XEP-0198 ack; MUC sends confirm by matching the
   reflected message's origin-id. Both map onto the existing `pendingLabel`/`failed` columns;
   timeout marks `failed`. Unlike IRC there is no retry affordance in v1 — a failed send must be
   re-typed (XMPP rows always carry a msgid, so `isGenericRetryEligible` never admits them).
4. **MUC occupants:** `(bufferId, nick)` is the only occupant key (per-room, so no cross-room
   collisions). Real JID, affiliation, and role metadata are deferred.

Payoff: chat list, unread/mention badges, FTS search, drafts, and read anchors work for XMPP
buffers with no changes to those layers.

## 4. Connection lifecycle & service integration

**Routing decorator.** The UI keeps the single `ConnectionManager` interface (`ServiceSeam.kt`).
Hilt binds a new `RoutingConnectionManager` that delegates per call to the IRC
`ConnectionManagerImpl` or `XmppConnectionManager`, keyed by the target network's `protocol`.
Buffer-scoped calls resolve buffer → network → protocol at dispatch; if the row is gone the call
returns the existing `SendAcceptance.Rejected` path and **no pending row is created**.
`startAll`/`stopAll`/`reconnectStale` fan out to both managers. `connectionStates` merge into one
flow; Smack lifecycle maps onto the existing client-state type. No ViewModel changes.

**Account actors.** One actor per XMPP network row, reconciled against the DB wanted set.
Each builds a Smack `XMPPTCPConnection` from the `NetworkEntity`, logs in, and enables XEP-0198
**for send acks only — stream resumption disabled**. Smack's `ReconnectionManager` is disabled;
the actor owns reconnect using the app's existing backoff/connectivity/Doze conventions.

Lifecycle invariants (from review):

- All account-level listeners (stanza, presence, roster) are registered **before** `login()`;
  room listeners **before** `join()`. No missed-stanza windows.
- An account is **Ready** only after authentication *and* initial roster load; MUC joins happen
  after Ready and surface per-room via the existing `joined` flag.
- Every reconnect is a clean login + MUC rejoin. Unacked pending rows flip to `failed` (no retry
  affordance in v1 — must be re-typed), same as IRC's echo timeout; origin-id dedup collapses any
  edge-case replays.
- The wanted-MUC set is exactly the buffers with `joined=true`. Reconnect rejoins them and
  refreshes occupants wholesale. Explicit leave increments `membershipCycle`; reconnect rejoin
  does not.
- MUC join failure (banned, members-only, password required, nickname conflict, other presence
  error) writes an ERROR event to the buffer and clears `joined`, so reconnect cannot loop on a
  dead room; no auto-retry in v1. A kick behaves like IRC KICK: PART-style event, `joined=false`,
  `membershipCycle` increment.

**Threading.** Smack callbacks funnel into one Channel per account, consumed by a single
coroutine that invokes `XmppEventProcessor` sequentially — the ordered single-writer invariant.
Nothing else writes XMPP state.

**Send contract.** Identical to IRC: "accepted" means the pending timeline row (with generated
origin-id) is durable, not on-wire.

**Delivery mode.** XMPP accounts always use the persistent-socket path. If any XMPP account
exists, the foreground service stays up even when IRC uses `UNIFIED_PUSH` (no XMPP push/MAM in
v1; idle disconnect would silently drop MUC traffic). The "wants persistent" predicate becomes a
shared protocol-aware function used by both `BootReceiver` and the manager's push-idle teardown,
so boot behavior stays correct with XMPP accounts present. Revisit when MAM lands.

## 5. UI integration

- **Account setup:** `NetworkForm` gains a protocol selector. XMPP fields: JID, password, MUC
  nickname (defaults to JID localpart), advanced server override (host/port/direct-TLS).
  Onboarding remains IRC-only in v1 (deliberate cut; it is deeply soju-oriented).
- **Chat list:** unchanged.
- **New conversations:** `NewConversationSheet` becomes protocol-aware for XMPP accounts —
  "new chat" accepts a bare JID or roster contact, "join channel" accepts a room JID, with JID
  validation replacing channel-name validation.
- **Display names:** for QUERY buffers, `displayName` = roster name when present, else the bare
  JID; `XmppEventProcessor` updates it on roster events (the roster is the source of truth).
  Non-roster inbound senders display their bare JID until added to the roster.
- **Composer gating:** a per-buffer `ProtocolCapabilities` value (derived from the network's
  protocol, exposed by `ChatViewModel`) gates affordances.
  - Kept on XMPP: plain messages, `/me` (XEP-0245, client-rendered), MUC nick autocomplete
    (same `members` table), 1:1 typing indicators via XEP-0085 (Smack `ChatStateManager`).
  - Hidden on XMPP in v1: other slash commands, reactions, reply affordance (the wire cannot
    carry replies yet; showing the UI would mislead).
- **Info screens:** channel/member info reads the shared `members` table; IRC-only elements
  (modes, prefixes, hostmasks) hide for XMPP; roster contact info replaces WHOIS-derived detail.

## 6. Testing strategy

**Unit tests** (`:app:testFossDebugUnitTest`):

- `XmppEventProcessor` driven by literal stanza XML fixtures parsed with Smack's parser
  utilities: buffer creation, JID normalization, `XMPP_MSGID` dedup (duplicates/replays), MUC
  join/part/subject, pending→confirmed via ack and via MUC reflection, timeout→`failed`,
  deletion-race (no pending row for vanished buffers).
- Actor lifecycle against a fake `XmppSession` interface (thin wrapper over the Smack operations
  used), asserting the review invariants: listener-before-login ordering, room-listener-before-
  join, Ready-after-roster, clean-login reconnect flipping unacked rows to `failed`, MUC rejoin
  from the `joined=true` set.
- `RoutingConnectionManager`: protocol dispatch, fan-out, state merging, rejection on deleted
  rows.
- Migration 15→16 test in the existing style; schema export committed.
- ViewModel/UI logic: capability gating, JID validation, `NewConversationSheet` XMPP behavior.
- A modest seeded case generator for `XmppEventProcessor` joins the existing fuzz infrastructure
  at the PR profile.

**Live verification** against `xmpp.glvortex.net`:

1. Env-gated JVM integration tests (real Smack ↔ ejabberd as `motd-test`, roundtrips with
   `motd-peer`); credentials from environment variables; **self-skip when unset** so CI and
   other environments are unaffected.
2. A peer-driver script (dev tooling, container-side) that sends/echoes as `motd-peer` and joins
   the MUC, used to verify each implementation phase against the real server.

**Command matrix:** routine iteration runs app unit tests + FOSS lint/assembly. Because this
work touches the version catalog and Compose surfaces, the milestone completes only after the
full release-parity Gradle command in `.agents/testing.md` passes. Required CI is unchanged; no
emulator E2E.

## 7. Security notes

- XMPP account passwords are stored in the existing `saslPassword` column (same trust model as
  IRC SASL credentials today) and redacted from `toString()` like other secrets.
- The `motd-test`/`motd-peer` credentials are throwaway; they appeared in shell history and
  chat and must never be reused for real accounts. Rotate via `ejabberdctl change_password`
  at will.
