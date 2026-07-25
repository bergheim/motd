# Bridge picker + IRC browse — design spec

Date: 2026-07-25
Status: approved design (discussed), pre-implementation
Builds on: the XMPP support already merged on `xmpp-support` (Smack seam,
`XmppEventProcessor`, `RoutingConnectionManager`, gateway-aware IRC joining).

## 1. Goal

Replace hand-typed bridge addresses (`#systemcrafters%irc.libera.chat@irc.xmpp.glvortex.net`)
with a discovery-driven flow: from **＋ (new conversation)** on an XMPP account, the user first
picks a **bridge** the server actually offers, then reaches conversations on that bridge the way
that bridge supports — browse-and-join for IRC, existing-contacts + type-to-join for slidge
gateways (Telegram/Signal/Steam).

The server already advertises every bridge via service discovery; today the app only surfaces the
IRC one. This feature surfaces all of them and tailors the second step per bridge kind.

## 2. Scope

**In scope (v1 of this feature):**
- Enumerate the account's components and classify each (native MUC, IRC/Biboumi gateway, slidge
  legacy gateway, other).
- A bridge-picker step in the new-conversation sheet listing those with human names.
- IRC bridge → server picker (recents + defaults, already built) → **channel browse** (list +
  filter) → join, plus the existing type-a-channel path. Never expose the `%@` address.
- Slidge bridge (Telegram/Signal/Steam) → show the user's **existing contacts/rooms already on
  that gateway** (scoped from the roster we already load) + a **type-to-join** field for a group
  address on that gateway.
- Pretty display names throughout (already implemented for `%`-rooms and `!`-DMs; extend to
  slidge contact JIDs).

**Explicitly deferred:**
- First-time bridge **registration/linking** (slidge ad-hoc command forms, entering a Signal
  linking code / Telegram login). Requires XEP-0050 ad-hoc command UI the client does not have.
  Until then, linking is a one-time task done from a desktop client (Gajim / jabber.el); once
  linked, contacts appear automatically. This limitation is stated in-UI, not hidden.
- Matrix gateway specifics beyond generic "gateway" treatment.
- Bridge management (unregister, settings) — out of scope.

## 3. Component classification

One disco#items on the account domain, then disco#info per item (this is the existing
`listIrcGateways` query with the IRC-only filter removed). Classify by the disco identity:

| Identity (category / type)         | Kind          | Second step                         |
|------------------------------------|---------------|-------------------------------------|
| `conference` / `text`              | NATIVE_MUC    | existing XMPP-room browse/join      |
| `conference` / `irc`               | IRC_GATEWAY   | server picker → channel browse/join |
| `gateway` / `signal\|telegram\|…`  | SLIDGE_GATEWAY| contacts list + type-to-join        |
| `gateway` / `matrix` (no slidge id)| OTHER_GATEWAY | type-to-join only (generic)         |
| anything else (pubsub/proxy/upload)| (hidden)      | not a conversation source           |

Produced as `data class GatewayInfo(val jid: String, val kind: GatewayKind, val name: String)`
at the `XmppSession` seam, threaded `session → actor → manager → XmppConnectionSurface → sheet`
exactly like `listIrcGateways` (single-flight + non-Ready cache invalidation already exist —
generalize that cache to hold `List<GatewayInfo>`). The account's own MUC service (`conference.*`)
and the account itself are always available even if disco is slow, so the picker shows a built-in
"XMPP rooms" entry without waiting.

## 4. UI flow (new-conversation sheet)

