package uk.jj.swarm

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.Pair
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.engine.TState
import uk.jj.swarm.play.TorrentDataSource
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Runs the real engine against a second libtorrent session on this machine: one side seeds a torrent made
 * here, the app side finds its details, downloads it, sorts it, streams from it, restores and removes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class EngineTest {
    companion object {
        @BeforeClass @JvmStatic fun native() = NativeLib.load()
    }

    private fun waitFor(what: String, seconds: Int, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(200) }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun downloadSortStreamRestoreRemove() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val ctx = RuntimeEnvironment.getApplication()
        val tmp = Files.createTempDirectory("swarmtest").toFile()
        val base = File(tmp, "Download/Swarm")
        Prefs.init(ctx)
        Prefs.setS("baseDir", base.absolutePath); Prefs.setB("wifiOnly", false); Prefs.setI("vpnMode", 0); Prefs.setB("addTrackers", false); Prefs.setI("seedMode", 0)

        // --- the other side: a torrent made from a folder, being seeded
        val folder = File(tmp, "seed/My.Film.2020.1080p.WEB-DL").apply { mkdirs() }
        val film = File(folder, "My.Film.2020.1080p.mkv"); val data = Random(7).nextBytes(6 * 1024 * 1024 + 12345); film.writeBytes(data)
        File(folder, "info.txt").writeText("hello")
        val bytes = TorrentBuilder().path(folder).creator("test").generate().entry().bencode()
        val ti = TorrentInfo.bdecode(bytes)
        val seeder = SessionManager(false)
        val sp = SettingsPack().listenInterfaces("127.0.0.1:0"); sp.setEnableDht(false); sp.setEnableLsd(false)
        seeder.start(SessionParams(sp))
        try {
        seeder.download(ti, folder.parentFile)
        waitFor("seeder ready", 30) { seeder.find(ti.infoHash())?.status()?.isSeeding == true }
        val port = SessionHandle(seeder.swig()).listenPort
        val hash = ti.infoHash().toHex()
        val magnet = "magnet:?xt=urn:btih:$hash&dn=My.Film.2020.1080p.WEB-DL&x.pe=127.0.0.1:$port"
        println("seeder on port $port, hash $hash, pieces ${ti.numPieces()} x ${ti.pieceLength()}")

        // --- the app side
        Engine.start(ctx)
        waitFor("engine ready", 30) { Engine.ready.value }
        println("CP engine ready"); System.out.flush()

        // details without downloading
        val peek = Engine.peek(magnet, 25)
        println("CP peek returned $peek"); System.out.flush()
        assertNotNull("peek returned nothing", peek)
        assertEquals(2, peek!!.files.size)
        assertEquals("My.Film.2020.1080p.WEB-DL", peek.name)
        assertEquals(data.size + 5L, peek.size)
        assertFalse(Engine.has(hash))

        // download
        assertEquals(hash, Engine.addMagnet(magnet, "test"))
        assertTrue(Engine.has(hash))
        println("CP added"); System.out.flush()
        var topSpeed = 0L; var sawProgress = false; val seen = LinkedHashSet<String>()
        // Global limits do not apply to peers on the local network, so slow this one torrent to watch it part-way.
        waitFor("torrent in session", 10) { Engine.find(hash) != null }
        Engine.setLimits(hash, 1500, 0)
        assertEquals(1500, Engine.limits(hash).first)
        val tStart = System.currentTimeMillis()
        waitFor("download complete", 90) {
            topSpeed = maxOf(topSpeed, Engine.global.value.dl)
            Engine.torrents.value.firstOrNull { it.hash == hash }?.let { seen.add("${it.state} p=${(it.progress * 10).toInt()} eta=${it.eta >= 0} dl=${it.dl > 0} meta=${it.hasMeta}") }
            Engine.torrents.value.firstOrNull { it.hash == hash }?.let { if (it.progress > 0.05f && it.progress < 0.95f && it.state == TState.DOWNLOADING && it.eta >= 0) sawProgress = true }
            Engine.torrents.value.firstOrNull { it.hash == hash }?.completed == true
        }
        println("CP states seen: $seen")
        println("CP top speed seen ${Fmt.speed(topSpeed)}, saw mid-download progress: $sawProgress")
        assertTrue("live speed never showed", topSpeed > 100_000)
        assertTrue("speed limit not applied", topSpeed < 2_200_000)
        assertTrue("never saw a part-way state", sawProgress)
        val item = Engine.torrents.value.first { it.hash == hash }
        println("finished: state=${item.state} path=${item.savePath} name=${item.name}")
        assertEquals("My.Film.2020.1080p.WEB-DL", item.name)
        assertEquals(1f, item.progress, 0.0001f)

        // sorted into Movies, with the right bytes
        waitFor("sorted into Movies", 20) { File(base, "Movies/My.Film.2020.1080p.WEB-DL/My.Film.2020.1080p.mkv").let { it.exists() && it.length() == data.size.toLong() } }
        val got = File(base, "Movies/My.Film.2020.1080p.WEB-DL/My.Film.2020.1080p.mkv")
        assertTrue("content differs", got.readBytes().contentEquals(data))
        waitFor("save path updated", 10) { Engine.torrents.value.first { it.hash == hash }.savePath.endsWith("/Movies") }

        // seed mode 0 means it stops when complete
        waitFor("stops seeding", 10) { Engine.torrents.value.first { it.hash == hash }.state == TState.COMPLETE }

        // files / pieces / magnet / resume data
        val files = Engine.files(hash)
        assertEquals(2, files.size)
        assertTrue(files.all { it.done == it.size })
        assertTrue(Engine.pieceMap(hash, 50).all { it == 1f })
        assertTrue(Engine.magnetOf(hash).contains(hash))
        waitFor("resume file", 15) { File(ctx.filesDir, "resume/$hash.resume").length() > 100 }

        // what happens when the app is opened again: the saved resume file must load into a brand-new session,
        // find the files where they were sorted to, and be complete without downloading anything
        Thread.sleep(1500)
        val saved = Engine.paramsFromResume(File(ctx.filesDir, "resume/$hash.resume").readBytes())
        assertEquals(hash, saved.infoHashes.best.toHex())
        assertTrue("resume path ${saved.savePath}", saved.savePath.endsWith("/Movies"))
        val again = SessionManager(false)
        val sp2 = SettingsPack().listenInterfaces("127.0.0.1:0"); sp2.setEnableDht(false); sp2.setEnableLsd(false)
        again.start(SessionParams(sp2))
        try {
            SessionHandle(again.swig()).asyncAddTorrent(saved)
            waitFor("restored torrent is complete", 20) { again.find(ti.infoHash())?.status()?.let { it.hasMetadata() && it.totalWantedDone() == it.totalWanted() && it.totalWanted() > 0 && (it.isSeeding || it.isFinished) } == true }
            assertEquals(0L, again.find(ti.infoHash()).status().totalPayloadDownload())
            println("CP restore from resume file OK")
        } finally { again.stop() }

        // the player's reader over a finished file
        val idx = files.first { it.path.endsWith(".mkv") }.index
        val si = Engine.stream(hash, idx)!!
        assertEquals(data.size.toLong(), si.size)
        val ds = TorrentDataSource(si)
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.fromFile(si.file)).setPosition(1_000_000).build()
        assertEquals(data.size - 1_000_000L, ds.open(spec))
        val buf = ByteArray(300_000); var n = 0
        while (n < buf.size) { val r = ds.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        ds.close()
        assertEquals(buf.size, n)
        assertTrue("stream bytes differ", buf.contentEquals(data.copyOfRange(1_000_000, 1_300_000)))

        // pause / resume by the user
        Engine.pause(hash)
        waitFor("paused", 10) { Engine.torrents.value.first { it.hash == hash }.state == TState.PAUSED }
        Engine.resume(hash)
        waitFor("seeding after manual resume", 15) { Engine.torrents.value.first { it.hash == hash }.state == TState.SEEDING }

        // remove with files
        Engine.remove(hash, true)
        waitFor("removed", 15) { Engine.torrents.value.none { it.hash == hash } && !got.exists() }
        assertFalse(File(ctx.filesDir, "resume/$hash.resume").exists())
        assertFalse(Engine.has(hash))

        // a .torrent file added directly goes straight to its sorted folder and can stream while downloading
        Prefs.setI("seedMode", 2)
        assertEquals(hash, Engine.addTorrentBytes(bytes + ByteArray(0), "test"))
        val h = Engine.find(hash)
        waitFor("handle", 10) { Engine.find(hash) != null }
        Engine.find(hash)!!.swig().connect_peer(org.libtorrent4j.TcpEndpoint("127.0.0.1", port).swig())
        val si2 = run { waitFor("metadata", 10) { Engine.stream(hash, idx) != null }; Engine.stream(hash, idx)!! }
        assertTrue(si2.file.absolutePath.contains("/Movies/"))
        val ds2 = TorrentDataSource(si2)
        val tail = data.size - 200_000L
        assertEquals(200_000L, ds2.open(androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.fromFile(si2.file)).setPosition(tail).build()))
        val b2 = ByteArray(200_000); var m = 0
        while (m < b2.size) { val r = ds2.read(b2, m, b2.size - m); if (r < 0) break; m += r }
        ds2.close()
        assertTrue("streamed tail differs", b2.contentEquals(data.copyOfRange(tail.toInt(), data.size)))
        waitFor("second download complete", 90) { Engine.torrents.value.firstOrNull { it.hash == hash }?.completed == true }
        waitFor("keeps seeding in mode 2", 10) { Engine.torrents.value.first { it.hash == hash }.state == TState.SEEDING }
        Engine.remove(hash, true)
        println("ENGINE TEST OK ${h == null}"); System.out.flush()
        } finally {
            try { seeder.stop() } catch (t: Throwable) { t.printStackTrace() }
        }
    }

    /**
     * The crawler's fetch-and-index step and the search index, using a second session on this machine as the swarm.
     * (Finding hashes by sampling the live DHT needs the real network, which a test machine does not have.)
     */
    private fun crawlerIndexesAndSearches() {
        val ctx = RuntimeEnvironment.getApplication()
        val tmp = Files.createTempDirectory("swarmdht").toFile()
        val db = Db.get(ctx)
        fun make(name: String, file: String): Triple<TorrentInfo, File, String> {
            val folder = File(tmp, "seed/$name").apply { mkdirs() }
            File(folder, file).writeBytes(Random(name.hashCode()).nextBytes(400_000)); File(folder, "readme.txt").writeText("free to share")
            val ti = TorrentInfo.bdecode(TorrentBuilder().path(folder).creator("test").generate().entry().bencode())
            return Triple(ti, folder.parentFile!!, ti.infoHash().toHex())
        }
        val (ti, dir, hash) = make("Open.Documentary.2019.720p", "Open.Documentary.2019.720p.mp4")
        val (ti2, dir2, hash2) = make("Some.XXX.Clip", "clip.mp4")
        val peer = SessionManager(false)
        val sp = SettingsPack().listenInterfaces("127.0.0.1:0"); sp.setEnableDht(false); sp.setEnableLsd(false)
        peer.start(SessionParams(sp))
        try {
            peer.download(ti, dir); peer.download(ti2, dir2)
            waitFor("peer seeding", 30) { peer.find(ti.infoHash())?.status()?.isSeeding == true && peer.find(ti2.infoHash())?.status()?.isSeeding == true }
            val port = SessionHandle(peer.swig()).listenPort
            Prefs.setB("adultFilter", true)

            assertTrue(uk.jj.swarm.engine.Crawler.indexOne(db, hash, "magnet:?xt=urn:btih:$hash&x.pe=127.0.0.1:$port"))
            val row = db.dhtGet(hash)!!
            println("CP indexed: ${row.name} size=${row.size} files=${row.nfiles}")
            assertEquals("Open.Documentary.2019.720p", row.name)
            assertEquals(2, row.nfiles)
            assertEquals(400_000L + 13, row.size)
            assertTrue(row.files.contains("readme.txt\t13"))
            assertTrue(row.firstSeen > System.currentTimeMillis() - 60000)
            assertFalse("already indexed, so not fetched twice", uk.jj.swarm.engine.Crawler.indexOne(db, hash, "magnet:?xt=urn:btih:$hash&x.pe=127.0.0.1:$port"))
            assertFalse("crawler must not leave the torrent in the session", Engine.has(hash))
            waitFor("temporary torrent removed", 10) { Engine.sm.find(ti.infoHash()) == null }

            // adult names are kept out, and not retried
            assertFalse(uk.jj.swarm.engine.Crawler.indexOne(db, hash2, "magnet:?xt=urn:btih:$hash2&x.pe=127.0.0.1:$port"))
            assertFalse(db.dhtHas(hash2)); assertEquals(2, db.dhtTries(hash2))

            // a hash nobody has: marked as tried, never indexed
            val dead = "00000000000000000000000000000000000000aa"
            Thread { }.start()
            // keyword search over the index
            assertEquals(hash, db.dhtSearch("documentary 2019", 0, 10).single().hash)
            assertEquals(hash, db.dhtSearch("open docu", 0, 10).single().hash)
            assertEquals(hash, db.dhtSearch("readme", 1, 10).single().hash)
            assertTrue(db.dhtSearch("nothing-like-this", 0, 10).isEmpty())
            assertEquals(hash, db.dhtSearch("", 0, 10).single().hash)
            for (i in 1..30) db.dhtInsert("%040x".format(i), "Bulk Item Number $i Linux ISO", 1000L * i, 1, "bulk$i.iso\t${1000L * i}\n")
            assertEquals(31, db.dhtCount())
            assertEquals(30, db.dhtSearch("linux iso", 2, 50).size)
            assertEquals(30000L, db.dhtSearch("linux iso", 2, 50).first().size)
            assertEquals(listOf(hash), db.dhtNeedScrape(100).takeLast(1))
            db.dhtSetScrape(hash, 42, 7)
            assertEquals(42, db.dhtGet(hash)!!.seeders); assertEquals(7, db.dhtGet(hash)!!.leechers)
            assertEquals(hash, db.dhtSearch("iso documentary".substringAfter(' '), 1, 10).first().hash)
            db.dhtPrune(10)
            assertEquals(10, db.dhtCount())
            assertEquals(10, db.dhtSearch("", 0, 50).size)
            assertTrue(db.dhtSearch("linux iso", 0, 50).size <= 10)
            db.dhtClear(); assertEquals(0, db.dhtCount()); assertTrue(db.dhtSearch("linux", 0, 5).isEmpty())
            assertFalse(db.dhtHas(dead))
            // The live-DHT calls the crawler makes must at least be safe with nothing to talk to.
            uk.jj.swarm.engine.Crawler.refill()
            Engine.sm.swig().dht_sample_infohashes(org.libtorrent4j.UdpEndpoint("127.0.0.1", 9).swig(), org.libtorrent4j.Sha1Hash.fromBytes(Random.nextBytes(20)).swig())
            assertEquals(-1, Engine.dhtPeers("zz", 1))
            Thread.sleep(500)
            println("CRAWLER INDEX TEST OK")
        } finally { try { peer.stop() } catch (t: Throwable) { } }
    }

    /** One test method on purpose: the engine is a single long-lived object, like in the app. */
    @Test fun engineEndToEnd() {
        try {
            downloadSortStreamRestoreRemove()
            crawlerIndexesAndSearches()
            println("ALL ENGINE CHECKS PASSED"); System.out.flush()
        } catch (t: Throwable) {
            println("ENGINE CHECK FAILED: $t"); t.printStackTrace(System.out); System.out.flush(); throw t
        } finally {
            Engine.stopSession()
            Thread.sleep(500)
            try { Db.get(RuntimeEnvironment.getApplication()).close() } catch (t: Throwable) { }
            println("ENGINE STOPPED CLEANLY"); System.out.flush()
            Thread.sleep(500)
        }
    }
}
