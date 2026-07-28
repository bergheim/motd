# Backend-neutral IRC extraction and XMPP rollout

Date: 2026-07-28
Status: approved strategy, pre-implementation

This document records the delivery strategy for introducing a real protocol
backend boundary and then adding XMPP through that boundary. It is a
forward-looking rollout plan. `ARCHITECTURE.md` continues to describe the code
that exists today and must only be updated as each architectural change lands.

## Decision

The work is delivered as two stacked branches and two independently reviewable
pull requests:

```text
main
  |
  +-- backend-neutral-irc       PR 1: behavior-preserving IRC extraction
        |
        +-- xmpp-backend        PR 2: XMPP implementation
              |
              +-- later XMPP and MOTD work
```

The old `xmpp-support` branch at `b367893` remains unchanged as a successful
prototype and tested behavioral reference. It proved the protocol and exposed
the architectural constraints needed for the next implementation. Its commits
are not rebased or replayed onto current `main`. Its parallel router,
XMPP-specific Room writer, IRC-shaped connection state, and protocol switches
are explicitly rejected as the target architecture.

No commits are made directly on `main`. Branch 2 is always restacked after a
change to Branch 1.

Both branches are completed and validated before either pull request is
opened: Branch 2 must implement and test XMPP against the Branch 1 boundary,
all resulting interface feedback must be folded back into Branch 1, and the
maintainer must test the combined result. The abstraction PR is opened only
once the XMPP implementation has proven the boundary; the XMPP PR follows it.
If upstream declines either PR, the fork keeps the branches; the extraction
still minimizes the permanent fork delta.

## Scope classification

Every proposed change must be classified before it is assigned to a branch.

### Backend architecture

These are protocol-neutral MOTD changes prompted by XMPP:

- backend registry and lifecycle contracts;
- connection state and session identity;
- canonical incoming events and ingestion context;
- backend capabilities and optional operations;
- conversation, participant, and native-event identities;
- pending-send lifecycle;
- neutral inputs to notifications, typing presentation, history presentation,
  and read state;
- contract tests and boundary enforcement.

This work belongs in PR 1 even when an omission is discovered while building
XMPP.

### Protocol backend work

Wire behavior belongs to the backend:

- parsing and serialization;
- transport, authentication, connection, and reconnect behavior;
- capability discovery;
- protocol identity normalization;
- history queries;
- wire receipts, read markers, and typing messages;
- channel or MUC operations;
- encryption implementation.

IRC implementations stay in `:irc` or the app-side IRC adapter. XMPP
implementations belong in PR 2 or later XMPP-specific PRs.

### Shared MOTD behavior

User-visible policy remains protocol-neutral:

- timeline persistence, ordering, and deduplication;
- unread and local read state;
- notification policy and presentation;
- typing presentation;
- history paging and display;
- search, previews, attachments, drafts, replies, and reactions;
- later unified views such as Firehose.

A backend supplies neutral facts. Shared MOTD code decides how those facts are
stored and presented.

### Independent MOTD features

The following are not part of the first two PRs:

- Firehose;
- local-LLM notification summarization;
- unrelated notification redesigns;
- unrelated media, search, reaction, reply, or UI expansion.

Their existing branches may be used as behavioral references, but they will be
recut on top of the new architecture rather than rebased through the obsolete
XMPP implementation.

## PR 1: extract IRC behind the backend boundary

### Goal

PR 1 is a behavior-preserving refactor of current IRC MOTD. It must be useful
and mergeable without PR 2. After it lands, IRC remains the only production
backend, but another backend can implement the contracts without exposing
protocol types to shared code.

### Required boundary

The exact type names may change during implementation, but the ownership model
is fixed:

- An app-level backend registry resolves a persisted protocol discriminator to
  a backend. Shared code may perform this one registry lookup; it must not
  contain per-operation `IRC`/`XMPP` switches.
- The contracts are protocol-open, not an IRC/XMPP pair. Nothing in shared
  code enumerates the supported protocols; adding a third backend means a new
  adapter, a registry binding, and its own detail persistence, not edits to
  shared chat, notification, history, or connection code.
- A neutral backend/session contract exposes lifecycle state, commands, event
  streams, connection generation, and supported capabilities.
- Optional behavior is represented by small functional capabilities, not only
  boolean flags. Examples include history, read markers, typing, room
  discovery, encryption, raw protocol commands, DCC, and bouncer management.
