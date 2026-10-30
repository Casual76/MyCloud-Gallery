package dev.pampa.mycloudgallery.feature.people.page

import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.PersonFaces
import dev.pampa.mycloudgallery.core.faces.contract.PersonView
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.CoverChoicesUi
import dev.pampa.mycloudgallery.feature.people.contract.MergeUi
import dev.pampa.mycloudgallery.feature.people.contract.NamingUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.PersonAction
import dev.pampa.mycloudgallery.feature.people.contract.PersonHeader
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi
import dev.pampa.mycloudgallery.feature.people.sheets.MergeFlow
import dev.pampa.mycloudgallery.feature.people.sheets.asMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * LA PAGINA DI UNA PERSONA (Fase 8, P9; docs/fase8-progetto.md §1.5 `PersonPageState`, §2.6 "Pagina persona", D22, D24, D25, D28, E-F23,
 * E-F27, E-F28): l'intestazione (copertina, nome o "Aggiungi un nome", conto), le azioni, "Forse e' anche…" e le foto nell'ordine della
 * Libreria. Non sa niente di Compose.
 *
 * - La vista viene da `People.person` (celle mostrabili, mai un nascosto; nemmeno `viewIndexOf` ne raggiunge uno): qui non si filtra.
 * - Una persona **unita** a un'altra (alias) mostra la persona che resta: la pagina e' sempre della radice.
 * - Una persona che sparisce mentre la pagina e' aperta (dimenticata da un altro dispositivo, E-F27) lascia [PersonPageState.Forgotten]; una a cui si nascondono **tutte le foto**
 *   ([People.knows], NV-1) lascia [PersonPageState.AllHidden], che dice un'altra cosa (il nome c'e' ancora);
 *   una che non c'e' mai stata [PersonPageState.NotFound]. Nascosta: [PersonPageState.Hidden], che dice come mostrarla di nuovo.
 * - Le azioni sono tutte voci `people` di `People` ("vale subito"); "Dimentica" chiede la conferma, "Unisci" i suoi passi
 *   ([MergeFlow]). Il nome si da' in un foglio (`NamingUi`): 1-64 caratteri, il resto lo decide `People`.
 */
