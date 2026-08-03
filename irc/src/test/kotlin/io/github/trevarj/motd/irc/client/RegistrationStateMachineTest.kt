package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.event.IrcEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationStateMachineTest {
    @Test
    fun `server password is sent before CAP NICK and USER`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "cloak",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd-android",
                realname = "motd",
                serverPassword = "trev/libera:secret",
            ),
        )

        assertEquals(
            listOf(
                "PASS trev/libera:secret",
                "CAP LS 302",
                "NICK motd",
                "USER motd-android 0 * :motd",
            ),
            machine.start().sentLines(),
        )
    }

    @Test
    fun `server password uses safe trailing parameter serialization`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                serverPassword = "secret with spaces",
            ),
        )

        assertEquals("PASS :secret with spaces", machine.start().sentLines().first())
    }

    @Test
    fun `invalid server password fails without transmitting registration`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                serverPassword = "secret\r\nQUIT",
            ),
        )

        val actions = machine.start()
        val fail = actions.single() as RegistrationStateMachine.Action.Fail
        assertEquals("invalid server password", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `password mismatch is a fatal registration failure`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig("cloak", 6697, true, "motd", "motd", "motd", serverPassword = "bad"),
        )

        val fail = machine.onMessage(
            IrcMessage(command = "464", params = listOf("motd", "Password incorrect")),
        ).single() as RegistrationStateMachine.Action.Fail

        assertEquals("server password rejected", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `bouncer bind negotiates replay safety before fallback ready`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                bouncerNetId = "2",
            ),
        )

        machine.start()
        val req = machine.onMessage(
            cap(
                "LS",
                "sasl soju.im/bouncer-networks cap-notify draft/chathistory batch " +
                    "message-tags server-time echo-message extended-monitor",
            ),
        )
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        assertEquals(listOf("CAP REQ :$replayBarrier"), req.sentLines())

        val afterAck = machine.onMessage(cap("ACK", replayBarrier))
        assertEquals(listOf("BOUNCER BIND 2", "CAP END"), afterAck.sentLines())

        val afterFirstBindCapChange = machine.onMessage(cap("DEL", "extended-monitor"))
        assertTrue(afterFirstBindCapChange.any { it is RegistrationStateMachine.Action.Complete })
        assertTrue(afterFirstBindCapChange.sentLines().isEmpty())
        assertEquals(listOf("CAP REQ :echo-message"), afterFirstBindCapChange.deferredLines())
        val ready = afterFirstBindCapChange.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()
        assertTrue(ready.caps.containsAll(replayBarrier.split(' ')))
        assertEquals(setOf("echo-message"), ready.deferredCaps)
        assertTrue(afterFirstBindCapChange.deferredLines().all { it.substringAfter("CAP REQ :").contains(' ').not() })
    }

    @Test
    fun `registration FAIL surfaces instead of hanging`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                bouncerNetId = "404",
            ),
        )

        val actions = machine.onMessage(
            IrcMessage(command = "FAIL", params = listOf("BOUNCER", "INVALID_NETID", "No such network")),
        )

        val fail = actions.single() as RegistrationStateMachine.Action.Fail
        assertEquals("INVALID_NETID No such network", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `pre-away sends AWAY before CAP END when capability is acked`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                initialAwayMessage = "back later",
            ),
        )

        machine.start()
        assertEquals(listOf("CAP REQ :draft/pre-away"), machine.onMessage(cap("LS", "draft/pre-away")).sentLines())
        assertEquals(
            listOf("AWAY :back later", "CAP END"),
            machine.onMessage(cap("ACK", "draft/pre-away")).sentLines(),
        )
    }

    @Test
    fun `initial away falls back after welcome when pre-away is absent`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                initialAwayMessage = "back later",
            ),
        )

        machine.start()
        assertEquals(listOf("CAP END"), machine.onMessage(cap("LS", "")).sentLines())
        val welcome = machine.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))

        assertTrue(welcome.any { it is RegistrationStateMachine.Action.Complete })
        assertEquals(listOf("AWAY :back later"), welcome.sentLines())
    }

    @Test
    fun `pre-away failure is reported and retried after welcome`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                initialAwayMessage = "back later",
            ),
        )

        machine.start()
        machine.onMessage(cap("LS", "draft/pre-away"))
        machine.onMessage(cap("ACK", "draft/pre-away"))
        val failure = machine.onMessage(
            IrcMessage(command = "FAIL", params = listOf("AWAY", "INVALID_PARAMS", "rejected")),
        ).single() as RegistrationStateMachine.Action.Emit
        val reply = failure.event as IrcEvent.StandardReply
        assertEquals("AWAY", reply.commandName)

        val welcome = machine.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))
        assertEquals(listOf("AWAY :back later"), welcome.sentLines())
    }

    @Test
    fun `account network authcid negotiates replay safety before fallback ready`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                saslUser = "motd/libera",
            ),
        )

        machine.start()
        val req = machine.onMessage(
            cap(
                "LS",
                "cap-notify sasl soju.im/bouncer-networks draft/chathistory batch message-tags " +
                    "server-time znc.in/server-time-iso echo-message",
            ),
        )
        val replayBarrier = "sasl draft/chathistory batch message-tags server-time"
        assertEquals(listOf("CAP REQ :$replayBarrier"), req.sentLines())

        val afterAck = machine.onMessage(cap("ACK", replayBarrier))
        assertEquals(listOf("CAP END"), afterAck.sentLines())

        val afterCapChange = machine.onMessage(cap("DEL", "extended-monitor"))
        assertTrue(afterCapChange.any { it is RegistrationStateMachine.Action.Complete })
        assertTrue(afterCapChange.sentLines().isEmpty())
        assertEquals(listOf("CAP REQ :echo-message"), afterCapChange.deferredLines())
    }

    @Test
    fun `normal bouncer welcome exposes deferred capability decisions`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                bouncerNetId = "2",
            ),
        )
        machine.start()
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        machine.onMessage(cap("LS", "$replayBarrier draft/read-marker"))
        machine.onMessage(cap("ACK", replayBarrier))

        val welcome = machine.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))
        val ready = welcome.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()

        assertTrue("draft/read-marker" in ready.deferredCaps)
        assertTrue(welcome.sentLines().contains("CAP REQ :draft/read-marker"))
    }

    @Test
    fun `fallback CAP DEL updates ready snapshot and drops stale deferred caps`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                bouncerNetId = "2",
            ),
        )

        machine.start()
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        machine.onMessage(cap("LS", "$replayBarrier extended-monitor"))
        machine.onMessage(cap("ACK", replayBarrier))

        val actions = machine.onMessage(cap("DEL", "message-tags extended-monitor"))
        val ready = actions.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()

        assertTrue("message-tags" !in ready.caps)
        assertTrue("draft/chathistory" in ready.caps)
        assertTrue("message-tags" !in ready.deferredCaps)
        assertTrue(
            actions.deferredLines().none {
                it.endsWith("message-tags") || it.endsWith("extended-monitor")
            },
        )
    }

    @Test
    fun `fallback CAP NEW adds newly advertised desired cap to deferred requests`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                bouncerNetId = "2",
            ),
        )

        machine.start()
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        machine.onMessage(cap("LS", replayBarrier))
        machine.onMessage(cap("ACK", replayBarrier))

        val actions = machine.onMessage(
            cap("NEW", "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1"),
        )

        assertTrue(actions.deferredLines().contains("CAP REQ :draft/metadata-2"))
        val ready = actions.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()
        assertTrue(ready.caps.none { it.startsWith("draft/metadata-2") })
        assertTrue("draft/metadata-2" in ready.deferredCaps)
    }

    private fun cap(subcommand: String, caps: String) =
        IrcMessage(command = "CAP", params = listOf("*", subcommand, caps))

    private fun List<RegistrationStateMachine.Action>.sentLines(): List<String> =
        filterIsInstance<RegistrationStateMachine.Action.Send>().map { it.line }

    private fun List<RegistrationStateMachine.Action>.deferredLines(): List<String> =
        filterIsInstance<RegistrationStateMachine.Action.SendDeferred>().map { it.line }
}