- The app-side IRC adapter implements these contracts around the existing
  pure-JVM `:irc` engine. `:irc` must not depend on Android or `:app`.
- Shared types do not expose `IrcClient`, `IrcClientState`, `IrcEvent`,
  `Prefix`, `IrcMessage`, XMPP stanzas, or Smack types.
- Ingestion has two entry points: the live session and UnifiedPush delivery
  (`IrcEventSink.processPush`, `WebPushRegistrar`). Both are IRC-adapter
  surfaces behind the same boundary; the push path must not feed protocol
  events into shared code directly. PR 1 defines no neutral push contract;
  XEP-0357 arrives later with its own design.
- IRC-only surfaces such as DCC and bouncer administration may use IRC-specific
  capabilities inside clearly owned IRC feature packages. General chat,
  notification, history, and connection code may not.

### Remove the client escape hatch

The public `ConnectionManager.clientFor(networkId): IrcClient?` method must be
removed. Existing callers use it for several distinct purposes that must become
explicit contracts:

- connection generation and current-session checks;
- negotiated capabilities;
- persisted and live identity rules;
- server-history availability and requests;
- avatar support;
- push endpoint registration;
- DCC operations;
- bouncer operations;
- raw IRC commands and channel administration.

Wrapping `IrcClient` in another generic handle is not sufficient. Each caller
must request the operation or state it actually needs.

Identity behavior needs particular care. IRC casemapping is negotiated through
ISUPPORT but must continue to work while disconnected using persisted
per-network identity rules. Moving normalization behind the backend boundary
must not change connected or offline behavior.

### Persistence and writer ownership

`EventProcessor` remains the sole owner of IRC-derived Room writes. It may call
shared canonical repositories, but no second IRC path may write the same state.

This invariant generalizes instead of duplicating. Each backend has exactly
one processor that turns its wire events into canonical facts, and every
processor persists those facts only through the shared canonical
repositories; no backend adds a private Room write path. A network belongs to
exactly one backend, so chat-derived state keeps a single writer per network.
PR 2's XMPP processor follows the same rule: it is not a second writer for
IRC state and owns no tables of its own.

Canonical native identifiers must be backend-neutral. A third backend must not
require a new protocol-named alias such as `XMPP_MSGID`. Prefer a generic
backend-scoped identity containing the network/account, identifier namespace,
assigning authority when applicable, and opaque value.

The persisted protocol discriminator is the one schema change PR 1 is
expected to make: a migration that adds a protocol column to the network row,
defaulted to IRC, and nothing else. Its version number tracks whatever `main`
holds at freeze time; `20 -> 21` is the current expectation, not a contract.
Beyond that, PR 1 should not change the
Room schema unless a neutral canonical model genuinely requires it. Any PR 1
migration must not contain XMPP-specific names or semantics. PR 2 then uses
the next available schema version; it never assumes that it owns migration
`20 -> 21`.

Account rows follow the same rule. The existing IRC-shaped columns on the
network row (host, port, nick, SASL, and related fields) are grandfathered
and remain owned by the IRC adapter; moving them into a detail table is not
part of PR 1. No new protocol-specific columns are added to shared tables: a
backend persists its account and protocol detail in its own per-protocol
table keyed by the network row. PR 2 adds an XMPP detail table rather than
nullable `jid`-style columns on the shared row. Shared code outside the IRC
adapter must not read or write the grandfathered IRC columns.

### Scope limits

PR 1 contains:

- neutral contracts and models;
- the IRC adapter;
- caller migrations away from IRC escape hatches;
- neutralization of existing shared boundaries;
- tests and import-boundary enforcement;
- the protocol-discriminator migration and, only if required, further
  neutral schema changes.

PR 1 contains no:

- XMPP source or user-facing XMPP choice;
- Smack dependency;
- XMPP account columns;
- XMPP-specific enum values or schema;
- new product behavior;
- intentional IRC behavior or preference changes.

### Review structure

PR 1 remains one pull request by default, organized into reviewable commit
groups:

1. connection state, backend registry, capabilities, identity, and removal of
   `clientFor`;
2. canonical event, notification, typing, history, read, and pending-send
   boundaries;
3. contract tests, fake backend, architecture enforcement, and documentation.

If the measured diff becomes unreviewable, these groups may become stacked
sub-PRs with maintainer approval. That does not change the rule that all of
them precede the XMPP implementation.

### Acceptance gates

