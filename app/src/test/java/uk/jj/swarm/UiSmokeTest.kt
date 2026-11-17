package uk.jj.swarm

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.search.Hit
import uk.jj.swarm.ui.SearchState

/** Opens every screen of the real app (with the real engine running) to prove none of them crash when drawn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)   // real hit-testing for rounded corners
class UiSmokeTest {
    companion object { @BeforeClass @JvmStatic fun native() = NativeLib.load() }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun see(text: String) {
        rule.waitUntil(15000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Saves a picture of the screen when run with -Pshots=<folder>. */
    private fun shot(name: String) {
        val dir = System.getProperty("swarm.shots") ?: return
        try {
            rule.waitForIdle()
            rule.runOnUiThread {
                val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
                val v = dlg?.window?.decorView ?: rule.activity.window.decorView
                val bmp = android.graphics.Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), android.graphics.Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bmp))
                java.io.File(dir).mkdirs()
                java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (t: Throwable) { println("shot $name failed: $t") }
    }

    private fun go(route: String, expect: String) { Ui.go.value = route; rule.waitForIdle(); see(expect); println("SCREEN OK: $route") }
    private fun back() { Ui.go.value = "back"; rule.waitForIdle() }

    @Test fun everyScreenOpens() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        try {
            // first run
            see("Welcome to Swarm"); shot("01-welcome")
            rule.onNodeWithText("Done").performClick()
            see("Looks like you need to download some content."); shot("02-torrents-empty")
            assertTrue(Prefs.onboarded)
            rule.waitUntil(30000) { Engine.ready.value }

            // the five tabs
            Ui.tab.value = 1; rule.waitForIdle(); see("Latest from your sources"); see("2 sources switched on"); shot("03-search-start")
            Ui.tab.value = 2; rule.waitForIdle(); see("Your DHT index is empty so far."); see("0 torrents in your index"); shot("04-dht-empty")
            Ui.tab.value = 3; rule.waitForIdle(); see("Swarm needs permission to list your videos.")
            Ui.tab.value = 4; rule.waitForIdle(); see("Swarm needs permission to list your music.")
            Ui.tab.value = 0; rule.waitForIdle(); see("Torrents (0)")
            println("TABS OK")

            // (Dialogs that contain a text box are not opened here: under this desktop test host a text box inside a
            // dialog never reports itself as settled, even in a bare stock dialog, so the test would wait for ever.)

            // a torrent in the list, then its detail screen and tabs
            val hash = "0123456789abcdef0123456789abcdef01234567"
            assertEquals(hash, Engine.addMagnet("magnet:?xt=urn:btih:$hash&dn=Smoke+Test+Torrent"))
            see("Smoke Test Torrent"); see("Torrents (1)"); shot("05-torrents-one")
            // tap the row: detail screen, and each of its tabs really switches
            rule.onNodeWithText("Smoke Test Torrent").performClick(); rule.waitForIdle()
            see("Info-hash"); see("Play now"); shot("06-detail-info")
            rule.onNodeWithText("Files").performClick(); see("The file list appears once")
            rule.onNodeWithText("Peers").performClick(); see("No peers connected right now.")
            rule.onNodeWithText("Trackers").performClick(); see("DHT and peer exchange are also used.")
            rule.onNodeWithText("Info").performClick(); see("Info-hash")
            rule.onNodeWithContentDescription("More").performClick(); see("Copy magnet link"); rule.onNodeWithText("Show QR code").performClick(); see("Scan to add this torrent"); shot("07-qr"); rule.onNodeWithText("Close").performClick()
            rule.onNodeWithContentDescription("Pause or resume").performClick()
            rule.waitUntil(10000) { Engine.torrents.value.firstOrNull()?.state == uk.jj.swarm.engine.TState.PAUSED }
            back(); see("Torrents (1)"); see("Paused"); see("Resume All")
            rule.onNodeWithText("Resume All").performClick()
            rule.waitUntil(10000) { Engine.torrents.value.firstOrNull()?.state != uk.jj.swarm.engine.TState.PAUSED }
            println("DETAIL OK")

            // sub-tabs filter the list
            rule.onNodeWithText("Complete").performClick(); see("Complete (0)")
            rule.onNodeWithText("Downloading").performClick(); see("Downloading (1)")
            rule.onNodeWithText("All").performClick(); see("Torrents (1)")

            // press-and-hold starts selection; back leaves it
            rule.onNodeWithText("Smoke Test Torrent").performTouchInput { longClick() }; rule.waitForIdle()
            see("1 Torrent"); rule.onNodeWithText("Cancel All").performClick(); rule.waitForIdle(); see("0 Torrents")
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle(); see("Torrents (1)")

            // selection mode, share/delete bar and the delete dialog
            rule.onNodeWithContentDescription("Select").performClick(); see("0 Torrents")
            rule.onNodeWithText("Smoke Test Torrent").performClick(); see("1 Torrent"); shot("08-select")
            rule.onNodeWithText("Delete").performClick(); see("Are you sure to delete 1 torrent?"); see("And downloaded files"); shot("09-delete-dialog")
            rule.onNodeWithText("YES").performClick()
            see("Looks like you need to download some content.")
            assertTrue(Engine.torrents.value.isEmpty())
            println("LIST OK")

            // bottom bar by touch
            rule.onNodeWithText("DHT").performClick(); see("Your DHT index is empty so far.")
            rule.onNodeWithText("Video").performClick(); see("Swarm needs permission to list your videos.")
            rule.onNodeWithText("Torrents").performClick(); see("Torrents (0)")
            rule.onNodeWithText("Start Downloading").performClick(); see("Latest from your sources")
            Ui.tab.value = 0; rule.waitForIdle()

            // search results and the result screen
            val hit = Hit("Internet Archive", "An Open Film 1080p", "magnet:?xt=urn:btih:$hash", null, hash, 2L * 1024 * 1024 * 1024, 678, 261, System.currentTimeMillis() - 4 * 86400000L, "https://archive.org/details/x")
            rule.runOnUiThread { SearchState.hits = listOf(hit); SearchState.searched = true; SearchState.query = "open film"; SearchState.loading = false }
            Ui.tab.value = 1; rule.waitForIdle(); see("1 result"); see("An Open Film 1080p"); see("▲ 678"); see("▼ 261"); see("4 d ago"); shot("10-search-results")
            rule.onNodeWithText("An Open Film 1080p").performClick(); rule.waitForIdle()
            see("Leechers"); see("678"); see("Internet Archive"); see("Download"); shot("11-result"); println("SCREEN OK: result")
            back()
            rule.runOnUiThread { SearchState.searched = false; SearchState.hits = emptyList() }

            // DHT index rows
            Db.get(rule.activity).dhtInsert("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ubuntu-26.10-desktop-amd64.iso", 6_000_000_000, 1, "ubuntu-26.10-desktop-amd64.iso\t6000000000\n")
            Ui.tab.value = 0; rule.waitForIdle(); Ui.tab.value = 2; rule.waitForIdle(); see("ubuntu-26.10-desktop-amd64.iso"); see("1 torrent in your index"); shot("12-dht-row")
            Ui.tab.value = 0; rule.waitForIdle()

            // every side-menu screen
            go("settings", "Bandwidth"); see("Wifi Networks Only"); see("Use system default"); shot("13-settings")
            rule.onNodeWithText("Dark").performClick(); rule.waitForIdle(); assertEquals(1, Prefs.theme.value); shot("14-settings-dark")
            back(); rule.waitForIdle(); shot("15-torrents-dark"); Ui.go.value = "settings"; rule.waitForIdle(); see("Bandwidth")
            rule.onNodeWithText("Light").performClick(); rule.waitForIdle(); assertEquals(0, Prefs.theme.value)
            back()
            go("sources", "Built in"); see("Internet Archive"); see("Linux Tracker"); shot("16-sources")
            back()
            go("rss", "No feeds yet."); back()
            go("watch", "Nothing on your watchlist."); back()
            go("create", "Create and seed"); back()
            go("about", "Open source licences"); see("Save a backup file"); shot("17-about"); back()
            go("browser", "Browser"); back()
            see("Torrents (0)")
            println("ALL SCREENS OPENED")
        } finally {
            Engine.stopSession()
            Thread.sleep(300)
        }
    }
}
