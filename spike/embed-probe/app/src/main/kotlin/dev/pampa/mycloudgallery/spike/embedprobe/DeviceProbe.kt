package dev.pampa.mycloudgallery.spike.embedprobe

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

data class MemSnapshot(val pssMb: Double, val rssMb: Double, val hwmMb: Double, val nativeHeapMb: Double) {
    fun toJson(): JSONObject = JSONObject().num("pssMb", pssMb, 1).num("rssMb", rssMb, 1)
        .num("rssPeakMb", hwmMb, 1).num("nativeHeapMb", nativeHeapMb, 1)

    fun short() = "PSS ${Fmt.mb(pssMb)} / RSS ${Fmt.mb(rssMb)} / picco RSS ${Fmt.mb(hwmMb)}"
}

/** chargeUah = contatore di carica residua in microampere-ora (piu fine del %), -1 se il dispositivo non lo espone. */
data class BatterySnapshot(val levelPct: Int, val tempC: Double, val charging: Boolean, val chargeUah: Long = -1) {
    fun toJson(): JSONObject = JSONObject().put("levelPct", levelPct).num("tempC", tempC, 1).put("charging", charging)
        .num("chargeMah", if (chargeUah > 0) chargeUah / 1000.0 else Double.NaN, 1)
}

class ThermalTracker(private val pm: PowerManager) {
    @Volatile
    var maxStatus: Int = pm.currentThermalStatus
        private set
    private val executor = Executor { it.run() }
    private val listener = PowerManager.OnThermalStatusChangedListener { s -> if (s > maxStatus) maxStatus = s }

    fun start() {
        maxStatus = pm.currentThermalStatus
        pm.addThermalStatusListener(executor, listener)
    }

    fun stop() {
        pm.removeThermalStatusListener(listener)
    }
}

class DeviceProbe(private val ctx: Context) {
    private val pm = ctx.getSystemService(PowerManager::class.java)

    fun memory(): MemSnapshot {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        var rssKb = -1L
        var hwmKb = -1L
        try {
            for (line in File("/proc/self/status").readLines()) {
                if (line.startsWith("VmRSS:")) rssKb = kb(line)
                else if (line.startsWith("VmHWM:")) hwmKb = kb(line)
            }
        } catch (_: Exception) {
        }
        return MemSnapshot(
            pssMb = mi.totalPss / 1024.0,
            rssMb = if (rssKb >= 0) rssKb / 1024.0 else Double.NaN,
            hwmMb = if (hwmKb >= 0) hwmKb / 1024.0 else Double.NaN,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1048576.0,
        )
    }

    private fun kb(line: String): Long = line.filter { it.isDigit() }.toLongOrNull() ?: -1L

    /** 0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN. */
    fun thermalStatus(): Int = pm.currentThermalStatus

    /** 1.0 = soglia di throttling severo. NaN se non disponibile (il sistema limita le chiamate frequenti). */
    fun thermalHeadroom(): Double = try {
        pm.getThermalHeadroom(0).toDouble()
    } catch (_: Exception) {
        Double.NaN
    }

    fun newThermalTracker() = ThermalTracker(pm)

    fun battery(): BatterySnapshot {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val counter = try {
            ctx.getSystemService(BatteryManager::class.java).getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (_: Exception) {
            Long.MIN_VALUE
        }
        return BatterySnapshot(
            levelPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            tempC = if (temp == Int.MIN_VALUE) Double.NaN else temp / 10.0,
            charging = plugged,
            chargeUah = if (counter > 0) counter else -1,
        )
    }

    fun deviceInfo(): JSONObject {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("hardware", Build.HARDWARE)
            .put("socManufacturer", Build.SOC_MANUFACTURER)
            .put("socModel", Build.SOC_MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
            .num("totalRamMb", mi.totalMem / 1048576.0, 0)
            .put("app", JSONObject().put("name", "embed-probe").put("version", "0.1-spike").put("litertlm", "0.18.0"))
    }
}
