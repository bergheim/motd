package io.github.trevarj.motd

import org.junit.Assert.assertFalse
import org.junit.Test

class MainActivityUiStateTest {
    @Test
    fun initialRemoteContentStateFailsClosedUntilPreferencesLoad() {
        val previews = MainActivityUiState().contentPreviews

        assertFalse(previews.showImages)
        assertFalse(previews.showLinkPreviews)
        assertFalse(previews.autoLoadOnUnmetered)
        assertFalse(previews.autoLoadOnMetered)
    }
}
