@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.melodix.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.melodix.app.Routes
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.AppBackground
import com.melodix.app.ui.components.ArtworkImage
import com.melodix.app.ui.components.PlayPauseButton
import com.melodix.app.ui.components.formatTime
import com.melodix.app.ui.components.rememberArtwork
import com.melodix.app.ui.theme.ImageTheme
import com.melodix.app.ui.theme.LocalVisuals

@Composable
fun NowPlayingScreen(vm: AppViewModel, nav: NavHostController) {
    val s by vm.player.state.collectAsStateWithLifecycle()
    val sleepLeft by vm.player.sleepRemainingMs.collectAsStateWithLifecycle()
    val visuals = LocalVisuals.current
    val cs = MaterialTheme.colorScheme
    val art = rememberArtwork(s.uri, 900)

    // Цвета фона из обложки
    val coverPalette = remember(art) { art?.let { ImageTheme.paletteFrom(it, true) } }
    val target = if (visuals.settings.adaptPlayerToCover && coverPalette != null) coverPalette.gradient else visuals.palette.gradient
    val g0 by animateColorAsState(target[0], tween(900), label = "c0")
    val g1 by animateColorAsState(target[1], tween(900), label = "c1")
    val g2 by animateColorAsState(target[2], tween(900), label = "c2")
    val accentTarget = if (visuals.settings.adaptPlayerToCover && coverPalette != null) coverPalette.primary else cs.primary
    val accent by animateColorAsState(accentTarget, tween(700), label = "acc")

    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val onBg = if (visuals.settings.adaptPlayerToCover && coverPalette != null) Color.White else cs.onBackground

    AppBackground(overrideGradient = listOf(g0, g1, g2)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть", tint = onBg, modifier = Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("СЕЙЧАС ИГРАЕТ", style = MaterialTheme.typography.labelSmall, color = onBg.copy(alpha = 0.7f))
                    if (s.queueSize > 0) Text("${s.queueIndex + 1} из ${s.queueSize}", style = MaterialTheme.typography.labelMedium, color = onBg)
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь", tint = onBg) }
            }

            Spacer(Modifier.weight(0.6f))

            // Обложка: масштаб «дышит» при паузе/игре, свайп — переключение трека
            val scale by animateFloatAsState(if (s.isPlaying) 1f else 0.86f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow), label = "art")
            var dragX by remember { mutableFloatStateOf(0f) }
            val dragAnim by animateFloatAsState(dragX, spring(stiffness = Spring.StiffnessMediumLow), label = "drag")
            AnimatedContent(
                targetState = s.mediaId,
                transitionSpec = {
                    (fadeIn(tween(380)) + slideInHorizontally(tween(380)) { it / 5 }) togetherWith (fadeOut(tween(250)) + slideOutHorizontally(tween(300)) { -it / 5 })
                },
                label = "cover",
            ) { _ ->
                ArtworkImage(
                    s.uri,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = dragAnim; rotationZ = dragAnim / 60f }
                        .shadow(28.dp, RoundedCornerShape(36.dp), ambientColor = accent, spotColor = accent)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (dragX < -160) vm.player.next() else if (dragX > 160) vm.player.previous()
                                    dragX = 0f
                                },
                                onDragCancel = { dragX = 0f },
                            ) { _, d -> dragX += d }
                        },
                    RoundedCornerShape(36.dp), 900,
                )
            }

            Spacer(Modifier.weight(0.5f))

            Text(
                s.title.ifEmpty { "Ничего не играет" }, style = MaterialTheme.typography.headlineSmall, color = onBg, maxLines = 1,
                modifier = Modifier.fillMaxWidth().basicMarquee(), textAlign = TextAlign.Center,
            )
            Text(s.artist, style = MaterialTheme.typography.titleMedium, color = onBg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)

            Spacer(Modifier.height(20.dp))

            // Перемотка
            var seeking by remember { mutableStateOf<Float?>(null) }
            val dur = s.durationMs.coerceAtLeast(1)
            val value = seeking ?: (s.positionMs.toFloat() / dur).coerceIn(0f, 1f)
            Slider(
                value = value,
                onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { vm.player.seekTo((it * dur).toLong()) }; seeking = null },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = onBg.copy(alpha = 0.2f)),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(((seeking ?: value) * dur).toLong()), style = MaterialTheme.typography.labelMedium, color = onBg.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text("-" + formatTime(dur - ((seeking ?: value) * dur).toLong()), style = MaterialTheme.typography.labelMedium, color = onBg.copy(alpha = 0.75f))
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val shuffleColor by animateColorAsState(if (s.shuffle) accent else onBg.copy(alpha = 0.6f), label = "sh")
                IconButton(onClick = vm.player::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "Перемешать", tint = shuffleColor) }
                IconButton(onClick = vm.player::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Назад", tint = onBg, modifier = Modifier.size(38.dp)) }
                PlayPauseButton(s.isPlaying, vm.player::togglePlay, Modifier.size(78.dp), 40.dp)
                IconButton(onClick = vm.player::next, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Вперёд", tint = onBg, modifier = Modifier.size(38.dp)) }
                val repeatColor by animateColorAsState(if (s.repeatMode != Player.REPEAT_MODE_OFF) accent else onBg.copy(alpha = 0.6f), label = "rp")
                IconButton(onClick = vm.player::cycleRepeat) {
                    Icon(if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Повтор", tint = repeatColor)
                }
            }

            Spacer(Modifier.weight(0.4f))

            // Инструменты
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.08f)).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val key = s.mediaId
                ToolIcon(Icons.Rounded.Speed, if (s.speed != 1f || s.pitch != 1f) "%.2fx".format(s.speed) else "Скорость", onBg) { showSpeed = true }
                ToolIcon(Icons.Rounded.Timer, if (sleepLeft > 0) formatTime(sleepLeft) else "Таймер", onBg) { showSleep = true }
                ToolIcon(Icons.Rounded.Equalizer, "Эквалайзер", onBg) { nav.navigate(Routes.EQ) { launchSingleTop = true } }
                ToolIcon(Icons.Rounded.ContentCut, "Обрезка", onBg) { if (key != null) nav.navigate(Routes.editor(key)) }
                ToolIcon(Icons.Rounded.GraphicEq, "Стемы", onBg) { if (key != null) nav.navigate(Routes.separate(key)) }
                ToolIcon(Icons.Rounded.Edit, "Теги", onBg) { if (key != null) nav.navigate(Routes.tags(key)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showQueue) QueueSheet(vm) { showQueue = false }
    if (showSpeed) SpeedDialog(s.speed, s.pitch, onApply = { sp, pi -> vm.player.setSpeedPitch(sp, pi) }) { showSpeed = false }
    if (showSleep) SleepDialog(onPick = { vm.player.setSleepTimer(it); showSleep = false }) { showSleep = false }
}

