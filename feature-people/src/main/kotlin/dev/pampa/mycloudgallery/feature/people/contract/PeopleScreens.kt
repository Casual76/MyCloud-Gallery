package dev.pampa.mycloudgallery.feature.people.contract

import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode

// Gli STATI delle schermate delle persone (docs/fase8-progetto.md §1.5, D38). Contratto di P0: la Fase 8 decide COSA si mostra e QUANDO,
// il filone grafico decide COME. P7 (attivazione, avviso, Impostazioni), P8 ("Disattiva e cancella") e P9 (sezione, elenchi, pagina,
// revisione, unione) scrivono ViewModel e composable con i soli componenti G2 esistenti; il filone grafico rifinisce senza toccare questi
// tipi. Solo dati gia' pronti da mostrare: niente NAS, niente percorsi, nessuna generazione, **mai un nascosto** (le viste e i conteggi
// vengono da core-faces, che filtra con `VisibleMedia` e `HiddenMask`, D28). Gli stati del §1.5 che vivono in altri moduli: il chip del
// viewer e' `ViewerFaceChip` (feature-viewer, P9), il chip "Persona" di Cerca e' di feature-search (P10), la regola "Persone" dell'editor
// e' di feature-albums (P10). Mai nomi o id nei log (D37): i `toString()` di questo file non li mostrano.

/** Perche' Persone non si puo' usare adesso (§2.1, §2.8): un codice stabile, il testo e' della schermata. */
enum class PeopleUnavailable(override val code: String) : StableCode {
  /** La voce `faces` dice una forma che questa versione non capisce (E-F11): "Aggiorna l'app". */
  NEEDS_UPDATE("needs_update"),

  /** "Disattiva e cancella" in corso: la sezione mostra lo stato della cancellazione. */
  ERASING("erasing"),

  /** I modelli dei volti non si caricano (asset dell'APK guasto, Q1): la Diagnostica dice `FACES_MODEL_UNAVAILABLE`. */
  MODELS("models"),
}

/** Raccolte -> Persone (§1.5 `PeopleSectionState`). */
sealed interface PeopleSectionState {
  /** La riga "Attiva riconoscimento dei volti" (apre la pagina di attivazione). */
  data object Off : PeopleSectionState

  /** Attiva sull'account, ma questo dispositivo non ha ancora visto l'avviso (D4): niente si scarica ne' si calcola qui. */
  data object AwaitingNotice : PeopleSectionState

  /** "Non su questo dispositivo" (D5): niente volti qui, con "Includi di nuovo". */
  data object Excluded : PeopleSectionState

  /** Il primo calcolo: [percent] dell'archivio fatto (null = aspetta, per esempio il Wi-Fi di casa o le regole). */
  data class FirstRun(val percent: Int?) : PeopleSectionState {
    init {
      require(percent == null || percent in 0..100) { "Percentuale fuori intervallo." }
    }
  }

  /**
   * Le persone in primo piano: con Q2 (b) **solo chi ha un nome** ([top], dal piu' fotografato), [total] persone con un nome, e la riga
   * "Altri volti" con [othersCount] gruppi senza nome (a un tocco). Nessun gruppo sparisce.
   */
  data class Ready(val top: List<PersonCard>, val total: Int, val othersCount: Int) : PeopleSectionState {
    override fun toString(): String = "PeopleSectionState.Ready(${top.size}/$total, altri=$othersCount)"
  }

  /**
   * Come [Ready], ma i dati dei volti restano su questo dispositivo: la recinzione delle cartelle vere (D34, Q5) o il NAS che non si
   * raggiunge. La sezione mostra la nota ("Per ora le persone restano su questo dispositivo").
   */
  data class LocalOnly(val top: List<PersonCard>, val total: Int, val othersCount: Int) : PeopleSectionState {
    override fun toString(): String = "PeopleSectionState.LocalOnly(${top.size}/$total, altri=$othersCount)"
  }

