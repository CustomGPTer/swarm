@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.swarm.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.swarm.Actions
import uk.jj.swarm.Db
import uk.jj.swarm.DhtRow
import uk.jj.swarm.Fmt
import uk.jj.swarm.Magnet
import uk.jj.swarm.Prefs
import uk.jj.swarm.Ui
import uk.jj.swarm.engine.Crawler
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.engine.Scrape
import uk.jj.swarm.search.Hit
import uk.jj.swarm.search.Safety
import uk.jj.swarm.search.Search

val Green = Color(0xFF2E9E5B)
val Red = Color(0xFFD64545)

/** Search results live here so they survive switching tabs. */
object SearchState {
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<Hit>>(emptyList())
    var live by mutableStateOf<Map<String, Scrape.SL>>(emptyMap())
    var loading by mutableStateOf(false)
    var progress by mutableStateOf("")
    var searched by mutableStateOf(false)
    var sort by mutableIntStateOf(0)       // 0 seeders, 1 newest, 2 biggest, 3 smallest
    var cat by mutableStateOf("All")
    var files: List<Pair<String, Long>>? = null   // file list already known for the result being viewed
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun run(ctx: android.content.Context, q: String) {
        job?.cancel()
        query = q; searched = true; loading = true; hits = emptyList(); live = emptyMap(); progress = ""
        val app = ctx.applicationContext
        job = scope.launch {
            if (q.isNotBlank()) withContext(Dispatchers.IO) { Db.get(app).addHistory(q.trim()) }
            val all = Search.all(app, q) { partial, done, total -> hits = partial; progress = "$done of $total sources answered" }
            hits = all; loading = false
            live = Search.live(all)
        }
    }

    fun seeders(h: Hit): Int = h.hash?.let { live[it]?.seeders }?.let { maxOf(it, h.seeders) } ?: h.seeders
    fun leechers(h: Hit): Int = h.hash?.let { live[it]?.leechers }?.let { maxOf(it, h.leechers) } ?: h.leechers
}

@Composable
fun SearchScreen(nav: NavHostController, openMenu: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = SearchState
    var text by remember { mutableStateOf(s.query) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var sourceCount by remember { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(s.sort, s.cat, s.query) { listState.scrollToItem(0) }
    LaunchedEffect(s.searched) { withContext(Dispatchers.IO) { history = Db.get(ctx).history(); sourceCount = Search.providers(ctx).size } }

    val shown = remember(s.hits, s.live, s.sort, s.cat) {
        s.hits.filter { s.cat == "All" || Search.categoryOf(it) == s.cat }.let { l ->
            when (s.sort) { 1 -> l.sortedByDescending { it.published }; 2 -> l.sortedByDescending { it.size }; 3 -> l.sortedBy { if (it.size < 0) Long.MAX_VALUE else it.size }; else -> l.sortedByDescending { s.seeders(it) } }
        }
    }

    Page("Search", header = {
        SearchHeader(text, { text = it }, "Search your sources", null, { s.run(ctx, text) }, leading = { IconButton(onClick = openMenu) { Icon(Icons.Filled.Menu, "Menu") } })
    }) {
        if (!s.searched) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { nav.navigate("sources") }.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Dns, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("$sourceCount source${if (sourceCount == 1) "" else "s"} switched on", fontWeight = FontWeight.Medium)
                        Text("Add a Jackett or Torznab address, or any website address", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { text = ""; s.run(ctx, "") }, label = { Text("Latest from your sources") }, leadingIcon = { Icon(Icons.Filled.NewReleases, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { Ui.tab.value = 2 }, label = { Text("Search the DHT index") }, leadingIcon = { Icon(Icons.Filled.Hub, null, Modifier.size(18.dp)) })
                }
                if (history.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { Db.get(ctx).clearHistory(); history = emptyList() } }) { Text("Clear") }
                    }
                    history.forEach { h ->
                        Row(Modifier.fillMaxWidth().clickable { text = h; s.run(ctx, h) }.padding(horizontal = 22.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(14.dp)); Text(h, fontSize = 15.sp)
                        }
                    }
                } else EmptyState("Type a few words and press search.", "Results show seeders, leechers, size and age.")
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in listOf("All", "Video", "Audio", "Books", "Software", "Other")) FilterChip(s.cat == c, { s.cat = c }, { Text(c) })
            }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.loading) "Searching… ${s.progress}" else "${shown.size} result${if (shown.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (s.query.isNotBlank()) IconButton(onClick = { scope.launch(Dispatchers.IO) { val db = Db.get(ctx); if (db.watches().none { it.query.equals(s.query.trim(), true) }) db.addWatch(s.query.trim()); Ui.say("Watching \"${s.query.trim()}\". You'll get a notification when something new appears.") } }) { Icon(Icons.Filled.NotificationAdd, "Watch this search") }
                Box {
                    TextButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(listOf("Seeders", "Newest", "Biggest", "Smallest")[s.sort]) }
                    DropdownMenu(sortMenu, { sortMenu = false }) { listOf("Most seeders", "Newest first", "Biggest first", "Smallest first").forEachIndexed { i, l -> DropdownMenuItem({ Text(l) }, { s.sort = i; sortMenu = false }) } }
                }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(2.dp))
            if (shown.isEmpty() && !s.loading) EmptyState(if (sourceCount == 0) "No search sources are switched on." else "Nothing found.", if (sourceCount == 0) "Add one in Search sources." else "Try fewer words, or the DHT tab.", if (sourceCount == 0) "Search sources" else null) { nav.navigate("sources") }
            else LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown) { h ->
                    HitRow(h.title, h.source, h.size, s.seeders(h), s.leechers(h), h.published, h.note, h.hash?.let { Engine.has(it) } == true,
                        onClick = { Ui.hit = h; s.files = null; nav.navigate("result") },
                        onDownload = { scope.launch { Ui.say(Actions.addHit(ctx, h)) } })
                }
            }
        }
    }
}

