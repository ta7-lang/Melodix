@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodix.app.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.melodix.app.Routes
import com.melodix.app.data.JobManager
import com.melodix.app.data.Track
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.ArtworkImage
import com.melodix.app.ui.components.GlassCard
import com.melodix.app.ui.components.formatTime

private enum class Tool(val title: String, val subtitle: String, val icon: ImageVector) {
    EDIT("Обрезка и вырезание", "Точное выделение по форме волны, фейды, нормализация, экспорт WAV/M4A", Icons.Rounded.ContentCut),
    SEPARATE("Вокал / инструментал", "Нейросеть MDX-Net офлайн: «минус» и чистый вокал", Icons.Rounded.GraphicEq),
    TAGS("Редактор тегов", "Название, исполнитель, альбом, год, жанр, обложка, текст", Icons.Rounded.Edit),
}

@Composable
fun ToolsScreen(vm: AppViewModel, nav: NavHostController) {
    val cs = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf<Tool?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 180.dp)) {
        Text("Студия", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Text("Все инструменты работают без интернета", style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))

        JobCard(vm, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Tool.entries.forEach { t ->
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { picking = t }) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(cs.primary, cs.secondary))),
                        contentAlignment = Alignment.Center,
                    ) { Icon(t.icon, null, tint = cs.onPrimary, modifier = Modifier.size(28.dp)) }
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            }
        }
    }

    picking?.let { tool ->
        TrackPicker(vm, title = tool.title, onDismiss = { picking = null }) { t ->
            picking = null
            nav.navigate(
                when (tool) {
                    Tool.EDIT -> Routes.editor(t.key)
                    Tool.SEPARATE -> Routes.separate(t.key)
                    Tool.TAGS -> Routes.tags(t.key)
                }
            )
        }
    }
}

@Composable
fun TrackPicker(vm: AppViewModel, title: String, onDismiss: () -> Unit, onPick: (Track) -> Unit) {
    val all by vm.allTracks.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val filtered = remember(all, q) {
        if (q.isBlank()) all else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text("Выберите трек", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Поиск") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            val current = player.mediaId?.let { id -> all.firstOrNull { it.key == id } }
            if (current != null && q.isBlank()) item {
                PickRow(current, "Сейчас играет") { onPick(current) }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            items(filtered, key = { it.key }) { t -> PickRow(t, null) { onPick(t) } }
        }
    }
}

@Composable
private fun PickRow(t: Track, badge: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(t.uri, Modifier.size(46.dp), RoundedCornerShape(14.dp), 140)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.artist} · ${formatTime(t.durationMs)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) AssistChip(onClick = onClick, label = { Text(badge) }, shape = RoundedCornerShape(12.dp))
    }
}

/** Карточка фоновой задачи: прогресс, отмена, результаты. */
@Composable
fun JobCard(vm: AppViewModel, modifier: Modifier = Modifier, kind: String? = null) {
    val job by JobManager.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val j = job
    AnimatedVisibility(visible = j != null && (kind == null || j.kind == kind), modifier = modifier) {
        if (j == null) return@AnimatedVisibility
        GlassCard(Modifier.fillMaxWidth().animateContentSize(), alpha = 0.7f) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(
                        when { j.error != null -> Color(0xFFFF5370); j.done -> Color(0xFF5EF0A0); else -> cs.primary }
                    ))
                    Text(j.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!j.done) TextButton(onClick = { JobManager.cancel() }) { Text("Отмена") }
                    else IconButton(onClick = { JobManager.dismiss() }) { Icon(Icons.Rounded.Close, "Скрыть") }
                }
                if (!j.done) {
                    val p by animateFloatAsState(j.progress, tween(400), label = "jp")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                    Spacer(Modifier.height(8.dp))
                    Text("${(j.progress * 100).toInt()}% · ${j.stage}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else if (j.error != null) {
                    Text(j.error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp))
                } else {
                    val secs = (System.currentTimeMillis() - j.startedAt) / 1000
                    Text("Готово за ${formatTime(secs * 1000)} · сохранено в Music/Melodix", style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    j.outputs.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                                .background(cs.primary.copy(alpha = 0.12f)).padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(o.label, style = MaterialTheme.typography.titleSmall)
                                Text(o.location, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.openExternal(o.uri) }) { Icon(Icons.Rounded.PlayArrow, "Слушать", tint = cs.primary) }
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/*"; putExtra(Intent.EXTRA_STREAM, o.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "Поделиться"))
                            }) { Icon(Icons.Rounded.Share, "Поделиться") }
                        }
                    }
                    TextButton(onClick = { vm.refreshLibrary() }) { Text("Обновить медиатеку") }
                }
            }
        }
    }
}
