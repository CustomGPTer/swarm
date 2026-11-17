@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.swarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.swarm.Db
import uk.jj.swarm.Prefs
import uk.jj.swarm.Ui
import uk.jj.swarm.askAllFiles
import uk.jj.swarm.engine.Blocklist
import uk.jj.swarm.engine.Crawler
import uk.jj.swarm.hasAllFiles

@Composable
fun SwitchRow(label: String, key: String, default: Boolean, sub: String? = null, onChange: (Boolean) -> Unit = {}) {
    var v by remember { mutableStateOf(Prefs.getB(key, default)) }
    Row(Modifier.fillMaxWidth().clickable { v = !v; Prefs.setB(key, v); onChange(v) }.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(v, { v = it; Prefs.setB(key, it); onChange(it) })
    }
}

@Composable
fun ValueRow(label: String, value: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.ChevronRight, null)
    }
}

/** A row that opens a list of choices. Each choice has a short label and a plain-English explanation. */
@Composable
fun ChoiceRow(label: String, key: String, default: Int, options: List<Triple<Int, String, String>>, sub: String? = null, onChange: (Int) -> Unit = {}) {
    var v by remember { mutableIntStateOf(Prefs.getI(key, default)) }
    var open by remember { mutableStateOf(false) }
    ValueRow(label, options.firstOrNull { it.first == v }?.second ?: v.toString(), sub) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { (value, name, help) ->
                Row(Modifier.fillMaxWidth().clickable { v = value; Prefs.setI(key, value); onChange(value); open = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(v == value, null)
                    Column(Modifier.padding(start = 10.dp)) { Text(name, fontSize = 15.sp); if (help.isNotBlank()) Text(help, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    })
}

@Composable
fun NumberRow(label: String, key: String, default: Int, unit: String, zeroLabel: String, help: String, max: Int = Int.MAX_VALUE) {
    var v by remember { mutableIntStateOf(Prefs.getI(key, default)) }
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    ValueRow(label, if (unit.startsWith(":")) "$v$unit" else if (v <= 0) zeroLabel else "$v $unit".trim()) { text = if (v > 0) v.toString() else ""; open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column { Text(help, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedTextField(text, { text = it.filter(Char::isDigit).take(7) }, singleLine = true, suffix = { Text(unit) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 10.dp)) }
    }, confirmButton = { TextButton(onClick = { v = (text.toIntOrNull() ?: 0).coerceIn(0, max); Prefs.setI(key, v); open = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
}

@Composable
fun TextRow(label: String, key: String, default: String, help: String, lines: Int = 1, display: (String) -> String = { it }, onSaved: (String) -> Unit = {}) {
    var v by remember { mutableStateOf(Prefs.getS(key, default)) }
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    ValueRow(label, display(v).take(22)) { text = v; open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column { Text(help, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedTextField(text, { text = it }, singleLine = lines == 1, minLines = lines, maxLines = maxOf(lines, 8), modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) }
    }, confirmButton = { TextButton(onClick = { v = text.trim(); Prefs.setS(key, v); onSaved(v); open = false }) { Text("Save") } },
        dismissButton = { Row { TextButton(onClick = { v = default; Prefs.setS(key, default); onSaved(default); open = false }) { Text("Reset") }; TextButton(onClick = { open = false }) { Text("Cancel") } } })
}

@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by Prefs.theme.collectAsState()
    var allFiles by remember { mutableStateOf(hasAllFiles()) }
    var rules by remember { mutableIntStateOf(Blocklist.rules) }
    var indexCount by remember { mutableLongStateOf(-1) }
    var clearIndex by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { indexCount = withContext(Dispatchers.IO) { Db.get(ctx).dhtCount() } }

    BackPage("Settings", { nav.popBackStack() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            HelpLink(nav, "Help", "How to use Swarm, what each thing does, and how to add sources. In plain words.")
            SectionTitle(Icons.Filled.Palette, "Theme")
            listOf(0 to "Light", 1 to "Dark", 2 to "Use system default").forEach { (v, l) ->
                Row(Modifier.fillMaxWidth().clickable { Prefs.setI("theme", v) }.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l, fontSize = 16.sp, color = if (theme == v) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (theme == v) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            SectionTitle(Icons.Filled.Speed, "Bandwidth")
            SwitchRow("Wifi Networks Only", "wifiOnly", true, "Nothing is downloaded or uploaded on mobile data")
            NumberRow("Download Limit", "dlLimitKb", 0, "KB/s", "Max", "The fastest Swarm may download. Leave empty for no limit.")
            NumberRow("Upload Limit", "ulLimitKb", 0, "KB/s", "Max", "The fastest Swarm may upload. Leave empty for no limit.")
            NumberRow("Active Torrent Limit", "activeLimit", 0, "", "Off", "How many torrents download at once. The rest wait in a queue. Leave empty for no limit.", 50)
            NumberRow("Connection limit", "maxConnections", 200, "", "200", "The most peers Swarm connects to in total. Lower it if your Wi-Fi struggles.", 2000)

            SectionTitle(Icons.Filled.Folder, "Others")
            TextRow("Default Download Folder", "baseDir", Prefs.defaultBase, "Full path of the folder downloads are saved in.", display = { it.trimEnd('/').substringAfterLast('/') })
            if (!allFiles) ValueRow("File access", "Off", "Needed to save into Download and to sort files. Tap to allow.") { askAllFiles(ctx); allFiles = hasAllFiles() }
            SwitchRow("Sort into folders", "autoSort", true, "Finished downloads go into Movies, TV, Music, Books or Other inside the download folder")
            NumberRow("Incoming Port", "port", 0, "", "0", "The port other peers connect to. 0 lets Swarm pick one. Use 1024 to 65535.", 65535)

            SectionTitle(Icons.Filled.OndemandVideo, "Video")
            SwitchRow("Play video while in background", "bgVideo", false)
            SwitchRow("Resume music queue after video", "resumeMusic", true)

            SectionTitle(Icons.Filled.BatteryChargingFull, "Pro Setting")
            SwitchRow("Auto-Shutdown", "autoShutdown", false, "Closes Swarm when every download has finished")
            ChoiceRow("Battery Saver", "batterySaver", 0, listOf(Triple(0, "Off", "Never pause for low battery"), Triple(15, "15%", "Pause below 15% unless charging"), Triple(25, "25%", "Pause below 25% unless charging"), Triple(40, "40%", "Pause below 40% unless charging")))
            SwitchRow("Only while charging", "chargingOnly", false, "Downloads wait until the phone is plugged in")
            SwitchRow("Scheduled hours", "schedOn", false, "Only download between the two times below")
            NumberRow("Schedule starts", "schedStart", 1, ":00", "0 :00", "Hour of the day downloads may start (0 to 23).", 23)
            NumberRow("Schedule ends", "schedEnd", 7, ":00", "0 :00", "Hour of the day downloads stop (0 to 23).", 23)

            SectionTitle(Icons.Filled.Download, "Downloads")
            SwitchRow("Download in order", "sequentialDefault", false, "New torrents download start to finish so video can play sooner. Slightly slower overall.")
            SwitchRow("Add public trackers", "addTrackers", true, "Adds well-known open trackers to each torrent so it finds peers faster")
            TextRow("Tracker list", "trackers", Prefs.DEFAULT_TRACKERS, "One tracker address per line. Also used to look up seeders and leechers.", lines = 5, display = { "${it.lines().count { l -> l.isNotBlank() }} trackers" })

            SectionTitle(Icons.Filled.Upload, "Seeding")
            ChoiceRow("After a download finishes", "seedMode", 1, listOf(
                Triple(0, "Stop", "Stop straight away. Nothing is uploaded after it completes."),
                Triple(1, "Seed to ratio", "Upload until you have given back as much as you took, then stop."),
                Triple(2, "Keep seeding", "Keep uploading until you pause it yourself.")))
            ChoiceRow("Ratio to seed to", "seedRatioX10", 10, listOf(Triple(5, "0.5", "Give back half"), Triple(10, "1.0", "Give back the same amount"), Triple(20, "2.0", "Give back twice as much"), Triple(30, "3.0", "Give back three times as much"))) { Prefs.setF("seedRatio", it / 10f) }
            SwitchRow("Upload on Wi-Fi only", "seedWifiOnly", true, "Finished torrents never seed on mobile data")

            SectionTitle(Icons.Filled.Security, "Privacy and safety")
            ChoiceRow("VPN guard", "vpnMode", 1, listOf(
                Triple(0, "Off", "No VPN checks."),
                Triple(1, "Warn", "Show a warning on the Torrents tab when no VPN is on."),
                Triple(2, "Block", "Stop all torrent traffic, including the DHT crawler, whenever no VPN is on.")), "Other peers in a swarm can see your IP address")
            ChoiceRow("Encryption", "encryption", 1, listOf(
                Triple(0, "Off", "Plain connections only."),
                Triple(1, "Prefer", "Use encrypted connections when the other peer supports them."),
                Triple(2, "Require", "Only connect to peers that encrypt. Fewer peers, sometimes slower.")), "Hides torrent traffic from simple network filters. It does not hide your IP address.")
            SwitchRow("Safety checks", "safety", true, "Warn about likely fakes and refuse to open program files (.exe, .apk and similar)")
            TextRow("IP blocklist", "blocklistUrl", "", "Web address of a blocklist in P2P or DAT format (plain or .gz). Leave empty for none.", display = { if (it.isBlank()) "None" else "$rules ranges" }) { url ->
                scope.launch(Dispatchers.IO) {
                    if (url.isBlank()) { Blocklist.clear(ctx); rules = 0; Ui.say("Blocklist removed") }
                    else { Ui.say("Downloading blocklist…"); val n = Blocklist.download(ctx, url); rules = maxOf(0, n); Ui.say(if (n >= 0) "Blocklist loaded: $n ranges" else "Could not load that blocklist") }
                }
            }

            SectionTitle(Icons.Filled.Hub, "DHT index")
            ChoiceRow("Crawler runs", "crawlMode", 1, listOf(
                Triple(0, "Off", "The index stops growing."),
                Triple(1, "Charging and open", "While Swarm is open, and in the background while charging on Wi-Fi. Recommended."),
                Triple(2, "Only while open", "Only while you have Swarm on screen. Slowest growth."),
                Triple(3, "Always", "Whenever Swarm is running. Fastest growth, noticeable battery use."))) { Crawler.want("always", it == 3); Crawler.update() }
            SwitchRow("Hide adult content", "adultFilter", true, "Torrents with adult names are left out of the index")
            NumberRow("Index size limit", "indexMax", 300000, "torrents", "300000 torrents", "When the index passes this many, the oldest entries are dropped.", 5_000_000)
            ValueRow("Clear the index", if (indexCount >= 0) "%,d".format(indexCount) else "", "Deletes everything the crawler has found") { clearIndex = true }
        }
    }
    if (clearIndex) ChoiceDialog("Clear the DHT index?", "Keep it", "Clear", { clearIndex = false }, {
        clearIndex = false
        scope.launch(Dispatchers.IO) { Db.get(ctx).dhtClear(); indexCount = 0; Ui.say("DHT index cleared") }
    }) { Text("The crawler will start again from empty.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
}
