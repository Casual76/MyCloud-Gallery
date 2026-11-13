package dev.pampa.mycloudgallery.feature.search.contract

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.SelectionModel
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import kotlinx.coroutines.flow.StateFlow

// Le PORTE di Cerca e della pagina Ricerca e modelli (Fase 6, P8; docs/fase6-progetto.md Â§1.1, Â§1.5, D15, D41, D48). feature-search non
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

  /**
   * Pacchetto X: lo stato dell'analisi **ridotto** per la testata e la pagina "Analisi delle foto" (un solo stato per tutti i posti, con
   * il motivo vero di una pausa). Di serie niente da dire, per i montaggi che non lo legano.
   */
  val analysis: StateFlow<LibraryAnalysisStatus> get() = ANALYSIS_IDLE

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

/** Lo stato ridotto quando nessuno lo lega: niente da dire. */
private val ANALYSIS_IDLE: StateFlow<LibraryAnalysisStatus> = kotlinx.coroutines.flow.MutableStateFlow(LibraryAnalysisStatus.Idle)

/**
 * Un media nel nastro della pagina "Analisi delle foto" (Pacchetto X), **gia' passato dal cancello** in `:app`: o si puo' mostrare
 * ([Shown], con la richiesta di miniatura locale e il segnaposto sfocato, come in Cerca) o non si mostra affatto ([Quiet]: un media
 * che la Libreria in questo momento non mostrerebbe â€” nascosto, in un album protetto, nel cestino, revocato mentre la pagina e'
 * aperta â€” si conta nei numeri ma a schermo e' un segnaposto neutro). Nessuna chiave di contenuto arriva qui.
 */
sealed interface AnalysisThumb {
  data class Shown(val id: MediaId, val request: ThumbRequest?, val thumbHash: ThumbHash?, val video: Boolean) : AnalysisThumb {
    /** XS3-5: solo il tipo (e se e' un video): mai l'id del media, la versione, il riferimento al telefono o al NAS, ne' il segnaposto sfocato, in nessun dump o log. */
    override fun toString(): String = "AnalysisThumb.Shown(video=$video)"
  }

  data object Quiet : AnalysisThumb
}

/**
 * Il calcolo mentre scorre, per la pagina: il media di adesso, gli ultimi finiti, i prossimi in coda.
 *
 * [visibilityGeneration] (XS2-1, additivo, di serie 0): il **numero d'ordine di cio' che la Libreria mostra** (permessi, selezione, nascosti, cestino).
 * `:app` lo alza a **ogni** cambio di visibilita' e, nello stesso istante, emette uno stato con **tutte le anteprime `Quiet`** della nuova generazione:
 * chi disegna deve trattare ogni `Shown` nata in una generazione **piu' vecchia** di quella dell'ultimo valore ricevuto come **non piu' autorizzata**
 * (niente byte, niente ThumbHash, niente riferimento: neutra) **subito**, anche se la sta trattenendo per un'animazione. Il pacing riguarda il movimento,
 * mai l'autorizzazione. Il solo avanzare del motore (un media finito) **non** alza la generazione.
 *
 * [finished] (XU-9, additivo, di serie 0): quanti media sono usciti dal lavoro da che il processo e' nato (il contatore del motore, `AiLive.finished`).
 * Cresce di 1 per ogni media finito e **non** si azzera a una generazione nuova: chi disegna lo usa per far avanzare il nastro anche quando due stati
 * hanno le stesse anteprime (un nastro tutto di caselle neutre). E' un conteggio: non dice quali media.
 */
data class AnalysisFlow(
  val current: AnalysisThumb?,
  val recent: List<AnalysisThumb>,
  val next: List<AnalysisThumb>,
  val visibilityGeneration: Long = 0L,
  val finished: Long = 0L,
) {
  /** XS3-5: soli tipi e conteggi (come `AiLive.toString`): quanti posti, quante anteprime mostrabili, la generazione e i media finiti; mai un id, una versione o un riferimento. */
  override fun toString(): String {
    val all = listOfNotNull(current) + recent + next
    return "AnalysisFlow(ora=${if (current != null) "si" else "no"}, fatte=${recent.size}, prossime=${next.size}, " +
      "mostrate=${all.count { it is AnalysisThumb.Shown }}, generazione=$visibilityGeneration, finiti=$finished)"
  }

  companion object {
    val EMPTY = AnalysisFlow(null, emptyList(), emptyList())
  }
}

/**
 * Da dove la pagina "Analisi delle foto" prende il nastro: le chiavi del motore risolte in `:app` nelle celle che la Libreria mostra
 * **adesso** (stesso cancello delle altre pagine). Miniature solo da cio' che e' gia' sul dispositivo (D28, D33): gli stessi
 * [SearchThumbnails] di Cerca. **Opzionale**: senza, la pagina mostra solo lo stato.
 */
interface AnalysisLiveSource {
  val live: StateFlow<AnalysisFlow>
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
) {
  /**
   * XS3-4 (Pacchetto XF, giro 3): le miniature del nastro dell'analisi **con un proprietario** ([ANALYSIS_OWNER]), mai al posto della finestra della Libreria (`window`
   * la sostituirebbe e annullerebbe le celle in volo della griglia). Valgono come richieste "in arrivo" accanto alla finestra e **restano finche' non si rilasciano**:
   * chi disegna chiama questo quando le miniature mostrate cambiano e [releaseAnalysis] quando la pagina esce dalla composizione, cosi' non restano richieste ne'
   * ripetizioni (il caricatore riprova dopo 15 s) oltre il tempo in cui il nastro e' guardato. Non svuota la finestra globale.
   */
  fun holdAnalysis(requests: List<ThumbRequest>) = loader.keep(ANALYSIS_OWNER, requests)

  /** XS3-4: il nastro dell'analisi non e' piu' guardato: toglie le sue richieste (e le loro riprove). Idempotente. */
  fun releaseAnalysis() = loader.keep(ANALYSIS_OWNER, emptyList())

  companion object {
    /** Il proprietario delle richieste del nastro dell'analisi nel caricatore condiviso. */
    const val ANALYSIS_OWNER: String = "analysis-lane"
  }
}

/**
 * I2 (fase I): il volto di una persona per l'anteprima di un suggerimento (Â«per una persona il voltoÂ»). `:app` la lega ai ritagli di feature-people, con
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
