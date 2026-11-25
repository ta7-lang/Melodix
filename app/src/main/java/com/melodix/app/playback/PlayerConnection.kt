package com.melodix.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.melodix.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueEntry(val index: Int, val mediaId: String, val title: String, val artist: String)

data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val uri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
)

/** Обёртка над MediaController: даёт UI поток состояния и команды управления. */
class PlayerConnection(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private var controller: MediaController? = null
    private var pending: (MediaController.() -> Unit)? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null

    private val _sleepRemainingMs = MutableStateFlow(0L)
    val sleepRemainingMs: StateFlow<Long> = _sleepRemainingMs

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c ->
                controller = c
                c.addListener(listener)
                pending?.invoke(c); pending = null
                publish()
                startTicker()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        ticker?.cancel()
    }

    private fun withController(block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) c.block() else { pending = block; connect() }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _state.value = _state.value.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0))
                }
                delay(100)
            }
        }
    }

    private fun publish() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val md = item?.mediaMetadata
        _state.value = PlayerUiState(
            connected = true,
            mediaId = item?.mediaId,
            title = md?.title?.toString() ?: "",
            artist = md?.artist?.toString() ?: "",
            uri = item?.localConfiguration?.uri ?: item?.requestMetadata?.mediaUri,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            pitch = c.playbackParameters.pitch,
            queueIndex = c.currentMediaItemIndex,
            queueSize = c.mediaItemCount,
        )
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(key)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build()
        )
        .build()

    fun playTracks(tracks: List<Track>, startIndex: Int = 0): Unit = withController {
        setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), 0L)
        prepare()
        play()
    }

    fun playNext(track: Track): Unit = withController {
        if (mediaItemCount == 0) { setMediaItem(track.toMediaItem()); prepare(); play() }
        else addMediaItem(currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun addToQueue(track: Track): Unit = withController {
        addMediaItem(track.toMediaItem())
        if (mediaItemCount == 1) { prepare(); play() }
    }

    fun togglePlay(): Unit = withController {
        if (isPlaying) pause() else {
            if (playbackState == Player.STATE_IDLE) prepare()
            if (playbackState == Player.STATE_ENDED) this.seekTo(0L)
            play()
        }
    }

    fun next(): Unit = withController { seekToNext() }
    fun previous(): Unit = withController { if (currentPosition > 3000) this.seekTo(0L) else seekToPrevious() }
    fun seekTo(ms: Long): Unit = withController { this.seekTo(ms); _state.value = _state.value.copy(positionMs = ms) }
    fun skipTo(index: Int): Unit = withController { this.seekTo(index, 0L); this.play() }
    fun toggleShuffle(): Unit = withController { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat(): Unit = withController {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeedPitch(speed: Float, pitch: Float): Unit = withController { playbackParameters = PlaybackParameters(speed, pitch) }
    fun removeFromQueue(index: Int): Unit = withController { if (index in 0 until mediaItemCount) removeMediaItem(index) }
    fun moveInQueue(from: Int, to: Int): Unit = withController { if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to) }

    fun queue(): List<QueueEntry> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { i ->
            val mi = c.getMediaItemAt(i)
            QueueEntry(i, mi.mediaId, mi.mediaMetadata.title?.toString() ?: "", mi.mediaMetadata.artist?.toString() ?: "")
        }
    }

    /** Таймер сна: плавно приглушает и ставит на паузу. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { _sleepRemainingMs.value = 0; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (isActive) {
                val left = end - System.currentTimeMillis()
                _sleepRemainingMs.value = left.coerceAtLeast(0)
                if (left <= 0) break
                delay(1000)
            }
            controller?.let { c ->
                val startVol = c.volume
                for (i in 20 downTo 0) { c.volume = startVol * i / 20f; delay(150) }
                c.pause()
                c.volume = startVol
            }
            _sleepRemainingMs.value = 0
        }
    }
}
