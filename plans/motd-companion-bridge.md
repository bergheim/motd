# Motd Companion Bridge Protocol and XMPP Provider

## Decision

Build a public Android companion-provider protocol with two layers:

1. A small Binder control plane for discovery, mutual pairing, account selection, lifecycle, wakeups, provider-owned UI, and opening a byte stream.
2. Standard IRCv3 lines over a full-duplex `ParcelFileDescriptor` for all chat traffic.

Ship motd integration behind Labs. Build XMPP as a separate, management-only companion derived from Conversations. Keep SDK/spec and XMPP provider in two new repositories. Pin SDK source into both consumers as a Git submodule while API remains experimental.

This preserves motd's architecture: `IrcClient` still parses one IRC protocol, `ConnectionManager` still owns sessions and actions, and `EventProcessor` remains sole IRC-to-Room writer.

## Research findings

### Feasibility

Android supports cross-app bound services, explicit component binding, package visibility declarations, and full-duplex socket pairs carried through Binder. A bound provider can live while motd holds its session without exposing a localhost port.

- Bound services: <https://developer.android.com/develop/background-work/services/bound-services>
- Package visibility: <https://developer.android.com/training/package-visibility/declaring>
- `ParcelFileDescriptor.createReliableSocketPair()`: <https://developer.android.com/reference/android/os/ParcelFileDescriptor>

### Existing gateway options

BitlBee is closest existing IRC-fronted XMPP gateway, but unsuitable for selected Modern core. It lacks OMEMO and does not advertise IRCv3 message tags, batch, message IDs, replies, reactions, or `CHATHISTORY`. Porting its native daemon to Android would retain those protocol gaps.

- BitlBee: <https://www.bitlbee.org/>
- Current CAP implementation: <https://raw.githubusercontent.com/bitlbee/bitlbee/master/irc_cap.c>

Smack is reusable on Android and supports MAM, stream management, carbons, chat markers, and some retraction support. Current stable APIs do not provide complete modern replies/reactions, and documented OMEMO support targets legacy namespaces rather than current OMEMO 2. A fresh Smack app would require more XMPP protocol work than the sidecar itself.

Conversations already implements mature Android lifecycle, MAM, carbons, stream management, OMEMO, replies, reactions, retractions, files, and account/security UI. It is GPLv3 and monolithic, so reference provider should remain a maintained fork with sidecar changes isolated rather than pretend Conversations is a reusable library.

- Conversations source: <https://codeberg.org/iNPUTmice/Conversations>
- XMPP client profile: <https://xmpp.org/software/conversations/>
- Modern XMPP guidance: <https://docs.modernxmpp.org/client/protocol/>

### Protocol mapping

Modern XMPP features have direct IRCv3 representations or bounded adapters:

- MAM -> `draft/chathistory` batches with `server-time` and `msgid`
- XEP-0461 replies -> `+reply`
- XEP-0444 reactions -> `+draft/react` / `+draft/unreact`
- XEP-0424 retractions -> `draft/message-redaction` / `REDACT`
- XEP-0333 displayed markers -> `draft/read-marker` / `MARKREAD`
- XEP-0085 chat states -> `+typing` `TAGMSG`
- roster and MUC presence -> IRC users, `MONITOR`, `JOIN`, `PART`, `NAMES`, and away notifications
- friendly names and avatars -> `draft/metadata-2` keys `display-name` and `avatar`

Relevant specifications:

- Replies: <https://xmpp.org/extensions/xep-0461.html>
- Stable IDs: <https://xmpp.org/extensions/xep-0359.html>
- Reactions: <https://xmpp.org/extensions/xep-0444.html>
- Retractions: <https://xmpp.org/extensions/xep-0424.html>
- Displayed markers: <https://xmpp.org/extensions/xep-0333.html>
- IRC metadata: <https://ircv3.net/specs/extensions/metadata>

### Signal and app bridges

Protocol can host future providers, but Signal is not a first reference provider. Signal exposes no supported third-party client API. `signal-cli` is unofficial, fast-moving, and not an Android-supported deployment target. A future Signal provider should connect to a maintained self-hosted `signal-cli` bridge and carry explicit unsupported/ToS warnings. Notification-listener mirroring can offer read/reply-only access to other apps but cannot provide durable history or complete semantics.

