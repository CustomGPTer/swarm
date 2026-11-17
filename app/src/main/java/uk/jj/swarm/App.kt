package uk.jj.swarm

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.jj.swarm.engine.Crawler
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.engine.Notify
import uk.jj.swarm.play.PlayerActivity
import uk.jj.swarm.search.Hit
import uk.jj.swarm.search.Http
import uk.jj.swarm.search.Rss
import uk.jj.swarm.search.Safety
import uk.jj.swarm.search.Search
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notify.channels(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("Swarm ${BuildConfig.VERSION_NAME} · ${java.util.Date()}\nThread: ${t.name}\n\n$sw")
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
        Workers.schedule(this)
    }
}

/** Small pieces of UI state shared between screens. */
object Ui {
    val message = MutableStateFlow<String?>(null)     // shown once as a snackbar
    val tab = MutableStateFlow(0)                     // bottom tab: 0 Torrents, 1 Search, 2 DHT, 3 Video, 4 Music
    val openHash = MutableStateFlow<String?>(null)    // torrent to open from a notification
    val go = MutableStateFlow<String?>(null)          // screen to open ("back" goes back)
    @Volatile var browserUrl: String? = null          // page the browser should open next, instead of its start page
    @Volatile var hit: Hit? = null                    // search result being viewed
    fun say(s: String) { message.value = s }
}

object Actions {
    /** Adds whatever the user gave us: a magnet link, a bare info-hash, or a web address of a .torrent file. */
    suspend fun addAny(ctx: Context, input: String, source: String = ""): String = withContext(Dispatchers.IO) {
        val text = input.trim()
        Engine.start(ctx)
        waitReady()
        val magnet = Regex("magnet:\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE).find(text)?.value
        when {
            magnet != null -> if (Engine.addMagnet(magnet, source) != null) "Added to Torrents" else "That magnet link is not valid"
            Magnet.isHash(text) -> if (Engine.addMagnet(Magnet.build(text.lowercase(), null), source) != null) "Added to Torrents" else "That hash is not valid"
            text.startsWith("http://", true) || text.startsWith("https://", true) -> {
                val (m, bytes) = Http.resolve(text)
                when {
                    m != null -> if (Engine.addMagnet(m, source) != null) "Added to Torrents" else "That magnet link is not valid"
                    bytes != null -> if (Engine.addTorrentBytes(bytes, source) != null) "Added to Torrents" else "That is not a valid .torrent file"
                    else -> "No torrent found at that address"
                }
            }
            else -> "Paste a magnet link, an info-hash, or the address of a .torrent file"
        }
    }

    suspend fun addUri(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        Engine.start(ctx); waitReady()
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext "Could not read that file"
            if (Engine.addTorrentBytes(bytes, "File") != null) "Added to Torrents" else "That is not a valid .torrent file"
        } catch (e: Exception) { "Could not read that file" }
    }

    /** Prefers the .torrent file when a source offers one (it carries web seeds and trackers), otherwise the magnet. */
    suspend fun addHit(ctx: Context, h: Hit): String = withContext(Dispatchers.IO) {
        Engine.start(ctx); waitReady()
        if (h.hash != null && Engine.has(h.hash)) return@withContext "Already in Torrents"
        if (h.pageOnly) {
            // The listing gave no download link, so look on the result's own page. If the page only shows its
            // link after a tap, open it in Swarm's browser, which picks the link up when the user taps it.
            val full = try { uk.jj.swarm.search.SiteProvider.resolve(ctx, h) } catch (e: Exception) { null }
            if (full != null && !full.pageOnly) return@withContext addHit(ctx, full)
            Ui.browserUrl = h.page
            Ui.go.value = "browser"
            return@withContext "Tap the page's own download or magnet link and Swarm will pick it up"
        }
        if (h.torrentUrl != null) {
            val (m, bytes) = Http.resolve(h.torrentUrl)
            if (bytes != null && Engine.addTorrentBytes(bytes, h.source) != null) return@withContext "Added to Torrents"
            if (m != null && Engine.addMagnet(m, h.source) != null) return@withContext "Added to Torrents"
        }
        val mg = h.magnet ?: h.hash?.let { Magnet.build(it, h.title) }
        if (mg != null && Engine.addMagnet(mg, h.source) != null) "Added to Torrents" else "Could not get a download link from this source"
    }

