package io.github.trevarj.motd.ui.imageviewer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

internal const val MAX_IMAGE_SCALE = 5f

/** A scale and translation applied to the fitted image, in viewport pixels. */
internal data class ImageTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/** The exact fitted image dimensions and viewport used to keep image content covering the view. */
internal data class ImageTransformBounds(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
)

/**
 * Derive the dimensions [ContentScale.Fit] will render for a valid intrinsic image and viewport.
 * Invalid or unavailable dimensions deliberately produce no bounds so callers reset safely.
 */
internal fun imageTransformBounds(intrinsicSize: Size, viewportSize: IntSize): ImageTransformBounds? {
    val imageWidth = intrinsicSize.width
    val imageHeight = intrinsicSize.height
    val viewportWidth = viewportSize.width.toFloat()
    val viewportHeight = viewportSize.height.toFloat()
    if (
        !imageWidth.isFinite() ||
        !imageHeight.isFinite() ||
        imageWidth <= 0f ||
        imageHeight <= 0f ||
        viewportWidth <= 0f ||
        viewportHeight <= 0f
    ) {
        return null
    }

    val fitScale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val fittedWidth = imageWidth * fitScale
    val fittedHeight = imageHeight * fitScale
    if (!fitScale.isFinite() || !fittedWidth.isFinite() || !fittedHeight.isFinite()) return null

    return ImageTransformBounds(viewportWidth, viewportHeight, fittedWidth, fittedHeight)
}

/**
 * Clamp one transform against fitted content. This is the single transform boundary for gesture,
 * double-tap, animation, and layout/painter updates.
 */
internal fun clampImageTransform(
    transform: ImageTransform,
    bounds: ImageTransformBounds?,
): ImageTransform {
    if (bounds == null) return ImageTransform()

    val scale = transform.scale.takeIf(Float::isFinite)?.coerceIn(1f, MAX_IMAGE_SCALE) ?: 1f
    val maxX = ((scale * bounds.fittedWidth - bounds.viewportWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((scale * bounds.fittedHeight - bounds.viewportHeight) / 2f).coerceAtLeast(0f)
    val offsetX = if (maxX == 0f) {
        0f
    } else {
        transform.offsetX.takeIf(Float::isFinite)?.coerceIn(-maxX, maxX) ?: 0f
    }
    val offsetY = if (maxY == 0f) {
        0f
    } else {
        transform.offsetY.takeIf(Float::isFinite)?.coerceIn(-maxY, maxY) ?: 0f
    }

    return ImageTransform(scale, offsetX, offsetY)
}
