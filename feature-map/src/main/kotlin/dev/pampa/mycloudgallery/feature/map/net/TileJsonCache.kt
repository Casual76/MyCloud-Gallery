package dev.pampa.mycloudgallery.feature.map.net

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

// LA VERSIONE DEL SERVER E L'ATTESA DOPO UN ERRORE (Fase 9, P1; docs/fase9-progetto.md §2.8, D22, E-MAP3, E-MAP13). Lo stile chiede le tessere
// all'URL **stabile** `/planet/v/{z}/{x}/{y}.pbf` (cosi' cache e regioni restano legate a quello); l'intercettore lo riscrive sulla versione
// corrente, che legge dal TileJSON `/planet` **al piu' una volta al giorno** (e dopo un riavvio del telefono) e ricorda su file. Un TileJSON
// che non e' quello atteso non entra: la versione resta quella di prima, o nessuna (e allora nessuna tessera esce). Orologio solo monotono.

/** Un istante dell'orologio monotono: [boot] distingue un riavvio del telefono (dopo il quale [ms] riparte da zero). */
internal data class MonoStamp(val boot: Long, val ms: Long)

internal fun interface MapNetClock {
  fun now(): MonoStamp
}

/** Il TileJSON di OpenFreeMap letto e controllato: la sola cosa che interessa e' la versione nell'URL delle tessere. */
internal object TileJsonReader {
  private val TILES_URL = Regex("^https://tiles\\.openfreemap\\.org/planet/([^/]+)/\\{z\\}/\\{x\\}/\\{y\\}\\.pbf$")

  /** La versione del server dal testo del TileJSON, o null se non e' esattamente la forma attesa (host, percorso, versione valida). */
  fun version(text: String): String? {
    val tiles = try {
      JSONObject(text).optJSONArray("tiles") ?: return null
    } catch (_: JSONException) {
      return null
    }
    if (tiles.length() < 1) return null
    val url = tiles.optString(0, "")
    val version = TILES_URL.matchEntire(url)?.groupValues?.get(1) ?: return null
    return version.takeIf { MapHost.VERSION.matches(it) }
  }
}

/**
 * La versione corrente del server, in memoria e su [file] (`noBackupFilesDir/map/tilejson-version`, scritto con `StateFiles`; null nei test che
 * non vogliono file). Quando chiedere di nuovo il TileJSON:
 *  - mai chiesto, o ultima lettura di un altro avvio del telefono o di 24 ore fa: si', ma non piu' di una volta ogni [RETRY_MS] se la lettura
 *    non riesce (nessuna raffica, E-MAP3);
 *  - una tessera ha risposto 404 (la versione potrebbe essere stata tolta dal server): si', se l'ultima lettura ha piu' di un'ora.
 * Una versione vecchia resta buona finche' non arriva quella nuova (il server tiene le ultime versioni e risponde con l'ultima a una versione
 * che non ha piu').
 */
internal class TileJsonCache(private val file: File?, private val clock: MapNetClock) {

  private class Entry(val version: String, val at: MonoStamp)

  private val lock = Any()
  private var loaded = false
  private var entry: Entry? = null
  private var lastAttempt: MonoStamp? = null
  private var suspect = false

  fun version(): String? = synchronized(lock) { load(); entry?.version }

  fun wantsRefresh(): Boolean = synchronized(lock) {
    load()
    val now = clock.now()
    val current = entry
    val due = current == null || age(current.at, now) >= DAY_MS || (suspect && age(current.at, now) >= HOUR_MS)
    due && lastAttempt.let { it == null || age(it, now) >= RETRY_MS }
  }

  /** Si sta per chiedere il TileJSON: la prossima richiesta, se questa non riesce, aspetta [RETRY_MS]. */
  fun attempted() = synchronized(lock) { lastAttempt = clock.now() }

  fun store(version: String) {
    require(MapHost.VERSION.matches(version)) { "Versione non valida." }
    val now = clock.now()
    synchronized(lock) {
      entry = Entry(version, now)
      suspect = false
      loaded = true
    }
    val target = file ?: return
    try {
      StateFiles.writeText(target, "$FORMAT\n$version\n${now.boot}\n${now.ms}\n", label = LABEL)
    } catch (_: IOException) {
      GalleryLog.w(TAG) { "versione della mappa non salvata (resta in memoria)" }
    }
  }

  /** Una tessera ha risposto 404: alla prossima occasione (se l'ultima lettura ha piu' di un'ora) si rilegge la versione. */
  fun markSuspect() = synchronized(lock) { suspect = true }

  private fun load() {
    if (loaded) return
    loaded = true
    val target = file ?: return
    val lines = try {
      StateFiles.readText(target, label = LABEL, maxBytes = MAX_FILE_BYTES)?.lines()
    } catch (_: IOException) {
      null
    } ?: return
    if (lines.size < 4 || lines[0] != FORMAT) return
    val version = lines[1].takeIf { MapHost.VERSION.matches(it) } ?: return
    val boot = lines[2].toLongOrNull() ?: return
    val ms = lines[3].toLongOrNull() ?: return
    entry = Entry(version, MonoStamp(boot, ms))
  }

  private companion object {
    const val TAG = "MapTileJson"
    const val LABEL = "tilejson-version"
    const val FORMAT = "mcg-tilejson-1"
    const val MAX_FILE_BYTES = 1024L
    const val HOUR_MS = 60L * 60 * 1000
    const val DAY_MS = 24 * HOUR_MS
    const val RETRY_MS = 10L * 60 * 1000

    /** L'eta' di [then] a [now]; un altro avvio del telefono, o un orologio che torna indietro, vale "vecchissimo". */
    fun age(then: MonoStamp, now: MonoStamp): Long = if (then.boot != now.boot || now.ms < then.ms) Long.MAX_VALUE else now.ms - then.ms
  }
}

/**
 * L'attesa crescente dopo un errore del server o della rete (E-MAP3): 2 s, 4 s, 8 s... fino a 5 minuti; un successo la azzera. Durante l'attesa
 * nessuna richiesta esce (l'intercettore le trattiene come "rete assente", e MapLibre riprova con la sua attesa). Orologio monotono.
 */
internal class ServerBackoff(private val clock: MapNetClock) {
  private val lock = Any()
  private var failures = 0
  private var until: MonoStamp? = null

  /** Vero se adesso si deve aspettare. */
  fun holding(): Boolean = synchronized(lock) {
    val end = until ?: return false
    val now = clock.now()
    end.boot == now.boot && now.ms < end.ms
  }

  fun failure() = synchronized(lock) {
    failures = (failures + 1).coerceAtMost(MAX_STEPS)
    val now = clock.now()
    val wait = (FIRST_MS shl (failures - 1)).coerceAtMost(MAX_MS)
    until = MonoStamp(now.boot, now.ms + wait)
  }

  fun success() = synchronized(lock) {
    failures = 0
    until = null
  }

  private companion object {
    const val FIRST_MS = 2_000L
    const val MAX_MS = 5L * 60 * 1000
    const val MAX_STEPS = 16
  }
}