## Repository layout

### `motd-sidecar-sdk`

Apache-2.0 repository containing:

- Android AIDL API and constants
- JSON schemas and validators for provider/account/action descriptors
- companion IRCv3 profile specification
- provider conformance testkit
- fake provider fixture for consumer tests
- Nix flake, lock file, and `.envrc`

Use API version `1` under an experimental `0.x` repository release. Consumers pin an exact submodule commit. Defer Maven Central publication until Labs graduation.

Namespace: `io.github.trevarj.motd.sidecar`.

### `motd-xmpp-sidecar`

GPLv3 Conversations-derived repository containing:

- provider service and IRC facade
- sidecar alias/identity tables
- management dashboard
- retained Conversations account, room/contact, OMEMO trust, upload, and diagnostics flows
- no normal chat surface in launcher navigation
- upstream Conversations remote and documented merge workflow
- Nix development environment and reproducible source build

Prototype is source-only. APK publication and F-Droid submission remain outside prototype scope.

### `motd`

Add SDK as `third_party/motd-sidecar-sdk`, pinned to exact commit. Keep `:irc` pure JVM. Android Binder/PFD implementation belongs in `:app` transport/service boundary.

## Companion API v1

### Discovery

Providers export one service with action:

```text
io.github.trevarj.motd.sidecar.PROVIDER
```

motd declares an intent-based `<queries>` entry, resolves services, and always binds by explicit `ComponentName`. Service label and icon come from `PackageManager`; provider metadata declares API min/max only.

### AIDL control plane

Use primitives and platform parcelables. Keep extensible metadata in bounded UTF-8 JSON, not custom cross-version parcelables.

```aidl
interface IMotdSidecarProvider {
    int getApiVersion();
    String getProviderInfoJson();
    List<String> getAccountsJson();
    Intent createUiIntent(String action, String accountId, String requestJson);
    ParcelFileDescriptor openSession(String accountId, String optionsJson);
    void setWakeIntent(String accountId, in PendingIntent wakeIntent);
    void clearWakeIntent(String accountId);
}
```

Contract rules:

- `getProviderInfoJson()` and pairing intent creation are available before pairing.
- Account listing, session opening, wake registration, and account actions require paired caller identity.
- `accountId` is opaque, stable on that provider installation, non-secret, and at most 256 UTF-8 bytes.
- JSON inputs and outputs have explicit schema versions, field/collection size ceilings, required-key validation, and unknown-key tolerance.
- Service errors use documented `ServiceSpecificException` codes.
- Returned UI intents must target an explicit activity in provider package.
- No credentials, OMEMO keys, message bodies, or history cross control plane.

Provider UI actions:

```text
pair
manage_account
pick_target
manage_security
send_attachment
```

`pick_target` returns target kind, IRC-safe wire target, and display label. `send_attachment` receives one `content://` URI through `ClipData` with temporary read permission; provider copies/stages content before finishing, handles upload/encryption, then emits resulting message through IRC session. motd revokes grant after result. Multiple attachments remain out of scope.

### Pairing and trust

Use mutual explicit approval, not a shared signature permission, because public providers use unrelated signing keys.

motd side:

1. Show provider app label, package, service, signing certificate digest, and API compatibility.
2. User explicitly selects Pair.
3. Launch one-time provider pairing activity returned from Binder call.
4. On success, persist exact service component and signer digest in backup-excluded `SidecarTrustStore`.
5. Before every bind, verify component and signing lineage. Accept valid Android key rotation whose history contains pinned signer; require reapproval for unexpected replacement.

provider side:

1. Pairing request is bound to Binder caller UID, package, and signing identity.
2. Provider UI shows requesting app identity and asks user.
3. Provider stores approved caller signer and rejects protected calls from every other caller.
4. Unpairing revokes sessions and wake intents.

Installed-app discovery alone grants no account access. A malicious provider remains equivalent to a malicious configured chat server, so motd labels provider identity and never auto-pairs.

### Session stream

Provider creates `ParcelFileDescriptor.createReliableSocketPair()`, returns one endpoint, and owns other. Stream is full-duplex IRCv3 text with CRLF framing.

