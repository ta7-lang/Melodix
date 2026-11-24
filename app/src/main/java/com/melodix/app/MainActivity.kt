package com.melodix.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.melodix.app.ui.AppViewModel
import com.melodix.app.ui.components.AppBackground
import com.melodix.app.ui.components.GlassCard
import com.melodix.app.ui.components.MiniPlayer
import com.melodix.app.ui.screens.EditorScreen
import com.melodix.app.ui.screens.EqualizerScreen
import com.melodix.app.ui.screens.LibraryScreen
import com.melodix.app.ui.screens.NowPlayingScreen
import com.melodix.app.ui.screens.SeparatorScreen
import com.melodix.app.ui.screens.TagEditorScreen
import com.melodix.app.ui.screens.ThemesScreen
import com.melodix.app.ui.screens.ToolsScreen
import com.melodix.app.ui.theme.MelodixTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var pendingOpen = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val visuals by vm.visuals.collectAsStateWithLifecycle()
            MelodixTheme(visuals) { AppRoot(vm, pendingOpen) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { pendingOpen.value = it }
    }
}

object Routes {
    const val LIBRARY = "library"
    const val EQ = "eq"
    const val TOOLS = "tools"
    const val THEMES = "themes"
    const val PLAYER = "player"
    const val EDITOR = "editor/{key}"
    const val TAGS = "tags/{key}"
    const val SEPARATE = "separate/{key}"
    fun editor(key: String) = "editor/" + Uri.encode(key)
    fun tags(key: String) = "tags/" + Uri.encode(key)
    fun separate(key: String) = "separate/" + Uri.encode(key)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.LIBRARY, "Музыка", Icons.Rounded.LibraryMusic),
    Tab(Routes.EQ, "Эквалайзер", Icons.Rounded.Equalizer),
    Tab(Routes.TOOLS, "Студия", Icons.Rounded.AutoAwesome),
    Tab(Routes.THEMES, "Темы", Icons.Rounded.Palette),
)

fun audioPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
}.toTypedArray()

@Composable
fun AppRoot(vm: AppViewModel, pendingOpen: androidx.compose.runtime.MutableState<Uri?>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val nav = rememberNavController()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                ctx, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasPermission = res[if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (hasPermission) vm.refreshLibrary()
    }
    LaunchedEffect(Unit) {
        if (hasPermission) vm.refreshLibrary() else launcher.launch(audioPermissions())
    }
    val open = pendingOpen.value
    LaunchedEffect(open) {
        if (open != null) {
            vm.openExternal(open)
            pendingOpen.value = null
            nav.navigate(Routes.PLAYER) { launchSingleTop = true }
        }
    }

    val showChrome = route in tabs.map { it.route }

    AppBackground {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = Routes.LIBRARY,
                enterTransition = { fadeIn(tween(340)) + scaleIn(tween(340, easing = FastOutSlowInEasing), initialScale = 0.96f) },
                exitTransition = { fadeOut(tween(220)) + scaleOut(tween(340), targetScale = 1.02f) },
                popEnterTransition = { fadeIn(tween(340)) + scaleIn(tween(340, easing = FastOutSlowInEasing), initialScale = 1.03f) },
                popExitTransition = { fadeOut(tween(220)) + scaleOut(tween(340), targetScale = 0.96f) },
            ) {
                composable(Routes.LIBRARY) {
                    LibraryScreen(vm, hasPermission, onRequestPermission = { launcher.launch(audioPermissions()) }, nav = nav)
                }
                composable(Routes.EQ) { EqualizerScreen(vm) }
                composable(Routes.TOOLS) { ToolsScreen(vm, nav) }
                composable(Routes.THEMES) { ThemesScreen(vm) }
                composable(
                    Routes.PLAYER,
                    enterTransition = { slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)) },
                    exitTransition = { fadeOut(tween(250)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                    popExitTransition = { slideOutVertically(tween(400, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(350)) },
                ) { NowPlayingScreen(vm, nav) }
                composable(Routes.EDITOR) { e -> KeyedTrack(vm, nav, e.arguments?.getString("key")) { t -> EditorScreen(vm, t, nav) } }
                composable(Routes.TAGS) { e -> KeyedTrack(vm, nav, e.arguments?.getString("key")) { t -> TagEditorScreen(vm, t, nav) } }
                composable(Routes.SEPARATE) { e -> KeyedTrack(vm, nav, e.arguments?.getString("key")) { t -> SeparatorScreen(vm, t, nav) } }
            }

            AnimatedVisibility(
                visible = showChrome,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(350)) { it } + fadeIn(),
                exit = slideOutVertically(tween(300)) { it } + fadeOut(),
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
                    AnimatedVisibility(visible = player.mediaId != null) {
                        MiniPlayer(
                            player,
                            onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                            onToggle = vm.player::togglePlay,
                            onNext = vm.player::next,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    BottomBar(route, nav)
                }
            }
        }
    }
}

@Composable
private fun KeyedTrack(vm: AppViewModel, nav: NavHostController, key: String?, content: @Composable (com.melodix.app.data.Track) -> Unit) {
    val all by vm.allTracks.collectAsStateWithLifecycle()
    val t = remember(all, key) { key?.let { k -> all.firstOrNull { it.key == k } } }
    if (t != null) content(t) else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Трек не найден", color = MaterialTheme.colorScheme.onBackground)
        }
        LaunchedEffect(all) { if (all.isNotEmpty() && t == null) nav.popBackStack() }
    }
}

@Composable
private fun BottomBar(route: String?, nav: NavHostController) {
    val cs = MaterialTheme.colorScheme
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(28.dp), alpha = 0.82f) {
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { tab ->
                val selected = route == tab.route
                val bg by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.22f) else Color.Transparent, tween(300), label = "tb")
                val fg by animateColorAsState(if (selected) cs.primary else cs.onSurfaceVariant, tween(300), label = "tf")
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(bg)
                        .clickable(remember { MutableInteractionSource() }, androidx.compose.material3.ripple()) {
                            if (!selected) nav.navigate(tab.route) {
                                popUpTo(Routes.LIBRARY) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(24.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
                }
            }
        }
    }
}
