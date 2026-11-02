package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidMetricTile
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.rules.ByteFormat
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.LocalStepUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.ChoiceRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.ToggleRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

// I passi 6, 7 e 8: tre scelte locali (non toccano il NAS, quindi si fanno anche a NAS spento, E-O1).

// --- Passo 6: sorgenti di backup --------------------------------------------------------------------

@Stable
internal class BackupSourcesForm(camera: Boolean = true, screenshots: Boolean = true) {
  var camera: Boolean by mutableStateOf(camera)
  var screenshots: Boolean by mutableStateOf(screenshots)
}

/**
 * Passo 6: da dove si fa il backup (§2.1). Fotocamera e screenshot, accesi di partenza; le altre cartelle si
 * scelgono dopo, nelle Impostazioni.
 */
@Composable
internal fun BackupSourcesScreen(
  chrome: StepChrome,
  ui: LocalStepUi,
  form: BackupSourcesForm,
  onSubmit: (camera: Boolean, screenshots: Boolean) -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(R.string.sources_title),
    subtitle = stringResource(R.string.sources_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.BACKUP_SOURCES),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { onSubmit(form.camera, form.screenshots) },
      enabled = !ui.submitting,
      loading = ui.submitting,
    ),
  ) {
    item(key = "header") { GallerySectionHeader(title = stringResource(R.string.sources_section)) }
    item(key = "list") {
      FluidListGroup {
        ToggleRow(
          title = stringResource(R.string.sources_camera),
          subtitle = stringResource(R.string.sources_camera_detail),
          icon = Icons.Rounded.CameraAlt,
          checked = form.camera,
          onCheckedChange = { form.camera = it },
        )
        FluidListDivider()
        ToggleRow(
          title = stringResource(R.string.sources_screenshots),
          subtitle = stringResource(R.string.sources_screenshots_detail),
          icon = Icons.Rounded.Screenshot,
          checked = form.screenshots,
          onCheckedChange = { form.screenshots = it },
        )
      }
    }
    if (!form.camera && !form.screenshots) {
      item(key = "none") {
        NoticeCard(
          tone = NoticeTone.Info,
          icon = Icons.Rounded.Info,
          title = stringResource(R.string.sources_none_title),
          body = stringResource(R.string.sources_none_detail),
        )
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.sources_footnote)) }
  }
}

// --- Passo 7: ruoli ---------------------------------------------------------------------------------

@Stable
internal class RolesForm(indexer: Boolean, preparer: Boolean) {
  var indexer: Boolean by mutableStateOf(indexer)
  var preparer: Boolean by mutableStateOf(preparer)
  private var seeded: Boolean = false

  /** La prima volta si parte dal consiglio per questo tipo di dispositivo; poi decide l'utente. */
  fun seed(advice: RoleAdvice) {
    if (!seeded) {
      indexer = advice.indexer
      preparer = advice.preparer
      seeded = true
    }
  }
}

/** Il consiglio sui ruoli in base al tipo di dispositivo (E-A4): il telefono fa tutto, il tablet prepara. */
internal data class RoleAdvice(val indexer: Boolean, val preparer: Boolean) {
  companion object {
    fun forDevice(isTablet: Boolean): RoleAdvice = RoleAdvice(indexer = !isTablet, preparer = true)
  }
}

/**
 * Passo 7: i ruoli del dispositivo (§2.1). L'Indicizzatore analizza le foto per la ricerca, il Preparatore
 * prepara le anteprime per tutta la famiglia. Con un consiglio in base al tipo di dispositivo (E-A4): su un
 * tablet, piu' lento e spesso condiviso, si consiglia solo il Preparatore.
 */
@Composable
internal fun RolesScreen(
  chrome: StepChrome,
  ui: LocalStepUi,
  form: RolesForm,
  advice: RoleAdvice,
  onSubmit: (indexer: Boolean, preparer: Boolean) -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(R.string.roles_title),
    subtitle = stringResource(R.string.roles_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.ROLES),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { onSubmit(form.indexer, form.preparer) },
      enabled = !ui.submitting,
      loading = ui.submitting,
    ),
  ) {
    item(key = "header") { GallerySectionHeader(title = stringResource(R.string.roles_section)) }
    item(key = "list") {
      FluidListGroup {
        RoleRow(
          title = R.string.roles_indexer,
          detail = R.string.roles_indexer_detail,
          icon = Icons.Rounded.Search,
          checked = form.indexer,
          recommended = advice.indexer,
          onChange = { form.indexer = it },
        )
        FluidListDivider()
        RoleRow(
          title = R.string.roles_preparer,
          detail = R.string.roles_preparer_detail,
          icon = Icons.Rounded.Memory,
          checked = form.preparer,
          recommended = advice.preparer,
          onChange = { form.preparer = it },
        )
      }
    }
    item(key = "advice") {
      FluidSectionFootnote(
        text = stringResource(
          if (advice.indexer) R.string.roles_advice_phone else R.string.roles_advice_tablet,
        ),
      )
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = onProblemAction) }
    }
  }
}

@Composable
private fun RoleRow(
  title: Int,
  detail: Int,
  icon: ImageVector,
  checked: Boolean,
  recommended: Boolean,
  onChange: (Boolean) -> Unit,
) {
  Column {
    ToggleRow(
      title = stringResource(title),
      subtitle = stringResource(detail),
      icon = icon,
      checked = checked,
      onCheckedChange = onChange,
    )
    if (recommended) {
      Row(modifier = Modifier.padding(start = OnboardingDimens.CardPadding, bottom = OnboardingDimens.CardGap)) {
        FluidStatusBadge(label = stringResource(R.string.roles_recommended), tone = FluidTone.Success)
      }
    }
  }
}

