@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.swarm.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.LibTorrent
import uk.jj.swarm.Actions
import uk.jj.swarm.BuildConfig
import uk.jj.swarm.Db
import uk.jj.swarm.Fmt
import uk.jj.swarm.Prefs
import uk.jj.swarm.RssRow
import uk.jj.swarm.SourceRow
import uk.jj.swarm.Ui
import uk.jj.swarm.WatchRow
import uk.jj.swarm.WatchWorker
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.hasAllFiles
import uk.jj.swarm.search.Search
import uk.jj.swarm.search.SiteProvider
import uk.jj.swarm.search.SiteReader
import uk.jj.swarm.search.TorznabProvider
import java.io.File

// ------------------------------------------------------------------------------------------- sources

@Composable
fun SourcesScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var add by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { rows = withContext(Dispatchers.IO) { Db.get(ctx).sources() } }

    BackPage("Search sources", { nav.popBackStack() }, actions = { IconButton(onClick = { add = true }) { Icon(Icons.Filled.Add, "Add a source") } }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Banner("Swarm comes with open catalogues only. Any source you add is your choice. Many sites list material that is not free to share, so only download what you have the right to.", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            SectionTitle(null, "Built in")
            Search.BUILT_IN.forEach { (name, desc) -> SwitchRow(name, "src_$name", true, desc) }
            SectionTitle(null, "Your sources")
            if (rows.isEmpty()) Text("None yet. Tap + to add a Jackett or Torznab address, or an ordinary website address.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 16.sp)
                        Text((if (r.type == "torznab") "Torznab / Jackett · " else "Website · ") + r.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(r.enabled, { on -> scope.launch(Dispatchers.IO) { Db.get(ctx).setSourceEnabled(r.id, on); reload++ } })
                    IconButton(onClick = { scope.launch(Dispatchers.IO) { Db.get(ctx).deleteSource(r.id); reload++ } }) { Icon(Icons.Filled.Delete, "Remove") }
                }
            }
            Button(onClick = { add = true }, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp), shape = RoundedCornerShape(24.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a source") }
        }
    }
    if (add) AddSourceDialog({ add = false }) { add = false; reload++ }
}

@Composable
private fun AddSourceDialog(onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("torznab") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun row(): SourceRow? {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) { status = "The address must start with http:// or https://"; return null }
        val finalUrl = if (type == "site") SiteReader.toTemplate(u) ?: run { status = "Search the site for the word test first, then paste the address of that results page. Swarm needs to see where the search word goes."; return null } else u
        val n = name.trim().ifBlank { try { Uri.parse(u).host ?: "Source" } catch (e: Exception) { "Source" } }
        return SourceRow(0, type, n, finalUrl, key.trim(), true)
    }

    AlertDialog(onDismissRequest = onClose, title = { Text("Add a source") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == "torznab", { type = "torznab"; status = "" }, { Text("Torznab / Jackett") })
                FilterChip(type == "site", { type = "site"; status = "" }, { Text("Website") })
            }
            Text(if (type == "torznab") "Paste the Torznab feed address and API key from Jackett or Prowlarr, or a site's own Torznab address."
                else "Search the site in your browser for the word test, then paste the address of the results page here.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; status = "" }, label = { Text(if (type == "torznab") "Torznab address" else "Results page address") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            if (type == "torznab") OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = { val r = row() ?: return@TextButton; scope.launch(Dispatchers.IO) { Db.get(ctx).addSource(r.type, r.name, r.url, r.apikey); withContext(Dispatchers.Main) { onSaved() } } }) { Text("Save") }
    }, dismissButton = {
        Row {
            TextButton(enabled = !busy, onClick = {
                val r = row() ?: return@TextButton
                busy = true; status = "Testing with the word \"ubuntu\"…"
                scope.launch(Dispatchers.IO) {
                    val found = try { (if (r.type == "torznab") TorznabProvider(r) else SiteProvider(r, ctx)).search("ubuntu") } catch (e: Exception) { null }
                    val n = found?.size ?: -1
                    val pageOnly = found != null && found.isNotEmpty() && found.all { it.pageOnly }
                    status = when { pageOnly -> "Working: $n results came back. This site keeps its download links on each result's own page, so Swarm opens that page when you tap Download."; n > 0 -> "Working: $n results came back."; n == 0 -> "It answered but gave no results Swarm could read. Check the address" + (if (r.type == "torznab") " and key." else ", or try the site's results page for another word."); else -> "Could not reach it." }
                    busy = false
                }
            }) { Text("Test") }
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    })
}