Step 1 — **bridge picker** (only when the selected network is XMPP and ≥1 non-native bridge
exists; a plain XMPP server with no bridges skips straight to today's behavior):

```
New conversation · <account> ▾
─────────────────────────────
 ⌨  XMPP rooms
 #  IRC            (Biboumi XMPP-IRC gateway)
 ✈  Telegram       (Telegram · slidge)
 ✦  Signal         (Signal · slidge)
```

Step 2 depends on the pick:
- **XMPP rooms** → today's room browse + type-a-JID (unchanged).
- **IRC** → server dropdown (recents/defaults, done) + **Browse channels** button and a channel
  field. Browse lists rooms from the gateway (see §5) with a live filter; tapping one joins.
- **Telegram/Signal/Steam** → two sections: **"Your <bridge> chats"** (roster contacts/rooms whose
  bare JID domain == the gateway JID, from the roster we already hold — no new round trip) and a
  **"Join by address"** field (e.g. a Telegram group id/link the gateway accepts), with a one-line
  note: *"New to this bridge? Link it once from a desktop client, then your chats appear here."*

All addressing is composed by the app. The user never types `%` or `@gateway`.

## 5. IRC channel browsing via Biboumi

The existing channel browser (`ChannelListViewModel`, MUC `listRooms`) is reused, but the room
source for an IRC bridge is Biboumi's channel list, which is served differently from a native MUC
service. **Implementation-time verification item (do not assume):** confirm against Biboumi's
current disco behavior whether the channel list comes from disco#items on the IRC-server JID
(`irc.libera.chat@<gateway>`) and whether it is paged/searchable. Libera has tens of thousands of
channels, so:
- The browse must be **query-driven** (send the filter to the gateway if it supports server-side
  search; otherwise cap the returned set and `log()`/surface "showing first N — type to narrow").
- A `couldn't load` must distinguish "gateway refused a full LIST" (ask the user to type a filter)
  from "not connected."

If Biboumi cannot serve a browsable list for a given network, the IRC step degrades gracefully to
the **type-a-channel** field (already built and pretty-named) — browsing is an enhancement, not a
precondition for joining.

## 6. Data model & display

- No schema change. Bridged rooms/contacts are still buffers on the XMPP account's network row
  (the single-writer `XmppEventProcessor` invariant is untouched).
- Display-name prettifying (in `XmppEventProcessor`, existing pure functions) extends to slidge
  contacts: a JID `<local>@<slidge-gateway>` shows `<local>` (or its roster name when present),
  not the gateway-qualified JID. Reuse the roster-name path already there for XMPP contacts.
- Recents already persist per network for IRC servers; add per-gateway "last joined address" only
  if it proves useful — not required for v1.

## 7. Testing

- Pure classification function `classifyGateway(identities): GatewayKind` — table-driven test over
  the real identities observed on the user's server (conference/text, conference/irc,
  gateway/signal, gateway/telegram, gateway/matrix) plus non-gateway components (must be hidden).
- Seam test: manager `listGateways(networkId)` returns the classified list from a fake session;
  single-flight and non-Ready invalidation covered by the existing gateway-cache tests, extended
  to the richer type.
- Sheet logic as pure functions (bridge list → picker rows; roster + gatewayJid → "your chats"
  filter; compose-join per kind) — no Compose needed to test them.
- IRC browse: the query-driven/capped path and the "refused → fall back to type-a-channel"
  degradation.
- Slidge roster scoping: contacts on the gateway domain are listed; contacts on other domains and
  the native account are not.

## 8. Honest limitations (stated in-UI, not buried)

- **Linking is not in-app yet.** A never-linked slidge bridge shows an empty "your chats" list with
  the desktop-linking note. This is the biggest gap and the natural next project (XEP-0050 ad-hoc
  commands).
- **No MAM/carbons still bites harder here.** Bridged traffic that arrives while the phone's socket
  is down is not backfilled on the phone (it stays in Signal/Telegram and reaches your desktop
  session). Bridged use makes server-side history the top follow-up.
- **No notifications yet** applies to bridged messages too.

## 9. Phasing

1. Generalize discovery + classification + the bridge picker + IRC browse/join (the address-typing
   killer — highest value, all on top of today's code).
2. Slidge contacts listing + type-to-join.
3. (Separate project) ad-hoc command support → in-app bridge linking, which makes the whole thing
   self-serve.
