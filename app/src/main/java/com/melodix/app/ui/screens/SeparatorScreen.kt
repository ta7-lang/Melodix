@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodix.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.melodix.app.audio.ExportFormat
import com.melodix.app.data.JobManager
import com.melodix.app.data.Track
import com.melodix.app.separation.ModelInfo
import com.melodix.app.separation.ModelManager
import com.melodix.app.separation.SepOptions
import com.melodix.app.separation.SepQuality
import com.melodix.app.separation.Separator
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.ArtworkImage
import com.melodix.app.ui.components.GlassCard
import com.melodix.app.ui.components.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SeparatorScreen(vm: AppViewModel, track: Track, nav: NavHostController) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val job by JobManager.state.collectAsStateWithLifecycle()
    var models by remember { mutableStateOf(ModelManager.available(ctx)) }
    var selected by remember(models) { mutableStateOf<ModelInfo?>(models.firstOrNull()) }
    var useDsp by remember(models) { mutableStateOf(models.isEmpty()) }
    var quality by remember { mutableStateOf(SepQuality.BALANCED) }
    var format by remember { mutableStateOf(ExportFormat.WAV) }
    var strength by remember { mutableFloatStateOf(0.7f) }
    var accelerate by remember { mutableStateOf(true) }
    var calVersion by remember { mutableIntStateOf(0) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    val options = SepOptions(model = if (useDsp) null else selected, quality = quality, format = format, dspStrength = strength, accelerate = accelerate)
    val busy = job?.done == false

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = cs.onBackground) }
            Text("Вокал и инструментал", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
        }

        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(track.uri, Modifier.size(64.dp), RoundedCornerShape(18.dp), 200)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${track.artist} · ${formatTime(track.durationMs)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }

        Text("Метод", style = MaterialTheme.typography.titleMedium, color = cs.onBackground, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
        models.forEach { m ->
            MethodCard(
                title = "Нейросеть · ${m.title}",
                subtitle = "MDX-Net, ${if (m.primaryIsVocals) "вокальная" else "инструментальная"} модель, n_fft ${m.nFft}. Лучшее качество, работает офлайн.",
                selected = !useDsp && selected == m,
                onClick = { useDsp = false; selected = m },
                onDelete = if (m.customFile != null) ({ ModelManager.delete(m); models = ModelManager.available(ctx) }) else null,
            )
        }
        MethodCard(
            title = "Быстрый DSP-метод",
            subtitle = "Анализ стереобазы без нейросети. Секунды на трек, но с заметными артефактами — для караоке «на скорую руку».",
            selected = useDsp, onClick = { useDsp = true }, onDelete = null,
        )
        if (models.isEmpty()) {
            Text(
                "Нейросетевая модель не найдена в APK. Соберите приложение через GitHub Actions (модель скачается автоматически) или импортируйте .onnx-файл модели UVR MDX-Net.",
                style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        TextButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.padding(start = 12.dp)) {
            Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Импортировать модель .onnx")
        }

        GlassCard(Modifier.fillMaxWidth().padding(16.dp).animateContentSize()) {
            Column(Modifier.padding(16.dp)) {
                if (!useDsp) {
                    Text("Качество", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        SepQuality.entries.forEachIndexed { i, q ->
                            SegmentedButton(quality == q, { quality = q }, SegmentedButtonDefaults.itemShape(i, SepQuality.entries.size)) { Text(q.label) }
                        }
                    }
                    Text(quality.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Аппаратное ускорение (NPU)", style = MaterialTheme.typography.titleSmall)
                            val cal = remember(selected, calVersion, job?.done) { selected?.let { Separator.calibrationInfo(ctx, it) } }
                            Text(
                                when {
                                    !accelerate -> "Только процессор, FP32"
                                    cal == null -> "При первом запуске приложение само выберет самый быстрый вычислитель и проверит точность"
                                    else -> "${cal.backend.label} · ${"%.1f".format(cal.secondsPerPass)} с на блок" +
                                        (cal.snrDb?.let { " · точность ${"%.0f".format(it)} дБ" } ?: "")
                                },
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                        Switch(accelerate, { accelerate = it })
                    }
                    if (accelerate && selected != null) {
                        TextButton(onClick = { selected?.let { Separator.resetCalibration(ctx, it) }; calVersion++ }, enabled = !busy) {
                            Text("Перекалибровать ускоритель")
                        }
                    }
                } else {
                    LabeledSlider("Строгость выделения вокала", strength * 100, 0f..100f, "%") { strength = it / 100f }
                }
                Spacer(Modifier.height(12.dp))
                Text("Формат", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    ExportFormat.entries.forEachIndexed { i, f ->
                        SegmentedButton(format == f, { format = f }, SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size)) { Text(f.label) }
                    }
                }
                Text("Ориентировочно: ~${formatTime(Separator.estimateSeconds(ctx, track.durationMs, options) * 1000)} (зависит от процессора)",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Button(
                    enabled = !busy && (useDsp || selected != null),
                    onClick = {
                        JobManager.start(ctx, "separate", "Разделение: ${track.title}") { Separator.run(ctx, track, options, this) }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.GraphicEq, null); Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Идёт обработка…" else "Разделить на дорожки")
                }
                Text(
                    "Обработка идёт в фоне — можно свернуть приложение. Результат: два файла в Music/Melodix.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        JobCard(vm, Modifier.padding(horizontal = 16.dp), kind = "separate")
        Spacer(Modifier.height(32.dp))
    }

    importUri?.let { uri ->
        var nfft by remember { mutableStateOf("6144") }
        var vocals by remember { mutableStateOf(false) }
        var comp by remember { mutableStateOf("1.0") }
        var name by remember { mutableStateOf(uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.onnx") }
        var busyImport by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { importUri = null }, shape = RoundedCornerShape(30.dp),
            title = { Text("Импорт модели MDX-Net") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(nfft, { nfft = it.filter(Char::isDigit) }, label = { Text("n_fft (6144, 7680, 5120…)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(comp, { comp = it }, label = { Text("Компенсация (обычно 1.0–1.07)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Модель выдаёт вокал", Modifier.weight(1f)); Switch(vocals, { vocals = it })
                    }
                    Text("Параметры можно найти в model_data.json проекта Ultimate Vocal Remover.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !busyImport, onClick = {
                    busyImport = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                ModelManager.import(ctx, uri, name, nfft.toIntOrNull() ?: 6144, vocals, comp.replace(',', '.').toFloatOrNull() ?: 1f)
                            }
                        }
                        models = ModelManager.available(ctx)
                        busyImport = false
                        importUri = null
                    }
                }) { Text(if (busyImport) "Копирование…" else "Импорт") }
            },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun MethodCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.2f) else cs.surface.copy(alpha = 0.45f))
            .border(if (selected) 1.5.dp else 0.dp, if (selected) cs.primary else Color.Transparent, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Удалить модель") }
    }
}
