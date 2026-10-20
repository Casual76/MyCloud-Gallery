package dev.pampa.mycloudgallery.feature.backup.page

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedEntryCard
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksEntryCard
import dev.pampa.mycloudgallery.feature.backup.first.FirstBackupSheet
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSectionTitle
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.sourceName

// La pagina Backup (docs/fase5-progetto.md §1.5, §2.6, D16, D30; G2): il problema piu' grave in cima con la sua azione (o lo stato, se
// va tutto bene), "Fai ora" e "Ferma", una riga per sorgente, gli altri problemi con la loro azione, la cartella del dispositivo e le
// impostazioni. Il ViewModel non decide niente: legge lo stato e inoltra i tocchi. Solo componenti G2 (D30).

/** Le uscite della pagina: tutte partono da un tocco e le esegue `:app` (o il ViewModel, per il foglio). */
@Immutable
internal class BackupPageActions(
  val perform: (BackupAction) -> Unit = {},
  val backupNow: () -> Unit = {},
  val stop: () -> Unit = {},
  val open: (BackupScreen) -> Unit = {},
  val openSheet: () -> Unit = {},
  val closeSheet: () -> Unit = {},
  val startFirstBackup: (tonight: Boolean) -> Unit = {},
)

/**
 * La pagina Backup con il suo ViewModel. [openFirstBackup] apre il foglio del primo backup appena si entra (la rotta `backup/first`);
 * [onBack] e' null nel pannello di dettaglio del tablet; [hostPadding] e' lo spazio della pillola delle schede in quel caso.
 */
@Composable
fun BackupPageRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  openFirstBackup: Boolean = false,
  viewModel: BackupPageViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(openFirstBackup) { if (openFirstBackup) viewModel.openFirstBackup() }
  // BK-3, BK-13: a ogni ritorno alla pagina (dalla Diagnostica, dalla password, dal dialogo dei permessi) lo stato si ricalcola.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
  val actions = remember(viewModel) {
    BackupPageActions(
      perform = viewModel::perform,
      backupNow = viewModel::backupNow,
      stop = viewModel::stop,
      open = viewModel::open,
      openSheet = viewModel::openFirstBackup,
      closeSheet = viewModel::closeFirstBackup,
      startFirstBackup = viewModel::startFirstBackup,
    )
  }
  BackupPageScreen(state = state, actions = actions, modifier = modifier, onBack = onBack, hostPadding = hostPadding, backdropOut = backdropOut)
}

/** La pagina per uno [state]: nessun effetto. La provano i test e gli screenshot. */
@Composable
internal fun BackupPageScreen(
  state: BackupPageUi,
  actions: BackupPageActions,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  val settingsLabel = stringResource(R.string.backup_settings_open)
  BackupPage(
    label = BackupLabel(title = stringResource(R.string.backup_title), subtitle = null),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    commands = settingsCommands(settingsLabel) { actions.open(BackupScreen.SETTINGS) },
    hostPadding = hostPadding,
  ) { insets ->
    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = BackupDimens.PageMaxWidth).fillMaxWidth(),
        contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom),
        verticalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap),
      ) {
        item(key = "status") { BackupStatusCard(status = state.status, actions = actions, onOpenSummary = actions.openSheet) }
        // 5B (P14, D20): "Da decidere", solo se c'e' qualcosa da decidere.
        if (state.pendingTasks > 0) {
          item(key = "pending-tasks") { PendingTasksEntryCard(count = state.pendingTasks, onOpen = { actions.open(BackupScreen.PENDING_TASKS) }) }
        }
        // 5B (IN-7): "Ricevuti in coda", solo se un'altra app ha mandato qualcosa che non e' ancora sul NAS.
        if (state.received.total > 0) {
          item(key = "received") { ReceivedEntryCard(counts = state.received, onOpen = { actions.open(BackupScreen.RECEIVED) }) }
        }
        otherProblems(state, actions)
        sourcesSection(state.sources, sourcesHalt(state.status))
        deviceSection(state, actions)
      }
    }
  }
  val ready = state.status as? BackupStatus.FirstBackupReady
  FirstBackupSheet(
    visible = state.sheetOpen && ready != null,
    summary = (ready ?: FallbackReady).summary,
    onTonight = { actions.startFirstBackup(true) },
    onNow = { actions.startFirstBackup(false) },
    onDismissRequest = actions.closeSheet,
  )
}

private val FallbackReady = BackupStatus.FirstBackupReady(dev.pampa.mycloudgallery.core.backup.contract.BackupSummary.EMPTY)

