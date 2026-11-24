@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.melodix.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.melodix.app.Routes
import com.melodix.app.data.Track
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.SortMode
import com.melodix.app.ui.components.ArtworkImage
import com.melodix.app.ui.components.GlassCard
import com.melodix.app.ui.components.formatTime

private enum class LibTab(val label: String) { SONGS("Треки"), ALBUMS("Альбомы"), ARTISTS("Исполнители"), FOLDERS("Папки") }

@Composable
fun LibraryScreen(vm: AppViewModel, hasPermission: Boolean, onRequestPermission: () -> Unit, nav: NavHostController) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibTab.SONGS) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val cs = MaterialTheme.colorScheme

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.openExternal(uri)
            nav.navigate(Routes.PLAYER) { launchSingleTop = true }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Заголовок
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Медиатека", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
                Text("${tracks.size} треков", style = MaterialTheme.typography.bodySmall, color = cs.onBackground.copy(alpha = 0.6f))
            }
            IconButton(onClick = { openFile.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.FolderOpen, "Открыть файл", tint = cs.onBackground) }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Сортировка", tint = cs.onBackground) }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label) },
                            leadingIcon = { if (m == sort) Icon(Icons.Rounded.Check, null) },
                            onClick = { vm.setSort(m); sortMenu = false }
                        )
                    }
                }
            }
            IconButton(onClick = { vm.refreshLibrary() }) { Icon(Icons.Rounded.Refresh, "Обновить", tint = cs.onBackground) }
        }

        // Поиск
        TextField(
            value = query, onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Поиск: название, исполнитель, альбом") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Очистить") } },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = cs.surface.copy(alpha = 0.6f), unfocusedContainerColor = cs.surface.copy(alpha = 0.45f),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        // Вкладки
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibTab.entries) { t ->
                FilterChip(
                    selected = tab == t, onClick = { tab = t; group = null },
                    label = { Text(t.label) }, shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = cs.surface.copy(alpha = 0.35f),
                        selectedContainerColor = cs.primary.copy(alpha = 0.3f),
                        selectedLabelColor = cs.onBackground, labelColor = cs.onBackground.copy(alpha = 0.8f),
                    ),
                    border = null,
                )
            }
        }

        if (!hasPermission) {
            PermissionCard(onRequestPermission)
            return@Column
        }
        if (loading && tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        AnimatedContent(
            targetState = tab to group,
            transitionSpec = { (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 12 }) togetherWith fadeOut(tween(200)) },
            label = "lib",
        ) { (t, g) ->
            val onPlay: (List<Track>, Int) -> Unit = { list, i -> vm.player.playTracks(list, i) }
            when {
                t == LibTab.SONGS -> TrackList(tracks, player.mediaId, player.isPlaying, onPlay, { menuTrack = it }, null)
                g != null -> {
                    val sub = tracks.filter {
                        when (t) {
                            LibTab.ALBUMS -> it.album == g
                            LibTab.ARTISTS -> it.artist == g
                            else -> it.folder == g
                        }
                    }
                    TrackList(sub, player.mediaId, player.isPlaying, onPlay, { menuTrack = it }, header = {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { group = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = cs.onBackground) }
                            Text(g, style = MaterialTheme.typography.titleLarge, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { vm.player.playTracks(sub.shuffled(), 0) }, shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Перемешать")
                            }
                        }
                    })
                }
                t == LibTab.ALBUMS -> {
                    val albums = remember(tracks) { tracks.groupBy { it.album }.toList().sortedBy { it.first.lowercase() } }
                    LazyVerticalGrid(
                        GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 180.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(albums, key = { it.first }) { (name, list) ->
                            GlassCard(Modifier.animateItem(), onClick = { group = name }) {
                                ArtworkImage(list.first().uri, Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp), RoundedCornerShape(20.dp), 400)
                                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
                                Text("${list.first().artist} · ${list.size}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
                            }
                        }
                    }
                }
                else -> {
                    val groups = remember(tracks, t) {
                        tracks.groupBy { if (t == LibTab.ARTISTS) it.artist else it.folder }.toList().sortedBy { it.first.lowercase() }
                    }
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 180.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(groups, key = { it.first }) { (name, list) ->
                            GlassCard(Modifier.fillMaxWidth().animateItem(), onClick = { group = name }) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(52.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                                        Icon(if (t == LibTab.ARTISTS) Icons.Rounded.Person else Icons.Rounded.Folder, null, tint = cs.primary)
                                    }
                                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${list.size} треков · ${formatTime(list.sumOf { it.durationMs })}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    menuTrack?.let { t ->
        TrackActionsSheet(t, onDismiss = { menuTrack = null }, onAction = { action ->
            menuTrack = null
            when (action) {
                TrackAction.PLAY_NEXT -> vm.player.playNext(t)
                TrackAction.QUEUE -> vm.player.addToQueue(t)
                TrackAction.TAGS -> nav.navigate(Routes.tags(t.key))
                TrackAction.EDIT -> nav.navigate(Routes.editor(t.key))
                TrackAction.SEPARATE -> nav.navigate(Routes.separate(t.key))
            }
        })
    }
}

@Composable
private fun TrackList(
    list: List<Track>, currentId: String?, playing: Boolean,
    onPlay: (List<Track>, Int) -> Unit, onMenu: (Track) -> Unit,
    header: (@Composable () -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 180.dp)) {
        if (header != null) item { header() }
        if (list.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Text("Здесь пока пусто", color = cs.onBackground.copy(alpha = 0.6f))
            }
        }
        itemsIndexed(list, key = { _, t -> t.key }) { i, t ->
            val current = t.key == currentId
            Row(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (current) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .combinedClickable(onClick = { onPlay(list, i) }, onLongClick = { onMenu(t) })
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ArtworkImage(t.uri, Modifier.size(52.dp), RoundedCornerShape(16.dp), 160)
                    if (current) Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        PlayingBars(playing)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, color = if (current) cs.primary else cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.artist} · ${formatTime(t.durationMs)} · ${t.extension.uppercase()}", style = MaterialTheme.typography.bodySmall,
                        color = cs.onBackground.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onMenu(t) }) { Icon(Icons.Rounded.MoreVert, "Действия", tint = cs.onBackground.copy(alpha = 0.7f)) }
            }
        }
    }
}

