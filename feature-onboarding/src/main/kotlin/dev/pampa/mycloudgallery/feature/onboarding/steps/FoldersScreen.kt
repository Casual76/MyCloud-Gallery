package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.family.contract.ExclusionReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.FoldersUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.LoadingBlock
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.ToggleRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/** Le cartelle da escludere scelte al passo 5: tutte quelle proposte, finche' l'utente non ne accende una. */
@Stable
internal class FoldersForm(excluded: Set<String> = emptySet()) {
  var excluded: Set<String> by mutableStateOf(excluded)
  private var seeded: Boolean = false

  /** Alla prima proposta si parte con tutto escluso: e' quello che l'app consiglia. */
  fun seed(paths: List<String>) {
    if (!seeded && paths.isNotEmpty()) {
      excluded = paths.toSet()
      seeded = true
    }
  }

  fun toggle(path: String, on: Boolean) {
    excluded = if (on) excluded + path else excluded - path
  }
}

internal class FoldersActions(
  val onSubmit: (Set<String>) -> Unit = {},
  val onSkip: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

@StringRes
internal fun ExclusionReason.label(): Int = when (this) {
  ExclusionReason.MUSIC -> R.string.folders_reason_music
  ExclusionReason.MOVIES -> R.string.folders_reason_movies
  ExclusionReason.SMALL_COVERS -> R.string.folders_reason_covers
  ExclusionReason.SYSTEM_FOLDER -> R.string.folders_reason_system
  ExclusionReason.APP_FOLDER -> R.string.folders_reason_app
}

internal fun ExclusionReason.icon(): ImageVector = when (this) {
  ExclusionReason.MUSIC -> Icons.Rounded.MusicNote
  ExclusionReason.MOVIES -> Icons.Rounded.Movie
  ExclusionReason.SMALL_COVERS -> Icons.Rounded.Image
  ExclusionReason.SYSTEM_FOLDER -> Icons.Rounded.Settings
  ExclusionReason.APP_FOLDER -> Icons.Rounded.Apps
}

/**
 * Passo 5: le cartelle da non guardare (§2.1, E-N14, E-N15).
 *
 * L'app ha dato un'occhiata alla cartella comune (al massimo due livelli e 5000 voci, e si puo' saltare) e propone
 * di escludere musica, film e copertine piccole, che non sono foto di famiglia. Ogni proposta e' un interruttore,
 * acceso di partenza. Il passo si puo' saltare: resta in Impostazioni, "Da completare".
 */
@Composable
internal fun FoldersScreen(
  chrome: StepChrome,
  ui: FoldersUi,
  form: FoldersForm,
  actions: FoldersActions,
  modifier: Modifier = Modifier,
) {
  val proposal = ui.proposal
  LaunchedEffect(proposal) { proposal?.let { form.seed(it.excluded.map { folder -> folder.path }) } }
  StepScaffold(
    title = stringResource(R.string.folders_title),
    subtitle = stringResource(R.string.folders_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FOLDERS),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { actions.onSubmit(form.excluded) },
      enabled = !ui.loading && !ui.submitting,
      loading = ui.submitting,
    ),
    secondary = DockAction(
      label = stringResource(if (ui.loading) R.string.folders_skip_scan else R.string.action_skip_step),
      onClick = actions.onSkip,
      enabled = !ui.submitting,
      style = FluidButtonStyle.Plain,
    ),
  ) {
    when {
      ui.loading -> item(key = "loading") { LoadingBlock(label = stringResource(R.string.folders_loading)) }
      proposal == null || proposal.excluded.isEmpty() -> item(key = "empty") {
        NoticeCard(
          tone = NoticeTone.Success,
          icon = Icons.Rounded.CheckCircle,
          title = stringResource(R.string.folders_none_title),
          body = stringResource(R.string.folders_none_detail),
          bodyBehindInfo = true,
          family = GalleryIconFamily.Backup,
        )
      }
      else -> {
        item(key = "header") {
          GallerySectionHeader(
            title = stringResource(R.string.folders_section),
            detail = stringResource(R.string.folders_section_detail),
          )
        }
        item(key = "list") {
          FluidListGroup {
            proposal.excluded.forEachIndexed { index, folder ->
              if (index > 0) FluidListDivider()
              ToggleRow(
                title = folder.path,
                subtitle = stringResource(folder.reason.label()),
                icon = folder.reason.icon(),
                checked = folder.path in form.excluded,
                onCheckedChange = { form.toggle(folder.path, it) },
                family = GalleryIconFamily.Backup,
              )
            }
          }
        }
      }
    }
    proposal?.let {
      item(key = "scanned") {
        FluidSectionFootnote(
          text = pluralStringResource(
            if (it.truncated) R.plurals.folders_scanned_truncated else R.plurals.folders_scanned,
            it.scanned,
            it.scanned,
          ),
        )
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
  }
}