@Composable
private fun ToolIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.75f), maxLines = 1)
    }
}

@Composable
private fun QueueSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val s by vm.player.state.collectAsStateWithLifecycle()
    val queue = remember(s.queueSize, s.queueIndex, s.mediaId) { vm.player.queue() }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Text("Очередь · ${queue.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(queue, key = { it.index.toString() + it.mediaId }) { q ->
                val current = q.index == s.queueIndex
                Row(
                    Modifier.animateItem().fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { vm.player.skipTo(q.index) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${q.index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(q.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.player.moveInQueue(q.index, q.index - 1) }, enabled = q.index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Выше") }
                    IconButton(onClick = { vm.player.removeFromQueue(q.index) }) { Icon(Icons.Rounded.Close, "Убрать") }
                }
            }
        }
    }
}

@Composable
private fun SpeedDialog(speed: Float, pitch: Float, onApply: (Float, Float) -> Unit, onDismiss: () -> Unit) {
    var sp by remember { mutableFloatStateOf(speed) }
    var pi by remember { mutableFloatStateOf(pitch) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        title = { Text("Скорость и тональность") },
        text = {
            Column {
                Text("Скорость: %.2fx".format(sp))
                Slider(sp, { sp = (it * 20).toInt() / 20f; onApply(sp, pi) }, valueRange = 0.5f..2f)
                Text("Тональность: %.2fx (%+.1f полутона)".format(pi, 12 * kotlin.math.ln(pi.toDouble()) / kotlin.math.ln(2.0)))
                Slider(pi, { pi = (it * 100).toInt() / 100f; onApply(sp, pi) }, valueRange = 0.5f..2f)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = { TextButton(onClick = { sp = 1f; pi = 1f; onApply(1f, 1f) }) { Text("Сброс") } },
    )
}

@Composable
private fun SleepDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        title = { Text("Таймер сна") },
        text = {
            Column {
                listOf(0 to "Выключить", 5 to "5 минут", 10 to "10 минут", 15 to "15 минут", 30 to "30 минут", 45 to "45 минут", 60 to "1 час", 90 to "1,5 часа", 120 to "2 часа").forEach { (m, l) ->
                    Text(l, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(m) }.padding(14.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
