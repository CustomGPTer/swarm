package uk.jj.swarm.engine

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Asks public UDP trackers how many seeders and leechers a torrent has (BEP 15 scrape). */
object Scrape {
    data class SL(val seeders: Int, val leechers: Int, val completed: Int)

    private const val PROTOCOL_ID = 0x41727101980L
    private const val BATCH = 70

    fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }

    fun connectRequest(tid: Int): ByteArray = ByteBuffer.allocate(16).putLong(PROTOCOL_ID).putInt(0).putInt(tid).array()

    fun scrapeRequest(connId: Long, tid: Int, hashes: List<String>): ByteArray {
        val b = ByteBuffer.allocate(16 + 20 * hashes.size).putLong(connId).putInt(2).putInt(tid)
        for (h in hashes) b.put(hexToBytes(h))
        return b.array()
    }

    fun parseScrape(data: ByteArray, len: Int, tid: Int, hashes: List<String>): Map<String, SL> {
        if (len < 8) return emptyMap()
        val b = ByteBuffer.wrap(data, 0, len)
        if (b.int != 2 || b.int != tid) return emptyMap()
        val out = HashMap<String, SL>()
        for (h in hashes) {
            if (b.remaining() < 12) break
            val s = b.int; val c = b.int; val l = b.int
            out[h] = SL(s, l, c)
        }
        return out
    }

    /** One tracker. [tracker] is udp://host:port/announce. */
    fun scrapeOne(tracker: String, hashes: List<String>, timeoutMs: Int): Map<String, SL> {
        val u = URI(tracker)
        if (u.scheme != "udp" || u.host == null || u.port <= 0) return emptyMap()
        val out = HashMap<String, SL>()
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(u.host)
            val buf = ByteArray(16 + 12 * BATCH + 64)
            var tid = Random.nextInt()
            val cr = connectRequest(tid)
            sock.send(DatagramPacket(cr, cr.size, addr, u.port))
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            val rb = ByteBuffer.wrap(buf, 0, p.length)
            if (p.length < 16 || rb.int != 0 || rb.int != tid) return emptyMap()
            val connId = rb.long
            for (chunk in hashes.chunked(BATCH)) {
                tid = Random.nextInt()
                val rq = scrapeRequest(connId, tid, chunk)
                sock.send(DatagramPacket(rq, rq.size, addr, u.port))
                val rp = DatagramPacket(buf, buf.size)
                sock.receive(rp)
                out.putAll(parseScrape(buf, rp.length, tid, chunk))
            }
        }
        return out
    }

    /** Asks several trackers at once and keeps the highest seeder count seen for each hash. */
    fun scrape(hashes: List<String>, trackers: List<String>, timeoutMs: Int = 4000): Map<String, SL> {
        val valid = hashes.filter { it.length == 40 }.distinct()
        val udp = trackers.filter { it.startsWith("udp://") }.take(6)
        if (valid.isEmpty() || udp.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(udp.size)
        val merged = HashMap<String, SL>()
        try {
            val futures = udp.map { t -> pool.submit(Callable { try { scrapeOne(t, valid, timeoutMs) } catch (e: Exception) { emptyMap() } }) }
            for (f in futures) {
                val m = try { f.get(timeoutMs * 3L + 2000, TimeUnit.MILLISECONDS) } catch (e: Exception) { emptyMap() }
                for ((h, sl) in m) {
                    if (sl.seeders == 0 && sl.leechers == 0 && sl.completed == 0) continue   // this tracker does not know the torrent
                    val cur = merged[h]; if (cur == null || sl.seeders > cur.seeders) merged[h] = sl
                }
            }
        } finally { pool.shutdownNow() }
        return merged
    }
}