motd's `SidecarTransport`:

- binds and verifies provider
- opens selected account session
- duplicates descriptor for independent Okio source/sink lifetime
- enforces existing 16 KiB defensive line ceiling
- serializes writes
- maps reliable-socket errors, Binder death, EOF, uninstall, and unpairing to ordinary connection failure
- closes descriptors and unbinds on cancellation/connection close

Provider replacement closes prior session for same caller/account. Existing `ConnectionActor` reconnect/backoff handles provider death without a second lifecycle system.

## IRCv3 Sidecar Profile v1

Every session behaves as a local IRC server. Mandatory registration:

- `CAP LS 302`, `CAP REQ`, `CAP END`
- `NICK`, `USER`
- welcome numerics `001`, `005`, end-of-MOTD
- vendor marker `trevarj.github.io/sidecar=1`

Core provider profile requires:

- `message-tags`
- `server-time`
- `batch`
- `labeled-response`
- `echo-message`
- `standard-replies`
- basic `PRIVMSG`, `NOTICE`, `TAGMSG`, `JOIN`, `PART`, `NAMES`, and `TOPIC`

Everything else remains normal CAP negotiation. Durable providers add `draft/chathistory`, `draft/event-playback`, and `draft/read-marker`. Relations use message tags and `draft/message-redaction`. Friendly identity and security state use `draft/metadata-2`. Provider must advertise only behavior it implements; motd keeps existing degradation when caps are absent.

Wake registration is allowed only for durable providers. Conformance testkit validates registration, CAP ACK/NAK behavior, line bounds, labels/echoes, history framing, metadata, disconnect/error handling, and malformed input rejection.

## motd implementation

### Labs and persistence

Add backup-excluded `SidecarPrefs`, default false. Labs toggle controls discovery and operation.

When disabled:

- disconnect all sidecar networks
- clear provider wake registrations
- preserve network rows and history
- show chats/settings as disabled rather than deleting them
- block new sends with direct route back to Labs

Database migration from current schema adds nullable/default-safe fields:

`NetworkEntity`:

- transport kind, default ordinary network
- provider package and service class
- opaque provider account ID

`RoomEntity`:

- nullable `wireTarget`; existing rows continue using `displayName`
- nullable current sidecar security state

`TimelineEventEntity`:

- nullable per-message sidecar security state

`RoomEntity.ircTarget` becomes `wireTarget ?: displayName`. Existing IRC rows remain byte-for-byte compatible. Sidecar rooms keep stable IRC-safe target separately from mutable Unicode display label.

Signer pins and pairing state live outside Room and configuration backup. Configuration export may identify provider package/type but strips account ID and trust. Restore creates disabled unresolved sidecar network requiring local Labs enablement, pairing, and account selection.

### Connection path

`ConnectionManagerImpl.buildConnection` selects sidecar transport when network transport kind is sidecar. It skips TCP/TLS/STS/proxy/client-cert preparation but builds same `IrcClient`. Host/port placeholders never reach network.

All incoming data still follows:

```text
SidecarTransport -> IrcClient -> IrcEvent -> EventProcessor -> Room
```

All outgoing chat actions still follow `ConnectionManager`. No provider writes Room, calls repositories, or injects semantic events over Binder.

### Provider setup UI

Behind Labs, Add network gains Companion provider:

1. Discover compatible providers.
2. Pair selected provider.
3. List provider-owned accounts.
4. Select account and create one motd DIRECT network per provider account.

Sidecar network settings hide endpoint/TLS/SASL/proxy fields. Show provider identity, account label, connection state, Manage account, Re-pair, and Remove.

New conversation launches provider `pick_target`; returned channel sends `JOIN`, returned person creates/opens query. Provider owns roster search, MUC discovery, account setup, and OMEMO trust UI.

### Display names, avatars, and security

Extend existing Metadata support:

- subscribe to `display-name`, `avatar`, and `trevarj.github.io/sidecar/security`
- preserve safe wire target as protocol identity
- store Unicode display label independently
- resolve message sender display from metadata while retaining stable normalized actor
- keep current AvatarCoordinator path for avatar URLs

