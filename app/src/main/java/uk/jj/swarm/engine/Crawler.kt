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
    private val asked = object : LinkedHashMap<String, Long>(1024, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 6000
    }
    private const val FETCHERS = 24
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
        if (should && job?.isActive != true) { job = scope.launch { run() }; running.value = true }
        if (!should && job != null) { job?.cancel(); job = null; running.value = false }
    }

    @Synchronized fun stopAll() { wants.clear(); job?.cancel(); job = null; running.value = false }

    /** Called when a run ends. Only the current run may mark the crawler as stopped: an older one finishing late must not. */
    @Synchronized private fun ended(mine: Job?) { if (job === mine) { job = null; running.value = false } }

    private fun enqueue(hash: String) {
        synchronized(queue) { if (queue.size < 6000) queue.add(hash) }
    }

    private fun poll(): String? = synchronized(queue) { val it = queue.iterator(); if (it.hasNext()) { val h = it.next(); it.remove(); h } else null }

    fun onAlert(a: Alert<*>) {
        if (!running.value) return
        when (a) {
            is DhtSampleInfohashesAlert -> {
                for (h in a.samples()) enqueue(h.toHex())
                synchronized(pool) { for (n in a.nodes()) if (pool.size < 1500) pool.addLast(n.second) }
            }
            is DhtAnnounceAlert -> enqueue(a.infoHash().toHex())
            is DhtGetPeersAlert -> enqueue(a.infoHash().toHex())
            else -> {}
        }
    }

    /**
     * How many detail fetches may run at once. The crawler shares the engine and the connection with the user's
     * own downloads, so it stands aside while a video is playing and runs gently while anything is downloading.
     */
    fun allowed(): Int = when {
        Engine.streaming.get() > 0 -> 0
        Engine.global.value.downloading > 0 -> 6
        else -> FETCHERS
    }

    private fun canWork() = Engine.ready.value && Engine.blocked.value == null && Engine.networkAllowed() && Engine.sm.isDhtRunning

    private suspend fun run() {
        val mine = currentCoroutineContext()[Job]
        try {
            while (!Engine.isStarted || !Engine.ready.value) delay(500)
            val db = Db.get(Engine.app)
            coroutineScope {
                launch { sampler() }
                repeat(FETCHERS) { n -> launch(Dispatchers.IO) { fetcher(db, n) } }
                launch(Dispatchers.IO) { scraper(db) }
                launch(Dispatchers.IO) {
                    while (isActive) {
                        stats.value = Stats(db.dhtCount(), added, synchronized(queue) { queue.size }, failed, try { Engine.sm.dhtNodes() } catch (t: Throwable) { 0 })
                        delay(2000)
                    }
                }
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w("Swarm", "crawler stopped", t)
        } finally { ended(mine) }
    }

    internal fun refill() {
        try {
            val nodes = Engine.sm.swig().session_state(SessionHandle.SAVE_DHT_STATE).dht_state.nodes
            val list = ArrayList<UdpEndpoint>()
            for (i in 0 until nodes.size) list.add(UdpEndpoint(nodes[i]))
            list.shuffle()
            synchronized(pool) { for (e in list.take(160)) pool.addLast(e) }
        } catch (t: Throwable) { Log.w("Swarm", "dht refill", t) }
    }

    private suspend fun sampler() {
        while (currentCoroutineContext().isActive) {
            val room = allowed()
            if (!canWork() || room == 0) { delay(3000); continue }
            val qs = synchronized(queue) { queue.size }
            if (qs < 1500) {
                if (synchronized(pool) { pool.size } < 30) refill()
                val now = System.currentTimeMillis()
                var sent = 0; var looked = 0
                while (sent < room && looked++ < 200) {
                    val ep = synchronized(pool) { pool.removeFirstOrNull() } ?: break
                    // A node asks not to be sampled again for a while, so leave each one alone for half an hour.
                    val key = try { ep.toString() } catch (t: Throwable) { continue }
                    val last = synchronized(asked) { asked[key] }
                    if (last != null && now - last < 30 * 60_000L) continue
                    synchronized(asked) { asked[key] = now }
                    try { Engine.sm.swig().dht_sample_infohashes(ep.swig(), Sha1Hash.fromBytes(Random.nextBytes(20)).swig()); sent++ } catch (t: Throwable) { }
                }
            }
            delay(1000)
        }
    }

    private suspend fun fetcher(db: Db, n: Int) {
        while (currentCoroutineContext().isActive) {
            if (!canWork() || n >= allowed()) { delay(3000); continue }
            val hash = poll()
            if (hash == null) { delay(700); continue }
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
        if (meta.seeds >= 0) db.dhtSetSwarm(hash, meta.seeds, maxOf(0, meta.peers - meta.seeds))
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
                    // A tracker that has never heard of a torrent says nothing useful, so keep what the swarm itself showed.
                    for (h in need) { val sl = res[h]; if (sl != null) db.dhtSetScrape(h, sl.seeders, sl.leechers) else db.dhtMarkScraped(h) }
                }
                if (++rounds % 30 == 0) db.dhtPrune(Prefs.indexMax)
            } catch (t: Throwable) { Log.w("Swarm", "crawl scrape", t) }
        }
    }
}