@Composable
private fun settingsCommands(description: String, onClick: () -> Unit): @Composable RowScope.() -> Unit = {
  GalleryChromeIconButton(icon = Icons.Rounded.Tune, contentDescription = description, onClick = onClick)
}

// --- Gli altri problemi ----------------------------------------------------------------------------------------------------------

/**
 * I problemi delle sorgenti che non sono quello in cima: sommati per codice e nell'ordine del §2.6 (il piu' grave per primo). Se lo stato
 * e' gia' quel problema non si ripete.
 */
internal fun otherProblems(state: BackupPageUi): List<Pair<BackupProblemCode, Int>> {
  val shown = (state.status as? BackupStatus.Problem)?.code ?: if (state.status == BackupStatus.TestOnly) BackupProblemCode.TEST_ONLY else null
  return state.sources
    .flatMap { it.problems.entries }
    .groupBy({ it.key }, { it.value })
    .map { (code, counts) -> code to counts.sum() }
    .filter { (code, _) -> code != shown }
    .sortedBy { (code, _) -> code.ordinal }
}

private fun LazyListScope.otherProblems(state: BackupPageUi, actions: BackupPageActions) {
  val problems = otherProblems(state)
  if (problems.isEmpty()) return
  item(key = "problems-title") { BackupSectionTitle(stringResource(R.string.backup_section_problems)) }
  problems.forEach { (code, count) ->
    item(key = "problem-${code.code}") { ProblemCard(code = code, count = count, actions = actions) }
  }
}

// --- Le sorgenti -----------------------------------------------------------------------------------------------------------------

/**
 * D-4 (prove sul campo): perche' le sorgenti non si salvano adesso, anche se hanno media da salvare; null = il backup puo' lavorare. Con il
 * backup "in prova" (D12) le righe dicevano "In corso · 0 al sicuro · N in coda" e sembrava un backup al lavoro, mentre non partira' mai finche'
 * la share non e' di prova. Il perche' lungo lo dice la scheda in cima; la riga dice che e' fermo, con poche parole.
 */
internal enum class SourcesHalt(@StringRes val pill: Int, @StringRes val reason: Int) {
  /** D12: il backup scrive solo nelle share di prova. */
  TEST_ONLY(R.string.backup_source_state_halted, R.string.backup_source_halt_test_only),

  /** Manca un passo della configurazione (area privata, sorgenti, cartella del dispositivo, permesso). */
  NOT_CONFIGURED(R.string.backup_source_state_halted, R.string.backup_source_halt_not_configured),

  /** Il riepilogo del primo backup aspetta "Stanotte" o "Inizia ora" (§2.11). */
  FIRST_BACKUP(R.string.backup_source_state_not_started, R.string.backup_source_halt_first_backup),

  /** "Ferma" dell'utente (R4): una pausa che dura finche' non tocca "Riprendi". */
  PAUSED(R.string.backup_source_state_paused, R.string.backup_source_halt_paused),
}

/** Lo stato del backup che ferma tutte le sorgenti, o null. Android che ferma un lavoro (`StopBy.SYSTEM`) non e' una fermata: riprende da solo. */
internal fun sourcesHalt(status: BackupStatus): SourcesHalt? = when (status) {
  BackupStatus.TestOnly -> SourcesHalt.TEST_ONLY
  is BackupStatus.NotConfigured -> SourcesHalt.NOT_CONFIGURED
  is BackupStatus.FirstBackupReady -> SourcesHalt.FIRST_BACKUP
  is BackupStatus.Stopped -> if (status.by == StopBy.USER) SourcesHalt.PAUSED else null
  else -> null
}

private fun LazyListScope.sourcesSection(sources: List<SourceStatus>, halt: SourcesHalt?) {
  item(key = "sources-title") { BackupSectionTitle(stringResource(R.string.backup_section_sources)) }
  if (sources.isEmpty()) {
    item(key = "sources-empty") { SourcesEmpty() }
    return
  }
  item(key = "sources") {
    FluidListGroup {
      sources.forEachIndexed { index, source ->
        if (index > 0) FluidListDivider()
        SourceRow(source, halt)
      }
    }
  }
}

@Composable
private fun SourcesEmpty() {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_sources_empty_title),
      subtitle = stringResource(R.string.backup_sources_empty_detail),
      tone = FluidTone.Neutral,
      leading = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
      disclosure = false,
    )
  }
}

/**
 * Una sorgente: il nome, quanti media e quanti al sicuro, quanti in coda e con problemi, e una pillola con lo stato. Con il backup fermo
 * ([halt], D-4) la pillola non dice "In corso" e i media che mancano sono "da salvare", non "in coda", con il perche' in poche parole.
 */
