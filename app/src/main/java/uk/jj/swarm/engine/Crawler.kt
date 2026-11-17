package uk.jj.swarm.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.UdpEndpoint
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.DhtAnnounceAlert
import org.libtorrent4j.alerts.DhtGetPeersAlert
import org.libtorrent4j.alerts.DhtSampleInfohashesAlert
import uk.jj.swarm.Db
import uk.jj.swarm.Prefs
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Builds the on-phone DHT index. The DHT has no keyword search, so this asks DHT nodes for samples of the
 * info-hashes they know (BEP 51), listens for hashes other peers announce, then fetches each torrent's name
 * and file list from its swarm (BEP 9). Only details are fetched; no content is downloaded.
 */
object Crawler {
    data class Stats(val indexed: Long = 0, val added: Int = 0, val queued: Int = 0, val failed: Int = 0, val nodes: Long = 0)

    val running = MutableStateFlow(false)
    val stats = MutableStateFlow(Stats())
    private val wants: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val queue = LinkedHashSet<String>()
    private val pool = ArrayDeque<UdpEndpoint>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var added = 0
    @Volatile private var failed = 0

    private val ADULT = Regex("(?i)(porn|xxx|\\bsex\\b|hentai|erotic|nsfw|onlyfans|brazzers|jav\\b|\\banal\\b|blowjob|milf|creampie|naked|nude|18\\+|adult[ ._-]?(video|movie|film))")

    /** [who] is "ui", "worker" or "always". The crawler runs while anyone wants it and the setting allows it. */
    @Synchronized fun want(who: String, on: Boolean) {
        if (on) wants.add(who) else wants.remove(who)
        update()
    }

    @Synchronized fun update() {
        val mode = Prefs.crawlMode
        val should = mode != 0 && wants.isNotEmpty()
        if (should && job == null) job = scope.launch { run() }
        if (!should && job != null) { job?.cancel(); job = null; running.value = false }
    }

    @Synchronized fun stopAll() { wants.clear(); job?.cancel(); job = null; running.value = false }

    private fun enqueue(hash: String) {
        synchronized(queue) { if (queue.size < 3000) queue.add(hash) }
    }

    private fun poll(): String? = synchronized(queue) { val it = queue.iterator(); if (it.hasNext()) { val h = it.next(); it.remove(); h } else null }

    fun onAlert(a: Alert<*>) {
        if (!running.value) return
        when (a) {
            is DhtSampleInfohashesAlert -> {
                for (h in a.samples()) enqueue(h.toHex())
                synchronized(pool) { for (n in a.nodes()) if (pool.size < 600) pool.addLast(n.second) }
            }
            is DhtAnnounceAlert -> enqueue(a.infoHash().toHex())
            is DhtGetPeersAlert -> enqueue(a.infoHash().toHex())
            else -> {}
        }
    }

    private fun canWork() = Engine.ready.value && Engine.blocked.value == null && Engine.networkAllowed() && Engine.sm.isDhtRunning

    private suspend fun run() {
        running.value = true
        val db = Db.get(Engine.app)
        try {
            coroutineScope {
                launch { sampler() }
                repeat(6) { launch(Dispatchers.IO) { fetcher(db) } }
                launch(Dispatchers.IO) { scraper(db) }
                launch(Dispatchers.IO) {
                    while (isActive) {
                        stats.value = Stats(db.dhtCount(), added, synchronized(queue) { queue.size }, failed, try { Engine.sm.dhtNodes() } catch (t: Throwable) { 0 })
                        delay(2000)
                    }
                }
            }
        } finally { running.value = false }
    }

    internal fun refill() {
        try {
            val nodes = Engine.sm.swig().session_state(SessionHandle.SAVE_DHT_STATE).dht_state.nodes
            val list = ArrayList<UdpEndpoint>()
            for (i in 0 until nodes.size) list.add(UdpEndpoint(nodes[i]))
            list.shuffle()
            synchronized(pool) { for (e in list.take(80)) pool.addLast(e) }
        } catch (t: Throwable) { Log.w("Swarm", "dht refill", t) }
    }

    private suspend fun sampler() {
        while (currentCoroutineContext().isActive) {
            if (!canWork()) { delay(3000); continue }
            val qs = synchronized(queue) { queue.size }
            if (qs < 300) {
                if (synchronized(pool) { pool.size } < 12) refill()
                repeat(8) {
                    val ep = synchronized(pool) { pool.removeFirstOrNull() } ?: return@repeat
                    try { Engine.sm.swig().dht_sample_infohashes(ep.swig(), Sha1Hash.fromBytes(Random.nextBytes(20)).swig()) } catch (t: Throwable) { }
                }
            }
            delay(1500)
        }
    }

    private suspend fun fetcher(db: Db) {
        while (currentCoroutineContext().isActive) {
            if (!canWork()) { delay(3000); continue }
            val hash = poll()
            if (hash == null) { delay(1000); continue }
            try { indexOne(db, hash, "magnet:?xt=urn:btih:$hash") } catch (t: Throwable) { Log.w("Swarm", "crawl fetch", t) }
        }
    }

    /** Fetches one discovered torrent's details and stores them. Returns true when a new entry was added. */
    internal fun indexOne(db: Db, hash: String, magnet: String): Boolean {
        if (Engine.has(hash) || db.dhtHas(hash) || db.dhtTries(hash) >= 2) return false
        val meta = Engine.peek(magnet, 15)
        if (meta == null) { db.dhtMarkTried(hash); failed++; return false }
        val name = meta.name
        val sb = StringBuilder()
        for ((path, size) in meta.files.take(40)) { sb.append(path).append('\t').append(size).append('\n'); if (sb.length > 4000) break }
        if (name.isBlank()) { db.dhtMarkTried(hash); return false }
        if (Prefs.adultFilter && (ADULT.containsMatchIn(name) || ADULT.containsMatchIn(sb))) { db.dhtMarkTried(hash); db.dhtMarkTried(hash); return false }
        if (!db.dhtInsert(hash, name, meta.size, meta.files.size, sb.toString())) return false
        added++
        return true
    }

    private suspend fun scraper(db: Db) {
        var rounds = 0
        while (currentCoroutineContext().isActive) {
            delay(20000)
            if (!canWork()) continue
            try {
                val need = db.dhtNeedScrape(70)
                if (need.isNotEmpty()) {
                    val res = Scrape.scrape(need, Prefs.trackerList())
                    for (h in need) { val sl = res[h]; db.dhtSetScrape(h, sl?.seeders ?: -1, sl?.leechers ?: -1) }
                }
                if (++rounds % 30 == 0) db.dhtPrune(Prefs.indexMax)
            } catch (t: Throwable) { Log.w("Swarm", "crawl scrape", t) }
        }
    }
}
