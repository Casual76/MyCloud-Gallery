package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Il nucleo puro del raggruppamento e il lavoro con il custode (docs/fase8-progetto.md §2.5, D20-D22). Vedi Grouping.kt.

/** Chi da' gli id delle persone nuove (D22: casuali a 128 bit). Nei test e nel riferimento un generatore deterministico. */
fun interface PersonIdSource {
  fun next(): PersonId

  companion object {
    val RANDOM: PersonIdSource = PersonIdSource { PersonId.random() }
  }
}

/**
 * L'ingresso di un passo (§2.5): lo stato di partenza, **tutti** i volti correnti dello spazio (CORE e WEAK, gia' tolti quelli dei media
 * esclusi: screenshot, documenti, ritagli), le correzioni, i parametri e i contenuti spariti o diventati documenti ([gone]: i loro volti
 * escono, E-F16, E-F39). I nascosti **ci sono** (si calcolano e si raggruppano; non si mostrano, D28).
 */
data class GroupingInput(
  val state: GroupingState,
  val faces: List<GroupFace>,
  val corrections: GroupingCorrections = GroupingCorrections.NONE,
  val params: GroupingParams = GroupingParams.DEFAULT,
  val gone: Set<ContentKey> = emptySet(),
) {
  override fun toString(): String = "GroupingInput(${faces.size} volti, $state)"
}

/** Una proposta "E' la stessa persona?" (D20 (a)): due persone con il legame medio fra gli esemplari [link] fra `tSuggest` e `tMerge`. */
data class MergeSuggestion(val pair: PersonPair, val link: Float) {
  override fun toString(): String = "MergeSuggestion"
}

/** I numeri di un passo (solo numeri, D37). */
data class GroupingReport(
  val assigned: Int,
  val unassigned: Int,
  val born: Int,
  val merged: Int,
  val ejected: Int,
  val removed: Int,
  val persons: Int,
) {
  companion object {
    val EMPTY = GroupingReport(0, 0, 0, 0, 0, 0, 0)
  }
}

/**
 * R6, L343: il raggruppamento non si fa perche' i volti con vettore della generazione superano il tetto duro (`FacesArchiveLimits.MAX_GROUP_FACES`). E' una
 * `IllegalStateException` come un archivio chiuso (chi non la conosce salta il giro), ma il custode la riconosce e la conta (`FACES_GROUPING_SKIPPED`):
 * un account cosi' grande non si raggruppa, e ora la Diagnostica lo dice. Niente numeri di volti nel messaggio.
 */
class GroupingOverCapException : IllegalStateException("Troppi volti per il raggruppamento.")

/** L'uscita di un passo: lo stato nuovo (`seq` + 1 se e' cambiato qualcosa), le proposte e i numeri. */
data class GroupingOutcome(val state: GroupingState, val suggestions: List<MergeSuggestion>, val report: GroupingReport)

/**
 * Il nucleo del raggruppamento (D20-D23, §2.5), **puro e deterministico**: niente orologio, niente casualita' fuori da [PersonIdSource], ogni
 * spareggio per riferimento o per id.
 * - [incremental]: le correzioni, l'assegnazione dei volti nuovi (esemplari con soglia e margine, vincolo "stessa foto"), le nascite se i
 *   non assegnati CORE sono almeno `birthsEvery` in piu' dell'ultima volta. A fine unita', solo il custode (D21).
 * - [provisional]: l'assegnazione **provvisoria e solo locale** dei volti che l'istantanea non contiene, con gli esemplari dell'istantanea:
 *   mai nascite, mai unioni; il risultato non si pubblica (D21, gli altri dispositivi).
 * - [nightly]: la manutenzione notturna del custode: volti spariti, correzioni, espulsioni sotto `tEject`, esemplari e centroidi rinnovati,
 *   unioni automatiche (alias) e proposte, riassegnazione, nascite.
 * - [recluster]: un ricalcolo completo (modello, spazio o ricetta nuovi, E-F24): raggruppa da capo e **abbina** i gruppi nuovi alle persone
 *   di [previous] per volti in comune (stesso posto, IoU >= 0,5; assegnazione ottima; almeno il 30%), cosi' id, nomi e correzioni si
 *   ereditano; una persona vecchia senza gruppo resta (con il suo nome: "0 foto · ricontrolla").
 */
