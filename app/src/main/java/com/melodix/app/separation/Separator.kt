package com.melodix.app.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import com.melodix.app.audio.AacEncoder
import com.melodix.app.audio.AudioDecoder
import com.melodix.app.audio.ExportFormat
import com.melodix.app.audio.Resampler
import com.melodix.app.audio.WavWriter
import com.melodix.app.data.JobScope
import com.melodix.app.data.OutputFile
import com.melodix.app.data.OutputStore
import com.melodix.app.data.Track
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

// ===================================================================================
//  Модели
// ===================================================================================

data class ModelInfo(
    val name: String,
    val assetName: String?,       // модель внутри APK
    val customFile: File?,        // импортированная пользователем
    val nFft: Int,
    val primaryIsVocals: Boolean,
    val compensate: Float,
) {
    val title: String get() = name.removeSuffix(".onnx")
}

object ModelManager {
    private val KNOWN = mapOf(
        "UVR-MDX-NET-Inst_HQ_3.onnx" to Triple(6144, false, 1.022f),
        "UVR-MDX-NET-Inst_HQ_4.onnx" to Triple(6144, false, 1.022f),
        "UVR-MDX-NET-Inst_HQ_2.onnx" to Triple(6144, false, 1.022f),
        "UVR-MDX-NET-Voc_FT.onnx" to Triple(7680, true, 1.021f),
        "Kim_Vocal_2.onnx" to Triple(7680, true, 1.009f),
        "Kim_Vocal_1.onnx" to Triple(7680, true, 1.009f),
        "UVR_MDXNET_KARA_2.onnx" to Triple(5120, false, 1.065f),
    )

    fun customDir(ctx: Context) = File(ctx.filesDir, "models_custom").apply { mkdirs() }

    fun available(ctx: Context): List<ModelInfo> {
        val out = ArrayList<ModelInfo>()
        runCatching { ctx.assets.list("models") }.getOrNull()?.filter { it.endsWith(".onnx") }?.forEach { name ->
            val k = KNOWN[name] ?: Triple(6144, false, 1.0f)
            out += ModelInfo(name, name, null, k.first, k.second, k.third)
        }
        customDir(ctx).listFiles { f -> f.name.endsWith(".onnx") }?.forEach { f ->
            val cfg = File(f.path + ".cfg").takeIf { it.exists() }?.readLines()?.associate {
                it.substringBefore('=') to it.substringAfter('=')
            } ?: emptyMap()
            val k = KNOWN[f.name]
            out += ModelInfo(
                f.name, null, f,
                cfg["nfft"]?.toIntOrNull() ?: k?.first ?: 6144,
                cfg["primary"]?.let { it == "vocals" } ?: k?.second ?: false,
                cfg["compensate"]?.toFloatOrNull() ?: k?.third ?: 1f,
            )
        }
        return out
    }

    fun import(ctx: Context, uri: Uri, name: String, nFft: Int, primaryVocals: Boolean, compensate: Float): File {
        val safe = name.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").let { if (it.endsWith(".onnx")) it else "$it.onnx" }
        val f = File(customDir(ctx), safe)
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it, 1 shl 16) } }
            ?: error("Не удалось прочитать файл модели")
        File(f.path + ".cfg").writeText("nfft=$nFft\nprimary=${if (primaryVocals) "vocals" else "instrumental"}\ncompensate=$compensate\n")
        return f
    }

    fun delete(model: ModelInfo) {
        model.customFile?.let { it.delete(); File(it.path + ".cfg").delete() }
    }

    /** Файл модели на диске (модель из APK копируется один раз). */
    fun file(ctx: Context, m: ModelInfo): File {
        m.customFile?.let { return it }
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val f = File(dir, m.assetName!!)
        val size = runCatching { ctx.assets.openFd("models/${m.assetName}").use { it.length } }.getOrDefault(-1L)
        if (!f.exists() || (size > 0 && f.length() != size)) {
            val tmp = File(dir, m.assetName + ".tmp")
            ctx.assets.open("models/${m.assetName}").use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
            tmp.renameTo(f)
        }
        return f
    }
}

