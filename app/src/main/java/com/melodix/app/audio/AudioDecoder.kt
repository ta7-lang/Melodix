package com.melodix.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** Декодированный PCM: 16 бит, little-endian, чередующиеся каналы. */
class PcmFile(val file: File, val sampleRate: Int, val channels: Int, val frames: Long) {
    val durationMs: Long get() = frames * 1000 / sampleRate

    /** Отображение файла в память (без загрузки целиком в heap). */
    fun map(): ShortBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val bb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            bb.order(ByteOrder.LITTLE_ENDIAN)
            return bb.asShortBuffer()
        }
    }
}

object AudioDecoder {

    /**
     * Декодирует любой поддерживаемый системой формат (MP3, AAC/M4A, FLAC, OGG/Vorbis, Opus,
     * WAV, AMR, 3GP, MKA/WebM, ALAC на новых Android и т. д.) в 16-битный PCM.
     */
    fun decode(
        context: Context,
        uri: Uri,
        out: File,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmFile {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) { extractor.release(); error("В файле нет аудиодорожки") }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
            extractor.release(); error("Формат $mime не поддерживается декодерами устройства")
        }
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var frames = 0L
        val os = BufferedOutputStream(FileOutputStream(out), 1 shl 17)
        var scratch = ByteArray(0)
        var lastProgress = -1f

        try {
            while (!outputDone) {
                if (isCancelled()) error("Отменено")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.LITTLE_ENDIAN)
                            val written = writePcm(ob, pcmEncoding, os, scratch).also { scratch = it.second }.first
                            frames += written / channels
                            if (durationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastProgress > 0.01f) { lastProgress = p; onProgress(p) }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            os.close()
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        if (frames == 0L) error("Не удалось декодировать аудио")
        return PcmFile(out, sampleRate, channels, frames)
    }

    /** Конвертирует буфер декодера в int16 LE. Возвращает число записанных сэмплов. */
    private fun writePcm(src: ByteBuffer, encoding: Int, os: BufferedOutputStream, scratchIn: ByteArray): Pair<Int, ByteArray> {
        var scratch = scratchIn
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val n = src.remaining() / 4
                if (scratch.size < n * 2) scratch = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = (src.float.coerceIn(-1f, 1f) * 32767f).toInt()
                    scratch[2 * i] = (v and 0xFF).toByte(); scratch[2 * i + 1] = (v shr 8).toByte()
                }
                os.write(scratch, 0, n * 2); return n to scratch
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = src.remaining()
                if (scratch.size < n * 2) scratch = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = ((src.get().toInt() and 0xFF) - 128) shl 8
                    scratch[2 * i] = (v and 0xFF).toByte(); scratch[2 * i + 1] = (v shr 8).toByte()
                }
                os.write(scratch, 0, n * 2); return n to scratch
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val n = src.remaining() / 3
                if (scratch.size < n * 2) scratch = ByteArray(n * 2)
                for (i in 0 until n) {
                    src.get() // младший байт отбрасываем
                    scratch[2 * i] = src.get(); scratch[2 * i + 1] = src.get()
                }
                os.write(scratch, 0, n * 2); return n to scratch
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val n = src.remaining() / 4
                if (scratch.size < n * 2) scratch = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = src.int shr 16
                    scratch[2 * i] = (v and 0xFF).toByte(); scratch[2 * i + 1] = (v shr 8).toByte()
                }
                os.write(scratch, 0, n * 2); return n to scratch
            }
            else -> {
                val n = src.remaining()
                if (scratch.size < n) scratch = ByteArray(n)
                src.get(scratch, 0, n)
                os.write(scratch, 0, n); return n / 2 to scratch
            }
        }
    }
}