Provider attaches `trevarj.github.io/sidecar/security` tag to each message/history row and publishes current outbound conversation policy through metadata. Values:

```text
plaintext
e2ee-unverified
e2ee-verified
blocked
```

motd shows current state in chat header and per-message exception where needed. Security details and verification launch provider `manage_security` UI.

OMEMO terminates in provider. Plaintext crosses private kernel IPC and is stored in motd's ordinary Room history. UI/documentation must say "encrypted to this device," not imply encrypted motd storage. Encrypted-at-rest Room work is explicitly outside prototype.

### Background wake and pull

motd creates one immutable, fixed-data `PendingIntent` targeting non-exported `SidecarWakeReceiver` per sidecar network and registers it with provider.

Provider contract:

- commit incoming state durably first
- coalesce wake calls
- invoke pending intent with no payload
- clear invalid token on `CanceledException`
- resume provider-owned notification fallback when no valid wake token exists

motd receiver:

- ignore when Labs disabled or network unpaired
- coalesce by network
- bind/open local session
- register and run bounded single-network CHATHISTORY catch-up through existing EventProcessor/notification policy
- cap receiver work below Android broadcast execution window
- leave unfinished backlog for next wake or app foreground; durable history prevents loss

Disabling Labs/unpairing clears wake intent so provider resumes fallback notifications. Live sessions use normal socket path and do not need wake processing.

### Attachment upload

For sidecar rooms, attachment sheet offers Send with provider under Labs. It launches explicit pinned provider activity with one granted URI, target, MIME type, filename, and optional caption. Provider owns progress, XMPP HTTP Upload, OMEMO file encryption, retries, and error UI. Timeline changes only when provider emits IRC echo.

Keep existing motd upload-to-URL flow as alternative. Defer generic upload progress IPC and multi-file transfer.

## XMPP reference provider

### Product surface

Use Conversations-derived implementation but expose management shell:

- accounts and connection health
- contact/MUC picker
- account and room settings
- OMEMO device/fingerprint trust
- attachment upload progress
- diagnostics and pairing status

Hide normal conversation list/composer from launcher navigation. Keep upstream internal UI/code where removal would complicate merges; product routes expose management only. Suppress duplicate chat notifications while valid motd wake token exists, with automatic fallback when token is absent/cancelled.

### Identity mapping

One XMPP account equals one motd network.

Persist provider-side mapping from XMPP identity to IRC-safe alias:

- contacts/occupants -> deterministic collision-safe nick aliases
- MUCs -> deterministic `#` channel aliases
- self -> stable safe nick
- mutable roster/room names -> Metadata `display-name`
- avatars -> Metadata `avatar`

Do not rely on reversible aliases or leak JIDs in IRC account tags. Keep provider account IDs opaque.

### Feature mapping

- Conversations DB and MAM state back `CHATHISTORY` requests; no network MAM request is required per motd page.
- Use MUC stanza ID, server stanza ID, then scoped origin ID as stable `msgid` precedence.
- Preserve mappings between origin/stanza IDs so replies, reactions, retractions, echoes, and later MAM copies deduplicate.
- XEP-0461 <-> `+reply`.
- XEP-0444 reaction-set updates <-> react/unreact tags.
- XEP-0424 <-> `REDACT`; never promise remote erasure.
- XEP-0085 <-> typing states.
- XEP-0333 marker IDs resolve to newest local message at/before IRC marker timestamp; updates remain monotonic.
- MUC membership/presence <-> JOIN/PART/NAMES/away.
- Carbons and stream management remain provider-internal.
- OMEMO policy remains provider-owned. Reject prohibited plaintext sends with Standard Reply instead of silently downgrading.
- HTTP Upload and encrypted-file handling remain provider-owned attachment action.

Message editing is deferred. Incoming XEP-0308 corrections become explicit reply-linked "edited" fallback events instead of mutating old motd rows. Proper edit support needs separate IRC mapping, Room mutation/FTS semantics, composer UI, and Labs design.

## Creative provider opportunities

Protocol intentionally supports more than XMPP without changing motd's data path:

