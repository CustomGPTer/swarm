package uk.jj.swarm.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.ip_filter
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import uk.jj.swarm.Db
import uk.jj.swarm.Kind
import uk.jj.swarm.Magnet
import uk.jj.swarm.Prefs
import uk.jj.swarm.TRow
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class TState { META, CHECKING, DOWNLOADING, SEEDING, COMPLETE, PAUSED, QUEUED, ERROR }

data class TItem(
    val hash: String, val name: String, val state: TState, val progress: Float, val done: Long, val total: Long,
    val dl: Int, val ul: Int, val seeds: Int, val peers: Int, val eta: Long, val uploaded: Long, val ratio: Float,
    val added: Long, val savePath: String, val error: String?, val hasMeta: Boolean, val sequential: Boolean,
    val completed: Boolean, val swarmSeeds: Int, val swarmPeers: Int,
) {
    val active get() = state == TState.DOWNLOADING || state == TState.META || state == TState.CHECKING || state == TState.QUEUED
}

data class Global(val dl: Long = 0, val ul: Long = 0, val dhtNodes: Long = 0, val downloading: Int = 0, val seeding: Int = 0)
data class TFile(val index: Int, val path: String, val size: Long, val done: Long, val priority: Int)
data class PeerRow(val ip: String, val client: String, val dl: Int, val ul: Int, val progress: Float)
data class Meta(val name: String, val size: Long, val files: List<Pair<String, Long>>, val seeds: Int = -1, val peers: Int = -1)
data class StreamInfo(val hash: String, val fileIndex: Int, val file: File, val size: Long, val fileOffset: Long, val pieceLength: Int, val numPieces: Int) {
    val firstPiece: Int get() = (fileOffset / pieceLength).toInt()
    val lastPiece: Int get() = ((fileOffset + maxOf(size, 1) - 1) / pieceLength).toInt()
    /**
     * How many pieces to ask for ahead of the player: about 5 MB, whatever the piece size. Kept modest on purpose.
     * Urgent requests jump every queue, and asking one peer for too much at once can stall it.
     */
    val window: Int get() = ((5L * 1024 * 1024 + pieceLength - 1) / pieceLength).toInt().coerceIn(2, 12)
}

/**
 * Which pieces of a torrent have arrived, remembered on this side so the player does not have to ask the
 * engine on every read. Asking is a round trip to the engine's network thread, which is slow when it is busy.
 */
class PieceCache(val hash: String, private val numPieces: Int) {
    private val have = java.util.BitSet(numPieces)
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var lastRefresh = 0L

    private fun handle(): TorrentHandle? = handle?.takeIf { it.isValid } ?: Engine.find(hash)?.also { handle = it }

    /** One question to the engine brings back every piece that has arrived, so reading on needs no more questions. */
    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 250) return
        lastRefresh = now
        try {
            val bf = handle()?.status(TorrentHandle.QUERY_PIECES)?.pieces() ?: return
            val n = minOf(bf.size(), numPieces)
            synchronized(have) { for (i in 0 until n) if (!have.get(i) && bf.getBit(i)) have.set(i) }
        } catch (t: Throwable) { }
    }

    fun has(piece: Int): Boolean {
        if (synchronized(have) { have.get(piece) }) return true
        refresh()
        return synchronized(have) { have.get(piece) }
    }

    /**
     * Puts these pieces at the front of the ordinary queue. They are not made "urgent" (given a deadline):
     * an urgent piece jumps every queue at once, and on a fast connection that can swamp a peer until it
     * stops answering for a minute, which is what kept the player waiting.
     */
    fun want(from: Int, count: Int, last: Int) {
        try {
            val h = handle() ?: return
            for (p in from..minOf(last, from + count - 1)) {
                if (p < 0 || synchronized(have) { have.get(p) }) continue
                h.piecePriority(p, Priority.TOP_PRIORITY)
            }
        } catch (t: Throwable) { }
    }

    /** Last resort for one piece that still has not come after a long wait: let the engine ask a second peer for it. */
    fun urgent(piece: Int) { try { handle()?.setPieceDeadline(piece, 500) } catch (t: Throwable) { } }

    companion object {
        private val all = HashMap<String, PieceCache>()
        fun of(info: StreamInfo): PieceCache = synchronized(all) { all.getOrPut(info.hash) { PieceCache(info.hash, info.numPieces) } }
        fun forget(hash: String) { synchronized(all) { all.remove(hash) } }
    }
}

object Net {
    @Volatile var connected = false
    @Volatile var metered = false
    @Volatile var vpn = false
    private var cm: ConnectivityManager? = null

    fun init(ctx: Context) {
        if (cm != null) return
        val c = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm = c
        refresh()
        try {
            c.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            })
        } catch (e: Exception) { Log.w("Swarm", "net callback", e) }
    }

    fun refresh() {
        val c = cm ?: return
        try {
            val n = c.activeNetwork
            val caps = if (n != null) c.getNetworkCapabilities(n) else null
            connected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            metered = connected && c.isActiveNetworkMetered
            vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) { Log.w("Swarm", "net refresh", e) }
    }
}

