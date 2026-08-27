package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure string assertions on the generated firehose SQL; no database involved. */
class FirehosePagingQueryTest {
    @Test
    fun keepsConversationKindsAndProjectsTheConversationTag() {
        val sql = firehosePagingQuery(MessageVisibilitySpec(), networks = emptyList()).sql

        assertTrue(sql.contains("'PRIVMSG'"))
        assertTrue(sql.contains("'NOTICE'"))
        assertTrue(sql.contains("'ACTION'"))
        assertFalse(sql.contains("'JOIN'"))
        assertFalse(sql.contains("'QUIT'"))
        assertTrue(sql.contains("b.displayName AS bufferDisplayName"))
        assertTrue(sql.contains("n.name AS networkName"))
        assertTrue(sql.contains("ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC"))
    }

    @Test
    fun excludesRoomsThatAreNotPartOfTheStream() {
        val sql = firehosePagingQuery(MessageVisibilitySpec(), networks = emptyList()).sql

        assertTrue(sql.contains("b.type IN ('CHANNEL','QUERY')"))
        assertTrue(sql.contains("b.dismissed = 0"))
        assertTrue(sql.contains("b.archived = 0"))
        assertTrue(sql.contains("b.pendingCloseAt IS NULL"))
        assertTrue(sql.contains("b.redirectToRoomId IS NULL"))
    }

    @Test
    fun antiJoinsRowsThatLostCanonicalEventMerging() {
        val sql = firehosePagingQuery(MessageVisibilitySpec(), networks = emptyList()).sql

        assertTrue(sql.contains("LEFT JOIN event_redirects redirect ON redirect.losingEventId = m.id"))
        assertTrue(sql.contains("redirect.losingEventId IS NULL"))
    }

    @Test
    fun foolClauseIsTrivialWithoutFoolsOrWithoutNetworks() {
        val noFools =
            firehosePagingQuery(
                MessageVisibilitySpec(fools = emptySet()),
                networks = listOf(FirehoseNetwork(1L), FirehoseNetwork(2L)),
            ).sql
        val noNetworks =
            firehosePagingQuery(
                MessageVisibilitySpec(fools = setOf("troll"), foolsMode = FoolsMode.HIDE),
                networks = emptyList(),
            ).sql

        assertTrue(noFools.contains("AND 1 ORDER BY"))
        assertFalse(noFools.contains("n.id = 1"))
        assertTrue(noNetworks.contains("AND 1 ORDER BY"))
    }

    /** Collapse has no compact rendering here, so the firehose mutes fools in either mode. */
    @Test
    fun foolsAreExcludedInCollapseModeToo() {
        val sql =
            firehosePagingQuery(
                MessageVisibilitySpec(fools = setOf("troll"), foolsMode = FoolsMode.COLLAPSE),
                networks = listOf(FirehoseNetwork(7L)),
            ).sql

        assertTrue(sql.contains("(n.id = 7 AND NOT ("))
        assertTrue(sql.contains("m.normalizedActor"))
    }

    @Test
    fun eachNetworkNormalizesFoolsWithItsOwnCasemap() {
        val sql =
            firehosePagingQuery(
                MessageVisibilitySpec(fools = setOf("Ann[ie]"), foolsMode = FoolsMode.HIDE),
                networks =
                    listOf(
                        FirehoseNetwork(7L, IrcIdentityRules.from("rfc1459", null)),
                        FirehoseNetwork(9L, IrcIdentityRules.from("ascii", null)),
                    ),
            ).sql

        assertTrue(sql.contains("(n.id = 7 AND "))
        assertTrue(sql.contains("(n.id = 9 AND "))
        // RFC1459 folds brackets to braces; ASCII leaves them alone, so the two terms differ.
        assertTrue("rfc1459 term missing folded actor", sql.contains("X'616e6e7b69657d'"))
        assertTrue("ascii term missing unfolded actor", sql.contains("X'616e6e5b69655d'"))
        // Networks the caller did not describe stay unfiltered rather than silently muted.
        assertTrue(sql.contains("n.id NOT IN (7,9)"))
    }
}
