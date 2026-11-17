package uk.jj.swarm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import uk.jj.swarm.Ui

/** One piece of a help topic. */
sealed class HelpBit {
    data class P(val text: String) : HelpBit()                       // a paragraph
    data class H(val text: String) : HelpBit()                       // a small heading inside a topic
    data class Steps(val items: List<String>) : HelpBit()            // numbered steps
    data class Points(val items: List<Pair<String, String>>) : HelpBit()   // a word or name, then what it means
    data class Example(val label: String, val text: String) : HelpBit()    // something to type, shown in a box
    data class Tip(val text: String) : HelpBit()
    data class Go(val label: String, val route: String, val tab: Int = -1) : HelpBit()   // a button that opens the screen being described
}

data class HelpTopic(val id: String, val icon: ImageVector, val title: String, val summary: String, val bits: List<HelpBit>) {
    /** Everything in the topic as plain text, for the search box. */
    val text: String by lazy {
        (listOf(title, summary) + bits.flatMap { b ->
            when (b) {
                is HelpBit.P -> listOf(b.text); is HelpBit.H -> listOf(b.text); is HelpBit.Steps -> b.items
                is HelpBit.Points -> b.items.flatMap { listOf(it.first, it.second) }
                is HelpBit.Example -> listOf(b.label, b.text); is HelpBit.Tip -> listOf(b.text); is HelpBit.Go -> listOf(b.label)
            }
        }).joinToString(" ").lowercase()
    }
}

/** The whole of the in-app help. Written to be read by someone who has never used a torrent app. */
object Help {
    private fun p(t: String) = HelpBit.P(t)
    private fun h(t: String) = HelpBit.H(t)
    private fun steps(vararg s: String) = HelpBit.Steps(s.toList())
    private fun points(vararg s: Pair<String, String>) = HelpBit.Points(s.toList())
    private fun ex(label: String, t: String) = HelpBit.Example(label, t)
    private fun tip(t: String) = HelpBit.Tip(t)
    private fun go(label: String, route: String, tab: Int = -1) = HelpBit.Go(label, route, tab)

