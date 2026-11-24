package com.melodix.app.audio

import java.io.File
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class EditMode { TRIM, CUT }
enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    WAV("wav", "audio/wav", "WAV (без потерь)"),
    M4A("m4a", "audio/mp4", "M4A / AAC"),
}

data class EditParams(
    val startMs: Long,
    val endMs: Long,
    val mode: EditMode,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val gainDb: Float = 0f,
    val normalize: Boolean = false,
    val format: ExportFormat = ExportFormat.WAV,
    val bitrate: Int = 256_000,
    val reverse: Boolean = false,
)

object Waveform {
    /** Пиковые значения (0..1) для отрисовки формы волны. */
    fun peaks(pcm: PcmFile, buckets: Int = 1500): FloatArray {
        val buf = pcm.map()
        val ch = pcm.channels
        val frames = pcm.frames
        val out = FloatArray(buckets)
        val per = max(1L, frames / buckets)
        for (b in 0 until buckets) {
            val start = b * per
            val end = min(frames, start + per)
            var peak = 0
            var f = start
            // прореживание для скорости на очень длинных файлах
            val step = max(1L, (end - start) / 2000)
            while (f < end) {
                val idx = (f * ch).toInt()
                for (c in 0 until ch) { val v = abs(buf.get(idx + c).toInt()); if (v > peak) peak = v }
                f += step
            }
            out[b] = peak / 32768f
        }
        return out
    }
}

object AudioEditor {

    private class Segment(val start: Long, val end: Long) { val len get() = end - start }

    fun export(
        pcm: PcmFile, p: EditParams, workDir: File,
        onProgress: (Float) -> Unit, isCancelled: () -> Boolean,
    ): File {
        val sr = pcm.sampleRate
        val inCh = pcm.channels
        val outCh = min(inCh, 2)
        val src = pcm.map()
        val s = (p.startMs * sr / 1000).coerceIn(0, pcm.frames)
        val e = (p.endMs * sr / 1000).coerceIn(s, pcm.frames)
        val segments = when (p.mode) {
            EditMode.TRIM -> listOf(Segment(s, e))
            EditMode.CUT -> listOf(Segment(0, s), Segment(e, pcm.frames)).filter { it.len > 0 }
        }
        val outFrames = segments.sumOf { it.len }
        if (outFrames <= 0) error("Пустой результат: выделите фрагмент")

        val micro = (sr * 0.006).toLong() // 6 мс микрофейды на стыках — без щелчков
        val fadeIn = p.fadeInMs * sr / 1000
        val fadeOut = p.fadeOutMs * sr / 1000
        var gain = 10f.pow(p.gainDb / 20f)

        fun read(frame: Long, c: Int): Float {
            val cc = if (c < inCh) c else 0
            return src.get((frame * inCh + cc).toInt()) / 32768f
        }

        // Генератор выходных сэмплов (используется дважды: поиск пика и запись)
        fun forEachFrame(block: (Long, FloatArray) -> Unit) {
            val frame = FloatArray(outCh)
            var j = 0L
            for ((si, seg) in segments.withIndex()) {
                for (k in 0 until seg.len) {
                    val f = if (p.reverse) seg.end - 1 - k else seg.start + k
                    var env = 1f
                    if (fadeIn > 0 && j < fadeIn) env *= j.toFloat() / fadeIn
                    if (fadeOut > 0 && outFrames - j < fadeOut) env *= (outFrames - j).toFloat() / fadeOut
                    if (segments.size > 1) {
                        if (si > 0 && k < micro) env *= k.toFloat() / micro
                        if (si < segments.size - 1 && seg.len - k < micro) env *= (seg.len - k).toFloat() / micro
                    }
                    for (c in 0 until outCh) frame[c] = read(f, c) * env
                    block(j, frame)
                    j++
                }
            }
        }

        if (p.normalize) {
            var peak = 0f
            forEachFrame { j, fr ->
                for (v in fr) { val a = abs(v); if (a > peak) peak = a }
                if (j and 0xFFFF == 0L) onProgress(0.3f * j / outFrames)
            }
            if (peak > 1e-5f) gain = 0.966f / peak // -0.3 dBFS
        }

        val wav = File(workDir, "edit_${System.currentTimeMillis()}.wav")
        WavWriter(wav, sr, outCh).use { w ->
            forEachFrame { j, fr ->
                for (v in fr) w.writeFloat(v * gain)
                if (j and 0x7FFF == 0L) {
                    if (isCancelled()) error("Отменено")
                    onProgress(0.3f + 0.4f * j / outFrames)
                }
            }
        }
        if (p.format == ExportFormat.WAV) { onProgress(1f); return wav }

        // AAC: при необходимости понижаем частоту до 48 кГц
        var data: ShortBuffer = WavWriter.mapPcm(wav)
        var rate = sr
        if (sr > 48000) {
            data = ShortBuffer.wrap(Resampler.resample(data, outCh, sr, 48000, outCh))
            rate = 48000
        }
        val m4a = File(workDir, "edit_${System.currentTimeMillis()}.m4a")
        AacEncoder.encode(data, rate, outCh, p.bitrate, m4a, { onProgress(0.7f + 0.3f * it) }, isCancelled)
        wav.delete()
        return m4a
    }
}
