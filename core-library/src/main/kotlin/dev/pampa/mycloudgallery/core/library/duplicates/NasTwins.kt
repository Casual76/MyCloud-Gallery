package dev.pampa.mycloudgallery.core.library.duplicates

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.WorkStage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// DP-1 (revisione di Fase 7, D25, D27): I DOPPIONI IDENTICI FRA CARTELLE DEL NAS.
//
// La scansione del NAS osserva solo dimensione e hash rapido: due file uguali in due cartelle diventano **due media** (nel dubbio non si unisce, mai senza
// hash completo). La query dei Doppioni cerca una versione con due posizioni dalla stessa parte, quindi i gemelli del NAS non comparivano mai, a meno che
// il telefono facesse da ponte. Qui si chiude il cerchio senza cambiare le regole d'identita':
//
//  1. **dove serve**, mai su tutta la Libreria: i candidati sono i media con una copia presente sul NAS che hanno la stessa dimensione e lo stesso hash
//     rapido di un altro media pure sul NAS. Per loro, se manca l'hash completo, si mette in coda la fase `FULL_HASH` (il catalogo la consuma con il
//     lavoro di sicurezza del backup, con le sue regole: Wi-Fi di casa, attivita' pesante per i video);
//  2. **dopo**, quando gli hash completi arrivano e sono uguali, i due media si uniscono con `MediaCatalog.merge` come fa `DeferredFullHashes` per il
//     telefono: decide `MergeRules` (originali con hash completi uguali), qui non c'e' logica d'identita'. Il media unito ha le due posizioni sul NAS:
//     e' un doppione identico, e la query lo trova.
//
// Mai un'unione senza hash completo. I media di un gruppo (raffica, copie simili, "da verificare") non si toccano: nel dubbio non si unisce (E-L12), e il
// gruppo provvisorio lo risolve il catalogo da se'. Solo numeri nei resoconti: niente id ne' percorsi.

/** Il resoconto di un giro: soli numeri. */
class TwinReport(val queued: Int, val merged: Int, val waiting: Int, val finished: Boolean) {
  override fun toString(): String = "TwinReport(queued=$queued, merged=$merged, waiting=$waiting, finished=$finished)"

  companion object {
    val NONE: TwinReport = TwinReport(0, 0, 0, finished = true)
  }
}

/**
 * Trova i file uguali fra cartelle del NAS dello stesso account e li porta a essere un media con due copie.
 *
 * @param account l'account di questo dispositivo: solo le posizioni NAS sue (area privata e comune) contano.
 * @param maxSets al piu' quanti insiemi di candidati si guardano per giro (i piu' piccoli prima: le foto, poi i video).
 */
