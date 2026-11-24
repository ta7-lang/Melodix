@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodix.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.melodix.app.data.Track
import com.melodix.app.tags.SaveResult
import com.melodix.app.tags.TagData
import com.melodix.app.tags.TagEditor
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.ArtworkLoader
import com.melodix.app.ui.components.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun TagEditorScreen(vm: AppViewModel, track: Track, nav: NavHostController) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<TagData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(track.key) {
        try { data = withContext(Dispatchers.IO) { TagEditor.read(ctx, track) } }
        catch (e: Exception) { error = "Не удалось прочитать теги: ${e.message ?: e.javaClass.simpleName}" }
    }

    fun save() {
        val d = data ?: return
        saving = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { TagEditor.write(ctx, track, d) }
            saving = false
            when (r) {
                is SaveResult.Ok -> {
                    ArtworkLoader.invalidate(); vm.refreshLibrary()
                    data = d.copy(artworkChanged = false)
                    snackbar.showSnackbar("Теги сохранены")
                }
                is SaveResult.Error -> snackbar.showSnackbar(r.message)
                is SaveResult.NeedPermission -> pendingSender.value = r
            }
        }
    }

    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) save()
    }
    val pending by pendingSender
    LaunchedEffect(pending) {
        pending?.let { writeLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()); pendingSender.value = null }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { compressCover(ctx, uri) }
            if (bytes != null) data = data?.copy(artwork = bytes, artworkChanged = true)
        }
    }

    Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = cs.onBackground) }
                Column(Modifier.weight(1f)) {
                    Text("Теги", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
                    Text(track.displayName, style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(onClick = { save() }, enabled = data != null && !saving, shape = RoundedCornerShape(16.dp)) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Save, null)
                    Spacer(Modifier.width(6.dp)); Text("Сохранить")
                }
            }

            AnimatedContent(targetState = data == null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tags") { loading ->
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        if (error != null) Text(error!!, color = Color(0xFFFF8A80)) else CircularProgressIndicator()
                    }
                } else {
                    val d = data!!
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                val bmp = remember(d.artwork) { d.artwork?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                                Box(
                                    Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)).background(cs.primary.copy(alpha = 0.2f))
                                        .clickable { imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (bmp != null) Image(bmp.asImageBitmap(), "Обложка", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else Icon(Icons.Rounded.AddPhotoAlternate, null, tint = cs.primary, modifier = Modifier.size(40.dp))
                                }
                                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                    Text("${d.format}${if (d.lossless) " · lossless" else ""}", style = MaterialTheme.typography.titleSmall)
                                    Text(d.bitrate, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    Text("${d.sampleRate} · ${d.channels}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Row {
                                        TextButton(onClick = { imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Обложка") }
                                        if (d.artwork != null) TextButton(onClick = { data = d.copy(artwork = null, artworkChanged = true) }) { Text("Удалить") }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TagField("Название", d.title) { data = d.copy(title = it) }
                                TagField("Исполнитель", d.artist) { data = d.copy(artist = it) }
                                TagField("Альбом", d.album) { data = d.copy(album = it) }
                                TagField("Исполнитель альбома", d.albumArtist) { data = d.copy(albumArtist = it) }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TagField("Год", d.year, Modifier.weight(1f), number = true) { data = d.copy(year = it) }
                                    TagField("№ трека", d.track, Modifier.weight(1f), number = true) { data = d.copy(track = it) }
                                    TagField("Диск", d.disc, Modifier.weight(1f), number = true) { data = d.copy(disc = it) }
                                }
                                TagField("Жанр", d.genre) { data = d.copy(genre = it) }
                                TagField("Композитор", d.composer) { data = d.copy(composer = it) }
                                TagField("Комментарий", d.comment) { data = d.copy(comment = it) }
                                TagField("Текст песни", d.lyrics, single = false) { data = d.copy(lyrics = it) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Совет", style = MaterialTheme.typography.titleSmall)
                                    Text("Без «Доступа ко всем файлам» Android будет спрашивать разрешение при каждом сохранении.",
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    TextButton(onClick = {
                                        runCatching {
                                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                                        }
                                    }) { Text("Разрешить доступ") }
                                }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

private val pendingSender = mutableStateOf<SaveResult.NeedPermission?>(null)

@Composable
private fun TagField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, single: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier, label = { Text(label) },
        singleLine = single, minLines = if (single) 1 else 4, shape = RoundedCornerShape(16.dp),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

/** Сжимает картинку обложки до 1000×1000 JPEG. */
private fun compressCover(ctx: android.content.Context, uri: Uri): ByteArray? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / (s * 2) >= 1000 && o.outHeight / (s * 2) >= 1000) s *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
    val side = minOf(bmp.width, bmp.height)
    val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    val scaled = if (side > 1000) Bitmap.createScaledBitmap(sq, 1000, 1000, true) else sq
    ByteArrayOutputStream().use { os -> scaled.compress(Bitmap.CompressFormat.JPEG, 92, os); os.toByteArray() }
}.getOrNull()
