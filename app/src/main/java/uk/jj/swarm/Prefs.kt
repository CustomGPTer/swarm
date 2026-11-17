package uk.jj.swarm

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** All app settings. Plain SharedPreferences; [theme] and [tick] are observable. */
object Prefs {
    private lateinit var sp: SharedPreferences
    val theme = MutableStateFlow(0)          // 0 light, 1 dark, 2 system
    val tick = MutableStateFlow(0)           // bumps whenever any setting changes

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("swarm", Context.MODE_PRIVATE)
        theme.value = sp.getInt("theme", 0)
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply(); tick.value++
    }

    fun getB(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun getI(k: String, d: Int) = sp.getInt(k, d)
    fun getS(k: String, d: String) = sp.getString(k, d) ?: d
    fun getF(k: String, d: Float) = sp.getFloat(k, d)
    fun setB(k: String, v: Boolean) = put { putBoolean(k, v) }
    fun setI(k: String, v: Int) { put { putInt(k, v) }; if (k == "theme") theme.value = v }
    fun setS(k: String, v: String) = put { putString(k, v) }
    fun setF(k: String, v: Float) = put { putFloat(k, v) }

    // Bandwidth
    val wifiOnly get() = getB("wifiOnly", true)
    val dlLimitKb get() = getI("dlLimitKb", 0)
    val ulLimitKb get() = getI("ulLimitKb", 0)
    val activeLimit get() = getI("activeLimit", 0)
    val maxConnections get() = getI("maxConnections", 200)

    // Storage
    val defaultBase: String get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Swarm").absolutePath
    val baseDir get() = getS("baseDir", defaultBase)
    val autoSort get() = getB("autoSort", true)
    val port get() = getI("port", 0)

    // Behaviour
    val sequentialDefault get() = getB("sequentialDefault", false)
    val addTrackers get() = getB("addTrackers", true)
    val trackers get() = getS("trackers", DEFAULT_TRACKERS)
    val encryption get() = getI("encryption", 1)   // 0 off, 1 prefer, 2 require
    val seedMode get() = getI("seedMode", 1)       // 0 stop, 1 ratio, 2 forever
    val seedRatio get() = getF("seedRatio", 1.0f)
    val seedWifiOnly get() = getB("seedWifiOnly", true)
    val vpnMode get() = getI("vpnMode", 1)         // 0 nothing, 1 warn, 2 block
    val safety get() = getB("safety", true)
    val adultFilter get() = getB("adultFilter", true)

    // Power / schedule
    val autoShutdown get() = getB("autoShutdown", false)
    val batterySaver get() = getI("batterySaver", 0) // 0 off, else pause below this %
    val schedOn get() = getB("schedOn", false)
    val schedStart get() = getI("schedStart", 1)
    val schedEnd get() = getI("schedEnd", 7)
    val chargingOnly get() = getB("chargingOnly", false)

    // DHT crawler: 0 off, 1 charging + Wi-Fi and while open, 2 only while open, 3 always
    val crawlMode get() = getI("crawlMode", 1)
    val indexMax get() = getI("indexMax", 300_000)

    // Players
    val bgVideo get() = getB("bgVideo", false)
    val resumeMusic get() = getB("resumeMusic", true)

    val onboarded get() = getB("onboarded", false)
    val blocklistUrl get() = getS("blocklistUrl", "")

    const val DEFAULT_TRACKERS = """udp://tracker.opentrackr.org:1337/announce
udp://open.stealth.si:80/announce
udp://tracker.torrent.eu.org:451/announce
udp://open.tracker.cl:1337/announce
udp://open.demonii.com:1337/announce
udp://exodus.desync.com:6969/announce
udp://explodie.org:6969/announce"""

    fun trackerList(): List<String> = trackers.lines().map { it.trim() }.filter { it.startsWith("udp://") || it.startsWith("http") }

    /** Everything, for export. */
    fun all(): Map<String, *> = sp.all
    fun importAll(m: Map<String, Any?>) {
        put {
            for ((k, v) in m) when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putInt(k, v.toInt())
                is Double -> putFloat(k, v.toFloat())
                is Float -> putFloat(k, v)
                is String -> putString(k, v)
            }
        }
        theme.value = sp.getInt("theme", 0)
    }
}
