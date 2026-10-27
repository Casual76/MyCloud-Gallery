package dev.pampa.mycloudgallery.core.mediastore.sync

import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.PlaceToRecompute
import dev.pampa.mycloudgallery.core.metadata.extract.PlaceChoice
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il **ricalcolo dei luoghi** quando l'app porta un dataset nuovo (docs/fase3-progetto.md §2.3 e §1.4, `LibraryMaintenanceWorker`; docs/luoghi.md §"Uso").
 * Debito del 27/10: `MediaCatalog.setPlacesDataset` non aveva chiamanti e i `place_id` restavano quelli letti con il dataset di prima.
 *
 * - **Una volta sola.** Il segnale e' `catalog_meta.places_dataset` contro `ReverseGeocoder.dataset`: uguali = [Outcome.CURRENT], una lettura e niente
 *   altro. Diversi = si ricalcolano le versioni con GPS il cui luogo non porta il dataset in uso; alla fine (e solo alla fine) si scrive `places_dataset`.
 * - **A tappe, con la ripresa.** Un lotto per volta ([BATCH] versioni, una transazione ciascuno) finche' c'e' tempo; ogni versione riscritta porta il dataset
 *   nuovo e non torna in coda, quindi dopo la morte del processo si riparte da quelle che mancano, senza cursori e senza rifare niente.
 * - **La stessa regola della lettura dei file** ([PlaceChoice]): solo `CITY` o `NEAR`. Un punto che nel dataset nuovo non ha piu' un luogo vicino perde il luogo.
 * - **Senza dataset aperto** (`dataset` <= 0: l'apertura non e' finita, l'asset manca o e' alterato) non si fa niente: un'altra notte.
 * - Nessuna rete, nessun file: solo il catalogo e il dataset gia' aperto. Il lavoro di calcolo gira su `default`, le scritture passano dalla coda del catalogo.
 */
class PlacesRecompute(
  private val catalog: MediaCatalog,
  private val geocoder: ReverseGeocoder,
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val batch: Int = BATCH,
) {

  enum class Outcome {
    /** Il dataset dell'app non e' aperto (o l'app non ne ha uno): niente da confrontare. */
    NOT_READY,

    /** `places_dataset` e' gia' quello dell'app: non si fa niente. */
    CURRENT,

    /** Il budget e' finito prima della fine: la prossima manutenzione riprende dalle versioni che mancano. */
    PARTIAL,

    /** Tutto ricalcolato e `places_dataset` scritto. */
    DONE,
  }

  /** Il bilancio di un giro: solo numeri. [recomputed] versioni riscritte, [changed] media a cui il luogo e' cambiato. */
  class Report(val outcome: Outcome, val recomputed: Int = 0, val changed: Int = 0) {
    override fun toString(): String = "Report($outcome, recomputed=$recomputed, changed=$changed)"
  }

  private val lock = Mutex()

  /** Un giro entro [budget] (almeno un lotto): confronta i dataset e, se differiscono, ricalcola a tappe. Un giro alla volta. */
  suspend fun run(budget: Duration): Report = lock.withLock { execute(budget) }

  private suspend fun execute(budget: Duration): Report {
    val dataset = geocoder.dataset
    if (dataset <= 0) return Report(Outcome.NOT_READY)
    if (catalog.placesDataset() == dataset) return Report(Outcome.CURRENT)
    val deadline = monotonicMs() + budget.inWholeMilliseconds
    var recomputed = 0
    var changed = 0
    while (true) {
      val rows = catalog.placesToRecompute(dataset, batch)
      if (rows.isEmpty()) {
        catalog.setPlacesDataset(dataset)
        GalleryLog.d(TAG) { "luoghi ricalcolati: dataset $dataset, versioni $recomputed, luoghi cambiati $changed" }
        return Report(Outcome.DONE, recomputed, changed)
      }
      val resolved = withContext(dispatchers.default) { rows.associate { it.version to placeOf(it) } }
      val saved = catalog.savePlaces(dataset, resolved)
      recomputed += saved.stamped
      changed += saved.changed
      // Un lotto che non ha riscritto niente (le versioni sono sparite fra la lettura e la scrittura) non si ripete a vuoto: un'altra notte.
      if (saved.stamped == 0 || monotonicMs() >= deadline) return Report(Outcome.PARTIAL, recomputed, changed)
    }
  }

  /** Il luogo di una riga; un punto non valido o un errore del dataset valgono "nessun luogo" (la versione porta comunque il dataset, e non torna in coda). */
  private fun placeOf(row: PlaceToRecompute): PlaceRef? = try {
    PlaceChoice.of(geocoder, GeoPoint(row.latitude, row.longitude))
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  companion object {
    const val TAG = "PlacesRecompute"

    /** Versioni per transazione: abbastanza da non fare 200 transazioni su 100 mila, poche perche' la coda del catalogo non resti occupata. */
    const val BATCH: Int = 500
  }
}
