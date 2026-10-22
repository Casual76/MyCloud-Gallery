package dev.pampa.mycloudgallery.feature.search.contract

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import kotlinx.coroutines.flow.StateFlow

// Le PORTE di Cerca e della pagina Ricerca e modelli (Fase 6, P8; docs/fase6-progetto.md §1.1, §1.5, D15, D41, D48). feature-search non
// vede core-personal ne' core-family (Phase6ArchitectureTest): le scelte personali e il ruolo del dispositivo le legge e le scrive `:app` (P11)
// dietro queste interfacce, sopra `SearchPersonalSettings` e `RolesSettings`. Tutte **opzionali per Dagger** ([SearchPortsModule]): senza
// legatura la schermata funziona da sola, con il suo comportamento di riposo (ricerca vuota, ordine "Recenti", lettura spenta).

/** L'ordine dei risultati scelto (D48, Q10): ricordato e sincronizzato fra i dispositivi dell'utente. `:app` lo lega a `search_order`. */
interface SearchOrderStore {
  val order: StateFlow<ResultOrder>

  suspend fun setOrder(order: ResultOrder)
}

/**
 * La versione del testo della pagina di attivazione che l'account ha accettato (0 = mai, D41, Q4). `:app` la lega a `text_reading`
 * (`TextReadingChoice.OnForAccount.acceptedTextVersion`). Serve alla pagina per sapere se il testo e' cambiato e va riaccettato.
 */
interface TextReadingAccepted {
  val acceptedVersion: StateFlow<Int>
}

/** Com'e' andato il cambio del ruolo Indicizzatore di questo dispositivo (D15, Q3). */
enum class IndexerChange { SAVED, UNCHANGED, NOT_CONFIGURED }

/** Il lavoro che resta da fare e le aree senza chi le analizza (E-I12): numeri e tipi di area, mai nomi di share. */
data class IndexerBacklog(val toAnalyze: Int, val areasWithoutIndexer: List<AreaKind>) {
  companion object {
    val NONE = IndexerBacklog(0, emptyList())
  }
}

/** Lo spazio che l'indice di ricerca occupa su questo dispositivo (D20): i vettori e il testo letto. */
data class IndexSpace(val vectorBytes: Long, val textBytes: Long) {
  companion object {
    val NONE = IndexSpace(0, 0)
  }
}

/**
 * Il ruolo Indicizzatore di QUESTO dispositivo e lo stato del calcolo (D15, D22). `:app` lo lega a `RolesSettings`, `AiComputeEngine.status`,
 * `AiBacklog`, `IndexerAreas` e all'indice locale. [setIndexer] salva e ripubblica lo stato firmato; spegnere rilascia le prenotazioni (lo fa chi
 * osserva il ruolo).
 */
interface IndexerControl {
  val indexer: StateFlow<Boolean>

  val status: StateFlow<AiStatus>

  val backlog: StateFlow<IndexerBacklog>

  val space: StateFlow<IndexSpace>

  suspend fun setIndexer(on: Boolean): IndexerChange

  /** "Indicizza ora": una richiesta dell'utente, con le regole del calcolo (D22). */
  fun indexNow()
}

/**
 * Da dove Cerca prende le miniature: **solo cio' che e' gia' sul dispositivo** (D28, D33). Il telefono da' la sua miniatura; un media che sta solo sul NAS
 * (sul tablet, quasi tutti) ha la sua **nella replica locale** (preparata da chi l'ha caricato) e, se manca, quella provvisoria letta al rilevamento: mai una
 * lettura del NAS. La regola e' di chi chiede: i riferimenti del NAS vanno al caricatore sempre e solo come richieste "in arrivo" ([windowOf]), mai come
 * "visibili", e la sorgente del NAS legge l'originale solo per il contenuto visibile (D17): in Cerca non lo e' mai (CE-5). Senza miniatura, il segnaposto
 * sfocato (ThumbHash). Il [loader] e' quello della Libreria (o un caricatore finto nei test).
 */
class SearchThumbnails(
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val refs: SearchRefs,
)

/** La sorgente di una miniatura per la cella [index] dell'istantanea: dal catalogo locale (`ColumnarTimelineSnapshot.thumbRefAt`, come la Libreria). */
fun interface SearchRefs {
  fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef?
}
