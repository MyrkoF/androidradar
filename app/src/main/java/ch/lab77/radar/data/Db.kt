package ch.lab77.radar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Schéma inspiré de WiGLE, étendu (cahier §6) : `network` (un enregistrement par appareil), `location`
 * (chaque observation horodatée et géolocalisée, rattachée à une session), `session` (un lieu + une date),
 * `estimate` (position estimée + incertitude par émetteur et par session). Toutes les sessions s'accumulent.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "radar.db", null, 2) {

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
                altitude REAL, accuracy REAL, time INTEGER, session_id INTEGER)"""
        )
        db.execSQL("CREATE INDEX idx_loc_bssid ON location(bssid)")
        db.execSQL("CREATE INDEX idx_loc_time ON location(time)")
        db.execSQL("CREATE INDEX idx_net_lasttime ON network(lasttime)")
        createV2(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS session (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, start INTEGER, end INTEGER)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS estimate (
                session_id INTEGER, bssid TEXT, lat REAL, lon REAL, radius REAL, n INTEGER, persistence TEXT, updated INTEGER,
                PRIMARY KEY (session_id, bssid))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE location ADD COLUMN session_id INTEGER")
            createV2(db)
        }
    }

    fun newSession(start: Long, name: String): Long {
        val cv = ContentValues().apply { put("name", name); put("start", start) }
        return writableDatabase.insert("session", null, cv)
    }

    fun endSession(id: Long, end: Long) {
        if (id > 0) writableDatabase.update("session", ContentValues().apply { put("end", end) }, "_id=?", arrayOf(id.toString()))
    }

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

    fun upsertEstimate(sessionId: Long, bssid: String, e: Estimate, now: Long) {
        val cv = ContentValues().apply {
            put("session_id", sessionId); put("bssid", bssid); put("lat", e.lat); put("lon", e.lon)
            put("radius", e.radius); put("n", e.n); put("persistence", e.persistence.name); put("updated", now)
        }
        writableDatabase.insertWithOnConflict("estimate", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun counts(): Pair<Long, Long> {
        val db = readableDatabase
        val n = db.compileStatement("SELECT COUNT(*) FROM network").simpleQueryForLong()
        val l = db.compileStatement("SELECT COUNT(*) FROM location").simpleQueryForLong()
        return n to l
    }

    fun clearAll() {
        writableDatabase.apply { delete("location", null, null); delete("network", null, null); delete("estimate", null, null); delete("session", null, null) }
    }
}