// ------------------------------------------------------------------------------------------- rss + watchlist

@Composable
fun RssScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<RssRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var add by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { rows = withContext(Dispatchers.IO) { Db.get(ctx).feeds() } }
    BackPage("RSS feeds", { nav.popBackStack() }, actions = {
        IconButton(onClick = { WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<WatchWorker>().build()); Ui.say("Checking feeds and watchlist now") }) { Icon(Icons.Filled.Refresh, "Check now") }
        IconButton(onClick = { add = true }) { Icon(Icons.Filled.Add, "Add a feed") }
    }) {
        Text("A feed is a list a site publishes of its newest torrents. Swarm checks each feed about once an hour. New items that match your filter are downloaded automatically, or just notified, as you choose.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
        if (rows.isEmpty()) EmptyState("No feeds yet.", "Tap + to add one.")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 16.sp)
                        Text(r.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (r.filter.isBlank()) "Everything" else "Filter: ${r.filter}") + " · " + (if (r.auto) "downloads automatically" else "notifies only") + " · checked " + (if (r.lastCheck > 0) Fmt.age(r.lastCheck) else "never"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch(Dispatchers.IO) { Db.get(ctx).deleteFeed(r.id); reload++ } }) { Icon(Icons.Filled.Delete, "Remove") }
                }
            }
        }
    }
    if (add) {
        var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("") }; var auto by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { add = false }, title = { Text("Add a feed") }, text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Feed address") }, singleLine = true, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(filter, { filter = it }, label = { Text("Filter (optional)") }, singleLine = true, modifier = Modifier.padding(top = 6.dp))
                Text("Words that must all appear in the title. Put - in front of a word to exclude it. Empty means everything.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Download matches automatically", modifier = Modifier.weight(1f)); Switch(auto, { auto = it }) }
                Text("Items already in the feed when you add it are skipped. Only new ones count.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = { if (url.trim().startsWith("http")) { scope.launch(Dispatchers.IO) { Db.get(ctx).addFeed(name.trim().ifBlank { "Feed" }, url.trim(), filter.trim(), auto); reload++ }; add = false } else Ui.say("The feed address must start with http") }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } })
    }
}

@Composable
fun WatchScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<WatchRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var add by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { rows = withContext(Dispatchers.IO) { Db.get(ctx).watches() } }
    BackPage("Watchlist", { nav.popBackStack() }, actions = { IconButton(onClick = { add = true }) { Icon(Icons.Filled.Add, "Add") } }) {
        Text("Saved searches. About once an hour Swarm runs each one across your sources and sends a notification when something new appears.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
        if (rows.isEmpty()) EmptyState("Nothing on your watchlist.", "Tap the bell on any search to add it.")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { SearchState.run(ctx, r.query); Ui.tab.value = 1; nav.popBackStack() }.padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(r.query, fontSize = 16.sp); Text("Checked " + if (r.lastCheck > 0) Fmt.age(r.lastCheck) else "never", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { scope.launch(Dispatchers.IO) { Db.get(ctx).deleteWatch(r.id); reload++ } }) { Icon(Icons.Filled.Delete, "Remove") }
                }
            }
        }
    }
    if (add) {
        var q by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { add = false }, title = { Text("Watch a search") }, text = { OutlinedTextField(q, { q = it }, label = { Text("Search words") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (q.isNotBlank()) scope.launch(Dispatchers.IO) { Db.get(ctx).addWatch(q.trim()); reload++ }; add = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } })
    }
}

// ------------------------------------------------------------------------------------------- create

