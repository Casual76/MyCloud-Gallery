package dev.pampa.mycloudgallery.feature.albums.editor

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.BroadHint
import dev.pampa.mycloudgallery.feature.albums.contract.NoPeopleChoices
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleChoices
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleRuleInfo
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// L'EDITOR DELLE REGOLE (Fase 7, P10; docs/fase7-progetto.md §1.5 `SmartEditorState`, D13, D14, D15, D16, §2.2 "Creazione", E-AL1, E-AL2, E-AL6,
// E-AL9, E-AL12). Il nome dell'album diventa regole che si **vedono e si cambiano tutte prima di creare**: periodo (con le serie di giorni del
// raffinamento), luogo (con gli omonimi e il raggio), descrizione, fino a 6 foto d'esempio, sorgenti, tipi. Ad ogni cambio l'anteprima si
// rifa' (quanti sicuri, quanti suggeriti, 12 miniature): se le regole sono troppo larghe l'editor lo dice e propone di restringerle (oltre 3.000
// elementi o il 20% della Libreria); se la descrizione non ha il motore di ricerca lo dice (D15) e le altre regole lavorano lo stesso.
//
// Due modi: **creare** (parte dal titolo, che passa dall'interprete) e **cambiare** le regole di un album che c'e' (parte dalle sue regole).
// Non sa niente di Compose. Mai un nascosto: l'anteprima e' del motore, che parte dai candidati visibili.
//
// Fase 8 (P10, D27, E-F23, E-F27, E-F28, E-F37): la regola **Persone**. Si scelgono dalla porta [PeopleChoices] (le persone con un nome, le piu' frequenti prima),
// "tutte" o "una qualunque"; le persone della regola si mostrano per nome (una persona perduta o nascosta non ha nome: l'editor lo dice, senza nome). Dove i volti
// non ci sono (E-F37) la sezione lo dice e la regola resta com'e': l'album non cambia. Dalla pagina persona l'editor parte con la regola gia' messa
// ([SmartEditorMode.Create.startWith]) e senza passare dall'interprete (il titolo e' un nome, non un'idea di regole).

sealed interface SmartEditorMode {
  /**
   * Un album nuovo: [title] e' il nome scelto, da cui l'interprete propone le regole. Con [startWith] (dalla pagina persona, "Crea album smart") le regole partono
   * dalla sola regola delle persone e l'interprete non si chiede.
   */
  data class Create(val title: String, val startWith: PeopleRule? = null) : SmartEditorMode

  /** Le regole di uno smart che c'e'. */
  data class Edit(val album: AlbumId) : SmartEditorMode
}

/** Perche' il salvataggio non e' andato (la schermata lo dice sopra il bottone). */
enum class EditorProblem { INVALID_TITLE, RULES_TOO_LONG, NOT_ALLOWED, NOT_FOUND, BLOCKED, UNAVAILABLE, PREVIEW_FAILED }

data class SmartEditorUi(
  val title: String,
  /** La fase: interpretando, la proposta, regole troppo larghe, descrizione senza motore. */
  val state: SmartEditorState,
  /** Le regole di adesso (null solo mentre si interpreta). */
  val rules: SmartRules? = null,
  /** Gli omonimi del luogo (E-AL2). */
  val alternatives: List<PlaceAlternative> = emptyList(),
  /** Le serie di giorni del raffinamento (E-AL12): quelle nel periodo sono scelte. */
  val series: List<LocalDayRange> = emptyList(),
  val notes: Set<ProposalNote> = emptySet(),
  val preview: SmartPreview? = null,
  val previewing: Boolean = false,
  val saving: Boolean = false,
  val problem: EditorProblem? = null,
  /** Dopo il salvataggio: l'album creato o cambiato. */
  val savedAlbum: AlbumId? = null,
  val canSave: Boolean = false,
  val creating: Boolean = true,
  /** Fase 8: vero se qui i volti ci sono (E-F37); falso = "Le persone non sono attive su questo dispositivo". */
  val peopleOn: Boolean = true,
  /** Le persone che si possono scegliere (con un nome, non nascoste), le piu' frequenti prima. */
  val peopleChoices: List<PersonChoice> = emptyList(),
  /** I nomi delle persone della regola e quelle perse (E-F27). */
  val peopleInfo: PeopleRuleInfo = PeopleRuleInfo.EMPTY,
)

