package uk.jj.swarm.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uk.jj.swarm.Magnet
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class Hit(
    val source: String, val title: String, val magnet: String?, val torrentUrl: String?, val hash: String?,
    val size: Long, val seeders: Int, val leechers: Int, val published: Long, val page: String?,
    val category: String = "", val note: String = "", val files: Int = -1,
) {
    /** Identity used for de-duplication and the watchlist. */
    val key: String get() = hash ?: (torrentUrl ?: (if (magnet == null) page else null) ?: title).lowercase()

    /** True for a result read from a listing that gives no download link: the link is on the result's own page. */
    val pageOnly: Boolean get() = magnet == null && torrentUrl == null && hash == null && page != null
}

/**
 * Generic reader for ordinary web pages. It is not written for any particular site: it finds magnet and
 * .torrent links, works out which block of the page each one belongs to, and reads the details around it.
 */
object SiteReader {
    private val SIZE = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(bytes|[KMGT]i?B)\\b")
    private val INT = Regex("^\\s*([0-9][0-9,.]*)\\s*$")
    private val AGO = Regex("(?i)(\\d+)\\s*(second|sec|minute|min|hour|hr|day|week|wk|month|mo|year|yr)s?\\.?\\s*(ago)?\\b")
    private val ISO = Regex("(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2}))?")
    private val DMY = Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})\\b")
    private val MON = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?[ .-]+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?[ ,.-]+(\\d{4})\\b")
    private val MON2 = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?[ ]+(\\d{1,2})(?:st|nd|rd|th)?,?[ ]+(\\d{4})\\b")
    private val SEED_LABEL = Regex("(?i)seed(?:er)?s?\\s*[:=]?\\s*([0-9][0-9,]*)")
    private val LEECH_LABEL = Regex("(?i)(?:leech(?:er)?s?|peers?)\\s*[:=]?\\s*([0-9][0-9,]*)")
    private val SL_SHORT = Regex("(?i)\\bS(?:E)?\\s*[:=]\\s*([0-9][0-9,]*)\\D{1,12}L(?:E)?\\s*[:=]\\s*([0-9][0-9,]*)")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parseSize(text: String): Long {
        val m = SIZE.find(text) ?: return -1
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2].uppercase()[0]) { 'K' -> 1024.0; 'M' -> 1048576.0; 'G' -> 1073741824.0; 'T' -> 1099511627776.0; else -> 1.0 }
        return (v * mult).toLong()
    }

    fun parseAge(text: String, now: Long = System.currentTimeMillis()): Long {
        ISO.find(text)?.let { m ->
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.clear()
            c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0)
            if (c.timeInMillis in 1..(now + 86400000L)) return c.timeInMillis
        }
        MON.find(text)?.let { m -> return ymd(m.groupValues[3].toInt(), MONTHS.indexOf(m.groupValues[2].lowercase().take(3)), m.groupValues[1].toInt()) }
        MON2.find(text)?.let { m -> return ymd(m.groupValues[3].toInt(), MONTHS.indexOf(m.groupValues[1].lowercase().take(3)), m.groupValues[2].toInt()) }
        DMY.find(text)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
            return if (b > 12) ymd(y, a - 1, b) else ymd(y, b - 1, a)
        }
        val low = text.lowercase()
        if (Regex("\\btoday\\b").containsMatchIn(low)) return now
        if (Regex("\\byesterday\\b").containsMatchIn(low)) return now - 86400000L
        for (m in AGO.findAll(text)) {
            if (m.groupValues[3].isEmpty() && !low.contains("ago")) continue
            val n = m.groupValues[1].toLong()
            val unit = when (m.groupValues[2].lowercase().take(2)) { "se" -> 1000L; "mi" -> 60000L; "ho", "hr" -> 3600000L; "da" -> 86400000L; "we", "wk" -> 604800000L; "mo" -> 2592000000L; else -> 31536000000L }
            return now - n * unit
        }
        return 0
    }

    private fun ymd(y: Int, m: Int, d: Int): Long {
        if (m !in 0..11) return 0
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.clear(); c.set(y, m, d); return c.timeInMillis
    }

    fun parseRfc822(s: String): Long {
        for (p in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try { return SimpleDateFormat(p, Locale.US).apply { if (p.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC") }.parse(s.trim())!!.time } catch (e: Exception) { }
        }
        return parseAge(s)
    }

    private fun int(text: String?): Int = text?.let { INT.find(it)?.groupValues?.get(1)?.replace(",", "")?.replace(".", "")?.toIntOrNull() } ?: -1

    private fun leadInt(text: String?): Int = text?.let { Regex("^\\s*([0-9][0-9,]*)").find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() } ?: -1

    private fun isTorrentLink(a: Element): Boolean {
        val h = a.attr("href")
        if (h.startsWith("magnet:", true)) return false
        val abs = a.absUrl("href").ifEmpty { h }
        val path = abs.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".torrent")
    }

    private fun targetKey(a: Element): String {
        val h = a.attr("href")
        return if (h.startsWith("magnet:", true)) (Magnet.hash(h) ?: h) else a.absUrl("href").ifEmpty { h }
    }

    private fun targets(root: Element): List<Element> = root.select("a[href]").filter { it.attr("href").startsWith("magnet:", true) || isTorrentLink(it) }

    private val HASH_IN_URL = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{40})(?![0-9a-fA-F])")

    private fun hashOf(a: Element): String? = HASH_IN_URL.find(a.absUrl("href").substringAfter("://").substringAfter('/', ""))?.groupValues?.get(1)?.lowercase()

    /**
     * Every result on a listing page. A result is recognised by its magnet link, its .torrent link, or, failing
     * both, a link whose address contains the torrent's 40-character info-hash.
     */
    fun parse(html: String, baseUrl: String, source: String, now: Long = System.currentTimeMillis()): List<Hit> {
        val doc = Jsoup.parse(html, baseUrl)
        val body = doc.body() ?: return emptyList()
        var anchors = targets(body)
        val hashMode = anchors.isEmpty()
        if (hashMode) anchors = body.select("a[href]").filter { it.text().trim().length >= 6 && hashOf(it) != null }
        if (anchors.isEmpty()) return emptyList()
        fun inside(el: Element): List<Element> = if (hashMode) el.select("a[href]").filter { hashOf(it) != null } else targets(el)
        // An item has at most one magnet and one .torrent link, so a block holding two different ones spans several items.
        val counts = HashMap<Element, Boolean>()
        fun spansSeveral(el: Element): Boolean = counts.getOrPut(el) {
            val t = inside(el)
            if (hashMode) t.map { hashOf(it) }.distinct().size > 1
            else t.filter { it.attr("href").startsWith("magnet:", true) }.map { targetKey(it) }.distinct().size > 1 || t.filter { isTorrentLink(it) }.map { targetKey(it) }.distinct().size > 1
        }
        val merged = LinkedHashMap<Element, MutableList<Element>>()
        for (a in anchors) {
            var el: Element = a
            while (true) {
                val p = el.parent() ?: break
                if (p.tagName() == "body" || p.tagName() == "html" || spansSeveral(p)) break
                el = p
            }
            merged.getOrPut(el) { ArrayList() }.add(a)
        }
        val out = ArrayList<Hit>()
        for ((row, links) in merged) {
            val text = row.text()
            if (hashMode) {
                val a = links.maxByOrNull { it.text().length } ?: continue
                val hash = hashOf(a) ?: continue
                val title = a.text().trim().take(300)
                val (s, l) = seedLeech(row, text)
                val rest = text.replace(title, " ")
                out += Hit(source, title, Magnet.build(hash, title), null, hash, parseSize(rest), s, l, parseAge(rest, now), a.absUrl("href"))
                continue
            }
            val magnet = links.firstOrNull { it.attr("href").startsWith("magnet:", true) }?.attr("href")
            val torrent = links.firstOrNull { isTorrentLink(it) }?.absUrl("href")?.takeIf { it.isNotEmpty() }
            val hash = magnet?.let { Magnet.hash(it) }
            val title = pickTitle(row, links, magnet) ?: continue
            val (s, l) = seedLeech(row, text)
            val page = row.select("a[href]").firstOrNull { a -> a !in links && a.absUrl("href").startsWith("http") && a.text().trim().length >= 8 }?.absUrl("href")
            val rest = text.replace(title, " ")
            out += Hit(source, title, magnet, torrent, hash, parseSize(rest), s, l, parseAge(rest, now), page)
        }
        return out.distinctBy { it.key }
    }

    private fun pickTitle(row: Element, links: List<Element>, magnet: String?): String? {
        val best = row.select("a[href]").filter { it !in links }.map { it.text().trim() }.filter { it.length >= 6 && !it.startsWith("magnet", true) }.maxByOrNull { it.length }
        val dn = magnet?.let { Magnet.name(it) }?.trim()?.takeIf { it.length >= 3 }
        val own = links.map { it.text().trim() }.filter { it.length >= 12 && !it.equals("download", true) && !it.contains("magnet", true) }.maxByOrNull { it.length }
        val heading = row.select("h1,h2,h3,h4,strong,b").map { it.text().trim() }.filter { it.length >= 6 }.maxByOrNull { it.length }
        val big = row.text().length > 1200
        return (if (big) dn ?: heading ?: best ?: own else best ?: dn ?: own ?: heading)?.take(300)
    }

    private fun seedLeech(row: Element, text: String): Pair<Int, Int> {
        var s = -1; var l = -1
        for (el in row.select("[class],[id],[title],[data-label]")) {
            val tag = (el.className() + " " + el.id() + " " + el.attr("title") + " " + el.attr("data-label")).lowercase()
            val strong = tag.contains("seed") || tag.contains("leech") || tag.contains("peer")
            val weak = Regex("\\b(green|red|up|down|se|le)\\b").containsMatchIn(tag) || tag.contains("success") || tag.contains("danger")
            if (!strong && !weak) continue
            val txt = el.ownText().ifBlank { el.text() }
            val v = if (strong) leadInt(el.text()) else int(txt)
            if (v < 0) continue
            if (s < 0 && (tag.contains("seed") || (!strong && (Regex("\\b(green|up|se)\\b").containsMatchIn(tag) || tag.contains("success"))))) s = v
            else if (l < 0 && (tag.contains("leech") || tag.contains("peer") || (!strong && (Regex("\\b(red|down|le)\\b").containsMatchIn(tag) || tag.contains("danger"))))) l = v
        }
        if (s < 0 || l < 0) {
            val tr = generateSequence(row) { it.parent() }.firstOrNull { it.tagName() == "tr" }
            val table = tr?.let { t -> generateSequence(t) { it.parent() }.firstOrNull { it.tagName() == "table" } }
            if (tr != null && table != null) {
                val head = table.select("tr").firstOrNull { it.select("th").size >= 2 } ?: table.select("tr").firstOrNull()
                val hc = head?.select("th,td")?.map { it.text().trim().lowercase() } ?: emptyList()
                val cells = tr.select("> td")
                if (head != null && head != tr && hc.size == cells.size) {
                    val si = hc.indexOfFirst { it.matches(Regex("(se|s|seed(er)?s?|seeds?)")) || it.startsWith("seed") }
                    val li = hc.indexOfFirst { it.matches(Regex("(le|l|leech(er)?s?|peers?)")) || it.startsWith("leech") }
                    if (s < 0 && si >= 0) s = int(cells[si].text())
                    if (l < 0 && li >= 0) l = int(cells[li].text())
                }
            }
        }
        if (s < 0 || l < 0) SL_SHORT.find(text)?.let { if (s < 0) s = it.groupValues[1].replace(",", "").toIntOrNull() ?: -1; if (l < 0) l = it.groupValues[2].replace(",", "").toIntOrNull() ?: -1 }
        if (s < 0) SEED_LABEL.find(text)?.let { s = it.groupValues[1].replace(",", "").toIntOrNull() ?: -1 }
        if (l < 0) LEECH_LABEL.find(text)?.let { l = it.groupValues[1].replace(",", "").toIntOrNull() ?: -1 }
        return s to l
    }

    /** For listing pages with no magnet links of their own: the repeated same-site links most likely to be the results. */
    fun candidates(html: String, baseUrl: String, limit: Int = 15): List<Pair<String, String>> = candidateLinks(Jsoup.parse(html, baseUrl), baseUrl, limit).map { it.text().trim() to it.absUrl("href") }

    /**
     * Results on a listing page that shows no download links of its own, only a link to each result's page.
     * The size, seeders and age are read from the block around each link; the download link is fetched later,
     * from the result's page, when the user asks for it.
     */
    fun listing(html: String, baseUrl: String, source: String, now: Long = System.currentTimeMillis()): List<Hit> {
        val doc = Jsoup.parse(html, baseUrl)
        val links = candidateLinks(doc, baseUrl, 80)
        if (links.size < 3) return emptyList()
        val set = links.toSet()
        val out = ArrayList<Hit>()
        for (a in links) {
            var row: Element = a
            while (true) {
                val p = row.parent() ?: break
                if (p.tagName() == "body" || p.tagName() == "html") break
                if (p.select("a[href]").any { it !== a && it in set && it.absUrl("href") != a.absUrl("href") }) break
                row = p
            }
            val title = a.text().trim().take(300)
            val rest = row.text().replace(title, " ")
            val (s, l) = seedLeech(row, rest)
            out += Hit(source, title, null, null, null, parseSize(rest), s, l, parseAge(rest, now), a.absUrl("href"))
        }
        // A list of torrents shows sizes. A list of anything else (news, forum threads, menus) does not.
        if (out.count { it.size > 0 } * 3 < out.size) return emptyList()
        return out.distinctBy { it.key }
    }

    private fun candidateLinks(doc: Document, baseUrl: String, limit: Int): List<Element> {
        val host = try { URI(baseUrl).host } catch (e: Exception) { null } ?: return emptyList()
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (a in doc.select("a[href]")) {
            val url = a.absUrl("href"); val text = a.text().trim()
            if (text.length < 12 || !url.startsWith("http")) continue
            val u = try { URI(url) } catch (e: Exception) { continue }
            if (u.host != host) continue
            val path = u.path ?: continue
            if (path.count { it == '/' } < 1 || path.length < 3) continue
            val shape = path.trim('/').split('/').joinToString("/") { seg -> if (seg.any { it.isDigit() } || seg.length > 24) "*" else seg } + if (u.query != null) "?" + u.query.split('&').joinToString("&") { it.substringBefore('=') } else ""
            groups.getOrPut(shape) { ArrayList() }.add(a)
        }
        val best = groups.values.map { g -> g.distinctBy { it.absUrl("href") } }.filter { it.size >= 3 }
            .maxByOrNull { g -> g.size * 10 + g.sumOf { it.text().trim().length } / g.size } ?: return emptyList()
        return best.take(limit)
    }

    /** A single torrent's own page. */
    fun detail(html: String, pageUrl: String, title: String, source: String, now: Long = System.currentTimeMillis()): Hit? {
        val doc = Jsoup.parse(html, pageUrl)
        val body = doc.body() ?: return null
        val links = targets(body)
        val magnet = links.firstOrNull { it.attr("href").startsWith("magnet:", true) }?.attr("href")
        val torrent = links.firstOrNull { isTorrentLink(it) }?.absUrl("href")?.takeIf { it.isNotEmpty() }
            ?: body.select("a[href]").firstOrNull { a -> val h = a.attr("href"); val t = a.text(); h.contains("download", true) && (h.contains("torrent", true) || t.contains("torrent", true)) }?.absUrl("href")?.takeIf { it.startsWith("http") }
        val urlHash = HASH_IN_URL.find(pageUrl.substringAfter("://").substringAfter('/', ""))?.groupValues?.get(1)?.lowercase()
        if (magnet == null && torrent == null && urlHash == null) return null
        val text = body.text()
        val sizeLabel = Regex("(?i)(?:total\\s+)?size\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?\\s*(?:bytes|[KMGT]i?B))").find(text)?.groupValues?.get(1)
        val s = SEED_LABEL.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: -1
        val l = LEECH_LABEL.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: -1
        val dateLabel = Regex("(?i)(?:uploaded|added|date|published)\\s*(?:on)?\\s*[:=]?\\s*(.{4,40})").find(text)?.groupValues?.get(1)
        val hash = magnet?.let { Magnet.hash(it) } ?: urlHash
        return Hit(source, title, magnet ?: hash?.let { Magnet.build(it, title) }, torrent, hash, parseSize(sizeLabel ?: text), s, l, dateLabel?.let { parseAge(it, now) } ?: 0, pageUrl)
    }

    /** True when a page is an anti-bot interstitial rather than real content. */
    fun looksBlocked(code: Int, html: String?): Boolean {
        if (code == 403 || code == 503 || code == 429) return true
        val h = html ?: return true
        return h.length < 6000 && (h.contains("Just a moment", true) || h.contains("cf-chl", true) || h.contains("challenge-platform", true) || h.contains("Checking your browser", true) || h.contains("Attention Required", true))
    }

    /** Turns the address of a results page for the word "test" into a reusable template. */
    fun toTemplate(url: String): String? {
        val u = url.trim()
        if (u.contains("{query}")) return u
        val i = u.indexOf("test", ignoreCase = true)
        if (i < 0) return null
        // prefer an occurrence in the query string or path after the host
        val hostEnd = u.indexOf('/', u.indexOf("://") + 3).let { if (it < 0) u.length else it }
        val j = u.indexOf("test", hostEnd, ignoreCase = true)
        if (j < 0) return null
        return u.substring(0, j) + "{query}" + u.substring(j + 4)
    }
}