    private suspend fun waitReady() { var n = 0; while (!Engine.ready.value && n++ < 100) delay(100) }

    fun copy(ctx: Context, text: String, label: String = "Copied") {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Swarm", text))
        Ui.say(label)
    }

    fun share(ctx: Context, text: String) {
        try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    fun clipboardMagnet(ctx: Context): String? = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        t?.takeIf { it.startsWith("magnet:", true) || Magnet.isHash(it) || (it.startsWith("http", true) && it.substringBefore('?').endsWith(".torrent", true)) }
    } catch (e: Exception) { null }

    /** Opens a downloaded file: built-in player for video, music player for audio, otherwise another app. */
    fun open(ctx: Context, file: File, hash: String? = null, index: Int = -1) {
        when {
            Safety.refuseOpen(file.name) && Prefs.safety -> Ui.say("Swarm won't open .${Kind.ext(file.name)} files. Program files from torrents are a common way to get malware.")
            Kind.isVideo(file.name) -> PlayerActivity.start(ctx, file, file.name, hash, index)
            !file.exists() -> Ui.say("This file has not downloaded yet")
            Kind.isAudio(file.name) -> uk.jj.swarm.play.Music.play(ctx, listOf(uk.jj.swarm.play.Track(file.nameWithoutExtension, "", "", file.absolutePath, 0)), 0)
            else -> openExternal(ctx, file)
        }
    }

    fun openExternal(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "uk.jj.swarm.files", file)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(Kind.ext(file.name)) ?: "*/*"
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    // ---- export / import (torrent list, sources, feeds, watchlist, settings)

    fun exportJson(ctx: Context): String {
        val db = Db.get(ctx)
        val o = JSONObject()
        o.put("app", "Swarm"); o.put("version", 1)
        o.put("settings", JSONObject(Prefs.all()))
        o.put("torrents", JSONArray(Engine.registry().map { Engine.magnetOf(it.hash) }))
        o.put("sources", JSONArray(db.sources().map { JSONObject().put("type", it.type).put("name", it.name).put("url", it.url).put("apikey", it.apikey) }))
        o.put("feeds", JSONArray(db.feeds().map { JSONObject().put("name", it.name).put("url", it.url).put("filter", it.filter).put("auto", it.auto) }))
        o.put("watch", JSONArray(db.watches().map { it.query }))
        return o.toString(2)
    }

    fun importJson(ctx: Context, json: String): String {
        return try {
            val o = JSONObject(json)
            if (o.optString("app") != "Swarm") return "That is not a Swarm export file"
            val db = Db.get(ctx)
            o.optJSONObject("settings")?.let { s -> Prefs.importAll(s.keys().asSequence().associateWith { k -> s.get(k) }) }
            val have = db.sources().map { it.url }.toSet()
            o.optJSONArray("sources")?.let { a -> for (i in 0 until a.length()) { val s = a.getJSONObject(i); if (s.getString("url") !in have) db.addSource(s.getString("type"), s.getString("name"), s.getString("url"), s.optString("apikey")) } }
            val feeds = db.feeds().map { it.url }.toSet()
            o.optJSONArray("feeds")?.let { a -> for (i in 0 until a.length()) { val s = a.getJSONObject(i); if (s.getString("url") !in feeds) db.addFeed(s.getString("name"), s.getString("url"), s.optString("filter"), s.optBoolean("auto")) } }
            val w = db.watches().map { it.query }.toSet()
            o.optJSONArray("watch")?.let { a -> for (i in 0 until a.length()) if (a.getString(i) !in w) db.addWatch(a.getString(i)) }
            var n = 0
            o.optJSONArray("torrents")?.let { a -> for (i in 0 until a.length()) if (Engine.addMagnet(a.getString(i), "Import") != null) n++ }
            "Imported. $n torrents added."
        } catch (e: Exception) { "Could not read that file" }
    }
}

