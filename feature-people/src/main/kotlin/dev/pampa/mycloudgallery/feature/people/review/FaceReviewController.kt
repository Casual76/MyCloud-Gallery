package dev.pampa.mycloudgallery.feature.people.review

import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.ReviewFace
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.sheets.asMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "CONTROLLA LE FOTO" e "DIVIDI" (Fase 8, P9; docs/fase8-progetto.md §1.5 `FaceReviewState`, §2.6, D23, D24, E-F20, E-F21, E-F3): la griglia dei
 * volti di [person], dal meno sicuro al piu' sicuro (l'assegnazione dei volti dubbi viene per prima), con la selezione.
 *
 *  - [ReviewPurpose.CHECK]: i volti scelti non sono di questa persona ("Non e' …": escono e non ci tornano, E-F21) oppure vanno ignorati
 *    ("Ignora questo volto": poster, TV, statue, E-F3);
 *  - [ReviewPurpose.SPLIT]: i volti scelti diventano una persona nuova, "separata" dalla prima (E-F20); non tutti (la persona di partenza
 *    resta con qualcosa: [FaceReviewState.Grid.canApply]).
 *
 * Non sa niente di Compose. **I volti vengono da celle mostrabili** (`PersonFaces.reviewFaces`: mai un nascosto, D28). Ogni azione sono voci
 * `people` di `People`: qui si decide solo cosa chiedere e che messaggio lasciare. Mai nomi o id nei log (D37).
 */
class FaceReviewController(
  private val person: PersonId,
  private val purpose: ReviewPurpose,
  private val people: People,
  private val faces: PersonFaces,
  private val scope: CoroutineScope,
) {
  init {
    require(purpose == ReviewPurpose.CHECK || purpose == ReviewPurpose.SPLIT) { "Questa griglia e' per controllare o dividere." }
  }

  private val state = MutableStateFlow(FaceReviewUi(FaceReviewState.Applying))
  val ui: StateFlow<FaceReviewUi> = state.asStateFlow()

  init {
    scope.launch { load() }
  }

  private suspend fun load() {
    val rows = faces.reviewFaces(person).map { ReviewFace(it.face, it.media, person) }
    state.update { it.copy(state = FaceReviewState.Grid(purpose, person, rows, emptySet())) }
  }

  private fun grid(): FaceReviewState.Grid? = state.value.state as? FaceReviewState.Grid

  /** Sceglie o toglie un volto. */
  fun toggle(face: FaceLocator) {
    val current = grid() ?: return
    if (current.faces.none { it.face == face }) return
    val next = if (face in current.selection) current.selection - face else current.selection + face
    state.update { it.copy(state = current.copy(selection = next), message = null) }
  }

  /** Toglie la selezione. */
  fun clear() {
    val current = grid() ?: return
    state.update { it.copy(state = current.copy(selection = emptySet())) }
  }

  /** "Non e' …": i volti scelti non sono di questa persona. */
  fun rejectSelected() {
    val current = grid() ?: return
    if (purpose != ReviewPurpose.CHECK || current.selection.isEmpty()) return
    apply(current) { face -> people.reject(face, person) }
  }

  /** "Non e' …" su un volto solo (l'azione di TalkBack): non cambia la selezione. */
  fun rejectOne(face: FaceLocator) {
    val current = grid() ?: return
    if (purpose != ReviewPurpose.CHECK || current.faces.none { it.face == face }) return
    apply(current.copy(selection = setOf(face))) { target -> people.reject(target, person) }
  }

  /** "Ignora questo volto" sui volti scelti. */
  fun ignoreSelected() {
    val current = grid() ?: return
    if (purpose != ReviewPurpose.CHECK || current.selection.isEmpty()) return
    apply(current) { face -> people.ignore(face) }
  }

  private fun apply(current: FaceReviewState.Grid, action: suspend (FaceLocator) -> EditOutcome) {
    val chosen = current.faces.map { it.face }.filter { it in current.selection }
    state.update { it.copy(state = FaceReviewState.Applying) }
    scope.launch {
      var done = 0
      var failure: EditOutcome? = null
      for (face in chosen) {
        val out = action(face)
        if (out == EditOutcome.Done) done++ else if (failure == null) failure = out
      }
      val message = failure?.asMessage()
      state.value = if (message == null) {
        FaceReviewUi(FaceReviewState.Done, changed = done)
      } else {
        // GE-2 (correzioni D): un'azione fallita non sembra riuscita. Si resta sulla schermata, con la griglia di adesso (i volti riusciti sono usciti) e il messaggio, che si legge:
        // "Persone non e' attiva", "il volto non c'e' piu'". Prima la pagina tornava indietro appena lo stato era `Done`, senza dire niente.
        FaceReviewUi(regrid(current), message = message, changed = done)
      }
    }
  }

  /** La griglia di adesso dopo un'azione che non e' andata fino in fondo: i volti ancora della persona, senza selezione. Se non si rilegge, quella di prima. */
  private suspend fun regrid(before: FaceReviewState.Grid): FaceReviewState.Grid = try {
    before.copy(faces = faces.reviewFaces(person).map { ReviewFace(it.face, it.media, person) }, selection = emptySet())
  } catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
  } catch (e: Exception) {
    before.copy(selection = emptySet())
  }

  /** "Sposta in una persona nuova": i volti scelti, non tutti. La persona nuova si apre dopo (la porta il risultato). */
  fun split() {
    val current = grid() ?: return
    if (purpose != ReviewPurpose.SPLIT || !current.canApply) return
    val chosen = current.faces.map { it.face }.filter { it in current.selection }
    state.update { it.copy(state = FaceReviewState.Applying) }
    scope.launch {
      state.value = when (val out = people.split(person, chosen)) {
        is SplitOutcome.Split -> FaceReviewUi(FaceReviewState.Done, openNext = out.newPerson, changed = chosen.size)
        is SplitOutcome.Failed -> FaceReviewUi(current, message = out.outcome.asMessage() ?: PeopleMessage.INVALID)
      }
    }
  }

  fun dismissMessage() {
    state.update { it.copy(message = null) }
  }
}