@Composable
fun HitRow(title: String, source: String, size: Long, seeders: Int, leechers: Int, published: Long, note: String, added: Boolean, ageLabel: String = "", onClick: () -> Unit, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 22.dp, end = 10.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HealthDot(if (seeders < 0 && note.startsWith("Web seeded")) 99 else seeders)
                if (seeders >= 0) Text("▲ $seeders", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (leechers >= 0) Text("▼ $leechers", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (seeders < 0 && leechers < 0) Text(if (note.isNotBlank()) note.substringBefore(':') else "Seeders not known", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.size(size), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((ageLabel + " " + Fmt.age(published)).trim(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(source, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
        }
        Box(Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).clickable(enabled = !added, onClick = onDownload), contentAlignment = Alignment.Center) {
            Icon(if (added) Icons.Filled.Check else Icons.Filled.Download, if (added) "Already added" else "Download", tint = if (added) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
        }
    }
}

/** Everything known about one search result, plus the file list fetched from the swarm. */
@Composable
fun ResultScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val h = Ui.hit
    if (h == null) { BackPage("Result", { nav.popBackStack() }) { EmptyState("Nothing to show.", "") }; return }
    var files by remember { mutableStateOf(SearchState.files) }
    var peeking by remember { mutableStateOf(false) }
    var peekFailed by remember { mutableStateOf(false) }
    var dhtPeers by remember { mutableIntStateOf(-2) }
    var sl by remember { mutableStateOf(h.hash?.let { SearchState.live[it] }) }
    var qr by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(h.hash?.let { Engine.has(it) } == true) }
    val magnet = h.magnet ?: h.hash?.let { Magnet.build(it, h.title) }

    LaunchedEffect(h.key) {
        if (magnet != null && h.hash != null && Engine.ready.value && Engine.blocked.value == null && Engine.networkAllowed()) {
            launch(Dispatchers.IO) { if (sl == null) sl = try { Scrape.scrape(listOf(h.hash), Prefs.trackerList())[h.hash] } catch (e: Exception) { null } }
            launch(Dispatchers.IO) { dhtPeers = Engine.dhtPeers(h.hash, 12) }
            if (files == null) {
                peeking = true
                val meta = withContext(Dispatchers.IO) { Engine.peek(magnet, 35, withTrackers = true) }
                peeking = false
                if (meta != null) files = meta.files else peekFailed = true
            }
        }
    }
    val seeders = maxOf(h.seeders, sl?.seeders ?: -1); val leechers = maxOf(h.leechers, sl?.leechers ?: -1)
    val size = if (h.size > 0) h.size else files?.sumOf { it.second } ?: -1
    val risk = if (Prefs.safety) Safety.assess(h.title, size, seeders, files) else Safety.Risk(0, emptyList())

    BackPage("Details", { nav.popBackStack() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(h.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column { Text(if (seeders >= 0) "$seeders" else "–", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Seeders", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column { Text(if (leechers >= 0) "$leechers" else "–", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Leechers", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column { Text(Fmt.size(size), fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Size", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column { Text(Fmt.age(h.published).removeSuffix(" ago"), fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(if (h.source == "DHT") "Since first seen" else "Age", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (risk.level > 0) Column(Modifier.padding(top = 12.dp)) {
                risk.reasons.forEach { Banner((if (risk.level == 2) "Warning: " else "Check: ") + it, if (risk.level == 2) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer, if (risk.level == 2) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer) }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { val m = Actions.addHit(ctx, h); Ui.say(m); if (m.startsWith("Added") || m.startsWith("Already")) added = true } }, enabled = !added, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
                    Icon(if (added) Icons.Filled.Check else Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text(if (added) "In Torrents" else "Download")
                }
                if (magnet != null) FilledTonalIconButton(onClick = { Actions.copy(ctx, magnet, "Magnet link copied") }) { Icon(Icons.Filled.ContentCopy, "Copy magnet link") }
                if (magnet != null) FilledTonalIconButton(onClick = { Actions.share(ctx, magnet) }) { Icon(Icons.Filled.Share, "Share") }
                if (magnet != null) FilledTonalIconButton(onClick = { qr = true }) { Icon(Icons.Filled.QrCode2, "QR code") }
            }
            Labeled("Source", h.source)
            if (h.category.isNotBlank() || Search.categoryOf(h) != "Other") Labeled("Type", Search.categoryOf(h))
            if (h.published > 0) Labeled(if (h.source == "DHT") "First seen" else "Published", java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(h.published)))
            if (h.note.isNotBlank()) Labeled("Note", h.note)
            if (sl != null) Labeled("Finished", "${sl!!.completed} downloads counted by trackers")
            if (dhtPeers >= 0) Labeled("DHT peers", "$dhtPeers found just now")
            if (h.hash != null) Labeled("Info-hash", h.hash)
            if (h.page != null) Row(Modifier.fillMaxWidth().clickable { Ui.browserUrl = h.page; nav.navigate("browser") }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Web page", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(118.dp)); Text(h.page, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            Text("Files" + (files?.let { " (${it.size})" } ?: if (h.files > 0) " (${h.files})" else ""), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            when {
                files != null -> files!!.take(200).forEach { (p, sz) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(p, fontSize = 13.sp, modifier = Modifier.weight(1f), color = if (Safety.refuseOpen(p)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(Fmt.size(sz), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                peeking -> Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Asking the swarm for the file list…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                h.pageOnly -> Text("This source keeps the download link on the result's own page. Tap Download and Swarm fetches it, or opens the page for you to tap the link.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                peekFailed -> Text("No peer answered in time, so the file list is not known. It will appear after you download.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                else -> Text("The file list is fetched from other peers. It needs a connection that Swarm is allowed to use.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
    if (qr && magnet != null) QrDialog(magnet) { qr = false }
}

@Composable
fun DhtScreen(nav: NavHostController, openMenu: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by Crawler.stats.collectAsState()
    val running by Crawler.running.collectAsState()
    val blocked by Engine.blocked.collectAsState()
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<DhtRow>>(emptyList()) }
    var mode by remember { mutableIntStateOf(Prefs.crawlMode) }
    var count by remember { mutableLongStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(sort, query) { listState.scrollToItem(0) }

    LaunchedEffect(query, sort, stats.indexed) {
        withContext(Dispatchers.IO) { val db = Db.get(ctx); rows = db.dhtSearch(query, sort, 200); count = db.dhtCount() }
    }

    Page("DHT", header = {
        SearchHeader(text, { text = it; if (it.isBlank()) query = "" }, "Search the DHT index", null, { query = text }, leading = { IconButton(onClick = openMenu) { Icon(Icons.Filled.Menu, "Menu") } })
    }) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("%,d torrent%s in your index".format(count, if (count == 1L) "" else "s"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val status = when {
                        mode == 0 -> "Crawler is switched off"
                        blocked != null -> "Crawler waiting: $blocked"
                        !Engine.networkAllowed() -> "Crawler waiting for an allowed connection"
                        running && Engine.streaming.get() > 0 -> "Crawler paused while a video is playing"
                        running && !Engine.ready.value -> "Crawler starting…"
                        running -> "Crawling · ${stats.nodes} DHT nodes · ${stats.added} added this session · ${stats.queued} queued"
                        else -> "Crawler idle"
                    }
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("What is this?", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp).clickable { Ui.helpTopic = "dht"; nav.navigate("help") })
                }
                Switch(mode != 0, { on -> mode = if (on) 1 else 0; Prefs.setI("crawlMode", mode); Crawler.update() })
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Newest", "Seeders", "Biggest").forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, { Text(l) }) }
            Spacer(Modifier.weight(1f))
            Text(if (query.isBlank()) listOf("Newly discovered", "Most seeders", "Biggest")[sort] else "${rows.size} match${if (rows.size == 1) "" else "es"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
        }
        if (rows.isEmpty()) EmptyState(if (query.isBlank()) "Your DHT index is empty so far." else "Nothing in your index matches.", if (query.isBlank()) "It fills while Swarm is open, and overnight on charge and Wi-Fi." else "The index only holds what the crawler has found so far.")
        else LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            items(rows) { r ->
                val hit = Hit("DHT", r.name, Magnet.build(r.hash, r.name), null, r.hash, r.size, r.seeders, r.leechers, r.firstSeen, null, files = r.nfiles)
                HitRow(r.name, "${r.nfiles} file${if (r.nfiles == 1) "" else "s"}", r.size, r.seeders, r.leechers, r.firstSeen, "", Engine.has(r.hash), "seen",
                    onClick = {
                        Ui.hit = hit
                        SearchState.files = r.files.lines().filter { it.isNotBlank() }.map { l -> l.substringBefore('\t') to (l.substringAfter('\t', "0").toLongOrNull() ?: 0L) }.takeIf { it.isNotEmpty() && r.nfiles <= 40 }
                        nav.navigate("result")
                    },
                    onDownload = { scope.launch { Ui.say(Actions.addHit(ctx, hit)) } })
            }
        }
    }
}
