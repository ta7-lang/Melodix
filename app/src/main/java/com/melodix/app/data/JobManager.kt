package com.melodix.app.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.melodix.app.MainActivity
import com.melodix.app.MelodixApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

data class OutputFile(val label: String, val name: String, val uri: Uri, val location: String)

data class JobState(
    val id: Long,
    val kind: String,
    val title: String,
    val stage: String = "Подготовка…",
    val progress: Float = 0f,
    val done: Boolean = false,
    val error: String? = null,
    val outputs: List<OutputFile> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
)

class JobScope(private val id: Long) {
    @Volatile var cancelled = false

    /** Обновление прогресса из любого потока (не suspend). */
    fun update(stage: String, value: Float) {
        val s = JobManager.state.value
        if (s != null && s.id == id) JobManager.set(s.copy(stage = stage, progress = value.coerceIn(0f, 1f)))
    }

    suspend fun progress(stage: String, value: Float) {
        coroutineContext.ensureActive()
        update(stage, value)
    }

    suspend fun check() = coroutineContext.ensureActive()
}

/** Фоновые задачи (экспорт, разделение дорожек) с уведомлением о прогрессе. */
object JobManager {
    private val _state = MutableStateFlow<JobState?>(null)
    val state: StateFlow<JobState?> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var scopeRef: JobScope? = null

    internal fun set(s: JobState) { _state.value = s }

    val isBusy: Boolean get() = _state.value?.let { !it.done } == true

    fun start(context: Context, kind: String, title: String, block: suspend JobScope.() -> List<OutputFile>) {
        if (isBusy) return
        val id = System.nanoTime()
        _state.value = JobState(id, kind, title)
        val app = context.applicationContext
        runCatching { ContextCompat.startForegroundService(app, Intent(app, TaskService::class.java)) }
        val js = JobScope(id)
        scopeRef = js
        job = scope.launch {
            try {
                val outputs = js.block()
                _state.value = _state.value?.copy(done = true, progress = 1f, stage = "Готово", outputs = outputs)
            } catch (e: CancellationException) {
                _state.value = _state.value?.copy(done = true, error = "Отменено")
            } catch (t: Throwable) {
                _state.value = _state.value?.copy(done = true, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun cancel() { scopeRef?.cancelled = true; job?.cancel() }

    fun dismiss() { if (!isBusy) _state.value = null }
}

class TaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build(JobManager.state.value), type)
        scope.launch {
            JobManager.state.collect { s ->
                val nm = getSystemService(NotificationManager::class.java)
                if (s == null || s.done) {
                    ServiceCompat.stopForeground(this@TaskService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (s != null) nm.notify(NOTIF_ID + 1, build(s))
                    stopSelf()
                } else nm.notify(NOTIF_ID, build(s))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(s: JobState?): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, MelodixApp.TASK_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s?.title ?: "Melodix")
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
        if (s == null || !s.done) {
            b.setContentText(s?.stage ?: "Обработка…")
                .setProgress(1000, ((s?.progress ?: 0f) * 1000).toInt(), s == null)
                .setOngoing(true)
        } else {
            b.setContentText(s.error ?: "Готово: файлы сохранены в Music/Melodix").setAutoCancel(true)
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val NOTIF_ID = 4210 }
}

/** Сохранение результатов в общую папку Music/Melodix. */
object OutputStore {
    const val FOLDER = "Melodix"

    fun publish(context: Context, temp: File, displayName: String, mime: String, label: String): OutputFile {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("Не удалось создать файл в Music/$FOLDER")
            resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            temp.delete()
            return OutputFile(label, displayName, uri, "Music/$FOLDER/$displayName")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
            dir.mkdirs()
            var target = File(dir, displayName)
            var n = 1
            while (target.exists()) {
                target = File(dir, displayName.substringBeforeLast('.') + " ($n)." + displayName.substringAfterLast('.'))
                n++
            }
            temp.copyTo(target)
            temp.delete()
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            return OutputFile(label, target.name, Uri.fromFile(target), target.absolutePath)
        }
    }

    fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "track" }
}
