package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.prefs.FoolsMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure string assertions on the generated firehose SQL; no database involved. */
class FirehosePagingQueryTest {

    @Test
    fun keepsConversationKindsAndDropsJoinPart() {
        val sql = firehosePagingQuery(MessageVisibilitySpec(), networks = emptyList()).sql
        assertTrue(sql.contains("'PRIVMSG'"))
        assertTrue(sql.contains("'ACTION'"))
        assertTrue(sql.contains("'NOTICE'"))
        assertFalse(sql.contains("'JOIN'"))
        assertFalse(sql.contains("'PART'"))
        assertTrue(sql.contains("ORDER BY m.serverTime DESC, m.id DESC"))
        // Projection columns the FirehoseRow embeds beyond m.*.
        assertTrue(sql.contains("b.displayName AS bufferDisplayName"))
        assertTrue(sql.contains("n.name AS networkName"))
        assertTrue(sql.contains("n.protocol AS networkProtocol"))
    }

    @Test
    fun foolClauseIsTrivialWhenNoFoolsConfigured() {
        val sql = firehosePagingQuery(
            MessageVisibilitySpec(fools = emptySet()),
            networks = listOf(FirehoseNetwork(1L), FirehoseNetwork(2L)),
        ).sql
        assertTrue(sql.contains("AND 1 ORDER BY"))
        assertFalse(sql.contains("n.id ="))
    }

    @Test
    fun foolClauseIsTrivialWhenNoNetworksEvenWithFools() {
        val sql = firehosePagingQuery(
            MessageVisibilitySpec(fools = setOf("troll"), foolsMode = FoolsMode.HIDE),
            networks = emptyList(),
        ).sql
        assertTrue(sql.contains("AND 1 ORDER BY"))
    }

    @Test
    fun foolsComposePerNetworkScopedClause() {
        val sql = firehosePagingQuery(
            MessageVisibilitySpec(fools = setOf("troll"), foolsMode = FoolsMode.HIDE),
            networks = listOf(FirehoseNetwork(7L), FirehoseNetwork(9L)),
        ).sql
        // Each network gets its own predicate, joined by OR.
        assertTrue(sql.contains("(n.id = 7 AND "))
        assertTrue(sql.contains("(n.id = 9 AND "))
        // Networks with no fools represented are left unfiltered.
        assertTrue(sql.contains("n.id NOT IN (7,9)"))
        // The not-fool predicate compares against the normalized actor blob.
        assertTrue(sql.contains("normalizedActor"))
    }
}
