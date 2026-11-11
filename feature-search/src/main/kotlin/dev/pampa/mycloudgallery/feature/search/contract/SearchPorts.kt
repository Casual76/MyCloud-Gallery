package dev.pampa.mycloudgallery.feature.search.contract

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.SelectionModel
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import kotlinx.coroutines.flow.StateFlow

// Le PORTE di Cerca e della pagina Ricerca e modelli (Fase 6, P8; docs/fase6-progetto.md §1.1, §1.5, D15, D41, D48). feature-search non
// vede core-personal ne' core-family (Phase6ArchitectureTest): le scelte personali e il ruolo del dispositivo le legge e le scrive `:app` (P11)
// dietro queste interfacce, sopra `SearchPersonalSettings` e `RolesSettings`. Tutte **opzionali per Dagger** ([SearchPortsModule]): senza
// legatura la ricerca funziona da sola, con il suo comportamento di riposo (ricerca vuota, lettura spenta). L'ordine dei risultati non e' piu' una scelta:
// solo per pertinenza (H4, risposta dell'utente dell'11/11).

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

  /**
   * Rilegge il lavoro che resta, lo spazio e lo stato (IX-3): la pagina lo chiama a ogni apertura e dopo ogni tappa. Prima si leggevano una
   * volta sola, alla nascita del controllo. Di serie non fa niente.
   */
  fun refresh() = Unit

  /** L'ultima misura breve del dispositivo (D15), ricordata fra una visita e l'altra: senza, la stima delle notti restava "in misura" per sempre (IX-3). */
  fun savedProbe(): dev.pampa.mycloudgallery.core.ai.contract.ProbeResult? = null

  fun saveProbe(result: dev.pampa.mycloudgallery.core.ai.contract.ProbeResult) = Unit
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

/**
 * I2 (fase I): il volto di una persona per l'anteprima di un suggerimento («per una persona il volto»). `:app` la lega ai ritagli di feature-people, con
 * **il cancello dei volti** delle Raccolte: il ritaglio arriva solo se il volto sta in una foto mostrabile adesso, e porta quelle foto ([SearchFace.origins]),
 * cosi' la ricerca non lo disegna se nessuna e' piu' nella Libreria visibile (una foto nascosta un attimo fa). **Opzionale**: senza, le persone hanno il
 * segno della persona.
 */
interface SearchFaces {
  /** Il volto di [person] a [sizePx], o null (Persone spenta, nessun volto mostrabile, cancello chiuso). Fuori dal thread principale. */
  suspend fun face(person: dev.pampa.mycloudgallery.core.model.PersonId, sizePx: Int): SearchFace?

  /** Cambia quando i volti gia' dati non valgono piu' (Persone spenta o rimontata, ritagli cancellati): la ricerca li butta tutti. Di serie mai. */
  val revocations: kotlinx.coroutines.flow.Flow<Any?> get() = kotlinx.coroutines.flow.flowOf(Unit)
}

/** Un volto e le foto (visibili quando il cancello l'ha dato) da cui viene. Mai salvato: solo in memoria, come i ritagli delle Raccolte. */
class SearchFace(val bitmap: android.graphics.Bitmap, val origins: Set<MediaId>)

/** La sorgente di una miniatura per la cella [index] dell'istantanea: dal catalogo locale (`ColumnarTimelineSnapshot.thumbRefAt`, come la Libreria). */
fun interface SearchRefs {
  fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef?
}

/**
 * La selezione sui risultati (H4): e' **quella della Libreria** (lo stesso modello), cosi' la barra delle azioni, i fogli di conferma e gli avvisi sono gli
 * stessi della griglia della Libreria e non una seconda copia. `:app` la passa dalla griglia della Libreria.
 *
 * @property model gli elementi scelti.
 * @param selecting vero in modalita' selezione (uno stato di Compose: chi lo legge si ricompone quando cambia).
 * @param begin entra in modalita' selezione.
 */
class SearchSelection(
  val model: SelectionModel,
  private val selecting: () -> Boolean,
  private val begin: () -> Unit,
) {
  /** Vero in modalita' selezione. */
  val active: Boolean get() = selecting()

  /** La pressione lunga su un risultato: si entra in selezione con quel risultato scelto. */
  fun startWith(id: MediaId) {
    if (!selecting()) begin()
    if (id !in model.selected.value.ids) model.toggle(id)
  }

  /** Un tocco in selezione: il risultato entra o esce. */
  fun toggle(id: MediaId) = model.toggle(id)
}
