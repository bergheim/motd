package io.github.trevarj.motd.ui.imageviewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImageViewerGestureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun zoomed_wide_image_cannot_pan_into_its_vertical_letterbox() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.size(200.dp, 400.dp)) {
                    ImageViewerContent(
                        painter = BitmapPainter(ImageBitmap(400, 100)),
                        imageReady = true,
                        imageState = null,
                        imageIdentity = "wide-fixture",
                        onBack = {},
                        onShare = {},
                        onSave = { ImageSaveFeedback.SAVED },
                    )
                }
            }
        }

        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput {
            down(0, Offset(75f, 200f))
            down(1, Offset(125f, 200f))
            moveTo(0, Offset(50f, 100f))
            moveTo(1, Offset(150f, 300f))
            up(0)
            up(1)
        }
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput {
            down(Offset(100f, 200f))
            moveTo(Offset(100f, 350f))
            up()
        }

        val node = compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).fetchSemanticsNode()
        val transform = node.config[ImageViewerTransformKey]
        val bounds = requireNotNull(
            imageTransformBounds(
                intrinsicSize = androidx.compose.ui.geometry.Size(400f, 100f),
                viewportSize = IntSize(node.layoutInfo.width, node.layoutInfo.height),
            ),
        )
        val expected = clampImageTransform(transform, bounds)

        assertTrue("pinch should zoom the fitted image", transform.scale > 1f)
        assertEquals(expected, transform)
        assertEquals("wide content remains vertically centered until it covers the viewport", 0f, transform.offsetY, 0.001f)
    }

    @Test fun save_feedback_waits_for_completion_and_allows_retry() {
        val firstResult = CompletableDeferred<ImageSaveFeedback>()
        var saveCalls = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.size(200.dp, 400.dp)) {
                    ImageViewerContent(
                        painter = BitmapPainter(ImageBitmap(200, 200)),
                        imageReady = true,
                        imageState = null,
                        imageIdentity = "save-fixture",
                        onBack = {},
                        onShare = {},
                        onSave = {
                            saveCalls += 1
                            if (saveCalls == 1) firstResult.await() else ImageSaveFeedback.SAVED
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_BUTTON_TAG).performClick()
        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG).assertDoesNotExist()
        compose.runOnIdle { firstResult.complete(ImageSaveFeedback.FAILED) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG)
            .assertTextEquals(context.getString(io.github.trevarj.motd.R.string.image_viewer_save_failed))

        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_BUTTON_TAG).performClick()
        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG)
            .assertTextEquals(context.getString(io.github.trevarj.motd.R.string.image_viewer_saved))
        assertEquals(2, saveCalls)
    }
}
