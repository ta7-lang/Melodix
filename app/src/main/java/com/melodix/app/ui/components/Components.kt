package com.melodix.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melodix.app.playback.PlayerUiState
import com.melodix.app.ui.theme.LocalVisuals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -------------------------------------------------------------------------------------
//  Обложки
// -------------------------------------------------------------------------------------

object ArtworkLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val missing = HashSet<String>()

    fun cached(uri: Uri, size: Int): Bitmap? = cache.get("$uri@$size")

    suspend fun load(ctx: Context, uri: Uri, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@$size"
        cache.get(key)?.let { return@withContext it }
        synchronized(missing) { if (key in missing) return@withContext null }
        val bmp = runCatching {
            var b: Bitmap? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content" && uri.authority == "media") {
                b = runCatching { ctx.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null) }.getOrNull()
            }
            if (b == null) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    r.embeddedPicture?.let { bytes ->
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var s = 1
                        while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
                        b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    }
                } finally { r.release() }
            }
            b
        }.getOrNull()
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing += key }
        bmp
    }

    fun invalidate() { cache.evictAll(); synchronized(missing) { missing.clear() } }
}

@Composable
fun rememberArtwork(uri: Uri?, size: Int = 256): Bitmap? {
    val ctx = LocalContext.current
    val state by produceState(initialValue = uri?.let { ArtworkLoader.cached(it, size) }, uri, size) {
        value = uri?.let { ArtworkLoader.load(ctx, it, size) }
    }
    return state
}

@Composable
fun ArtworkImage(uri: Uri?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp), sizePx: Int = 256) {
    val bmp = rememberArtwork(uri, sizePx)
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(shape)) {
        AnimatedContent(targetState = bmp, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) }, label = "art") { b ->
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(cs.primary.copy(alpha = 0.55f), cs.secondary.copy(alpha = 0.35f)))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.fillMaxSize(0.42f)) }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
//  Фон и «стеклянные» карточки
// -------------------------------------------------------------------------------------

@Composable
fun AppBackground(
    overrideGradient: List<Color>? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val v = LocalVisuals.current
    val colors = overrideGradient ?: v.palette.gradient
    val animated = v.settings.animatedBackground
    val t = if (animated) {
        val inf = rememberInfiniteTransition(label = "bg")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "bgf").value
    } else 0.3f
    val density = LocalDensity.current
    val w = with(density) { 1200.dp.toPx() }
    val h = with(density) { 2400.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = colors,
                    start = Offset(w * t * 0.6f, 0f),
                    end = Offset(w * (1f - t * 0.5f), h),
                )
            )
    ) {
        val img = v.backgroundImage
        if (img != null && v.settings.useImage && v.settings.imageVisible && overrideGradient == null) {
            BackgroundImage(img, v.settings.imageBlur, v.settings.imageDim, colors)
        }
        content()
    }
}

@Composable
private fun BackgroundImage(img: ImageBitmap, blur: Float, dim: Float, colors: List<Color>) {
    Image(
        img, null,
        Modifier
            .fillMaxSize()
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0f) Modifier.blur(blur.dp) else Modifier)
            .graphicsLayer { alpha = 1f - dim * 0.6f },
        contentScale = ContentScale.Crop,
    )
    // Градиент из цветов фото поверх картинки — элементы интерфейса сливаются с изображением
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    colors[0].copy(alpha = 0.25f + dim * 0.5f),
                    colors[1].copy(alpha = 0.35f + dim * 0.5f),
                    colors[2].copy(alpha = 0.6f + dim * 0.4f),
                )
            )
        )
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    alpha: Float = 0.55f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "press")
    Surface(
        modifier = modifier.scale(scale).clip(shape).then(
            if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick) else Modifier
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = alpha),
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f), modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

// -------------------------------------------------------------------------------------
//  Мини-плеер
// -------------------------------------------------------------------------------------

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    GlassCard(modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(24.dp), onClick = onOpen, alpha = 0.78f) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtworkImage(state.uri, Modifier.size(48.dp), RoundedCornerShape(16.dp), 160)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                AnimatedContent(state.title, transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(200))
                }, label = "mt") { title ->
                    Text(title.ifEmpty { "Ничего не играет" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(state.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayPauseButton(state.isPlaying, onToggle, Modifier.size(44.dp))
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Следующий") }
        }
        val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        val anim by animateFloatAsState(progress, tween(250), label = "mp")
        LinearProgressIndicator(
            progress = { anim },
            modifier = Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 18.dp).clip(CircleShape),
            color = cs.primary, trackColor = cs.onSurface.copy(alpha = 0.1f),
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, iconSize: Dp = 26.dp) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pp")
    Box(
        modifier
            .scale(scale)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(cs.primary, cs.secondary)))
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(playing, transitionSpec = {
            (scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.6f) + fadeIn(tween(220))) togetherWith
                (scaleOut(tween(180), targetScale = 0.6f) + fadeOut(tween(180)))
        }, label = "ppi") { p ->
            Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Пауза" else "Играть", tint = cs.onPrimary, modifier = Modifier.size(iconSize))
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTimePrecise(ms: Long): String {
    val m = ms / 60000; val s = (ms % 60000) / 1000; val c = (ms % 1000) / 10
    return "%d:%02d.%02d".format(m, s, c)
}

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp = 12.dp) = Spacer(Modifier.width(w))
