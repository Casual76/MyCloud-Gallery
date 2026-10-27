package dev.pampa.mycloudgallery.feature.people.sheets

import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.MergeUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** L'esito di un'azione sulle persone come messaggio per la schermata (null = fatto, niente da dire). */
internal fun EditOutcome.asMessage(): PeopleMessage? = when (this) {
  EditOutcome.Done -> null
  EditOutcome.InvalidName -> PeopleMessage.INVALID_NAME
  EditOutcome.NotFound -> PeopleMessage.NOT_FOUND
  EditOutcome.NotActive -> PeopleMessage.NOT_ACTIVE
  EditOutcome.NeedsNameChoice, EditOutcome.Invalid -> PeopleMessage.INVALID
}

/**
 * L'unione di persone, passo per passo (docs/fase8-progetto.md §1.5 `MergeSheetState`, §2.6 "Unisci" e "Proposte", E-F23, D24). Non sa niente
 * di Compose. Tre ingressi: [choose] ("Unisci con…" dalla pagina di una persona: si scelgono le altre), [begin] (una selezione, o una proposta
 * "E' la stessa persona?") e [no] (la proposta rifiutata: le due persone non si riuniscono piu' da sole).
 *
 * **Con due nomi si sceglie quale tenere** ([MergeSheetState.PickName]); con piu' di due nomi si unisce due persone alla volta
 * ([PeopleMessage.TOO_MANY_NAMES]): la scelta di quale nome tenere ha senso fra due. Con un nome solo resta quello, senza nomi la persona
 * piu' fotografata. L'unione e' un alias (D22): niente cambia id, album e ricerche seguono; **si fa solo dopo un tocco su "Unisci"**.
 */
internal class MergeFlow(
  private val people: People,
  private val scope: CoroutineScope,
  private val onMessage: (PeopleMessage) -> Unit,
  private val onMerged: () -> Unit,
  /** Dopo ogni voce scritta (un'unione, un "No"): chi guarda le proposte le rilegge, perche' la lista delle persone puo' non cambiare. */
  private val onChanged: () -> Unit = {},
) {
  private val state = MutableStateFlow<MergeUi?>(null)
  val ui: StateFlow<MergeUi?> = state.asStateFlow()

  private var anchor: PersonCard? = null
  private var cards: List<PersonCard> = emptyList()
  private var proposal: MergeProposal? = null

  /** "Unisci con…" dalla pagina di [anchor]: si scelgono fra [candidates] le persone da unire. */
  fun choose(anchor: PersonCard, candidates: List<PersonCard>) {
    this.anchor = anchor
    this.cards = listOf(anchor)
    this.proposal = null
    state.value = MergeUi(MergeSheetState.Choose(candidates.filter { it.id != anchor.id }.distinctBy { it.id }, emptySet()))
  }

  /** Sceglie o toglie un candidato; il terzo nome non entra (si unisce due nomi alla volta). */
  fun toggle(id: PersonId) {
    val sheet = state.value?.sheet as? MergeSheetState.Choose ?: return
    val now = sheet.selected
    val next = if (id in now) now - id else now + id
    val named = (listOfNotNull(anchor) + sheet.candidates.filter { it.id in next }).count { it.name != null }
    if (id !in now && named > 2) {
      onMessage(PeopleMessage.TOO_MANY_NAMES)
      return
    }
    state.value = MergeUi(sheet.copy(selected = next))
  }

  /** Da "Unisci con…" al passo dopo: i nomi o la conferma. */
  fun continueFromChoose() {
    val sheet = state.value?.sheet as? MergeSheetState.Choose ?: return
    val first = anchor ?: return
    begin(listOf(first) + sheet.candidates.filter { it.id in sheet.selected }, null)
  }

  /** Una selezione di persone (o la proposta [from]) da unire: i nomi se sono due, altrimenti la conferma. */
  fun begin(persons: List<PersonCard>, from: MergeProposal? = null) {
    val unique = persons.distinctBy { it.id }
    if (unique.size < 2) {
      state.value = null
      return
    }
    cards = unique
    proposal = from
    val named = unique.filter { it.name != null }
    when {
      named.size > 2 -> {
        state.value = null
        onMessage(PeopleMessage.TOO_MANY_NAMES)
      }
      named.size == 2 -> state.value = MergeUi(MergeSheetState.PickName(named[0], named[1], null, from))
      else -> state.value = MergeUi(MergeSheetState.Confirm(unique, named.singleOrNull()?.id, from))
    }
  }

  /** Quale dei due nomi tenere. */
  fun pick(id: PersonId) {
    val sheet = state.value?.sheet as? MergeSheetState.PickName ?: return
    if (id != sheet.first.id && id != sheet.second.id) return
    state.value = MergeUi(sheet.copy(chosen = id))
  }

  /** Dai nomi alla conferma, solo con un nome scelto. */
  fun continueFromName() {
    val sheet = state.value?.sheet as? MergeSheetState.PickName ?: return
    if (!sheet.canConfirm) return
    state.value = MergeUi(MergeSheetState.Confirm(cards, sheet.chosen, proposal))
  }

  /** L'ultimo tocco: unisce. Un esito che chiede il nome torna alla scelta; gli altri chiudono il foglio con un messaggio. */
  fun confirm() {
    val sheet = state.value?.sheet as? MergeSheetState.Confirm ?: return
    if (state.value?.busy == true) return
    state.update { it?.copy(busy = true) }
    scope.launch {
      when (val out = people.merge(sheet.persons.map { it.id }, sheet.keepNameOf)) {
        EditOutcome.Done -> {
          state.value = null
          onMerged()
          onChanged()
        }
        EditOutcome.NeedsNameChoice -> {
          val named = sheet.persons.filter { it.name != null }
          state.value = if (named.size == 2) MergeUi(MergeSheetState.PickName(named[0], named[1], null, sheet.proposal)) else null
          if (named.size != 2) onMessage(PeopleMessage.INVALID)
        }
        else -> {
          state.value = null
          out.asMessage()?.let(onMessage)
        }
      }
    }
  }

  /** "No, sono diverse": la coppia non si riunisce piu' da sola (voce `apart`). */
  fun no() {
    val asked = proposal ?: return
    scope.launch {
      val out = people.answer(asked, same = false)
      state.value = null
      out.asMessage()?.let(onMessage)
      if (out == EditOutcome.Done) onChanged()
    }
  }

  fun dismiss() {
    state.value = null
  }
}
