package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewVisibilityTest {
    @Test
    fun loadingToNegativeCompletion_keepsThePreviewFootprint() {
        assertTrue(shouldShowLinkPreview(preview = null, loading = true, resolved = false))
        assertTrue(shouldShowLinkPreview(preview = null, loading = false, resolved = true))
        assertFalse(shouldShowLinkPreview(preview = null, loading = false, resolved = false))
        assertTrue(shouldShowLinkPreview(preview = null, loading = false, resolved = false, awaiting = true))
    }

    @Test
    fun successfulPreview_isVisible() {
        assertTrue(
            shouldShowLinkPreview(
                preview =
                    LinkPreview(
                        url = "https://example.test",
                        title = "Example",
                        description = null,
                        imageUrl = null,
                        siteName = null,
                    ),
                loading = false,
                resolved = true,
            ),
        )
    }

    @Test
    fun awaiting_state_is_explicit() {
        assertSame(
            LinkPreviewRenderState.Awaiting,
            resolveLinkPreviewRenderState(preview = null, loading = false, awaiting = true),
        )
    }

    @Test
    fun loadingState_takesPrecedenceOverPreview() {
        assertSame(
            LinkPreviewRenderState.Loading,
            resolveLinkPreviewRenderState(preview = preview(imageUrl = null), loading = true),
        )
    }

    @Test
    fun completionStates_routeToAvailableFailedAndUnavailable() {
        assertEquals(
            LinkPreviewRenderState.Available(preview(imageUrl = null)),
            resolveLinkPreviewRenderState(preview = preview(imageUrl = null), loading = false),
        )
        assertSame(
            LinkPreviewRenderState.Failed,
            resolveLinkPreviewRenderState(preview = null, loading = false, failed = true),
        )
        assertSame(
            LinkPreviewRenderState.Unavailable,
            resolveLinkPreviewRenderState(preview = null, loading = false),
        )
    }

    @Test
    fun transitionKeys_distinguishPhasesButShareAvailableUpdates() {
        assertEquals(
            LinkPreviewTransitionKey.AWAITING,
            LinkPreviewRenderState.Awaiting.transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.LOADING,
            LinkPreviewRenderState.Loading.transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.AVAILABLE,
            LinkPreviewRenderState.Available(preview(imageUrl = null)).transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.AVAILABLE,
            LinkPreviewRenderState.Available(preview(imageUrl = "https://example.test/image.png")).transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.FAILED,
            LinkPreviewRenderState.Failed.transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.UNAVAILABLE,
            LinkPreviewRenderState.Unavailable.transitionKey,
        )
    }

    @Test
    fun text_preview_still_uses_the_available_render_state() {
        assertEquals(
            LinkPreviewRenderState.Available(preview(null).copy(kind = LinkPreviewKind.TEXT)),
            resolveLinkPreviewRenderState(preview(null).copy(kind = LinkPreviewKind.TEXT), loading = false),
        )
    }

    private fun preview(imageUrl: String?): LinkPreview =
        LinkPreview(
            url = "https://example.test",
            title = "Example",
            description = null,
            imageUrl = imageUrl,
            siteName = null,
        )
}
