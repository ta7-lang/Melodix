package com.melodix.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.melodix.app.playback.EqEngine
import com.melodix.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MelodixApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(TASK_CHANNEL, "Обработка аудио", NotificationManager.IMPORTANCE_LOW)
        )
        // Восстанавливаем сохранённые настройки эквалайзера до первого воспроизведения
        appScope.launch { Prefs.loadEq(this@MelodixApp)?.let { EqEngine.apply(it) } }
    }

    companion object {
        const val TASK_CHANNEL = "melodix_tasks"
        lateinit var instance: MelodixApp
            private set
    }
}