/** Turns a Storage Access Framework pick into an ordinary path on the phone's main storage. */
private fun pathOf(uri: Uri, tree: Boolean): String? = try {
    val id = if (tree) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
    when {
        id.startsWith("primary:") -> "/storage/emulated/0/" + id.removePrefix("primary:")
        id.startsWith("raw:") -> id.removePrefix("raw:")
        id.contains(':') -> "/storage/" + id.substringBefore(':') + "/" + id.substringAfter(':')
        else -> null
    }
} catch (e: Exception) { null }

@Composable
fun CreateScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var trackers by remember { mutableStateOf(Prefs.trackers) }
    var comment by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var magnet by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf(false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) path = pathOf(u, false) ?: run { Ui.say("Pick a file from the phone's own storage"); "" } }
    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) path = pathOf(u, true) ?: run { Ui.say("Pick a folder from the phone's own storage"); "" } }

    BackPage("Create a torrent", { nav.popBackStack() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text("Makes a torrent from a file or folder of your own, starts seeding it from where it is, and gives you a magnet link to share.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasAllFiles()) Text("File access is off, so Swarm may not be able to read the file. Allow it in Settings.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Description, null); Spacer(Modifier.width(6.dp)); Text("Pick a file") }
                OutlinedButton(onClick = { pickDir.launch(null) }) { Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(6.dp)); Text("Pick a folder") }
            }
            if (path.isNotBlank()) Text(path.replace("/storage/emulated/0/", "Phone storage/"), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
            OutlinedTextField(trackers, { trackers = it }, label = { Text("Trackers, one per line") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            OutlinedTextField(comment, { comment = it }, label = { Text("Comment (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Private torrent"); Text("Peers are found only through the trackers above, not DHT", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(priv, { priv = it })
            }
            Button(enabled = path.isNotBlank() && !busy, onClick = {
                busy = true; magnet = null
                scope.launch(Dispatchers.IO) {
                    val f = File(path)
                    val m = if (!f.exists() || !f.canRead()) null else Engine.create(f, trackers.lines().map { it.trim() }.filter { it.isNotBlank() }, priv, comment.trim())
                    magnet = m; busy = false
                    Ui.say(if (m != null) "Torrent created and seeding" else "Could not read that file or folder")
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(24.dp)) { Text(if (busy) "Working… big files take a while" else "Create and seed") }
            magnet?.let { m ->
                Text("Magnet link", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                Text(m, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { Actions.copy(ctx, m, "Magnet link copied") }) { Text("Copy") }
                    FilledTonalButton(onClick = { Actions.share(ctx, m) }) { Text("Share") }
                    FilledTonalButton(onClick = { qr = true }) { Text("QR") }
                }
                Text("The .torrent file was saved next to the original.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (qr && magnet != null) QrDialog(magnet!!) { qr = false }
}

// ------------------------------------------------------------------------------------------- about + backup

@Composable
fun AboutScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val crashFile = remember { File(ctx.filesDir, "crash.txt") }
    var crash by remember { mutableStateOf(if (crashFile.exists()) crashFile.readText() else "") }
    var showCrash by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) scope.launch(Dispatchers.IO) { try { ctx.contentResolver.openOutputStream(u)?.use { it.write(Actions.exportJson(ctx).toByteArray()) }; Ui.say("Backup file saved") } catch (e: Exception) { Ui.say("Could not save the backup file") } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch(Dispatchers.IO) { val t = try { ctx.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() } } catch (e: Exception) { null }; Ui.say(if (t == null) "Could not read that file" else Actions.importJson(ctx, t)) }
    }
    val engine = remember { try { LibTorrent.version() } catch (e: Throwable) { "?" } }

    BackPage("About and backup", { nav.popBackStack() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Honeycomb(Modifier.size(84.dp, 64.dp)); Spacer(Modifier.width(12.dp))
                Column { Text("Swarm", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · libtorrent $engine", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            SectionTitle(Icons.Filled.Backup, "Backup")
            ValueRow("Save a backup file", "", "Your torrent list, sources, feeds, watchlist and settings in one file") { export.launch("swarm-backup.json") }
            ValueRow("Restore from a backup file", "", "Adds everything in the file to this phone") { restore.launch(arrayOf("application/json", "*/*")) }
            SectionTitle(Icons.Filled.SystemUpdate, "Updates")
            ValueRow("Check for a new version", "", "Looks at the Swarm download page on GitHub") { scope.launch { uk.jj.swarm.Updater.check(true); Ui.say(uk.jj.swarm.Updater.available.value?.let { "Version ${it.first} is available. See the Torrents tab." } ?: "You have the latest version, or the download page could not be reached.") } }
            TextRow("Download page", "updateRepo", BuildConfig.UPDATE_REPO, "The GitHub repository Swarm checks for new versions, written as owner/name. Leave empty to switch update checks off.")
            SectionTitle(Icons.Filled.BugReport, "Problems")
            ValueRow("Last crash report", if (crash.isBlank()) "None" else "View", "If Swarm ever closes by itself, the reason is kept here") { if (crash.isNotBlank()) showCrash = true }
            SectionTitle(Icons.Filled.Gavel, "Open source licences")
            Text("Swarm is built on these open-source projects:\n\n• libtorrent (Arvid Norberg and contributors), BSD 3-Clause\n• libtorrent4j (Alden Torres), MIT\n• AndroidX, Jetpack Compose and Media3 (Google), Apache 2.0\n• FFmpeg audio decoders via the Jellyfin Media3 build, LGPL 2.1+ / GPL 3\n• OkHttp (Square), Apache 2.0\n• jsoup (Jonathan Hedley), MIT\n• ZXing and ZXing Android Embedded, Apache 2.0\n• Kotlin and kotlinx.coroutines (JetBrains), Apache 2.0",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp))
            Text("BitTorrent is a way of moving files. What you move with it is your responsibility: only download and share things you have the right to.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
        }
    }
    if (showCrash) AlertDialog(onDismissRequest = { showCrash = false }, title = { Text("Last crash report") }, text = { Text(crash.take(6000), fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { Actions.copy(ctx, crash, "Crash report copied"); showCrash = false }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = { crashFile.delete(); crash = ""; showCrash = false }) { Text("Delete") } })
}

// ------------------------------------------------------------------------------------------- browser

/** A plain in-app browser. Tapping a magnet link or a .torrent link sends it straight to Torrents. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf((Ui.browserUrl ?: Prefs.getS("browserHome", "https://archive.org")).also { Ui.browserUrl = null }) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    fun go(input: String) {
        val t = input.trim(); if (t.isEmpty()) return
        val u = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else if (t.contains('.') && !t.contains(' ')) "https://$t" else "https://duckduckgo.com/?q=" + Uri.encode(t)
        web?.loadUrl(u)
    }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else nav.popBackStack() }
    BackPage("Browser", { nav.popBackStack() }, actions = {
        IconButton(onClick = { web?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
        IconButton(onClick = { web?.url?.let { Prefs.setS("browserHome", it); Ui.say("Set as the browser's start page") } }) { Icon(Icons.Filled.Home, "Set as start page") }
    }) {
        OutlinedTextField(address, { address = it }, singleLine = true, shape = RoundedCornerShape(22.dp), placeholder = { Text("Web address or search words") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go(address) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    private fun capture(u: String): Boolean {
                        if (u.startsWith("magnet:", true) || u.substringBefore('?').endsWith(".torrent", true)) { scope.launch { Ui.say(Actions.addAny(ctx, u, "Browser")) }; return true }
                        return !(u.startsWith("http://", true) || u.startsWith("https://", true))
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = capture(request.url.toString())
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { loading = true; if (url != null) address = url }
                    override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                }
                setDownloadListener { url, _, _, mime, _ -> if (mime == "application/x-bittorrent" || url.substringBefore('?').endsWith(".torrent", true)) scope.launch { Ui.say(Actions.addAny(ctx, url, "Browser")) } else Ui.say("Swarm's browser only picks up torrent links") }
                loadUrl(address)
                web = this
            }
        }, onRelease = { it.destroy() })
    }
}
