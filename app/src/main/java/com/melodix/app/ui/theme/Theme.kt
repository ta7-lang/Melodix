package com.melodix.app.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.melodix.app.data.ThemeSettings
import java.io.File

@Immutable
data class ThemePreset(
    val id: String,
    val name: String,
    val dark: Boolean,
    val primary: Color,
    val secondary: Color,
    val gradient: List<Color>,
)

object ThemePresets {
    private fun c(hex: Long) = Color(hex)
    val all = listOf(
        ThemePreset("aurora", "Аврора", true, c(0xFF9D8CFF), c(0xFF5CE1E6), listOf(c(0xFF120B2E), c(0xFF1F1452), c(0xFF0B3B4A))),
        ThemePreset("midnight", "Полночь", true, c(0xFF7AA7FF), c(0xFFB28DFF), listOf(c(0xFF05070F), c(0xFF0E1630), c(0xFF161B3D))),
        ThemePreset("sunset", "Закат", true, c(0xFFFFA36C), c(0xFFFF6B9A), listOf(c(0xFF2B0F1E), c(0xFF4A1830), c(0xFF5C2A12))),
        ThemePreset("ocean", "Океан", true, c(0xFF4FD1FF), c(0xFF5EF0C0), listOf(c(0xFF031726), c(0xFF06304A), c(0xFF0A4A5C))),
        ThemePreset("forest", "Лес", true, c(0xFF7EE08A), c(0xFFD4E157), listOf(c(0xFF07160C), c(0xFF0F2A17), c(0xFF1D3B1A))),
        ThemePreset("neon", "Неон", true, c(0xFFFF3EA5), c(0xFF00F0FF), listOf(c(0xFF0A0014), c(0xFF22003A), c(0xFF00213A))),
        ThemePreset("lavender", "Лаванда", true, c(0xFFD0B3FF), c(0xFFFFB3E6), listOf(c(0xFF1A1426), c(0xFF2C2142), c(0xFF3A2440))),
        ThemePreset("coffee", "Кофе", true, c(0xFFE0B48A), c(0xFFF2D3A0), listOf(c(0xFF1A110B), c(0xFF2E1E14), c(0xFF3B2618))),
        ThemePreset("sakura", "Сакура", false, c(0xFFC2185B), c(0xFF8E24AA), listOf(c(0xFFFFF0F5), c(0xFFFFD6E7), c(0xFFF3E0FF))),
        ThemePreset("ice", "Лёд", false, c(0xFF0277BD), c(0xFF00897B), listOf(c(0xFFF2FBFF), c(0xFFD9F1FF), c(0xFFE0FFF8))),
        ThemePreset("lava", "Лава", true, c(0xFFFF5A36), c(0xFFFFC23D), listOf(c(0xFF140504), c(0xFF3A0B05), c(0xFF4D1A00))),
        ThemePreset("emerald", "Изумруд", true, c(0xFF2EE6A6), c(0xFF8BF7E0), listOf(c(0xFF02140F), c(0xFF043024), c(0xFF0A3D35))),
        ThemePreset("graphite", "Графит", true, c(0xFFE6E6E6), c(0xFF9E9E9E), listOf(c(0xFF0C0C0D), c(0xFF1A1A1C), c(0xFF26262A))),
        ThemePreset("sand", "Песок", false, c(0xFF8D5A2B), c(0xFFB5763A), listOf(c(0xFFFFF8EC), c(0xFFF6E7CF), c(0xFFF0DCC0))),
        ThemePreset("cosmos", "Космос", true, c(0xFFB388FF), c(0xFFFF80AB), listOf(c(0xFF02010A), c(0xFF140A33), c(0xFF2A0E3D))),
        ThemePreset("mint", "Мята", false, c(0xFF00796B), c(0xFF388E3C), listOf(c(0xFFEFFFF9), c(0xFFD2F7EA), c(0xFFE6FBEF))),
        ThemePreset("purple", "Пурпур", true, c(0xFFE040FB), c(0xFF7C4DFF), listOf(c(0xFF14001C), c(0xFF2D0040), c(0xFF1A0A4A))),
        ThemePreset("amber", "Янтарь", true, c(0xFFFFC107), c(0xFFFF9800), listOf(c(0xFF140F00), c(0xFF2E2100), c(0xFF3D2400))),
        ThemePreset("snow", "Снег", false, c(0xFF3F51B5), c(0xFF7E57C2), listOf(c(0xFFFFFFFF), c(0xFFF1F3FA), c(0xFFE8EAF6))),
        ThemePreset("ruby", "Рубин", true, c(0xFFFF5370), c(0xFFFF8A80), listOf(c(0xFF1A0006), c(0xFF3A0010), c(0xFF2A0820))),
        ThemePreset("arctic", "Северное сияние", true, c(0xFF69F0AE), c(0xFF40C4FF), listOf(c(0xFF001018), c(0xFF00261F), c(0xFF0A1F3D))),
        ThemePreset("peach", "Персик", false, c(0xFFE65100), c(0xFFD84315), listOf(c(0xFFFFF5EE), c(0xFFFFE0CC), c(0xFFFFD5D5))),
        ThemePreset("deepsea", "Глубина", true, c(0xFF18FFFF), c(0xFF448AFF), listOf(c(0xFF000814), c(0xFF001D3D), c(0xFF003566))),
        ThemePreset("vinyl", "Винил", true, c(0xFFFFD54F), c(0xFFFF7043), listOf(c(0xFF101010), c(0xFF1E1A14), c(0xFF2A1F12))),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

/** Итоговая палитра приложения. */
@Immutable
data class AppPalette(
    val dark: Boolean,
    val primary: Color,
    val secondary: Color,
    val gradient: List<Color>,
)

@Immutable
data class AppVisuals(
    val palette: AppPalette,
    val backgroundImage: ImageBitmap?,
    val settings: ThemeSettings,
)

val LocalVisuals = staticCompositionLocalOf<AppVisuals> { error("no visuals") }

object ImageTheme {
    /** Загружает картинку темы с уменьшением (чтобы не тратить память). */
    fun loadBitmap(path: String, maxSide: Int = 1440): Bitmap? = runCatching {
        val f = File(path)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / sample > maxSide || o.outHeight / sample > maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Извлекает цветовую гамму фото и строит из неё палитру и градиент. */
    fun paletteFrom(bitmap: Bitmap, dark: Boolean): AppPalette {
        val p = Palette.from(bitmap).maximumColorCount(24).generate()
        fun sw(vararg c: Int?): Color = Color(c.firstOrNull { it != null } ?: 0xFF6750A4.toInt())
        val vibrant = sw(p.vibrantSwatch?.rgb, p.lightVibrantSwatch?.rgb, p.dominantSwatch?.rgb)
        val dv = sw(p.darkVibrantSwatch?.rgb, p.darkMutedSwatch?.rgb, p.dominantSwatch?.rgb)
        val muted = sw(p.mutedSwatch?.rgb, p.dominantSwatch?.rgb)
        val lv = sw(p.lightVibrantSwatch?.rgb, p.lightMutedSwatch?.rgb, p.vibrantSwatch?.rgb)
        val dominant = sw(p.dominantSwatch?.rgb)
        return if (dark) {
            AppPalette(
                dark = true,
                primary = ensureLight(lv),
                secondary = ensureLight(vibrant),
                gradient = listOf(lerp(dv, Color.Black, 0.55f), lerp(dominant, Color.Black, 0.6f), lerp(muted, Color.Black, 0.5f)),
            )
        } else {
            AppPalette(
                dark = false,
                primary = ensureDark(dv),
                secondary = ensureDark(vibrant),
                gradient = listOf(lerp(lv, Color.White, 0.7f), lerp(dominant, Color.White, 0.75f), lerp(muted, Color.White, 0.7f)),
            )
        }
    }

    fun ensureLight(c: Color): Color { var x = c; var i = 0; while (x.luminance() < 0.38f && i < 10) { x = lerp(x, Color.White, 0.18f); i++ }; return x }
    fun ensureDark(c: Color): Color { var x = c; var i = 0; while (x.luminance() > 0.22f && i < 10) { x = lerp(x, Color.Black, 0.18f); i++ }; return x }
}

fun paletteFromPreset(p: ThemePreset) = AppPalette(p.dark, p.primary, p.secondary, p.gradient)

private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White

fun buildScheme(p: AppPalette): ColorScheme {
    val base = p.gradient.first()
    return if (p.dark) {
        val surface = lerp(base, Color.White, 0.07f)
        darkColorScheme(
            primary = p.primary, onPrimary = onColor(p.primary),
            primaryContainer = lerp(p.primary, Color.Black, 0.55f), onPrimaryContainer = Color.White,
            secondary = p.secondary, onSecondary = onColor(p.secondary),
            secondaryContainer = lerp(p.secondary, Color.Black, 0.6f), onSecondaryContainer = Color.White,
            tertiary = lerp(p.primary, p.secondary, 0.5f),
            background = base, onBackground = Color(0xFFF3F2F8),
            surface = surface, onSurface = Color(0xFFF3F2F8),
            surfaceVariant = lerp(base, Color.White, 0.13f), onSurfaceVariant = Color(0xFFC9C6D6),
            surfaceContainer = lerp(base, Color.White, 0.10f),
            surfaceContainerHigh = lerp(base, Color.White, 0.14f),
            surfaceContainerHighest = lerp(base, Color.White, 0.18f),
            surfaceContainerLow = lerp(base, Color.White, 0.06f),
            outline = Color(0x66FFFFFF), outlineVariant = Color(0x33FFFFFF),
        )
    } else {
        val surface = lerp(base, Color.White, 0.6f)
        lightColorScheme(
            primary = p.primary, onPrimary = onColor(p.primary),
            primaryContainer = lerp(p.primary, Color.White, 0.75f), onPrimaryContainer = Color(0xFF1A1A1A),
            secondary = p.secondary, onSecondary = onColor(p.secondary),
            secondaryContainer = lerp(p.secondary, Color.White, 0.78f), onSecondaryContainer = Color(0xFF1A1A1A),
            tertiary = lerp(p.primary, p.secondary, 0.5f),
            background = base, onBackground = Color(0xFF17151C),
            surface = surface, onSurface = Color(0xFF17151C),
            surfaceVariant = lerp(base, Color.Black, 0.06f), onSurfaceVariant = Color(0xFF4B4757),
            surfaceContainer = lerp(base, Color.White, 0.5f),
            surfaceContainerHigh = lerp(base, Color.White, 0.4f),
            surfaceContainerHighest = lerp(base, Color.White, 0.3f),
            surfaceContainerLow = lerp(base, Color.White, 0.7f),
            outline = Color(0x55000000), outlineVariant = Color(0x22000000),
        )
    }
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    )
}

/** Плавно анимирует все цвета палитры при смене темы. */
@Composable
fun animatePalette(target: AppPalette): AppPalette {
    val spec = tween<Color>(durationMillis = 700)
    val primary by animateColorAsState(target.primary, spec, label = "p")
    val secondary by animateColorAsState(target.secondary, spec, label = "s")
    val g0 by animateColorAsState(target.gradient.getOrElse(0) { Color.Black }, spec, label = "g0")
    val g1 by animateColorAsState(target.gradient.getOrElse(1) { Color.Black }, spec, label = "g1")
    val g2 by animateColorAsState(target.gradient.getOrElse(2) { Color.Black }, spec, label = "g2")
    return AppPalette(target.dark, primary, secondary, listOf(g0, g1, g2))
}

@Composable
fun MelodixTheme(visuals: AppVisuals, content: @Composable () -> Unit) {
    val animated = animatePalette(visuals.palette)
    CompositionLocalProvider(LocalVisuals provides visuals.copy(palette = animated)) {
        MaterialTheme(
            colorScheme = buildScheme(animated),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

fun Bitmap.toImage(): ImageBitmap = asImageBitmap()