PR 1 is complete only when:

- all existing IRC behavior remains available;
- the complete documented FOSS release-parity build passes;
- required CI E2E passes on the branch, runnable from a fork push or manual
  dispatch before any PR exists;
- deterministic EventProcessor and canonical-timeline seeds produce the same
  normalized state as the recorded `main` baseline;
- shared packages have no forbidden IRC or XMPP imports;
- shared operations contain no per-protocol dispatch switches;
- a fake third backend passes the same contract suite, including variants
  with no history, typing, reactions, or other optional capabilities, and
  variants with semantics IRC lacks: own messages delivered from another
  session, distinct sender-supplied and archive-assigned identifiers, and
  out-of-order history pages;
- tests enforce the single IRC-derived Room-writer invariant;
- database migration tests pass from a real v20 fixture, covering the
  discriminator migration and any further PR 1 schema change.

The seeded fuzz generators and their versions stay frozen while PR 1 is in
flight so that identical seeds produce identical sequences on `main` and
Branch 1. If a generator must change, the `main` baseline is re-recorded
before comparisons continue. The baseline itself is reproducible rather than
an archived artifact: it is regenerated from the recorded Branch 1 base
commit with the same seeds and harness version.

The final local gate is:

```sh
nix develop .#native -c ./gradlew \
  :irc:build \
  :app:testFossDebugUnitTest :app:testFossReleaseUnitTest \
  :app:lintFossDebug :app:lintFossRelease :app:assembleFossRelease \
  --stacktrace --no-daemon --max-workers=1
```

Device E2E is not run routinely. The required CI gate owns headless E2E.

## PR 2: add the XMPP backend

### Goal

PR 2 proves the boundary by implementing XMPP without reopening shared
architecture. It is based directly on PR 1 and contains only the XMPP delta.

The old XMPP branch is used as:

- a wire-protocol and lifecycle reference;
- a source of Smack session behavior, stanza fixtures, address helpers, actor
  invariants, reliability fixes, and tests;
- a checklist of previously working user journeys.

It is not used as a source for the old routing decorator, IRC-shaped state,
protocol switches, independent Room-writing architecture, or protocol-aware
branches in shared UI. PR 2 is a reimplementation against the new seam, not a
rewrite from memory and not a commit-history replay.

### Baseline backend scope

The initial XMPP backend includes the proven baseline necessary to test it as a
real backend:

- TLS and SASL account login;
- actor-owned connection and reconnect lifecycle;
- roster loading;
- direct messages;
- MUC join, leave, occupants, subjects, and messages;
- durable pending sends and send acknowledgements;
- one-to-one typing where supported;
- account creation and edits;
- the minimum protocol-aware conversation and account UI;
- existing security, logging, race, reconnect, and late-confirmation fixes;
- room and gateway discovery already proven on the reference branch;
- fake-session, processor, actor, routing-contract, and stanza-fixture tests.

Protocol-aware UI is confined to protocol-owned surfaces such as account
setup and protocol-specific settings, reached through the registry and
capabilities. Shared conversation, chat-list, and notification UI stays
switch-free and renders capability and detail data instead.

XMPP persistence starts at the next schema version available after PR 1 and
includes dedicated migration and exported-schema tests.

### Feedback into PR 1

When PR 2 exposes a missing abstraction:

1. classify the change;
2. put shared interface, canonical model, IRC, or shared behavior changes on
   Branch 1;
3. complete and test that Branch 1 change;
4. restack Branch 2 on the updated Branch 1;
5. keep only the XMPP implementation on Branch 2;
6. rerun both branches' gates.

Boundary feedback is captured as Branch 1 contract tests, not only as
interface changes. When XMPP exposes a gap — sender-supplied versus
archive-assigned identifiers, own messages arriving from another session,
out-of-order history pages — the fake backend and contract suite grow a
variant that exercises it, so the merged PR 1 tests keep enforcing the
boundary upstream while the XMPP implementation is still unmerged.

A protocol switch or wire type appearing in shared code is evidence that PR 1
is incomplete, not justification for an exception in PR 2.

### Verification

PR 2 must pass:

- every PR 1 IRC regression and release-parity gate;
- XMPP unit and integration tests;
- migration tests from the exact parent schema;
- deterministic replay, deduplication, pending-send, reconnect, and
  deletion-race tests;
- live ejabberd login, direct-message, MUC, and acknowledgement tests;
- maintainer manual testing.

