@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.melodix.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melodix.app.playback.EqEngine
import com.melodix.app.playback.EqPreset
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.GlassCard
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(vm: AppViewModel) {
    val eq by vm.eq.collectAsStateWithLifecycle()
    val custom by vm.customPresets.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    var saveDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EqPreset?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 180.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Эквалайзер", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
                Text("10 полос · точный DSP · ${eq.presetName}", style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = 0.6f))
            }
            Switch(checked = eq.enabled, onCheckedChange = { v -> vm.updateEq { it.copy(enabled = v) } })
        }

        // АЧХ
        GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
            ResponseCurve(Modifier.fillMaxWidth().height(150.dp).padding(12.dp), enabled = eq.enabled)
        }

        // Пресеты
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EqEngine.builtInPresets + custom, key = { (if (it.custom) "c:" else "b:") + it.name }) { p ->
                val selected = p.name == eq.presetName
                FilterChip(
                    selected = selected,
                    onClick = { vm.applyPreset(p) },
                    label = { Text(p.name) },
                    leadingIcon = if (p.custom) ({ Icon(Icons.Rounded.Star, null, Modifier.size(16.dp)) }) else null,
                    trailingIcon = if (p.custom) ({
                        Icon(Icons.Rounded.Close, "Удалить", Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).clickable { deleteTarget = p })
                    }) else null,
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = cs.surface.copy(alpha = 0.35f),
                        selectedContainerColor = cs.primary.copy(alpha = 0.32f),
                        labelColor = cs.onBackground.copy(alpha = 0.85f), selectedLabelColor = cs.onBackground,
                    ),
                    border = null,
                )
            }
        }

        // Полосы
        GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                EqEngine.BANDS.indices.forEach { i ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        val g = eq.gains.getOrElse(i) { 0f }
                        Text(if (g > 0) "+%.1f".format(g) else "%.1f".format(g), style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurface.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        VerticalBandSlider(
                            value = g, enabled = eq.enabled,
                            onChange = { v -> vm.updateEq { s -> s.copy(gains = s.gains.toMutableList().also { it[i] = v }, presetName = "Пользовательский") } },
                            modifier = Modifier.height(200.dp).width(30.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(EqEngine.BAND_LABELS[i], style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Text("Двойной тап по полосе — сброс в 0 дБ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
        }

        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                LabeledSlider("Предусиление", eq.preamp, -12f..12f, "дБ") { v -> vm.updateEq { it.copy(preamp = v) } }
                LabeledSlider("Бас (полка 90 Гц)", eq.bass, -12f..12f, "дБ") { v -> vm.updateEq { it.copy(bass = v, presetName = "Пользовательский") } }
                LabeledSlider("Верха (полка 9 кГц)", eq.treble, -12f..12f, "дБ") { v -> vm.updateEq { it.copy(treble = v, presetName = "Пользовательский") } }
                LabeledSlider("Ширина стерео", eq.width * 100f, 0f..200f, "%") { v -> vm.updateEq { it.copy(width = v / 100f) } }
                LabeledSlider("Добротность полос (Q)", eq.q, 0.5f..4f, "") { v -> vm.updateEq { it.copy(q = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Защита от перегрузки", style = MaterialTheme.typography.titleSmall)
                        Text("Мягкий лимитер без искажений при подъёме частот", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Switch(eq.limiter, { v -> vm.updateEq { it.copy(limiter = v) } })
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.applyPreset(EqEngine.builtInPresets.first()); vm.updateEq { it.copy(preamp = 0f, width = 1f, q = 1.41f) } },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Сброс")
            }
            Button(onClick = { saveDialog = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(6.dp)); Text("Сохранить")
            }
        }
    }

    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false }, shape = RoundedCornerShape(30.dp),
            title = { Text("Новый пресет") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, shape = RoundedCornerShape(16.dp)) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.saveCustomPreset(name.trim()); saveDialog = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Отмена") } },
        )
    }
    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null }, shape = RoundedCornerShape(30.dp),
            title = { Text("Удалить «${p.name}»?") },
            confirmButton = { TextButton(onClick = { vm.deleteCustomPreset(p.name); deleteTarget = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (unit == "%") "${value.roundToInt()}%" else "%.1f %s".format(value, unit).trim(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, { onChange(((it * 10).roundToInt() / 10f)) }, valueRange = range)
    }
}

@Composable
private fun VerticalBandSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val anim by animateFloatAsState(value, spring(stiffness = 900f), label = "band")
    val max = EqEngine.MAX_DB
    var dragValue by remember { mutableFloatStateOf(value) }
    val currentValue by rememberUpdatedState(value)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onChange(0f) })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragValue = currentValue },
                ) { change, dy ->
                    change.consume()
                    dragValue = (dragValue - dy / size.height * 2 * max).coerceIn(-max, max)
                    onChange((dragValue * 2).roundToInt() / 2f)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val trackW = 6.dp.toPx()
        val cx = w / 2
        val alpha = if (enabled) 1f else 0.35f
        drawRoundRect(cs.onSurface.copy(alpha = 0.12f * alpha), Offset(cx - trackW / 2, 0f), Size(trackW, h), CornerRadius(trackW))
        val zeroY = h / 2
        val y = zeroY - (anim / max) * (h / 2)
        drawLine(cs.onSurface.copy(alpha = 0.25f), Offset(cx - 10.dp.toPx(), zeroY), Offset(cx + 10.dp.toPx(), zeroY), 1.dp.toPx())
        drawRoundRect(
            Brush.verticalGradient(listOf(cs.primary.copy(alpha = alpha), cs.secondary.copy(alpha = alpha))),
            Offset(cx - trackW / 2, minOf(y, zeroY)), Size(trackW, kotlin.math.abs(y - zeroY)), CornerRadius(trackW),
        )
        drawCircle(cs.primary.copy(alpha = alpha), radius = 11.dp.toPx(), center = Offset(cx, y))
        drawCircle(Color.White.copy(alpha = 0.9f * alpha), radius = 4.dp.toPx(), center = Offset(cx, y))
    }
}

/** Точная суммарная АЧХ, рассчитанная из тех же биквадов, что обрабатывают звук. */
@Composable
private fun ResponseCurve(modifier: Modifier, enabled: Boolean) {
    val eq by EqEngine.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val points = remember(eq) {
        val n = 160
        FloatArray(n) { i ->
            val f = 20.0 * 10.0.pow(i / (n - 1.0) * log10(20000.0 / 20.0))
            EqEngine.responseDb(eq, f).toFloat()
        }
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val range = 18f
        // сетка
        listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
            val y = h / 2 - db / range * h / 2
            drawLine(cs.onSurface.copy(alpha = if (db == 0f) 0.25f else 0.08f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
        }
        listOf(100.0, 1000.0, 10000.0).forEach { f ->
            val x = (log10(f / 20) / log10(1000.0) * w).toFloat()
            drawLine(cs.onSurface.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
        }
        val path = Path()
        val fill = Path()
        points.forEachIndexed { i, db ->
            val x = i / (points.size - 1f) * w
            val y = (h / 2 - db.coerceIn(-range, range) / range * h / 2)
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h / 2); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, h / 2); fill.close()
        val a = if (enabled) 1f else 0.3f
        drawPath(fill, Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.35f * a), Color.Transparent)))
        drawPath(path, Brush.horizontalGradient(listOf(cs.primary.copy(alpha = a), cs.secondary.copy(alpha = a))), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}
