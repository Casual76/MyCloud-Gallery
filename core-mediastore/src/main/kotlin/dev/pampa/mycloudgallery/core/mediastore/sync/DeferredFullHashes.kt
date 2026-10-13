package dev.pampa.mycloudgallery.core.mediastore.sync

import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gli **hash completi rimandati** (docs/fase3-progetto.md §2.1, riga "Impronta"; §1.4, `LibraryMaintenanceWorker`).
 *
 * Durante la lettura del telefono l'hash completo si calcola solo sotto [PhoneSyncConfig.fullHashMaxBytes]: un file piu'
 * grande con la stessa impronta rapida di un altro media nasce **separato** (`KEEP_SEPARATE`, nel dubbio non si unisce).
 * Questa fase, che gira solo nella manutenzione quotidiana in carica, rilegge quei file per intero (a pezzi, memoria
 * costante), fa imparare l'hash completo al catalogo e, se i byte sono **gli stessi** di un altro media, chiede l'unione
 * (`MediaCatalog.merge`, che applica `MergeRules`: e' il catalogo a decidere, qui non c'e' logica d'identita').
 *
 * - Solo con l'accesso completo ai media, e le foto solo con `ACCESS_MEDIA_LOCATION` (D9: mai un'impronta oscurata).
 * - Solo copie presenti, con un `_ID` della versione attuale del MediaStore (E-P22).
 * - Con un budget di tempo e un tetto di file per giro: riprende al giro dopo dal punto in cui e' rimasta, perche' la
 *   query restituisce solo cio' che e' ancora senza hash completo.
 *
 * Aggiunta di P11 (integrazione): nessuna firma esistente cambia.
 */
class DeferredFullHashes(
  private val source: MediaStoreSource,
  private val access: PhoneAccessReader,
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
  private val config: PhoneSyncConfig = PhoneSyncConfig(),
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) {

  /** Il bilancio di un giro: solo numeri. [completed] e' falso se il budget o il tetto hanno fermato il giro. */
  data class Report(val hashed: Int = 0, val merged: Int = 0, val skipped: Int = 0, val completed: Boolean = true)

  private val lock = Mutex()

  suspend fun run(budget: Duration, maxFiles: Int = MAX_FILES_PER_RUN): Report = lock.withLock {
    withContext(dispatchers.io) { execute(monotonicMs() + budget.inWholeMilliseconds, maxFiles) }
  }

  private suspend fun execute(deadline: Long, maxFiles: Int): Report {
    access.refresh()
    val now = access.state.value
    // Con l'accesso parziale non si legge niente fuori dalla selezione: la manutenzione aspetta l'accesso completo.
    if (now.media != MediaAccess.FULL) return Report(completed = false)
    val rows = database.locationDao().deferredFullHashes(config.fullHashMaxBytes, maxFiles + 1)
    var hashed = 0
    var merged = 0
    var skipped = 0
    var stopped = rows.size > maxFiles
    for (entity in rows.take(maxFiles)) {
      if (monotonicMs() >= deadline) {
        stopped = true
        break
      }
      try {
        when (val outcome = hashOne(entity.locationId, entity.versionId, now.mediaLocation)) {
          null -> skipped++
          else -> {
            hashed++
            merged += outcome
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: SecurityException) {
        // Permessi ritirati a meta' giro: ci si ferma, si riprova al prossimo.
        return Report(hashed, merged, skipped, completed = false)
      } catch (e: Exception) {
        skipped++
        GalleryLog.w(TAG) { "hash completo rimandato non calcolato: ${e.javaClass.simpleName}" }
      }
    }
    return Report(hashed, merged, skipped, completed = !stopped)
  }

  /** Calcola l'hash completo di una copia e lo fa imparare al catalogo. Null se saltata; altrimenti quante unioni. */
  private suspend fun hashOne(locationId: Long, versionId: String, mediaLocation: Boolean): Int? {
    val version = database.versionDao().get(versionId) ?: return null
    val media = catalog.media(MediaId(version.mediaId)) ?: return null
    val placed = media.locations.firstOrNull { it.id.value == locationId && it.isPresent } ?: return null
    val phone = placed.location as? Location.Phone ?: return null
    val mediaStoreId = phone.mediaStoreId ?: return null
    if (phone.mediaStoreVersion != source.version(phone.volume)) return null
    val expected = media.versions.firstOrNull { it.id == placed.versionId }?.fingerprint ?: return null
    if (expected.fullHash != null) return null
    val type = if (media.kind.isVideo) RowType.VIDEO else RowType.IMAGE
    // D9: una foto senza il permesso della posizione darebbe byte oscurati, quindi un hash sbagliato.
    if (type == RowType.IMAGE && !mediaLocation) return null
    val row = MediaStoreRow(
      volume = phone.volume,
      id = mediaStoreId,
      type = type,
      displayName = phone.displayName,
      relativePath = phone.relativePath,
      mime = null,
      size = placed.stamp.size,
      dateModifiedMs = placed.stamp.modifiedAtMillis,
      generationModified = placed.stamp.generation,
    )
    val full = PhoneFingerprints.full(source, row, expected) ?: return null
    val sha = full.fullHash ?: return null
    // Stesso posto, stessi byte: il catalogo impara l'hash completo della versione (SameFile).
    catalog.recordAll(listOf(Observation(phone, placed.stamp, full, media.kind, media.source)), MediaCatalog.WhenUndecided.WAIT)
    var merged = 0
    val twins = database.versionDao().byFullHash(sha.hex).map { it.mediaId }.filter { it != media.id.value }.distinct()
    for (other in twins) {
      val survivor = catalog.resolve(media.id) ?: break
      val otherId = catalog.resolve(MediaId(other)) ?: continue
      if (otherId == survivor) continue
      if (catalog.merge(survivor, otherId) is MediaCatalog.MergeOutcome.Merged) merged++
    }
    return merged
  }

  private companion object {
    const val TAG = "DeferredFullHashes"

    /** Al massimo tanti file per giro: un video da 2 GB si legge in decine di secondi anche su un telefono veloce. */
    const val MAX_FILES_PER_RUN = 50
  }
}
