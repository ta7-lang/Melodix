package com.melodix.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Собственный DSP-эквалайзер, встроенный в аудиоконвейер ExoPlayer.
 * Работает одинаково на всех устройствах (в отличие от системного android.media.audiofx.Equalizer,
 * у которого число полос и точность зависят от прошивки).
 * Обработка ведётся в double, фильтры — каскад биквадов (Transposed Direct Form II).
 */
@OptIn(UnstableApi::class)
class EqualizerProcessor : BaseAudioProcessor() {

    private var sampleRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var appliedVersion = -1

    private var filters: List<Biquad> = emptyList()
    private var z1 = Array(0) { DoubleArray(0) }
    private var z2 = Array(0) { DoubleArray(0) }
    private var settings = EqSettings()
    private var preampLin = 1.0
    private var frame = DoubleArray(8)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = enc
        frame = DoubleArray(channels.coerceAtLeast(1))
        appliedVersion = -1
        return inputAudioFormat
    }

    private fun refresh() {
        val v = EqEngine.version
        if (v == appliedVersion) return
        appliedVersion = v
        settings = EqEngine.current
        val newFilters = if (settings.enabled) EqEngine.buildFilters(settings, sampleRate.toDouble()) else emptyList()
        if (newFilters.size != filters.size || z1.firstOrNull()?.size != channels) {
            z1 = Array(newFilters.size) { DoubleArray(channels) }
            z2 = Array(newFilters.size) { DoubleArray(channels) }
        }
        filters = newFilters
        preampLin = if (settings.enabled) 10.0.pow(settings.preamp / 20.0) else 1.0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        refresh()
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(remaining)
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frames = remaining / frameBytes
        val enabled = settings.enabled
        val width = settings.width.toDouble()
        val limiter = settings.limiter && enabled
        val nf = filters.size

        for (i in 0 until frames) {
            for (c in 0 until channels) {
                frame[c] = if (encoding == C.ENCODING_PCM_FLOAT) inputBuffer.float.toDouble()
                else inputBuffer.short / 32768.0
            }
            if (enabled) {
                if (channels == 2 && width != 1.0) {
                    val m = (frame[0] + frame[1]) * 0.5
                    val s = (frame[0] - frame[1]) * 0.5 * width
                    frame[0] = m + s; frame[1] = m - s
                }
                for (c in 0 until channels) {
                    var x = frame[c] * preampLin
                    for (k in 0 until nf) {
                        val f = filters[k]
                        val y = f.b0 * x + z1[k][c]
                        z1[k][c] = f.b1 * x - f.a1 * y + z2[k][c]
                        z2[k][c] = f.b2 * x - f.a2 * y
                        x = y
                    }
                    if (limiter) x = softLimit(x)
                    frame[c] = x
                }
            }
            for (c in 0 until channels) {
                val v = frame[c]
                if (encoding == C.ENCODING_PCM_FLOAT) out.putFloat(v.toFloat())
                else out.putShort((v.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort())
            }
        }
        // Хвост неполного кадра (не должен встречаться, но на всякий случай)
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun softLimit(x: Double): Double {
        val ax = abs(x)
        if (ax <= 0.89) return x
        val y = 0.89 + 0.11 * tanh((ax - 0.89) / 0.11)
        return if (x < 0) -y else y
    }

    override fun onFlush() {
        for (k in z1.indices) { z1[k].fill(0.0); z2[k].fill(0.0) }
    }

    override fun onReset() {
        filters = emptyList()
        z1 = Array(0) { DoubleArray(0) }
        z2 = Array(0) { DoubleArray(0) }
        appliedVersion = -1
    }
}
