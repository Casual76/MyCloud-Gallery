package dev.pampa.mycloudgallery.feature.albums.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens

// L'EDITOR DELLE REGOLE (Fase 7, P10; docs/fase7-progetto.md §1.5 `SmartEditorState`, D13-D16, §2.2, E-AL6, E-AL9, E-AL12). Una pagina di sezioni, ognuna una regola
// che si legge e si cambia: Periodo, Luogo, Descrizione, Foto d'esempio, Da dove, Che cosa; poi l'anteprima. Il bottone che crea sta in una barra sotto
// la pagina (nel layout, mai sopra le sezioni) e dice quante foto entrano. Se le regole sono troppo larghe lo dice una scheda in evidenza in cima
// all'anteprima; se la descrizione non ha il motore lo dice la sezione Descrizione. Solo componenti G2.

/** Cosa l'editor chiede fuori da se'. */
interface SmartEditorHandlers {
  fun onBack()

  fun onSeries(keep: Set<LocalDayRange>)

  fun onPeriod(range: LocalDayRange)

  fun onRemovePeriod()

  fun onPlace(alternative: PlaceAlternative)

  fun onRadius(km: Int)

  fun onRemovePlace()

  /** Fase 8: tocca una persona nel foglio di scelta (la mette o la toglie). */
  fun onTogglePerson(id: PersonId)

  /** Fase 8: toglie una persona dalla regola (la riga nella sezione). */
  fun onRemovePerson(id: PersonId)

  /** Fase 8: "tutte" o "una qualunque". */
  fun onPeopleMode(mode: PeopleMode)

  fun onRemovePeople()

  fun onText(text: String)

  fun onAddExamples()

  fun onRemoveExample(media: MediaId)

  fun onSource(source: SmartSource)

  fun onType(type: SmartMediaType)

  fun onSave()
}

object NoSmartEditorHandlers : SmartEditorHandlers {
  override fun onBack() = Unit

  override fun onSeries(keep: Set<LocalDayRange>) = Unit

  override fun onPeriod(range: LocalDayRange) = Unit

  override fun onRemovePeriod() = Unit

  override fun onPlace(alternative: PlaceAlternative) = Unit

  override fun onRadius(km: Int) = Unit

  override fun onRemovePlace() = Unit

  override fun onTogglePerson(id: PersonId) = Unit

  override fun onRemovePerson(id: PersonId) = Unit

  override fun onPeopleMode(mode: PeopleMode) = Unit

  override fun onRemovePeople() = Unit

  override fun onText(text: String) = Unit

  override fun onAddExamples() = Unit

  override fun onRemoveExample(media: MediaId) = Unit

  override fun onSource(source: SmartSource) = Unit

  override fun onType(type: SmartMediaType) = Unit

  override fun onSave() = Unit
}

@Composable
fun SmartEditorScreen(
  ui: SmartEditorUi,
  thumbnails: AlbumThumbnails,
  handlers: SmartEditorHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val rules = ui.rules
  // Le miniature delle foto d'esempio e dell'anteprima, in una finestra sola.
  val requests = remember(rules?.examples, ui.preview?.sample) {
    (rules?.examples.orEmpty() + ui.preview?.sample.orEmpty()).mapNotNull { thumbnails.refs.request(it) }
  }
  LaunchedEffect(requests, thumbnails) { thumbnails.loader.window(requests, emptyList()) }
  var datesFor by remember { mutableStateOf(false) }
  var picking by remember { mutableStateOf(false) }
  Column(modifier = modifier.fillMaxSize()) {
    FluidScreen(
      title = stringResource(if (ui.creating) R.string.editor_title_create else R.string.editor_title_edit),
      subtitle = ui.title,
      modifier = Modifier.weight(1f),
      onBack = handlers::onBack,
      listState = listState,
      contentMaxWidth = AlbumDimens.SheetWidth,
    ) {
      if (rules == null) {
        item(key = "interpreting") { Text(stringResource(R.string.editor_interpreting, ui.title), color = MaterialTheme.colorScheme.onSurfaceVariant) }
      } else {
        editorSections(ui, rules, thumbnails, handlers, onEditDates = { datesFor = true }, onPickPeople = { picking = true })
      }
    }
    if (rules != null) SaveBar(ui, handlers)
  }
  DatesSheet(visible = datesFor, current = ui.series.firstOrNull() ?: (rules?.period as? SmartPeriod.Ranges)?.ranges?.firstOrNull(), onApply = {
    handlers.onPeriod(it)
    datesFor = false
  }, onDismiss = { datesFor = false })
  PeoplePickerSheet(
    visible = picking, choices = ui.peopleChoices, chosen = rules?.people?.ids.orEmpty().toSet(), onToggle = handlers::onTogglePerson, onDismiss = { picking = false },
  )
}