// ===================================================================================
//  Параметры
// ===================================================================================

enum class SepQuality(val label: String, val description: String, val overlap: Float, val denoise: Boolean) {
    FAST("Быстро", "Без перекрытия блоков", 0f, false),
    BALANCED("Баланс", "Перекрытие 50%, мягкие стыки", 0.5f, false),
    BEST("Максимум", "Перекрытие 75% + шумоподавление (в ~8 раз дольше)", 0.75f, true),
}

data class SepOptions(
    val model: ModelInfo?,          // null → DSP
    val quality: SepQuality = SepQuality.BALANCED,
    val format: ExportFormat = ExportFormat.WAV,
    val bitrate: Int = 320_000,
    val dspStrength: Float = 0.7f,
)

/** Получатель «первичной» дорожки (до компенсации), потоково. */
typealias StemSink = (start: Long, l: FloatArray, r: FloatArray, count: Int) -> Unit

// ===================================================================================
//  Нейросетевой движок MDX-Net (ONNX Runtime, CPU, полностью офлайн)
// ===================================================================================

class MdxEngine(modelFile: File, private val nFft: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val dimF: Int
    val dimT: Int
    private val hop = 1024

    init {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(modelFile.absolutePath, opts)
        inputName = session.inputNames.first()
        val shape = (session.inputInfo[inputName]!!.info as TensorInfo).shape
        dimF = shape[2].toInt()
        dimT = shape[3].toInt()
        require(dimF > 0 && dimT > 0) { "Неподдерживаемая форма входа модели" }
        require(dimF <= nFft / 2 + 1) { "n_fft=$nFft слишком мал для модели (dim_f=$dimF)" }
    }

    private fun infer(spec: FloatArray, out: FloatArray) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, dimF.toLong(), dimT.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val o = res.get(0) as OnnxTensor
                o.floatBuffer.get(out)
            }
        }
    }

    suspend fun process(mix: ShortArray, frames: Int, quality: SepQuality, job: JobScope, p0: Float, p1: Float, sink: StemSink) {
        val chunk = hop * (dimT - 1)
        val trim = nFft / 2
        val gen = chunk - 2 * trim
        require(gen > 0) { "Неверные параметры модели" }
        val step = max(hop, (gen * (1f - quality.overlap)).toInt())
        val ramp = gen - step
        val taper = FloatArray(gen) { i ->
            when {
                ramp <= 0 -> 1f
                i < ramp -> (0.5 - 0.5 * cos(PI * (i + 0.5) / ramp)).toFloat().coerceAtLeast(1e-3f)
                i >= gen - ramp -> (0.5 - 0.5 * cos(PI * (gen - i - 0.5) / ramp)).toFloat().coerceAtLeast(1e-3f)
                else -> 1f
            }
        }
        val stft = MdxStft(nFft, hop, dimF, dimT)
        val plane = dimF * dimT
        val spec = FloatArray(4 * plane)
        val pred = FloatArray(4 * plane)
        val neg = if (quality.denoise) FloatArray(4 * plane) else FloatArray(0)
        val inL = FloatArray(chunk); val inR = FloatArray(chunk)
        val outL = FloatArray(chunk); val outR = FloatArray(chunk)
        val segL = FloatArray(gen); val segR = FloatArray(gen)

        val acc = OverlapAccumulator(gen, frames.toLong(), 0L, sink)
        val total = ceil(frames.toDouble() / step).toInt().coerceAtLeast(1)
        var s = 0
        var ci = 0
        val t0 = System.currentTimeMillis()
        while (s < frames) {
            job.check()
            for (j in 0 until chunk) {
                val src = s - trim + j
                if (src in 0 until frames) {
                    inL[j] = mix[2 * src] / 32768f; inR[j] = mix[2 * src + 1] / 32768f
                } else { inL[j] = 0f; inR[j] = 0f }
            }
            stft.forward(inL, inR, spec)
            infer(spec, pred)
            if (quality.denoise) {
                for (i in spec.indices) spec[i] = -spec[i]
                infer(spec, neg)
                for (i in pred.indices) pred[i] = 0.5f * (pred[i] - neg[i])
            }
            stft.inverse(pred, outL, outR)
            for (i in 0 until gen) {
                segL[i] = outL[trim + i] * taper[i]; segR[i] = outR[trim + i] * taper[i]
            }
            acc.add(s.toLong(), segL, segR, taper, gen)
            ci++
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            val eta = if (ci > 0) elapsed * (total - ci) / ci else 0L
            job.progress("Нейросеть: блок $ci из $total · осталось ~${eta / 60}:${"%02d".format(eta % 60)}", p0 + (p1 - p0) * ci / total)
            s += step
        }
        acc.finish()
    }

    override fun close() { session.close() }
}

