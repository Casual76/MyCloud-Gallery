package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// LE PERSONE: NOMI, CORREZIONI, VISTE (docs/fase8-progetto.md §1.2 "Persone", "Indice", §2.6, §2.9, D24-D28, Q2 (b) del 27/10, E-F20-E-F29,
// E-F38). Contratti di P0; l'implementazione (people/) e le schermate (feature-people) sono di P9, l'esecutore di riferimento e'
// `testing/InMemoryPeople`. Ogni azione scrive voci `people` ([PeopleEdit]), vale **subito** su questo dispositivo anche senza l'ora del NAS
// e viaggia con la pubblicazione dei volti (E-F5). **Nascosti mai** (D28): conteggi, copertine, pagine, proposte e dizionario solo da media
// visibili (`VisibleMedia`, filtro finale `HiddenMask`). Mai nomi o id nei log (D37): nessun `toString()` di questo file li mostra.

/** Il livello di un'appartenenza (D27, §2.7): **sicuro** = volto assegnato (automatico o confermato); **forse** = fra `tSuggest` e `tJoin`. */
enum class MatchLevel { SURE, LIKELY }

/**
 * Una persona in un elenco (§1.5): [name] null = senza nome; [photos] = celle **visibili** con un suo volto (membri del gruppo compresi);
 * [cover] = il volto da ritagliare al momento (mai salvato, D25), solo da un media mostrabile; null = nessuna copertina possibile.
 */
data class PersonCard(val id: PersonId, val name: String?, val photos: Int, val cover: FaceLocator?) {
  init {
    require(photos >= 0) { "Conteggio negativo." }
  }

  override fun toString(): String = "PersonCard(${name != null}, $photos)"
}

/**
 * "Tutte le persone" (§1.5 `PeopleListState`, Q2 (b)): [named] = persone con un nome, in ordine (prima con piu' foto, poi per id);
 * [unnamed] = le senza nome **solo** con Q2 (a) (con Q2 (b) e' vuoto e le senza nome stanno in "Altri volti", [othersCount]);
 * [hiddenCount] = le nascoste (in Impostazioni -> Persone -> "Persone nascoste"); [progressPercent] = primo calcolo in corso, o null.
 */
data class PeopleList(
  val named: List<PersonCard>,
  val unnamed: List<PersonCard>,
  val othersCount: Int,
  val hiddenCount: Int,
  val progressPercent: Int?,
) {
  override fun toString(): String = "PeopleList(${named.size}, ${unnamed.size}, altri=$othersCount, nascoste=$hiddenCount)"

  companion object {
    val EMPTY = PeopleList(emptyList(), emptyList(), 0, 0, null)
  }
}

/**
 * La pagina persona (§1.5 `PersonPageState`, §2.6): intestazione, numero di foto, foto in ordine di tempo ([view]: una
 * `OrderedTimelineView` delle celle **visibili** con un volto della persona; nemmeno `viewIndexOf` raggiunge un nascosto), proposte "Forse e'
 * anche…" ([maybeAlso]). [hidden] = persona nascosta (la pagina lo dice). Una persona dimenticata o sconosciuta non ha pagina (null).
 */
class PersonView(
  val card: PersonCard,
  val view: OrderedTimelineView,
  val hidden: Boolean,
  val maybeAlso: List<PersonCard>,
) {
  override fun toString(): String = "PersonView($card, nascosta=$hidden, ${maybeAlso.size} proposte)"
}

/** "Altri volti" (Q2 (b)): le persone senza nome con almeno 3 foto visibili, dalla piu' grande. Con Q2 (a) e' vuoto. */
data class OthersView(val people: List<PersonCard>) {
  override fun toString(): String = "OthersView(${people.size})"
}

/**
 * Un volto del media aperto per la riga "Persone" del viewer (§2.6): il posto, la persona (null = non assegnato) e il suo nome, il livello.
 * I volti NONE non hanno vettore: la riga li conta come "N volti non riconoscibili". Una persona nascosta non compare (D28).
 */
data class FaceChip(val face: FaceLocator, val person: PersonId?, val name: String?, val tier: FaceTier) {
  override fun toString(): String = "FaceChip(${tier.code}, ${person != null}, ${name != null})"
}

/** L'esito di un'azione sulle persone: mai un'eccezione per un caso previsto. */
sealed interface EditOutcome {
  /** Fatto: vale subito qui, viaggia con la pubblicazione. */
  data object Done : EditOutcome

  /** Nome non ammesso: vuoto, oltre 64 caratteri, caratteri di controllo, di direzione o invisibili ([PersonNames]). */
  data object InvalidName : EditOutcome

  /** La persona o il volto non ci sono (piu'): dimenticata, unita, sparita. */
  data object NotFound : EditOutcome

  /** "Unisci" di piu' persone con un nome senza dire quale tenere (E-F23). */
  data object NeedsNameChoice : EditOutcome

