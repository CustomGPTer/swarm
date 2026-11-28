package uk.jj.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.jj.swarm.engine.Scrape
import uk.jj.swarm.search.ArchiveProvider
import uk.jj.swarm.search.Rss
import uk.jj.swarm.search.Safety
import uk.jj.swarm.search.SiteReader
import uk.jj.swarm.search.TorznabProvider
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

class LogicTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val NOW = 1_800_000_000_000L
    private val H1 = "0123456789abcdef0123456789abcdef01234567"
    private val H2 = "89abcdef0123456789abcdef0123456789abcdef"
    private val H3 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    // ---------------------------------------------------------------- util

    @Test fun magnetHash() {
        assertEquals(H1, Magnet.hash("magnet:?xt=urn:btih:${H1.uppercase()}&dn=Some+Name&tr=udp%3A%2F%2Fx"))
        assertEquals("Some Name", Magnet.name("magnet:?xt=urn:btih:$H1&dn=Some+Name&tr=x"))
        assertNull(Magnet.hash("magnet:?xt=urn:btmh:1220abcd"))
        // base32 form of 20 zero bytes
        assertEquals("0".repeat(40), Magnet.hash("magnet:?xt=urn:btih:" + "A".repeat(32)))
        assertEquals("ff".repeat(20), Magnet.hash("magnet:?xt=urn:btih:" + "7".repeat(32)))
        assertTrue(Magnet.build(H1, "a b", listOf("udp://t:1/announce")).startsWith("magnet:?xt=urn:btih:$H1&dn=a+b&tr=udp%3A%2F%2Ft%3A1%2Fannounce"))
    }

    @Test fun formatting() {
        assertEquals("66 M", Fmt.size(66L * 1024 * 1024))
        assertEquals("2 G", Fmt.size(2L * 1024 * 1024 * 1024))
        assertEquals("5.9 M", Fmt.size((5.9 * 1024 * 1024).toLong()))
        assertEquals("7 M/s", Fmt.speed(7L * 1024 * 1024))
        assertEquals("4m 37s", Fmt.eta(277))
        assertEquals("4 d ago", Fmt.age(1_000_000_000_000L - 4 * 86400000L, 1_000_000_000_000L))
        assertEquals("1:02:03", Fmt.time(3723000))
    }

    @Test fun ftsAndKinds() {
        assertEquals("never surrender 2026*", ftsQuery("Never.Surrender  2026"))
        assertNull(ftsQuery("  ... "))
        assertEquals("Movies", Kind.folder("Some Film 2020 1080p", "film.mkv"))
        assertEquals("TV", Kind.folder("Show.S02E05.720p", "show.s02e05.mkv"))
        assertEquals("Music", Kind.folder("Album", "01 track.flac"))
        assertEquals("Books", Kind.folder("Book", "book.epub"))
        assertEquals("Other", Kind.folder("ubuntu", "ubuntu.iso"))
    }

    // ---------------------------------------------------------------- site reader

    @Test fun sizesAndDates() {
        assertEquals(1503238553L, SiteReader.parseSize("Size 1.4 GB, uploaded"))
        assertEquals(700L * 1024 * 1024, SiteReader.parseSize("700 MiB"))
        assertEquals(-1L, SiteReader.parseSize("no size here"))
        val now = 1_800_000_000_000L
        assertEquals(now - 3 * 86400000L, SiteReader.parseAge("3 days ago", now))
        assertEquals(now - 2 * 3600000L, SiteReader.parseAge("2 hours ago", now))
        assertEquals(1792022400000L, SiteReader.parseAge("Oct 15, 2026", now))
        assertEquals(1792022400000L, SiteReader.parseAge("15 Oct 2026", now))
        assertEquals(1792022400000L, SiteReader.parseAge("2026-10-15", now))
        assertEquals(0L, SiteReader.parseAge("nothing", now))
    }

    @Test fun tableWithMagnets() {
        val html = """<html><body><div id=nav><a href="/top">Top torrents of the week</a></div>
          <table><tr><th>Name</th><th>Size</th><th>Added</th><th>SE</th><th>LE</th></tr>
          <tr><td><a href="/t/1/first-thing">First Thing 1080p WEB-DL</a> <a href="magnet:?xt=urn:btih:$H1&dn=First.Thing"><img src=m.png></a></td><td>2.1 GB</td><td>4 days ago</td><td>678</td><td>261</td></tr>
          <tr><td><a href="/t/2/second-thing">Second Thing Complete</a> <a href="magnet:?xt=urn:btih:$H2"><img src=m.png></a> <a href="/dl/2.torrent">torrent</a></td><td>700 MB</td><td>2026-11-01</td><td>12</td><td>3</td></tr>
          </table></body></html>"""
        val hits = SiteReader.parse(html, "https://example.org/search?q=x", "Example", 1_800_000_000_000L)
        assertEquals(2, hits.size)
        assertEquals("First Thing 1080p WEB-DL", hits[0].title)
        assertEquals(H1, hits[0].hash)
        assertEquals(678, hits[0].seeders); assertEquals(261, hits[0].leechers)
        assertEquals((2.1 * 1073741824).toLong(), hits[0].size)
        assertEquals(1_800_000_000_000L - 4 * 86400000L, hits[0].published)
        assertEquals("https://example.org/t/1/first-thing", hits[0].page)
        assertEquals("Second Thing Complete", hits[1].title)
        assertEquals("https://example.org/dl/2.torrent", hits[1].torrentUrl)
        assertEquals(12, hits[1].seeders); assertEquals(3, hits[1].leechers)
    }

    @Test fun divLayoutWithClasses() {
        val html = """<body><ul class=results>
          <li><div class=name><a href="/item/9">An Open Movie (2008)</a></div><span class="size">1.2 GiB</span>
              <span class="seeders">45</span><span class="leechers">7</span><a class=dl href="magnet:?xt=urn:btih:$H1&dn=An+Open+Movie">Magnet</a></li>
          <li><div class=name><a href="/item/10">Another Open Film</a></div><span class="size">350 MB</span>
              <span class="seeders">0</span><span class="leechers">1</span><a class=dl href="magnet:?xt=urn:btih:$H2">Magnet</a></li>
          <li><div class=name><a href="/item/11">Third One Here</a></div><span>Seeders: 9 Leechers: 2</span><a class=dl href="magnet:?xt=urn:btih:$H3">Magnet</a></li>
        </ul></body>"""
        val hits = SiteReader.parse(html, "https://example.org/", "Example")
        assertEquals(3, hits.size)
        assertEquals("An Open Movie (2008)", hits[0].title)
        assertEquals(45, hits[0].seeders); assertEquals(7, hits[0].leechers)
        assertEquals(0, hits[1].seeders)
        assertEquals(9, hits[2].seeders); assertEquals(2, hits[2].leechers)
    }

    @Test fun realListingWithHashLinks() {
        val hits = SiteReader.parse(res("lt.html"), "https://linuxtracker.org/torrents/?q=ubuntu", "Linux Tracker")
        assertTrue("found ${hits.size}", hits.size >= 10)
        val h = hits.first { it.hash == "f3cd8b82a4f96c36da018a503ea1f09fd9bdb7a1" }
        assertEquals("ubuntustudio-26.10-desktop-amd64.iso", h.title)
        assertEquals(11, h.seeders); assertEquals(0, h.leechers)
        assertEquals((6.4 * 1073741824).toLong(), h.size)
        assertEquals(1792022400000L, h.published)
        assertTrue(h.magnet!!.startsWith("magnet:?xt=urn:btih:f3cd8b82"))
        assertEquals("https://linuxtracker.org/torrents/f3cd8b82a4f96c36da018a503ea1f09fd9bdb7a1/", h.page)
        assertTrue(hits.all { it.title.length > 5 && it.size > 0 && it.seeders >= 0 })
    }

    @Test fun realDetailPage() {
        val d = SiteReader.detail(res("ltd.html"), "https://linuxtracker.org/torrents/f3cd8b82a4f96c36da018a503ea1f09fd9bdb7a1/", "ubuntustudio", "Linux Tracker")
        assertNotNull(d)
        assertEquals("f3cd8b82a4f96c36da018a503ea1f09fd9bdb7a1", d!!.hash)
        assertEquals("https://linuxtracker.org/torrents/f3cd8b82a4f96c36da018a503ea1f09fd9bdb7a1/download/", d.torrentUrl)
    }

    @Test fun candidatesAndTemplate() {
        val html = StringBuilder("<body><a href='/about'>About this website here</a>")
        for (i in 1..8) html.append("<div><a href='/torrent/$i/some-long-name-$i'>Some Long Torrent Name Number $i</a></div>")
        html.append("</body>")
        val c = SiteReader.candidates(html.toString(), "https://example.org/search?q=x")
        assertEquals(8, c.size)
        assertEquals("https://example.org/torrent/1/some-long-name-1", c[0].second)
        assertEquals("https://example.org/search?q={query}&page=1", SiteReader.toTemplate("https://example.org/search?q=test&page=1"))
        assertEquals("https://testsite.org/s/{query}/", SiteReader.toTemplate("https://testsite.org/s/test/"))
        assertNull(SiteReader.toTemplate("https://example.org/"))
        assertTrue(SiteReader.looksBlocked(403, "x"))
        assertTrue(SiteReader.looksBlocked(200, "<title>Just a moment...</title>"))
        assertFalse(SiteReader.looksBlocked(200, "<html>" + "x".repeat(7000)))
    }

    // ---------------------------------------------------------------- torznab / archive / rss

    @Test fun listingWithoutDownloadLinks() {
        fun card(n: Int, title: String, size: String, seeds: Int, age: String) = """
            <div class="card"><div class="head"><a href="/item/$n-${title.lowercase().replace(' ', '-')}/">$title</a></div>
              <div class="meta">Posted in <a href="/browse/?cat=music">Music - MP3</a>
                <span class="text-success">$seeds</span> <span class="size">$size</span> <span>$age</span>
                <a href="/user/someone/">someone</a></div></div>"""
        val page = "<html><body><nav><a href='/'>Home</a><a href='/browse/?cat=movies'>Movies and films</a><a href='/browse/?cat=music'>Music and audio</a></nav>" +
            card(101, "Open Background Music Pack Volume One", "412.5 MB", 41, "4 years ago") +
            card(102, "Creative Commons Piano Collection", "1.42 GB", 7, "2 months ago") +
            card(103, "Public Domain Orchestral Recordings", "3.1 GB", 0, "11 days ago") +
            card(104, "Field Recordings for Film Makers", "96 MB", 12, "1 year ago") + "</body></html>"
        val base = "https://example.org/browse/?q=music"
        assertTrue("no magnet links, so the ordinary reader finds nothing", SiteReader.parse(page, base, "x").isEmpty())
        val hits = SiteReader.listing(page, base, "Example", NOW)
        assertEquals(4, hits.size)
        val h = hits[1]
        assertEquals("Creative Commons Piano Collection", h.title)
        assertTrue(h.pageOnly)
        assertEquals("https://example.org/item/102-creative-commons-piano-collection/", h.page)
        assertEquals((1.42 * 1073741824).toLong(), h.size)
        assertEquals(7, h.seeders)
        assertEquals(NOW - 2 * 2592000000L, h.published)
        assertEquals(0, hits[2].seeders)
        assertEquals(4, hits.map { it.key }.distinct().size)

        // the result's own page gives the link
        val detail = "<html><body><h1>Creative Commons Piano Collection</h1><a href='magnet:?xt=urn:btih:$H1&dn=Piano'>Magnet</a><p>Size: 1.42 GB Seeders: 9</p></body></html>"
        val full = SiteReader.detail(detail, h.page!!, h.title, "Example", NOW)!!
        assertEquals(H1, full.hash); assertTrue(!full.pageOnly)

        // a page of links that is not a torrent list is left alone
        val news = "<html><body>" + (1..6).joinToString("") { "<div><a href='/news/$it-something-happened-today/'>Something happened today, part $it</a><p>Read more about it.</p></div>" } + "</body></html>"
        assertTrue(SiteReader.listing(news, "https://example.org/news/", "x").isEmpty())
    }

    @Test fun torznab() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:torznab="http://torznab.com/schemas/2015/feed">