object Power {
    @Volatile var charging = false
    @Volatile var level = 100
    fun refresh(ctx: Context) {
        try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) level = l * 100 / s
        } catch (e: Exception) { }
    }
}

object Engine {
    private const val TAG = "Swarm"
    val sm = SessionManager(false)
    lateinit var app: Context
        private set
    private lateinit var db: Db
    private lateinit var resumeDir: File
    private val reg = ConcurrentHashMap<String, TRow>()
    private val startedFlag = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val torrents = MutableStateFlow<List<TItem>>(emptyList())
    val global = MutableStateFlow(Global())
    val blocked = MutableStateFlow<String?>(null)
    val vpnWarn = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val events = MutableStateFlow<String?>(null)      // one-shot messages for the UI

    /** Last two minutes of total download / upload speed, newest last. */
    val speedHistory = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    private val perTorrentHistory = ConcurrentHashMap<String, ArrayDeque<Int>>()
    @Volatile private var completedThisSession = 0
    @Volatile private var hadWork = false
    @Volatile private var lastPrefsTick = -1
    @Volatile private var stopping = false
    @Volatile private var inTick = false

    val isStarted get() = startedFlag.get()

    fun start(ctx: Context) {
        if (!startedFlag.compareAndSet(false, true)) return
        app = ctx.applicationContext
        Prefs.init(app)
        db = Db.get(app)
        resumeDir = File(app.filesDir, "resume").apply { mkdirs() }
        Net.init(app); Power.refresh(app)
        scope.launch(Dispatchers.IO) {
            try {
                val stateFile = File(app.filesDir, "session.state")
                val params = try { if (stateFile.exists()) SessionParams(stateFile.readBytes()) else SessionParams() } catch (e: Throwable) { SessionParams() }
                params.settings = settingsPack()
                try { params.setPosixDiskIO() } catch (e: Throwable) { }
                sm.addListener(listener)
                sm.start(params)
                lastPrefsTick = Prefs.tick.value
                restore()
                try { Blocklist.applyCached(app) } catch (e: Throwable) { Log.w(TAG, "blocklist", e) }
                ready.value = true
                loop()
            } catch (t: Throwable) {
                Log.e(TAG, "engine start failed", t)
                events.value = "Torrent engine failed to start: ${t.message}"
            }
        }
    }

