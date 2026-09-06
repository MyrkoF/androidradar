package org.equalium.sonde.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Schéma inspiré de WiGLE : une table `network` (un enregistrement par appareil, meilleur signal, dernière
 * position) et une table `location` (chaque observation horodatée et géolocalisée). Toutes les sessions
 * s'accumulent ; l'export travaille sur une fenêtre temporelle.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "sonde.db", null, 1) {

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
                altitude REAL, accuracy REAL, time INTEGER)"""
        )
        db.execSQL("CREATE INDEX idx_loc_bssid ON location(bssid)")
        db.execSQL("CREATE INDEX idx_loc_time ON location(time)")
        db.execSQL("CREATE INDEX idx_net_lasttime ON network(lasttime)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun upsert(d: Device, isNew: Boolean, bestImproved: Boolean) {
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
                put("altitude", d.altitude); put("accuracy", d.accuracy); put("time", d.lastSeen)
            }
            db.insert("location", null, lv)
        }
    }

    fun counts(): Pair<Long, Long> {
        val db = readableDatabase
        val n = db.compileStatement("SELECT COUNT(*) FROM network").simpleQueryForLong()
        val l = db.compileStatement("SELECT COUNT(*) FROM location").simpleQueryForLong()
        return n to l
    }

    fun clearAll() {
        writableDatabase.apply { delete("location", null, null); delete("network", null, null) }
    }
}
