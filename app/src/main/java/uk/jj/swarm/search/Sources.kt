package uk.jj.swarm.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uk.jj.swarm.Db
import uk.jj.swarm.Magnet
import uk.jj.swarm.Prefs
import uk.jj.swarm.SourceRow
import uk.jj.swarm.engine.Scrape
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private val noRedirect: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    data class Resp(val code: Int, val body: String?, val url: String)

    fun get(url: String): Resp = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").header("Accept-Language", "en-GB,en;q=0.9").build()).execute().use {
            Resp(it.code, it.body?.string(), it.request.url.toString())
        }
    } catch (e: Exception) { Resp(-1, null, url) }

    fun text(url: String): String? = get(url).let { if (it.code in 200..299) it.body else null }

    fun bytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    } catch (e: Exception) { null }

    /**
     * Follows a download link to its end. Returns a magnet link (some sources redirect to one) or the .torrent bytes.
     */
    fun resolve(url: String): Pair<String?, ByteArray?> {
        var u = url
        repeat(6) {
            if (u.startsWith("magnet:", true)) return u to null
            val r = try { noRedirect.newCall(Request.Builder().url(u).header("User-Agent", UA).build()).execute() } catch (e: Exception) { return null to null }
            r.use {
                if (it.code in 300..399) {
                    val loc = it.header("Location") ?: return null to null
                    u = if (loc.startsWith("magnet:", true)) loc else (it.request.url.resolve(loc)?.toString() ?: return null to null)
                } else {
                    if (!it.isSuccessful) return null to null
                    val b = it.body?.bytes() ?: return null to null
                    if (b.isNotEmpty() && b[0] == 'd'.code.toByte()) return null to b
                    val s = String(b, 0, minOf(b.size, 4000))
                    Regex("magnet:\\?xt=urn:btih:[^\"'<> ]+").find(s)?.let { m -> return m.value.replace("&amp;", "&") to null }
                    return null to null
                }
            }
        }
        return if (u.startsWith("magnet:", true)) u to null else null to null
    }
}

interface Provider {
    val name: String
    fun search(q: String): List<Hit>
}

/** Internet Archive: public-domain and freely licensed films, music, books and software. */
class ArchiveProvider : Provider {
    override val name = "Internet Archive"
    override fun search(q: String): List<Hit> {
        val query = (if (q.isBlank()) "" else "(${q.trim()}) AND ") + "format:\"Archive BitTorrent\""
        val sb = StringBuilder("https://archive.org/advancedsearch.php?q=").append(URLEncoder.encode(query, "UTF-8"))
        for (f in listOf("identifier", "title", "item_size", "publicdate", "btih", "mediatype")) sb.append("&fl%5B%5D=").append(f)
        sb.append(if (q.isBlank()) "&sort%5B%5D=publicdate+desc" else "&sort%5B%5D=downloads+desc").append("&rows=60&output=json")
        val body = Http.text(sb.toString()) ?: return emptyList()
        return parse(body)
    }

    companion object {
        fun parse(json: String): List<Hit> {
            val docs = try { JSONObject(json).getJSONObject("response").getJSONArray("docs") } catch (e: Exception) { return emptyList() }
            val out = ArrayList<Hit>()
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val id = d.optString("identifier"); if (id.isEmpty()) continue
                val title = str(d.opt("title")).ifBlank { id }
                val btih = str(d.opt("btih")).lowercase().takeIf { it.length == 40 }
                val cat = when (d.optString("mediatype")) { "movies" -> "Video"; "audio", "etree" -> "Audio"; "texts" -> "Books"; "software" -> "Software"; else -> "Other" }
                out += Hit(
                    "Internet Archive", title,
                    btih?.let { Magnet.build(it, title, listOf("http://bt1.archive.org:6969/announce", "http://bt2.archive.org:6969/announce")) },
                    "https://archive.org/download/$id/${id}_archive.torrent", btih, d.optLong("item_size", -1), -1, -1,
                    SiteReader.parseAge(d.optString("publicdate")), "https://archive.org/details/$id", cat, "Web seeded: always available",
                )
            }
            return out
        }

        private fun str(v: Any?): String = when (v) { null -> ""; is JSONArray -> if (v.length() > 0) v.optString(0) else ""; else -> v.toString() }
    }
}

/** Any source that speaks Torznab: Jackett, Prowlarr, or a site's own Torznab address. */
class TorznabProvider(private val row: SourceRow) : Provider {
    override val name = row.name
    override fun search(q: String): List<Hit> {
        val body = Http.text(url(row.url, row.apikey, q)) ?: return emptyList()
        return parse(body, row.name)
    }

