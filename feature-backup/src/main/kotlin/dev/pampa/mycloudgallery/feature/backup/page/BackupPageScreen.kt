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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.first.FirstBackupSheet
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCoverWindow
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSectionTitle
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers

// La pagina Backup (docs/fase5-progetto.md §1.5, §2.6, D16, D30; G2; I3 della fase I). In cima lo stato in un colpo d'occhio ([BackupHero]: l'anello, il
// segno, un'etichetta, al piu' una riga, l'azione con l'icona); poi cio' che e' da controllare (righe con la piastrella del problema: il perche' e il
// rimedio dietro un tocco), le copertine delle sorgenti ([SourceGrid]: la foto piu' recente, la barra, il numero che conta) e le righe del NAS. Il
// motivo per cui le sorgenti sono ferme si dice **una volta**, in cima: sulle copertine c'e' solo il segno. Il ViewModel non decide niente.

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
  // I3: le copertine delle sorgenti (la Libreria visibile e la cache delle miniature); senza la porta, le piastrelle.
  CompositionLocalProvider(LocalBackupCovers provides viewModel.covers) {
    BackupPageScreen(state = state, actions = actions, modifier = modifier, onBack = onBack, hostPadding = hostPadding, backdropOut = backdropOut)
  }
}

/** La pagina per uno [state]: nessun effetto (se non la preparazione delle copertine, quando ci sono). La provano i test e gli screenshot. */
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
  val sourceList = remember(state.sources) { state.sources.map { it.source } }
  BackupCoverWindow(LocalBackupCovers.current, sourceList)
  val hero = heroModel(state.status, actions)
  val share = remember(state.status, state.sources) { SafeShare.of(state.status, state.sources) }
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
        item(key = "status") { BackupHero(model = hero, share = share) }
        toCheckSection(state, actions)
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

// --- Da controllare ----------------------------------------------------------------------------------------------------------------

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

/**
 * «Da controllare»: le attivita' da decidere (5B), i file ricevuti da un'altra app (IN-7) e gli altri problemi, in un gruppo solo. Ogni riga ha la
 * piastrella che dice la cosa e un segno; il perche' e il rimedio si aprono sotto con un tocco. Niente gruppo se non c'e' niente.
 */
private fun LazyListScope.toCheckSection(state: BackupPageUi, actions: BackupPageActions) {
  val problems = otherProblems(state)
  if (problems.isEmpty() && state.pendingTasks <= 0 && state.received.total <= 0) return
  item(key = "problems-title") { BackupSectionTitle(stringResource(R.string.backup_section_problems)) }
  item(key = "problems") {
    ToCheckGroup(pendingTasks = state.pendingTasks, received = state.received, problems = problems, actions = actions)
  }
}

// --- Le sorgenti -----------------------------------------------------------------------------------------------------------------

/**
 * D-4 (prove sul campo): perche' le sorgenti non si salvano adesso, anche se hanno media da salvare; null = il backup puo' lavorare. Il perche' lo dice
 * la testata, **una volta** (I3); la copertina mostra solo il segno.
 */
internal enum class SourcesHalt(@StringRes val pill: Int, val mark: GalleryIconStatus) {
  /** D12: il backup vero non e' ancora acceso. */
  TEST_ONLY(R.string.backup_source_state_paused, GalleryIconStatus.Paused),

  /** Manca un passo della configurazione (area privata, sorgenti, cartella del dispositivo, permesso). */
  NOT_CONFIGURED(R.string.backup_source_state_halted, GalleryIconStatus.Stopped),

  /** Il riepilogo del primo backup aspetta "Stanotte" o "Inizia ora" (§2.11). */
  FIRST_BACKUP(R.string.backup_source_state_not_started, GalleryIconStatus.Paused),

  /** "Ferma" dell'utente (R4): una pausa che dura finche' non tocca "Riprendi". */
  PAUSED(R.string.backup_source_state_paused, GalleryIconStatus.Paused),
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
  item(key = "sources") {
    if (sources.isEmpty()) SourcesEmpty() else SourceGrid(sources = sources, halt = halt)
  }
}

internal enum class SourceState(val label: Int, val mark: GalleryIconStatus) {
  Safe(R.string.backup_source_state_safe, GalleryIconStatus.Done),
  Working(R.string.backup_source_state_working, GalleryIconStatus.Running),

  /** D-4: ci sono media da salvare ma il backup e' fermo (l'etichetta e il segno sono quelli di [SourcesHalt]). */
  Halted(R.string.backup_source_state_halted, GalleryIconStatus.Paused),
  Problem(R.string.backup_source_state_problem, GalleryIconStatus.Attention),
  Off(R.string.backup_source_state_off, GalleryIconStatus.Stopped),
}

/** Lo stato di una sorgente: "In corso" solo se il backup puo' lavorare (D-4); i problemi e "Al sicuro" restano quello che sono. */
internal fun sourceState(source: SourceStatus, problems: Int, halt: SourcesHalt?): SourceState = when {
  !source.active -> SourceState.Off
  problems > 0 -> SourceState.Problem
  source.queued > 0 || source.safe < source.media -> if (halt != null) SourceState.Halted else SourceState.Working
  else -> SourceState.Safe
}

// --- La cartella del dispositivo e le impostazioni -------------------------------------------------------------------------------

private fun LazyListScope.deviceSection(state: BackupPageUi, actions: BackupPageActions) {
  item(key = "device-title") { BackupSectionTitle(stringResource(R.string.backup_section_device)) }
  item(key = "device") { DeviceGroup(state = state, actions = actions) }
}