class PersonPageController(
  private val id: PersonId,
  private val people: People,
  private val faces: PersonFaces,
  private val scope: CoroutineScope,
) {
  private val naming = MutableStateFlow<NamingUi?>(null)
  private val forgetting = MutableStateFlow(false)
  private val covers = MutableStateFlow<CoverChoicesUi?>(null)
  private val message = MutableStateFlow<PeopleMessage?>(null)
  private var seen = false

  private val merging = MergeFlow(people, scope, onMessage = { message.value = it }, onMerged = { })

  /** La persona (la radice) che la pagina mostra adesso, per le azioni. */
  @Volatile private var current: PersonCard? = null

  private val page: StateFlow<PersonPageState> = people.person(id).map { view ->
    when {
      view == null -> if (seen) goneState() else PersonPageState.NotFound
      else -> {
        seen = true
        current = view.card
        stateOf(view)
      }
    }
  }.stateIn(scope, SharingStarted.Eagerly, PersonPageState.Loading)

  /**
   * NV-1 (correzioni D): la vista di una persona che la pagina aveva gia' non c'e' piu'. Se la persona esiste ancora ([People.knows]) non e' stata dimenticata: ha solo
   * le foto tutte nascoste, e la pagina lo dice ([PersonPageState.AllHidden], senza foto ne' conto e senza copertina). Altrimenti e' [PersonPageState.Forgotten].
   */
  private suspend fun goneState(): PersonPageState {
    val card = current ?: return PersonPageState.Forgotten
    val exists = try {
      people.knows(id)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (e: Exception) {
      false
    }
    return if (exists) PersonPageState.AllHidden(PersonHeader(card.id, card.name, 0, null)) else PersonPageState.Forgotten
  }

  private val sheets: StateFlow<Sheets> = combine(naming, forgetting, covers, message) { n, f, c, m -> Sheets(n, f, c, m) }
    .stateIn(scope, SharingStarted.Eagerly, Sheets(null, false, null, null))

  private class Sheets(val naming: NamingUi?, val forgetting: Boolean, val covers: CoverChoicesUi?, val message: PeopleMessage?)

  val ui: StateFlow<PersonPageUi> = combine(page, sheets, merging.ui) { state, s, merge ->
    PersonPageUi(state, s.naming, merge, s.forgetting, s.covers, s.message)
  }.stateIn(scope, SharingStarted.Eagerly, PersonPageUi())

  private fun stateOf(view: PersonView): PersonPageState {
    val card = view.card
    val header = PersonHeader(card.id, card.name, card.photos, card.cover)
    if (view.hidden) return PersonPageState.Hidden(header)
    val actions = buildSet {
      add(PersonAction.RENAME)
      add(PersonAction.MERGE)
      if (card.photos > 0) {
        add(PersonAction.REVIEW)
        add(PersonAction.SET_COVER)
        // SM-1: l'album smart di una persona ha per titolo il suo nome e per regola lei, fra le persone con un nome: senza nome prima si da' un nome.
        if (card.name != null) add(PersonAction.SMART_ALBUM)
      }
      if (card.photos > 1) add(PersonAction.SPLIT)
      add(PersonAction.HIDE)
      add(PersonAction.FORGET)
    }
    return PersonPageState.Ready(header, view.view, actions, view.maybeAlso.map { MergeProposal(card, it) })
  }

  // --- Il nome -----------------------------------------------------------------------------------------------------------------------

  /** "Aggiungi un nome" / "Rinomina": apre il foglio. */
  fun startNaming() {
    val card = current ?: return
    naming.value = NamingUi(card.id, card.name)
  }

  fun dismissNaming() {
    naming.value = null
  }

  /** Salva il nome. Non ammesso (vuoto, oltre 64 caratteri, caratteri vietati): il foglio resta aperto e lo dice. */
  fun saveName(text: String) {
    val open = naming.value ?: return
    scope.launch {
      when (val out = people.rename(open.person, text)) {
        EditOutcome.Done -> naming.value = null
        EditOutcome.InvalidName -> naming.value = open.copy(invalid = true)
        else -> {
          naming.value = null
          out.asMessage()?.let { message.value = it }
        }
      }
    }
  }

  /** "Togli il nome": la persona torna senza nome. */
  fun removeName() {
    val open = naming.value ?: return
    scope.launch {
      naming.value = null
      people.rename(open.person, null).asMessage()?.let { message.value = it }
    }
  }

  // --- Nascondi, dimentica ----------------------------------------------------------------------------------------------------------

  fun hide() = setHidden(true)

  /** "Mostra di nuovo" (dalla pagina di una persona nascosta). */
  fun unhide() = setHidden(false)

  private fun setHidden(hidden: Boolean) {
    val card = current ?: return
    scope.launch { people.setHidden(card.id, hidden).asMessage()?.let { message.value = it } }
  }

  fun askForget() {
    forgetting.value = true
  }

  fun dismissForget() {
    forgetting.value = false
  }

  /** Dopo la conferma: gruppo e nome spariscono, i suoi volti restano ignorati, nessuna impronta (E-F27). La pagina diventa [PersonPageState.Forgotten]. */
  fun confirmForget() {
    val card = current ?: return
    forgetting.value = false
    scope.launch { people.forget(card.id).asMessage()?.let { message.value = it } }
  }

  // --- Unisci ---------------------------------------------------------------------------------------------------------------------------

  /** "Unisci con…": si scelgono le altre persone fra quelle con un nome, quelle senza e i gruppi di "Altri volti". */
  fun startMerge() {
    val anchor = current ?: return
    scope.launch {
      val list = people.list.value
      val others = people.others().first().people
      merging.choose(anchor, (list.named + list.unnamed + others).distinctBy { it.id })
    }
  }

  fun toggleCandidate(id: PersonId) = merging.toggle(id)

  fun continueFromChoose() = merging.continueFromChoose()

  fun pickName(id: PersonId) = merging.pick(id)

  fun continueFromName() = merging.continueFromName()

  fun confirmMerge() = merging.confirm()

  fun dismissMerge() = merging.dismiss()

  /** Una proposta "Forse e' anche…" toccata: il foglio "E' la stessa persona?". */
  fun openProposal(proposal: MergeProposal) = merging.begin(listOf(proposal.first, proposal.second), proposal)

  fun rejectProposal() = merging.no()

  // --- Copertina ---------------------------------------------------------------------------------------------------------------------------

  /** "Scegli copertina": i volti della persona (di media mostrabili), dal piu' sicuro. */
  fun openCovers() {
    val card = current ?: return
    scope.launch { covers.value = CoverChoicesUi(faces.reviewFaces(card.id).sortedByDescending { it.confirmed }.take(MAX_COVER_CHOICES)) }
  }

  fun dismissCovers() {
    covers.value = null
  }

  fun pickCover(face: FaceLocator?) {
    val card = current ?: return
    covers.value = null
    scope.launch { people.setCover(card.id, face).asMessage()?.let { message.value = it } }
  }

  fun dismissMessage() {
    message.value = null
  }

  private companion object {
    const val MAX_COVER_CHOICES = 60
  }
}