  data class Unavailable(val reason: PeopleUnavailable) : PeopleSectionState
}

/** "Tutte le persone" (§1.5 `PeopleListState`, Q2 (b)). */
sealed interface PeopleListState {
  /**
   * [named] dal piu' fotografato; [unnamed] vuota con Q2 (b) (i gruppi senza nome stanno in "Altri volti": [othersCount]); [hiddenCount]
   * persone nascoste (la riga "Persone nascoste" in Impostazioni).
   */
  data class Ready(
    val named: List<PersonCard>,
    val unnamed: List<PersonCard>,
    val othersCount: Int,
    val hiddenCount: Int,
  ) : PeopleListState {
    override fun toString(): String = "PeopleListState.Ready(${named.size}, ${unnamed.size}, altri=$othersCount, nascoste=$hiddenCount)"
  }

  data object Empty : PeopleListState

  data class Working(val percent: Int?) : PeopleListState
}

/** Le azioni della pagina persona (§2.6): il menu e i pulsanti le mostrano solo se ci sono. */
enum class PersonAction(override val code: String) : StableCode {
  RENAME("rename"),
  MERGE("merge"),
  SPLIT("split"),
  REVIEW("review"),
  SET_COVER("set_cover"),
  HIDE("hide"),
  UNHIDE("unhide"),
  FORGET("forget"),

  /** "Crea album smart" (D27): un album con la regola "Persone" su questa persona. L'editor degli album e' di `feature-albums` (P10), lo apre `:app`. */
  SMART_ALBUM("smart_album"),
}

/** La testa della pagina persona: nome (null = "Aggiungi un nome"), quante foto visibili, copertina (solo da un media mostrabile). */
data class PersonHeader(val id: PersonId, val name: String?, val count: Int, val cover: FaceLocator?) {
  override fun toString(): String = "PersonHeader(${name != null}, $count)"
}

/** La pagina persona (§1.5 `PersonPageState`, §2.6). */
sealed interface PersonPageState {
  data object Loading : PersonPageState

  /**
   * [view]: le foto della persona nell'ordine della Libreria (si apre il viewer su questa vista); [proposals]: "E' la stessa persona?"
   * con [PersonCard] gia' visibili.
   */
  class Ready(
    val header: PersonHeader,
    val view: OrderedTimelineView,
    val actions: Set<PersonAction>,
    val proposals: List<MergeProposal>,
  ) : PersonPageState {
    override fun toString(): String = "PersonPageState.Ready($header, ${actions.size} azioni, ${proposals.size} proposte)"
  }

  /** La persona e' stata dimenticata (anche da un altro dispositivo, E-F27): la pagina si chiude con un messaggio. */
  data object Forgotten : PersonPageState

  /** La persona e' nascosta: la pagina dice come mostrarla di nuovo, senza le foto. */
  data class Hidden(val header: PersonHeader) : PersonPageState

  data object NotFound : PersonPageState
}

/** Perche' si apre la griglia dei volti (§2.6). */
enum class ReviewPurpose(override val code: String) : StableCode {
  /** "Controlla le foto": i volti assegnati di una persona, per togliere quelli sbagliati ("Non e' …"). */
  CHECK("check"),

  /** "Dividi": si scelgono i volti della persona nuova (E-F20). */
  SPLIT("split"),

  /** "Altri volti" (Q2 (b)): i gruppi senza nome, per dare un nome o "Dimentica". */
  OTHERS("others"),
}

/** Un volto della griglia: il posto, il media (gia' visibile) e la persona attuale. La miniatura si ritaglia al momento ([PeopleCrops]). */
data class ReviewFace(val face: FaceLocator, val media: MediaId, val person: PersonId?) {
  override fun toString(): String = "ReviewFace(${person != null})"
}

