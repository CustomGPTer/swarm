package uk.jj.swarm

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.engine.TItem
import uk.jj.swarm.engine.TState
import uk.jj.swarm.search.Hit
import uk.jj.swarm.ui.SearchState

/** Draws the app's screens with sample content, for the pictures on the website. Run with -Pshots=<folder>. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SiteShotsTest {
    companion object { @BeforeClass @JvmStatic fun native() = NativeLib.load() }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun see(text: String) {
        rule.waitUntil(15000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        val dir = System.getProperty("swarm.shots") ?: return
        rule.waitForIdle()
        rule.runOnUiThread {
            val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            val v = dlg?.window?.decorView ?: rule.activity.window.decorView
            val bmp = android.graphics.Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            java.io.File(dir).mkdirs()
            java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024
    private fun h(n: Int) = java.security.MessageDigest.getInstance("SHA-1").digest("sample-$n".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun item(n: Int, name: String, state: TState, progress: Float, total: Long, dl: Int = 0, ul: Int = 0, seeds: Int = 0, peers: Int = 0, eta: Long = -1, ratio: Float = 0f) =
        TItem(h(n), name, state, progress, (total * progress).toLong(), total, dl, ul, seeds, peers, eta, (total * ratio).toLong(), ratio,
            System.currentTimeMillis() - n * 3600_000L, "/storage/emulated/0/Download/Swarm", null, true, false, progress >= 1f, seeds * 9, peers * 4)

    private fun sample() = listOf(
        item(1, "ubuntu-26.10-desktop-amd64.iso", TState.DOWNLOADING, 0.62f, (5.9 * gb).toLong(), dl = 4_400_000, ul = 310_000, seeds = 41, peers = 12, eta = 8 * 60 + 40),
        item(2, "Sintel (2010) 4K", TState.DOWNLOADING, 0.18f, (6.7 * gb).toLong(), dl = 2_100_000, ul = 90_000, seeds = 17, peers = 6, eta = 43 * 60),
        item(3, "Big Buck Bunny (2008) 1080p", TState.SEEDING, 1f, 886 * mb, ul = 240_000, seeds = 0, peers = 5, ratio = 0.7f),
        item(4, "debian-13.2.0-amd64-netinst.iso", TState.PAUSED, 0.34f, 754 * mb),
        item(5, "Pride and Prejudice (LibriVox audiobook)", TState.COMPLETE, 1f, 312 * mb, ratio = 1.0f),
        item(6, "Tears of Steel (2012) 1080p", TState.QUEUED, 0f, (1.4 * gb).toLong()),
    )

    private fun hit(src: String, title: String, n: Int, size: Double, s: Int, l: Int, days: Int, page: String) =
        Hit(src, title, "magnet:?xt=urn:btih:${h(100 + n)}", null, h(100 + n), (size * gb).toLong(), s, l, System.currentTimeMillis() - days * 86400000L, page)

    @Test fun pictures() {
        try {
            see("Welcome to Swarm"); shot("welcome")
            rule.onNodeWithText("Done").performClick()
            see("Looks like you need to download some content.")
            rule.waitUntil(30000) { Engine.ready.value }
            Engine.stopSession()
            Engine.vpnWarn.value = false

            // torrents
            Engine.torrents.value = sample(); rule.waitForIdle()
            see("Torrents (6)"); shot("torrents")
            rule.onNodeWithText("ubuntu-26.10-desktop-amd64.iso").performClick(); rule.waitForIdle()
            see("Info-hash"); shot("detail")
            Ui.go.value = "back"; rule.waitForIdle(); see("Torrents (6)")

            // search
            val hits = listOf(
                hit("Linux Tracker", "ubuntu-26.10-desktop-amd64.iso", 1, 5.9, 2143, 87, 26, "https://linuxtracker.org/"),
                hit("Linux Tracker", "linuxmint-22.3-cinnamon-64bit.iso", 2, 2.9, 1210, 44, 61, "https://linuxtracker.org/"),
                hit("Linux Tracker", "debian-13.2.0-amd64-DVD-1.iso", 3, 3.8, 964, 31, 12, "https://linuxtracker.org/"),
                hit("Linux Tracker", "Fedora-Workstation-Live-43-1.6.x86_64.iso", 4, 2.4, 612, 29, 34, "https://linuxtracker.org/"),
                hit("Internet Archive", "Linux Format magazine, issue 1 (2000)", 5, 0.09, 38, 2, 2190, "https://archive.org/"),
                hit("Linux Tracker", "archlinux-2026.11.01-x86_64.iso", 6, 1.3, 548, 12, 16, "https://linuxtracker.org/"),
                hit("Linux Tracker", "openSUSE-Leap-16.0-DVD-x86_64.iso", 7, 4.4, 301, 18, 47, "https://linuxtracker.org/"),
                hit("Internet Archive", "Revolution OS (2001 documentary)", 8, 0.7, 26, 1, 1460, "https://archive.org/"),
            )
            rule.runOnUiThread { SearchState.hits = hits; SearchState.searched = true; SearchState.query = "linux"; SearchState.loading = false }
            Ui.tab.value = 1; rule.waitForIdle(); see("8 results"); shot("search")
            rule.onNodeWithText("ubuntu-26.10-desktop-amd64.iso").performClick(); rule.waitForIdle()
            see("Leechers"); shot("result")
            Ui.go.value = "back"; rule.waitForIdle()

            // DHT index
            val db = Db.get(rule.activity)
            val found = listOf(
                Triple("Elephants Dream (2006) 1080p", 815 * mb, 3), Triple("kali-linux-2026.3-installer-amd64.iso", (4.1 * gb).toLong(), 1),
                Triple("LibreOffice_26.2.1_Win_x86-64.msi", 362 * mb, 1), Triple("wikipedia_en_top_maxi_2026-10.zim", (7.3 * gb).toLong(), 1),
                Triple("Cosmos Laundromat (2015) 2K", (1.1 * gb).toLong(), 4), Triple("manjaro-kde-25.1-linux612.iso", (4.3 * gb).toLong(), 1),
                Triple("Blender 4.5 demo files", (2.2 * gb).toLong(), 148), Triple("Project Gutenberg, top 100 EPUBs", 96 * mb, 100),
                Triple("tails-amd64-7.2.img", (1.6 * gb).toLong(), 1), Triple("Spring (2019) open movie 4K", (3.0 * gb).toLong(), 6),
            )
            found.forEachIndexed { i, (name, size, n) ->
                db.dhtInsert(h(200 + i), name, size, n, "$name\t$size\n")
                db.dhtSetScrape(h(200 + i), listOf(412, 233, 57, 91, 148, 320, 19, 44, 276, 63)[i], listOf(9, 14, 2, 6, 11, 21, 1, 0, 8, 5)[i])
            }
            Ui.tab.value = 0; rule.waitForIdle(); Ui.tab.value = 2; rule.waitForIdle(); see("10 torrents in your index"); shot("dht")
            Ui.tab.value = 0; rule.waitForIdle()

            // side-menu screens
            Ui.go.value = "settings"; rule.waitForIdle(); see("Bandwidth"); shot("settings")
            rule.onNodeWithText("Dark").performClick(); rule.waitForIdle(); shot("settings-dark")
            Ui.go.value = "back"; rule.waitForIdle(); see("Torrents (6)"); shot("torrents-dark")
            rule.runOnUiThread { SearchState.hits = hits; SearchState.searched = true; SearchState.query = "linux" }
            Ui.tab.value = 1; rule.waitForIdle(); see("8 results"); shot("search-dark")
            Ui.tab.value = 0; rule.waitForIdle()
            Ui.go.value = "settings"; rule.waitForIdle(); see("Bandwidth"); rule.onNodeWithText("Light").performClick(); rule.waitForIdle()
            Ui.go.value = "back"; rule.waitForIdle()
            Ui.go.value = "sources"; rule.waitForIdle(); see("Built in"); shot("sources"); Ui.go.value = "back"; rule.waitForIdle()
            Ui.go.value = "about"; rule.waitForIdle(); see("Open source licences"); shot("about"); Ui.go.value = "back"; rule.waitForIdle()
        } finally {
            Engine.stopSession()
            Thread.sleep(300)
        }
    }
}
