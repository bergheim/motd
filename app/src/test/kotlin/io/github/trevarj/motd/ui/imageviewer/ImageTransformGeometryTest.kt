package io.github.trevarj.motd.ui.imageviewer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageTransformGeometryTest {
    @Test fun `wide image in portrait keeps its letterboxed axis centered`() {
        val bounds = requireNotNull(imageTransformBounds(Size(400f, 100f), IntSize(200, 400)))

        assertEquals(200f, bounds.fittedWidth)
        assertEquals(50f, bounds.fittedHeight)
        assertEquals(ImageTransform(2.5f, 150f, 0f), clampImageTransform(ImageTransform(2.5f, 999f, 999f), bounds))
    }

    @Test fun `tall image in landscape keeps its letterboxed axis centered`() {
        val bounds = requireNotNull(imageTransformBounds(Size(100f, 400f), IntSize(400, 200)))

        assertEquals(50f, bounds.fittedWidth)
        assertEquals(200f, bounds.fittedHeight)
        assertEquals(ImageTransform(2.5f, 0f, -150f), clampImageTransform(ImageTransform(2.5f, -999f, -999f), bounds))
    }

    @Test fun `square image uses its fitted dimensions for both pan axes`() {
        val bounds = requireNotNull(imageTransformBounds(Size(100f, 100f), IntSize(200, 400)))

        assertEquals(200f, bounds.fittedWidth)
        assertEquals(200f, bounds.fittedHeight)
        assertEquals(ImageTransform(3f, 200f, 100f), clampImageTransform(ImageTransform(3f, 999f, 999f), bounds))
    }

    @Test fun `exact fit permits pan only after scaling`() {
        val bounds = requireNotNull(imageTransformBounds(Size(400f, 200f), IntSize(200, 100)))

        assertEquals(ImageTransform(), clampImageTransform(ImageTransform(1f, 5f, -5f), bounds))
        assertEquals(ImageTransform(2f, -100f, 50f), clampImageTransform(ImageTransform(2f, -999f, 999f), bounds))
    }

    @Test fun `scale one always clears translation`() {
        val bounds = requireNotNull(imageTransformBounds(Size(400f, 100f), IntSize(200, 400)))

        assertEquals(ImageTransform(), clampImageTransform(ImageTransform(1f, 1f, -1f), bounds))
    }

    @Test fun `maximum scale is enforced before calculating pan`() {
        val bounds = requireNotNull(imageTransformBounds(Size(400f, 100f), IntSize(200, 400)))

        assertEquals(
            ImageTransform(MAX_IMAGE_SCALE, 400f, 0f),
            clampImageTransform(ImageTransform(99f, 999f, 999f), bounds),
        )
    }

    @Test fun `viewport resize re-clamps an existing transform to the new fitted image`() {
        val original = requireNotNull(imageTransformBounds(Size(400f, 200f), IntSize(200, 100)))
        val resized = requireNotNull(imageTransformBounds(Size(400f, 200f), IntSize(200, 400)))
        val atOriginalEdge = clampImageTransform(ImageTransform(MAX_IMAGE_SCALE, 400f, 200f), original)

        assertEquals(ImageTransform(MAX_IMAGE_SCALE, 400f, 50f), clampImageTransform(atOriginalEdge, resized))
    }

    @Test fun `invalid or zero dimensions reset instead of producing a non-finite transform`() {
        assertNull(imageTransformBounds(Size.Unspecified, IntSize(200, 400)))
        assertNull(imageTransformBounds(Size(Float.NaN, 100f), IntSize(200, 400)))
        assertNull(imageTransformBounds(Size(100f, 100f), IntSize.Zero))

        assertEquals(ImageTransform(), clampImageTransform(ImageTransform(Float.NaN, Float.NaN, Float.NaN), null))
    }
}