/** Looks at the latest GitHub release and tells the user when a newer version is there to download. */
object Updater {
    val available = MutableStateFlow<Pair<String, String>?>(null)   // version to download address

    fun newer(latest: String, current: String): Boolean {
        val a = latest.trim().trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.trim().trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    /** Returns version to address, or null. [json] is the reply from GitHub's "latest release" address. */
    fun parse(json: String, current: String): Pair<String, String>? = try {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        if (tag.isBlank() || !newer(tag, current)) null else {
            val assets = o.optJSONArray("assets")
            var url = o.optString("html_url")
            if (assets != null) for (i in 0 until assets.length()) { val u = assets.getJSONObject(i).optString("browser_download_url"); if (u.endsWith(".apk", true)) { url = u; break } }
            tag.trimStart('v', 'V') to url
        }
    } catch (e: Exception) { null }

    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        val repo = Prefs.getS("updateRepo", BuildConfig.UPDATE_REPO).trim()
        if (repo.isBlank()) return@withContext
        val last = Prefs.getS("updateChecked", "0").toLongOrNull() ?: 0
        if (!force && System.currentTimeMillis() - last < 12 * 3600_000L) return@withContext
        Prefs.setS("updateChecked", System.currentTimeMillis().toString())
        val body = Http.text("https://api.github.com/repos/$repo/releases/latest") ?: return@withContext
        available.value = parse(body, BuildConfig.VERSION_NAME)
    }
}

object Workers {
    fun schedule(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniquePeriodicWork("watch", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            wm.enqueueUniquePeriodicWork("crawl", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CrawlWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()).build())
        } catch (e: Exception) { }
    }
}

/** Runs only while the phone is charging on Wi-Fi: lets the DHT index grow overnight. */
class CrawlWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (Prefs.crawlMode != 1 && Prefs.crawlMode != 3) return Result.success()
        Engine.start(applicationContext)
        Crawler.want("worker", true)
        try { delay(9 * 60 * 1000L) } finally { Crawler.want("worker", false) }
        return Result.success()
    }
}

/** Hourly: re-runs saved searches and checks RSS feeds. */
class WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val db = Db.get(ctx)
        try {
            for (w in db.watches()) {
                val hits = Search.all(ctx, w.query) { _, _, _ -> }
                val seen = w.seen.split('\n').filter { it.isNotBlank() }.toMutableSet()
                val fresh = hits.filter { it.key !in seen }
                if (w.lastCheck > 0 && fresh.isNotEmpty()) Notify.alert(ctx, 5000 + w.id.toInt(), "${fresh.size} new for \"${w.query}\"", fresh.take(5).joinToString("\n") { it.title })
                seen.addAll(hits.map { it.key })
                db.updateWatch(w.id, seen.toList().takeLast(800).joinToString("\n"))
            }
            for (f in db.feeds()) {
                val xml = Http.text(f.url) ?: continue
                val items = Rss.parse(xml).filter { Rss.matches(f.filter, it.title) }
                val fresh = items.filter { !db.rssSeen(f.id.toString() + "|" + it.link) }
                if (f.lastCheck > 0 && fresh.isNotEmpty()) {
                    if (f.auto) {
                        var n = 0
                        for (it in fresh.take(10)) if (Actions.addAny(ctx, it.link, f.name).startsWith("Added")) n++
                        if (n > 0) Notify.alert(ctx, 6000 + f.id.toInt(), "${f.name}: $n new download${if (n == 1) "" else "s"} started", fresh.take(5).joinToString("\n") { it.title })
                    } else Notify.alert(ctx, 6000 + f.id.toInt(), "${f.name}: ${fresh.size} new", fresh.take(5).joinToString("\n") { it.title })
                }
                for (it in items) db.rssMark(f.id.toString() + "|" + it.link)
                db.touchFeed(f.id)
            }
        } catch (e: Exception) { }
        Result.success()
    }
}
