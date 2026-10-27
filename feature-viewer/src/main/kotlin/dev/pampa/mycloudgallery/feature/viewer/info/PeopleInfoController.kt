package dev.pampa.mycloudgallery.feature.viewer.info

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceAction
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceOutcome
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceRef
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPersonChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Un foglio aperto sopra la riga "Persone": il menu del chip, "Chi e'?" o la conferma di "Ignora questo volto". */
internal sealed interface PeopleSheet {
  /** Il menu di un chip (pressione lunga, o le azioni di TalkBack): "Chi e'?", "Non e' …", "Apri la pagina", "Ignora questo volto". */
  data class Menu(val chip: ViewerFaceChip) : PeopleSheet

  /** "Chi e'?": le persone con un nome fra cui scegliere, o un nome nuovo. [invalid] = l'ultimo nome non era ammesso. */
  data class Who(val face: ViewerFaceRef, val choices: List<ViewerPersonChoice>, val invalid: Boolean = false) : PeopleSheet

  /** "Ignora questo volto" chiede conferma: non c'e' un "annulla" (poster, TV, statue: il volto esce da tutto, E-F3). */
  data class ConfirmIgnore(val face: ViewerFaceRef) : PeopleSheet
}

/**
 * La riga "Persone" delle informazioni, senza Compose (docs/fase8-progetto.md §1.5 `ViewerFaceChip`, §2.6 "Info del viewer", D24, D25, D28): cosa
 * succede a un tocco su un chip e quali azioni sui volti ("Chi e'?", "Non e' …", "Ignora questo volto") partono dai fogli. Le azioni vanno a
 * [ViewerPeople.act] (`People` di core-faces, legato da `:app`): voci `people` che valgono subito qui e viaggiano con la pubblicazione.
 *
 * Il viewer non vede core-faces e non sa cosa c'e' dietro un riferimento ([ViewerFaceRef]): lo passa indietro com'e'. **Nessun nome nei log**
 * (D37). Un'azione che non va ([ViewerFaceOutcome]) lascia un avviso sotto i chip, che sparisce alla prossima.
 */
internal class PeopleInfoController(
  private val source: ViewerPeople,
  private val scope: CoroutineScope,
) {
  private val sheetState = MutableStateFlow<PeopleSheet?>(null)
  val sheet: StateFlow<PeopleSheet?> = sheetState.asStateFlow()

  private val noticeState = MutableStateFlow<ViewerFaceOutcome?>(null)

  /** L'ultimo esito che non era "fatto" (il volto non c'e' piu', Persone non e' attiva qui), o null. */
  val notice: StateFlow<ViewerFaceOutcome?> = noticeState.asStateFlow()

  /** Il tocco su un chip: una persona con nome apre la sua pagina; un volto senza nome chiede "Chi e'?". */
  fun tap(media: MediaId, chip: ViewerFaceChip) {
    when (chip) {
      is ViewerFaceChip.Named -> source.openPerson(chip.person)
      is ViewerFaceChip.Unnamed -> who(media, chip.face)
      is ViewerFaceChip.Unrecognizable -> Unit
    }
  }

  fun menu(chip: ViewerFaceChip) {
    if (chip is ViewerFaceChip.Unrecognizable) return
    noticeState.value = null
    sheetState.value = PeopleSheet.Menu(chip)
  }

  fun who(media: MediaId, face: ViewerFaceRef) {
    noticeState.value = null
    scope.launch { sheetState.value = PeopleSheet.Who(face, source.choices(media, face)) }
  }

  fun openPage(person: PersonId) {
    sheetState.value = null
    source.openPerson(person)
  }

  /** "E' …" una persona che c'e'. */
  fun choose(media: MediaId, face: ViewerFaceRef, person: PersonId) = act(media, face, ViewerFaceAction.Is(person))

  /** "E' …" una persona nuova con questo nome (1-64 caratteri: se non va il foglio resta aperto e lo dice). */
  fun createPerson(media: MediaId, face: ViewerFaceRef, name: String) = act(media, face, ViewerFaceAction.IsNew(name))

  /** "Non e' …" la persona a cui il volto e' assegnato adesso. */
  fun notThisPerson(media: MediaId, face: ViewerFaceRef, person: PersonId) = act(media, face, ViewerFaceAction.IsNot(person))

  fun askIgnore(face: ViewerFaceRef) {
    sheetState.value = PeopleSheet.ConfirmIgnore(face)
  }

  /** Dopo la conferma: il volto esce da tutto. */
  fun confirmIgnore(media: MediaId, face: ViewerFaceRef) = act(media, face, ViewerFaceAction.Ignore)

  fun dismiss() {
    sheetState.value = null
  }

  private fun act(media: MediaId, face: ViewerFaceRef, action: ViewerFaceAction) {
    val before = sheetState.value
    scope.launch {
      when (val outcome = source.act(media, face, action)) {
        ViewerFaceOutcome.DONE -> {
          sheetState.value = null
          noticeState.value = null
        }
        // Un nome non ammesso: il foglio resta aperto con il suo avviso e il testo scritto.
        ViewerFaceOutcome.INVALID_NAME -> sheetState.value = (before as? PeopleSheet.Who)?.copy(invalid = true) ?: before
        ViewerFaceOutcome.NOT_FOUND, ViewerFaceOutcome.UNAVAILABLE -> {
          sheetState.value = null
          noticeState.value = outcome
        }
      }
    }
  }
}