// --- Passo 8: preset e qualita' ---------------------------------------------------------------------

@Stable
internal class PresetForm(preset: Preset = Preset.NIGHT, quality: PreviewQuality = PreviewQuality.STANDARD) {
  var preset: Preset by mutableStateOf(preset)
  var quality: PreviewQuality by mutableStateOf(quality)
}

private fun Preset.icon(): ImageVector = when (this) {
  Preset.NIGHT -> Icons.Rounded.Nightlight
  Preset.BALANCED -> Icons.Rounded.Balance
  Preset.WIFI_ALWAYS -> Icons.Rounded.Wifi
  Preset.MAXIMUM -> Icons.Rounded.AllInclusive
  Preset.CUSTOM -> Icons.Rounded.Info
}

private fun PreviewQuality.label(): Int = when (this) {
  PreviewQuality.COMPACT -> R.string.preset_quality_compact
  PreviewQuality.STANDARD -> R.string.preset_quality_standard
  PreviewQuality.HIGH -> R.string.preset_quality_high
}

private fun PreviewQuality.detail(): Int = when (this) {
  PreviewQuality.COMPACT -> R.string.preset_quality_compact_detail
  PreviewQuality.STANDARD -> R.string.preset_quality_standard_detail
  PreviewQuality.HIGH -> R.string.preset_quality_high_detail
}

/**
 * Passo 8: preset e qualita' (§2.1). Quattro preset descritti da `PresetCatalog`, con le stime di batteria, dati
 * mobili e tempo che ne seguono (formule di `core-rules`, su una libreria tipica: vedi [PresetEstimates]). La
 * fascia notte del preset "Notte" deriva dall'orario del NAS (sempre acceso: 23-07).
 */
@Composable
internal fun PresetScreen(
  chrome: StepChrome,
  ui: LocalStepUi,
  form: PresetForm,
  onSubmit: (Preset, PreviewQuality) -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  val estimate = PresetEstimates.estimate(form.preset, form.quality)
  StepScaffold(
    title = stringResource(R.string.preset_title),
    subtitle = stringResource(R.string.preset_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.PRESET),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { onSubmit(form.preset, form.quality) },
      enabled = !ui.submitting,
      loading = ui.submitting,
    ),
  ) {
    item(key = "header") { GallerySectionHeader(title = stringResource(R.string.preset_section)) }
    item(key = "list") {
      FluidListGroup {
        PresetEstimates.choices.forEachIndexed { index, preset ->
          if (index > 0) FluidListDivider()
          val info = PresetCatalog.info(preset)
          ChoiceRow(
            title = info.title,
            subtitle = info.summary,
            selected = form.preset == preset,
            onSelect = { form.preset = preset },
            icon = preset.icon(),
          )
        }
      }
    }
    item(key = "estimates-header") {
      GallerySectionHeader(
        title = stringResource(R.string.preset_estimates),
        detail = stringResource(R.string.preset_estimates_detail),
      )
    }
    item(key = "estimates") {
      Row(horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap), modifier = Modifier.fillMaxWidth()) {
        FluidMetricTile(
          label = stringResource(R.string.preset_metric_battery),
          value = stringResource(R.string.preset_metric_battery_value, estimate.batteryPercentPerDay),
          detail = stringResource(R.string.preset_metric_battery_detail),
          modifier = Modifier.weight(1f),
        )
        FluidMetricTile(
          label = stringResource(R.string.preset_metric_mobile),
          value = if (estimate.mobileBytesPerMonth <= 0L) {
            stringResource(R.string.preset_metric_mobile_none)
          } else {
            ByteFormat.size(estimate.mobileBytesPerMonth)
          },
          detail = stringResource(R.string.preset_metric_mobile_detail),
          modifier = Modifier.weight(1f),
        )
        FluidMetricTile(
          label = stringResource(R.string.preset_metric_time),
          value = estimate.daysToFinish.daysLabel(),
          detail = stringResource(R.string.preset_metric_time_detail),
          modifier = Modifier.weight(1f),
        )
      }
    }
    item(key = "quality-header") {
      GallerySectionHeader(
        title = stringResource(R.string.preset_quality_section),
        detail = stringResource(R.string.preset_quality_section_detail),
      )
    }
    item(key = "quality") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
        val options = PreviewQuality.entries
        val labels = options.associateWith { stringResource(it.label()) }
        FluidSegmentedControl(
          options = options,
          selected = form.quality,
          onSelect = { form.quality = it },
          modifier = Modifier.fillMaxWidth(),
          label = { labels.getValue(it) },
        )
        Text(
          text = stringResource(form.quality.detail()),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.preset_footnote)) }
  }
}

/** "circa 9 giorni", "oltre un mese", "mai": il tempo per mettere al sicuro l'arretrato, in parole. */
@Composable
internal fun Double?.daysLabel(): String = when {
  this == null -> stringResource(R.string.preset_days_never)
  this > DAYS_PER_MONTH -> stringResource(R.string.preset_days_month)
  else -> pluralStringResource(R.plurals.preset_days, kotlin.math.ceil(this).toInt().coerceAtLeast(1), kotlin.math.ceil(this).toInt().coerceAtLeast(1))
}

private const val DAYS_PER_MONTH = 30.0
