package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.encodeAgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.fragmentAgentwireEnvelope
import io.github.trevarj.motd.irc.client.EventMapper
import io.github.trevarj.motd.irc.client.SequencedIrcEvent
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

class AgentwireDeliveryCoordinatorTest {
    @Test
    fun `stream gap retries one correlated sync then converges on replacement snapshot`() =
        runTest {
            val session = AgentwireSessionOrchestrator()
            var state = session.beginSync(baseState())

            fun requestInitialSync(id: String) {
                session.syncRequested(id)
            }

            fun update(
                sequence: Long,
                envelope: AgentwireEnvelope,
            ): AgentwireDeliveryCoordinator.Result {
                val result = session.ingest(state, SequencedIrcEvent(sequence, inbound(envelope)))
                if (result is AgentwireDeliveryCoordinator.Result.Updated) state = result.state
                return result
            }

            requestInitialSync("sync-1")
            update(1, hello("sync-1", "epoch-1"))
            val initialSnapshot = update(2, snapshot("sync-1", "epoch-1", "session-1", busy = true))
            assertTrue((initialSnapshot as AgentwireDeliveryCoordinator.Result.Updated).syncCompleted)
            update(3, event("turn.started", "epoch-1", sid = "session-1", tid = "turn-1"))
            assertTrue(state.busy)
            assertEquals(1, state.timeline.size)

            val fragmentedRequest =
                event(
                    kind = "request.opened",
                    epoch = "epoch-1",
                    sid = "session-1",
                    rid = "request-1",
                    data =
                        buildJsonObject {
                            put("type", "approval")
                            put("summary", incompressibleText())
                            put("redacted", false)
                            put("inactive", false)
                        },
                )
            val firstFragment = fragmentAgentwireEnvelope(fragmentedRequest).first()
            val partial = session.ingest(state, SequencedIrcEvent(4, inboundRaw(firstFragment)))
            assertTrue(partial is AgentwireDeliveryCoordinator.Result.Ignored)

            // Sequence 5, the rest of request.opened, was dropped while the observer was stalled.
            val resync =
                update(6, event("turn.completed", "epoch-1", sid = "session-1", tid = "turn-1"))
                    as AgentwireDeliveryCoordinator.Result.ResyncRequired
            state = resync.state
            assertEquals("Agentwire event stream gap; resynchronizing", resync.reason)
            assertTrue(session.awaitingSync)
            assertTrue(state.timeline.isEmpty())
            assertTrue(state.requests.isEmpty())
            assertFalse(state.busy)
            assertTrue(state.actions.isEmpty())

            val requestedSyncs = mutableListOf<String>()
            var replacement: AgentwireDeliveryCoordinator.Result.Updated? = null
            val budget = AgentwireSyncBudget({ 0L }).also { it.anchor() }
            session.retryUntilReady(
                budget = budget,
                isReady = { !session.awaitingSync },
                issue = { id ->
                    requestedSyncs += id
                    // A live event from the lost epoch must not revive derived state before the
                    // replacement hello and snapshot complete this first correlated sync request.
                    val stale = update(7, event("request.opened", "epoch-1", sid = "session-1", rid = "request-1"))
                    assertTrue(stale is AgentwireDeliveryCoordinator.Result.Ignored)
                    assertTrue(state.requests.isEmpty())
                    update(8, hello(id, "epoch-2", actions = setOf("turn.prompt")))
                    replacement =
                        update(9, snapshot(id, "epoch-2", "session-2", busy = false))
                            as AgentwireDeliveryCoordinator.Result.Updated
                    true
                },
            )

            assertEquals(1, requestedSyncs.size)
            assertTrue(checkNotNull(replacement).syncCompleted)
            assertFalse(session.awaitingSync)
            assertEquals("epoch-2", state.epoch)
            assertEquals("session-2", state.activeSid)
            assertFalse(state.busy)
            assertTrue(state.requests.isEmpty())
            assertTrue(state.timeline.isEmpty())
            assertEquals(setOf("turn.prompt"), state.actions)
        }

    @Test
    fun `compressed one-part hello completes sync instead of reporting protocol mismatch`() {
        val session = AgentwireSessionOrchestrator()
        var state = session.beginSync(baseState())
        session.syncRequested("sync-compressed")
        val compressedHello =
            hello("sync-compressed", "epoch-compressed").copy(
                data =
                    buildJsonObject {
                        put("epoch", "epoch-compressed")
                        put("padding", "x".repeat(12_000))
                    },
            )
        val fragments = fragmentAgentwireEnvelope(compressedHello)
        assertEquals(1, fragments.size)
        assertTrue("\"encoding\":\"zlib\"" in fragments.single())

        val helloResult =
            session.ingest(state, SequencedIrcEvent(1, inboundRaw(fragments.single())))
                as AgentwireDeliveryCoordinator.Result.Updated
        state = helloResult.state
        val snapshotResult =
            session.ingest(
                state,
                SequencedIrcEvent(2, inbound(snapshot("sync-compressed", "epoch-compressed", "session-1", busy = false))),
            ) as AgentwireDeliveryCoordinator.Result.Updated

        assertTrue(snapshotResult.syncCompleted)
        assertEquals("epoch-compressed", snapshotResult.state.epoch)
    }