The baseline live gate asserts that reconnect re-establishes the session and
that messages flow again afterwards. It does not assert gapless delivery
across reconnects or multi-device consistency; those guarantees arrive with
MAM, carbons, and stream resumption in the cross-device follow-ups, and the
baseline is never presented as providing them.

The ordinary environment-gated live test may continue to skip in general CI.
A separate maintainer live-test command must fail, rather than skip, when its
required credentials are missing. A skipped test is not accepted as live-test
evidence.

No device installation is performed unless the maintainer explicitly requests
it. An APK may be built and handed to the maintainer for the manual gate.

## Stacked branch workflow

Use separate worktrees for Branch 1 and Branch 2 to avoid scope contamination.

While the branches are in flight, Branch 1 is rebased onto current `main`
routinely, not only before the pull requests open, and Branch 2 is restacked
immediately after every rebase. Drift against `main` is treated as a process
defect to fix when it appears, not at the end.

Before opening the pull requests:

1. update Branch 1 from current `main`;
2. finish all known interface feedback from Branch 2;
3. run PR 1's full gates;
4. restack Branch 2;
5. run PR 2's full gates and maintainer test;
6. perform an independent scope and architecture review of each diff;
7. freeze Branch 1 and record its base and tip commit IDs.

PR 1 targets `main`. PR 2 targets Branch 1 so its review diff contains only
XMPP.

If Branch 1 changes after review starts, its review and relevant gates must be
repeated, then Branch 2 must be restacked. Do not silently move a reviewed
parent.

After PR 1 merges:

1. use the recorded Branch 1 tip to restack PR 2 correctly for the merge method
   used by the repository;
2. update PR 2 onto the resulting `main`;
3. rerun migration and release-parity verification;
4. retarget PR 2 to `main`.

Do not assume a squash merge preserves ancestry. Record the parent tip so an
explicit `rebase --onto` remains possible.

## Work after the first two PRs

### XMPP cross-device release requirements

These are XMPP backend and shared-semantic follow-ups. They may be delivered as
stacked PRs after the baseline backend, but XMPP is not declared a proper
cross-device release until they are complete:

- XEP-0313 MAM catch-up and paged history with bounded windows and durable
  cursors;
- XEP-0280 carbons, including outgoing messages from another device;
- XEP-0359 stable stanza and origin identifiers;
- XEP-0198 stream management with resumption;
- XEP-0333 incoming and outgoing chat markers, including markers from another
  device belonging to the same account;
- XEP-0184 delivery receipts;
- XEP-0352 client state indication;
- XEP-0402 bookmarks with required compatibility fallback;
- XEP-0410 MUC self-ping;
- replay-safe notification and unread semantics.

MAM, carbons, and stream resumption must share a single gap/duplicate test
matrix. Ingestion context must explicitly distinguish live delivery,
background catch-up, initial synchronization, and user-requested history.
Delay stamps alone do not decide notification behavior.

No release should present XMPP as complete while these cross-device
requirements remain unfinished.

### Later XMPP capabilities

- OMEMO implementation, trust/device UI, key/session/prekey persistence,
  encrypted media, backup, and interoperability;
- XEP-0357 push as a wakeup path followed by resume or MAM synchronization;
- Slidge discovery, data forms, ad-hoc commands, and in-app linking;
- HTTP upload and other XMPP media capabilities.

The neutral models must not assume that message content is always available as
plaintext, but speculative OMEMO key/session tables are not added before the
OMEMO design and interoperability spike.

### Shared MOTD adaptations

Existing MOTD features are adapted to consume neutral backend facts:

- notification policy;
- local and remote read presentation;
- typing presentation;
- history UI and paging;
- search, previews, attachments, drafts, replies, and reactions.

These adaptations remain backend-neutral even when XMPP creates the first need
for them.

### Independent MOTD projects

Firehose and on-device notification summarization are separate product
projects. Firehose is recut from the new architecture. Summarization sits
behind a neutral `NotificationSummarizer`, uses a deterministic fallback, and
is not part of XMPP delivery.

## Working test for scope

Use this rule when ownership is unclear:

> Wire behavior belongs to a backend. User-visible policy belongs to MOTD.
> Abstractions required by both belong to the shared architecture.

PR 1 must merge independently as a pure IRC-preserving extraction. PR 2 must
then demonstrate that XMPP plugs into that boundary without introducing a
parallel stack or reopening shared architecture.
