package ch.lab77.radar.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Schéma inspiré de WiGLE, étendu (cahier §6) : `network` (un enregistrement par appareil), `location`
 * (chaque observation horodatée et géolocalisée, rattachée à une session, avec direction éventuelle),
 * `session` (un lieu + une date), `estimate` (position estimée + incertitude par émetteur et par session,
 * avec nom/sécurité/type/catégorie pour le diff), `whitelist` (connus du lieu : pas d'alerte).
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "radar.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE network (
                bssid TEXT PRIMARY KEY, ssid TEXT, type TEXT, vendor TEXT, vendor_long TEXT, category TEXT,
                frequency INTEGER, capabilities TEXT, bestlevel INTEGER, bestlat REAL, bestlon REAL,
                lastlat REAL, lastlon REAL, firsttime INTEGER, lasttime INTEGER, seen INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE location (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, bssid TEXT, level INTEGER, lat REAL, lon REAL,
                altitude REAL, accuracy REAL, time INTEGER, session_id INTEGER, bearing REAL)"""
        )
        db.execSQL("CREATE INDEX idx_loc_bssid ON location(bssid)")
        db.execSQL("CREATE INDEX idx_loc_time ON location(time)")
        db.execSQL("CREATE INDEX idx_net_lasttime ON network(lasttime)")
        createV2(db); createV4(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS session (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, start INTEGER, end INTEGER)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS estimate (
                session_id INTEGER, bssid TEXT, lat REAL, lon REAL, radius REAL, n INTEGER, persistence TEXT, updated INTEGER,
                PRIMARY KEY (session_id, bssid))"""
        )
    }

    private fun createV4(db: SQLiteDatabase) {
        for (c in listOf("name TEXT", "security TEXT", "kind TEXT", "category TEXT", "confirmed INTEGER")) {
            try { db.execSQL("ALTER TABLE estimate ADD COLUMN $c") } catch (_: Exception) {}
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS whitelist (bssid TEXT PRIMARY KEY, name TEXT, added INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_loc_session ON location(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) { db.execSQL("ALTER TABLE location ADD COLUMN session_id INTEGER"); createV2(db) }
        if (oldVersion < 3) db.execSQL("ALTER TABLE location ADD COLUMN bearing REAL")
        if (oldVersion < 4) createV4(db)
    }

    // ---- sessions ---------------------------------------------------------------------------------

    fun newSession(start: Long, name: String): Long =
        writableDatabase.insert("session", null, ContentValues().apply { put("name", name); put("start", start) })

    fun endSession(id: Long, end: Long) {
        if (id > 0) writableDatabase.update("session", ContentValues().apply { put("end", end) }, "_id=?", arrayOf(id.toString()))
    }

    fun renameSession(id: Long, name: String) {
        writableDatabase.update("session", ContentValues().apply { put("name", name) }, "_id=?", arrayOf(id.toString()))
    }

    fun deleteSession(id: Long) {
        writableDatabase.apply {
            delete("estimate", "session_id=?", arrayOf(id.toString()))
            delete("location", "session_id=?", arrayOf(id.toString()))
            delete("session", "_id=?", arrayOf(id.toString()))
        }
    }

    fun sessions(): List<SessionInfo> {
        val out = ArrayList<SessionInfo>()
        readableDatabase.rawQuery(
            """SELECT s._id, s.name, s.start, s.end,
                 (SELECT COUNT(*) FROM estimate e WHERE e.session_id = s._id AND e.lat IS NOT NULL) AS positioned
               FROM session s ORDER BY s.start DESC""", null
        ).use { c -> while (c.moveToNext()) out += SessionInfo(c.getLong(0), c.getString(1) ?: "", c.getLong(2), if (c.isNull(3)) null else c.getLong(3), c.getInt(4)) }
        return out
    }

    fun sessionEstimates(id: Long): Map<String, EstRow> {
        val out = HashMap<String, EstRow>()
        readableDatabase.rawQuery(
            "SELECT bssid, lat, lon, radius, n, persistence, name, security, kind, category, confirmed FROM estimate WHERE session_id=? AND lat IS NOT NULL",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val bssid = c.getString(0)
                out[bssid] = EstRow(
                    bssid, c.getDouble(1), c.getDouble(2), c.getFloat(3), c.getInt(4),
                    runCatching { Persistence.valueOf(c.getString(5) ?: "") }.getOrDefault(Persistence.UNKNOWN),
                    c.getString(6) ?: "", c.getString(7) ?: "", runCatching { Kind.valueOf(c.getString(8) ?: "") }.getOrDefault(Kind.WIFI),
                    runCatching { Category.valueOf(c.getString(9) ?: "") }.getOrDefault(Category.UNKNOWN), c.getInt(10) == 1,
                )
            }
        }
        return out
    }

    /** Observations d'une session, par émetteur — pour reprendre l'affinage là où il s'était arrêté. */
    fun sessionObservations(id: Long): Map<String, List<Obs>> {
        val out = HashMap<String, ArrayList<Obs>>()
        readableDatabase.rawQuery(
            "SELECT bssid, time, lat, lon, accuracy, level, bearing FROM location WHERE session_id=? AND lat IS NOT NULL ORDER BY time",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val list = out.getOrPut(c.getString(0)) { ArrayList() }
                if (list.size < Estimator.MAX_OBS) list += Obs(c.getLong(1), c.getDouble(2), c.getDouble(3), c.getFloat(4), c.getInt(5), bearing = if (c.isNull(6)) null else c.getFloat(6))
            }
        }
        return out
    }

    /** Appareils d'une session (ceux qui ont une estimation), reconstitués depuis `network`. */
    fun sessionDevices(id: Long): List<Device> {
        val out = ArrayList<Device>()
        readableDatabase.rawQuery(
            """SELECT n.bssid, n.ssid, n.type, n.vendor, n.vendor_long, n.category, n.frequency, n.capabilities, n.bestlevel,
                      n.firsttime, n.lasttime, n.seen, n.lastlat, n.lastlon
               FROM network n JOIN estimate e ON e.bssid = n.bssid WHERE e.session_id=?""", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val kind = runCatching { Kind.valueOf(c.getString(2) ?: "") }.getOrDefault(Kind.WIFI)
                val cat = runCatching { Category.valueOf(c.getString(5) ?: "") }.getOrDefault(Category.UNKNOWN)
                out += Device(
                    kind, c.getString(0), c.getString(1) ?: "", c.getInt(8), c.getInt(8), c.getInt(6), c.getString(7) ?: "",
                    c.getString(3) ?: "", c.getString(4) ?: "", cat, c.getLong(9), c.getLong(10), c.getInt(11),
                    if (c.isNull(12)) null else c.getDouble(12), if (c.isNull(13)) null else c.getDouble(13), null, null,
                )
            }
        }
        return out
    }

    // ---- appareils / observations -----------------------------------------------------------------

    fun upsert(d: Device, isNew: Boolean, bestImproved: Boolean, sessionId: Long) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("bssid", d.id); put("ssid", d.name); put("type", d.kind.name)
            put("vendor", d.vendor); put("vendor_long", d.vendorLong); put("category", d.category.name)
            put("frequency", d.frequency); put("capabilities", d.capabilities)
            put("lastlat", d.lat); put("lastlon", d.lon); put("lasttime", d.lastSeen); put("seen", d.seenCount)
            if (isNew) { put("firsttime", d.firstSeen) }
            if (isNew || bestImproved) { put("bestlevel", d.bestRssi); put("bestlat", d.lat); put("bestlon", d.lon) }
        }
        if (isNew) db.insertWithOnConflict("network", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        else db.update("network", cv, "bssid=?", arrayOf(d.id))

        if (d.lat != null && d.lon != null) {
            val lv = ContentValues().apply {
                put("bssid", d.id); put("level", d.rssi); put("lat", d.lat); put("lon", d.lon)
                put("altitude", d.altitude); put("accuracy", d.accuracy); put("time", d.lastSeen); put("session_id", sessionId)
            }
            db.insert("location", null, lv)
        }
    }

    /** Observation de direction (guide de marche) : d'où, vers où. */
    fun insertBearing(sessionId: Long, bssid: String, lat: Double, lon: Double, acc: Float, rssi: Int, bearing: Float, now: Long) {
        val lv = ContentValues().apply {
            put("bssid", bssid); put("level", rssi); put("lat", lat); put("lon", lon); put("accuracy", acc)
            put("time", now); put("session_id", sessionId); put("bearing", bearing)
        }
        writableDatabase.insert("location", null, lv)
    }

    fun upsertEstimate(sessionId: Long, d: Device, e: Estimate, now: Long) {
        val confirmed = e.rttFix || e.bearingFix || e.locked || e.persistence == Persistence.STATIONARY
        val cv = ContentValues().apply {
            put("session_id", sessionId); put("bssid", d.id); put("lat", e.lat); put("lon", e.lon)
            put("radius", e.radius); put("n", e.n); put("persistence", e.persistence.name); put("updated", now)
            put("name", d.name); put("security", d.security); put("kind", d.kind.name); put("category", d.category.name)
            put("confirmed", if (confirmed) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("estimate", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- liste blanche ----------------------------------------------------------------------------

    fun whitelist(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT bssid FROM whitelist", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun addWhitelist(bssid: String, name: String) {
        writableDatabase.insertWithOnConflict("whitelist", null, ContentValues().apply { put("bssid", bssid); put("name", name); put("added", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeWhitelist(bssid: String) { writableDatabase.delete("whitelist", "bssid=?", arrayOf(bssid)) }
    fun clearWhitelist() { writableDatabase.delete("whitelist", null, null) }

    fun counts(): Pair<Long, Long> {
        val db = readableDatabase
        val n = db.compileStatement("SELECT COUNT(*) FROM network").simpleQueryForLong()
        val l = db.compileStatement("SELECT COUNT(*) FROM location").simpleQueryForLong()
        return n to l
    }

    fun clearAll() {
        writableDatabase.apply { delete("location", null, null); delete("network", null, null); delete("estimate", null, null); delete("session", null, null); delete("whitelist", null, null) }
    }
}
