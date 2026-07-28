package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.VoiceSendRequest
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.e2e.BootstrappedNetwork
import io.github.trevarj.motd.e2e.BufferProbe
import io.github.trevarj.motd.e2e.ConnectionProbe
import io.github.trevarj.motd.e2e.E2eBootstrap
import io.github.trevarj.motd.e2e.E2eFailureArtifactRule
import io.github.trevarj.motd.e2e.E2eMilestoneRecorder
import io.github.trevarj.motd.e2e.FixtureIrcClient
import io.github.trevarj.motd.e2e.HistorySyncProbe
import io.github.trevarj.motd.e2e.MessageLifecycleProbe
import io.github.trevarj.motd.e2e.MessageRunProbe
import io.github.trevarj.motd.e2e.ScenarioHolder
import io.github.trevarj.motd.e2e.TimelineDiagnostics
import io.github.trevarj.motd.e2e.robots.BouncerRobot
import io.github.trevarj.motd.e2e.robots.ChatListRobot
import io.github.trevarj.motd.e2e.robots.ChatRobot
import io.github.trevarj.motd.e2e.robots.OnboardingRobot
import io.github.trevarj.motd.e2e.robots.SettingsRobot
import io.github.trevarj.motd.e2e.robots.ThemeSheetRobot
import io.github.trevarj.motd.e2e.robots.TimelineRobot
import io.github.trevarj.motd.e2e.robots.NetworksRobot
import io.github.trevarj.motd.service.MotdNotifications
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.TestClass

