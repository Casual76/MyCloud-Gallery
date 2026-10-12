package dev.pampa.mycloudgallery.spike.embedprobe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raccoglie gli eventi della sessione e riscrive a ogni evento il file
 * getExternalFilesDir(null)/embedprobe-<timestamp>.json. Solo numeri e parametri: nessun nome di file.
 */
class ResultsLog(ctx: Context, private val device: JSONObject) {
    private val events = JSONArray()
    private val started = Date()
    val file: File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir,
        "embedprobe-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(started) + ".json",
    )

    @Synchronized
    fun add(type: String, body: JSONObject) {
        body.put("type", type)
        body.put("tMs", System.currentTimeMillis())
        events.put(body)
        write()
    }

    @Synchronized
    fun toJsonString(): String = JSONObject()
        .put("schema", 2)
        .put("startedAtMs", started.time)
        .put("startedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(started))
        .put("device", device)
        .put("indexRunsByBackend", indexRunsByBackend())
        .put("events", events)
        .toString(2)

    /**
     * Vista compatta per confrontare le serie: le esecuzioni di indicizzazione raggruppate per backend
     * (CPU / GPU / NPU), con i numeri che servono a decidere. I dettagli restano negli eventi.
     */
    private fun indexRunsByBackend(): JSONObject {
        val out = JSONObject()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (e.optString("type") != "index") continue
            val backend = e.optString("backend", "?")
            val cfg = e.optJSONObject("config")
            val spec = cfg?.optJSONObject("spec")
            val embed = e.optJSONObject("embedMs")
            val thr = e.optJSONObject("throughput")
            val drop = e.optJSONObject("thermalDrop")
            val therm = e.optJSONObject("thermal")
            val batt = e.optJSONObject("batteryUse")
            val used = e.optJSONObject("backendUsed")
            val mem = e.optJSONObject("memory")
            val row = JSONObject()
                .put("eventIndex", i)
                .put("tMs", e.optLong("tMs"))
                .put("modelVariant", spec?.optString("model"))
                .put("visionTokens", spec?.optInt("visionTokens"))
                .put("resolution", cfg?.optInt("resolution"))
                .put("convert", cfg?.optString("convert"))
                .put("pipelined", cfg?.optBoolean("pipelined"))
                .put("images", e.optJSONObject("counts")?.optInt("embedded"))
                .put("embedMsP50", embed?.opt("p50")).put("embedMsP95", embed?.opt("p95"))
                .put("embedOnlyImgPerSec", thr?.opt("embedOnlyImgPerSec"))
                .put("sumPhasesImgPerSec", thr?.opt("sumPhasesImgPerSec"))
                .put("wallImgPerSec", thr?.opt("wallImgPerSec"))
                .put("thermalDropEmbedPct", drop?.opt("dropEmbedOnlyPct"))
                .put("thermalStatusMax", therm?.opt("statusMax"))
                .put("batteryTempMaxC", therm?.opt("batteryTempMaxC"))
                .put("batteryPctPer1000Images", batt?.opt("pctPer1000Images"))
                .put("batteryMahPer1000Images", batt?.opt("mahPer1000Images"))
                .put("batteryMeasurementValid", batt?.opt("valid"))
                .put("embedCpuCores", used?.opt("embedCpuCores"))
                .put("peakPssMb", mem?.opt("peakPssMb"))
            val arr = out.optJSONArray(backend) ?: JSONArray().also { out.put(backend, it) }
            arr.put(row)
        }
        return out
    }

    @Synchronized
    fun eventCount(): Int = events.length()

    private fun write() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(toJsonString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
            // Il file e un di piu: la copia negli appunti resta disponibile.
        }
    }
}
