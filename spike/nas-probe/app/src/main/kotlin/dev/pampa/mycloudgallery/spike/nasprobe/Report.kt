package dev.pampa.mycloudgallery.spike.nasprobe

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Costruzione e salvataggio del rapporto JSON. Mai password, utente, share, cartella, MAC o nomi di file. */
object Report {

    fun build(
        mode: String,
        settings: ProbeSettings,
        startedAtMs: Long,
        durationMs: Long,
        interrupted: Boolean,
        results: List<TestResult>,
    ): JSONObject {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT)
        iso.timeZone = TimeZone.getDefault()
        return JSONObject().apply {
            put("strumento", "nasprobe")
            put("versioneRapporto", 1)
            put("modo", mode)
            put("inizio", iso.format(Date(startedAtMs)))
            put("durataMs", durationMs)
            put("interrotto", interrupted)
            put("smbj", SMBJ_VERSION)
            put(
                "dispositivo",
                JSONObject().apply {
                    put("produttore", Build.MANUFACTURER)
                    put("modello", Build.MODEL)
                    put("android", Build.VERSION.RELEASE)
                    put("sdk", Build.VERSION.SDK_INT)
                },
            )
            put(
                "impostazioni",
                JSONObject().apply {
                    put("host", settings.host.trim())
                    put("cartellaIndicata", settings.folderPath.isNotEmpty())
                    put("secondaShareIndicata", settings.share2.isNotBlank())
                    put("macIndicato", settings.mac.isNotBlank())
                    put("cifraturaRichiesta", settings.requireEncryption)
                    put("socketLegatoAWifi", settings.bindToWifi)
                    put("vociInventario", settings.inventoryEntries.size)
                },
            )
            put("risultati", org.json.JSONArray().also { a -> results.forEach { a.put(it.toJson()) } })
        }
    }

    /** Salva in getExternalFilesDir(null)/nasprobe-<timestamp>.json (ripiego: cartella interna dell'app). */
    fun save(ctx: Context, json: JSONObject, startedAtMs: Long, prefix: String = "nasprobe"): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startedAtMs))
        var file = File(dir, "$prefix-$stamp.json")
        var n = 1
        while (file.exists()) file = File(dir, "$prefix-$stamp-${n++}.json")
        file.writeText(json.pretty(), Charsets.UTF_8)
        return file
    }
}
