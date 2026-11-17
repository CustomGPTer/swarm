@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package uk.jj.swarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.swarm.Actions
import uk.jj.swarm.Fmt
import uk.jj.swarm.Kind
import uk.jj.swarm.Ui
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.engine.PeerRow
import uk.jj.swarm.engine.TFile
import uk.jj.swarm.engine.TItem
import uk.jj.swarm.engine.TState
import uk.jj.swarm.search.Safety
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetailScreen(hash: String, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by Engine.torrents.collectAsState()
    val t = all.firstOrNull { it.hash == hash }
    var tab by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    var limits by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<TFile>>(emptyList()) }
    var peers by remember { mutableStateOf<List<PeerRow>>(emptyList()) }
    var trackers by remember { mutableStateOf<List<String>>(emptyList()) }
    var pieces by remember { mutableStateOf(FloatArray(0)) }
    var history by remember { mutableStateOf<List<Int>>(emptyList()) }

    LaunchedEffect(hash, tab) {
        while (true) {
            withContext(Dispatchers.IO) {
                when (tab) {
                    0 -> { pieces = Engine.pieceMap(hash, 160); history = Engine.history(hash) }
                    1 -> files = Engine.files(hash)
                    2 -> peers = Engine.peers(hash)
                    else -> trackers = Engine.trackers(hash)
                }
            }
            delay(if (tab == 0) 1500 else 2500)
        }
    }

    if (t == null) { BackPage("Torrent", { nav.popBackStack() }) { EmptyState("This torrent has been removed.", "") }; return }

    BackPage(t.name, { nav.popBackStack() }, actions = {
        IconButton(onClick = { if (t.state == TState.PAUSED || t.state == TState.COMPLETE) Engine.resume(hash) else Engine.pause(hash) }) {
            Icon(if (t.state == TState.PAUSED || t.state == TState.COMPLETE) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Pause or resume")
        }
        IconButton(onClick = { Actions.share(ctx, Engine.magnetOf(hash)) }) { Icon(Icons.Filled.Share, "Share") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Copy magnet link") }, { menu = false; Actions.copy(ctx, Engine.magnetOf(hash), "Magnet link copied") })
                DropdownMenuItem({ Text("Show QR code") }, { menu = false; qr = true })
                DropdownMenuItem({ Text(if (t.sequential) "Download in any order" else "Download in order (for playing)") }, { menu = false; Engine.setSequential(hash, !t.sequential) })
                DropdownMenuItem({ Text("Speed limits") }, { menu = false; limits = true })
                DropdownMenuItem({ Text("Move up the queue") }, { menu = false; Engine.queueUp(hash) })
                DropdownMenuItem({ Text("Move down the queue") }, { menu = false; Engine.queueDown(hash) })
                DropdownMenuItem({ Text("Ask trackers again") }, { menu = false; Engine.reannounce(hash); Ui.say("Asking trackers and DHT for more peers") })
                DropdownMenuItem({ Text("Re-check files") }, { menu = false; Engine.recheck(hash) })
                DropdownMenuItem({ Text("Delete") }, { menu = false; del = true })
            }
        }
    }) {
        SubTabs(listOf("Info", "Files", "Peers", "Trackers"), tab) { tab = it }
        when (tab) {
            0 -> InfoTab(t, pieces, history, opening) {
                // One tap, one player. The button shows it has been pressed, and looking up the file does not hold up the screen.
                if (!opening) {
                    opening = true
                    scope.launch {
                        try {
                            val target = withContext(Dispatchers.IO) {
                                val f = files.ifEmpty { Engine.files(hash) }
                                val v = f.filter { Kind.isVideo(it.path) }.maxByOrNull { it.size }
                                if (v == null) null else Engine.filePath(hash, v.index)?.let { it to v.index }
                            }
                            if (target == null) Ui.say("No video file found in this torrent yet") else Actions.open(ctx, target.first, hash, target.second)
                            delay(2000)
                        } finally { opening = false }
                    }
                }
            }
            1 -> FilesTab(hash, files) { files = Engine.files(hash) }
            2 -> if (peers.isEmpty()) EmptyState("No peers connected right now.", "") else LazyColumn(Modifier.weight(1f)) {
                items(peers) { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.ip, fontSize = 14.sp)
                            Text(p.client.ifBlank { "Unknown client" } + "  ·  has ${(p.progress * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) { Text("↓ ${Fmt.speed(p.dl.toLong())}", fontSize = 12.sp); Text("↑ ${Fmt.speed(p.ul.toLong())}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            else -> TrackersTab(hash, trackers)
        }
    }

    if (del) DeleteDialog(1, { del = false }) { f -> del = false; scope.launch(Dispatchers.IO) { Engine.remove(hash, f) }; nav.popBackStack() }
    if (qr) QrDialog(Engine.magnetOf(hash)) { qr = false }
    if (limits) {
        val cur = remember { Engine.limits(hash) }
        var d by remember { mutableStateOf(if (cur.first > 0) cur.first.toString() else "") }
        var u by remember { mutableStateOf(if (cur.second > 0) cur.second.toString() else "") }
        AlertDialog(onDismissRequest = { limits = false }, title = { Text("Speed limits for this torrent") }, text = {
            Column {
                Text("In KB per second. Leave empty for no limit.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(d, { d = it.filter(Char::isDigit) }, label = { Text("Download") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(u, { u = it.filter(Char::isDigit) }, label = { Text("Upload") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
            }
        }, confirmButton = { TextButton(onClick = { Engine.setLimits(hash, d.toIntOrNull() ?: 0, u.toIntOrNull() ?: 0); limits = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { limits = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ColumnScope.InfoTab(t: TItem, pieces: FloatArray, history: List<Int>, opening: Boolean, onPlay: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${(t.progress * 100).toInt()}%", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = primary)
                Text(stateLine(t), fontSize = 14.sp, color = if (t.state == TState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onPlay, enabled = !opening, shape = RoundedCornerShape(22.dp)) {
                if (opening) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Opening…") }
                else { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (t.completed) "Play" else "Play now") }
            }
        }
        LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(6.dp).clip(RoundedCornerShape(3.dp)), trackColor = track, gapSize = 0.dp, drawStopIndicator = {})

        Text("Download speed, last minute", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 20.dp, vertical = 6.dp)) {
            val max = (history.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
            drawRect(track.copy(alpha = 0.5f), size = size)
            if (history.size > 1) {
                val p = Path(); val fill = Path()
                val step = size.width / 59f
                val x0 = size.width - (history.size - 1) * step
                history.forEachIndexed { i, v ->
                    val x = x0 + i * step; val y = size.height - (v / max) * (size.height - 4f) - 2f
                    if (i == 0) { p.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { p.lineTo(x, y); fill.lineTo(x, y) }
                }
                fill.lineTo(size.width, size.height); fill.close()
                drawPath(fill, primary.copy(alpha = 0.15f)); drawPath(p, primary, style = Stroke(3f))
            }
        }

        Labeled("Down / up", "↓ ${Fmt.speed(t.dl.toLong())}   ↑ ${Fmt.speed(t.ul.toLong())}")
        Labeled("Size", if (t.hasMeta) "${Fmt.size(t.done)} of ${Fmt.size(t.total)}" else "Not known yet")
        Labeled("Connected", "${t.seeds} seeders, ${maxOf(0, t.peers - t.seeds)} others")
        if (t.swarmSeeds >= 0 && (t.swarmSeeds > 0 || t.swarmPeers > 0)) Labeled("In the swarm", "${t.swarmSeeds} seeders, ${t.swarmPeers} leechers (tracker count)")
        Labeled("Uploaded", "${Fmt.size(t.uploaded)}  (ratio ${"%.2f".format(t.ratio)})")
        Labeled("Added", SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(t.added)))
        Labeled("Saved in", t.savePath.replace("/storage/emulated/0/", "Phone storage/"))
        Labeled("Order", if (t.sequential) "In order, so it can play while downloading" else "Any order (fastest)")
        Labeled("Info-hash", t.hash)
        if (t.error != null) Labeled("Error", t.error)

        if (pieces.isNotEmpty()) {
            Text("Pieces", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 14.dp))
            Canvas(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 20.dp, vertical = 6.dp)) {
                val w = size.width / pieces.size
                drawRect(track, size = size)
                pieces.forEachIndexed { i, v -> if (v > 0f) drawRect(primary.copy(alpha = 0.25f + 0.75f * v), topLeft = Offset(i * w, 0f), size = Size(w + 0.5f, size.height)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.FilesTab(hash: String, files: List<TFile>, refresh: () -> Unit) {
    val ctx = LocalContext.current
    if (files.isEmpty()) { EmptyState("The file list appears once the torrent's", "details have been found."); return }
    val risky = files.any { Safety.refuseOpen(it.path) }
    if (risky) Banner("This torrent contains program files. Swarm will not open them.", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    Text("Tap a file to open it here. Press and hold to open it in another app. Untick a file to skip it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    LazyColumn(Modifier.weight(1f)) {
        items(files, key = { it.index }) { f ->
            val wanted = f.priority != 0
            val name = f.path.substringAfterLast('/')
            val folder = f.path.substringBeforeLast('/', "")
            Row(Modifier.fillMaxWidth().combinedClickable(
                onClick = { Engine.filePath(hash, f.index)?.let { Actions.open(ctx, it, hash, f.index) } },
                onLongClick = { Engine.filePath(hash, f.index)?.let { file -> if (Safety.refuseOpen(file.name) && uk.jj.swarm.Prefs.safety) Ui.say("Swarm won't open program files") else if (file.exists()) Actions.openExternal(ctx, file) else Ui.say("This file has not downloaded yet") } },
            ).padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(wanted, { Engine.setFilePriority(hash, f.index, it); refresh() })
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (Safety.refuseOpen(name)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (folder.isNotBlank()) Text(folder, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val pct = if (f.size > 0) (f.done * 100 / f.size).toInt() else 100
                    Text("${Fmt.size(f.size)}  ·  " + if (!wanted) "skipped" else if (pct >= 100) "done" else "$pct%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (wanted && f.done < f.size) IconButton(onClick = { Engine.setFilePriority(hash, f.index, true, f.priority != 7); refresh() }) {
                    Icon(Icons.Filled.PriorityHigh, "Download this file first", tint = if (f.priority == 7) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
                Icon(when { Kind.isVideo(name) -> Icons.Filled.Movie; Kind.isAudio(name) -> Icons.Filled.MusicNote; Safety.refuseOpen(name) -> Icons.Filled.Warning; else -> Icons.Filled.Description }, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ColumnScope.TrackersTab(hash: String, trackers: List<String>) {
    var add by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${trackers.size} tracker${if (trackers.size == 1) "" else "s"}. DHT and peer exchange are also used.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = { add = true }) { Text("Add") }
    }
    LazyColumn(Modifier.weight(1f)) { items(trackers) { Text(it, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp)) } }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("Add a tracker") }, text = { OutlinedTextField(url, { url = it }, placeholder = { Text("udp://…/announce") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) Engine.addTracker(hash, url); url = ""; add = false }) { Text("Add") } }, dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } })
}