    companion object {
        fun url(base: String, key: String, q: String): String {
            var b = base.trim()
            if (!b.contains("?")) {
                val path = b.substringAfter("://").substringAfter('/', "")
                if (!path.trimEnd('/').endsWith("api")) b = b.trimEnd('/') + "/api"
            }
            val sep = if (b.contains("?")) "&" else "?"
            val sb = StringBuilder(b).append(sep).append("t=search&limit=100")
            if (key.isNotBlank() && !b.contains("apikey=")) sb.append("&apikey=").append(URLEncoder.encode(key.trim(), "UTF-8"))
            if (q.isNotBlank()) sb.append("&q=").append(URLEncoder.encode(q.trim(), "UTF-8"))
            return sb.toString()
        }

        fun parse(xml: String, source: String): List<Hit> {
            val doc = try { Jsoup.parse(xml, "", Parser.xmlParser()) } catch (e: Exception) { return emptyList() }
            val out = ArrayList<Hit>()
            for (item in doc.select("item")) {
                val attrs = HashMap<String, String>()
                for (c in item.children()) if (c.tagName().endsWith(":attr") || c.tagName() == "attr") attrs[c.attr("name").lowercase()] = c.attr("value")
                val title = item.selectFirst("> title")?.text()?.trim().orEmpty(); if (title.isEmpty()) continue
                val link = item.selectFirst("> link")?.text()?.trim().orEmpty()
                val guid = item.selectFirst("> guid")?.text()?.trim().orEmpty()
                val enc = item.selectFirst("> enclosure")
                val encUrl = enc?.attr("url").orEmpty()
                val magnet = attrs["magneturl"]?.takeIf { it.startsWith("magnet:", true) } ?: listOf(link, guid, encUrl).firstOrNull { it.startsWith("magnet:", true) }
                val torrent = listOf(encUrl, link).firstOrNull { it.startsWith("http", true) }
                val hash = attrs["infohash"]?.lowercase()?.takeIf { it.length == 40 } ?: magnet?.let { Magnet.hash(it) }
                val size = item.selectFirst("> size")?.text()?.trim()?.toLongOrNull() ?: attrs["size"]?.toLongOrNull() ?: enc?.attr("length")?.toLongOrNull() ?: -1
                val seeders = attrs["seeders"]?.toIntOrNull() ?: -1
                val leechers = attrs["leechers"]?.toIntOrNull() ?: attrs["peers"]?.toIntOrNull()?.let { p -> if (seeders >= 0) maxOf(0, p - seeders) else p } ?: -1
                val date = item.selectFirst("> pubDate")?.text()?.let { SiteReader.parseRfc822(it) } ?: 0
                val page = item.selectFirst("> comments")?.text()?.trim()?.takeIf { it.startsWith("http") } ?: guid.takeIf { it.startsWith("http") && it != torrent }
                val cat = attrs["category"]?.toIntOrNull()?.let { c -> when (c / 1000) { 2, 5 -> "Video"; 3 -> "Audio"; 7, 8 -> "Books"; 1, 4 -> "Software"; else -> "Other" } } ?: ""
                out += Hit(source, title, magnet ?: hash?.let { Magnet.build(it, title) }, torrent, hash, size, seeders, leechers, date, page, cat, files = attrs["files"]?.toIntOrNull() ?: -1)
            }
            return out
        }
    }
}

/** An ordinary website address the user added. Read with the generic [SiteReader]. */
class SiteProvider(private val row: SourceRow, private val ctx: Context?) : Provider {
    override val name = row.name
    override fun search(q: String): List<Hit> {
        val url = row.url.replace("{query}", URLEncoder.encode(q.trim(), "UTF-8"))
        fun read(html: String): List<Hit> = SiteReader.parse(html, url, row.name).ifEmpty { SiteReader.listing(html, url, row.name) }
        val plain = Http.get(url)
        if (!SiteReader.looksBlocked(plain.code, plain.body)) {
            val hits = plain.body?.let { read(it) } ?: emptyList()
            if (hits.isNotEmpty() || ctx == null) return hits
        }
        // The site refused a plain request, or builds its results with scripts: load it the way a browser would.
        val html = ctx?.let { WebFetcher.html(it, url) { h -> read(h).isNotEmpty() } } ?: return emptyList()
        return read(html)
    }

    companion object {
        /** Finds the download link on one result's own page. Null when the page shows none without being tapped. */
        fun resolve(ctx: Context?, h: Hit): Hit? {
            val page = h.page ?: return null
            fun read(html: String) = SiteReader.detail(html, page, h.title, h.source)
            val plain = Http.get(page)
            if (!SiteReader.looksBlocked(plain.code, plain.body)) plain.body?.let { read(it) }?.let { return keep(h, it) }
            val html = ctx?.let { WebFetcher.html(it, page, 20) { x -> read(x) != null } } ?: return null
            return read(html)?.let { keep(h, it) }
        }

        private fun keep(from: Hit, d: Hit) = d.copy(size = if (from.size > 0) from.size else d.size, seeders = maxOf(from.seeders, d.seeders),
            leechers = maxOf(from.leechers, d.leechers), published = if (from.published > 0) from.published else d.published, category = from.category)
    }
}

object Search {
    /** Open catalogues that ship with the app: name to a one-line description. */
    val BUILT_IN = listOf(
        "Internet Archive" to "Public-domain and freely licensed films, music, books and software",
        "Linux Tracker" to "Linux and other open-source system downloads",
    )
    private val LINUX_TRACKER = SourceRow(-2, "site", "Linux Tracker", "https://linuxtracker.org/torrents/?q={query}", "", true)