    @Test
    fun `expired partial envelope starts a new sync before reducing the next live event`() {
        var now = 0L
        val session =
            AgentwireSessionOrchestrator(
                delivery =
                    AgentwireDeliveryCoordinator(
                        ingestor = AgentwireEventIngestor(reassembler = AgentwireReassembler { now }),
                    ),
            )
        var state =
            baseState().copy(
                epoch = "epoch-1",
                botAccount = "agent",
                activeSid = "session-1",
                busy = true,
                timeline = listOf(AgentwireTimelineItem("turn-1", "turn.started", 1, "session-1", "turn-1", "Running", null)),
            )
        val fragmented =
            fragmentAgentwireEnvelope(
                event(
                    kind = "request.opened",
                    epoch = "epoch-1",
                    sid = "session-1",
                    rid = "request-1",
                    data =
                        buildJsonObject {
                            put("type", "approval")
                            put("summary", incompressibleText())
                            put("redacted", false)
                            put("inactive", false)
                        },
                ),
            ).first()

        assertTrue(session.ingest(state, SequencedIrcEvent(1, inboundRaw(fragmented))) is AgentwireDeliveryCoordinator.Result.Ignored)
        now = 30_001L
        val result =
            session.ingest(
                state,
                SequencedIrcEvent(2, inbound(event("turn.completed", "epoch-1", sid = "session-1", tid = "turn-1"))),
            ) as AgentwireDeliveryCoordinator.Result.ResyncRequired
        state = result.state

        assertEquals("Agentwire fragment assembly expired; resynchronizing", result.reason)
        assertTrue(session.awaitingSync)
        assertTrue(state.timeline.isEmpty())
        assertFalse(state.busy)
    }

    @Test
    fun `stale live epoch failure starts replacement sync`() {
        val session = AgentwireSessionOrchestrator()
        val state =
            baseState().copy(
                epoch = "epoch-old",
                botAccount = "agent",
                activeSid = "session-old",
            )
        val unrelated =
            session.ingest(
                state,
                SequencedIrcEvent(
                    1,
                    inbound(
                        event(
                            "action.failed",
                            "epoch-new",
                            reply = "list-1",
                            data = buildJsonObject { put("message", "ordinary failure") },
                        ),
                    ),
                ),
            )
        assertTrue(unrelated is AgentwireDeliveryCoordinator.Result.Ignored)

        val observed =
            session.ingest(
                state,
                SequencedIrcEvent(
                    2,
                    inbound(
                        event(
                            "action.failed",
                            "epoch-new",
                            reply = "list-2",
                            data =
                                buildJsonObject {
                                    put("message", "stale or missing live epoch")
                                },
                        ),
                    ),
                ),
            )
        assertTrue(observed.toString(), observed is AgentwireDeliveryCoordinator.Result.ResyncRequired)
        val result = observed as AgentwireDeliveryCoordinator.Result.ResyncRequired

        assertEquals(AgentwireResyncCause.EPOCH, result.cause)
        assertEquals("Agentwire epoch changed; resynchronizing", result.reason)
        assertTrue(session.awaitingSync)
        assertEquals(null, result.state.epoch)
        assertEquals(null, result.state.activeSid)
    }

    private fun incompressibleText(): String = Base64.getEncoder().encodeToString(Random(0).nextBytes(10_000))

    private fun baseState() =
        AgentwireUiState(
            channel = "#codex",
            controllerAccount = "controller",
            backendAccount = "agent",
        )

    private fun hello(
        reply: String,
        epoch: String,
        actions: Set<String> = emptySet(),
    ) = event(
        kind = "agent.hello",
        epoch = epoch,
        reply = reply,
        data =
            buildJsonObject {
                put("epoch", epoch)
                put("actions", buildJsonArray { actions.forEach { add(JsonPrimitive(it)) } })
            },
    )

    private fun snapshot(
        reply: String,
        epoch: String,
        sid: String,
        busy: Boolean,
    ) = event(
        kind = "channel.snapshot",
        epoch = epoch,
        reply = reply,
        data =
            buildJsonObject {
                put("binding", buildJsonObject { put("sid", sid) })
                put("busy", busy)
            },
    )

    private fun event(
        kind: String,
        epoch: String,
        sid: String? = null,
        tid: String? = null,
        rid: String? = null,
        reply: String? = null,
        data: kotlinx.serialization.json.JsonObject? = null,
    ) = AgentwireEnvelope(
        kind = kind,
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 1,
        instance = "agent",
        epoch = epoch,
        sid = sid,
        tid = tid,
        rid = rid,
        reply = reply,
        data = data,
    )

    private fun inbound(envelope: AgentwireEnvelope): IrcEvent = inboundRaw(encodeAgentwireEnvelope(envelope))

    private fun inboundRaw(raw: String): IrcEvent =
        checkNotNull(
            EventMapper({ "me" }, { Isupport() }).map(
                IrcMessage.parse(
                    IrcMessage(
                        tags = mapOf("account" to "agent", AGENTWIRE_TAG to raw),
                        source = Prefix("agent", "u", "h"),
                        command = "TAGMSG",
                        params = listOf("#codex"),
                    ).serialize(),
                ),
            ),
        )
}
