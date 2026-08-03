package io.github.trevarj.motd.ui.chat

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.util.LruCache
import android.util.Xml
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.components.isAppliedThemeDark
import io.github.trevarj.motd.ui.theme.contrastSafeOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmlpull.v1.XmlPullParser

/** Theme-adaptive gradient plus a stable, repeated monochrome SVG tile. */
@Composable
fun ChatWallpaperBackground(
    wallpaper: WallpaperSelection,
    modifier: Modifier = Modifier,
) {
    if (wallpaper.preset == ChatWallpaperPreset.NONE) {
        Box(modifier)
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current.density
    val requestedTileKey = remember(wallpaper.preset, density) {
        wallpaperTileKey(wallpaper.preset, density)
    }
    val tile by produceState<WallpaperTile?>(
        initialValue = WallpaperTileCache[requestedTileKey]?.let { WallpaperTile(requestedTileKey, it) },
        requestedTileKey,
    ) {
        value = WallpaperTile(
            requestedTileKey,
            withContext(Dispatchers.Default) {
                WallpaperTileCache.getOrRender(context.assets, requestedTileKey)
            },
        )
    }
    // A preset switch keeps the previous complete wallpaper until its replacement tile is ready.
    val renderedPreset = tile?.key?.preset ?: wallpaper.preset
    val dark = isAppliedThemeDark()
    val scheme = MaterialTheme.colorScheme
    val base = scheme.background
    val foregrounds = listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant)
    val gradient = remember(renderedPreset, scheme) {
        gradientColors(
            renderedPreset,
            base,
            scheme.primary,
            scheme.secondary,
            scheme.tertiary,
            foregrounds,
        )
    }
    val maxAlpha = wallpaperPatternMaxAlpha(
        dark = dark,
        trueBlack = base.toArgb() == AndroidColor.BLACK,
    )
    val pattern = contrastSafeOverlay(
        base = base,
        overlay = scheme.onSurfaceVariant,
        requestedAlpha = maxAlpha * wallpaper.intensity.coerceIn(0, 100) / 100f,
        foregrounds = foregrounds,
    )
    var gradientCoverage by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                val expanded = expandedWallpaperCoverage(gradientCoverage, it)
                if (gradientCoverage != expanded) gradientCoverage = expanded
            }
            .drawWithCache {
                val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
                val coverage = expandedWallpaperCoverage(gradientCoverage, canvasSize)
                val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        coverage.width.toFloat(),
                        coverage.height.toFloat(),
                        gradient.map(Color::toArgb).toIntArray(),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                }
                val patternPaint = tile?.bitmap?.let { bitmap ->
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                        colorFilter = PorterDuffColorFilter(pattern.toArgb(), PorterDuff.Mode.SRC_IN)
                    }
                }
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, gradientPaint)
                        patternPaint?.let {
                            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it)
                        }
                    }
                }
            },
    )
}

/** Warm the selected motif before a chat destination needs to draw it. */
@Composable
internal fun PreloadChatWallpaperTile(wallpaper: WallpaperSelection) {
    if (wallpaper.preset == ChatWallpaperPreset.NONE) return
    val assets = LocalContext.current.assets
    val density = LocalDensity.current.density
    val key = remember(wallpaper.preset, density) { wallpaperTileKey(wallpaper.preset, density) }
    LaunchedEffect(key) {
        withContext(Dispatchers.Default) { WallpaperTileCache.getOrRender(assets, key) }
    }
}

private fun gradientColors(
    preset: ChatWallpaperPreset,
    base: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    foregrounds: List<Color>,
): List<Color> {
    // AMOLED remains genuinely black: the pattern supplies texture without colored surface glow.
    if (base.toArgb() == AndroidColor.BLACK) return List(4) { base }
    val accents = when (preset) {
        ChatWallpaperPreset.CHATTER -> listOf(primary, tertiary, secondary)
        ChatWallpaperPreset.CHANNELS -> listOf(secondary, primary, tertiary)
        ChatWallpaperPreset.TERMINAL -> listOf(primary, secondary, tertiary)
        ChatWallpaperPreset.RELAY -> listOf(secondary, tertiary, primary)
        ChatWallpaperPreset.SIGNALS -> listOf(tertiary, primary, secondary)
        ChatWallpaperPreset.PIXELS -> listOf(primary, tertiary, secondary)
        ChatWallpaperPreset.NONE -> listOf(base, base, base)
    }
    return listOf(.06f, .04f, .05f, .03f).mapIndexed { index, alpha ->
        contrastSafeOverlay(base, accents[index % accents.size], alpha, foregrounds).compositeOver(base)
    }
}

internal fun wallpaperPatternMaxAlpha(dark: Boolean, trueBlack: Boolean): Float = when {
    trueBlack -> 0.06f
    dark -> 0.12f
    else -> 0.10f
}

private data class PatternPath(
    val path: AndroidPath,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float,
    val strokeWidth: Float,
    val opacity: Float,
)

