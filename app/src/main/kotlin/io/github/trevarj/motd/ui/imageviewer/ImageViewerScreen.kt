package io.github.trevarj.motd.ui.imageviewer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DOUBLE_TAP_SCALE = 2.5f
internal const val IMAGE_VIEWER_IMAGE_TAG = "image_viewer_image"
internal const val IMAGE_VIEWER_SAVE_BUTTON_TAG = "image_viewer_save_button"
internal const val IMAGE_VIEWER_SAVE_FEEDBACK_TAG = "image_viewer_save_feedback"
internal val ImageViewerTransformKey = SemanticsPropertyKey<ImageTransform>("ImageViewerTransform")
private var SemanticsPropertyReceiver.imageViewerTransform by ImageViewerTransformKey

/**
 * Full-screen image viewer (plans/07): black background, Coil image, hand-rolled pinch-zoom/pan via
 * [detectTransformGestures] + [graphicsLayer], share/save (MediaStore) actions, tap toggles chrome.
 *
 * Gestures are focal-point anchored: pinch and double-tap zoom around the touch point, and pan is
 * clamped to the scaled bounds so the image can't be flung off-screen (plans/15 #26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    url: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
    )
    val imageState = painter.state

    ImageViewerContent(
        painter = painter,
        imageReady = imageState is AsyncImagePainter.State.Success,
        imageState = imageState,
        imageIdentity = url,
        onBack = onBack,
        onShare = { shareImage(context, url) },
        onSave = { saveImage(context, url) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageViewerContent(
    painter: Painter,
    imageReady: Boolean,
    imageState: AsyncImagePainter.State?,
    imageIdentity: Any,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSave: suspend () -> ImageSaveFeedback,
) {
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(true) }
    var transform by remember { mutableStateOf(ImageTransform()) }
    var transformAnimationJob by remember { mutableStateOf<Job?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var saveFeedback by remember { mutableStateOf<ImageSaveFeedback?>(null) }
    var saveInProgress by remember { mutableStateOf(false) }
    val bounds = if (imageReady) imageTransformBounds(painter.intrinsicSize, viewportSize) else null

    fun applyTransform(candidate: ImageTransform) {
        transform = clampImageTransform(candidate, bounds)
    }

    // A newly loaded image must never inherit a previous image's transform. Layout changes retain
    // the current focal position where possible, but always re-clamp it to the new fitted bounds.
    LaunchedEffect(imageIdentity, painter, imageReady) {
        if (imageReady) {
            transformAnimationJob?.cancel()
            transformAnimationJob = null
            applyTransform(ImageTransform())
        }
    }
    LaunchedEffect(bounds) {
        // Animation frames capture the bounds from their launch composition. Stop them before a
        // viewport or intrinsic-size change so no stale frame can write an out-of-bounds value.
        transformAnimationJob?.cancel()
        transformAnimationJob = null
        applyTransform(transform)
    }

    val contentDesc = stringResource(R.string.image_viewer_content_description)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = contentDesc,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = transform.scale,
                    scaleY = transform.scale,
                    translationX = transform.offsetX,
                    translationY = transform.offsetY,
                )
                .semantics { imageViewerTransform = transform }
                .testTag(IMAGE_VIEWER_IMAGE_TAG)
                .pointerInput(bounds) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        // A direct gesture takes ownership of transform state immediately. Without
                        // cancelling the double-tap animation, both paths can write scale/offsets.
                        transformAnimationJob?.cancel()
                        transformAnimationJob = null
                        val current = transform
                        val newScale = (current.scale * zoom).coerceIn(1f, MAX_IMAGE_SCALE)
                        if (newScale > 1f) {
                            // Anchor the zoom on the gesture centroid relative to the box center.
                            val focusX = centroid.x - size.width / 2f
                            val focusY = centroid.y - size.height / 2f
                            val factor = newScale / current.scale
                            applyTransform(
                                ImageTransform(
                                    scale = newScale,
                                    offsetX = (current.offsetX + pan.x - focusX) * factor + focusX,
                                    offsetY = (current.offsetY + pan.y - focusY) * factor + focusY,
                                ),
                            )
                        } else {
                            applyTransform(ImageTransform())
                        }
                    }
                }
                .pointerInput(bounds) {
                    detectTapGestures(
                        onTap = { chromeVisible = !chromeVisible },
                        onDoubleTap = { tap ->
                            val initial = transform
                            val target = if (initial.scale > 1f) {
                                ImageTransform()
                            } else {
                                // Zoom toward the tapped point, not the center (plans/15 #26).
                                val focusX = tap.x - size.width / 2f
                                val focusY = tap.y - size.height / 2f
                                ImageTransform(
                                    scale = DOUBLE_TAP_SCALE,
                                    offsetX = -focusX * (DOUBLE_TAP_SCALE - 1f),
                                    offsetY = -focusY * (DOUBLE_TAP_SCALE - 1f),
                                )
                            }
                            val clampedTarget = clampImageTransform(target, bounds)
                            transformAnimationJob?.cancel()
                            transformAnimationJob = scope.launch {
                                coroutineScope {
                                    launch {
                                        animate(
                                            initialValue = initial.scale,
                                            targetValue = clampedTarget.scale,
                                            animationSpec = MotdMotion.softSpring,
                                        ) { value, _ -> applyTransform(transform.copy(scale = value)) }
                                    }
                                    launch {
                                        animate(
                                            initialValue = initial.offsetX,
                                            targetValue = clampedTarget.offsetX,
                                            animationSpec = MotdMotion.softSpring,
                                        ) { value, _ -> applyTransform(transform.copy(offsetX = value)) }
                                    }
                                    launch {
                                        animate(
                                            initialValue = initial.offsetY,
                                            targetValue = clampedTarget.offsetY,
                                            animationSpec = MotdMotion.softSpring,
                                        ) { value, _ -> applyTransform(transform.copy(offsetY = value)) }
                                    }
                                }
                            }
                        },
                    )
                },
        )

        // Loading / error affordances (plans/15 #26).
        when (imageState) {
            is AsyncImagePainter.State.Loading ->
                CircularProgressIndicator(color = Color.White)
            is AsyncImagePainter.State.Error ->
                Text(
                    text = stringResource(R.string.image_viewer_load_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            else -> Unit
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.image_viewer_back),
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.image_viewer_share),
                            tint = Color.White,
                        )
                    }
                    // MediaStore RELATIVE_PATH is API 29+; pre-29 would need WRITE_EXTERNAL_STORAGE,
                    // so hide Save there rather than request a legacy permission (plans/15 #26).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        IconButton(
                            enabled = !saveInProgress,
                            onClick = {
                                saveFeedback = null
                                saveInProgress = true
                                scope.launch {
                                    try {
                                        saveFeedback = onSave()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        saveFeedback = ImageSaveFeedback.FAILED
                                    } finally {
                                        saveInProgress = false
                                    }
                                }
                            },
                            modifier = Modifier.testTag(IMAGE_VIEWER_SAVE_BUTTON_TAG),
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = stringResource(R.string.image_viewer_save),
                                tint = Color.White,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }

        saveFeedback?.let { feedback ->
            Text(
                text = stringResource(
                    if (feedback == ImageSaveFeedback.SAVED) R.string.image_viewer_saved
                    else R.string.image_viewer_save_failed,
                ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG),
            )
        }
    }
}

/** Share the image URL via a plain-text intent (viewers resolve the link). */
private fun shareImage(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.image_viewer_share_chooser)),
    )
}

/**
 * Stream the image into a pending MediaStore row. The result is shown only after finalization.
 * Only called on API 29+ (the caller hides Save below Q).
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private suspend fun saveImage(context: Context, url: String): ImageSaveFeedback {
    val result = withContext(Dispatchers.IO) {
        ImageSaveOperation(
            connectionFactory = UrlConnectionImageSaveConnectionFactory(),
            store = MediaStoreImageSaveStore(context.contentResolver),
        ).save(url)
    }
    return result.feedback()
}

@Preview
@Composable
private fun ImageViewerPreview() {
    ImageViewerScreen(url = "https://example.com/cat.png")
}