    fun builtInEnabled(name: String) = Prefs.getB("src_" + name, true)

    fun providers(ctx: Context): List<Provider> {
        val out = ArrayList<Provider>()
        if (builtInEnabled("Internet Archive")) out += ArchiveProvider()
        if (builtInEnabled("Linux Tracker")) out += SiteProvider(LINUX_TRACKER, ctx)
        for (s in Db.get(ctx).sources()) if (s.enabled) out += if (s.type == "torznab") TorznabProvider(s) else SiteProvider(s, ctx)
        return out
    }

    /** Runs every enabled source at once. [onPartial] is called with the merged list each time one finishes. */
    suspend fun all(ctx: Context, q: String, onPartial: (List<Hit>, done: Int, total: Int) -> Unit): List<Hit> = coroutineScope {
        val ps = providers(ctx)
        val merged = LinkedHashMap<String, Hit>()
        var done = 0
        onPartial(emptyList(), 0, ps.size)
        ps.map { p ->
            async(Dispatchers.IO) {
                val hits = withTimeoutOrNull(70000) { try { p.search(q) } catch (e: Exception) { emptyList() } } ?: emptyList()
                synchronized(merged) {
                    for (h in hits) { val cur = merged[h.key]; if (cur == null || h.seeders > cur.seeders) merged[h.key] = h }
                    done++
                    onPartial(merged.values.toList(), done, ps.size)
                }
            }
        }.awaitAll()
        merged.values.toList()
    }

    /** Fills in live seeders / leechers from public trackers for hits that have a hash. */
    suspend fun live(hits: List<Hit>): Map<String, Scrape.SL> = withContext(Dispatchers.IO) {
        val hashes = hits.mapNotNull { it.hash }.distinct().take(140)
        if (hashes.isEmpty()) emptyMap() else try { Scrape.scrape(hashes, Prefs.trackerList()) } catch (e: Exception) { emptyMap() }
    }

    fun categoryOf(h: Hit): String {
        if (h.category.isNotBlank()) return h.category
        val t = h.title.lowercase()
        return when {
            Regex("(1080p|720p|2160p|480p|x264|x265|h\\.?264|hevc|bluray|web-?dl|webrip|hdtv|dvdrip|\\.mkv|\\.mp4|\\.avi|s\\d{2}e\\d{2})").containsMatchIn(t) -> "Video"
            Regex("(flac|mp3|320kbps|\\balbum\\b|discography|\\.m4a|\\bost\\b)").containsMatchIn(t) -> "Audio"
            Regex("(epub|mobi|azw3|\\.pdf|ebook|audiobook)").containsMatchIn(t) -> "Books"
            Regex("(\\.iso|linux|ubuntu|debian|fedora|windows|macos|android|\\.apk|\\.exe|x64|x86|amd64|arm64)").containsMatchIn(t) -> "Software"
            else -> "Other"
        }
    }
}

/** RSS / Atom feeds that carry magnet links or .torrent enclosures. */
object Rss {
    data class Item(val title: String, val link: String, val date: Long)

    fun parse(xml: String): List<Item> {
        val doc = try { Jsoup.parse(xml, "", Parser.xmlParser()) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Item>()
        for (it in doc.select("item, entry")) {
            val title = it.selectFirst("> title")?.text()?.trim().orEmpty(); if (title.isEmpty()) continue
            val cands = ArrayList<String>()
            it.selectFirst("> enclosure")?.attr("url")?.let { u -> cands += u }
            for (l in it.select("> link")) { cands += l.attr("href"); cands += l.text().trim() }
            it.selectFirst("> guid")?.text()?.trim()?.let { g -> cands += g }
            for (c in it.children()) if (c.tagName().endsWith(":attr") && c.attr("name").equals("magneturl", true)) cands.add(0, c.attr("value"))
            it.children().firstOrNull { c -> c.tagName().endsWith("magnetURI", true) }?.text()?.trim()?.let { m -> cands.add(0, m) }
            val link = cands.firstOrNull { c -> c.startsWith("magnet:", true) } ?: cands.firstOrNull { c -> c.startsWith("http", true) } ?: continue
            val date = (it.selectFirst("> pubDate") ?: it.selectFirst("> updated") ?: it.selectFirst("> published"))?.text()?.let { d -> SiteReader.parseRfc822(d) } ?: 0
            out += Item(title, link, date)
        }
        return out
    }

    /** Empty filter matches everything. Words must all appear; a filter wrapped in slashes is a regular expression. */
    fun matches(filter: String, title: String): Boolean {
        val f = filter.trim()
        if (f.isEmpty()) return true
        if (f.length > 2 && f.startsWith("/") && f.endsWith("/")) return try { Regex(f.substring(1, f.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(title) } catch (e: Exception) { false }
        val t = title.lowercase()
        return f.lowercase().split(Regex("\\s+")).all { w -> if (w.startsWith("-") && w.length > 1) !t.contains(w.substring(1)) else t.contains(w) }
    }
}