@Composable
fun PlayingBars(playing: Boolean, color: Color = Color.White) {
    val inf = rememberInfiniteTransition(label = "bars")
    val h = (0 until 3).map { i ->
        inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420 + i * 130), RepeatMode.Reverse), label = "b$i")
    }
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        h.forEach { s ->
            Box(Modifier.width(4.dp).fillMaxHeight(if (playing) s.value else 0.35f).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Нужен доступ к музыке", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Melodix работает полностью офлайн и читает только аудиофайлы на устройстве.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest, shape = RoundedCornerShape(18.dp)) { Text("Разрешить") }
        }
    }
}

enum class TrackAction(val label: String, val icon: ImageVector) {
    PLAY_NEXT("Играть следующим", Icons.AutoMirrored.Rounded.PlaylistPlay),
    QUEUE("Добавить в очередь", Icons.AutoMirrored.Rounded.QueueMusic),
    EDIT("Обрезать / вырезать фрагмент", Icons.Rounded.ContentCut),
    SEPARATE("Разделить: вокал и инструментал", Icons.Rounded.GraphicEq),
    TAGS("Редактировать теги", Icons.Rounded.Edit),
}

@Composable
fun TrackActionsSheet(t: Track, onDismiss: () -> Unit, onAction: (TrackAction) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtworkImage(t.uri, Modifier.size(64.dp), RoundedCornerShape(18.dp), 200)
            Column(Modifier.padding(start = 14.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${t.artist} · ${t.album}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        TrackAction.entries.forEach { a ->
            ListItem(
                headlineContent = { Text(a.label) },
                leadingContent = { Icon(a.icon, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = { onAction(a) }).padding(horizontal = 8.dp),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}
