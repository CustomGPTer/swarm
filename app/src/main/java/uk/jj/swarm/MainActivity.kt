@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.swarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import uk.jj.swarm.engine.Crawler
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.play.Music
import uk.jj.swarm.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Engine.start(this)
        handle(intent)
        setContent { SwarmTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra("openHash")?.let { Ui.openHash.value = it; i.removeExtra("openHash"); return }
        when (i.action) {
            Intent.ACTION_VIEW -> {
                val d = i.data ?: return
                i.data = null
                lifecycleScope.launch {
                    val msg = if (d.scheme == "magnet" || d.scheme == "http" || d.scheme == "https") Actions.addAny(this@MainActivity, d.toString(), "Link") else Actions.addUri(this@MainActivity, d)
                    Ui.tab.value = 0; Ui.say(msg)
                }
            }
            Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                i.removeExtra(Intent.EXTRA_TEXT)
                lifecycleScope.launch { val msg = Actions.addAny(this@MainActivity, t, "Shared"); Ui.tab.value = 0; Ui.say(msg) }
            }
        }
    }

    override fun onStart() { super.onStart(); Crawler.want("ui", true); if (Prefs.crawlMode == 3) Crawler.want("always", true) }
    override fun onStop() { super.onStop(); Crawler.want("ui", false) }
}

@Composable
fun Root() {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val msg by Ui.message.collectAsState()
    LaunchedEffect(msg) { msg?.let { Ui.message.value = null; snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it, duration = SnackbarDuration.Short) } }
    val open by Ui.openHash.collectAsState()
    LaunchedEffect(open) { open?.let { Ui.openHash.value = null; nav.navigate("detail/$it") } }
    val go by Ui.go.collectAsState()
    LaunchedEffect(go) { go?.let { Ui.go.value = null; if (it == "back") nav.popBackStack() else nav.navigate(it) } }
    val engineMsg by Engine.events.collectAsState()
    LaunchedEffect(engineMsg) { engineMsg?.let { Engine.events.value = null; Ui.say(it) } }
    var onboarded by remember { mutableStateOf(Prefs.onboarded) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        NavHost(nav, startDestination = "home") {
            composable("home") { Home(nav) }
            composable("detail/{hash}") { e -> DetailScreen(e.arguments?.getString("hash") ?: "", nav) }
            composable("result") { ResultScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
            composable("sources") { SourcesScreen(nav) }
            composable("rss") { RssScreen(nav) }
            composable("watch") { WatchScreen(nav) }
            composable("create") { CreateScreen(nav) }
            composable("about") { AboutScreen(nav) }
            composable("browser") { BrowserScreen(nav) }
            composable("help") { uk.jj.swarm.ui.HelpScreen(nav) }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 86.dp))
    }
    if (!onboarded) Onboarding { Prefs.setB("onboarded", true); onboarded = true }
}

private data class TabDef(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabDef("Torrents", Icons.Filled.CloudDownload), TabDef("Search", Icons.Filled.TravelExplore), TabDef("DHT", Icons.Filled.Hub),
    TabDef("Video", Icons.Filled.Movie), TabDef("Music", Icons.Filled.LibraryMusic),
)

@Composable
fun Home(nav: NavHostController) {
    val ctx = LocalContext.current
    val tab by Ui.tab.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var exit by remember { mutableStateOf(false) }
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    val music by Music.state.collectAsState()

    BackHandler(enabled = true) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            tab != 0 -> Ui.tab.value = 0
            else -> exit = true
        }
    }

    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(start = 24.dp, top = 28.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Honeycomb(Modifier.size(64.dp, 50.dp))
                    Spacer(Modifier.width(10.dp))
                    Column { Text("Swarm", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                @Composable fun item(icon: ImageVector, label: String, route: String) = NavigationDrawerItem(
                    icon = { Icon(icon, null) }, label = { Text(label) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; nav.navigate(route) }, modifier = Modifier.padding(horizontal = 12.dp))
                item(Icons.Filled.Settings, "Settings", "settings")
                item(Icons.Filled.Dns, "Search sources", "sources")
                item(Icons.Filled.RssFeed, "RSS feeds", "rss")
                item(Icons.Filled.NotificationsActive, "Watchlist", "watch")
                item(Icons.Filled.AddBox, "Create a torrent", "create")
                item(Icons.Filled.Public, "Browser", "browser")
                item(Icons.Filled.Info, "About and backup", "about")
                item(Icons.AutoMirrored.Filled.HelpOutline, "Help", "help")
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 24.dp))
                NavigationDrawerItem(icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }, label = { Text("Exit") }, selected = false,
                    onClick = { scope.launch { drawer.close() }; exit = true }, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    if (music.active) MiniPlayer(music)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        TABS.forEachIndexed { i, t ->
                            NavigationBarItem(selected = tab == i, onClick = { Ui.tab.value = i }, icon = { Icon(t.icon, t.label) },
                                label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
                when (tab) {
                    0 -> TorrentsScreen(nav, openMenu)
                    1 -> SearchScreen(nav, openMenu)
                    2 -> DhtScreen(nav, openMenu)
                    3 -> VideoScreen(openMenu)
                    else -> MusicScreen(openMenu)
                }
            }
        }
    }

    if (exit) ChoiceDialog("Are you sure you want to exit?", "No", "Yes", onNo = { exit = false }, onYes = {
        exit = false
        (ctx as? ComponentActivity)?.finishAffinity()
        Thread { Engine.shutdown() }.start()
    }) { Text("Downloads stop until you open Swarm again.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun MiniPlayer(s: uk.jj.swarm.play.MusicState) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable { Ui.tab.value = 4 }) {
                Text(s.title.ifBlank { "Playing" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (s.artist.isNotBlank()) Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { Music.prev() }) { Icon(Icons.Filled.SkipPrevious, "Previous") }
            IconButton(onClick = { Music.toggle() }) { Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause") }
            IconButton(onClick = { Music.next() }) { Icon(Icons.Filled.SkipNext, "Next") }
            IconButton(onClick = { Music.stop() }) { Icon(Icons.Filled.Close, "Stop") }
        }
    }
}

fun hasAllFiles(): Boolean = try { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() } catch (e: Exception) { false }

fun askAllFiles(ctx: Context) {
    try {
        if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
    } catch (e: Exception) { try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (x: Exception) { } }
}

fun batteryExempt(ctx: Context): Boolean = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@Composable
fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    BasicAlertDialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text("Welcome to Swarm", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Three things make downloads work properly. You can change any of them later in Android settings.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                key(refresh) {
                    Step("1", "Notifications and media", "Shows download progress, and lets the Video and Music tabs list your files.") {
                        val list = buildList {
                            if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.READ_MEDIA_VIDEO); add(Manifest.permission.READ_MEDIA_AUDIO) }
                            else add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        perms.launch(list.toTypedArray())
                    }
                    Step("2", if (hasAllFiles()) "File access: allowed" else "File access", "Lets Swarm save into your Download folder and sort finished files into Movies, TV, Music and Books.") { askAllFiles(ctx) }
                    Step("3", if (batteryExempt(ctx)) "Background running: allowed" else "Background running", "Stops Android pausing downloads when the screen is off.") {
                        try { settings.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) } catch (e: Exception) { }
                    }
                }
                Text("Other people in a torrent swarm can see your IP address. Swarm warns you when no VPN is on. Only download things you have the right to.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(24.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun Step(n: String, title: String, body: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text(n, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp); Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
