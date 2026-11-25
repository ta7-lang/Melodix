@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodix.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.melodix.app.data.LyricsRepository
import com.melodix.app.data.Lyrics
import com.melodix.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Текст песни на экране воспроизведения. Синхронизированный (LRC) — подсветка текущей строки,
 * плавная прокрутка, тап по строке — перемотка. Обычный — прокручиваемый текст.
 */
@Composable
fun LyricsPanel(track: Track?, positionMs: Long, accent: Color, onColor: Color, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val lyrics by produceState<Lyrics?>(null, track?.key, version) {
        value = track?.let { t -> withContext(Dispatchers.IO) { LyricsRepository.load(ctx, t) } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = track
        if (uri != null && t != null) scope.launch {
            withContext(Dispatchers.IO) {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (!text.isNullOrBlank()) LyricsRepository.saveCustom(ctx, t, text)
            }
            version++
        }
    }

    Box(modifier.clip(RoundedCornerShape(36.dp)).background(Color.Black.copy(alpha = 0.22f))) {
        val l = lyrics
        when {
            track == null -> Unit
            l == null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Текст не найден", style = MaterialTheme.typography.titleMedium, color = onColor)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Добавьте текст вручную, загрузите файл .lrc или положите «${track.title}.lrc» рядом с песней. Встроенный текст из тегов показывается автоматически.",
                    style = MaterialTheme.typography.bodySmall, color = onColor.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { editing = true }, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Вставить")
                    }
                    FilledTonalButton(onClick = { importer.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(".lrc")
                    }
                }
            }
            l.synced -> SyncedLyrics(l, positionMs, accent, onColor, onSeek)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 48.dp)) {
                l.lines.forEach { line ->
                    Text(
                        line.text.ifEmpty { " " }, style = MaterialTheme.typography.titleMedium, color = onColor,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }

        if (track != null && l != null) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Изменить текст", tint = onColor.copy(alpha = 0.8f)) }
                IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FileOpen, "Загрузить .lrc", tint = onColor.copy(alpha = 0.8f)) }
                if (LyricsRepository.hasCustom(ctx, track)) {
                    IconButton(onClick = { LyricsRepository.deleteCustom(ctx, track); version++ }) {
                        Icon(Icons.Rounded.Delete, "Удалить добавленный текст", tint = onColor.copy(alpha = 0.8f))
                    }
                }
            }
            Text(
                (if (l.synced) "Синхронизирован · " else "") + l.source,
                style = MaterialTheme.typography.labelSmall, color = onColor.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            )
        }
    }

    if (editing && track != null) {
        var text by remember { mutableStateOf(lyrics?.raw ?: "") }
        AlertDialog(
            onDismissRequest = { editing = false }, shape = RoundedCornerShape(30.dp),
            title = { Text("Текст песни") },
            text = {
                Column {
                    Text("Можно вставить обычный текст или LRC с метками времени [мм:сс.xx] — тогда строки будут подсвечиваться синхронно с музыкой.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp), shape = RoundedCornerShape(16.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = track
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (text.isBlank()) LyricsRepository.deleteCustom(ctx, t) else LyricsRepository.saveCustom(ctx, t, text)
                        }
                        version++
                    }
                    editing = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SyncedLyrics(l: Lyrics, positionMs: Long, accent: Color, onColor: Color, onSeek: (Long) -> Unit) {
    val lines = l.lines
    // +120 мс — компенсация задержки обновления позиции
    val current = remember(positionMs, lines) {
        var idx = -1
        for (i in lines.indices) { if (lines[i].timeMs <= positionMs + 120) idx = i else break }
        idx
    }
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Верхний отступ списка = половина высоты, поэтому прокрутка к строке ставит её по центру
        LaunchedEffect(current) {
            if (current >= 0) listState.animateScrollToItem(current)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = maxHeight / 2 - 24.dp, bottom = maxHeight / 2),
        ) {
            itemsIndexed(lines) { i, line ->
                val active = i == current
                val color by animateColorAsState(
                    when { active -> accent; i < current -> onColor.copy(alpha = 0.45f); else -> onColor.copy(alpha = 0.7f) },
                    tween(350), label = "lc",
                )
                val scale by animateFloatAsState(if (active) 1.06f else 1f, tween(350), label = "ls")
                Text(
                    line.text.ifEmpty { "♪" },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSeek(line.timeMs) }
                        .padding(vertical = 7.dp, horizontal = 4.dp),
                )
            }
        }
        // Мягкое затухание сверху и снизу
        Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))))
    }
}