<channel><title>Example</title>
<item><title>Big Buck Bunny 1080p</title><guid>https://example.org/details/1</guid>
 <comments>https://example.org/details/1</comments><pubDate>Mon, 02 Nov 2026 10:00:00 +0000</pubDate><size>276445467</size>
 <link>http://192.168.1.50:9117/dl/x/?jackett_apikey=k&amp;path=abc&amp;file=Big</link>
 <enclosure url="http://192.168.1.50:9117/dl/x/?jackett_apikey=k&amp;path=abc&amp;file=Big" length="276445467" type="application/x-bittorrent"/>
 <torznab:attr name="category" value="2040"/><torznab:attr name="seeders" value="120"/><torznab:attr name="peers" value="150"/>
 <torznab:attr name="infohash" value="${H1.uppercase()}"/><torznab:attr name="magneturl" value="magnet:?xt=urn:btih:$H1&amp;dn=Big+Buck+Bunny"/></item>
<item><title>Sintel</title><guid>magnet:?xt=urn:btih:$H2</guid><pubDate>Sun, 01 Nov 2026 09:00:00 +0000</pubDate>
 <enclosure url="magnet:?xt=urn:btih:$H2" length="1000" type="application/x-bittorrent"/><torznab:attr name="seeders" value="5"/><torznab:attr name="leechers" value="2"/></item>