// ===================================================================================
//  DSP-движок (без нейросети): выделение центрального канала по когерентности L/R
// ===================================================================================

object DspEngine {
    suspend fun process(mix: ShortArray, frames: Int, sampleRate: Int, strength: Float, job: JobScope, p0: Float, p1: Float, sink: StemSink) {
        val n = 4096
        val hop = 1024
        val fft = Fft(n)
        val w = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val wsq = FloatArray(n) { (w[it] * w[it]).toFloat() }
        val re = DoubleArray(n); val im = DoubleArray(n)
        val bins = n / 2 + 1
        val maskPrev = DoubleArray(bins)
        val gamma = 2.0 + 10.0 * strength           // чем больше — тем строже отбор «центра»
        val vL = DoubleArray(bins); val vI = DoubleArray(bins)
        val dataL = FloatArray(n); val dataR = FloatArray(n)
        val first = -(n - hop).toLong()
        val acc = OverlapAccumulator(n, frames.toLong(), first, sink)
        val band = DoubleArray(bins) { k ->
            val f = k.toDouble() * sampleRate / n
            when {
                f < 90 -> 0.0
                f < 200 -> (f - 90) / 110
                f < 8000 -> 1.0
                f < 16000 -> 1.0 - 0.6 * (f - 8000) / 8000
                else -> 0.4
            }
        }
        var s = first
        val totalSteps = ((frames - first) / hop).toInt().coerceAtLeast(1)
        var stepIdx = 0
        while (s < frames) {
            if (stepIdx % 64 == 0) {
                job.check()
                job.progress("DSP-разделение…", p0 + (p1 - p0) * stepIdx / totalSteps)
            }
            for (i in 0 until n) {
                val idx = s + i
                if (idx in 0 until frames) {
                    val ii = (idx * 2).toInt()
                    re[i] = mix[ii] / 32768.0 * w[i]; im[i] = mix[ii + 1] / 32768.0 * w[i]
                } else { re[i] = 0.0; im[i] = 0.0 }
            }
            fft.forward(re, im)
            for (k in 0 until bins) {
                val nk = if (k == 0) 0 else n - k
                val a = re[k]; val b = im[k]; val c = re[nk]; val d = im[nk]
                val lr = (a + c) * 0.5; val li = (b - d) * 0.5
                val rr = (b + d) * 0.5; val ri = (c - a) * 0.5
                val pl = lr * lr + li * li; val pr = rr * rr + ri * ri
                val cross = lr * rr + li * ri                       // Re(L·R*)
                var psi = 2 * cross / (pl + pr + 1e-12)            // 1 — идентичны (центр), ≤0 — разнесены
                psi = psi.coerceIn(0.0, 1.0)
                var m = psi.pow(gamma) * band[k]
                m = 0.65 * maskPrev[k] + 0.35 * m                   // сглаживание во времени (меньше «бульканья»)
                maskPrev[k] = m
                vL[k] = (lr + rr) * 0.5 * m; vI[k] = (li + ri) * 0.5 * m
            }
            // Обратное преобразование моно-вокала (эрмитова симметрия)
            for (k in 0 until n) {
                if (k < bins) { re[k] = vL[k]; im[k] = vI[k] } else { re[k] = vL[n - k]; im[k] = -vI[n - k] }
            }
            fft.inverse(re, im)
            for (i in 0 until n) {
                val v = (re[i] / n * w[i]).toFloat()
                dataL[i] = v; dataR[i] = v
            }
            acc.add(s, dataL, dataR, wsq, n)
            s += hop
            stepIdx++
        }
        acc.finish()
    }
}

