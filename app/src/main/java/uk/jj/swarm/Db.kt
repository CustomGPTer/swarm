package uk.jj.swarm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TRow(
    val hash: String, var name: String, val added: Long, var userPaused: Boolean, var seedDone: Boolean,
    var forceSeed: Boolean, var sorted: Boolean, var completed: Boolean, val source: String,
)

data class DhtRow(
    val hash: String, val name: String, val size: Long, val nfiles: Int, val files: String,
    val firstSeen: Long, val seeders: Int, val leechers: Int,
)

data class SourceRow(val id: Long, val type: String, val name: String, val url: String, val apikey: String, val enabled: Boolean)
data class WatchRow(val id: Long, val query: String, val lastCheck: Long, val seen: String)
data class RssRow(val id: Long, val name: String, val url: String, val filter: String, val auto: Boolean, val lastCheck: Long)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "swarm.db", null, 1) {
    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE torrents(hash TEXT PRIMARY KEY, name TEXT, added INTEGER, user_paused INTEGER DEFAULT 0, seed_done INTEGER DEFAULT 0, force_seed INTEGER DEFAULT 0, sorted INTEGER DEFAULT 0, completed INTEGER DEFAULT 0, source TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE dht(id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT UNIQUE, name TEXT, size INTEGER, nfiles INTEGER, files TEXT, first_seen INTEGER, seeders INTEGER DEFAULT -1, leechers INTEGER DEFAULT -1, scraped INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX dht_seen ON dht(first_seen)")
        db.execSQL("CREATE VIRTUAL TABLE dht_fts USING fts4(name, files, tokenize=unicode61)")
        db.execSQL("CREATE TABLE dht_tried(hash TEXT PRIMARY KEY, tries INTEGER, last INTEGER)")
        db.execSQL("CREATE TABLE sources(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, name TEXT, url TEXT, apikey TEXT DEFAULT '', enabled INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE watch(id INTEGER PRIMARY KEY AUTOINCREMENT, query TEXT, last_check INTEGER DEFAULT 0, seen TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE rss(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, url TEXT, filter TEXT DEFAULT '', auto INTEGER DEFAULT 0, last_check INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE rss_seen(k TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE positions(path TEXT PRIMARY KEY, pos INTEGER, dur INTEGER)")
        db.execSQL("CREATE TABLE history(q TEXT PRIMARY KEY, t INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    // ---- torrents registry
    fun torrents(): List<TRow> = writableDatabase.rawQuery("SELECT hash,name,added,user_paused,seed_done,force_seed,sorted,completed,source FROM torrents ORDER BY added DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(TRow(c.getString(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3) != 0, c.getInt(4) != 0, c.getInt(5) != 0, c.getInt(6) != 0, c.getInt(7) != 0, c.getString(8) ?: "")) }
    }

    fun saveTorrent(r: TRow) {
        val v = ContentValues().apply {
            put("hash", r.hash); put("name", r.name); put("added", r.added); put("user_paused", if (r.userPaused) 1 else 0)
            put("seed_done", if (r.seedDone) 1 else 0); put("force_seed", if (r.forceSeed) 1 else 0)
            put("sorted", if (r.sorted) 1 else 0); put("completed", if (r.completed) 1 else 0); put("source", r.source)
        }
        writableDatabase.insertWithOnConflict("torrents", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteTorrent(hash: String) { writableDatabase.delete("torrents", "hash=?", arrayOf(hash)) }

    // ---- DHT index
    fun dhtHas(hash: String): Boolean = writableDatabase.rawQuery("SELECT 1 FROM dht WHERE hash=?", arrayOf(hash)).use { it.moveToFirst() }

    fun dhtTries(hash: String): Int = writableDatabase.rawQuery("SELECT tries FROM dht_tried WHERE hash=?", arrayOf(hash)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun dhtMarkTried(hash: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO dht_tried(hash,tries,last) VALUES(?, COALESCE((SELECT tries FROM dht_tried WHERE hash=?),0)+1, ?)", arrayOf<Any>(hash, hash, System.currentTimeMillis()))
    }

    fun dhtInsert(hash: String, name: String, size: Long, nfiles: Int, files: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply { put("hash", hash); put("name", name); put("size", size); put("nfiles", nfiles); put("files", files); put("first_seen", System.currentTimeMillis()) }
            val id = db.insertWithOnConflict("dht", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            if (id <= 0) return false
            db.insert("dht_fts", null, ContentValues().apply { put("docid", id); put("name", name); put("files", files) })
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun dhtCount(): Long = writableDatabase.rawQuery("SELECT COUNT(*) FROM dht", null).use { it.moveToFirst(); it.getLong(0) }

    private fun dhtRows(sql: String, args: Array<String>): List<DhtRow> = writableDatabase.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(DhtRow(c.getString(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3), c.getString(4) ?: "", c.getLong(5), c.getInt(6), c.getInt(7))) }
    }

    private val cols = "d.hash,d.name,d.size,d.nfiles,d.files,d.first_seen,d.seeders,d.leechers"

    private fun dhtOrder(sort: Int) = when (sort) { 1 -> "d.seeders DESC, d.first_seen DESC"; 2 -> "d.size DESC"; else -> "d.first_seen DESC" }

    fun dhtRecent(limit: Int, sort: Int = 0): List<DhtRow> = dhtRows("SELECT $cols FROM dht d ORDER BY ${dhtOrder(sort)} LIMIT $limit", emptyArray())

    fun dhtGet(hash: String): DhtRow? = dhtRows("SELECT $cols FROM dht d WHERE d.hash=?", arrayOf(hash)).firstOrNull()

    /** sort: 0 newest, 1 seeders, 2 size */
    fun dhtSearch(query: String, sort: Int, limit: Int): List<DhtRow> {
        val m = ftsQuery(query) ?: return dhtRecent(limit, sort)
        val order = dhtOrder(sort)
        return try {
            dhtRows("SELECT $cols FROM dht_fts f JOIN dht d ON d.id=f.docid WHERE dht_fts MATCH ? ORDER BY $order LIMIT $limit", arrayOf(m))
        } catch (e: Exception) { emptyList() }
    }

    fun dhtNeedScrape(limit: Int): List<String> = writableDatabase.rawQuery("SELECT hash FROM dht WHERE scraped=0 ORDER BY first_seen DESC LIMIT $limit", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun dhtSetScrape(hash: String, seeders: Int, leechers: Int) {
        writableDatabase.execSQL("UPDATE dht SET seeders=?, leechers=?, scraped=? WHERE hash=?", arrayOf<Any>(seeders, leechers, System.currentTimeMillis(), hash))
    }

    /** What the swarm itself showed when the details were fetched. Does not count as a tracker lookup. */
    fun dhtSetSwarm(hash: String, seeders: Int, leechers: Int) {
        writableDatabase.execSQL("UPDATE dht SET seeders=?, leechers=? WHERE hash=?", arrayOf<Any>(seeders, leechers, hash))
    }

    fun dhtMarkScraped(hash: String) {
        writableDatabase.execSQL("UPDATE dht SET scraped=? WHERE hash=?", arrayOf<Any>(System.currentTimeMillis(), hash))
    }

    fun dhtPrune(max: Int) {
        val n = dhtCount()
        if (n <= max) return
        val db = writableDatabase
        val cut = n - max
        db.execSQL("DELETE FROM dht_fts WHERE docid IN (SELECT id FROM dht ORDER BY first_seen ASC LIMIT $cut)")
        db.execSQL("DELETE FROM dht WHERE id IN (SELECT id FROM dht ORDER BY first_seen ASC LIMIT $cut)")
        db.execSQL("DELETE FROM dht_tried WHERE last < ?", arrayOf<Any>(System.currentTimeMillis() - 7L * 86400000))
    }

    fun dhtClear() { writableDatabase.apply { execSQL("DELETE FROM dht"); execSQL("DELETE FROM dht_fts"); execSQL("DELETE FROM dht_tried") } }

    // ---- sources
    fun sources(): List<SourceRow> = writableDatabase.rawQuery("SELECT id,type,name,url,apikey,enabled FROM sources ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(SourceRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4) ?: "", c.getInt(5) != 0)) }
    }
    fun addSource(type: String, name: String, url: String, apikey: String) {
        writableDatabase.insert("sources", null, ContentValues().apply { put("type", type); put("name", name); put("url", url); put("apikey", apikey); put("enabled", 1) })
    }
    fun setSourceEnabled(id: Long, on: Boolean) { writableDatabase.execSQL("UPDATE sources SET enabled=? WHERE id=?", arrayOf<Any>(if (on) 1 else 0, id)) }
    fun deleteSource(id: Long) { writableDatabase.delete("sources", "id=?", arrayOf(id.toString())) }

    // ---- watchlist
    fun watches(): List<WatchRow> = writableDatabase.rawQuery("SELECT id,query,last_check,seen FROM watch ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(WatchRow(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3) ?: "")) }
    }
    fun addWatch(q: String) { writableDatabase.insert("watch", null, ContentValues().apply { put("query", q) }) }
    fun updateWatch(id: Long, seen: String) { writableDatabase.execSQL("UPDATE watch SET seen=?, last_check=? WHERE id=?", arrayOf<Any>(seen, System.currentTimeMillis(), id)) }
    fun deleteWatch(id: Long) { writableDatabase.delete("watch", "id=?", arrayOf(id.toString())) }

    // ---- rss
    fun feeds(): List<RssRow> = writableDatabase.rawQuery("SELECT id,name,url,filter,auto,last_check FROM rss ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(RssRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3) ?: "", c.getInt(4) != 0, c.getLong(5))) }
    }
    fun addFeed(name: String, url: String, filter: String, auto: Boolean) {
        writableDatabase.insert("rss", null, ContentValues().apply { put("name", name); put("url", url); put("filter", filter); put("auto", if (auto) 1 else 0) })
    }
    fun deleteFeed(id: Long) { writableDatabase.delete("rss", "id=?", arrayOf(id.toString())) }
    fun touchFeed(id: Long) { writableDatabase.execSQL("UPDATE rss SET last_check=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id)) }
    fun rssSeen(k: String): Boolean = writableDatabase.rawQuery("SELECT 1 FROM rss_seen WHERE k=?", arrayOf(k)).use { it.moveToFirst() }
    fun rssMark(k: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO rss_seen(k) VALUES(?)", arrayOf(k)) }

    // ---- playback positions
    fun position(path: String): Long = writableDatabase.rawQuery("SELECT pos FROM positions WHERE path=?", arrayOf(path)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    fun savePosition(path: String, pos: Long, dur: Long) { writableDatabase.execSQL("INSERT OR REPLACE INTO positions(path,pos,dur) VALUES(?,?,?)", arrayOf<Any>(path, pos, dur)) }

    // ---- search history
    fun history(): List<String> = writableDatabase.rawQuery("SELECT q FROM history ORDER BY t DESC LIMIT 20", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun addHistory(q: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO history(q,t) VALUES(?,?)", arrayOf<Any>(q, System.currentTimeMillis())) }
    fun clearHistory() { writableDatabase.execSQL("DELETE FROM history") }
}

/** Turns free text into an FTS4 MATCH expression: every word must appear, last word is a prefix. */
fun ftsQuery(q: String): String? {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return null
    return words.mapIndexed { i, w -> if (i == words.lastIndex) "$w*" else w }.joinToString(" ")
}