internal data class WallpaperTileKey(
    val preset: ChatWallpaperPreset,
    val tileSizePx: Int,
)

private data class WallpaperTile(val key: WallpaperTileKey, val bitmap: Bitmap)

private object WallpaperTileCache : LruCache<WallpaperTileKey, Bitmap>(4 * 1024) {
    private val renderMutex = Mutex()

    override fun sizeOf(key: WallpaperTileKey, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)

    suspend fun getOrRender(assets: AssetManager, key: WallpaperTileKey): Bitmap {
        get(key)?.let { return it }
        return renderMutex.withLock {
            get(key) ?: renderWallpaperTile(assets, key).also { put(key, it) }
        }
    }
}

private object PatternCache {
    private val cache = mutableMapOf<ChatWallpaperPreset, List<PatternPath>>()
    @Synchronized fun get(preset: ChatWallpaperPreset): List<PatternPath>? = cache[preset]
    @Synchronized fun put(preset: ChatWallpaperPreset, value: List<PatternPath>) { cache[preset] = value }
}

internal fun renderWallpaperTile(assets: AssetManager, key: WallpaperTileKey): Bitmap {
    // The mask is color-independent, so theme and intensity changes never rebuild GPU texture data.
    val bitmap = createBitmap(key.tileSizePx, key.tileSizePx, Bitmap.Config.ALPHA_8)
    val canvas = AndroidCanvas(bitmap)
    val paths = PatternCache.get(key.preset) ?: parsePattern(assets, key.preset).also {
        PatternCache.put(key.preset, it)
    }
    val tileScale = key.tileSizePx / SVG_TILE_SIZE
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (item in paths) {
        paint.alpha = (255 * item.opacity).toInt().coerceIn(0, 255)
        paint.strokeWidth = item.strokeWidth
        canvas.withTranslation(item.x * tileScale, item.y * tileScale) {
            withRotation(item.rotation, 32f * tileScale, 32f * tileScale) {
                withScale(item.scale * tileScale, item.scale * tileScale) {
                    drawPath(item.path, paint)
                }
            }
        }
    }
    return bitmap
}

internal fun wallpaperTileKey(
    preset: ChatWallpaperPreset,
    density: Float,
): WallpaperTileKey = WallpaperTileKey(
    preset = preset,
    tileSizePx = (TILE_SIZE_DP * density).toInt().coerceAtLeast(1),
)

/** Keep the gradient stationary when a transient inset shrinks the chat viewport. */
internal fun expandedWallpaperCoverage(current: IntSize, measured: IntSize): IntSize = IntSize(
    width = maxOf(current.width, measured.width),
    height = maxOf(current.height, measured.height),
)

internal fun assetName(preset: ChatWallpaperPreset): String = when (preset) {
    ChatWallpaperPreset.NONE -> error("NONE has no SVG asset")
    ChatWallpaperPreset.CHATTER -> "chatter.svg"
    ChatWallpaperPreset.CHANNELS -> "channels.svg"
    ChatWallpaperPreset.TERMINAL -> "terminal.svg"
    ChatWallpaperPreset.RELAY -> "relay.svg"
    ChatWallpaperPreset.SIGNALS -> "signals.svg"
    ChatWallpaperPreset.PIXELS -> "pixels.svg"
}

private fun parsePattern(assets: AssetManager, preset: ChatWallpaperPreset): List<PatternPath> {
    val parser = Xml.newPullParser()
    assets.open("chat-wallpapers/${assetName(preset)}").use { input ->
        parser.setInput(input, "UTF-8")
        val out = ArrayList<PatternPath>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "path") {
                val d = parser.getAttributeValue(null, "d") ?: error("wallpaper path missing d")
                require(parser.getAttributeValue(null, "stroke") == "#000000") { "wallpaper paths must be black" }
                val transform = parseTransform(parser.getAttributeValue(null, "transform").orEmpty())
                out += PatternPath(
                    path = AndroidPath(PathParser().parsePathString(d).toPath().asAndroidPath()),
                    x = transform[0], y = transform[1], rotation = transform[2], scale = transform[3],
                    strokeWidth = parser.getAttributeValue(null, "stroke-width")?.toFloatOrNull() ?: 4f,
                    opacity = parser.getAttributeValue(null, "stroke-opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                )
            }
            parser.next()
        }
        require(out.isNotEmpty()) { "wallpaper asset is empty" }
        return out
    }
}

private val TRANSFORM = Regex(
    "translate\\(([-.0-9]+) ([-.0-9]+)\\) rotate\\(([-.0-9]+) 32 32\\) scale\\(([-.0-9]+)\\)",
)

private fun parseTransform(value: String): FloatArray {
    val match = requireNotNull(TRANSFORM.matchEntire(value)) { "unsupported wallpaper transform: $value" }
    return FloatArray(4) { match.groupValues[it + 1].toFloat() }
}

private const val SVG_TILE_SIZE = 512f
private const val TILE_SIZE_DP = 244f