/** La revisione dei volti (§1.5 `FaceReviewState`). */
sealed interface FaceReviewState {
  data class Grid(
    val purpose: ReviewPurpose,
    val person: PersonId?,
    val faces: List<ReviewFace>,
    val selection: Set<FaceLocator>,
  ) : FaceReviewState {
    /** "Dividi" vuole almeno un volto scelto e non tutti (la persona di partenza resta con qualcosa). */
    val canApply: Boolean
      get() = when (purpose) {
        ReviewPurpose.SPLIT -> selection.isNotEmpty() && selection.size < faces.size
        ReviewPurpose.CHECK, ReviewPurpose.OTHERS -> selection.isNotEmpty()
      }

    override fun toString(): String = "FaceReviewState.Grid(${purpose.code}, ${selection.size}/${faces.size})"
  }

  data object Applying : FaceReviewState

  data object Done : FaceReviewState
}

/** L'unione e le proposte "E' la stessa persona?" (§1.5 `MergeSheetState`, E-F23). */
sealed interface MergeSheetState {
  /** Si scelgono le persone da unire a quella della pagina. */
  data class Choose(val candidates: List<PersonCard>, val selected: Set<PersonId>) : MergeSheetState {
    override fun toString(): String = "MergeSheetState.Choose(${selected.size}/${candidates.size})"
  }

  /**
   * Due nomi diversi: si sceglie quale tenere ([chosen] null = conferma spenta). [proposal] (aggiunta di P9): viene da "E' la stessa persona?", e
   * allora il foglio offre anche "No, sono diverse" (altrimenti la coppia con due nomi non si potrebbe rifiutare per sempre).
   */
  data class PickName(val first: PersonCard, val second: PersonCard, val chosen: PersonId?, val proposal: MergeProposal? = null) : MergeSheetState {
    val canConfirm: Boolean get() = chosen == first.id || chosen == second.id

    override fun toString(): String = "MergeSheetState.PickName(scelto=${chosen != null})"
  }

  /** La conferma: [persons] diventano una ([keepNameOf] il nome che resta); [proposal] se viene da "E' la stessa persona?". */
  data class Confirm(val persons: List<PersonCard>, val keepNameOf: PersonId?, val proposal: MergeProposal?) : MergeSheetState {
    override fun toString(): String = "MergeSheetState.Confirm(${persons.size}, proposta=${proposal != null})"
  }
}

/**
 * Le copertine e le miniature dei volti, ritagliate **al momento** dalla migliore immagine gia' sul dispositivo (D25): mai salvate su
 * disco, mai da un media nascosto (chi lega la porta, P9, lo controlla con `VisibleMedia`). Null = niente da mostrare (un segnaposto).
 */
fun interface PeopleCrops {
  suspend fun crop(face: FaceLocator, sizePx: Int): android.graphics.Bitmap?
}

/**
 * Le azioni che le schermate chiedono fuori da se' (le esegue :app, P13): il viewer su una vista di persona, le altre pagine delle persone,
 * i modelli e la Diagnostica. I `feature-*` non si conoscono fra loro.
 */
interface PeopleActions {
  fun openViewer(view: OrderedTimelineView, start: MediaId)

  fun openPerson(id: PersonId)

  fun openAllPeople()

  fun openOthers()

  fun openActivation()

  fun openPeopleSettings()

  fun openDiagnostics()

  /** Impostazioni -> Persone -> "Persone nascoste" (E-F28): la pagina e' di feature-people, la raggiunge `:app` dalla pagina di P7. Aggiunta di P9. */
  fun openHiddenPeople() = Unit

  /**
   * "Crea album smart" dalla pagina persona (D27, §2.7): `:app` apre l'editor degli album di `feature-albums` con la regola "Persone" su
   * [person] e il titolo proposto [title] (il nome della persona, se ce l'ha). Aggiunta di P9; i `feature-*` non si conoscono.
   */
  fun createSmartAlbum(person: PersonId, title: String?) = Unit
}
