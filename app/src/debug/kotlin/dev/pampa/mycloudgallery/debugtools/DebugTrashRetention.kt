package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import java.io.File

/**
 * SOLO build debug: la prova sul campo F20 della pulizia del cestino del NAS (docs/prova-500.md, revisione 4B) in **minuti** invece che in
 * 30 giorni. Con `adb shell` si scrive un numero intero di minuti nel marcatore
 * `/sdcard/Android/data/<id>/files/debug-trash-retention-minutes.txt` (cartella dei file esterni dell'app, privata da Android 11): il
 * cestino del NAS tiene le voci quei minuti (almeno 1, mai piu' di 30 giorni) e la pulizia si prova subito. Senza marcatore, o con un
 * contenuto che non e' un numero, la debug tiene i 30 giorni come la release.
 *
 * Solo in `app/src/debug`: la release non lo referenzia e non lo contiene (tools/check.ps1 cerca questo nome e quello del marcatore nel
 * dex dell'APK di release).
 */
internal object DebugTrashRetention {

  /** Il nome del marcatore (lo scrive solo `adb`). */
  const val MARKER: String = "debug-trash-retention-minutes.txt"

  private const val MINUTE_MS = 60_000L
  private const val MAX_MINUTES = 30L * 24 * 60

  /** I millisecondi chiesti dal marcatore, o null (nessun marcatore, contenuto non valido). */
  fun read(context: Context): Long? {
    val marker = context.getExternalFilesDir(null)?.let { File(it, MARKER) } ?: return null
    if (!marker.isFile) return null
    val minutes = runCatching { marker.readText().trim().toLong() }.getOrNull() ?: return null
    return minutes.coerceIn(1, MAX_MINUTES) * MINUTE_MS
  }
}
