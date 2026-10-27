package dev.pampa.mycloudgallery.feature.people.contract

import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.PersonFace
import dev.pampa.mycloudgallery.core.model.PersonId

// GLI STATI IN PIU' DELLE SCHERMATE DELLE PERSONE (docs/fase8-progetto.md §1.5, §2.6; D38). Aggiunte di P9 ai contratti di feature-people: cio'
// che una schermata ha oltre allo stato del §1.5 (la selezione, i fogli aperti, il messaggio dell'ultima azione). I controllori
// (`list/`, `page/`, `review/`, `hidden/`) li producono, le composable li disegnano; il filone grafico rifinisce senza toccarli. Solo dati gia'
// pronti da mostrare: mai un nascosto (le viste vengono da core-faces che filtra, D28), mai nomi o id nei `toString()` (D37).

/** Il foglio del nome: aggiungere il primo ([current] null) o cambiare quello che c'e'. [invalid] = l'ultimo tentativo non era ammesso. */
data class NamingUi(val person: PersonId, val current: String?, val invalid: Boolean = false) {
  override fun toString(): String = "NamingUi(${current != null}, invalid=$invalid)"
}

/** Un foglio di unione aperto: lo stato del §1.5 e se l'unione sta partendo (i bottoni si spengono, il foglio non si chiude da solo). */
data class MergeUi(val sheet: MergeSheetState, val busy: Boolean = false) {
  override fun toString(): String = "MergeUi($sheet, busy=$busy)"
}

/**
 * "Tutte le persone": lo stato del §1.5, le proposte "E' la stessa persona?" in cima, la selezione per "Unisci" e i fogli. [selected] ha senso
 * solo con [selecting]. [message] e' l'esito dell'ultima azione che non e' andata come previsto.
 */
data class PeopleListUi(
  val state: PeopleListState,
  val proposals: List<MergeProposal> = emptyList(),
  val selecting: Boolean = false,
  val selected: Set<PersonId> = emptySet(),
  val merge: MergeUi? = null,
  val message: PeopleMessage? = null,
) {
  override fun toString(): String = "PeopleListUi($state, ${proposals.size} proposte, scelte=${selected.size})"
}

/** "Altri volti" (Q2 (b)): la griglia dei gruppi senza nome (§1.5 `FaceReviewState`, `ReviewPurpose.OTHERS`) con il conto di ognuno. */
data class OthersUi(
  val state: FaceReviewState,
  /** Il gruppo di ogni volto della griglia (nome assente, conto delle foto): l'etichetta sotto l'avatar. */
  val groups: Map<PersonId, PersonCard> = emptyMap(),
  val selecting: Boolean = false,
  val forgetting: Boolean = false,
  val merge: MergeUi? = null,
  val message: PeopleMessage? = null,
) {
  override fun toString(): String = "OthersUi($state, ${groups.size} gruppi, selezione=$selecting)"
}

/** Le foto fra cui scegliere la copertina ("Scegli copertina"): i volti della persona, dal piu' sicuro. Mai salvate (ritaglio al momento). */
data class CoverChoicesUi(val faces: List<PersonFace>) {
  override fun toString(): String = "CoverChoicesUi(${faces.size})"
}

/** La pagina persona: lo stato del §1.5 e cio' che si apre sopra (foglio del nome, unione, "Dimentica", copertina) e l'ultimo messaggio. */
data class PersonPageUi(
  val page: PersonPageState = PersonPageState.Loading,
  val naming: NamingUi? = null,
  val merge: MergeUi? = null,
  val confirmForget: Boolean = false,
  val covers: CoverChoicesUi? = null,
  val message: PeopleMessage? = null,
) {
  override fun toString(): String = "PersonPageUi($page, nome=${naming != null}, unione=${merge != null}, dimentica=$confirmForget)"
}

/** "Controlla le foto" e "Dividi": la griglia dei volti di una persona, la selezione e dove si va dopo. */
data class FaceReviewUi(
  val state: FaceReviewState,
  val message: PeopleMessage? = null,
  /** Dopo "Dividi": la persona nuova, da aprire. Null dopo "Controlla le foto". */
  val openNext: PersonId? = null,
  /** Quanti volti ha tolto l'ultima azione (per dirlo). */
  val changed: Int = 0,
) {
  override fun toString(): String = "FaceReviewUi($state, cambiati=$changed)"
}

/** Le persone nascoste (Impostazioni -> Persone -> "Persone nascoste", E-F28). */
sealed interface HiddenPeopleState {
  data object Loading : HiddenPeopleState

  data object Empty : HiddenPeopleState

  data class Ready(val people: List<PersonCard>) : HiddenPeopleState {
    override fun toString(): String = "HiddenPeopleState.Ready(${people.size})"
  }
}