    val topics: List<HelpTopic> = listOf(
        HelpTopic("basics", Icons.Filled.School, "The basics, in two minutes", "What a torrent is, and the words you will see", listOf(
            p("A torrent is a way of downloading a file from lots of ordinary people at once, not from one website. Everyone who is downloading the file also passes on the bits they already have. The more people sharing, the faster it goes."),
            points(
                "Torrent" to "The file, or set of files, you are downloading. Also the small file (ending .torrent) that describes it.",
                "Magnet link" to "A link that starts with magnet: and points at a torrent. Tap one anywhere on your phone and Swarm offers to open it.",
                "Seeders (▲ green)" to "People who have the whole thing and are sharing it. This is the number that matters. No seeders usually means it will never finish.",
                "Leechers (▼ red)" to "People who are still downloading it, like you. They share the parts they have so far.",
                "Peers" to "Seeders and leechers together: everyone you are connected to for that torrent.",
                "Swarm" to "All the people sharing one torrent. It is where the app gets its name.",
                "Tracker" to "A server that keeps a list of who is sharing a torrent, so people can find each other.",
                "DHT" to "A way of finding other people without a tracker. See the topic called The DHT tab.",
                "Seeding" to "Carrying on sharing a torrent after your download has finished. It is how you give back.",
                "Ratio" to "How much you have uploaded compared with how much you downloaded. 1.0 means you gave back the same amount you took.",
            ),
            tip("Only download and share things you have the right to. Swarm cannot tell what is free to share and what is not. That part is up to you."),
        )),

        HelpTopic("add", Icons.Filled.AddLink, "Getting a torrent into Swarm", "Five ways to start a download", listOf(
            h("1. Search for it"),
            p("Go to the Search tab, type a few words and tap the magnifying glass. Tap the green arrow beside a result and it starts downloading."),
            go("Open the Search tab", "home", 1),
            h("2. Paste a link"),
            steps("Copy the magnet link (press and hold it, then Copy link).", "Open the Torrents tab and tap the link icon with a plus, at the top.", "Tap Paste from clipboard, then Add."),
            p("You can also paste the web address of a .torrent file, or a 40-character info-hash (the torrent's ID code)."),
            h("3. Tap a link in another app"),
            p("Tap a magnet link in your browser, in an email or in a message. Android asks which app to use. Choose Swarm. Opening a .torrent file from your Downloads works the same way."),
            h("4. Scan a QR code"),
            p("On the Torrents tab tap the link icon with a plus, then Scan QR, and point the camera at a magnet QR code. Someone else with Swarm can show you one: on their torrent's page, tap the three dots, then Show QR code."),
            h("5. Use Swarm's browser"),
            p("Open the side menu (the three lines, top left) and tap Browser. Go to any page that has torrents on it. When you tap a magnet link or a .torrent link, Swarm takes it."),
            go("Open the browser", "browser"),
        )),

        HelpTopic("torrents", Icons.Filled.CloudDownload, "The Torrents tab", "Watching, pausing, playing and deleting your downloads", listOf(
            p("This is your list. All shows everything, Downloading shows what is still coming, Complete shows what has finished."),
            points(
                "The round button on each row" to "Pauses or resumes that one torrent.",
                "Pause All / Resume All" to "Does the same for the whole list.",
                "The magnifying glass" to "Filters your own list by name. It does not search the internet. That is the Search tab.",
                "The tick-list icon" to "Lets you tick several torrents, then share or delete them together. Pressing and holding a row does the same.",
            ),
            h("What the words under a torrent mean"),
            points(
                "Finding details…" to "Swarm has the link and is asking other people for the file list. If it stays like this for a long time, nobody is sharing it right now.",
                "Queued" to "Waiting its turn. You set how many download at once in Settings, under Active Torrent Limit.",
                "Seeding" to "Finished, and now sharing with others.",
                "Complete" to "Finished, and no longer sharing.",
                "Paused" to "Stopped until you resume it.",
            ),
            h("Tap a torrent to open its page"),
            points(
                "Info" to "Progress, speed, how many people are connected, where it is saved.",
                "Files" to "Everything inside the torrent. Untick a file to skip it. Tap a finished file to open it. Press and hold to open it in another app.",
                "Peers" to "The people you are connected to right now.",
                "Trackers" to "The servers helping this torrent find people. You can add another one.",
                "Play now" to "Starts a video before it has finished. Swarm fetches the parts the player needs first: the start, a small index at the very end of the file, then on from wherever you are watching. Under the spinner it says what it is waiting for. A few seconds is normal, and so is a short wait each time you jump to a new place.",
                "The three dots" to "Copy the magnet link, show a QR code, set speed limits for this torrent only, move it up or down the queue, ask the trackers again, re-check the files, or delete.",
            ),
            h("Deleting"),
            p("When you delete, Swarm asks whether to delete the downloaded files too. Leave the box unticked to keep the files and just remove the torrent from the list."),
            go("Open the Torrents tab", "home", 0),
        )),

        HelpTopic("search", Icons.Filled.Search, "The Search tab", "Finding torrents by keyword, and reading the results", listOf(
            steps("Type a few words in the box at the top.", "Tap the magnifying glass on the right.", "Wait a few seconds. Results appear as each source answers."),
            h("Reading a result"),
            points(
                "The coloured dot" to "Green means plenty of people are sharing it. Amber means only a few. Red means nobody is. Grey means Swarm could not find out.",
                "▲ and a number" to "Seeders. Higher is better and faster.",
                "▼ and a number" to "Leechers: people still downloading.",
                "Web seeded" to "The file is also kept on an ordinary web server, so it downloads even with no seeders. Internet Archive results are like this.",
                "The size and the age" to "How big the download is, and how long ago it was put up.",
                "The name underneath" to "Which source the result came from.",
            ),
            h("Narrowing it down"),
            points(
                "All, Video, Audio, Books, Software, Other" to "Show only that kind of thing.",
                "The sort button (it says Seeders to start with)" to "Tap it to choose Most seeders, Newest first, Biggest first or Smallest first.",
                "The bell" to "Watches this search for you. See the topic called Feeds and the watchlist.",
            ),
            h("Before you download"),
            p("Tap a result (not the arrow) to see more: the list of files inside it, where it came from, and any warning. Swarm warns you if a file looks like a program dressed up as a video, or if a video looks too small to be real."),
            tip("If nothing useful comes back, try fewer words, check which sources are switched on, or try the DHT tab."),
            go("Open the Search tab", "home", 1),
        )),

        HelpTopic("sources", Icons.Filled.Dns, "Search sources: where Search looks", "The two built-in sources, and what a source is", listOf(
            p("Swarm does not hold a list of torrents itself. When you search, it asks each of your sources and puts the answers together. A source is simply a website or service that lists torrents."),
            h("Built in"),
            points(
                "Internet Archive" to "A huge free library: old films, music, books and software that are in the public domain or freely licensed.",
                "Linux Tracker" to "Linux and other free, open-source system downloads.",
            ),
            p("Each has a switch, so you can turn it off."),
            h("Adding your own"),
            p("There are two kinds you can add, and each has its own topic below:"),
            points(
                "A website" to "The easy one. You show Swarm the site's search results page and it reads that page for you.",
                "A Torznab or Jackett address" to "For people who run a program called Jackett or Prowlarr on a computer. More setting up, but it can search many sites at once.",
            ),
            tip("Any source you add is your choice. Many sites list things that are not free to share. Only download what you have the right to."),
            go("Open Search sources", "sources"),
        )),

        HelpTopic("website", Icons.Filled.Language, "How to add a website as a source", "Step by step, with an example", listOf(
            p("You are going to search the website once yourself, for the word test, and give Swarm the address of the page that comes up. Swarm then knows where the search word goes."),
            steps(
                "Open the website in your normal browser (Chrome, Samsung Internet).",
                "Use the website's own search box. Type the word test and search.",
                "You are now looking at the site's results page. Tap the address bar at the top and copy the whole address.",
                "Come back to Swarm. Open the side menu (three lines, top left) and tap Search sources.",
                "Tap + at the top, or Add a source.",
                "Tap Website.",
                "Paste the address into Results page address. Give it a name if you like.",
                "Tap Test. Swarm tries a search and tells you how many results it could read.",
                "Tap Save.",
            ),
            ex("What a results address looks like", "https://example.org/search?q=test"),
            p("Swarm swaps the word test for whatever you search for. In your list of sources you will see it written with {query} where test was. That is normal."),
            h("What Test can tell you"),
            points(
                "Working: 25 results came back" to "All good.",
                "…keeps its download links on each result's own page" to "Also good. The site's list has no download buttons, so when you tap Download, Swarm goes to that result's page and gets the link. If the page only shows its link after a tap, Swarm opens the page for you. Tap the site's own magnet or download link there and Swarm picks it up.",
                "It answered but gave no results Swarm could read" to "Check you copied the results page, not the home page, and that the address has the word test in it.",
                "Could not reach it" to "The site is down, or the address is wrong.",
            ),
            h("If a site still shows nothing"),
            steps(
                "Open the side menu and tap Browser.",
                "Go to the site there and let it load fully. Some sites first check that a real person is visiting.",
                "Go back and search again.",
            ),
            tip("Results from a website may show Seeders not known. That only means the site did not show the number in a way Swarm could read."),
            go("Open Search sources", "sources"),
        )),

        HelpTopic("torznab", Icons.Filled.SettingsEthernet, "Torznab and Jackett: what they are and how to set them up", "For searching many sites at once, using a computer at home", listOf(
            h("What they are"),
            points(
                "Jackett" to "A free program that runs on a computer (Windows, Mac or Linux), not on your phone. You tell it which torrent sites you use. It then searches all of them for you and hands back one tidy list.",
                "Prowlarr" to "Another free program that does the same job. Use either one.",
                "Torznab" to "The name of the language those programs speak. A Torznab address is just the web address Swarm uses to talk to Jackett or Prowlarr.",
                "API key" to "A long password made of letters and numbers. Jackett shows it to you. Swarm needs it to be allowed in.",
            ),
            p("You do not need any of this to use Swarm. It is an extra for people who want to search a lot of sites in one go. If that sounds like too much, add a website instead (see the topic above)."),
            h("Where to get Jackett"),
            steps(
                "On your computer, search the web for Jackett GitHub. The project lives at github.com/Jackett/Jackett.",
                "Open its Releases page and download the installer for your computer. On Windows it is the file ending Installer.Windows.exe.",
                "Install it and start it. It opens a page in your computer's browser, called the dashboard.",
            ),
            h("Setting Jackett up"),
            steps(
                "On the dashboard, click Add indexer. Indexer is Jackett's word for a torrent site.",
                "Pick the sites you want from the list and click the plus beside each.",
                "Each site you added now has a row with a button called Copy Torznab Feed. That is the address Swarm needs.",
                "The API key is in the top right corner of the dashboard.",
            ),
            h("Putting it into Swarm"),
            steps(
                "Find your computer's address on your Wi-Fi. On Windows: Settings, Network, Wi-Fi, your network, and look for IPv4 address. It looks like 192.168.1.50.",
                "In Swarm: side menu, Search sources, +, then Torznab / Jackett.",
                "In Torznab address, type the address from Copy Torznab Feed, but with your computer's address in place of 127.0.0.1 or localhost.",
                "In API key, type the key from the top right of the dashboard.",
                "Tap Test, then Save.",
            ),
            ex("One site through Jackett", "http://192.168.1.50:9117/api/v2.0/indexers/NAME-OF-SITE/results/torznab/"),
            ex("Every site you added to Jackett, in one go", "http://192.168.1.50:9117/api/v2.0/indexers/all/results/torznab/"),
            ex("One site through Prowlarr (the number is that site's number in Prowlarr)", "http://192.168.1.50:9696/1/api"),
            p("Change 192.168.1.50 to your own computer's address. In Prowlarr the API key is under Settings, then General."),
            h("Things that catch people out"),
            points(
                "127.0.0.1 or localhost does not work" to "Those mean this device. On your phone that is the phone, not the computer. Use the computer's Wi-Fi address.",
                "It only works at home" to "Yes. The phone has to be on the same Wi-Fi as the computer, and the computer has to be switched on with Jackett running.",
                "Could not reach it" to "Check the computer is on, Jackett is running, and Windows Firewall is not blocking it. In Jackett's settings, External access must be ticked.",
                "It answered but gave no results" to "The API key is probably wrong. Copy it again.",
            ),
            go("Open Search sources", "sources"),
        )),

        HelpTopic("dht", Icons.Filled.Hub, "The DHT tab", "What the DHT is, and how Swarm builds its own list from it", listOf(
            h("What the DHT is"),
            p("DHT stands for distributed hash table. Forget the name. Think of it as a giant phone book that no one company owns. Every torrent app in the world holds a few pages of it. When your app wants to find people sharing a torrent, it asks other apps, who pass it along until someone has the answer."),
            p("The DHT is how torrents keep working with no website and no tracker. But it has one big gap: you cannot search it by name. It only answers the question who is sharing torrent number such-and-such."),
            h("What Swarm does about that"),
            p("Swarm has a crawler. It quietly asks other apps on the DHT which torrents they know about. For each one it hears of, it asks the people sharing it for the name and the list of files. It writes those down in an index on your phone. Then you can search that index by name."),
            points(
                "It only fetches names and file lists" to "Nothing is downloaded until you tap the download arrow.",
                "The index is yours" to "It is stored on your phone and is not sent anywhere.",
                "It starts empty" to "It grows while Swarm is open, and overnight while the phone is charging on Wi-Fi. Expect hundreds to a few thousand an hour. Leave it charging for a night or two for a big index.",
                "Nobody has checked it" to "The DHT is everything everyone is sharing, good and bad. Titles that look adult are left out unless you change that. A torrent being in the index does not mean you have the right to download it.",
            ),
            h("Using the tab"),
            points(
                "The switch" to "Turns the crawler on or off.",
                "The line under the count" to "Crawling means it is working, and shows how many it has added. Waiting means your settings are holding it back, for example Wi-Fi only while you are on mobile data. The crawler slows right down while you are downloading, and pauses while a video plays, so it never gets in their way.",
                "The search box" to "Searches the index by name. Type words and tap the magnifying glass.",
                "Newest, Seeders, Biggest" to "Changes the order of the list.",
            ),
            h("Settings for it"),
            p("In Settings, under DHT index: choose when the crawler runs (off, charging and open, only while open, or always), set the most torrents the index may hold, hide adult content, or clear the index and start again."),
            tip("The count is not going up? Check Swarm is allowed to run in the background (Android Settings, Apps, Swarm, Battery, Unrestricted) and that you are on Wi-Fi."),
            go("Open the DHT tab", "home", 2),
        )),

        HelpTopic("media", Icons.Filled.OndemandVideo, "Video and Music tabs", "Playing what you have downloaded", listOf(
            p("These two tabs list the video and audio files on your phone, including finished downloads. Tap one to play it in Swarm's own player."),
            points(
                "Video player" to "Tap the screen for the controls. It remembers where you stopped.",
                "Music player" to "Keeps playing when you leave Swarm or turn the screen off, with controls in the notification.",
                "Open in another app" to "On a torrent's Files tab, press and hold a file.",
            ),
            p("The first time, Swarm asks for permission to see your videos and music. Without it these tabs stay empty."),
        )),

        HelpTopic("seeding", Icons.Filled.Upload, "Seeding: giving back", "What Swarm uploads, and how to control it", listOf(
            p("Torrents only work because people share. While you download, Swarm passes on the parts you already have. After a download finishes, it keeps sharing for a while. That is seeding."),
            p("Out of the box, Swarm seeds until it has given back the same amount it downloaded (a ratio of 1.0), and only on Wi-Fi. Then it stops by itself."),
            h("To change it"),
            p("Settings, under Seeding:"),
            points(
                "After a download finishes" to "Seed to ratio, stop straight away, or keep seeding until you pause it.",
                "Ratio to seed to" to "Half, the same amount, twice or three times what you downloaded.",
                "Upload on Wi-Fi only" to "Leave this on unless you have unlimited mobile data.",
            ),
            go("Open Settings", "settings"),
        )),

        HelpTopic("privacy", Icons.Filled.Security, "Privacy and staying safe", "What other people can see, VPNs, and fake files", listOf(
            h("What others can see"),
            p("Everyone sharing a torrent can see the internet address (IP address) of everyone else sharing it. That is how torrents work in every app. It means your internet provider, and anyone watching that torrent, can tell that your connection took part."),
            h("VPN"),
            p("A VPN is a separate app that hides your real address behind its own. Swarm does not include one. Under Settings, Privacy and safety, VPN guard:"),
            points(
                "Warn" to "Shows a notice on the Torrents tab when no VPN is on. This is how Swarm starts.",
                "Block" to "Stops all torrent traffic, including the DHT crawler, until a VPN is on.",
                "Off" to "No checks.",
            ),
            h("Encryption"),
            p("This scrambles torrent traffic so simple network filters do not recognise it. It does not hide your address, and it is not a VPN."),
            h("Fake and harmful files"),
            p("Files from torrents come from strangers. With Safety checks on, Swarm warns you about results that look fake and refuses to open program files (.exe, .apk and the like). A video is never a program. If a film turns out to be an .exe, delete it."),
            h("IP blocklist"),
            p("An optional list of addresses Swarm should refuse to connect to. If you have the web address of one, paste it under Settings, Privacy and safety, IP blocklist."),
            go("Open Settings", "settings"),
        )),

        HelpTopic("settings", Icons.Filled.Settings, "Settings explained", "Every setting, in plain words", listOf(
            h("Theme"),
            p("Light, Dark, or follow whatever your phone is set to."),
            h("Bandwidth"),
            points(
                "Wifi Networks Only" to "On: nothing downloads or uploads on mobile data. Turn it off only if you have plenty of data.",
                "Download Limit / Upload Limit" to "The fastest Swarm may go, in KB per second. Empty means no limit. Useful if Swarm makes everything else on your Wi-Fi slow.",
                "Active Torrent Limit" to "How many torrents download at the same time. The rest wait in a queue.",
                "Connection limit" to "The most people Swarm talks to at once. Lower it if your Wi-Fi struggles.",
            ),
            h("Others"),
            points(
                "Default Download Folder" to "Where files are saved. It starts as a folder called Swarm inside Download.",
                "File access" to "Android's permission to save there. If it says Off, tap it and allow it.",
                "Sort into folders" to "Puts finished downloads into Movies, TV, Music, Books or Other.",
                "Incoming Port" to "Leave at 0 unless you know you need a particular one.",
            ),
            h("Video"),
            points(
                "Play video while in background" to "Keeps the sound going when you leave the player.",
                "Resume music queue after video" to "Goes back to your music when a video ends.",
            ),
            h("Pro Setting"),
            points(
                "Auto-Shutdown" to "Closes Swarm when every download has finished.",
                "Battery Saver" to "Pauses everything when the battery drops below the level you pick, unless the phone is charging.",
                "Only while charging" to "Downloads wait until the phone is plugged in.",
                "Scheduled hours" to "Only download between two times, for example 1 in the morning to 7.",
            ),
            h("Downloads"),
            points(
                "Download in order" to "Downloads from start to finish so video can play sooner. A little slower overall.",
                "Add public trackers" to "Adds well-known open trackers to every torrent so it finds people faster. Leave on.",
                "Tracker list" to "The trackers used for that, one per line. Also used to look up seeders and leechers.",
            ),
            h("Seeding, Privacy and safety, DHT index"),
            p("Each of these has its own topic in this help."),
            go("Open Settings", "settings"),
        )),

        HelpTopic("more", Icons.Filled.MoreHoriz, "Feeds, the watchlist and the other tools", "Everything else in the side menu", listOf(
            p("Open the side menu with the three lines at the top left."),
            h("Watchlist"),
            p("A saved search that Swarm runs for you about once an hour. When something new turns up, you get a notification. To add one, search for something on the Search tab and tap the bell."),
            h("RSS feeds"),
            p("An RSS feed is a list that a website keeps updated, like a news ticker of new torrents. Give Swarm the feed's address and it checks about once an hour."),
            points(
                "Filter" to "Words that must be in the title. Put a minus sign in front of a word to leave things out, for example ubuntu -beta.",
                "Download matches automatically" to "Starts the download by itself when something new matches.",
            ),
            h("Create a torrent"),
            p("Share something of your own. Pick a file or folder, tap Create and seed, and Swarm gives you a magnet link to send to people. The file stays where it is. Your phone has to be on, with Swarm running, for others to download it."),
            h("Browser"),
            p("A simple web browser. When you tap a magnet link or a .torrent link, Swarm takes it. The house icon sets the page it starts on."),
            h("About and backup"),
            points(
                "Save a backup file" to "Puts your torrent list, sources, feeds, watchlist and settings into one file. Keep it private: it includes any API keys.",
                "Restore from a backup file" to "Brings them back, on this phone or a new one.",
                "Check for a new version" to "Looks for a newer Swarm. Swarm also tells you on the Torrents tab.",
                "Last crash report" to "If Swarm ever closes by itself, the reason is kept here to copy and send.",
            ),
        )),

        HelpTopic("fix", Icons.Filled.Build, "When something is not working", "The usual causes, and what to do", listOf(
            h("A torrent sits at 0%, or says Finding details…"),
            points(
                "Nobody is sharing it" to "Look at the seeders. If it is 0 or not known, it may never start. Try a different result.",
                "You are on mobile data" to "Wifi Networks Only is on to protect your data. Connect to Wi-Fi.",
                "The VPN guard is blocking" to "If you chose Block and no VPN is on, nothing moves. Turn the VPN on, or change the setting.",
                "Scheduled hours or Only while charging is on" to "It is waiting for the right time, or for the charger.",
            ),
            h("It is slow"),
            p("Speed depends on how many seeders there are and how fast they are, not on Swarm. Check you have not set a Download Limit. On the torrent's page, tap the three dots, then Ask trackers again."),
            h("Downloads stop when the screen is off"),
            p("Android is putting Swarm to sleep. Open Android Settings, Apps, Swarm, Battery, and choose Unrestricted."),
            h("I cannot find my files"),
            p("Open your phone's My Files app, then Download, then Swarm. Finished downloads are in Movies, TV, Music, Books or Other inside it. If File access is off, they are inside the app instead: turn File access on in Settings."),
            h("Search shows nothing"),
            p("Check at least one source is switched on, under Search sources. Try fewer words. The built-in sources only hold free material, so a search for a new film will find nothing there."),
            h("A website source shows nothing"),
            p("See the end of the topic called How to add a website as a source."),
            h("The DHT index is not growing"),
            p("See the tip at the end of the topic called The DHT tab."),
            h("Swarm will not open a file"),
            p("If it is a program file (.exe, .apk and similar), that is on purpose. For anything else, press and hold the file to open it in another app."),
        )),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HelpScreen(nav: NavHostController) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf((Ui.helpTopic ?: "").also { Ui.helpTopic = null }) }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = if (words.isEmpty()) Help.topics else Help.topics.filter { t -> words.all { t.text.contains(it) } }

