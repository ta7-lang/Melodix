package com.melodix.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Настройки 10-полосного параметрического эквалайзера. */
data class EqSettings(
    val enabled: Boolean = true,
    val gains: List<Float> = List(EqEngine.BANDS.size) { 0f },
    val preamp: Float = 0f,
    val bass: Float = 0f,
    val treble: Float = 0f,
    val width: Float = 1f,
    val limiter: Boolean = true,
    val q: Float = 1.41f,
    val presetName: String = "Ровно",
)

data class EqPreset(val name: String, val gains: List<Float>, val bass: Float = 0f, val treble: Float = 0f, val custom: Boolean = false)

/** Коэффициенты биквадратного фильтра (RBJ Audio EQ Cookbook), нормированные на a0. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** Амплитудная характеристика в дБ на частоте f. */
    fun magnitudeDb(f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2; val ni = -(b1 * sw + b2 * s2)
        val dr = 1 + a1 * cw + a2 * c2; val di = -(a1 * sw + a2 * s2)
        val num = nr * nr + ni * ni; val den = dr * dr + di * di
        return 10 * log10((num / den).coerceAtLeast(1e-20))
    }

    companion object {
        fun peaking(f: Double, q: Double, gainDb: Double, fs: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * f / fs
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val a0 = 1 + alpha / a
            return Biquad((1 + alpha * a) / a0, (-2 * cw) / a0, (1 - alpha * a) / a0, (-2 * cw) / a0, (1 - alpha / a) / a0)
        }

        fun lowShelf(f: Double, gainDb: Double, fs: Double, s: Double = 0.8): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * f / fs
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / 2 * sqrt((a + 1 / a) * (1 / s - 1) + 2)
            val sa = 2 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * cw + sa
            return Biquad(
                a * ((a + 1) - (a - 1) * cw + sa) / a0,
                2 * a * ((a - 1) - (a + 1) * cw) / a0,
                a * ((a + 1) - (a - 1) * cw - sa) / a0,
                -2 * ((a - 1) + (a + 1) * cw) / a0,
                ((a + 1) + (a - 1) * cw - sa) / a0,
            )
        }

        fun highShelf(f: Double, gainDb: Double, fs: Double, s: Double = 0.8): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * f / fs
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / 2 * sqrt((a + 1 / a) * (1 / s - 1) + 2)
            val sa = 2 * sqrt(a) * alpha
            val a0 = (a + 1) - (a - 1) * cw + sa
            return Biquad(
                a * ((a + 1) + (a - 1) * cw + sa) / a0,
                -2 * a * ((a - 1) + (a + 1) * cw) / a0,
                a * ((a + 1) + (a - 1) * cw - sa) / a0,
                2 * ((a - 1) - (a + 1) * cw) / a0,
                ((a + 1) - (a - 1) * cw - sa) / a0,
            )
        }
    }
}

object EqEngine {
    val BANDS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
    const val MAX_DB = 15f
    const val BASS_FREQ = 90.0
    const val TREBLE_FREQ = 9000.0

    private val _state = MutableStateFlow(EqSettings())
    val state: StateFlow<EqSettings> = _state

    /** Изменяется при каждом обновлении — аудиопроцессор пересчитывает коэффициенты. */
    @Volatile var version: Int = 0
        private set

    @Volatile var current: EqSettings = EqSettings()
        private set

    fun apply(s: EqSettings) {
        current = s
        _state.value = s
        version++
    }

    fun update(block: (EqSettings) -> EqSettings) = apply(block(current))

    /** Построение цепочки фильтров для заданной частоты дискретизации. */
    fun buildFilters(s: EqSettings, fs: Double): List<Biquad> {
        val list = ArrayList<Biquad>()
        val nyq = fs / 2
        if (s.bass != 0f) list += Biquad.lowShelf(BASS_FREQ, s.bass.toDouble(), fs)
        BANDS.forEachIndexed { i, f ->
            val g = s.gains.getOrElse(i) { 0f }
            if (g != 0f && f < nyq * 0.92) list += Biquad.peaking(f, s.q.toDouble(), g.toDouble(), fs)
        }
        if (s.treble != 0f && TREBLE_FREQ < nyq * 0.9) list += Biquad.highShelf(TREBLE_FREQ, s.treble.toDouble(), fs)
        return list
    }

    /** Итоговая АЧХ (дБ) для отрисовки кривой. */
    fun responseDb(s: EqSettings, f: Double, fs: Double = 48000.0): Double {
        var sum = s.preamp.toDouble()
        for (b in buildFilters(s, fs)) sum += b.magnitudeDb(f, fs)
        return sum
    }

    val builtInPresets = listOf(
        EqPreset("Ровно", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Усиление баса", listOf(6f, 5.5f, 4.5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Глубокий бас", listOf(8f, 7f, 5f, 2f, 0f, -1f, -1f, 0f, 0f, 0f), bass = 2f),
        EqPreset("Меньше баса", listOf(-6f, -5f, -4f, -2.5f, -1f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Высокие", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5.5f, 6.5f)),
        EqPreset("Вокал", listOf(-2f, -3f, -2f, 0f, 2.5f, 4f, 4f, 3f, 1f, 0f)),
        EqPreset("Рок", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 4.5f)),
        EqPreset("Поп", listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
        EqPreset("Джаз", listOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f)),
        EqPreset("Классика", listOf(4f, 3f, 2.5f, 2f, -1f, -1f, 0f, 2f, 3f, 3.5f)),
        EqPreset("Электроника", listOf(5f, 4.5f, 1.5f, 0f, -2f, 2f, 1f, 1.5f, 4.5f, 5f)),
        EqPreset("Хип-хоп", listOf(5.5f, 4.5f, 1.5f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f)),
        EqPreset("Акустика", listOf(4f, 4f, 3f, 1f, 2f, 2f, 3f, 3.5f, 3f, 2f)),
        EqPreset("Танцевальная", listOf(4f, 6.5f, 5f, 0f, 2f, 3.5f, 5f, 4.5f, 3.5f, 0f)),
        EqPreset("Фортепиано", listOf(2.5f, 1.5f, 0f, 2f, 3f, 1.5f, 3.5f, 4.5f, 3f, 3.5f)),
        EqPreset("Громкость (Loudness)", listOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -5f, 5f, 1f)),
        EqPreset("Ночной режим", listOf(-3f, -2f, -1f, 0f, 1f, 2f, 2f, 1f, -1f, -2f)),
        EqPreset("Маленькие динамики", listOf(5.5f, 4.5f, 4f, 3f, 2f, 0f, -2f, -3f, -3.5f, -4f)),
        EqPreset("Наушники", listOf(3f, 2f, 0f, -1f, -1f, 0f, 1.5f, 3f, 2f, 1f)),
        EqPreset("Подкаст / речь", listOf(-6f, -4f, -1f, 1f, 3f, 4.5f, 4f, 2f, 0f, -2f)),
        EqPreset("R&B", listOf(3f, 7f, 5.5f, 1.5f, -2f, -1.5f, 2f, 2.5f, 3f, 3.5f)),
        EqPreset("Латино", listOf(4.5f, 3f, 0f, 0f, -1.5f, -1.5f, -1.5f, 0f, 3f, 4.5f)),
        EqPreset("Лаунж", listOf(-3f, -1.5f, -0.5f, 1.5f, 4f, 2.5f, 0f, -1.5f, 2f, 1f)),
        EqPreset("Метал", listOf(4f, 5f, 3f, 0f, -2f, 0f, 2f, 4f, 5f, 4f)),
    )
}
