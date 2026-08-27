package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure timeline-filter behavior: presence visibility, fools HIDE, exemptions. */
class MessageFilterTest {
    private fun msg(
        sender: String = "alice",
        kind: MessageKind = MessageKind.PRIVMSG,
        isSelf: Boolean = false,
        normalizedActor: String = IrcIdentityRules().normalize(sender),
        senderAccount: String? = null,
    ) = MessageEntity(
        id = 1,
        bufferId = 1,
        serverTime = 1_000L,
        sender = sender,
        normalizedActor = normalizedActor,
        senderAccount = senderAccount,
        kind = kind,
        text = "hi",
        isSelf = isSelf,
        dedupKey = "k",
    )

    // --- isFoolMessage ---

    @Test fun `fool sender matches case-insensitively`() {
        assertTrue(isFoolMessage(msg(sender = "Alice"), fools = setOf("alice")))
    }

    @Test fun `own messages are never fools`() {
        assertFalse(isFoolMessage(msg(sender = "alice", isSelf = true), fools = setOf("alice")))
    }

    @Test fun `non-listed sender is not a fool`() {
        assertFalse(isFoolMessage(msg(sender = "bob"), fools = setOf("alice")))
    }

    // --- keepMessage: presence events ---

    @Test fun `presence rows kept when the mode shows everything`() {
        val spec = MessageFilterSpec(presenceMode = PresenceMode.ALL)
        assertTrue(keepMessage(msg(kind = MessageKind.JOIN), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.PART), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.QUIT), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.NICK), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.AWAY), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.BACK), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.NETSPLIT), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.NETJOIN), spec))
    }

    @Test fun `presence rows dropped when the mode hides them`() {
        val spec = MessageFilterSpec(presenceMode = PresenceMode.HIDDEN)
        assertFalse(keepMessage(msg(kind = MessageKind.JOIN), spec))
        assertFalse(keepMessage(msg(kind = MessageKind.PART), spec))
        assertFalse(keepMessage(msg(kind = MessageKind.QUIT), spec))
        assertFalse(keepMessage(msg(kind = MessageKind.NICK), spec))
        assertFalse(keepMessage(msg(kind = MessageKind.AWAY), spec))
        assertFalse(keepMessage(msg(kind = MessageKind.BACK), spec))
        assertFalse(keepMessage(msg(kind = MessageKind.NETSPLIT), spec))
        assertFalse(keepMessage(msg(kind = MessageKind.NETJOIN), spec))
    }

    /**
     * SMART is resolved in SQL, so a row that reached an in-memory consumer already passed it; the
     * entity-only predicate must not second-guess that and hide a row that is on screen.
     */
    @Test fun `smart keeps presence rows in memory because SQL already decided`() {
        val spec = MessageFilterSpec(presenceMode = PresenceMode.SMART)
        assertTrue(keepMessage(msg(kind = MessageKind.JOIN), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.NICK), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.QUIT), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.AWAY), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.BACK), spec))
    }

    @Test fun `non-presence system kinds always kept regardless of the mode`() {
        val spec = MessageFilterSpec(presenceMode = PresenceMode.HIDDEN)
        assertTrue(keepMessage(msg(kind = MessageKind.KICK), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.MODE), spec))
        assertTrue(keepMessage(msg(kind = MessageKind.TOPIC), spec))
    }

    // --- keepMessage: fools ---

    @Test fun `fool HIDE drops the fool's messages`() {
        val spec = MessageFilterSpec(fools = setOf("alice"), foolsMode = FoolsMode.HIDE)
        assertFalse(keepMessage(msg(sender = "alice"), spec))
    }

    @Test fun `fool COLLAPSE keeps the row for the placeholder`() {
        val spec = MessageFilterSpec(fools = setOf("alice"), foolsMode = FoolsMode.COLLAPSE)
        assertTrue(keepMessage(msg(sender = "alice"), spec))
    }

    @Test fun `fool HIDE does not drop own messages`() {
        val spec = MessageFilterSpec(fools = setOf("me"), foolsMode = FoolsMode.HIDE)
        assertTrue(keepMessage(msg(sender = "me", isSelf = true), spec))
    }

    @Test fun `fool HIDE never removes system-kind rows`() {
        // A fool's JOIN is a system event; JPQ visibility governs it, not fool mode.
        val spec = MessageFilterSpec(fools = setOf("alice"), foolsMode = FoolsMode.HIDE)
        assertTrue(keepMessage(msg(sender = "alice", kind = MessageKind.JOIN), spec))
    }

    @Test fun `fool HIDE matching is case-insensitive`() {
        val spec = MessageFilterSpec(fools = setOf("alice"), foolsMode = FoolsMode.HIDE)
        assertFalse(keepMessage(msg(sender = "ALICE"), spec))
    }

    @Test fun `non-fool message kept under HIDE`() {
        val spec = MessageFilterSpec(fools = setOf("alice"), foolsMode = FoolsMode.HIDE)
        assertTrue(keepMessage(msg(sender = "bob"), spec))
    }

    @Test fun `friend and fool matching obey strict casemap without merging tilde`() {
        val strict = IrcIdentityRules(IrcCaseMapping.Rfc1459Strict)
        val message = msg(sender = "nick~", normalizedActor = strict.normalize("nick~"))

        assertFalse(message.matchesConfiguredActor(setOf("nick^"), strict))
        assertTrue(message.matchesConfiguredActor(setOf("NICK~"), strict))
        assertFalse(isFoolMessage(message, setOf("nick^"), strict))
    }

    @Test fun `unknown custom casemap uses conservative ASCII actor matching`() {
        val custom = IrcIdentityRules(IrcCaseMapping.Unknown("custom-map"))
        val message = msg(sender = "User[", normalizedActor = custom.normalize("User["))

        assertTrue(message.matchesConfiguredActor(setOf("user["), custom))
        assertFalse(message.matchesConfiguredActor(setOf("user{"), custom))
    }

    @Test fun `configured account matches after a nick change`() {
        val message = msg(sender = "newNick", senderAccount = "stable-account")

        assertTrue(message.matchesConfiguredActor(setOf("stable-account"), IrcIdentityRules()))
        assertTrue(isFoolMessage(message, setOf("stable-account")))
    }
}
