@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package uk.jj.swarm.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import uk.jj.swarm.Actions
import uk.jj.swarm.Fmt
import uk.jj.swarm.Ui
import uk.jj.swarm.askAllFiles
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.engine.TItem
import uk.jj.swarm.engine.TState
import uk.jj.swarm.hasAllFiles

@Composable
fun TorrentsScreen(nav: NavHostController, openMenu: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by Engine.torrents.collectAsState()
    val blocked by Engine.blocked.collectAsState()
    val vpnWarn by Engine.vpnWarn.collectAsState()
    val ready by Engine.ready.collectAsState()
    var sub by remember { mutableIntStateOf(0) }
    var filtering by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var addOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var fallbackDir by remember { mutableStateOf(false) }
    val update by uk.jj.swarm.Updater.available.collectAsState()
    LaunchedEffect(Unit) { try { uk.jj.swarm.Updater.check() } catch (e: Exception) { } }
    LaunchedEffect(ready) { if (ready) fallbackDir = Engine.usingFallbackDir() && !hasAllFiles() }

    val downloading = all.filter { !it.completed }
    val complete = all.filter { it.completed }
    val shown = (when (sub) { 1 -> downloading; 2 -> complete; else -> all }).filter { filter.isBlank() || it.name.contains(filter.trim(), true) }

    BackHandler(enabled = selecting || filtering) { if (selecting) { selecting = false; selected.clear() } else { filtering = false; filter = "" } }

    Page(
        title = if (selecting) "${selected.size} Torrent${if (selected.size == 1) "" else "s"}" else "Torrents",
        nav = if (selecting) Icons.Filled.Close else Icons.Filled.Menu,
        onNav = { if (selecting) { selecting = false; selected.clear() } else openMenu() },
        actions = {
            if (selecting) {
                TextButton(onClick = { if (selected.size == shown.size) selected.clear() else { selected.clear(); selected.addAll(shown.map { it.hash }) } }) { Text(if (selected.size == shown.size && shown.isNotEmpty()) "Cancel All" else "Select All") }
            } else {
                IconButton(onClick = { filtering = true }) { Icon(Icons.Filled.Search, "Filter torrents") }
                IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.AddLink, "Add torrent") }
                IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Checklist, "Select") }
            }
        },
        header = if (filtering && !selecting) ({ SearchHeader(filter, { filter = it }, "Filter your torrents", { filtering = false; filter = "" }, {}) }) else null,
    ) {
        SubTabs(listOf("All", "Downloading", "Complete"), sub) { sub = it }
        if (blocked != null) Banner(blocked!!, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        else if (vpnWarn && downloading.isNotEmpty()) Banner("No VPN is on. Other peers can see your IP address.", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        if (update != null) Banner("Swarm ${update!!.first} is ready to download.", action = "Get it") { try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(update!!.second))) } catch (e: Exception) { } }
        if (fallbackDir) Banner("Saving inside the app's own folder because file access is off.", action = "Allow") { askAllFiles(ctx) }

        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (sub) { 1 -> "Downloading (${downloading.size})"; 2 -> "Complete (${complete.size})"; else -> "Torrents (${all.size})" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (sub != 2 && downloading.isNotEmpty()) {
                val anyRunning = downloading.any { it.state != TState.PAUSED }
                TextButton(onClick = { if (anyRunning) Engine.pauseAll() else Engine.resumeAll() }) { Text(if (anyRunning) "Pause All" else "Resume All") }
            }
        }

        if (shown.isEmpty()) {
            if (!ready) Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else EmptyState("Hi there,", if (filter.isNotBlank()) "Nothing matches that filter." else "Looks like you need to download some content.", if (filter.isBlank()) "Start Downloading" else null) { Ui.tab.value = 1 }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.hash }) { t ->
                TorrentRow(t, selecting, t.hash in selected,
                    onClick = { if (selecting) { if (t.hash in selected) selected.remove(t.hash) else selected.add(t.hash) } else nav.navigate("detail/${t.hash}") },
                    onLong = { if (!selecting) { selecting = true; selected.clear(); selected.add(t.hash) } },
                    onToggle = { if (t.state == TState.PAUSED || (t.completed && t.state == TState.COMPLETE)) Engine.resume(t.hash) else Engine.pause(t.hash) })
            }
        }

        if (selecting) Row(Modifier.fillMaxWidth().height(58.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)).clickable(enabled = selected.isNotEmpty()) {
                Actions.share(ctx, selected.joinToString("\n\n") { Engine.magnetOf(it) })
            }, contentAlignment = Alignment.Center) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Share, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("Share", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) } }
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primary).clickable(enabled = selected.isNotEmpty()) { deleteOpen = true }, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text("Delete", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }

    if (addOpen) AddDialog(onDismiss = { addOpen = false }) { input -> addOpen = false; scope.launch { Ui.say(Actions.addAny(ctx, input, "Added by hand")) } }
    if (deleteOpen) DeleteDialog(selected.size, onNo = { deleteOpen = false }) { files ->
        deleteOpen = false
        val list = selected.toList(); selected.clear(); selecting = false
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { list.forEach { Engine.remove(it, files) } }
    }
}