    BackPage("Help", { nav.popBackStack() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Text("How to use Swarm. Tap a heading to open it.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, shape = RoundedCornerShape(22.dp), placeholder = { Text("Search the help") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
            if (shown.isEmpty()) Text("Nothing in the help matches those words. Try one word, such as seeders or Jackett.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
            for (t in shown) androidx.compose.runtime.key(t.id) {
                val isOpen = open == t.id || (words.isNotEmpty() && shown.size == 1)
                // Opening a topic low down the list would leave its text off the bottom of the screen, so bring it up.
                val req = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                androidx.compose.runtime.LaunchedEffect(isOpen) { if (isOpen) { kotlinx.coroutines.delay(320); try { req.bringIntoView() } catch (e: Exception) { } } }
                Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).then(Modifier.bringIntoViewRequesterCompat(req)), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { open = if (open == t.id) "" else t.id }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                                Text(t.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                            }
                            Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (isOpen) "Close" else "Open")
                        }
                        AnimatedVisibility(isOpen) { Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { t.bits.forEach { HelpBitView(it, nav) } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpBitView(b: HelpBit, nav: NavHostController) {
    val body = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    when (b) {
        is HelpBit.P -> Text(b.text, fontSize = 15.sp, lineHeight = 22.sp, color = body, modifier = Modifier.padding(top = 8.dp))
        is HelpBit.H -> Text(b.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        is HelpBit.Steps -> Column(Modifier.padding(top = 6.dp)) {
            b.items.forEachIndexed { i, s ->
                Row(Modifier.padding(top = 8.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) }
                    Text(s, fontSize = 15.sp, lineHeight = 22.sp, color = body, modifier = Modifier.padding(start = 12.dp).weight(1f))
                }
            }
        }
        is HelpBit.Points -> Column(Modifier.padding(top = 4.dp)) {
            b.items.forEach { (k, v) ->
                Column(Modifier.padding(top = 10.dp)) {
                    Text(k, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = body, lineHeight = 20.sp)
                    Text(v, fontSize = 14.sp, lineHeight = 20.sp, color = soft)
                }
            }
        }
        is HelpBit.Example -> Column(Modifier.padding(top = 12.dp)) {
            Text(b.label, fontSize = 13.sp, color = soft)
            Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
                SelectionContainer { Text(b.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 19.sp, color = body, modifier = Modifier.padding(12.dp)) }
            }
        }
        is HelpBit.Tip -> Surface(Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Row(Modifier.padding(12.dp)) {
                Icon(Icons.Filled.Lightbulb, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                Text(b.text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(start = 10.dp))
            }
        }
        is HelpBit.Go -> OutlinedButton(onClick = {
            if (b.route == "home") { Ui.tab.value = b.tab; nav.popBackStack("home", false) } else nav.navigate(b.route)
        }, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(top = 16.dp)) { Text(b.label) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.bringIntoViewRequesterCompat(r: androidx.compose.foundation.relocation.BringIntoViewRequester): Modifier = this.then(Modifier.bringIntoViewRequester(r))

/** The row that opens the help, used at the top of Settings and on the screens the help explains. */
@Composable
fun HelpLink(nav: NavHostController, label: String, sub: String, topic: String? = null) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().clickable { Ui.helpTopic = topic; nav.navigate("help") }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}
