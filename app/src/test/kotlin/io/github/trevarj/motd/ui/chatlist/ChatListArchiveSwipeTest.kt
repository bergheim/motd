package io.github.trevarj.motd.ui.chatlist

import androidx.compose.material3.SwipeToDismissBoxValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListArchiveSwipeTest {
    @Test
    fun `archive swipe requires sixty five percent of row width`() {
        assertEquals(130f, archiveSwipePositionalThreshold(200f), 0f)
        assertEquals(0.65f, CHAT_LIST_ARCHIVE_SWIPE_THRESHOLD_FRACTION, 0f)
    }

    @Test
    fun `haptic fires once per end to start arming`() {
        assertFalse(shouldPerformArchiveSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.Settled, enabled = true))
        assertFalse(shouldPerformArchiveSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.StartToEnd, enabled = true))
        assertTrue(shouldPerformArchiveSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.EndToStart, enabled = true))
        assertFalse(shouldPerformArchiveSwipeHaptic(SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.EndToStart, enabled = true))
        assertFalse(shouldPerformArchiveSwipeHaptic(SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.Settled, enabled = true))
        assertTrue(shouldPerformArchiveSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.EndToStart, enabled = true))
        assertFalse(shouldPerformArchiveSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.EndToStart, enabled = false))
    }
}