  /** La richiesta non ha senso (una persona sola da unire, un volto che non e' di quella persona). */
  data object Invalid : EditOutcome

  /** Persone non e' attiva su questo dispositivo (spenta, in attesa dell'avviso, esclusa, in cancellazione). */
  data object NotActive : EditOutcome
}

/** L'esito di "Dividi" (D24): la persona nuova, "separata" dalla prima, o perche' no. */
sealed interface SplitOutcome {
  data class Split(val newPerson: PersonId) : SplitOutcome {
    override fun toString(): String = "SplitOutcome.Split"
  }

  data class Failed(val outcome: EditOutcome) : SplitOutcome
}

/** "E' …" (§2.6): una persona esistente o una nuova con un nome. */
sealed interface PersonTarget {
  data class Existing(val id: PersonId) : PersonTarget {
    override fun toString(): String = "PersonTarget.Existing"
  }

  data class New(val name: String) : PersonTarget {
    override fun toString(): String = "PersonTarget.New"
  }
}

/** Una proposta "E' la stessa persona?" (D20 (a), §2.6) come la vede la UI: due persone visibili. */
data class MergeProposal(val first: PersonCard, val second: PersonCard) {
  val pair: PersonPair get() = PersonPair.of(first.id, second.id)

  override fun toString(): String = "MergeProposal"
}

/** Un nome del dizionario per la ricerca (D26): la persona, il nome e quanti media visibili ha. */
data class PersonName(val id: PersonId, val name: String, val visibleMedia: Int) {
  override fun toString(): String = "PersonName($visibleMedia)"
}

/**
 * Le persone (D24, D25, §2.6), su QUESTO dispositivo. [list], [person] e [others] sono le viste (nascosti mai); le azioni scrivono voci
 * `people` e valgono subito. [merge] con due nomi vuole [keepNameOf] (E-F23); [split] crea una persona nuova con un id casuale generato qui,
 * "separata" dalla prima (E-F20); [forget] toglie gruppo e nome e lascia i volti ignorati, senza impronte per riconoscerla (E-F27);
 * [answer] di una proposta: "Si'" = alias, "No" = separate per sempre (la coppia non si ripropone).
 */
interface People {
  val list: StateFlow<PeopleList>

  fun person(id: PersonId): Flow<PersonView?>

  fun others(): Flow<OthersView>

  suspend fun facesOf(media: MediaId): List<FaceChip>

  suspend fun rename(id: PersonId, name: String?): EditOutcome

  suspend fun merge(ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome

  suspend fun split(id: PersonId, faces: List<FaceLocator>): SplitOutcome

  suspend fun assign(face: FaceLocator, to: PersonTarget): EditOutcome

  suspend fun reject(face: FaceLocator, from: PersonId): EditOutcome

  suspend fun ignore(face: FaceLocator): EditOutcome

  suspend fun setHidden(id: PersonId, hidden: Boolean): EditOutcome

  suspend fun forget(id: PersonId): EditOutcome

  suspend fun setCover(id: PersonId, face: FaceLocator?): EditOutcome

  suspend fun proposals(): List<MergeProposal>

  suspend fun answer(proposal: MergeProposal, same: Boolean): EditOutcome

  /**
   * NV-1 (correzioni D): la persona **c'e' ancora** (anche unita a un'altra), dimenticata no. Serve a distinguere, quando [person] non da' piu' una pagina, "dimenticata" da
   * "ha le foto tutte nascoste": nessuna foto, nessun conto, solo un si' o un no per un id che la pagina aperta gia' ha. False se Persone non e' attiva qui.
   */
  suspend fun knows(id: PersonId): Boolean
}

/**
 * L'indice delle persone per la ricerca e gli album smart (D26, D27): le porte `PeopleLookup` di core-search e `PeopleMembership` di
 * core-albums le lega :app sopra questo (core-search e core-albums non vedono core-faces). [available] falso = Persone non attiva qui (il
 * dizionario e' vuoto, nessuna classe). [dictionary]: persone con un nome, non nascoste ne' dimenticate, con almeno un media visibile.
 * [contentWith]: le chiavi di contenuto con un volto delle persone, "una qualunque" o "tutte", al livello [level] o meglio; **lancia** (`IllegalStateException`) se i volti
 * non si leggono adesso (R10: "non so" non vale "nessuna foto"), e un vuoto e' un vuoto vero. [resolve] segue
 * gli alias (catene e cicli, §2.5); null = dimenticata o sconosciuta.
 */
interface PeopleIndex {
  val available: StateFlow<Boolean>

  suspend fun dictionary(): List<PersonName>

  suspend fun contentWith(persons: Set<PersonId>, mode: PeopleMode, level: MatchLevel): Map<ContentKey, MatchLevel>

  fun resolve(id: PersonId): PersonId?
}