/** Marks the real-stack, isolated journeys required by the headless API34 gate. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FastHeadlessE2e

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class RequiredHeadlessE2eTest {
    private val milestones = E2eMilestoneRecorder()
    private val scenario = ScenarioHolder()
    private val artifacts = E2eFailureArtifactRule(scenario, milestones)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(artifacts)
        .around(compose)

    private fun launchBootstrapped(requiredCaps: Set<String> = emptySet()): Pair<E2eBootstrap, BootstrappedNetwork> {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val network = runBlocking { bootstrap.connectedSojuNetwork() }
        val probe = ConnectionProbe(bootstrap.seams.connections(), bootstrap.seams.ircSessions(), milestones)
        runBlocking {
            probe.awaitReady(network.rootId, emptySet())
            probe.awaitReady(network.childId, requiredCaps)
        }
        scenario.launch()
        return bootstrap to network
    }

    @Test
    fun onboardingTrustsEphemeralTlsAndImportsNetwork() {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        scenario.launch()
        OnboardingRobot(compose).importSoju(bootstrap.args)
        val rows = runBlocking { bootstrap.seams.networks().observeNetworks().first() }
        val root = rows.single { it.role == NetworkRole.BOUNCER_ROOT }
        val child = rows.single {
            it.role == NetworkRole.BOUNCER_CHILD && it.parentId == root.id &&
                it.name == "libera" && !it.bouncerNetId.isNullOrBlank()
        }
        runBlocking { ConnectionProbe(bootstrap.seams.connections(), bootstrap.seams.ircSessions(), milestones).awaitReady(child.id, emptySet()) }
        assertTrue(runBlocking { bootstrap.seams.certTrust().isPinned(bootstrap.args.host, bootstrap.args.port, bootstrap.args.fingerprint) })
        compose.onAllNodesWithTag("cert_trust_dialog", useUnmergedTree = true).assertCountEquals(0)
        milestones.record("onboarding_imported", "root=${root.id} child=${child.id}")
    }

    @Test
    fun sendEchoPersistsVisibleRowAndReconnects() {
        val (bootstrap, network) = launchBootstrapped(
            setOf(
                "echo-message",
                "draft/chathistory",
                "batch",
                "message-tags",
                "server-time",
            ),
        )
        val bufferId = runBlocking { BufferProbe(bootstrap.seams.buffers(), milestones).awaitJoinedChannel(network.childId, bootstrap.args.channel) }
        ChatListRobot(compose).open(bufferId)
        val token = "required${bootstrap.args.runId.filter(Char::isLetterOrDigit).takeLast(16)}"
        val probe = MessageLifecycleProbe(bootstrap.seams.search(), milestones)
        val canonical = runBlocking {
            coroutineScope {
                val observed = async(start = CoroutineStart.UNDISPATCHED) { probe.awaitCanonical(token, bufferId) }
                ChatRobot(compose).send(token)
                observed.await()
            }
        }
        TimelineRobot(compose).assertMessageVisible(canonical.tag())
        runBlocking {
            bootstrap.seams.connections().disconnect(network.childId)
            bootstrap.seams.connections().connect(network.childId)
            ConnectionProbe(
                bootstrap.seams.connections(),
                bootstrap.seams.ircSessions(),
                milestones,
            ).awaitReady(
                network.childId,
                setOf(
                    "echo-message",
                    "draft/chathistory",
                    "batch",
                    "message-tags",
                    "server-time",
                ),
            )
        }
        val after = runBlocking { probe.awaitCanonical(token, bufferId) }
        assertEquals(canonical.id, after.id)
        TimelineRobot(compose).assertMessageVisible(after.tag())

        val fixture = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "required-${bootstrap.args.runId}.ogg",
        )
        fixture.delete()
        assertTrue(fixture.createNewFile())
        try {
            val upload = runBlocking {
                bootstrap.seams.voiceMessages().send(
                    VoiceSendRequest(
                        bufferId = bufferId,
                        file = fixture,
                        durationMs = 1_000,
                        mimeType = "audio/ogg",
                        extension = ".ogg",
                        sizeBytes = 0,
                        encrypt = false,
                    ),
                ).filterIsInstance<VoiceSendProgress.Complete>().first()
            }
            val voice = runBlocking { probe.awaitCanonicalContaining("voice", upload.url, bufferId) }
            // This is the journey's historically opaque failure: the row is provably in Room, yet
            // the timeline neither composes it nor resolves its Paging key. Snapshot the presented
            // list, the key map, Room, and the history window on both outcomes so the next run
            // reports which of those disagrees instead of only that the wait expired.
            TimelineRobot(compose).assertCompactAudioPlayer(
                voice.tag(),
                voice.id,
                diagnostics = TimelineDiagnostics(
                    compose = compose,
                    targetContext = InstrumentationRegistry.getInstrumentation().targetContext,
                    artifactPrefix = artifacts.artifactPrefix(),
                    milestones = milestones,
                    bufferId = bufferId,
                    probedEventId = voice.id,
                    probedMsgid = voice.msgid,
                ),
            )
            milestones.record("filehost_audio_rendered", "buffer=$bufferId")
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun unreadHistoryEntersAtMarkerAndRemainsCanonical() {
        val (bootstrap, network) = launchBootstrapped(
            setOf("draft/chathistory", "draft/read-marker", "batch", "message-tags", "server-time"),
        )
        val connectionProbe = ConnectionProbe(bootstrap.seams.connections(), bootstrap.seams.ircSessions(), milestones)
        val bufferId = runBlocking {
            BufferProbe(bootstrap.seams.buffers(), milestones).awaitJoinedChannel(network.childId, bootstrap.args.channel)
        }
        val token = "unread${bootstrap.args.runId.filter(Char::isLetterOrDigit).takeLast(14)}"
        val lifecycle = MessageLifecycleProbe(bootstrap.seams.search(), milestones)
        val runProbe = MessageRunProbe(bootstrap.seams.search(), milestones)

        val marker = FixtureIrcClient.connect(bootstrap.args).use { fixture ->
            fixture.sendMessage(bootstrap.args.channel, "$token marker")
            fixture.flushThroughServer("${token}marker")
            runBlocking { lifecycle.awaitCanonicalFromAnySender("$token marker", bufferId) }
        }
        val markerAnchor = TimelineAnchor(marker.serverTime, marker.id, marker.timelineOrder)
        runBlocking {
            bootstrap.seams.connections().markRead(bufferId, markerAnchor)
            awaitMarkerAtLeast(bootstrap, bufferId, markerAnchor, requireRemote = true)
            bootstrap.seams.connections().disconnect(network.childId)
            connectionProbe.awaitDisconnected(network.childId)
            awaitWallClockAfter(markerAnchor.serverTime)
        }

        FixtureIrcClient.connect(bootstrap.args).use { fixture ->
            (1..260).forEach { ordinal ->
                fixture.sendMessage(bootstrap.args.channel, "$token row${ordinal.toString().padStart(3, '0')}")
                // Timestamp injectivity: the PONG barrier proves Ergo stamped this row at some T,
                // and the sleep keeps the next row from reaching the server before T + 2 ms, so
                // every fixture row lands on a distinct millisecond. Timestamp-only CHATHISTORY
                // paging (soju advertises MSGREFTYPES=timestamp; BEFORE is strictly-less-than)
                // silently skips same-millisecond peers at page boundaries, so an unpaced burst
                // (3+ rows per millisecond) makes deep paging lossy by construction — a fixture
                // artifact, not app behavior.
                fixture.flushThroughServer("${token}p$ordinal")
                Thread.sleep(2)
            }
            fixture.flushThroughServer("${token}gap")
        }
        runBlocking {
            coroutineScope {
                val historySettled = async(start = CoroutineStart.UNDISPATCHED) {
                    HistorySyncProbe(bootstrap.seams.history(), milestones).awaitCycle(bufferId)
                }
                bootstrap.seams.connections().connect(network.childId)
                connectionProbe.awaitReady(
                    network.childId,
                    setOf("draft/chathistory", "draft/read-marker", "batch", "message-tags", "server-time"),
                )
                historySettled.await()
            }
        }
        // Automatic CHATHISTORY fetches one 50-event newest page; chat-only search omits the
        // replayed state event in that page.
        val recentWindow = runBlocking {
            runProbe.awaitStableRecentRows(
                token = token,
                bufferId = bufferId,
                minimumCount = 49,
                maximumCount = 49,
                expectedNewestOrdinal = 260,
                requiredText = "$token row260",
                excludedText = "$token row001",
            )
        }
        val newest = recentWindow.single { it.text == "$token row260" }
        val orderedRecent = recentWindow.sortedBy { it.anchor() }
        val oldestLoaded = orderedRecent.first()
        val secondLoaded = orderedRecent[1]
        assertTrue(recentWindow.none { it.text == "$token row001" })
        assertMarkerAtLeast(bootstrap, bufferId, marker)
        val roomBeforeEntry = runBlocking {
            bootstrap.seams.buffers().observeBuffer(bufferId).first { it != null }
        }
        assertEquals(markerAnchor.serverTime, roomBeforeEntry?.localReadAnchorTime)
        assertEquals(Long.MAX_VALUE, roomBeforeEntry?.localReadAnchorEventId)
        val listBeforeEntry = runBlocking {
            withTimeout(10_000) {
                bootstrap.seams.buffers().observeChatList().first { rows ->
                    rows.singleOrNull { it.bufferId == bufferId }?.let { row ->
                        row.unreadCount == 49 && row.unreadCountIncomplete
                    } == true
                }
            }
        }
        val boundedRow = listBeforeEntry.single { it.bufferId == bufferId }
        assertEquals(49, boundedRow.unreadCount)
        assertTrue(boundedRow.unreadCountIncomplete)

        ChatListRobot(compose).open(bufferId)
        val timeline = TimelineRobot(compose)
        timeline.assertUnreadEntry(
            oldestLoaded.tag(),
            secondLoaded.tag(),
            expectedLabel = "49+ new messages",
        )
        compose.waitForIdle()
        assertMarkerAtLeast(bootstrap, bufferId, marker)
        // Opening the timeline attaches the scroll-driven Pager over the gap-bounded Recent window
        // (49 unread fixture rows < initialLoadSize = pageSize * 3 = 150), so the local source
        // exhausts its bound (nextKey == null) and Paging auto-fires an older APPEND with no
        // scroll. Each persisted BEFORE page recedes the recoverable gap's newer edge, re-bounds
        // the window, and repeats while the whole window still fits under initialLoadSize. The wire
        // is timestamp-only (soju advertises MSGREFTYPES=timestamp): the catch-up gap must stay
        // RECOVERABLE across msgid-less saturated pages for this backfill to run at all — the
        // regression this pins. The bounded window also holds k >= 1 non-fixture state rows, all
        // newer than row260 (the replayed state event inside the newest catch-up page — the reason
        // the pre-open window is 49 rows of a 50-event page — plus the app's own reconnect state
        // rows), and the backfill stops at
        // the first generation whose WHOLE window reaches 150: k == 1 yields three pages (199
        // fixture rows), k >= 2 yields two (149). Both are bounded and final — never the backlog —
        // so the settle asserts the 149..199 range with row112 required (present in both terminal
        // states, absent while the cascade is still at 99) and row001 excluded. The bounded-
        // catch-up proof stays the frozen "49+" divider above and the pre-open unreadCount==49
        // badge, both captured before the Pager attached.
        val postOpenWindow = runBlocking {
            runProbe.awaitStableRecentRows(
                token = token,
                bufferId = bufferId,
                minimumCount = 149,
                maximumCount = 199,
                expectedNewestOrdinal = 260,
                requiredText = "$token row112",
                excludedText = "$token row001",
                // row112 lands with cascade page 2, so a k <= 1 run is still mid-cascade when the
                // required row first appears. A longer quiet window keeps a slow hosted emulator
                // from settling on that pre-terminal 149 and handing the reopen divider stale
                // oldest-row anchors once page 3 lands.
                stableMs = 4_000,
            )
        }
        // The reopen entry re-resolves against the grown island: its two oldest rows become the
        // next divider anchor.
        val orderedPostOpen = postOpenWindow.sortedBy { it.anchor() }
        val reopenOldest = orderedPostOpen.first()
        val reopenSecond = orderedPostOpen[1]

        // Reopening anchors a bounded entry on the settled window, not the backlog. The divider
        // count equals the visible unread window rows (fixture rows plus k), so no exact label is
        // pinned here; the frozen "49+" assertion above already proves the bounded-catch-up label.
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ChatListRobot(compose).apply { awaitTag("chatlist_row_$bufferId"); open(bufferId) }
        timeline.assertUnreadEntry(
            reopenOldest.tag(),
            reopenSecond.tag(),
        )
        assertMarkerAtLeast(bootstrap, bufferId, marker)
        // No search pin after the reopen: its entry lands ON the window's oldest unread row (the
        // APPEND boundary), so the boundary hint appends one more bounded page, which can saturate
        // the chat-only search surface (newest-200 cap) and become indistinguishable from deeper
        // windows there. The backfill mechanics are already pinned by the ranged settle above;
        // deeper paging is verified by row001 becoming reachable via deliberate scrolling plus the
        // terminal canonicality and newest-200 cap assertions below.
        timeline.scrollOlderUntil("$token row001")
        val (firstUnread, secondUnread) = runBlocking {
            lifecycle.awaitCanonicalFromAnySender("$token row001", bufferId) to
                lifecycle.awaitCanonicalFromAnySender("$token row002", bufferId)
        }
        assertTrue(markerAnchor < firstUnread.anchor())
        assertTrue(firstUnread.anchor() < secondUnread.anchor())
        assertTrue(secondUnread.anchor() < newest.anchor())
        timeline.assertMessageVisible(firstUnread.tag())

        timeline.scrollToBottom()
        runBlocking {
            awaitMarkerAtLeast(bootstrap, bufferId, newest.anchor())
        }
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ChatListRobot(compose).apply { awaitTag("chatlist_row_$bufferId"); open(bufferId) }
        timeline.assertNoUnreadDivider()
        timeline.assertMessage("$token row260")

        scenario.scenario?.onActivity { activity ->
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                activity,
                Intent(activity, MainActivity::class.java)
                    .setAction(MotdNotifications.ACTION_OPEN_BUFFER)
                    .putExtra(MotdNotifications.EXTRA_BUFFER_ID, bufferId)
                    .putExtra(MotdNotifications.EXTRA_JUMP_MSGID, firstUnread.msgid)
                    .putExtra(MotdNotifications.EXTRA_JUMP_TIME, firstUnread.serverTime)
                    .putExtra(MotdNotifications.EXTRA_EVENT_ID, firstUnread.id),
            )
        }
        // The notification deep jump is a cold cross-activity entry: a new route, a fresh Pager
        // generation keyed around a row ~260 messages deep, target materialization, and the entry
        // scroll must all complete before the row is displayed. Budget it like the suite's other
        // navigation/network-scale waits (20-45s) rather than the generic 10s component wait,
        // which is a slow-hosted-emulator flake edge for this step.
        //
        // Headroom, not a fix: the app's own materialization cap is TARGET_MATERIALIZATION_TIMEOUT_MS
        // (30s), so a 30s test budget ties with the production deadline and loses by construction
        // whenever the deep jump legitimately needs its full cap. 45s leaves the app room to finish
        // and still fails loudly if it never does.
        timeline.assertMessageVisible(firstUnread.tag(), timeoutMs = 45_000)
        scenario.scenario?.onActivity { it.recreate() }
        // Activity recreation replays the same deep entry from scratch on the same cold budget.
        timeline.assertMessageVisible(firstUnread.tag(), timeoutMs = 45_000)
        // Directional paging restores older rows; search then exposes its exact newest-200 cap.
        runBlocking {
            runProbe.awaitRows(
                token = token,
                bufferId = bufferId,
                count = 200,
                expectedExtras = emptySet(),
                expectedNewestOrdinal = 260,
            )
        }
        milestones.record("notification_restore_stable", "buffer=$bufferId event=${firstUnread.id}")
    }

    @Test
    fun bootstrappedNavigationSettingsAndBouncerSmoke() {
        val (bootstrap, network) = launchBootstrapped()
        SettingsRobot(compose).apply {
            open()
            appearance()
        }
        ThemeSheetRobot(compose).selectAyuDarkAndTrueBlack()
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onAllNodesWithTag("settings_theme_sheet", useUnmergedTree = true).assertCountEquals(0)
        // Return from Appearance to Settings, then exercise the category and bouncer routes.
        SettingsRobot(compose).apply {
            returnToRoot()
            chat()
            assertDisplayed("settings_switch_show_jpq")
            returnToRoot()
            networks()
        }
        NetworksRobot(compose).openRoot(network.rootId)
        BouncerRobot(compose).assertPanels()
        milestones.record("settings_bouncer_smoke", "root=${network.rootId}")
    }

    private suspend fun awaitMarkerAtLeast(
        bootstrap: E2eBootstrap,
        bufferId: Long,
        expected: TimelineAnchor,
        requireRemote: Boolean = false,
    ) {
        withTimeout(20_000) {
            bootstrap.seams.buffers().observeBuffer(bufferId).first { room ->
                room != null && markerAtLeast(room.localReadAnchorTime, room.localReadAnchorEventId, expected) &&
                    (!requireRemote || (room.readMarkerTime ?: Long.MIN_VALUE) >= expected.serverTime)
            }
        }
    }

    private fun assertMarkerAtLeast(
        bootstrap: E2eBootstrap,
        bufferId: Long,
        marker: io.github.trevarj.motd.data.db.MessageEntity,
    ) {
        val room = runBlocking { bootstrap.seams.buffers().observeBuffer(bufferId).first { it != null } }
        assertTrue(markerAtLeast(room?.localReadAnchorTime, room?.localReadAnchorEventId, marker.anchor()))
    }

    private fun markerAtLeast(time: Long?, eventId: Long?, expected: TimelineAnchor): Boolean =
        time != null && eventId != null &&
            (time > expected.serverTime || (time == expected.serverTime && eventId >= expected.eventId))

    private suspend fun awaitWallClockAfter(serverTime: Long) {
        // IRC read markers are timestamp-only and therefore include every message in the same
        // millisecond. Keep the fixture's unread burst outside that intentionally inclusive tie.
        withTimeout(5_000) {
            while (System.currentTimeMillis() <= serverTime) delay(1)
        }
    }

    private fun io.github.trevarj.motd.data.db.MessageEntity.anchor(): TimelineAnchor =
        TimelineAnchor(serverTime, id, timelineOrder)

    private fun io.github.trevarj.motd.data.db.MessageEntity.tag(): String = "chat_message_${msgid ?: id}"

    companion object {
        /**
         * The four journeys share one hermetic soju/ergo stack and one channel, so their execution
         * order is load-bearing rather than incidental: `unreadHistory…` seeds ~260 backlog rows
         * that `sendEcho…` then sends and pages against, and the deep-jump steps depend on that
         * depth existing. JUnit's default sorter orders methods by name hashCode, so renaming,
         * adding, or removing a journey silently permutes the sequence and quietly changes what
         * every later journey runs against — the kind of change that surfaces as an unexplained
         * required-gate flake rather than a failing assertion.
         *
         * Per-journey channel isolation would be the stronger fix, but the fixture stack pre-joins
         * a single channel (`FixtureArgs.channel`, also used directly by `FixtureIrcClient`), so
         * deriving a channel per journey would mean new join plumbing on both the app seam and the
         * harness. Pinning the order is the least invasive change that still removes the silent
         * breakage: any permutation fails here, once, with an explicit message.
         */
        private val JOURNEY_ORDER = listOf(
            "unreadHistoryEntersAtMarkerAndRemainsCanonical",
            "bootstrappedNavigationSettingsAndBouncerSmoke",
            "sendEchoPersistsVisibleRowAndReconnects",
            "onboardingTrustsEphemeralTlsAndImportsNetwork",
        )

        /** `TestClass` applies the same sorter `BlockJUnit4ClassRunner.computeTestMethods` uses. */
        @BeforeClass
        @JvmStatic
        fun pinJourneyOrder() {
            val actual = TestClass(RequiredHeadlessE2eTest::class.java)
                .getAnnotatedMethods(Test::class.java)
                .map { it.name }
            assertEquals(
                "required E2E journey order changed: these journeys share one channel and the " +
                    "later ones depend on the backlog the earlier ones seed. Re-pin JOURNEY_ORDER " +
                    "only after confirming the new sequence still satisfies those dependencies.",
                JOURNEY_ORDER,
                actual,
            )
        }
    }
}
