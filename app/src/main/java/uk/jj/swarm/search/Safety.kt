package uk.jj.swarm.search

import uk.jj.swarm.Kind

/** Plain-English warnings about a torrent before it is downloaded or a file before it is opened. */
object Safety {
    data class Risk(val level: Int, val reasons: List<String>)   // 0 fine, 1 caution, 2 danger

    private val MEDIA_TITLE = Regex("(?i)(1080p|720p|2160p|480p|x264|x265|h\\.?264|hevc|bluray|web-?dl|webrip|hdtv|dvdrip|hdcam|\\bflac\\b|\\bmp3\\b|epub|s\\d{2}e\\d{2})")
    private val DOUBLE_EXT = Regex("(?i)\\.(mkv|mp4|avi|mov|mp3|flac|pdf|epub|jpg|png|txt|docx?)\\.(exe|scr|bat|cmd|com|msi|apk|vbs|js|jar|lnk|ps1|pif)$")

    /** [files] is name to size; pass null when the file list is not known yet. */
    fun assess(title: String, size: Long, seeders: Int, files: List<Pair<String, Long>>?): Risk {
        val reasons = ArrayList<String>()
        var level = 0
        val looksMedia = MEDIA_TITLE.containsMatchIn(title)
        if (files != null && files.isNotEmpty()) {
            val risky = files.filter { Kind.ext(it.first) in Kind.RISKY }
            val biggest = files.maxByOrNull { it.second }?.first.orEmpty()
            val mediaInside = Kind.isVideo(biggest) || Kind.isAudio(biggest) || Kind.ext(biggest) in Kind.BOOK
            if (files.any { DOUBLE_EXT.containsMatchIn(it.first) }) { level = 2; reasons += "A file is disguised with a double ending (for example .mkv.exe). This is a common malware trick." }
            if (risky.isNotEmpty()) {
                if (looksMedia || mediaInside) { level = 2; reasons += "Contains a program file (.${Kind.ext(risky[0].first)}) inside what looks like a video, music or book download." }
                else { level = maxOf(level, 1); reasons += "Contains program files (.${Kind.ext(risky[0].first)}). Only run them if you trust where this came from." }
            }
            if (looksMedia && !mediaInside && risky.isEmpty()) { level = maxOf(level, 1); reasons += "The name suggests a video or music, but the biggest file inside is a .${Kind.ext(biggest).ifEmpty { "?" }}." }
            if (files.any { it.first.contains("password", true) && Kind.ext(it.first) in setOf("txt", "url", "html") }) { level = maxOf(level, 1); reasons += "Includes a 'password' note, which often means a locked archive that wants you to visit a website." }
        }
        if (looksMedia && Regex("(?i)(1080p|720p|2160p|bluray|web-?dl|webrip|hdtv)").containsMatchIn(title) && size in 1 until 30L * 1024 * 1024) { level = maxOf(level, 1); reasons += "Very small for a video of this quality. It may be a fake." }
        if (seeders == 0) { level = maxOf(level, 1); reasons += "No seeders right now, so it may never finish." }
        return Risk(level, reasons)
    }

    /** Files Swarm refuses to open from inside the app. */
    fun refuseOpen(name: String): Boolean = Kind.ext(name) in Kind.RISKY
}
