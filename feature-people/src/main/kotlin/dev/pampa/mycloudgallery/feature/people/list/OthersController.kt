package dev.pampa.mycloudgallery.feature.people.list

import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.MergeUi
import dev.pampa.mycloudgallery.feature.people.contract.OthersUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.ReviewFace
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.sheets.MergeFlow
import dev.pampa.mycloudgallery.feature.people.sheets.asMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "ALTRI VOLTI" (Fase 8, P9; docs/fase8-progetto.md Q2 (b) del 27/10, §1.5 `FaceReviewState` con `ReviewPurpose.OTHERS`, §2.6 "Altri
 * volti", D24, D25): le persone **senza nome** con almeno 3 foto mostrabili, dalla piu' grande, a un tocco dalle Raccolte. Nessun gruppo
 * sparisce: qui si da' un nome (la pagina della persona) o si fa dimenticare, e si uniscono quelli che sono la stessa persona.
 *
 * La griglia e' quella dei volti ([FaceReviewState.Grid]): un volto per gruppo (la sua copertina, ritagliata al momento, mai salvata), con
 * l'etichetta del gruppo da [OthersUi.groups]. Un tocco apre la pagina (dove si da' il nome); la pressione lunga entra nella selezione, con
 * "Unisci" e "Dimentica". Con Q2 (a) la lista e' vuota: i gruppi stanno gia' in "Tutte le persone".
 *
 * Non sa niente di Compose. Dimenticare chiede la conferma ([OthersUi.forgetting]): i volti di quel gruppo non si raggruppano piu'.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OthersController(
  private val people: People,
  private val faces: PersonFaces,
  private val scope: CoroutineScope,
) {
  private val selecting = MutableStateFlow(false)
  private val selected = MutableStateFlow<Set<PersonId>>(emptySet())
  private val forgetting = MutableStateFlow(false)
  private val message = MutableStateFlow<PeopleMessage?>(null)

  private val merging = MergeFlow(
    people, scope,
    onMessage = { message.value = it },
    onMerged = {
      selecting.value = false
      selected.value = emptySet()
    },
  )

  /** I gruppi di adesso, ognuno con il volto da mostrare e la cella da cui si ritaglia. */
  private val groups: StateFlow<List<Pair<PersonCard, ReviewFace>>?> = people.others()
    .mapLatest { view ->
      val media = faces.coverMedia(view.people.map { it.id })
      view.people.mapNotNull { card ->
        val face = card.cover ?: return@mapNotNull null
        val cell = media[card.id] ?: return@mapNotNull null
        card to ReviewFace(face, cell, card.id)
      }
    }
    .stateIn(scope, SharingStarted.Eagerly, null)

  private val content: StateFlow<OthersUi> = combine(groups, selecting, selected, forgetting) { list, choosing, chosen, asking ->
    val grid = list.orEmpty()
    val ids = grid.map { it.first.id }.toSet()
    val keep = chosen.filterTo(HashSet()) { it in ids }
    val state: FaceReviewState = if (list == null) {
      FaceReviewState.Applying
    } else {
      FaceReviewState.Grid(
        purpose = ReviewPurpose.OTHERS,
        person = null,
        faces = grid.map { it.second },
        selection = grid.filter { it.first.id in keep }.map { it.second.face }.toSet(),
      )
    }
    OthersUi(state = state, groups = grid.associate { it.first.id to it.first }, selecting = choosing, forgetting = asking && keep.isNotEmpty())
  }.stateIn(scope, SharingStarted.Eagerly, OthersUi(FaceReviewState.Applying))

  val ui: StateFlow<OthersUi> = combine(content, merging.ui, message) { base, merge: MergeUi?, note -> base.copy(merge = merge, message = note) }
    .stateIn(scope, SharingStarted.Eagerly, OthersUi(FaceReviewState.Applying))

  // --- Selezione ------------------------------------------------------------------------------------------------------------------------

  fun setSelecting(on: Boolean) {
    selecting.value = on
    if (!on) {
      selected.value = emptySet()
      forgetting.value = false
    }
  }

  /** La pressione lunga: entra nella selezione con questo gruppo gia' scelto. */
  fun startWith(id: PersonId) {
    selecting.value = true
    selected.value = setOf(id)
  }

  fun toggle(id: PersonId) {
    if (!selecting.value) return
    selected.update { if (id in it) it - id else it + id }
  }

  /** Il gruppo di un volto della griglia. */
  fun groupOf(face: FaceLocator): PersonId? = (ui.value.state as? FaceReviewState.Grid)?.faces?.firstOrNull { it.face == face }?.person

  // --- Unione e "Dimentica" ----------------------------------------------------------------------------------------------------------

  fun mergeSelected() {
    val cards = ui.value.groups.values.filter { it.id in selected.value }
    merging.begin(cards)
  }

  fun pickName(id: PersonId) = merging.pick(id)

  fun continueFromName() = merging.continueFromName()

  fun confirmMerge() = merging.confirm()

  fun dismissMerge() = merging.dismiss()

  fun askForget() {
    if (selected.value.isNotEmpty()) forgetting.value = true
  }

  fun dismissForget() {
    forgetting.value = false
  }

  /** Dopo la conferma: ogni gruppo scelto si dimentica (i suoi volti restano ignorati, nessuna impronta, E-F27). */
  fun confirmForget() {
    val ids = selected.value.toList()
    forgetting.value = false
    scope.launch {
      var failed: EditOutcome? = null
      for (id in ids) {
        val out = people.forget(id)
        if (out != EditOutcome.Done && failed == null) failed = out
      }
      selected.value = emptySet()
      selecting.value = false
      failed?.asMessage()?.let { message.value = it }
    }
  }

  fun dismissMessage() {
    message.value = null
  }
}