class NasTwins(
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
  private val account: AccountId,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val maxSets: Int = MAX_SETS,
) {

  init {
    require(maxSets > 0) { "Almeno un insieme per giro." }
  }

  /** Quanti insiemi di file che sembrano uguali aspettano ancora un hash completo (per dire alla pagina "sto controllando"). */
  suspend fun waiting(): Int = withContext(Dispatchers.IO) {
    database.openHelper.readableDatabase.query(SimpleSQLiteQuery(WAITING_SQL, arrayOf<Any?>(account.value))).use { c ->
      if (c.moveToFirst()) c.getInt(0) else 0
    }
  }

  /**
   * Un giro, al piu' per [budget]: prima le unioni di cio' che e' gia' provato uguale, poi la coda dell'hash completo per chi sembra uguale. Idempotente:
   * un insieme gia' in coda o gia' unito non fa niente la seconda volta. Un errore ferma il giro (`finished = false`) e non lancia.
   */
  suspend fun settle(budget: Duration): TwinReport {
    val started = timeSource.markNow()
    var merged = 0
    var queued = 0
    try {
      for (hash in provenSets()) {
        if (merged + queued > 0 && started.elapsedNow() >= budget) return TwinReport(queued, merged, waiting(), finished = false)
        merged += mergeProven(hash)
      }
      for (set in candidateSets()) {
        if (merged + queued > 0 && started.elapsedNow() >= budget) return TwinReport(queued, merged, waiting(), finished = false)
        queued += queueCandidates(set)
      }
      return TwinReport(queued, merged, waiting(), finished = true)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return TwinReport(queued, merged, 0, finished = false)
    }
  }

  // --- unione di cio' che e' provato uguale ----------------------------------------------------------------------------------

  /** Gli hash completi che hanno almeno due media con una copia presente sul NAS. */
  private suspend fun provenSets(): List<String> = withContext(Dispatchers.IO) {
    val out = ArrayList<String>()
    database.openHelper.readableDatabase.query(SimpleSQLiteQuery(PROVEN_SQL, arrayOf<Any?>(account.value, maxSets))).use { c ->
      while (c.moveToNext()) out += c.getString(0)
    }
    out
  }

  /** Unisce i media che hanno quell'hash completo. Quanti sono stati uniti. */
  private suspend fun mergeProven(fullHash: String): Int {
    val media = mediaWithHash(fullHash).filter { standsAlone(it) }
    if (media.size < 2) return 0
    var survivor = media.first()
    var merged = 0
    for (other in media.drop(1)) {
      val keeper = catalog.resolve(survivor) ?: break
      val loser = catalog.resolve(other) ?: continue
      if (keeper == loser) continue
      val outcome = catalog.merge(keeper, loser)
      if (outcome is MediaCatalog.MergeOutcome.Merged) {
        merged++
        survivor = outcome.survivor
      }
    }
    return merged
  }

  private suspend fun mediaWithHash(fullHash: String): List<MediaId> = withContext(Dispatchers.IO) {
    database.versionDao().byFullHash(fullHash).map { it.mediaId }.distinct().sorted().map(::MediaId)
  }

  // --- coda dell'hash completo per chi sembra uguale -------------------------------------------------------------------------

  /** Le coppie (dimensione, hash rapido) con piu' media sul NAS e almeno una versione senza hash completo, le piu' piccole prima. */
  private suspend fun candidateSets(): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
    val out = ArrayList<Pair<Long, String>>()
    database.openHelper.readableDatabase.query(SimpleSQLiteQuery(CANDIDATES_SQL, arrayOf<Any?>(account.value, maxSets))).use { c ->
      while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
    }
    out
  }

  /** Mette in coda `FULL_HASH` per i media dell'insieme che non hanno l'hash completo. Quanti ne ha messi. */
  private suspend fun queueCandidates(set: Pair<Long, String>): Int {
    val versions = withContext(Dispatchers.IO) { database.versionDao().byQuickFingerprint(set.first, set.second) }
    val media = versions.map { it.mediaId }.distinct().map(::MediaId).filter { standsAlone(it) && onNas(it) }
    if (media.size < 2) return 0
    val unknown = media.filter { id -> catalog.media(id)?.currentVersion?.fingerprint?.fullHash == null }
    return if (unknown.isEmpty()) 0 else catalog.requestWork(unknown, WorkStage.FULL_HASH)
  }

  // --- pezzi -----------------------------------------------------------------------------------------------------------------

  /** Il media non sta in nessun gruppo (raffica, copie simili, "da verificare"): nel dubbio non si unisce (E-L12). */
  private suspend fun standsAlone(id: MediaId): Boolean = catalog.media(id)?.groupId == null

  /** Il media ha una copia presente sul NAS di questo account. */
  private suspend fun onNas(id: MediaId): Boolean =
    catalog.media(id)?.locations?.any { it.isPresent && it.isNas } == true

  private companion object {
    const val MAX_SETS = 200

    /** I media con una copia presente sul NAS dell'account: la base di tutte le query. */
    const val FROM_NAS =
      "FROM media_version v JOIN media_location l ON l.version_id = v.version_id " +
        "WHERE l.location_type = 'nas' AND l.state = 'present' AND l.account_id = ?"

    const val PROVEN_SQL =
      "SELECT v.full_hash $FROM_NAS AND v.full_hash IS NOT NULL GROUP BY v.full_hash HAVING COUNT(DISTINCT v.media_id) > 1 LIMIT ?"

    const val CANDIDATES_SQL =
      "SELECT v.size, v.quick_hash $FROM_NAS GROUP BY v.size, v.quick_hash " +
        "HAVING COUNT(DISTINCT v.media_id) > 1 AND SUM(v.full_hash IS NULL) > 0 ORDER BY v.size LIMIT ?"

    const val WAITING_SQL =
      "SELECT COUNT(*) FROM (SELECT 1 $FROM_NAS GROUP BY v.size, v.quick_hash " +
        "HAVING COUNT(DISTINCT v.media_id) > 1 AND SUM(v.full_hash IS NULL) > 0)"
  }
}
