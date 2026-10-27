package dev.pampa.mycloudgallery.feature.people.hidden

import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.HiddenPeople
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.HiddenPeopleState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.sheets.asMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "PERSONE NASCOSTE" (Fase 8, P9; docs/fase8-progetto.md E-F28, §2.6 "Nascondi persona"): Impostazioni -> Persone -> "Persone nascoste". Una
 * persona nascosta sparisce da Persone, dai suggerimenti e dal dizionario della ricerca (le sue foto restano in Libreria): qui si ritrova e si
 * mostra di nuovo con un tocco. Elenca solo persone che avrebbero un posto fra quelle mostrate: una con soli media nascosti non c'e' (D28).
 * Non sa niente di Compose.
 */
class HiddenPeopleController(
  hidden: HiddenPeople,
  private val people: People,
  private val scope: CoroutineScope,
) {
  private val message = MutableStateFlow<PeopleMessage?>(null)

  val ui: StateFlow<HiddenPeopleState> = hidden.hidden()
    .map { list -> if (list.isEmpty()) HiddenPeopleState.Empty else HiddenPeopleState.Ready(list) }
    .stateIn(scope, SharingStarted.Eagerly, HiddenPeopleState.Loading)

  val notice: StateFlow<PeopleMessage?> get() = message

  /** "Mostra di nuovo": la persona torna in Persone (con un nome) o in "Altri volti". */
  fun show(id: PersonId) {
    scope.launch {
      val out: EditOutcome = people.setHidden(id, false)
      message.value = out.asMessage()
    }
  }

  fun dismissNotice() {
    message.value = null
  }
}
