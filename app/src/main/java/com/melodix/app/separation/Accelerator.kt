package com.melodix.app.separation

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import java.io.File
import java.util.EnumSet
import java.util.Properties

/** Вычислитель, на котором выполняется нейросеть. */
enum class Backend(val label: String, val short: String) {
    CPU("Процессор (FP32)", "CPU"),
    XNNPACK("Процессор, XNNPACK (FP32)", "CPU·XNNPACK"),
    NNAPI_FP32("Нейроускоритель (FP32)", "NPU·FP32"),
    NNAPI_FP16("Нейроускоритель MediaTek APU (FP16)", "NPU·FP16"),
}

data class AccelResult(val backend: Backend, val secondsPerPass: Double, val snrDb: Double?)

/**
 * Результаты калибровки ускорителя (по модели), а также защита от сбоев драйвера:
 * перед пробой бэкенда ставится метка «pending»; если приложение упало во время пробы,
 * при следующем запуске этот бэкенд помечается как сбойный и больше не используется.
 */
object AccelStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "accel.properties")

    @Synchronized
    private fun load(ctx: Context): Properties = Properties().apply {
        runCatching { file(ctx).inputStream().use { load(it) } }
    }

    @Synchronized
    private fun save(ctx: Context, p: Properties) {
        runCatching { file(ctx).outputStream().use { p.store(it, "Melodix accelerator calibration") } }
    }

    fun get(ctx: Context, model: String): AccelResult? {
        val p = load(ctx)
        val b = p.getProperty("$model.backend")?.let { runCatching { Backend.valueOf(it) }.getOrNull() } ?: return null
        if (b in crashed(ctx, model)) return null
        return AccelResult(b, p.getProperty("$model.sec")?.toDoubleOrNull() ?: 0.0, p.getProperty("$model.snr")?.toDoubleOrNull())
    }

    fun put(ctx: Context, model: String, r: AccelResult) {
        val p = load(ctx)
        p.setProperty("$model.backend", r.backend.name)
        p.setProperty("$model.sec", r.secondsPerPass.toString())
        if (r.snrDb != null) p.setProperty("$model.snr", r.snrDb.toString()) else p.remove("$model.snr")
        save(ctx, p)
    }

    /** CPU-время прохода (для оценки длительности). */
    fun putCpu(ctx: Context, model: String, sec: Double) {
        val p = load(ctx); p.setProperty("$model.cpusec", sec.toString()); save(ctx, p)
    }

    fun cpuSeconds(ctx: Context, model: String): Double? = load(ctx).getProperty("$model.cpusec")?.toDoubleOrNull()

    fun reset(ctx: Context, model: String) {
        val p = load(ctx)
        listOf("backend", "sec", "snr", "pending", "crashed", "cpusec").forEach { p.remove("$model.$it") }
        save(ctx, p)
    }

    fun crashed(ctx: Context, model: String): Set<Backend> {
        val p = load(ctx)
        val set = (p.getProperty("$model.crashed") ?: "").split(",").filter { it.isNotBlank() }
            .mapNotNull { runCatching { Backend.valueOf(it) }.getOrNull() }.toMutableSet()
        p.getProperty("$model.pending")?.let { pend -> runCatching { Backend.valueOf(pend) }.getOrNull()?.let { set += it } }
        return set
    }

    fun markPending(ctx: Context, model: String, b: Backend) {
        val p = load(ctx)
        // Незавершённая прошлая проба = падение процесса во время неё
        p.getProperty("$model.pending")?.let { prev ->
            val list = (p.getProperty("$model.crashed") ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
            list += prev
            p.setProperty("$model.crashed", list.joinToString(","))
        }
        p.setProperty("$model.pending", b.name)
        save(ctx, p)
    }

    fun clearPending(ctx: Context, model: String) {
        val p = load(ctx); p.remove("$model.pending"); save(ctx, p)
    }
}

object SessionFactory {
    /** Все 8 ядер Dimensity 8400 — «большие» Cortex-A725, поэтому используем их все. */
    val threads: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    fun create(env: OrtEnvironment, file: File, backend: Backend): OrtSession {
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        o.setIntraOpNumThreads(threads)
        o.setInterOpNumThreads(1)
        // Без «активного ожидания» потоков: меньше нагрев и троттлинг на длинной обработке
        o.addConfigEntry("session.intra_op.allow_spinning", "0")
        // Фиксированный размер батча — нужен NNAPI для компиляции графа под ускоритель
        runCatching { o.addFreeDimensionOverrideByName("batch_size", 1) }
        when (backend) {
            Backend.NNAPI_FP16 -> o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
            Backend.NNAPI_FP32 -> o.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            Backend.XNNPACK -> o.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            Backend.CPU -> {}
        }
        return env.createSession(file.absolutePath, o)
    }
}
