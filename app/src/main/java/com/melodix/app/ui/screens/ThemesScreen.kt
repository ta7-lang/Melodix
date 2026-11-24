@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodix.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.GlassCard
import com.melodix.app.ui.theme.LocalVisuals
import com.melodix.app.ui.theme.ThemePreset
import com.melodix.app.ui.theme.ThemePresets

@Composable
fun ThemesScreen(vm: AppViewModel) {
    val t by vm.themeSettings.collectAsStateWithLifecycle()
    val visuals = LocalVisuals.current
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.setImageTheme(uri)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 180.dp)) {
        Text("Оформление", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Text("${ThemePresets.all.size} тем + ваша картинка", style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))

        // ---- Тема из изображения ----
        GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(84.dp).clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(visuals.palette.gradient + visuals.palette.primary))
                            .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val img = visuals.backgroundImage
                        if (img != null && t.useImage) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text("Своя картинка", style = MaterialTheme.typography.titleMedium)
                        Text("Цвета фото переносятся на градиенты, кнопки и акценты", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Выбрать") }
                            if (t.imagePath != null) TextButton(onClick = { vm.updateTheme { it.copy(useImage = !it.useImage) } }) {
                                Text(if (t.useImage) "Выключить" else "Включить")
                            }
                        }
                    }
                }
                AnimatedVisibility(t.useImage && t.imagePath != null) {
                    Column(Modifier.padding(top = 8.dp)) {
                        // Извлечённая палитра
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            (visuals.palette.gradient + listOf(visuals.palette.primary, visuals.palette.secondary)).forEach { c ->
                                Box(Modifier.size(34.dp).clip(CircleShape).background(c).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
                            }
                        }
                        SettingSwitch("Тёмный вариант", "Светлый вариант — для светлых фото", t.imageDark) { v -> vm.updateTheme { it.copy(imageDark = v) } }
                        SettingSwitch("Показывать картинку на фоне", "Иначе — только её цвета", t.imageVisible) { v -> vm.updateTheme { it.copy(imageVisible = v) } }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            LabeledSlider("Размытие", t.imageBlur, 0f..40f, "dp") { v -> vm.updateTheme { it.copy(imageBlur = v) } }
                        }
                        LabeledSlider("Затемнение", t.imageDim * 100, 0f..100f, "%") { v -> vm.updateTheme { it.copy(imageDim = v / 100f) } }
                    }
                }
            }
        }

        // ---- Готовые темы ----
        Text("Темы", style = MaterialTheme.typography.titleMedium, color = cs.onBackground, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        val rows = ThemePresets.all.chunked(3)
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { p ->
                        PresetTile(p, selected = !t.useImage && t.presetId == p.id, modifier = Modifier.weight(1f)) { vm.selectPreset(p.id) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // ---- Прочее ----
        GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                SettingSwitch("Цвета обложки в плеере", "Фон экрана «Сейчас играет» подстраивается под обложку", t.adaptPlayerToCover) { v ->
                    vm.updateTheme { it.copy(adaptPlayerToCover = v) }
                }
                SettingSwitch("Живой фон", "Медленно переливающийся градиент", t.animatedBackground) { v -> vm.updateTheme { it.copy(animatedBackground = v) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val granted = android.os.Environment.isExternalStorageManager()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Доступ ко всем файлам", style = MaterialTheme.typography.titleSmall)
                            Text(if (granted) "Предоставлен — теги сохраняются без запросов" else "Нужен для редактирования тегов без подтверждений",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        if (!granted) TextButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))) }
                        }) { Text("Открыть") }
                    }
                }
            }
        }
        Text("Melodix 1.0 · полностью офлайн", style = MaterialTheme.typography.labelSmall, color = cs.onBackground.copy(alpha = 0.5f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
    }
}

@Composable
private fun PresetTile(p: ThemePreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1f else 0.94f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "tile")
    val border by animateDpAsState(if (selected) 3.dp else 0.dp, label = "tb")
    Column(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .border(border, p.primary, RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(p.gradient))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(p.primary))
            Box(Modifier.size(18.dp).clip(CircleShape).background(p.secondary))
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(selected, enter = scaleIn(), exit = scaleOut()) {
                Icon(Icons.Rounded.CheckCircle, null, tint = p.primary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(6.dp).clip(CircleShape).background(p.primary.copy(alpha = 0.8f)))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(6.dp).clip(CircleShape).background((if (p.dark) Color.White else Color.Black).copy(alpha = 0.3f)))
        Spacer(Modifier.height(10.dp))
        Text(p.name, style = MaterialTheme.typography.labelLarge, color = if (p.dark) Color.White else Color(0xFF1A1A1A), maxLines = 1)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