class SmartEditorController(
  private val mode: SmartEditorMode,
  private val interpreter: SmartNameInterpreter,
  private val engine: SmartAlbumEngine,
  private val albums: Albums,
  private val clock: AlbumScreensClock,
  private val scope: CoroutineScope,
  /** Fase 8: le persone che si scelgono; di serie nessuna (le persone non sono attive). */
  private val people: PeopleChoices = NoPeopleChoices,
) {
  private val titleOf: String = when (mode) {
    is SmartEditorMode.Create -> mode.title
    is SmartEditorMode.Edit -> albums.all.value.byId(mode.album)?.title.orEmpty()
  }

  private val _ui = MutableStateFlow(SmartEditorUi(titleOf, SmartEditorState.Interpreting(titleOf), creating = mode is SmartEditorMode.Create))
  val ui: StateFlow<SmartEditorUi> = _ui.asStateFlow()

  private var previewJob: Job? = null
  private var started = false

  /** Parte: interpreta il nome (nuovo) o legge le regole dell'album (esistente). Idempotente. */
  fun start() {
    if (started) return
    started = true
    scope.launch {
      when (mode) {
        is SmartEditorMode.Create -> if (mode.startWith != null) {
          _ui.update { it.copy(rules = SmartRules(null, null, null, people = mode.startWith)) }
        } else {
          // AS-7: un errore del motore (la ricerca, il catalogo) non deve arrivare al gestore dell'ambito e chiudere l'app: l'editor parte con regole vuote.
          val proposal = try {
            interpreter.propose(mode.title, clock.today())
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            null
          }
          if (proposal == null) {
            _ui.update { it.copy(rules = SmartRules(null, null, null), problem = EditorProblem.PREVIEW_FAILED, preview = EMPTY_PREVIEW, previewing = false) }
            _ui.update { compose(it) }
            return@launch
          }
          _ui.update { it.copy(rules = proposal.rules, alternatives = proposal.alternatives, series = proposal.periodSeries, notes = proposal.notes) }
        }
        is SmartEditorMode.Edit -> {
          val smart = albums.all.value.byId(mode.album) as? Album.Smart
          if (smart == null) {
            _ui.update { it.copy(problem = EditorProblem.NOT_FOUND, state = SmartEditorState.NoSearch(SmartRules(null, null, null), EMPTY_PREVIEW)) }
            return@launch
          }
          val ranges = (smart.rules.period as? SmartPeriod.Ranges)?.ranges.orEmpty()
          _ui.update { it.copy(rules = smart.rules, series = ranges) }
        }
      }
      refreshPeopleInfo()
      refreshPreview()
    }
    scope.launch { watchPeople() }
  }

  // --- Le persone (Fase 8) -----------------------------------------------------------------------------------------------

  /** Segue la porta: se i volti si accendono o si spengono, la sezione si rifa'; le persone da scegliere si rileggono. */
  private suspend fun watchPeople() {
    people.available.collect { on ->
      val choices = if (on) guarded { people.choices() }.orEmpty() else emptyList()
      _ui.update { it.copy(peopleOn = on, peopleChoices = choices) }
      refreshPeopleInfo()
    }
  }

  private suspend fun refreshPeopleInfo() {
    val rule = _ui.value.rules?.people
    val info = if (rule == null) PeopleRuleInfo.EMPTY else guarded { people.inspect(rule.ids) } ?: PeopleRuleInfo.EMPTY
    _ui.update { it.copy(peopleInfo = info) }
  }

  /** Un errore della porta delle persone non deve arrivare al gestore dell'ambito (AS-7): vale "non so". */
  private suspend fun <T> guarded(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  // --- Cambiare le regole -------------------------------------------------------------------------------------------------

  private fun change(block: (SmartRules) -> SmartRules) {
    val current = _ui.value.rules ?: return
    val next = block(current)
    if (next == current) return
    _ui.update { it.copy(rules = next, problem = null, savedAlbum = null) }
    scope.launch { refreshPreview() }
  }

  /** Tiene solo le serie in [keep] (E-AL12): non se ne toglie l'ultima, il periodo si toglie con [removePeriod]. */
  fun setSeries(keep: Set<LocalDayRange>) {
    val all = _ui.value.series.filter { it in keep }.sortedBy { it.from }
    if (all.isEmpty()) return
    change { it.copy(period = SmartPeriod.Ranges(all)) }
  }

  /** Un periodo scritto a mano: da [from] a [to] compresi. Rovesciato non vale. */
  fun setPeriod(range: LocalDayRange) {
    _ui.update { it.copy(series = listOf(range)) }
    change { it.copy(period = SmartPeriod.Ranges(listOf(range))) }
  }

  fun removePeriod() {
    _ui.update { it.copy(series = emptyList()) }
    change { it.copy(period = null) }
  }

  /** Sceglie fra gli omonimi (E-AL2). */
  fun choosePlace(alternative: PlaceAlternative) = change { it.copy(place = alternative.rule) }

  fun setRadius(km: Int) = change { rules ->
    val place = rules.place ?: return@change rules
    if (place.scope != PlaceScope.AROUND) return@change rules
    rules.copy(place = place.copy(radiusKm = km.coerceIn(SmartLimits.MIN_RADIUS_KM, SmartLimits.MAX_RADIUS_KM)))
  }

  fun removePlace() = change { it.copy(place = null) }

  /**
   * Aggiunge [ids] alla regola delle persone (E-F36 a parte, al piu' [PeopleRule.MAX_PEOPLE]). Con una sola persona il modo non conta: chi ne aggiunge una seconda
   * vuole di solito "tutte" (come "Marco e Anna"), e il modo si cambia subito dopo.
   */
  fun addPeople(ids: List<PersonId>) {
    change { rules ->
      val current = rules.people
      val all = (current?.ids.orEmpty() + ids).distinct().take(PeopleRule.MAX_PEOPLE)
      if (all.isEmpty()) {
        rules
      } else {
        val mode = if (current != null && current.ids.size > 1) current.mode else PeopleMode.ALL
        rules.copy(people = PeopleRule(all, mode))
      }
    }
    scope.launch { refreshPeopleInfo() }
  }

  /** Tocca una persona del foglio di scelta: la mette se non c'e', la toglie se c'e'. */
  fun togglePerson(id: PersonId) {
    if (_ui.value.rules?.people?.ids?.contains(id) == true) removePerson(id) else addPeople(listOf(id))
  }

  fun removePerson(id: PersonId) {
    change { rules ->
      val current = rules.people ?: return@change rules
      val rest = current.ids - id
      rules.copy(people = if (rest.isEmpty()) null else PeopleRule(rest, current.mode))
    }
    scope.launch { refreshPeopleInfo() }
  }

  /** "Tutte" o "una qualunque": cambia solo il modo, e solo con due persone o piu'. */
  fun setPeopleMode(mode: PeopleMode) = change { rules ->
    val current = rules.people ?: return@change rules
    if (current.ids.size < 2) rules else rules.copy(people = current.copy(mode = mode))
  }

  fun removePeople() {
    change { it.copy(people = null) }
    scope.launch { refreshPeopleInfo() }
  }

  fun setText(text: String) = change { it.copy(text = text.takeIf { value -> value.isNotBlank() }) }

  fun addExamples(media: List<MediaId>) = change { rules ->
    val all = (rules.examples + media).distinct().take(SmartLimits.MAX_EXAMPLES)
    rules.copy(examples = all)
  }

  fun removeExample(media: MediaId) = change { it.copy(examples = it.examples - media) }

  /** Accende o spegne una sorgente; ne resta sempre almeno una. */
  fun toggleSource(source: SmartSource) = change { rules ->
    val next = if (source in rules.sources) rules.sources - source else rules.sources + source
    if (next.isEmpty()) rules else rules.copy(sources = next)
  }

  fun toggleType(type: SmartMediaType) = change { rules ->
    val next = if (type in rules.types) rules.types - type else rules.types + type
    if (next.isEmpty()) rules else rules.copy(types = next)
  }

  // --- L'anteprima ----------------------------------------------------------------------------------------------------------

  private suspend fun refreshPreview() {
    previewJob?.cancel()
    val rules = _ui.value.rules ?: return
    val job = scope.launch {
      _ui.update { it.copy(previewing = true, canSave = false) }
      // AS-7: l'anteprima e' un aiuto, non un passaggio obbligato. Se il motore sbaglia (il catalogo non risponde, la ricerca cade) l'editor lo dice e
      // lascia salvare le regole: prima l'eccezione arrivava al gestore dell'ambito e l'app si chiudeva.
      val preview = try {
        engine.preview(rules)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      // Un'anteprima vecchia non scavalca le regole piu' nuove.
      _ui.update {
        when {
          it.rules != rules -> it
          preview == null -> compose(it.copy(preview = EMPTY_PREVIEW, previewing = false, problem = EditorProblem.PREVIEW_FAILED))
          else -> compose(it.copy(preview = preview, previewing = false))
        }
      }
    }
    previewJob = job
  }

  private fun compose(ui: SmartEditorUi): SmartEditorUi {
    val rules = ui.rules ?: return ui
    val preview = ui.preview ?: return ui.copy(state = SmartEditorState.Interpreting(ui.title), canSave = false)
    val usesSearch = !rules.text.isNullOrBlank() || rules.examples.isNotEmpty()
    val state = when {
      preview.tooBroad -> SmartEditorState.TooBroad(rules, preview, hintsFor(rules))
      usesSearch && !preview.searchUsed -> SmartEditorState.NoSearch(rules, preview)
      else -> SmartEditorState.Proposal(rules, ui.alternatives, ui.series, preview, ui.notes)
    }
    val hasRule = rules.period != null || rules.place != null || rules.people != null || !rules.text.isNullOrBlank() || rules.examples.isNotEmpty()
    return ui.copy(state = state, canSave = hasRule && !ui.saving && !ui.previewing)
  }

  /** Come restringere regole troppo larghe (E-AL6): il periodo, il raggio, o un luogo. */
  private fun hintsFor(rules: SmartRules): Set<BroadHint> = buildSet {
    if (rules.period != null) add(BroadHint.NARROW_PERIOD)
    val place = rules.place
    if (place != null && place.scope == PlaceScope.AROUND && place.radiusKm > SmartLimits.MIN_RADIUS_KM) add(BroadHint.SMALLER_RADIUS)
    if (place == null) add(BroadHint.ADD_PLACE)
    if (isEmpty()) add(BroadHint.NARROW_PERIOD)
  }

  // --- Salvare --------------------------------------------------------------------------------------------------------------

  /** "Crea album" / "Salva le regole": scrive solo nei dati personali (D3), mai un file. */
  fun save() {
    val current = _ui.value
    val rules = current.rules ?: return
    if (!current.canSave || current.saving) return
    _ui.update { it.copy(saving = true, problem = null, canSave = false) }
    scope.launch {
      val result: Pair<AlbumId?, EditorProblem?> = when (mode) {
        is SmartEditorMode.Create -> when (val out = albums.create(mode.title, AlbumKind.SMART, rules)) {
          is CreateOutcome.Created -> out.id to null
          is CreateOutcome.Invalid -> null to EditorProblem.INVALID_TITLE
          CreateOutcome.RulesTooLong -> null to EditorProblem.RULES_TOO_LONG
          CreateOutcome.Unavailable -> null to EditorProblem.UNAVAILABLE
        }
        is SmartEditorMode.Edit -> when (val out = albums.setRules(mode.album, rules)) {
          EditOutcome.Done -> mode.album to null
          is EditOutcome.Invalid -> null to EditorProblem.RULES_TOO_LONG
          is EditOutcome.NotAllowed -> null to EditorProblem.NOT_ALLOWED
          EditOutcome.NotFound -> null to EditorProblem.NOT_FOUND
          is EditOutcome.Blocked -> null to EditorProblem.BLOCKED
        }
      }
      _ui.update { compose(it.copy(saving = false, savedAlbum = result.first, problem = result.second)) }
    }
  }

  private companion object {
    val EMPTY_PREVIEW = SmartPreview(0, 0, tooBroad = false, sample = emptyList(), searchUsed = false)
  }
}

/** Il luogo di una proposta senza modificarlo: la schermata ne mostra l'etichetta ("Oslo") e il raggio. */
val PlaceRule.hasRadius: Boolean get() = scope == PlaceScope.AROUND
