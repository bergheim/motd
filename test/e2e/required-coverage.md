# Headless journey coverage map

The tiers deliberately separate deterministic state logic, fixture-free rendered components, a
compact required real-stack gate, and a broad manual choreography sweep.

| Behavior and state class | Continuous evidence | Broad or specialized evidence |
| --- | --- | --- |
| protocol parsing, playback mapping, canonical storage, ordering, and dedup | seeded JVM tests in `:irc:test` and `:app:testDebugUnitTest` | larger nightly generated-test shards |
| message bubbles, actions, audio, replies, placeholders, sync/error states | fixture-free Robolectric cases in `app/src/testDebug`, including full-list variants and Paging replacement | phases C, G, and conditional H |
| chat-list badges, presence, selection, archive, drawer, and empty states | Robolectric component tests | phases B and I |
| onboarding, TLS trust, Soju import, child readiness | required real-stack onboarding journey | phase A |
| send/echo persistence, reconnect identity, and audio upload rendering | required real-stack canonical-send journey | phases C and R |
| unread entry, divider, Paging-window placement, marker timing, ordering, and exactly-once recovery | required real-stack unread-history journey | phase R |
| composer, autocomplete, actions, reactions, replies, search, and scroll FAB | component/unit tests | phase C |
| channel info, membership, friends/fools, pin/mute, and part confirmation | unit tests | phase D |
| channel browse, search, join, and empty/loading/error states | component/unit tests | phase E against a registered browser-only fixture channel |
| invitation discovery without notifications, accept/ignore actions, and retained resolved state | component/DAO tests | phase V through a direct Ergo sender and Soju downstream |
| settings, theme, palette, density, backup controls, and process-stable theme content | component/unit tests plus required navigation smoke | phases F and G |
| Soju child/control-center routing and authorization-dependent panels | required navigation smoke plus unit tests | phase J and socket control probe |
| ZNC SASL, two-client routing, detached gaps, native playback, and CHATHISTORY degradation | protocol/client unit tests | scheduled `znc-stack.sh probe` job |
| process restoration, saved viewport, deep links, notification routing, and read anchors | deterministic ViewModel/repository tests plus warm notification/recreation in the unread-history journey | phase R for reconnect restoration; physical phase K for delivery-only behavior |

Physical-only behavior remains outside headless claims: OEM notifications, Doze, system pickers,
real certificates, GPU/rendering performance, and release install upgrades.

The required API34 gate discovers exactly the four methods in
`RequiredHeadlessE2eTest`. It is intentionally narrow; the exhaustive host UIAutomator runbook
remains manual-only.

| Removed broad assertion surface | Destination |
| --- | --- |
| onboarding, TLS trust, soju import | `onboardingTrustsEphemeralTlsAndImportsNetwork` plus onboarding reducer tests |
| retained-history backfill and mention/non-mention presentation | onboarding journey’s imported ready child/chat-list proof; deterministic CHATHISTORY/EventProcessor and mention-presentation tests |
| 18-send layout and coordinate anchor | layout preferences/component and keyed-anchor unit tests; long-list pixels remain manual UIAutomator |
| join, send, search, completion, command, `/me` | one canonical UI send in `sendEchoPersistsVisibleRowAndReconnects`; composer/parser/ViewModel/EventProcessor tests |
| replies, reactions, drafts | deterministic delivery, preview, mutation, canonical, and draft tests; choreography remains manual |
| reconnect duplicate/new second send | one canonical row before/after reconnect plus deterministic echo/canonical/resync tests |
| first-unread viewport and long offline gap | `unreadHistoryEntersAtMarkerAndRemainsCanonical` seeds 260 incoming rows through a second Ergo client, proves the bounded 49-row catch-up entry and marker placement, then pins scroll-driven paging — a bounded two-to-three-page automatic backfill on open (49→149..199 rows, halted by Paging's initialLoadSize, with the timestamp-only catch-up gap kept recoverable), then deliberate boundary scrolling — down to the oldest row, proving canonical order, exactly-once recovery, deliberate advancement, notification deep-jump, Activity recreation, and stable reopen behavior |
| channel/member/friend/fool sheets | ViewModel/presentation/repository tests; manual UIAutomator |
| settings and bouncer panels | `bootstrappedNavigationSettingsAndBouncerSmoke` plus preferences/catalog/model/ViewModel tests |
| verified BouncerServ account/channel/console capability discovery | navigation/bouncer smoke after root readiness; BouncerServ command/session/model tests retain protocol and authorization detail |
| host reconnect phase R / 40-row gap | EventProcessor/resync/canonical deterministic tests and manual ZNC/runbook gap validation |

No assertion is removed without one of these destinations. Host UIAutomator cannot acquire
`@FastHeadlessE2e`.
