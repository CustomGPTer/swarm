package uk.jj.swarm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Links to TorusApps.com, and the update check against its signed catalogue. Sends nothing about the user. */
object TorusLinks {
    const val SLUG = "swarm"
    const val HOME = "https://torusapps.com/"
    const val PAGE = "https://torusapps.com/swarm/"
    const val PRIVACY = "https://torusapps.com/privacy/"
    const val EMAIL = "hello@torusapps.com"
    const val DEVELOPER = "Developed by Jonathan Jackson"
    private const val CATALOGUE = "https://torusapps.com/catalogue.json"
    private const val KEY = "w0UBOBm/Y9AEHf0UmYVuuedWubQzc2T6RRHzhpZuSYM="

    /** The newest version name listed for this app in the signed catalogue, or null if it can't be read or the seal is wrong. */
    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) { latestVersionNow() }

    /** Same as [latestVersion] for callers already off the main thread. */
    fun latestVersionNow(): String? =
        runCatching {
            val body = get(CATALOGUE)
            val sig = android.util.Base64.decode(String(get("$CATALOGUE.sig"), Charsets.US_ASCII).trim(), android.util.Base64.DEFAULT)
            val key = android.util.Base64.decode(KEY, android.util.Base64.DEFAULT)
            if (!Ed25519Verify.verify(key, body, sig)) return@runCatching null
            val apps = org.json.JSONObject(String(body, Charsets.UTF_8)).getJSONArray("apps")
            (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("id") == SLUG }?.optString("version")?.ifBlank { null }
        }.getOrNull()

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try {
            check(c.responseCode == 200)
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