    fun settingsPack(): SettingsPack {
        val sp = SettingsPack()
        val port = Prefs.port
        sp.setString(settings_pack.string_types.user_agent.swigValue(), "Swarm/" + uk.jj.swarm.BuildConfig.VERSION_NAME)
        sp.listenInterfaces(if (port in 1024..65535) "0.0.0.0:$port,[::]:$port" else "0.0.0.0:0,[::]:0")
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        sp.downloadRateLimit(Prefs.dlLimitKb * 1024)
        sp.uploadRateLimit(Prefs.ulLimitKb * 1024)
        val lim = Prefs.activeLimit
        sp.activeDownloads(if (lim <= 0) -1 else lim)
        sp.activeSeeds(-1)
        sp.activeLimit(-1)
        sp.connectionsLimit(Prefs.maxConnections)
        sp.alertQueueSize(10000)
        // The DHT's own speed cap is 8 KB/s by default, which starves the crawler's look-ups. This is a ceiling, not a target.
        sp.setInteger(settings_pack.int_types.dht_upload_rate_limit.swigValue(), 40000)
        val mask = Alert.STATUS_NOTIFICATION.or_(Alert.ERROR_NOTIFICATION).or_(Alert.STORAGE_NOTIFICATION)
            .or_(Alert.DHT_NOTIFICATION).or_(Alert.DHT_OPERATION_NOTIFICATION)
        sp.setInteger(settings_pack.int_types.alert_mask.swigValue(), mask.to_int())
        val pol = when (Prefs.encryption) {
            0 -> settings_pack.enc_policy.pe_disabled
            2 -> settings_pack.enc_policy.pe_forced
            else -> settings_pack.enc_policy.pe_enabled
        }.swigValue()
        sp.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), pol)
        sp.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), pol)
        sp.setInteger(settings_pack.int_types.allowed_enc_level.swigValue(), settings_pack.enc_level.pe_both.swigValue())
        return sp
    }

    fun baseDir(): File {
        val f = File(Prefs.baseDir)
        if (canWrite(f)) return f
        val fb = File(app.getExternalFilesDir(null) ?: app.filesDir, "Downloads")
        fb.mkdirs()
        return fb
    }

    fun usingFallbackDir(): Boolean = !canWrite(File(Prefs.baseDir))

    private fun canWrite(dir: File): Boolean = try {
        dir.mkdirs()
        val t = File(dir, ".swarm-write-test"); t.writeText("x"); t.delete(); true
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ adding

    private fun handle(): SessionHandle = SessionHandle(sm.swig())

    fun find(hash: String): TorrentHandle? = try {
        val h = if (stopping && !sm.isRunning) null else sm.find(Sha1Hash.parseHex(hash)); if (h != null && h.isValid) h else null
    } catch (e: Throwable) { null }

    fun has(hash: String) = reg.containsKey(hash)

    /** Adds a magnet link. Returns the info-hash, or null if the link is not valid. */
    fun addMagnet(uri: String, source: String = ""): String? {
        return try {
            val p = AddTorrentParams.parseMagnetUri(uri.trim())
            val hash = p.infoHashes.best.toHex()
            if (reg.containsKey(hash)) return hash
            sm.find(p.infoHashes.best)?.let { tmp -> if (tmp.isValid) { sm.remove(tmp, SessionHandle.DELETE_FILES); Thread.sleep(400) } }
            p.savePath = baseDir().absolutePath
            if (Prefs.addTrackers) p.trackers = (p.trackers + Prefs.trackerList()).distinct()
            if (Prefs.sequentialDefault) p.flags = p.flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            val row = TRow(hash, p.name?.takeIf { it.isNotBlank() } ?: Magnet.name(uri) ?: "Fetching details…", System.currentTimeMillis(), false, false, false, false, false, source)
            reg[hash] = row; db.saveTorrent(row)
            File(resumeDir, "$hash.magnet").writeText(uri.trim())
            handle().asyncAddTorrent(p)
            hadWork = true
            TorrentService.sync(app, true)
            hash
        } catch (t: Throwable) { Log.w(TAG, "addMagnet", t); null }
    }

    fun addTorrentBytes(bytes: ByteArray, source: String = "", seedFrom: File? = null): String? {
        return try {
            val ti = TorrentInfo.bdecode(bytes)
            val hash = ti.infoHash().toHex()
            if (reg.containsKey(hash)) return hash
            val p = AddTorrentParams()
            p.torrentInfo = ti
            var sorted = false
            if (seedFrom != null) { p.savePath = seedFrom.absolutePath; sorted = true }
            else if (Prefs.autoSort) { p.savePath = File(baseDir(), folderFor(ti)).apply { mkdirs() }.absolutePath; sorted = true }
            else { p.savePath = baseDir().absolutePath; sorted = true }
            if (Prefs.addTrackers && !ti.isPrivate) p.trackers = (p.trackers + Prefs.trackerList()).distinct()
            if (Prefs.sequentialDefault) p.flags = p.flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            val row = TRow(hash, ti.name() ?: hash, System.currentTimeMillis(), false, false, seedFrom != null, sorted, false, source)
            reg[hash] = row; db.saveTorrent(row)
            File(resumeDir, "$hash.torrent").writeBytes(bytes)
            handle().asyncAddTorrent(p)
            hadWork = true
            TorrentService.sync(app, true)
            hash
        } catch (t: Throwable) { Log.w(TAG, "addTorrent", t); null }
    }

    private fun folderFor(ti: TorrentInfo): String {
        val fs = ti.files()
        var best = 0; var bestSize = -1L
        for (i in 0 until fs.numFiles()) { val s = fs.fileSize(i); if (s > bestSize) { bestSize = s; best = i } }
        return Kind.folder(ti.name() ?: "", if (fs.numFiles() > 0) fs.fileName(best) else "")
    }

    /** Turns a saved resume file back into something the session can load. */
    internal fun paramsFromResume(bytes: ByteArray): AddTorrentParams {
        val ec = error_code()
        val sp = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(bytes), ec)
        if (ec.value() != 0) throw IllegalStateException(ec.message())
        return AddTorrentParams(sp)
    }

    private fun restore() {
        for (r in db.torrents()) reg[r.hash] = r
        for (r in reg.values) {
            try {
                val rf = File(resumeDir, "${r.hash}.resume")
                val tf = File(resumeDir, "${r.hash}.torrent")
                val mf = File(resumeDir, "${r.hash}.magnet")
                when {
                    rf.exists() -> handle().asyncAddTorrent(paramsFromResume(rf.readBytes()))
                    tf.exists() -> {
                        val p = AddTorrentParams(); p.torrentInfo = TorrentInfo.bdecode(tf.readBytes()); p.savePath = baseDir().absolutePath
                        handle().asyncAddTorrent(p)
                    }
                    mf.exists() -> {
                        val p = AddTorrentParams.parseMagnetUri(mf.readText()); p.savePath = baseDir().absolutePath
                        handle().asyncAddTorrent(p)
                    }
                }
            } catch (t: Throwable) { Log.w(TAG, "restore ${r.hash}", t) }
        }
        if (reg.values.any { !it.userPaused && !(it.completed && it.seedDone) }) { hadWork = true; TorrentService.sync(app, true) }
    }

    // ------------------------------------------------------------------ alerts

    private val listener = object : AlertListener {
        override fun types(): IntArray? = null
        override fun alert(a: Alert<*>) { try { onAlert(a) } catch (t: Throwable) { Log.w(TAG, "alert", t) } }
    }

    private fun ours(h: TorrentHandle?): String? {
        if (h == null || !h.isValid) return null
        val hash = h.infoHash().toHex()
        return if (reg.containsKey(hash)) hash else null
    }

    private fun onAlert(a: Alert<*>) {
        when (a.type()) {
            AlertType.ADD_TORRENT -> {
                val al = a as AddTorrentAlert
                if (al.error().isError) return
                val h = al.handle(); if (ours(h) != null) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
            AlertType.METADATA_RECEIVED -> {
                val h = (a as TorrentAlert<*>).handle(); val hash = ours(h) ?: return
                val row = reg[hash] ?: return
                h.torrentFile()?.name()?.let { n -> if (n.isNotBlank()) { row.name = n; db.saveTorrent(row) } }
                h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
            AlertType.SAVE_RESUME_DATA -> {
                val al = a as SaveResumeDataAlert
                val hash = ours(al.handle()) ?: return
                File(resumeDir, "$hash.resume").writeBytes(AddTorrentParams.writeResumeDataBuf(al.params()))
            }
            AlertType.TORRENT_FINISHED, AlertType.TORRENT_PAUSED, AlertType.TORRENT_RESUMED, AlertType.STORAGE_MOVED, AlertType.TORRENT_CHECKED -> {
                val h = (a as TorrentAlert<*>).handle(); if (ours(h) != null) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
                if (a.type() == AlertType.STORAGE_MOVED && ours(h) != null) scan(h)
            }
            AlertType.DHT_ANNOUNCE, AlertType.DHT_GET_PEERS, AlertType.DHT_SAMPLE_INFOHASHES -> Crawler.onAlert(a)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ main loop

    private suspend fun loop() {
        var n = 0
        while (!stopping) {
            inTick = true
            try { if (!stopping) tick(n) } catch (t: Throwable) { Log.w(TAG, "tick", t) } finally { inTick = false }
            delay(1000); n++
        }
    }

    private fun inWindow(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = Prefs.schedStart; val e = Prefs.schedEnd
        return if (s == e) true else if (s < e) h in s until e else (h >= s || h < e)
    }

    private fun gate() {
        var reason: String? = null
        val netBlock: Boolean
        if (Net.connected && Prefs.wifiOnly && Net.metered) { reason = "Waiting for Wi-Fi"; netBlock = true }
        else if (Net.connected && Prefs.vpnMode == 2 && !Net.vpn) { reason = "Blocked: VPN is off"; netBlock = true }
        else {
            netBlock = false
            if (Prefs.batterySaver > 0 && !Power.charging && Power.level < Prefs.batterySaver) reason = "Battery saver: below ${Prefs.batterySaver}%"
            else if (Prefs.chargingOnly && !Power.charging) reason = "Waiting for charger"
            else if (Prefs.schedOn && !inWindow()) reason = "Outside scheduled hours (${Prefs.schedStart}:00–${Prefs.schedEnd}:00)"
        }
        vpnWarn.value = Prefs.vpnMode == 1 && Net.connected && !Net.vpn
        if (reason != blocked.value) {
            blocked.value = reason
            if (reason != null) sm.pause() else sm.resume()
        }
        try {
            if (netBlock && sm.isDhtRunning) sm.stopDht()
            if (!netBlock && !sm.isDhtRunning) sm.startDht()
        } catch (t: Throwable) { }
    }

    /** True when the network rules allow any torrent traffic at all (used by the DHT crawler). */
    fun networkAllowed(): Boolean = Net.connected && !(Prefs.wifiOnly && Net.metered) && !(Prefs.vpnMode == 2 && !Net.vpn)

    private fun tick(n: Int) {
        if (!sm.isRunning) return
        val t0 = System.currentTimeMillis(); var t1 = t0; var t2 = t0; var t3 = t0
        if (n % 10 == 0) { Net.refresh(); Power.refresh(app) }
        val pt = Prefs.tick.value
        if (pt != lastPrefsTick) { lastPrefsTick = pt; try { sm.applySettings(settingsPack()) } catch (t: Throwable) { Log.w(TAG, "apply", t) } }
        gate()
        t1 = System.currentTimeMillis()

        val out = ArrayList<TItem>(reg.size)
        var downloading = 0; var seeding = 0
        val seedBlocked = Prefs.seedWifiOnly && Net.metered
        for (row in reg.values) {
            val h = find(row.hash)
            if (h == null) {
                out += TItem(row.hash, row.name, TState.CHECKING, 0f, 0, 0, 0, 0, 0, 0, -1, 0, 0f, row.added, "", null, false, false, row.completed, 0, 0)
                continue
            }
            val st = h.status()
            val flags = st.flags()
            val paused = flags.and_(TorrentFlags.PAUSED).non_zero()
            val auto = flags.and_(TorrentFlags.AUTO_MANAGED).non_zero()
            val seq = flags.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero()
            val hasMeta = st.hasMetadata()
            val total = st.totalWanted(); val done = st.totalWantedDone()
            val ls = st.state()
            val finished = hasMeta && total > 0 && (ls == TorrentStatus.State.FINISHED || ls == TorrentStatus.State.SEEDING) && done >= total

            if (hasMeta && !row.sorted) sortNow(row, h)
            if (hasMeta && (row.name.isBlank() || row.name == "Fetching details…")) { st.name()?.takeIf { it.isNotBlank() }?.let { row.name = it; db.saveTorrent(row) } }

            if (finished && !row.completed) {
                row.completed = true
                if (Prefs.seedMode == 0 && !row.forceSeed) row.seedDone = true
                db.saveTorrent(row)
                completedThisSession++
                scan(h)
                Notify.completed(app, row.name, row.hash)
            }
            if (!finished && row.completed && hasMeta && ls == TorrentStatus.State.DOWNLOADING) { row.completed = false; row.seedDone = false; db.saveTorrent(row) }
            val uploaded = st.allTimeUpload()
            val ratio = if (done > 0) uploaded.toFloat() / done else 0f
            if (row.completed && !row.seedDone && !row.forceSeed && Prefs.seedMode == 1 && ratio >= Prefs.seedRatio) { row.seedDone = true; db.saveTorrent(row) }
            if (row.completed && !row.seedDone && !row.forceSeed && Prefs.seedMode == 0) { row.seedDone = true; db.saveTorrent(row) }

            val wantRun = !row.userPaused && !(row.completed && (row.seedDone || seedBlocked))
            try {
                if (wantRun && paused && !auto) h.setFlags(TorrentFlags.AUTO_MANAGED)
                if (!wantRun && (!paused || auto)) { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause() }
            } catch (t: Throwable) { }

            val err = st.errorCode()
            val state = when {
                err != null && err.isError -> TState.ERROR
                row.userPaused -> TState.PAUSED
                row.completed && (row.seedDone || seedBlocked) -> TState.COMPLETE
                !hasMeta -> TState.META
                paused && auto -> if (row.completed) TState.COMPLETE else TState.QUEUED
                paused -> if (row.completed) TState.COMPLETE else TState.PAUSED
                ls == TorrentStatus.State.CHECKING_FILES || ls == TorrentStatus.State.CHECKING_RESUME_DATA -> TState.CHECKING
                ls == TorrentStatus.State.DOWNLOADING_METADATA -> TState.META
                ls == TorrentStatus.State.SEEDING || ls == TorrentStatus.State.FINISHED -> TState.SEEDING
                else -> TState.DOWNLOADING
            }
            val dl = st.downloadPayloadRate(); val ul = st.uploadPayloadRate()
            val eta = if (!finished && dl > 0 && total > done) (total - done) / dl else -1
            if (state == TState.DOWNLOADING || state == TState.META || state == TState.CHECKING) downloading++
            if (state == TState.SEEDING) seeding++
            val hist = perTorrentHistory.getOrPut(row.hash) { ArrayDeque() }
            hist.addLast(dl); while (hist.size > 60) hist.removeFirst()
            out += TItem(row.hash, row.name.ifBlank { st.name() ?: row.hash }, state, if (total > 0) done.toFloat() / total else 0f, done, total,
                dl, ul, st.numSeeds(), st.numPeers(), eta, uploaded, ratio, row.added, h.savePath() ?: "", if (err != null && err.isError) err.message else null,
                hasMeta, seq, row.completed, st.numComplete(), st.numIncomplete())
        }
        out.sortByDescending { it.added }
        t2 = System.currentTimeMillis()
        torrents.value = out
        val g = Global(sm.downloadRate(), sm.uploadRate(), sm.dhtNodes(), downloading, seeding)
        global.value = g
        speedHistory.value = (speedHistory.value + (g.dl to g.ul)).takeLast(120)

        if (n % 30 == 29) for (row in reg.values) { val h = find(row.hash) ?: continue; try { if (h.needSaveResumeData()) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } catch (t: Throwable) { } }
        if (n % 300 == 299) saveState()

        val need = reg.values.any { !it.userPaused && !(it.completed && it.seedDone) }
        if (need) hadWork = true
        TorrentService.sync(app, need || Crawler.running.value)
        Notify.progress(app, g, blocked.value, need)
        if (n % 5 == 0) SwarmWidget.refresh(app)

        t3 = System.currentTimeMillis()
        if (t3 - t0 > 700) Log.w(TAG, "slow tick: gate ${t1 - t0} ms, torrents ${t2 - t1} ms, rest ${t3 - t2} ms")
        if (Prefs.autoShutdown && hadWork && completedThisSession > 0 && out.none { !it.completed && it.state != TState.PAUSED && it.state != TState.ERROR }) {
            shutdown()
        }
    }

    private fun sortNow(row: TRow, h: TorrentHandle) {
        try {
            row.sorted = true
            if (Prefs.autoSort) {
                val ti = h.torrentFile()
                if (ti != null) {
                    val target = File(baseDir(), folderFor(ti)).apply { mkdirs() }
                    if (File(h.savePath()).absolutePath != target.absolutePath) h.moveStorage(target.absolutePath)
                }
            }
            db.saveTorrent(row)
        } catch (t: Throwable) { Log.w(TAG, "sort", t) }
    }

    private fun scan(h: TorrentHandle) {
        try {
            val ti = h.torrentFile() ?: return
            val fs = ti.files(); val base = h.savePath()
            val paths = (0 until fs.numFiles()).map { File(base, fs.filePath(it)).absolutePath }.toTypedArray()
            if (paths.isNotEmpty()) MediaScannerConnection.scanFile(app, paths, null, null)
        } catch (t: Throwable) { }
    }

    fun saveState() { try { File(app.filesDir, "session.state").writeBytes(sm.saveState()) } catch (t: Throwable) { } }

    /** Stops everything that talks to the torrent session, saves progress, then closes the session. */
    fun stopSession(closeNative: Boolean = true) {
        if (stopping) return
        stopping = true
        try {
            Crawler.stopAll()
            var waited = 0
            while ((inTick || peeking.isNotEmpty()) && waited < 3000) { Thread.sleep(100); waited += 100 }
            if (sm.isRunning) {
                for (row in reg.values) find(row.hash)?.let { try { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } catch (t: Throwable) { } }
                Thread.sleep(600)
                saveState()
                if (closeNative) { sm.removeListener(listener); sm.stop() }
            }
        } catch (t: Throwable) { Log.w(TAG, "stop", t) }
    }

    fun shutdown() {
        // The process is killed straight after, so the native session is not torn down first: progress is already saved.
        stopSession(closeNative = false)
        try { TorrentService.sync(app, false); app.stopService(Intent(app, TorrentService::class.java)); Notify.cancelProgress(app) } catch (t: Throwable) { }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ------------------------------------------------------------------ actions

    fun pause(hash: String) { val r = reg[hash] ?: return; r.userPaused = true; db.saveTorrent(r) }

    fun resume(hash: String) {
        val r = reg[hash] ?: return
        r.userPaused = false
        if (r.completed && r.seedDone) { r.seedDone = false; r.forceSeed = true }
        db.saveTorrent(r)
        find(hash)?.let { try { it.swig().clear_error() } catch (t: Throwable) { } }
        hadWork = true
        TorrentService.sync(app, true)
    }

    fun pauseAll() { for (r in reg.values) if (!r.completed) { r.userPaused = true; db.saveTorrent(r) } }
    fun resumeAll() { for (r in reg.values) if (r.userPaused) { r.userPaused = false; db.saveTorrent(r) }; TorrentService.sync(app, true) }

    fun remove(hash: String, deleteFiles: Boolean) {
        val h = find(hash)
        reg.remove(hash); db.deleteTorrent(hash); perTorrentHistory.remove(hash); PieceCache.forget(hash)
        for (ext in listOf("resume", "magnet", "torrent")) File(resumeDir, "$hash.$ext").delete()
        try { if (h != null) { if (deleteFiles) sm.remove(h, SessionHandle.DELETE_FILES) else sm.remove(h) } } catch (t: Throwable) { Log.w(TAG, "remove", t) }
    }

    fun magnetOf(hash: String): String = try { find(hash)?.makeMagnetUri() ?: Magnet.build(hash, reg[hash]?.name) } catch (t: Throwable) { Magnet.build(hash, reg[hash]?.name) }

    fun setSequential(hash: String, on: Boolean) { find(hash)?.let { if (on) it.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else it.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) } }
    fun recheck(hash: String) { PieceCache.forget(hash); find(hash)?.forceRecheck() }
    fun reannounce(hash: String) { find(hash)?.let { it.forceReannounce(); it.forceDHTAnnounce() } }
    fun limits(hash: String): Pair<Int, Int> { val h = find(hash) ?: return 0 to 0; return (h.downloadLimit.coerceAtLeast(0) / 1024) to (h.uploadLimit.coerceAtLeast(0) / 1024) }
    fun setLimits(hash: String, dlKb: Int, ulKb: Int) { find(hash)?.let { it.downloadLimit = if (dlKb <= 0) -1 else dlKb * 1024; it.uploadLimit = if (ulKb <= 0) -1 else ulKb * 1024 } }
    fun history(hash: String): List<Int> = perTorrentHistory[hash]?.toList() ?: emptyList()
    fun queueUp(hash: String) { find(hash)?.queuePositionUp() }
    fun queueDown(hash: String) { find(hash)?.queuePositionDown() }

    fun files(hash: String): List<TFile> {
        val h = find(hash) ?: return emptyList()
        val ti = h.torrentFile() ?: return emptyList()
        val fs = ti.files()
        val prog = try { h.fileProgress() } catch (t: Throwable) { LongArray(0) }
        val prios = try { h.filePriorities() } catch (t: Throwable) { emptyArray<Priority>() }
        return (0 until fs.numFiles()).filter { !fs.padFileAt(it) }.map { i ->
            TFile(i, fs.filePath(i), fs.fileSize(i), prog.getOrElse(i) { 0 }, prios.getOrNull(i)?.swig()?.toInt() ?: 4)
        }
    }

    fun setFilePriority(hash: String, index: Int, wanted: Boolean, high: Boolean = false) {
        find(hash)?.filePriority(index, if (!wanted) Priority.IGNORE else if (high) Priority.TOP_PRIORITY else Priority.DEFAULT)
    }

    fun peers(hash: String): List<PeerRow> = try {
        find(hash)?.peerInfo()?.map { PeerRow(it.ip(), it.client() ?: "", it.downSpeed(), it.upSpeed(), it.progress()) } ?: emptyList()
    } catch (t: Throwable) { emptyList() }

    fun trackers(hash: String): List<String> = try { find(hash)?.trackers()?.map { it.url() } ?: emptyList() } catch (t: Throwable) { emptyList() }
    fun addTracker(hash: String, url: String) { try { find(hash)?.addTracker(AnnounceEntry(url.trim())) } catch (t: Throwable) { } }

    /** Piece map squeezed into [buckets] values from 0 (none) to 1 (all pieces in the bucket present). */
    fun pieceMap(hash: String, buckets: Int): FloatArray {
        val h = find(hash) ?: return FloatArray(0)
        return try {
            val bf = h.status(TorrentHandle.QUERY_PIECES).pieces()
            val n = bf.size(); if (n <= 0) return FloatArray(0)
            val b = minOf(buckets, n)
            FloatArray(b) { i ->
                val from = i.toLong() * n / b; val to = maxOf(from + 1, (i + 1).toLong() * n / b)
                var have = 0; for (p in from until to) if (bf.getBit(p.toInt())) have++
                have.toFloat() / (to - from)
            }
        } catch (t: Throwable) { FloatArray(0) }
    }

    fun filePath(hash: String, index: Int): File? {
        val h = find(hash) ?: return null
        val ti = h.torrentFile() ?: return null
        return File(h.savePath(), ti.files().filePath(index))
    }

    // ------------------------------------------------------------------ streaming support

    fun stream(hash: String, index: Int): StreamInfo? {
        val h = find(hash) ?: return null
        val ti = h.torrentFile() ?: return null
        val fs = ti.files()
        if (index !in 0 until fs.numFiles()) return null
        try {
            // The file must be wanted, but not at top priority: that level is kept for the pieces the player needs next.
            if (h.filePriority(index) == Priority.IGNORE || h.filePriority(index) == Priority.TOP_PRIORITY) h.filePriority(index, Priority.DEFAULT)
            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        } catch (t: Throwable) { }
        val info = StreamInfo(hash, index, File(h.savePath(), fs.filePath(index)), fs.fileSize(index), fs.fileOffset(index), ti.pieceLength(), ti.numPieces())
        // A player reads the start of a file and then, for most films, an index kept at the very end. Ask for both
        // straight away so the end is already on its way while the start is being read.
        val cache = PieceCache.of(info)
        val tail = ((3L * 1024 * 1024 + info.pieceLength - 1) / info.pieceLength).toInt().coerceIn(1, 6)
        cache.want(info.firstPiece, minOf(info.window, 4), info.lastPiece)
        cache.want(maxOf(info.firstPiece, info.lastPiece - tail + 1), tail, info.lastPiece)
        return info
    }

    /** How many video players are reading a torrent that is still downloading. The crawler stands aside for them. */
    val streaming = java.util.concurrent.atomic.AtomicInteger(0)

    fun havePiece(hash: String, piece: Int): Boolean = try { find(hash)?.havePiece(piece) ?: false } catch (t: Throwable) { false }

    fun wantPieces(hash: String, from: Int, count: Int, numPieces: Int) {
        val h = find(hash) ?: return
        try { for (i in 0 until count) { val p = from + i; if (p in 0 until numPieces && !h.havePiece(p)) h.setPieceDeadline(p, 200 + i * 150) } } catch (t: Throwable) { }
    }

    fun fileComplete(hash: String, index: Int): Boolean {
        val h = find(hash) ?: return false
        return try { val ti = h.torrentFile() ?: return false; h.fileProgress()[index] >= ti.files().fileSize(index) } catch (t: Throwable) { false }
    }

    // ------------------------------------------------------------------ metadata peek / create

    private val peeking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Fetches a torrent's name and file list from the swarm without downloading any of its content.
     * Blocks for up to [seconds]. Works for old (v1), new (v2) and hybrid torrents.
     */
    fun peek(magnet: String, seconds: Int, withTrackers: Boolean = false): Meta? {
        val p = try { AddTorrentParams.parseMagnetUri(magnet.trim()) } catch (t: Throwable) { return null }
        if (withTrackers && Prefs.addTrackers) try { p.trackers = (p.trackers + Prefs.trackerList()).distinct() } catch (t: Throwable) { }
        val sha = p.infoHashes.best
        val hash = sha.toHex()
        fun read(h: TorrentHandle): Meta? {
            val ti = h.torrentFile() ?: return null
            val fs = ti.files()
            val files = ArrayList<Pair<String, Long>>()
            for (i in 0 until fs.numFiles()) if (!fs.padFileAt(i)) files += fs.filePath(i) to fs.fileSize(i)
            var seeds = -1; var peers = -1
            try { val st = h.status(); seeds = maxOf(st.listSeeds(), st.numSeeds()); peers = maxOf(st.listPeers(), st.numPeers(), seeds) } catch (t: Throwable) { }
            return Meta(ti.name() ?: "", files.sumOf { it.second }, files, seeds, peers)
        }
        return try {
            if (reg.containsKey(hash)) return find(hash)?.let { read(it) }
            if (!peeking.add(hash)) return null
            try {
                p.savePath = File(app.cacheDir, "meta").apply { mkdirs() }.absolutePath
                p.flags = p.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv()).or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
                handle().asyncAddTorrent(p)
                val end = System.currentTimeMillis() + seconds * 1000L
                var out: Meta? = null
                while (System.currentTimeMillis() < end && !stopping) {
                    Thread.sleep(250)
                    val h = sm.find(sha) ?: continue
                    if (!h.isValid) continue
                    if (h.status().hasMetadata()) { out = read(h); break }
                }
                // Never remove a torrent the user added in the meantime.
                if (!stopping && !reg.containsKey(hash)) sm.find(sha)?.let { if (it.isValid) sm.remove(it, SessionHandle.DELETE_FILES) }
                out
            } finally { peeking.remove(hash) }
        } catch (t: Throwable) { Log.w(TAG, "peek", t); null }
    }

    fun dhtPeers(hash: String, seconds: Int): Int = try { sm.dhtGetPeers(Sha1Hash.parseHex(hash), seconds).size } catch (t: Throwable) { -1 }

    /** Builds a .torrent for [src], saves it next to the source, and starts seeding it. Returns the magnet link. */
    fun create(src: File, trackers: List<String>, privateFlag: Boolean, comment: String): String? {
        return try {
            val b = TorrentBuilder().path(src).creator("Swarm").comment(comment).setPrivate(privateFlag)
            trackers.forEachIndexed { i, t -> b.addTracker(t, i) }
            val bytes = b.generate().entry().bencode()
            File(src.parentFile, src.name + ".torrent").writeBytes(bytes)
            val hash = addTorrentBytes(bytes, "Created", src.parentFile) ?: return null
            Magnet.build(hash, src.name, trackers)
        } catch (t: Throwable) { Log.w(TAG, "create", t); null }
    }

    fun registry(): List<TRow> = reg.values.toList()
}

/** Optional IP blocklist (P2P or DAT format, plain or .gz). */
object Blocklist {
    private fun file(ctx: Context) = File(ctx.filesDir, "blocklist.txt")
    @Volatile var rules = 0

    fun download(ctx: Context, url: String): Int {
        val bytes = uk.jj.swarm.search.Http.bytes(url) ?: return -1
        val text = try {
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        } catch (e: Exception) { return -1 }
        file(ctx).writeBytes(text)
        return applyCached(ctx)
    }

    fun clear(ctx: Context) { file(ctx).delete(); rules = 0; try { Engine.sm.swig().set_ip_filter(ip_filter()) } catch (t: Throwable) { } }

    fun applyCached(ctx: Context): Int {
        val f = file(ctx); if (!f.exists()) return 0
        val filter = ip_filter(); var n = 0
        val range = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*-\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})")
        f.useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val m = range.find(line) ?: continue
                val ec = error_code()
                val a = org.libtorrent4j.swig.address.from_string(norm(m.groupValues[1]), ec); if (ec.value() != 0) continue
                val b = org.libtorrent4j.swig.address.from_string(norm(m.groupValues[2]), ec); if (ec.value() != 0) continue
                filter.add_rule(a, b, 1); n++
            }
        }
        Engine.sm.swig().set_ip_filter(filter)
        rules = n
        return n
    }

    private fun norm(ip: String) = ip.split('.').joinToString(".") { it.toInt().toString() }
}
