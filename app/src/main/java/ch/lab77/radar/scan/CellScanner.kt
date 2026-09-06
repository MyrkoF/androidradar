package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository

/**
 * Relevé cellulaire passif (cahier §4 ter) : cellules LTE / 5G NR / WCDMA / GSM vues par le modem, avec leur
 * niveau (RSRP), par position. Métadonnées diffusées seulement — rien n'est émis, rien n'est décodé.
 * Sert à cartographier la couverture (site survey) et à repérer une cellule nouvelle (veille).
 */
class CellScanner(ctx: Context, private val intervalMs: Long = 10_000L) : Sensor {
    override val label = "Cell"
    private val appCtx = ctx.applicationContext
    private val tm = appCtx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    override val isRunning: Boolean get() = running

    private val callback = object : TelephonyManager.CellInfoCallback() {
        override fun onCellInfo(cellInfo: MutableList<CellInfo>) { ingest(cellInfo) }
        override fun onError(errorCode: Int, detail: Throwable?) { fallback() }
    }

    private val tick = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!running) return
            try { tm.requestCellInfoUpdate(ContextCompat.getMainExecutor(appCtx), callback) } catch (_: Exception) { fallback() }
            handler.postDelayed(this, intervalMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallback() {
        try { tm.allCellInfo?.let { ingest(it) } } catch (_: Exception) {}
    }

    override fun start(): Boolean {
        if (running) return true
        running = true
        handler.post(tick)
        ScanRepository.setStatus { it.copy(cellOn = true) }
        ScanRepository.logLine("Cell : relevé démarré (toutes les ${intervalMs / 1000}s)")
        return true
    }

    override fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        ScanRepository.setStatus { it.copy(cellOn = false) }
        ScanRepository.logLine("Cell : relevé arrêté")
    }

    /** Les champs indisponibles valent Int.MAX_VALUE : on affiche « — ». */
    private fun v(x: Int): String = if (x == CellInfo.UNAVAILABLE || x == Int.MAX_VALUE) "—" else x.toString()

    private fun ingest(list: List<CellInfo>) {
        val operatorDefault = tm.networkOperatorName ?: ""
        for (ci in list) {
            when (ci) {
                is CellInfoLte -> {
                    val id = ci.cellIdentity as CellIdentityLte; val s = ci.cellSignalStrength
                    val plmn = (id.mccString ?: "?") + (id.mncString ?: "?")
                    val cell = if (id.ci != CellInfo.UNAVAILABLE) "LTE-$plmn-${id.ci}" else "LTE-$plmn-pci${id.pci}-${id.earfcn}"
                    val op = id.operatorAlphaLong?.toString()?.ifBlank { null } ?: operatorDefault
                    val band = id.bands.firstOrNull()?.let { "b$it " } ?: ""
                    val caps = "tech=LTE ${band}pci=${v(id.pci)} tac=${v(id.tac)} earfcn=${id.earfcn} rsrq=${v(s.rsrq)} sinr=${v(s.rssnr)} ta=${v(s.timingAdvance)}" + (if (ci.isRegistered) " registered" else "")
                    ScanRepository.observe(Kind.CELL, cell, "LTE $op ${band}PCI ${id.pci}", s.rsrp, id.earfcn, caps, vendorOverride = op)
                }
                is CellInfoNr -> {
                    val id = ci.cellIdentity as CellIdentityNr; val s = ci.cellSignalStrength as CellSignalStrengthNr
                    val plmn = (id.mccString ?: "?") + (id.mncString ?: "?")
                    val cell = if (id.nci != CellInfo.UNAVAILABLE_LONG) "NR-$plmn-${id.nci}" else "NR-$plmn-pci${id.pci}-${id.nrarfcn}"
                    val op = id.operatorAlphaLong?.toString()?.ifBlank { null } ?: operatorDefault
                    val band = id.bands.firstOrNull()?.let { "n$it " } ?: ""
                    val caps = "tech=NR ${band}pci=${v(id.pci)} tac=${v(id.tac)} nrarfcn=${id.nrarfcn} rsrq=${v(s.ssRsrq)} sinr=${v(s.ssSinr)}" + (if (ci.isRegistered) " registered" else "")
                    ScanRepository.observe(Kind.CELL, cell, "5G $op ${band}PCI ${id.pci}", s.ssRsrp, id.nrarfcn, caps, vendorOverride = op)
                }
                is CellInfoWcdma -> {
                    val id = ci.cellIdentity as CellIdentityWcdma; val s = ci.cellSignalStrength
                    val plmn = (id.mccString ?: "?") + (id.mncString ?: "?")
                    val cell = if (id.cid != CellInfo.UNAVAILABLE) "WCDMA-$plmn-${id.cid}" else "WCDMA-$plmn-psc${id.psc}-${id.uarfcn}"
                    val op = id.operatorAlphaLong?.toString()?.ifBlank { null } ?: operatorDefault
                    val caps = "tech=WCDMA psc=${v(id.psc)} lac=${v(id.lac)} uarfcn=${id.uarfcn}" + (if (ci.isRegistered) " registered" else "")
                    ScanRepository.observe(Kind.CELL, cell, "3G $op PSC ${id.psc}", s.dbm, id.uarfcn, caps, vendorOverride = op)
                }
                is CellInfoGsm -> {
                    val id = ci.cellIdentity as CellIdentityGsm; val s = ci.cellSignalStrength
                    val plmn = (id.mccString ?: "?") + (id.mncString ?: "?")
                    val cell = if (id.cid != CellInfo.UNAVAILABLE) "GSM-$plmn-${id.cid}" else "GSM-$plmn-arfcn${id.arfcn}-${id.bsic}"
                    val op = id.operatorAlphaLong?.toString()?.ifBlank { null } ?: operatorDefault
                    val caps = "tech=GSM lac=${v(id.lac)} arfcn=${id.arfcn} bsic=${v(id.bsic)}" + (if (ci.isRegistered) " registered" else "")
                    ScanRepository.observe(Kind.CELL, cell, "2G $op ARFCN ${id.arfcn}", s.dbm, id.arfcn, caps, vendorOverride = op)
                }
            }
        }
    }
}
