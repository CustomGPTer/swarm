package uk.jj.swarm

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object Fmt {
    fun size(b: Long): String {
        if (b < 0) return "–"
        if (b < 1024) return "$b B"
        val u = arrayOf("K", "M", "G", "T")
        var v = b / 1024.0; var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return if (v >= 100 || v == Math.floor(v)) String.format(Locale.UK, "%.0f %s", v, u[i]) else String.format(Locale.UK, "%.1f %s", v, u[i])
    }

    fun speed(bps: Long): String = size(bps) + "/s"

    fun eta(sec: Long): String = when {
        sec < 0 -> "∞"
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        sec < 86400 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
        else -> "${sec / 86400}d ${(sec % 86400) / 3600}h"
    }

    fun age(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return "–"
        val s = (now - epochMs) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            s < 86400L * 30 -> "${s / 86400} d ago"
            s < 86400L * 365 -> "${s / (86400L * 30)} mo ago"
            else -> "${s / (86400L * 365)} y ago"
        }
    }

    fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.UK, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format(Locale.UK, "%d:%02d", s / 60, s % 60)
    }
}

object Magnet {
    private val HEX = Regex("[0-9a-fA-F]{40}")
    private val B32 = Regex("[A-Za-z2-7]{32}")

    /** Lower-case 40-char hex info-hash from a magnet link, or null. */
    fun hash(magnet: String): String? {
        val m = Regex("xt=urn:btih:([^&]+)", RegexOption.IGNORE_CASE).find(magnet) ?: return null
        val v = m.groupValues[1]
        return when {
            HEX.matches(v) -> v.lowercase()
            B32.matches(v) -> base32ToHex(v)
            else -> null
        }
    }

    fun name(magnet: String): String? {
        val m = Regex("[?&]dn=([^&]+)").find(magnet) ?: return null
        return try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (e: Exception) { m.groupValues[1] }
    }

    fun build(hash: String, name: String?, trackers: List<String> = emptyList()): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(hash)
        if (!name.isNullOrBlank()) sb.append("&dn=").append(URLEncoder.encode(name, "UTF-8"))
        for (t in trackers) sb.append("&tr=").append(URLEncoder.encode(t, "UTF-8"))
        return sb.toString()
    }

    fun isHash(s: String) = HEX.matches(s.trim())

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0L; var n = 0
        val out = StringBuilder()
        for (ch in s.uppercase()) {
            val v = alphabet.indexOf(ch); if (v < 0) return null
            bits = (bits shl 5) or v.toLong(); n += 5
            while (n >= 4) { out.append(((bits shr (n - 4)) and 0xF).toString(16)); n -= 4 }
            bits = bits and ((1L shl n) - 1)
        }
        return if (out.length >= 40) out.substring(0, 40) else null
    }
}

object Kind {
    val VIDEO = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "webm", "ts", "mpg", "mpeg", "flv", "m2ts", "3gp")
    val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "m4b", "alac")
    val BOOK = setOf("epub", "mobi", "azw3", "pdf", "cbz", "cbr", "djvu", "fb2")
    val RISKY = setOf("exe", "scr", "bat", "cmd", "com", "msi", "apk", "vbs", "js", "jar", "lnk", "ps1", "pif", "hta", "wsf", "dmg")
    private val TV = Regex("(?i)(s\\d{1,2}[ ._-]?e\\d{1,3}|\\b\\d{1,2}x\\d{2}\\b|season[ ._-]?\\d+|complete[ ._-]series)")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun isVideo(name: String) = ext(name) in VIDEO
    fun isAudio(name: String) = ext(name) in AUDIO

    /** Folder a finished torrent is sorted into, from its name and biggest file. */
    fun folder(torrentName: String, biggestFile: String): String {
        val e = ext(biggestFile)
        return when {
            e in VIDEO -> if (TV.containsMatchIn(torrentName) || TV.containsMatchIn(biggestFile)) "TV" else "Movies"
            e in AUDIO -> "Music"
            e in BOOK -> "Books"
            else -> "Other"
        }
    }
}
