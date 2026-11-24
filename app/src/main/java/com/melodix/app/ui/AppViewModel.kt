package com.melodix.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melodix.app.data.MusicRepository
import com.melodix.app.data.Prefs
import com.melodix.app.data.ThemeSettings
import com.melodix.app.data.Track
import com.melodix.app.playback.EqEngine
import com.melodix.app.playback.EqPreset
import com.melodix.app.playback.EqSettings
import com.melodix.app.playback.PlayerConnection
import com.melodix.app.ui.theme.AppVisuals
import com.melodix.app.ui.theme.ImageTheme
import com.melodix.app.ui.theme.ThemePresets
import com.melodix.app.ui.theme.paletteFromPreset
import com.melodix.app.ui.theme.toImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SortMode(val label: String) { TITLE("Название"), ARTIST("Исполнитель"), ALBUM("Альбом"), DATE("Дата добавления"), DURATION("Длительность") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext

    val player = PlayerConnection(ctx).also { it.connect() }

    private val _all = MutableStateFlow<List<Track>>(emptyList())
    private val _external = MutableStateFlow<List<Track>>(emptyList())
    val loading = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(SortMode.TITLE)

    val allTracks: StateFlow<List<Track>> = combine(_all, _external) { a, b -> b + a }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tracks: StateFlow<List<Track>> = combine(_all, query, sort) { list, q, s ->
        val filtered = if (q.isBlank()) list else {
            val qq = q.trim().lowercase()
            list.filter { it.title.lowercase().contains(qq) || it.artist.lowercase().contains(qq) || it.album.lowercase().contains(qq) }
        }
        when (s) {
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> filtered.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }))
            SortMode.ALBUM -> filtered.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
            SortMode.DATE -> filtered.sortedByDescending { it.dateAdded }
            SortMode.DURATION -> filtered.sortedByDescending { it.durationMs }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- Тема ----------
    val themeSettings: StateFlow<ThemeSettings> = Prefs.themeFlow(ctx)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettings())

    private val _visuals = MutableStateFlow(
        AppVisuals(paletteFromPreset(ThemePresets.byId("aurora")), null, ThemeSettings())
    )
    val visuals: StateFlow<AppVisuals> = _visuals

    // ---------- Эквалайзер ----------
    val eq: StateFlow<EqSettings> = EqEngine.state
    val customPresets: StateFlow<List<EqPreset>> = Prefs.customPresetsFlow(ctx)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var eqSaveJob: Job? = null

    init {
        viewModelScope.launch {
            var lastImage: String? = null
            var lastBitmap: android.graphics.Bitmap? = null
            themeSettings.collect { t ->
                val v = withContext(Dispatchers.IO) {
                    if (t.useImage && t.imagePath != null) {
                        if (lastImage != t.imagePath) { lastBitmap = ImageTheme.loadBitmap(t.imagePath); lastImage = t.imagePath }
                        val bmp = lastBitmap
                        if (bmp != null) AppVisuals(ImageTheme.paletteFrom(bmp, t.imageDark), bmp.toImage(), t)
                        else AppVisuals(paletteFromPreset(ThemePresets.byId(t.presetId)), null, t)
                    } else AppVisuals(paletteFromPreset(ThemePresets.byId(t.presetId)), null, t)
                }
                _visuals.value = v
                runCatching { sort.value = SortMode.valueOf(t.sortMode.uppercase()) }
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            loading.value = true
            _all.value = withContext(Dispatchers.IO) { MusicRepository.load(ctx) }
            loading.value = false
        }
    }

    fun openExternal(uri: Uri, play: Boolean = true): Track {
        val t = MusicRepository.fromUri(ctx, uri)
        _external.value = listOf(t) + _external.value.filter { it.key != t.key }
        if (play) player.playTracks(listOf(t), 0)
        return t
    }

    fun find(key: String): Track? = allTracks.value.firstOrNull { it.key == key }

    fun setSort(s: SortMode) {
        sort.value = s
        updateTheme { it.copy(sortMode = s.name.lowercase()) }
    }

    // ---------- Тема: действия ----------
    fun updateTheme(block: (ThemeSettings) -> ThemeSettings) {
        viewModelScope.launch { Prefs.saveTheme(ctx, block(Prefs.themeFlow(ctx).first())) }
    }

    fun selectPreset(id: String) = updateTheme { it.copy(presetId = id, useImage = false) }

    fun setImageTheme(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val dir = File(ctx.filesDir, "theme").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "bg_${System.currentTimeMillis()}.img")
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                f.absolutePath
            }
            updateTheme { it.copy(useImage = true, imagePath = path) }
        }
    }

    // ---------- EQ: действия ----------
    fun updateEq(block: (EqSettings) -> EqSettings) {
        EqEngine.update(block)
        eqSaveJob?.cancel()
        eqSaveJob = viewModelScope.launch { delay(400); Prefs.saveEq(ctx, EqEngine.current) }
    }

    fun applyPreset(p: EqPreset) = updateEq {
        it.copy(gains = p.gains, bass = p.bass, treble = p.treble, presetName = p.name, enabled = true)
    }

    fun saveCustomPreset(name: String) {
        val s = EqEngine.current
        viewModelScope.launch {
            Prefs.saveCustomPreset(ctx, EqPreset(name, s.gains, s.bass, s.treble, custom = true))
            updateEq { it.copy(presetName = name) }
        }
    }

    fun deleteCustomPreset(name: String) { viewModelScope.launch { Prefs.deleteCustomPreset(ctx, name) } }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