@Composable
private fun SourceRow(source: SourceStatus, halt: SourcesHalt?) {
  val name = sourceName(source.source)
  val problems = source.problems.values.sum()
  val state = sourceState(source, problems, halt)
  val line = sourceLine(source, problems, halt.takeIf { state == SourceState.Halted })
  val pill = stringResource(if (state == SourceState.Halted && halt != null) halt.pill else state.label)
  FluidListRow(
    title = name,
    subtitle = line,
    tone = state.tone,
    leading = {
      Icon(
        imageVector = when (source.source) {
          BackupSource.Camera -> Icons.Rounded.PhotoCamera
          BackupSource.Screenshots -> Icons.Rounded.Image
          is BackupSource.Folder -> Icons.Rounded.Folder
        },
        contentDescription = null,
      )
    },
    labels = { FluidStatusBadge(label = pill, tone = state.tone) },
    disclosure = false,
    titleMaxLines = 2,
    // Per TalkBack la riga e' una frase sola: nome, stato e numeri.
    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$name, $pill. $line" },
  )
}

internal enum class SourceState(val label: Int, val tone: FluidTone) {
  Safe(R.string.backup_source_state_safe, FluidTone.Success),
  Working(R.string.backup_source_state_working, FluidTone.Info),

  /** D-4: ci sono media da salvare ma il backup e' fermo (la pillola e' quella di [SourcesHalt]). */
  Halted(R.string.backup_source_state_halted, FluidTone.Neutral),
  Problem(R.string.backup_source_state_problem, FluidTone.Warning),
  Off(R.string.backup_source_state_off, FluidTone.Neutral),
}

/** Lo stato di una riga: "In corso" solo se il backup puo' lavorare (D-4); i problemi e "Al sicuro" restano quello che sono. */
internal fun sourceState(source: SourceStatus, problems: Int, halt: SourcesHalt?): SourceState = when {
  !source.active -> SourceState.Off
  problems > 0 -> SourceState.Problem
  source.queued > 0 || source.safe < source.media -> if (halt != null) SourceState.Halted else SourceState.Working
  else -> SourceState.Safe
}

@Composable
private fun sourceLine(source: SourceStatus, problems: Int, halt: SourcesHalt?): String {
  val parts = ArrayList<String>(5)
  parts += pluralStringResource(R.plurals.backup_source_media, source.media, BackupText.count(source.media))
  if (source.active) {
    parts += stringResource(R.string.backup_source_safe, BackupText.count(source.safe))
    if (source.queued > 0) {
      parts += stringResource(if (halt != null) R.string.backup_source_to_save else R.string.backup_source_queued, BackupText.count(source.queued))
    }
    if (problems > 0) parts += pluralStringResource(R.plurals.backup_source_problems, problems, BackupText.count(problems))
    if (halt != null) parts += stringResource(halt.reason)
  } else {
    parts += stringResource(R.string.backup_source_not_saved)
  }
  return parts.joinToString(" · ")
}

// --- La cartella del dispositivo e le impostazioni -------------------------------------------------------------------------------

private fun LazyListScope.deviceSection(state: BackupPageUi, actions: BackupPageActions) {
  item(key = "device-title") { BackupSectionTitle(stringResource(R.string.backup_section_device)) }
  item(key = "device") {
    FluidListGroup {
      val folder = state.deviceFolder
      FluidListRow(
        title = stringResource(R.string.backup_device_folder_title),
        subtitle = if (folder != null) {
          stringResource(R.string.backup_device_folder_chosen, folder.name)
        } else {
          stringResource(R.string.backup_device_folder_not_chosen)
        },
        tone = FluidTone.Info,
        leading = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
        // La cartella si sceglie una volta (D7): scelta, non si tocca piu'. D-9 (prove sul campo): si sceglie **con** il primo backup, dal
        // suo riepilogo ("Stanotte" o "Inizia ora"), mai da sola. Prima la riga apriva la scelta della cartella e, scelta li', il primo backup
        // partiva al giro delle regole senza che l'utente avesse visto la stima di tempo, traffico e batteria (§2.11).
        disclosure = folder == null && state.status is BackupStatus.FirstBackupReady,
        onClick = if (folder == null && state.status is BackupStatus.FirstBackupReady) actions.openSheet else null,
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.backup_settings_title),
        subtitle = stringResource(R.string.backup_settings_detail),
        tone = FluidTone.Neutral,
        leading = { Icon(imageVector = Icons.Rounded.Tune, contentDescription = null) },
        onClick = { actions.open(BackupScreen.SETTINGS) },
      )
    }
  }
}