</channel></rss>"""
        val hits = TorznabProvider.parse(xml, "Jackett")
        assertEquals(2, hits.size)
        assertEquals("Big Buck Bunny 1080p", hits[0].title)
        assertEquals(H1, hits[0].hash)
        assertEquals(120, hits[0].seeders); assertEquals(30, hits[0].leechers)
        assertEquals(276445467L, hits[0].size)
        assertEquals("Video", hits[0].category)
        assertEquals(1793613600000L, hits[0].published)
        assertTrue(hits[0].magnet!!.contains("dn=Big+Buck+Bunny"))
        assertTrue(hits[0].torrentUrl!!.startsWith("http://192.168.1.50:9117/dl/x/"))
        assertEquals("https://example.org/details/1", hits[0].page)
        assertEquals(H2, hits[1].hash); assertEquals(5, hits[1].seeders); assertEquals(2, hits[1].leechers); assertEquals(1000L, hits[1].size)

        assertEquals("http://192.168.1.50:9117/api/v2.0/indexers/internetarchive/results/torznab/api?t=search&limit=100&apikey=KEY&q=big+buck",
            TorznabProvider.url("http://192.168.1.50:9117/api/v2.0/indexers/internetarchive/results/torznab/", "KEY", "big buck"))
        assertEquals("http://192.168.1.50:9696/1/api?t=search&limit=100&apikey=KEY", TorznabProvider.url("http://192.168.1.50:9696/1/api", "KEY", ""))
        assertEquals("http://h/api?apikey=K&t=search&limit=100&q=x", TorznabProvider.url("http://h/api?apikey=K", "K", "x"))
    }

    @Test fun archive() {
        val hits = ArchiveProvider.parse(res("ia.json"))
        assertTrue(hits.size > 10)
        val h = hits[0]
        assertEquals(40, h.hash!!.length)
        assertTrue(h.torrentUrl!!.startsWith("https://archive.org/download/") && h.torrentUrl!!.endsWith("_archive.torrent"))
        assertTrue(h.page!!.startsWith("https://archive.org/details/"))
        assertTrue(h.size > 0); assertTrue(h.published > 0); assertTrue(h.title.isNotBlank())
    }

    @Test fun rss() {
        val xml = """<rss><channel>
 <item><title>ubuntu-26.10-desktop-amd64.iso</title><link>https://example.org/a.torrent</link><pubDate>Mon, 02 Nov 2026 10:00:00 +0000</pubDate></item>
 <item><title>debian-13 netinst</title><enclosure url="magnet:?xt=urn:btih:$H1"/><link>https://example.org/page</link></item></channel></rss>"""
        val items = Rss.parse(xml)
        assertEquals(2, items.size)
        assertEquals("https://example.org/a.torrent", items[0].link)
        assertEquals("magnet:?xt=urn:btih:$H1", items[1].link)
        assertTrue(Rss.matches("", "anything"))
        assertTrue(Rss.matches("ubuntu desktop -server", items[0].title))
        assertFalse(Rss.matches("ubuntu -desktop", items[0].title))
        assertTrue(Rss.matches("/debian-\\d+/", items[1].title))
    }

    @Test fun updates() {
        assertTrue(Updater.newer("v1.1", "1.0")); assertTrue(Updater.newer("2.0", "1.9.9")); assertTrue(Updater.newer("1.0.1", "1.0"))
        assertFalse(Updater.newer("1.0", "1.0")); assertFalse(Updater.newer("v1.0", "1.1")); assertFalse(Updater.newer("", "1.0"))
        assertEquals("https://torusapps.com/swarm/", TorusLinks.PAGE); assertEquals("swarm", TorusLinks.SLUG)
    }

    // ---------------------------------------------------------------- safety

    @Test fun safety() {
        val gb = 1024L * 1024 * 1024
        assertEquals(0, Safety.assess("Film 2020 1080p WEB-DL", 2 * gb, 50, listOf("Film.mkv" to 2 * gb, "subs.srt" to 1000L)).level)
        assertEquals(2, Safety.assess("Film 2020 1080p WEB-DL", 2 * gb, 50, listOf("Film.mkv" to 2 * gb, "codec.exe" to 1000L)).level)
        assertEquals(2, Safety.assess("Holiday pictures", 100, 5, listOf("photo.jpg.exe" to 100L)).level)
        assertEquals(1, Safety.assess("Some Tool v2", 100, 5, listOf("setup.exe" to 100L)).level)
        assertEquals(1, Safety.assess("Film 2020 1080p BluRay", 5L * 1024 * 1024, 5, null).level)
        assertEquals(1, Safety.assess("ubuntu.iso", gb, 0, null).level)
        assertEquals(0, Safety.assess("ubuntu.iso", gb, -1, null).level)
        assertTrue(Safety.refuseOpen("x.apk")); assertFalse(Safety.refuseOpen("x.mkv"))
    }

    // ---------------------------------------------------------------- tracker scrape against a local fake tracker

    @Test fun udpScrape() {
        val server = DatagramSocket(0)
        val port = server.localPort
        val connId = 0x1122334455667788L
        val t = Thread {
            val buf = ByteArray(2048)
            repeat(2) {
                val p = DatagramPacket(buf, buf.size); server.receive(p)
                val b = ByteBuffer.wrap(buf, 0, p.length)
                val first = b.long; val action = b.int; val tid = b.int
                val reply = if (action == 0) {
                    check(first == 0x41727101980L)
                    ByteBuffer.allocate(16).putInt(0).putInt(tid).putLong(connId).array()
                } else {
                    check(first == connId && action == 2)
                    val n = (p.length - 16) / 20
                    val r = ByteBuffer.allocate(8 + 12 * n).putInt(2).putInt(tid)
                    for (i in 0 until n) if (i == 2) r.putInt(0).putInt(0).putInt(0) else r.putInt(100 + i).putInt(1000 + i).putInt(10 + i)
                    r.array()
                }
                server.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
        }
        t.start()
        val unknown = "c".repeat(40)
        val res = Scrape.scrape(listOf(H1, H2, unknown), listOf("udp://127.0.0.1:$port/announce"), 3000)
        t.join(3000); server.close()
        assertEquals(Scrape.SL(100, 10, 1000), res[H1])
        assertEquals(Scrape.SL(101, 11, 1001), res[H2])
        assertTrue("a torrent the tracker has never heard of is unknown, not zero", unknown !in res)
        assertTrue(Scrape.scrape(listOf(H1), listOf("udp://127.0.0.1:1/announce"), 500).isEmpty())
    }
}