- self-hosted bridge connector: provider authenticates to remote IRC facade and passes session through Binder
- Matrix: matrix-rust-sdk provider mapping rooms, relations, receipts, and history
- notification mirror: allowlisted Android apps exposed as best-effort read/reply chats through NotificationListener and RemoteInput, with no fake history claim
- Signal: remote maintained `signal-cli` connector only; mark unofficial and capability-limited
- Delta Chat/email: threads as channels, correspondents as queries
- Meshtastic/BLE mesh: channels and nodes over local radio
- MQTT/Home Assistant: topics/entities as rooms for alerts and controlled commands
- ntfy: topics as channels with publish support
- Forgejo/GitHub: issues, PRs, reviews, and CI runs as structured chat rooms
- Termux/local automation: command sessions or service logs, gated by explicit provider permissions

These are future providers, not SDK v1 requirements. Standard CAP negotiation exposes honest feature loss.

## Validation

### SDK

- AIDL cross-process tests on min and target Android APIs
- pairing authorization and signer mismatch/rotation tests
- bounded JSON schema and malformed payload tests
- full-duplex reliable socket, EOF, `closeWithError`, Binder death, cancellation, and write-serialization tests
- fake provider conformance for Core and Durable profiles
- IRC malformed-line, oversized-line, CAP, label, batch, and history cases

### motd

- Room migration preserves all existing IRC networks, rooms, history, and wire targets
- backup restore leaves sidecar accounts unresolved and trust absent
- provider discovery/API mismatch/missing package tests
- explicit pairing and signer pin tests
- transport process-death/reconnect tests
- Labs disable preserves data, blocks sends, disconnects, and clears wakes
- display-name/wire-target separation tests
- security metadata/message-tag persistence and UI tests
- duplicate wake idempotency, bounded catch-up, and provider fallback tests
- attachment URI grant, cancellation, explicit component, and revocation tests
- cross-package instrumentation fixture proving real Binder/PFD/PendingIntent behavior
- hosted component/headless journeys updated for sidecar Labs path; no routine local emulator run

### XMPP provider

Use Prosody test stack with MAM, carbons, PEP, MUC, HTTP Upload, and required XEP modules. Validate:

- plain and OMEMO DMs/MUCs
- roster names, avatars, presence, joins, and parts
- cold MAM catch-up and no duplicate carbons/history
- replies, reactions, retractions, typing, and read markers
- stable IDs across reconnect and provider process death
- attachment upload, OMEMO file handling, and echo into motd
- no plaintext downgrade when encryption policy forbids it
- wake after durable commit and fallback notification after token cancellation
- unpaired caller rejection and paired signer enforcement

### Prototype exit criteria

Prototype is complete when one hosted Android journey installs motd plus management-shell XMPP provider, enables Labs, pairs account, then proves:

1. OMEMO DM send/receive and MAM recovery after both app processes restart.
2. MUC join/history plus reply, reaction, retraction, typing, and read marker mapping.
3. Provider-native encrypted attachment upload.
4. Sidecar wake causes motd notification without duplicate provider notification.
5. Killing provider produces ordinary disconnect and automatic reconnect without data loss.
6. Independent fake provider passes same public SDK contract, proving API is not XMPP-specific.

## Explicit non-goals

- message edits in motd v1
- voice/video/Jingle, calls, location, or multi-attachment transfer
- direct integration with existing Signal app
- semantic message/event AIDL
- localhost TCP listener
- sidecar credentials in motd
- encrypted motd Room database
- self-hosted bridge implementation in XMPP prototype; public protocol permits one
- Maven Central, public APK, or F-Droid release during source prototype

## Implementation order

1. Create `motd-sidecar-sdk`: API v1, spec, validators, fake provider, conformance tests.
2. Pin SDK into motd; implement trust, Labs, persistence migration, transport, provider/account setup, metadata, wake/pull, and attachment action against fake provider.
3. Create Conversations-derived management-shell provider; add IRC facade and XMPP mappings incrementally: registration/DM, rooms/presence, durable history, relations/read state, security, attachment, wake.
4. Run cross-package hosted Android and Prosody interoperability suites; fix one bounded conformance/review cycle.
5. Stop at source-prototype exit criteria. Stabilization, package publication, and distribution require separate approval after evidence from prototype.
