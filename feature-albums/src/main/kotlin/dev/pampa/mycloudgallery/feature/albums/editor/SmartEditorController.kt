package dev.pampa.mycloudgallery.feature.albums.editor

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
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
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.BroadHint
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
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

sealed interface SmartEditorMode {
  /** Un album nuovo: [title] e' il nome scelto, da cui l'interprete propone le regole. */
  data class Create(val title: String) : SmartEditorMode

  /** Le regole di uno smart che c'e'. */
  data class Edit(val album: AlbumId) : SmartEditorMode
}

/** Perche' il salvataggio non e' andato (la schermata lo dice sopra il bottone). */
enum class EditorProblem { INVALID_TITLE, RULES_TOO_LONG, NOT_ALLOWED, NOT_FOUND, BLOCKED, UNAVAILABLE }

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
)

class SmartEditorController(
  private val mode: SmartEditorMode,
  private val interpreter: SmartNameInterpreter,
  private val engine: SmartAlbumEngine,
  private val albums: Albums,
  private val clock: AlbumScreensClock,
  private val scope: CoroutineScope,
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
        is SmartEditorMode.Create -> {
          val proposal = interpreter.propose(mode.title, clock.today())
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
      refreshPreview()
    }
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
      val preview = engine.preview(rules)
      // Un'anteprima vecchia non scavalca le regole piu' nuove.
      _ui.update { if (it.rules != rules) it else compose(it.copy(preview = preview, previewing = false)) }
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
    val hasRule = rules.period != null || rules.place != null || !rules.text.isNullOrBlank() || rules.examples.isNotEmpty()
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
