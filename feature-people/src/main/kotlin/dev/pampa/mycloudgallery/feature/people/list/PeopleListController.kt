package dev.pampa.mycloudgallery.feature.people.list

import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.sheets.MergeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * "TUTTE LE PERSONE" (Fase 8, P9; docs/fase8-progetto.md §1.5 `PeopleListState`, §2.6 "Cosa si vede", Q2 (b), D24, D25): la griglia delle
 * persone con un nome (con Q2 (a) anche le altre), le proposte "E' la stessa persona?" in cima, e la selezione per "Unisci". Non sa niente di
 * Compose. Le viste vengono da `People` (nascosti mai, copertine solo da media mostrabili, D28): qui non si filtra niente e non si calcola
 * niente di nuovo. **Nessuna azione sui file**: le sole scritture sono le voci `people` che `People` fa.
 *
 * Le proposte si rileggono quando cambia la lista (un nome dato, un'unione, un "No"): il "No" non ripropone piu' la stessa coppia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleListController(
  private val people: People,
  private val scope: CoroutineScope,
) {
  private val selecting = MutableStateFlow(false)
  private val selected = MutableStateFlow<Set<PersonId>>(emptySet())
  private val message = MutableStateFlow<PeopleMessage?>(null)

  /** Sale a ogni voce scritta da qui: un "No" non cambia la lista delle persone, ma le proposte si' (la coppia non si ripropone). */
  private val written = MutableStateFlow(0)

  private val merging = MergeFlow(
    people, scope,
    onMessage = { message.value = it },
    onMerged = {
      selecting.value = false
      selected.value = emptySet()
    },
    onChanged = { written.update { it + 1 } },
  )

  /** Le proposte di adesso: si rifanno a ogni cambio della lista e a ogni voce scritta da qui. */
  private val proposals: StateFlow<List<MergeProposal>> = combine(people.list, written) { _, _ -> }
    .mapLatest { people.proposals() }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Cio' che si legge dalle persone, la selezione e i fogli: due passi perche' `combine` tipato arriva a cinque flussi. */
  private val content: StateFlow<PeopleListUi> = combine(people.list, proposals, selecting, selected) { list, proposalList, choosing, chosen ->
    val shown = list.named + list.unnamed
    PeopleListUi(
      state = stateOf(list),
      proposals = proposalList,
      selecting = choosing,
      selected = chosen.filterTo(HashSet()) { id -> shown.any { it.id == id } },
    )
  }.stateIn(scope, SharingStarted.Eagerly, PeopleListUi(stateOf(people.list.value)))

  val ui: StateFlow<PeopleListUi> = combine(content, merging.ui, message) { base, merge, note -> base.copy(merge = merge, message = note) }
    .stateIn(scope, SharingStarted.Eagerly, PeopleListUi(stateOf(people.list.value)))

  /** Lo stato del §1.5: il primo calcolo in corso (e nessuna persona ancora), vuoto, o le persone. */
  private fun stateOf(list: PeopleList): PeopleListState = when {
    list.named.isEmpty() && list.unnamed.isEmpty() && list.othersCount == 0 && list.progressPercent != null -> PeopleListState.Working(list.progressPercent)
    list.named.isEmpty() && list.unnamed.isEmpty() && list.othersCount == 0 -> PeopleListState.Empty
    else -> PeopleListState.Ready(list.named, list.unnamed, list.othersCount, list.hiddenCount)
  }

  // --- Selezione ------------------------------------------------------------------------------------------------------------------------

  /** Entra o esce dalla selezione ("Seleziona" / "Fine"). Uscendo si dimentica cio' che era scelto. */
  fun setSelecting(on: Boolean) {
    selecting.value = on
    if (!on) selected.value = emptySet()
  }

  /** La pressione lunga: entra nella selezione con questa persona gia' scelta. */
  fun startWith(id: PersonId) {
    selecting.value = true
    selected.value = setOf(id)
  }

  fun toggle(id: PersonId) {
    if (!selecting.value) return
    selected.update { if (id in it) it - id else it + id }
  }

  // --- Unione e proposte ------------------------------------------------------------------------------------------------------------

  /** "Unisci" sulle persone scelte: i nomi (con due nomi) o la conferma. */
  fun mergeSelected() {
    val list = people.list.value
    val cards = (list.named + list.unnamed).filter { it.id in selected.value }
    merging.begin(cards)
  }

  /** Una proposta toccata: il foglio "E' la stessa persona?". */
  fun openProposal(proposal: MergeProposal) = merging.begin(listOf(proposal.first, proposal.second), proposal)

  fun pickName(id: PersonId) = merging.pick(id)

  fun continueFromName() = merging.continueFromName()

  fun confirmMerge() = merging.confirm()

  fun rejectProposal() = merging.no()

  fun dismissMerge() = merging.dismiss()

  fun dismissMessage() {
    message.value = null
  }
}