// ===================================================================================
//  Оркестратор
// ===================================================================================

object Separator {
    const val RATE = 44100

    suspend fun run(ctx: Context, track: Track, opt: SepOptions, job: JobScope): List<OutputFile> {
        val work = File(ctx.cacheDir, "sep").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        job.progress("Декодирование…", 0f)
        val pcm = AudioDecoder.decode(ctx, track.uri, File(work, "src.pcm"),
            { p -> job.update("Декодирование…", 0.08f * p) }, { job.cancelled })
        if (opt.model == null && pcm.channels < 2) {
            error("Запись моно: DSP-метод работает только со стерео. Выберите нейросеть.")
        }
        job.progress("Подготовка 44.1 кГц стерео…", 0.08f)
        val mix = Resampler.resample(pcm.map(), pcm.channels, pcm.sampleRate, RATE, 2)
        pcm.file.delete()
        val frames = mix.size / 2

        val vocWav = File(work, "vocals.wav")
        val insWav = File(work, "instrumental.wav")
        val vw = WavWriter(vocWav, RATE, 2)
        val iw = WavWriter(insWav, RATE, 2)
        val primaryVocals = opt.model?.primaryIsVocals ?: true
        val comp = opt.model?.compensate ?: 1f

        val sink: StemSink = { start, l, r, count ->
            for (i in 0 until count) {
                val idx = ((start + i) * 2).toInt()
                val mL = mix[idx] / 32768f; val mR = mix[idx + 1] / 32768f
                val pL = l[i] * comp; val pR = r[i] * comp
                val oL = mL - pL; val oR = mR - pR
                if (primaryVocals) {
                    vw.writeFloat(pL); vw.writeFloat(pR); iw.writeFloat(oL); iw.writeFloat(oR)
                } else {
                    iw.writeFloat(pL); iw.writeFloat(pR); vw.writeFloat(oL); vw.writeFloat(oR)
                }
            }
        }

        try {
            if (opt.model != null) {
                job.progress("Загрузка нейросети…", 0.1f)
                val file = ModelManager.file(ctx, opt.model)
                MdxEngine(file, opt.model.nFft).use { eng ->
                    eng.process(mix, frames, opt.quality, job, 0.1f, 0.92f, sink)
                }
            } else {
                DspEngine.process(mix, frames, RATE, opt.dspStrength, job, 0.1f, 0.92f, sink)
            }
        } finally {
            vw.close(); iw.close()
        }

        val base = OutputStore.safeName(track.title)
        val tag = if (opt.model != null) "" else " (DSP)"
        val results = ArrayList<OutputFile>()
        for ((i, pair) in listOf("Вокал" to vocWav, "Инструментал" to insWav).withIndex()) {
            val (label, wav) = pair
            val suffix = if (i == 0) "vocals" else "instrumental"
            if (opt.format == ExportFormat.M4A) {
                job.progress("Кодирование AAC: $label…", 0.92f + 0.04f * i)
                val m4a = File(work, "$suffix.m4a")
                AacEncoder.encode(WavWriter.mapPcm(wav), RATE, 2, opt.bitrate, m4a, {}, { job.cancelled })
                wav.delete()
                results += OutputStore.publish(ctx, m4a, "$base - $suffix$tag.m4a", "audio/mp4", label)
            } else {
                job.progress("Сохранение: $label…", 0.95f + 0.02f * i)
                results += OutputStore.publish(ctx, wav, "$base - $suffix$tag.wav", "audio/wav", label)
            }
        }
        return results
    }

    /** Оценка времени (сек) — грубо, для подсказки в интерфейсе. */
    fun estimateSeconds(durationMs: Long, opt: SepOptions): Long {
        val sec = durationMs / 1000.0
        val k = when {
            opt.model == null -> 0.05
            opt.quality == SepQuality.FAST -> 0.35
            opt.quality == SepQuality.BALANCED -> 0.7
            else -> 2.8
        }
        return (sec * k).toLong().coerceAtLeast(3)
    }
}