private fun LazyListScope.editorSections(
  ui: SmartEditorUi,
  rules: SmartRules,
  thumbnails: AlbumThumbnails,
  handlers: SmartEditorHandlers,
  onEditDates: () -> Unit,
  onPickPeople: () -> Unit,
) {
  item(key = "intro") { Text(stringResource(R.string.editor_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  (ui.state as? SmartEditorState.TooBroad)?.let { broad -> item(key = "broad") { BroadCard(broad) } }
  item(key = "period") { PeriodSection(rules.period, ui.series, handlers, onEditDates) }
  item(key = "place") { PlaceSection(rules.place, ui.alternatives, handlers) }
  if (showsPeopleSection(ui, rules.people)) item(key = "people") { PeopleSection(ui, rules.people, handlers, onPickPeople) }
  item(key = "text") { TextSection(rules.text.orEmpty(), noSearch = ui.state is SmartEditorState.NoSearch || ProposalNote.NO_SEARCH in ui.notes, handlers) }
  item(key = "examples") { ExamplesSection(rules.examples, thumbnails, handlers) }
  item(key = "sources") { SourcesSection(rules.sources, handlers) }
  item(key = "types") { TypesSection(rules.types, handlers) }
  item(key = "preview") { PreviewSection(ui, thumbnails) }
  ui.problem?.let { problem -> item(key = "problem") { ProblemMessage(problem) } }
}

@Composable
private fun ProblemMessage(problem: EditorProblem) {
  val text = when (problem) {
    EditorProblem.INVALID_TITLE -> R.string.editor_problem_title
    EditorProblem.RULES_TOO_LONG -> R.string.editor_problem_too_long
    EditorProblem.NOT_ALLOWED -> R.string.editor_problem_not_allowed
    EditorProblem.NOT_FOUND -> R.string.editor_problem_not_found
    EditorProblem.BLOCKED -> R.string.editor_problem_blocked
    EditorProblem.UNAVAILABLE -> R.string.editor_problem_unavailable
    EditorProblem.PREVIEW_FAILED -> R.string.editor_problem_preview_failed
  }
  FluidInlineMessage(message = stringResource(text), title = stringResource(R.string.editor_title_create), tone = FluidTone.Danger)
}

/** La barra sotto la pagina: quante foto entrano e il bottone che crea (o salva). Sta nel layout: la pagina finisce dove comincia lei. */
@Composable
private fun SaveBar(ui: SmartEditorUi, handlers: SmartEditorHandlers) {
  val resources = LocalContext.current.resources
  val preview = ui.preview
  Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
    Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AlbumDimens.Inset, vertical = AlbumDimens.BlockGap),
      verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap),
    ) {
      val summary = when {
        ui.previewing || preview == null -> stringResource(R.string.editor_preview_working)
        // Fase 8 (E-F37): senza i volti qui l'anteprima e' vuota per un motivo preciso, non perche' le regole non trovino foto.
        !preview.peopleAvailable -> stringResource(R.string.editor_preview_people_off)
        preview.confident + preview.suggested == 0 -> stringResource(R.string.editor_preview_none)
        else -> resources.getQuantityString(R.plurals.editor_preview_confident, preview.confident, preview.confident) + " · " +
          resources.getQuantityString(R.plurals.editor_preview_suggested, preview.suggested, preview.suggested)
      }
      Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (!ui.canSave && !ui.saving && !ui.previewing && ui.rules != null && ui.state !is SmartEditorState.Interpreting) {
        val empty = ui.rules.period == null && ui.rules.place == null && ui.rules.people == null && ui.rules.text.isNullOrBlank() && ui.rules.examples.isEmpty()
        if (empty) Text(stringResource(R.string.editor_no_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
      FluidButton(
        text = stringResource(
          when {
            ui.saving -> R.string.editor_saving
            ui.creating -> R.string.editor_save_create
            else -> R.string.editor_save_edit
          },
        ),
        onClick = handlers::onSave,
        enabled = ui.canSave,
        loading = ui.saving,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
  }
}

@Composable
internal fun SectionTitle(title: String) {
  GallerySectionHeader(title = title)
}
