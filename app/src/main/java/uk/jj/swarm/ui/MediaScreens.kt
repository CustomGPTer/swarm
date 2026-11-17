@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.swarm.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.jj.swarm.Db
import uk.jj.swarm.Fmt
import uk.jj.swarm.play.Music
import uk.jj.swarm.play.PlayerActivity
import uk.jj.swarm.play.Track
import java.io.File

data class VideoItem(val id: Long, val name: String, val path: String, val size: Long, val duration: Long, val isNew: Boolean)

private fun granted(ctx: Context, video: Boolean): Boolean {
    val p = if (Build.VERSION.SDK_INT >= 33) (if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO) else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

private fun loadVideos(ctx: Context): List<VideoItem> {
    val out = ArrayList<VideoItem>()
    val db = Db.get(ctx)
    try {
        ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION),
            null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(2) ?: continue
                out += VideoItem(c.getLong(0), c.getString(1) ?: File(path).name, path, c.getLong(3), c.getLong(4), db.position(path) == 0L)
            }
        }
    } catch (e: Exception) { }
    return out
}

private fun loadTracks(ctx: Context): List<Track> {
    val out = ArrayList<Track>()
    try {
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME),
            null, null, MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE ASC")?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(3) ?: continue
                val artist = c.getString(1)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown"
                out += Track(c.getString(5) ?: c.getString(0) ?: File(path).name, artist, c.getString(2)?.takeIf { it.isNotBlank() } ?: "", path, c.getLong(4))
            }
        }
    } catch (e: Exception) { }
    return out
}

@Composable
private fun NeedPermission(what: String, onGrant: () -> Unit) {
    EmptyState("Swarm needs permission to list your $what.", "", "Allow", onGrant)
}

@Composable
private fun Thumb(id: Long) {
    val ctx = LocalContext.current
    var bmp by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) {
        bmp = withContext(Dispatchers.IO) {
            try { ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), Size(240, 240), null) } catch (e: Exception) { null }
        }
    }
    Box(Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.PlayCircle, null, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

@Composable
fun VideoScreen(openMenu: () -> Unit) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(granted(ctx, true)) }
    var items by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted(ctx, true); reload++ }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) PlayerActivity.startUri(ctx, uri) }
    LaunchedEffect(ok, reload) { if (ok) { items = withContext(Dispatchers.IO) { loadVideos(ctx) }; loaded = true } }
    BackHandler(enabled = filtering) { filtering = false; filter = "" }
    val shown = items.filter { filter.isBlank() || it.name.contains(filter.trim(), true) }

    Page("Video", Icons.Filled.Menu, openMenu, actions = {
        IconButton(onClick = { filtering = true }) { Icon(Icons.Filled.Search, "Filter videos") }
        IconButton(onClick = { pick.launch(arrayOf("video/*")) }) { Icon(Icons.Filled.FolderOpen, "Open a video file") }
    }, header = if (filtering) ({ SearchHeader(filter, { filter = it }, "Filter your videos", { filtering = false; filter = "" }, {}) }) else null) {
        when {
            !ok -> NeedPermission("videos") { ask.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) }
            loaded && shown.isEmpty() -> EmptyState("No videos here yet.", "Finished video downloads appear in this tab.")
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp)) {
                items(shown, key = { it.id }) { v ->
                    Row(Modifier.fillMaxWidth().clickable { PlayerActivity.start(ctx, File(v.path), v.name); reload++ }.padding(horizontal = 22.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Thumb(v.id)
                            if (v.isNew) Box(Modifier.clip(RoundedCornerShape(topStart = 12.dp, bottomEnd = 8.dp)).background(Color(0xFFE5484D)).padding(horizontal = 5.dp, vertical = 1.dp)) { Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(v.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                            Text(Fmt.size(v.size) + if (v.duration > 0) "  ·  " + Fmt.time(v.duration) else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Disc(size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Album, null, tint = Color.White, modifier = Modifier.size((size * 0.62f).dp))
    }
}

@Composable
fun MusicScreen(openMenu: () -> Unit) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(granted(ctx, false)) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var filtering by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<Pair<String, String>?>(null) }   // "artist" / "album" to its name
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted(ctx, false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { val p = Music.get(ctx); p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri)); p.prepare(); p.play() }
    }
    val state by Music.state.collectAsState()
    LaunchedEffect(ok) { if (ok) { tracks = withContext(Dispatchers.IO) { loadTracks(ctx) }; loaded = true } }
    BackHandler(enabled = filtering || group != null) { if (group != null) group = null else { filtering = false; filter = "" } }
    val base = tracks.filter { filter.isBlank() || it.title.contains(filter.trim(), true) || it.artist.contains(filter.trim(), true) || it.album.contains(filter.trim(), true) }

    Page(group?.second?.ifBlank { "Unknown" } ?: "Music", if (group != null) Icons.Filled.Close else Icons.Filled.Menu, { if (group != null) group = null else openMenu() }, actions = {
        IconButton(onClick = { filtering = true }) { Icon(Icons.Filled.Search, "Filter music") }
        IconButton(onClick = { pick.launch(arrayOf("audio/*")) }) { Icon(Icons.Filled.FolderOpen, "Open an audio file") }
    }, header = if (filtering && group == null) ({ SearchHeader(filter, { filter = it }, "Filter your music", { filtering = false; filter = "" }, {}) }) else null) {
        if (!ok) { NeedPermission("music") { ask.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) }; return@Page }
        val g = group
        if (g != null) {
            val list = tracks.filter { if (g.first == "artist") it.artist == g.second else it.album == g.second }
            SongList(list, state.title, Modifier.weight(1f)) { i -> Music.play(ctx, list, i) }
            return@Page
        }
        SubTabs(listOf("Songs", "Artists", "Albums"), tab) { tab = it }
        if (loaded && base.isEmpty()) { EmptyState("No music here yet.", "Finished audio downloads appear in this tab."); return@Page }
        when (tab) {
            0 -> SongList(base, state.title, Modifier.weight(1f)) { i -> Music.play(ctx, base, i) }
            1 -> LazyColumn(Modifier.weight(1f)) {
                val artists = base.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                items(artists.entries.toList(), key = { it.key }) { (name, list) ->
                    Row(Modifier.fillMaxWidth().clickable { group = "artist" to name }.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val albums = list.map { it.album }.distinct().size
                            Text("$albums album${if (albums == 1) "" else "s"} · ${list.size} track${if (list.size == 1) "" else "s"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            }
            else -> {
                val albums = base.groupBy { it.album to it.artist }.entries.toList()
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albums) { (k, _) ->
                        Column(Modifier.clickable { group = "album" to k.first }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Album, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.62f)) }
                            Text(k.first.ifBlank { "Unknown album" }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(k.second, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongList(list: List<Track>, playingTitle: String, modifier: Modifier, onPlay: (Int) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
        itemsIndexed(list) { i, t ->
            Row(Modifier.fillMaxWidth().clickable { onPlay(i) }.padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Disc(52)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(t.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (t.title == playingTitle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (t.title == playingTitle) FontWeight.Bold else FontWeight.Normal)
                    Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                if (t.duration > 0) Text(Fmt.time(t.duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
