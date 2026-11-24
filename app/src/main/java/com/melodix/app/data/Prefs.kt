package com.melodix.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.melodix.app.playback.EqPreset
import com.melodix.app.playback.EqSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "melodix_settings")

/** Настройки оформления. */
data class ThemeSettings(
    val presetId: String = "aurora",
    val useImage: Boolean = false,
    val imagePath: String? = null,
    val imageDark: Boolean = true,
    val imageBlur: Float = 18f,
    val imageDim: Float = 0.45f,
    val imageVisible: Boolean = true,
    val adaptPlayerToCover: Boolean = true,
    val animatedBackground: Boolean = true,
    val sortMode: String = "title",
)

object Prefs {
    private val EQ = stringPreferencesKey("eq")
    private val CUSTOM_PRESETS = stringPreferencesKey("eq_custom")
    private val T_PRESET = stringPreferencesKey("t_preset")
    private val T_USE_IMAGE = booleanPreferencesKey("t_use_image")
    private val T_IMAGE = stringPreferencesKey("t_image")
    private val T_IMAGE_DARK = booleanPreferencesKey("t_image_dark")
    private val T_BLUR = floatPreferencesKey("t_blur")
    private val T_DIM = floatPreferencesKey("t_dim")
    private val T_IMG_VISIBLE = booleanPreferencesKey("t_img_visible")
    private val T_ADAPT = booleanPreferencesKey("t_adapt")
    private val T_ANIM = booleanPreferencesKey("t_anim")
    private val SORT = stringPreferencesKey("sort")
    private val MODEL_NFFT = stringPreferencesKey("custom_model_cfg")

    fun themeFlow(context: Context): Flow<ThemeSettings> = context.dataStore.data.map { p -> p.toTheme() }

    private fun Preferences.toTheme() = ThemeSettings(
        presetId = this[T_PRESET] ?: "aurora",
        useImage = this[T_USE_IMAGE] ?: false,
        imagePath = this[T_IMAGE],
        imageDark = this[T_IMAGE_DARK] ?: true,
        imageBlur = this[T_BLUR] ?: 18f,
        imageDim = this[T_DIM] ?: 0.45f,
        imageVisible = this[T_IMG_VISIBLE] ?: true,
        adaptPlayerToCover = this[T_ADAPT] ?: true,
        animatedBackground = this[T_ANIM] ?: true,
        sortMode = this[SORT] ?: "title",
    )

    suspend fun saveTheme(context: Context, t: ThemeSettings) {
        context.dataStore.edit { p ->
            p[T_PRESET] = t.presetId
            p[T_USE_IMAGE] = t.useImage
            if (t.imagePath != null) p[T_IMAGE] = t.imagePath else p.remove(T_IMAGE)
            p[T_IMAGE_DARK] = t.imageDark
            p[T_BLUR] = t.imageBlur
            p[T_DIM] = t.imageDim
            p[T_IMG_VISIBLE] = t.imageVisible
            p[T_ADAPT] = t.adaptPlayerToCover
            p[T_ANIM] = t.animatedBackground
            p[SORT] = t.sortMode
        }
    }

    // ---- Эквалайзер ----
    private fun EqSettings.encode(): String = listOf(
        enabled.toString(), gains.joinToString(","), preamp.toString(), bass.toString(),
        treble.toString(), width.toString(), limiter.toString(), q.toString(), presetName.replace("|", "/")
    ).joinToString("|")

    private fun decodeEq(s: String): EqSettings? = runCatching {
        val p = s.split("|")
        EqSettings(
            enabled = p[0].toBoolean(),
            gains = p[1].split(",").map { it.toFloat() },
            preamp = p[2].toFloat(), bass = p[3].toFloat(), treble = p[4].toFloat(),
            width = p[5].toFloat(), limiter = p[6].toBoolean(), q = p[7].toFloat(), presetName = p[8],
        )
    }.getOrNull()

    suspend fun saveEq(context: Context, s: EqSettings) {
        context.dataStore.edit { it[EQ] = s.encode() }
    }

    suspend fun loadEq(context: Context): EqSettings? =
        context.dataStore.data.first()[EQ]?.let { decodeEq(it) }

    fun customPresetsFlow(context: Context): Flow<List<EqPreset>> = context.dataStore.data.map { p ->
        (p[CUSTOM_PRESETS] ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val parts = line.split("|")
                EqPreset(parts[0], parts[1].split(",").map { it.toFloat() }, parts[2].toFloat(), parts[3].toFloat(), custom = true)
            }.getOrNull()
        }
    }

    suspend fun saveCustomPreset(context: Context, preset: EqPreset) {
        context.dataStore.edit { p ->
            val lines = (p[CUSTOM_PRESETS] ?: "").split("\n").filter { it.isNotBlank() && !it.startsWith(preset.name + "|") }
            val line = "${preset.name.replace("|", "/")}|${preset.gains.joinToString(",")}|${preset.bass}|${preset.treble}"
            p[CUSTOM_PRESETS] = (lines + line).joinToString("\n")
        }
    }

    suspend fun deleteCustomPreset(context: Context, name: String) {
        context.dataStore.edit { p ->
            p[CUSTOM_PRESETS] = (p[CUSTOM_PRESETS] ?: "").split("\n").filter { it.isNotBlank() && !it.startsWith("$name|") }.joinToString("\n")
        }
    }

    // ---- Пользовательская модель разделения: "nfft|primary|compensate" ----
    suspend fun saveModelConfig(context: Context, cfg: String) { context.dataStore.edit { it[MODEL_NFFT] = cfg } }
    suspend fun loadModelConfig(context: Context): String? = context.dataStore.data.first()[MODEL_NFFT]
}
