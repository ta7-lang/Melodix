@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodix.app.ui.screens

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import com.melodix.app.audio.AudioDecoder
import com.melodix.app.audio.AudioEditor
import com.melodix.app.audio.EditMode
import com.melodix.app.audio.EditParams
import com.melodix.app.audio.ExportFormat
import com.melodix.app.audio.PcmFile
import com.melodix.app.audio.Waveform
import com.melodix.app.data.JobManager
import com.melodix.app.data.OutputStore
import com.melodix.app.data.Track
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.GlassCard
import com.melodix.app.ui.components.formatTimePrecise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val loadProgress = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)
    val peaks = MutableStateFlow<FloatArray?>(null)
    val previewPos = MutableStateFlow(-1L)
    var pcm: PcmFile? = null
        private set
    private var loadedKey: String? = null
    private var preview: ExoPlayer? = null
    private var previewJob: Job? = null

    fun load(track: Track) {
        if (loadedKey == track.key) return
        loadedKey = track.key
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "edit").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    AudioDecoder.decode(ctx, track.uri, File(dir, "src.pcm"), { loadProgress.value = it })
                }
                pcm = p
                peaks.value = withContext(Dispatchers.Default) { Waveform.peaks(p, 1200) }
            } catch (e: Exception) {
                error.value = e.message ?: "Не удалось открыть файл"
            }
        }
    }

    fun playPreview(track: Track, fromMs: Long, toMs: Long) {
        stopPreview()
        val p = preview ?: ExoPlayer.Builder(ctx).build().also { preview = it }
        p.setMediaItem(MediaItem.fromUri(track.uri))
        p.prepare()
        p.seekTo(fromMs)
        p.play()
        previewJob = viewModelScope.launch {
            while (isActive) {
                val pos = p.currentPosition
                previewPos.value = pos
                if (pos >= toMs || (!p.isPlaying && p.playbackState == androidx.media3.common.Player.STATE_ENDED)) break
                delay(40)
            }
            p.pause(); previewPos.value = -1
        }
    }

    fun stopPreview() {
        previewJob?.cancel(); preview?.pause(); previewPos.value = -1
    }

    override fun onCleared() {
        preview?.release()
        super.onCleared()
    }
}

