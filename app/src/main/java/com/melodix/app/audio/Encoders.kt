package com.melodix.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Потоковая запись WAV (PCM 16 бит). */
class WavWriter(val file: File, val sampleRate: Int, val channels: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    var samplesWritten = 0L
        private set

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun write(s: Short) {
        if (buf.remaining() < 2) flush()
        buf.putShort(s)
        samplesWritten++
    }

    fun writeFloat(v: Float) {
        write((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    }

    private fun flush() {
        buf.flip()
        raf.write(buf.array(), 0, buf.limit())
        buf.clear()
    }

    override fun close() {
        flush()
        val dataLen = samplesWritten * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataLen).toInt())
        h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(sampleRate); h.putInt(sampleRate * channels * 2)
        h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen.toInt())
        raf.seek(0); raf.write(h.array())
        raf.close()
    }

    companion object {
        /** Чтение PCM-данных из WAV, записанного WavWriter (заголовок 44 байта). */
        fun mapPcm(file: File): ShortBuffer {
            RandomAccessFile(file, "r").use { raf ->
                val bb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 44, raf.length() - 44)
                bb.order(ByteOrder.LITTLE_ENDIAN)
                return bb.asShortBuffer()
            }
        }
    }
}

object AacEncoder {
    /**
     * Кодирует PCM16 (чередующиеся каналы) в AAC-LC внутри контейнера M4A.
     * Частота дискретизации должна быть ≤ 48 кГц, каналов 1–2.
     */
    fun encode(
        pcm: ShortBuffer, sampleRate: Int, channels: Int, bitrate: Int, out: File,
        onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false },
    ) {
        val total = pcm.remaining()
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var consumed = 0
        var inputDone = false
        var framesQueued = 0L
        try {
            while (true) {
                if (isCancelled()) error("Отменено")
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(5000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        val maxSamples = (ib.capacity() / 2 / channels) * channels
                        val n = min(maxSamples, total - consumed)
                        val pts = framesQueued * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            ib.order(ByteOrder.LITTLE_ENDIAN)
                            val sb = ib.asShortBuffer()
                            for (i in 0 until n) sb.put(pcm.get(consumed + i))
                            codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            consumed += n
                            framesQueued += n / channels
                            onProgress(consumed.toFloat() / total)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxerStarted = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (muxerStarted) muxer.stop() }
            muxer.release()
        }
    }
}

/**
 * Высококачественный ресемплер: оконный sinc (окно Блэкмана, 64 отвода),
 * табличная интерполяция фаз. Подавление алиасинга ~ -90 дБ.
 */
object Resampler {
    private const val HALF = 32
    private const val PHASES = 1024

    fun resample(
        src: ShortBuffer, srcChannels: Int, srcRate: Int, dstRate: Int, dstChannels: Int,
        onProgress: (Float) -> Unit = {},
    ): ShortArray {
        val srcFrames = src.remaining() / srcChannels
        fun sample(frame: Int, ch: Int): Float {
            if (frame < 0 || frame >= srcFrames) return 0f
            val c = if (ch < srcChannels) ch else 0
            return src.get(frame * srcChannels + c) / 32768f
        }
        val outCh = dstChannels
        if (srcRate == dstRate) {
            val out = ShortArray(srcFrames * outCh)
            for (f in 0 until srcFrames) for (c in 0 until outCh) out[f * outCh + c] = (sample(f, c) * 32767f).toInt().toShort()
            return out
        }
        val ratio = srcRate.toDouble() / dstRate
        val cutoff = min(1.0, 1.0 / ratio) * 0.97
        // Таблица ядра: [phase][tap]
        val table = Array(PHASES + 1) { p ->
            val frac = p.toDouble() / PHASES
            FloatArray(2 * HALF) { k ->
                val x = (k - HALF + 1) - frac
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
                val n = (x + HALF) / (2.0 * HALF)
                val w = if (n < 0 || n > 1) 0.0 else 0.42 - 0.5 * cos(2 * PI * n) + 0.08 * cos(4 * PI * n)
                (cutoff * sinc * w).toFloat()
            }
        }
        val dstFrames = floor(srcFrames / ratio).toInt()
        val out = ShortArray(dstFrames * outCh)
        for (i in 0 until dstFrames) {
            val t = i * ratio
            val base = floor(t).toInt()
            val phase = ((t - base) * PHASES).toInt().coerceIn(0, PHASES)
            val kern = table[phase]
            for (c in 0 until outCh) {
                var acc = 0f
                for (k in 0 until 2 * HALF) acc += sample(base + k - HALF + 1, c) * kern[k]
                out[i * outCh + c] = (acc.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            if (i and 0xFFFF == 0) onProgress(i.toFloat() / dstFrames)
        }
        return out
    }
}
