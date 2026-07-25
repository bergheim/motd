# Firehose — unified cross-buffer window — design spec

Date: 2026-07-25
Status: approved design, pre-implementation
Stacked on: `xmpp-support` (this branch is `firehose`, based off it; its PR targets
`xmpp-support`, not `main`).

## 1. Goal

A read-only "Firehose" window that shows conversation lines from **every** channel and DM — both
IRC and XMPP — interleaved in reverse-chronological order, each line tagged with its conversation,
and each line tappable to open that conversation scrolled to that exact message. Modeled on Emacs
ERC's unified buffer.

## 2. Scope (v1)

**In:**
- Read-only merged stream: scroll + tap-to-jump. No inline compose.
- Content: conversation lines only — `PRIVMSG`, `ACTION`, `NOTICE`, and the user's own messages.
  Excludes join/part/quit/nick/mode/topic system lines and muted ("fool") users, reusing the same
  visibility rules the per-buffer timeline and search already apply.
- Entry: a top-bar icon on the chat list opens the Firehose screen; back returns to the list.
- Live-updating: new messages in any conversation appear at the top.

**Out (v1):** inline reply/compose, per-buffer filtering UI, unread/read-marker semantics for the
merged view, search within the firehose (global search already exists separately).

## 3. Architecture

The Firehose is **derived state** over the existing `messages` table — no new write path, no
materialized table. A single cross-buffer Paging query is the whole data source; both the IRC
`EventProcessor` and `XmppEventProcessor` already write every message into `messages`, so the
firehose is automatically complete and consistent, and Room's invalidation makes it live.

Reused infrastructure (verified in code):
- The per-buffer timeline is a Paging 3 `@RawQuery pagingSource(query: SupportSQLiteQuery)` on
  `messages`, fed a WHERE clause built by `MessageVisibilityPolicy`.
- `ChatRoute(bufferId, jumpToMsgid, jumpToTime, jumpToEventId)` implements deep-jump-to-message
  (used by search + notifications). The firehose reuses this exactly.
- `buffers.displayName` holds the prettified conversation name (including XMPP/Biboumi gateway
  pretty names).

## 4. Components

Each is small, single-purpose, independently testable.

- **`UnifiedRow`** (projection) — `id` (the `messages` row id / canonical event id), `bufferId`,
  `displayName`, `networkId`, `networkName`, `protocol`, `sender`, `text`, `kind`, `serverTime`.
- **`buildFirehoseQuery(spec, networks): SupportSQLiteQuery`** — pure builder for the cross-buffer
  SQL: `SELECT <cols> FROM messages m JOIN buffers b ON b.id = m.bufferId JOIN networks n ON
  n.id = b.networkId WHERE <conversation-kind IN-list> AND <network-scoped not-fool> ORDER BY
  m.serverTime DESC, m.id DESC`. See §5 for the fool clause.
- **`MessageDao.firehosePagingSource(query): PagingSource<Int, UnifiedRow>`** — a `@RawQuery`
  observing `MessageEntity`, mirroring the existing per-buffer `pagingSource(query)`.
- **`FirehoseViewModel`** — combines the fool spec + the network list (for casemap/fool scoping)
  into the query and exposes `Flow<PagingData<UnifiedRow>>` plus an empty-state flag.
- **`FirehoseScreen`** — a `LazyColumn` with **stable item keys = row id**, each row a compact
  `[tag] sender: text` line; the tag is `displayName` disambiguated by network when needed. Tap →
  `ChatRoute(bufferId, jumpToEventId = id, jumpToTime = serverTime)`. Includes an empty state. New
  rows arriving at the top must not jerk the viewport (reuse the per-buffer list's key/scroll
  discipline).
- **Nav** — a `FirehoseRoute`; a top-bar action on the chat-list screen.
- **v18 migration** — additive index on `messages(serverTime, id)` so the global reverse-chrono
  scan is index-served (the existing `(bufferId, serverTime, id)` index is bufferId-prefixed and
  cannot serve a cross-buffer order-by).

## 5. The network-scoped fool clause (the one non-trivial bit)

Fools are a per-network, casemapped mute set. A naive global `sender NOT IN (fools)` would wrongly
hide a nick on network B because they are muted on network A. The firehose WHERE clause therefore
composes **per-network** not-fool predicates and ORs them, each side using that network's own
casemap:

```
(  (n.id = :netA AND <not-fool-in-A predicate on m>)
OR (n.id = :netB AND <not-fool-in-B predicate on m>)
OR ... )
```

Networks with no configured fools contribute `(n.id = :netX AND 1)`. This reuses
`MessageVisibilityPolicy`'s existing per-network fool-predicate construction — one instance per
network, combined here rather than a single global set. Conversation-kind filtering stays a single
static `kind IN (...)` clause (protocol-agnostic).

## 6. Deep-jump identity

Tapping a row navigates with `jumpToEventId = row.id` — the canonical `messages` row id, which is
stable and non-null for every row (IRC and XMPP, own and foreign). `serverTime` is passed only as
the scroll anchor, never as identity. This mirrors the search deep-jump path exactly; the
implementation plan verifies own-message and XMPP rows resolve through it.

## 7. Ordering & invalidation

Order is `serverTime DESC, id DESC`; `id` breaks `serverTime` ties (serverTime is not globally
unique). `serverTime` is non-null. History sync can rewrite a message's `serverTime`; when it does,
the row may move in the firehose — an accepted behavior, not a bug. Every message insert
invalidates the Paging source (expected); stable item keys keep the `LazyColumn` from re-animating
unrelated rows, and top-insert must not move the viewport off the user's current read position.

## 8. Testing

- Pure builder tests for `buildFirehoseQuery`: conversation-kind filter keeps PRIVMSG/ACTION/own
  and drops JOIN/PART/QUIT/TOPIC; the network-scoped fool clause hides a fool on their own network
  but keeps the same nick on another network.
- Robolectric in-memory-DB test: seed messages across two buffers on two networks, run the query,
  assert reverse-chrono order and the correct rows filtered.
- `FirehoseViewModel`/row test: a row maps to `ChatRoute(bufferId, jumpToEventId = id,
  jumpToTime = serverTime)`.
- Migration 17→18 test in the existing migration-test style; commit the exported `18.json`.

## 9. Non-goals / limitations (stated, not hidden)

- No unread/mention accounting for the merged view (the per-conversation list keeps that).
- A very busy channel dominates the stream; per-buffer muting/filtering in the firehose is a
  future enhancement, not v1.
- No read-marker interaction: opening a line via deep-jump does not change read state beyond what
  entering the target conversation already does.