interface GroupingEngine {
  fun incremental(input: GroupingInput, ids: PersonIdSource): GroupingOutcome

  fun provisional(input: GroupingInput): GroupingOutcome

  fun nightly(input: GroupingInput, ids: PersonIdSource): GroupingOutcome

  fun recluster(input: GroupingInput, previous: GroupingState, ids: PersonIdSource): GroupingOutcome
}

/**
 * Un'istantanea del raggruppamento letta dal NAS (classe `cluster`, §2.4, D21): lo stato (con `seq` e `base`) e il dispositivo
 * dell'account che l'ha pubblicata ([author]). Aggiunta di P5 (Fase 8 P5 in `docs/decisioni.md`): l'istantanea letta dal NAS sta
 * **separata** da quella locale dell'archivio, perche' il custode che perde si riallinea partendo da quella vincente senza perdere la sua.
 */
class ClusterSnapshot(val author: String, val state: GroupingState) {
  override fun toString(): String = "ClusterSnapshot(seq=${state.seq})"

  companion object {
    /** Vero se [a] batte [b] (§2.4): `seq` piu' alta, a pari numero il dispositivo minore. */
    fun beats(a: ClusterSnapshot, b: ClusterSnapshot): Boolean =
      a.state.seq > b.state.seq || (a.state.seq == b.state.seq && a.author < b.author)

    /** L'istantanea vincente fra [snapshots], o null se non ce ne sono. */
    fun winner(snapshots: Collection<ClusterSnapshot>): ClusterSnapshot? =
      snapshots.fold(null as ClusterSnapshot?) { best, s -> if (best == null || beats(s, best)) s else best }
  }
}

/**
 * Le istantanee del raggruppamento che la replica (P3, sync/) ha letto e accettato per la generazione [generation]: per ogni dispositivo
 * dell'account l'ultima che ha pubblicato (anche questo dispositivo). Vuoto = nessuna, NAS mai letto o recinzione (D34). Le regole di chi
 * legge (firmatario dell'account, generazione corrente, anti-rollback, tetti) sono di P3; qui arrivano solo istantanee valide.
 */
fun interface ClusterSnapshots {
  suspend fun latest(generation: FaceGeneration): List<ClusterSnapshot>
}

/**
 * I contenuti i cui volti escono dal raggruppamento (E-F16, E-F39, §2.5 (e)): fra [keys], quelli che non hanno piu' un media nel catalogo
 * di questo dispositivo o il cui media e' diventato un documento (bit `DOCUMENT`). Legata da :app (P13) sopra il catalogo. I nascosti
 * **non** escono (si raggruppano e non si mostrano, D28). Aggiunta di P5.
 */
fun interface GoneContents {
  suspend fun gone(keys: Collection<ContentKey>): Set<ContentKey>
}

/** Chi e' il custode del raggruppamento (D21): la prenotazione `faces/<G>/cluster` (1 ora, rinnovo ogni 20 minuti). */
sealed interface HolderState {
  /** Non si sa ancora (nessun giro). */
  data object Unknown : HolderState

  /** Un altro dispositivo dell'account e' custode: qui solo assegnazioni provvisorie e locali. */
  data object NotHolder : HolderState

  /** Questo dispositivo e' custode; l'ultima istantanea pubblicata ha [seq]. */
  data class Holder(val seq: Long) : HolderState

  /** La prenotazione e' contesa (E-F26): si pubblica, vince la `seq` piu' alta, il perdente si riallinea. */
  data object Contended : HolderState

  /** Recinzione (D34): ogni indicizzatore e' custode per se', in locale. */
  data object LocalOnly : HolderState
}

/**
 * Il lavoro del raggruppamento (P5) sopra l'archivio dei volti e il nucleo [GroupingEngine]: prende o rinnova la prenotazione del custode,
 * carica l'istantanea vincente piu' le voci `people`, esegue il passo entro [Duration], salva e mette in coda la pubblicazione (al piu'
 * ogni 5 minuti, a fine unita' e a fine manutenzione). Idempotente e riprendibile; mai un'eccezione per un caso previsto.
 */
interface FaceGrouping {
  val holder: StateFlow<HolderState>

  suspend fun incremental(budget: Duration): GroupingReport

  suspend fun nightly(budget: Duration): GroupingReport

  suspend fun recluster(budget: Duration): GroupingReport
}
