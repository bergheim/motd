package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryWindowBoundsTest {
    private val gaps = listOf(
        HistoryGapEntity(
            id = 1,
            roomId = 7,
            olderMsgid = null,
            olderServerTime = 100,
            newerMsgid = null,
            newerServerTime = 500,
        ),
        HistoryGapEntity(
            id = 2,
            roomId = 7,
            olderMsgid = null,
            olderServerTime = 700,
            newerMsgid = null,
            newerServerTime = 900,
        ),
    ).map { gap ->
        ResolvedHistoryGap(
            gap,
            TimelineAnchor(gap.olderServerTime, gap.olderServerTime),
            TimelineAnchor(gap.newerServerTime, gap.newerServerTime),
        )
    }

    @Test
    fun recentWindowStartsAtTheNewestKnownIsland() {
        val expected = HistoryWindowBounds(lowerBoundary = TimelineAnchor(900, 900))
        assertEquals(expected, historyWindowBounds(HistoryWindowFocus.Recent, gaps))
    }

    @Test
    fun focusedWindowIsBoundedByTheNearestGapInEachDirection() {
        assertEquals(
            HistoryWindowBounds(
                lowerBoundary = TimelineAnchor(500, 500),
                upperBoundary = TimelineAnchor(700, 700),
            ),
            historyWindowBounds(HistoryWindowFocus.Around(600), gaps),
        )
    }

    @Test
    fun equalTimestampGapSeparatesOpaqueBoundaryAnchors() {
        val gap = HistoryGapEntity(3, 7, "older", 100, "newer", 100)
        val resolved = ResolvedHistoryGap(
            gap,
            older = TimelineAnchor(100, 10, 10),
            newer = TimelineAnchor(100, 20, 20),
        )

        assertEquals(
            HistoryWindowBounds(upperBoundary = resolved.older),
            historyWindowBounds(
                HistoryWindowFocus.Around(100, eventId = 10, timelineOrder = 10),
                listOf(resolved),
            ),
        )
        assertEquals(
            HistoryWindowBounds(lowerBoundary = resolved.newer),
            historyWindowBounds(HistoryWindowFocus.Recent, listOf(resolved)),
        )
    }
}