@Composable
fun DeleteDialog(count: Int, onNo: () -> Unit, onYes: (Boolean) -> Unit) {
    var files by remember { mutableStateOf(false) }
    ChoiceDialog("Are you sure to delete $count torrent${if (count == 1) "" else "s"}?", "Think again", "Yes", onNo, { onYes(files) }) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("And downloaded files", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(files, { files = it })
        }
        Text(if (files) "The torrent and everything it downloaded will be deleted from this phone." else "Only the torrent is removed. Downloaded files stay on this phone.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
fun AddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val clip = remember { Actions.clipboardMagnet(ctx) }
    val pick = rememberLauncherForActivityResult(remember { ActivityResultContracts.OpenDocument() }) { uri -> if (uri != null) { onDismiss(); scope.launch { Ui.say(Actions.addUri(ctx, uri)) } } }
    val scan = rememberLauncherForActivityResult(remember { ScanContract() }) { r -> r.contents?.let { onAdd(it) } }
    ChoiceDialog("Add Torrent", "Cancel", "Add", onDismiss, { if (text.isNotBlank()) onAdd(text) }) {
        Text("Paste a magnet link, an info-hash, or the address of a .torrent file", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        OutlinedTextField(text, { text = it }, placeholder = { Text("magnet:?xt=… or https://…") }, singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth())
        if (clip != null && text.isBlank()) AssistChip(onClick = { text = clip }, label = { Text("Paste from clipboard") }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("File", maxLines = 1) }
            OutlinedButton(onClick = { scan.launch(ScanOptions().setOrientationLocked(true).setBeepEnabled(false).setPrompt("Point at a magnet QR code")) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scan QR", maxLines = 1) }
        }
    }
}

@Composable
fun TorrentTile(completed: Boolean, size: Int = 62) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (completed) 0.85f else 0.6f)), contentAlignment = Alignment.Center) {
        Icon(if (completed) Icons.Filled.DownloadDone else Icons.Filled.Hive, null, tint = Color.White, modifier = Modifier.size((size * 0.5f).dp))
    }
}

fun stateLine(t: TItem): String = when (t.state) {
    TState.META -> "Finding details…" + if (t.peers > 0) " (${t.peers} peers)" else ""
    TState.CHECKING -> "Checking files…"
    TState.DOWNLOADING -> (if (t.eta >= 0) Fmt.eta(t.eta) else "∞") + "  (${Fmt.speed(t.dl.toLong())})"
    TState.SEEDING -> "Seeding  ↑ ${Fmt.speed(t.ul.toLong())}  ratio ${"%.2f".format(t.ratio)}"
    TState.COMPLETE -> "Complete"
    TState.PAUSED -> "Paused"
    TState.QUEUED -> "Queued"
    TState.ERROR -> "Error: ${t.error ?: "unknown"}"
}

@Composable
private fun TorrentRow(t: TItem, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TorrentTile(t.completed)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(t.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Text(if (t.hasMeta) (if (t.completed) Fmt.size(t.total) else "${Fmt.size(t.done)} / ${Fmt.size(t.total)}") else "Size not known yet", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.padding(top = 2.dp)) {
                Text(stateLine(t), fontSize = 13.sp, color = if (t.state == TState.ERROR) MaterialTheme.colorScheme.error else if (t.state == TState.PAUSED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!t.completed) Text("${(t.progress * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!t.completed) LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)), trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), gapSize = 0.dp, drawStopIndicator = {})
        }
        if (selecting) Box(Modifier.size(30.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(20.dp)) }
        else Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            val paused = t.state == TState.PAUSED || t.state == TState.COMPLETE
            Icon(if (paused) (if (t.completed) Icons.Filled.Upload else Icons.Filled.ArrowDownward) else Icons.Filled.Pause, if (paused) "Resume" else "Pause", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

fun qrBitmap(text: String, size: Int = 640): Bitmap? = try { BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, size, size) } catch (e: Exception) { null }

@Composable
fun QrDialog(text: String, onClose: () -> Unit) {
    val bmp = remember(text) { qrBitmap(text) }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Close") } }, title = { Text("Scan to add this torrent") }, text = {
        if (bmp != null) Image(bmp.asImageBitmap(), "QR code", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)) else Text("This link is too long for a QR code.")
    })
}