@Composable
fun EditorScreen(vm: AppViewModel, track: Track, nav: NavHostController) {
    val evm: EditorViewModel = viewModel()
    LaunchedEffect(track.key) { evm.load(track) }
    val peaks by evm.peaks.collectAsStateWithLifecycle()
    val loadP by evm.loadProgress.collectAsStateWithLifecycle()
    val err by evm.error.collectAsStateWithLifecycle()
    val previewPos by evm.previewPos.collectAsStateWithLifecycle()
    val job by JobManager.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val duration = evm.pcm?.durationMs ?: track.durationMs.coerceAtLeast(1)
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(0L) }
    LaunchedEffect(peaks) { if (peaks != null && end == 0L) end = duration }
    var mode by remember { mutableStateOf(EditMode.TRIM) }
    var fadeIn by remember { mutableFloatStateOf(0f) }
    var fadeOut by remember { mutableFloatStateOf(0f) }
    var gain by remember { mutableFloatStateOf(0f) }
    var normalize by remember { mutableStateOf(false) }
    var reverse by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(ExportFormat.WAV) }
    var bitrate by remember { mutableIntStateOf(256_000) }
    var name by remember { mutableStateOf(OutputStore.safeName(track.title) + " (edit)") }
    var zoom by remember { mutableFloatStateOf(1f) }

    DisposableEffect(Unit) { onDispose { evm.stopPreview() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = cs.onBackground) }
            Column(Modifier.weight(1f)) {
                Text("Редактор", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
                Text(track.title, style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        AnimatedContent(targetState = when { err != null -> 2; peaks == null -> 0; else -> 1 }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "ed") { st ->
            when (st) {
                0 -> GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Декодирование аудио…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { loadP }, modifier = Modifier.fillMaxWidth())
                    }
                }
                2 -> GlassCard(Modifier.fillMaxWidth().padding(16.dp)) { Text(err ?: "", Modifier.padding(20.dp), color = Color(0xFFFF8A80)) }
                else -> Column {
                    // Форма волны
                    GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        WaveformEditor(
                            peaks = peaks!!, durationMs = duration, startMs = start, endMs = end, mode = mode, zoom = zoom,
                            playheadMs = previewPos,
                            onChange = { s, e -> start = s; end = e },
                            modifier = Modifier.fillMaxWidth().height(170.dp).padding(12.dp),
                        )
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ZoomIn, null, tint = cs.onSurfaceVariant)
                            Slider(zoom, { zoom = it }, valueRange = 1f..20f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text("×${zoom.toInt()}", style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TimeNudge("Начало", start, { start = (start + it).coerceIn(0, end - 10) })
                            FilledTonalIconButton(onClick = {
                                if (previewPos >= 0) evm.stopPreview()
                                else if (mode == EditMode.TRIM) evm.playPreview(track, start, end)
                                else evm.playPreview(track, (start - 3000).coerceAtLeast(0), start)
                            }, modifier = Modifier.size(56.dp)) {
                                Icon(if (previewPos >= 0) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, "Прослушать")
                            }
                            TimeNudge("Конец", end, { end = (end + it).coerceIn(start + 10, duration) })
                        }
                        Text("Длина результата: ${formatTimePrecise(if (mode == EditMode.TRIM) end - start else duration - (end - start))}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
                    }

                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Действие", style = MaterialTheme.typography.titleSmall)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                SegmentedButton(mode == EditMode.TRIM, { mode = EditMode.TRIM }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Оставить выделенное") }
                                SegmentedButton(mode == EditMode.CUT, { mode = EditMode.CUT }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Вырезать выделенное") }
                            }
                            LabeledSlider("Плавное начало (fade in)", fadeIn, 0f..10f, "с") { fadeIn = it }
                            LabeledSlider("Плавное затухание (fade out)", fadeOut, 0f..10f, "с") { fadeOut = it }
                            LabeledSlider("Громкость", gain, -12f..12f, "дБ") { gain = it }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Нормализация (пик −0.3 дБ)", Modifier.weight(1f)); Switch(normalize, { normalize = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Реверс (задом наперёд)", Modifier.weight(1f)); Switch(reverse, { reverse = it })
                            }
                        }
                    }

                    GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Экспорт", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Имя файла") },
                                singleLine = true, shape = RoundedCornerShape(16.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                ExportFormat.entries.forEachIndexed { i, f ->
                                    SegmentedButton(format == f, { format = f }, SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size)) { Text(f.label) }
                                }
                            }
                            if (format == ExportFormat.M4A) {
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(128_000, 192_000, 256_000, 320_000).forEach { b ->
                                        FilterChip(bitrate == b, { bitrate = b }, { Text("${b / 1000}k") }, shape = RoundedCornerShape(14.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            val busy = job?.done == false
                            Button(
                                enabled = !busy,
                                onClick = {
                                    val p = evm.pcm ?: return@Button
                                    val params = EditParams(start, end, mode, (fadeIn * 1000).toLong(), (fadeOut * 1000).toLong(), gain, normalize, format, bitrate, reverse)
                                    val fileName = OutputStore.safeName(name) + "." + format.ext
                                    JobManager.start(ctx, "edit", "Экспорт: $fileName") {
                                        val work = File(ctx.cacheDir, "export").apply { mkdirs() }
                                        val out = AudioEditor.export(p, params, work, { update("Обработка…", it) }, { cancelled })
                                        listOf(OutputStore.publish(ctx, out, fileName, format.mime, "Результат"))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(20.dp),
                            ) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Идёт обработка…" else "Сохранить") }
                        }
                    }
                    JobCard(vm, Modifier.padding(horizontal = 16.dp), kind = "edit")
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeNudge(label: String, value: Long, onNudge: (Long) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTimePrecise(value), style = MaterialTheme.typography.titleMedium)
        Row {
            SmallNudge("−1с") { onNudge(-1000) }
            SmallNudge("−0.1") { onNudge(-100) }
            SmallNudge("+0.1") { onNudge(100) }
            SmallNudge("+1с") { onNudge(1000) }
        }
    }
}

@Composable
private fun SmallNudge(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(32.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Форма волны с ручками выделения. Масштаб (zoom) центрируется на выделении,
 * что позволяет выставлять границы с точностью до миллисекунд.
 */
@Composable
private fun WaveformEditor(
    peaks: FloatArray, durationMs: Long, startMs: Long, endMs: Long, mode: EditMode, zoom: Float,
    playheadMs: Long, onChange: (Long, Long) -> Unit, modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val cStart by rememberUpdatedState(startMs)
    val cEnd by rememberUpdatedState(endMs)
    // Видимое окно
    val visibleDur = (durationMs / zoom).toLong().coerceAtLeast(200)
    val center = (startMs + endMs) / 2
    val viewStart = (center - visibleDur / 2).coerceIn(0, (durationMs - visibleDur).coerceAtLeast(0))
    val vStart by rememberUpdatedState(viewStart)
    val vDur by rememberUpdatedState(visibleDur)

    Canvas(
        modifier
            .pointerInput(durationMs) {
                detectTapGestures { pos ->
                    val t = vStart + (pos.x / size.width * vDur).toLong()
                    // тап переносит ближайшую границу
                    if (abs(t - cStart) < abs(t - cEnd)) onChange(t.coerceIn(0, cEnd - 10), cEnd) else onChange(cStart, t.coerceIn(cStart + 10, durationMs))
                }
            }
            .pointerInput(durationMs) {
                var dragging = 0
                detectDragGestures(
                    onDragStart = { pos ->
                        val t = vStart + (pos.x / size.width * vDur).toLong()
                        dragging = if (abs(t - cStart) < abs(t - cEnd)) 1 else 2
                    },
                    onDragEnd = { dragging = 0 },
                ) { change, drag ->
                    change.consume()
                    val dt = (drag.x / size.width * vDur).toLong()
                    if (dragging == 1) onChange((cStart + dt).coerceIn(0, cEnd - 10), cEnd)
                    else if (dragging == 2) onChange(cStart, (cEnd + dt).coerceIn(cStart + 10, durationMs))
                }
            }
    ) {
        val w = size.width; val h = size.height; val mid = h / 2
        fun xOf(ms: Long) = ((ms - viewStart).toFloat() / visibleDur * w)
        val sx = xOf(startMs).coerceIn(0f, w); val ex = xOf(endMs).coerceIn(0f, w)
        // подсветка
        if (mode == EditMode.TRIM) drawRect(cs.primary.copy(alpha = 0.16f), Offset(sx, 0f), Size(ex - sx, h))
        else drawRect(Color(0x55FF5370), Offset(sx, 0f), Size(ex - sx, h))
        val bars = (w / 3.dp.toPx()).toInt().coerceAtLeast(10)
        val bw = w / bars
        for (i in 0 until bars) {
            val t = viewStart + (i.toFloat() / bars * visibleDur).toLong()
            val pi = (t.toFloat() / durationMs * peaks.size).toInt().coerceIn(0, peaks.size - 1)
            val amp = peaks[pi].coerceAtLeast(0.02f) * (h / 2 - 4)
            val x = i * bw
            val inside = x in sx..ex
            val color = when {
                mode == EditMode.TRIM && inside -> cs.primary
                mode == EditMode.CUT && !inside -> cs.primary
                else -> cs.onSurface.copy(alpha = 0.3f)
            }
            drawRoundRect(color, Offset(x + bw * 0.15f, mid - amp), Size(bw * 0.7f, amp * 2), CornerRadius(bw))
        }
        // ручки
        listOf(sx, ex).forEach { x ->
            drawLine(cs.secondary, Offset(x, 0f), Offset(x, h), 2.5.dp.toPx())
            drawCircle(cs.secondary, 7.dp.toPx(), Offset(x, 8.dp.toPx()))
            drawCircle(cs.secondary, 7.dp.toPx(), Offset(x, h - 8.dp.toPx()))
        }
        if (playheadMs >= 0) {
            val px = xOf(playheadMs)
            if (px in 0f..w) drawLine(Color.White, Offset(px, 0f), Offset(px, h), 2.dp.toPx())
        }
    }
}
