package uk.jj.swarm

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
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
import uk.jj.swarm.engine.StreamInfo
import uk.jj.swarm.play.TorrentDataSource
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Plays a film while it downloads, the way the video player does: read the start, jump to the index at the
 * end, come back, jump to the middle, then read on. The download is held to 2.5 MB/s on this side, which is
 * the case that used to leave the player waiting a minute or more for a piece.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamTest {
    companion object { @BeforeClass @JvmStatic fun native() = NativeLib.load() }

    private fun waitFor(what: String, seconds: Int, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(200) }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun read(si: StreamInfo, pos: Long, len: Int, data: ByteArray, label: String) {
        val t = System.currentTimeMillis()
        val ds = TorrentDataSource(si)
        ds.open(androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.fromFile(si.file)).setPosition(pos).build())
        val buf = ByteArray(len); var n = 0
        while (n < len) { val r = ds.read(buf, n, len - n); if (r < 0) break; n += r }
        ds.close()
        val took = System.currentTimeMillis() - t
        println("STREAM $label: $took ms"); System.out.flush()
        assertTrue("$label: wrong bytes", buf.contentEquals(data.copyOfRange(pos.toInt(), pos.toInt() + len)))
        assertTrue("$label took $took ms", took < 30_000)
    }

    @Test fun playsWhileDownloading() {
        val ctx = RuntimeEnvironment.getApplication()
        val tmp = Files.createTempDirectory("swarmstream").toFile()
        Prefs.init(ctx)
        Prefs.setS("baseDir", File(tmp, "Download/Swarm").absolutePath); Prefs.setB("wifiOnly", false); Prefs.setI("vpnMode", 0); Prefs.setB("addTrackers", false); Prefs.setI("seedMode", 0); Prefs.setI("crawlMode", 0)
        val folder = File(tmp, "seed/Big.Film.2021.1080p").apply { mkdirs() }
        val data = Random(11).nextBytes(120 * 1024 * 1024 + 4321)
        File(folder, "Big.Film.2021.1080p.mp4").writeBytes(data)
        val ti = TorrentInfo.bdecode(TorrentBuilder().path(folder).pieceSize(1 shl 20).creator("test").generate().entry().bencode())
        val seeder = SessionManager(false)
        val sp = SettingsPack().listenInterfaces("127.0.0.1:0"); sp.setEnableDht(false); sp.setEnableLsd(false)
        seeder.start(SessionParams(sp))
        try {
            seeder.download(ti, folder.parentFile)
            waitFor("seeder ready", 60) { seeder.find(ti.infoHash())?.status()?.isSeeding == true }
            val port = SessionHandle(seeder.swig()).listenPort
            val hash = ti.infoHash().toHex()
            Engine.start(ctx)
            waitFor("engine ready", 30) { Engine.ready.value }
            Engine.addMagnet("magnet:?xt=urn:btih:$hash&dn=Big.Film&x.pe=127.0.0.1:$port", "test")
            waitFor("torrent in session", 10) { Engine.find(hash) != null }
            Engine.setLimits(hash, 2500, 0)
            waitFor("a tenth downloaded", 120) { (Engine.torrents.value.firstOrNull { it.hash == hash }?.progress ?: 0f) >= 0.10f }

            val si = Engine.stream(hash, 0)!!
            read(si, 0, 512 * 1024, data, "start, 512 KB")
            read(si, data.size - 3L * 1024 * 1024, 3 * 1024 * 1024, data, "end, 3 MB")
            read(si, 600_000, 4 * 1024 * 1024, data, "back near the start, 4 MB")
            read(si, 60L * 1024 * 1024, 1024 * 1024, data, "jump to the middle, 1 MB")
            read(si, 5L * 1024 * 1024, 20 * 1024 * 1024, data, "read on, 20 MB")
            assertTrue("the download itself stalled", (Engine.torrents.value.first { it.hash == hash }.dl) > 200_000)
        } finally { try { seeder.stop() } catch (t: Throwable) { }; Engine.stopSession() }
    }
}
