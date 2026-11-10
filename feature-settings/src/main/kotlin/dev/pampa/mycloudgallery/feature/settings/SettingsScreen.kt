package dev.pampa.mycloudgallery.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.backup.BackupEntryRow
import dev.pampa.mycloudgallery.feature.settings.search.SearchEntryRow
import dev.pampa.mycloudgallery.feature.settings.backup.BackupEntryViewModel
import dev.pampa.mycloudgallery.feature.settings.library.MotionAnimationRow
import dev.pampa.mycloudgallery.feature.settings.library.MotionAnimationViewModel
import dev.pampa.mycloudgallery.feature.settings.map.MapEntryRow
import dev.pampa.mycloudgallery.feature.settings.map.MapEntrySummary
import dev.pampa.mycloudgallery.feature.settings.map.MapEntryViewModel
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryRow
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntrySummary
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryViewModel
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryRow
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntrySummary
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryViewModel
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingEntryRow
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingEntryViewModel
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetContent
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetEntryRow
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetEntrySummary
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetEntryViewModel
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetRefresh
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import androidx.compose.runtime.getValue

/**
 * Le destinazioni delle Impostazioni. Con [None] (il default) le righe non portano da nessuna parte: e' la
 * forma dello scaffold P0, che gli screenshot di `:app` usano ancora.
 */
class SettingsLinks(
  val openDevices: () -> Unit = {},
  val openFamily: () -> Unit = {},
  val openNasAccount: () -> Unit = {},
  val openNetwork: () -> Unit = {},
  val openSecurity: () -> Unit = {},
  val openBackupNotice: () -> Unit = {},
  val openAbout: () -> Unit = {},
  val openQuality: () -> Unit = {},
  /** "Quando lavora l'app" (RE-8): preset, tetto dei dati mobili, roaming. */
  val openRules: () -> Unit = {},
  /**
   * Fase 5A (D16): la voce "Backup" porta alla pagina del backup di feature-backup, che le Impostazioni non conoscono (i feature non si
   * conoscono: la callback la scrive `:app`). Null = la voce non c'e'.
   */
  val openBackup: (() -> Unit)? = null,
  /**
   * Fase 5B (P12): la voce "Condivisioni" (rimuovi la posizione). La pagina e' delle Impostazioni stesse, quindi `settingsGraph` la scrive sempre;
   * null (il default dello scaffold) = la voce non c'e'.
   */
  val openSharing: (() -> Unit)? = null,
  /**
   * Fase 6 (P8, P11): la voce "Ricerca e modelli" porta alla pagina di feature-search, che le Impostazioni non conoscono (la callback la
   * scrive `:app`). Null = la voce non c'e'.
   */
  val openSearch: (() -> Unit)? = null,
  /**
   * Fase 7 (P9, D33): la voce "Nascosti" porta alla pagina dei Nascosti di feature-library (dietro la stessa credenziale di sempre), che le
   * Impostazioni non conoscono (la callback la scrive `:app`). Serve a ritrovarli anche quando la sezione Nascosti delle Raccolte e' nascosta
   * (P11). Null = la voce non c'e'.
   */
  val openHidden: (() -> Unit)? = null,
  /**
   * Fase 8 (P7, D3): la voce "Persone" porta alla pagina Impostazioni -> Persone di feature-people, che le Impostazioni non conoscono (la callback la
   * scrive `:app`). Null = la voce non c'e'.
   */
  val openPeople: (() -> Unit)? = null,
  /**
   * Fase 9 (P9, D15, D18): la voce "Ricordi e notifiche" porta alla pagina Impostazioni -> Ricordi e notifiche di feature-memories, che le Impostazioni non conoscono (la
   * callback la scrive `:app`). Null = la voce non c'e'.
   */
  val openMemories: (() -> Unit)? = null,
  /**
   * Fase 9 (P4, D24, Q1 (c)): la voce "Mappa" (sezione "Protezione": decide cosa esce dal telefono) porta alla pagina Impostazioni -> Mappa di feature-map, che le Impostazioni non conoscono (la callback la scrive
   * `:app`). Null = la voce non c'e'.
   */
  val openMap: (() -> Unit)? = null,
  /** Fase 10 (P3, D3, D6): la voce "Widget" (cosa mostra, ogni quanto cambia). La pagina e' delle Impostazioni stesse, quindi `settingsGraph` la scrive sempre; null = la voce non c'e'. */
  val openWidget: (() -> Unit)? = null,
) {
  companion object {
    val None = SettingsLinks()
  }
}

@Composable
internal fun SettingsRoute(
  onBack: () -> Unit,
  links: SettingsLinks,
  openDiagnostics: () -> Unit,
  resumeStep: (OnboardingStep) -> Unit,
  developerSection: (@Composable () -> Unit)?,
) {
  val viewModel: SettingsViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val backupViewModel: BackupEntryViewModel = hiltViewModel()
  val backup by backupViewModel.status.collectAsStateWithLifecycle()
  val sharingViewModel: SharingEntryViewModel = hiltViewModel()
  val removeLocation by sharingViewModel.removeLocation.collectAsStateWithLifecycle()
  val motionViewModel: MotionAnimationViewModel = hiltViewModel()
  val motionAnimation by motionViewModel.enabled.collectAsStateWithLifecycle()
  val searchViewModel: dev.pampa.mycloudgallery.feature.settings.search.SearchEntryViewModel = hiltViewModel()
  val searchSummary by searchViewModel.summary.collectAsStateWithLifecycle()
  val peopleViewModel: PeopleEntryViewModel = hiltViewModel()
  val peopleSummary by peopleViewModel.summary.collectAsStateWithLifecycle()
  val memoriesViewModel: MemoriesEntryViewModel = hiltViewModel()
  val memoriesSummary by memoriesViewModel.summary.collectAsStateWithLifecycle()
  val widgetViewModel: WidgetEntryViewModel = hiltViewModel()
  val widgetContent by widgetViewModel.content.collectAsStateWithLifecycle()
  val widgetPeriod by widgetViewModel.period.collectAsStateWithLifecycle()
  val mapViewModel: MapEntryViewModel = hiltViewModel()
  val mapSummary by mapViewModel.summary.collectAsStateWithLifecycle()
  SettingsScreen(
    state = state,
    onBack = onBack,
    openDiagnostics = openDiagnostics,
    resumeStep = resumeStep,
    developerSection = developerSection,
    links = links,
    backupStatus = backup,
    removeLocation = removeLocation,
    motionAnimation = motionAnimation,
    onMotionAnimation = motionViewModel::set,
    searchSummary = searchSummary,
    peopleSummary = peopleSummary,
    memoriesSummary = memoriesSummary,
    mapSummary = mapSummary,
    widgetSummary = WidgetEntrySummary(widgetContent, widgetPeriod),
  )
}

/**
 * La pagina principale delle Impostazioni (P7): un'apertura con lo stato della famiglia, poi le sezioni
 * (famiglia e dispositivi, connessione, protezione), i passi saltati dell'onboarding ("Da completare") e
 * la Diagnostica.
 *
 * Le azioni da admin (richieste da approvare) compaiono solo sul dispositivo che tiene la chiave di
 * famiglia. [developerSection] la passa :app solo in debug.
 */
@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onBack: () -> Unit,
  openDiagnostics: () -> Unit,
  resumeStep: (OnboardingStep) -> Unit,
  developerSection: (@Composable () -> Unit)? = null,
  links: SettingsLinks = SettingsLinks.None,
  backupStatus: BackupStatus? = null,
  removeLocation: Boolean = false,
  motionAnimation: Boolean? = null,
  onMotionAnimation: (Boolean) -> Unit = {},
  searchSummary: dev.pampa.mycloudgallery.feature.settings.search.SearchEntrySummary? = null,
  peopleSummary: PeopleEntrySummary? = null,
  memoriesSummary: MemoriesEntrySummary? = null,
  mapSummary: MapEntrySummary? = null,
  widgetSummary: WidgetEntrySummary = WidgetEntrySummary(WidgetContent.DEFAULT, WidgetRefresh.DEFAULT),
) {
  val family = familyHero(state)
  FluidScreen(
    title = stringResource(R.string.settings_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = family.tone, motif = FluidHeroMotif.Glow, urgent = false),
  ) {
    item {
      FluidHero(
        tone = family.tone,
        motif = FluidHeroMotif.Figures,
        eyebrow = stringResource(R.string.settings_hero_eyebrow),
        value = family.value(),
        title = family.title(),
        description = family.description(),
        icon = Icons.Rounded.Groups,
        actionLabel = if (state.isAdmin && state.pendingRequests > 0) stringResource(R.string.settings_hero_review) else null,
        onAction = if (state.isAdmin && state.pendingRequests > 0) links.openDevices else null,
      )
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_family)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_devices),
          subtitle = devicesSubtitle(state),
          tone = if (state.pendingRequests > 0 && state.isAdmin) FluidTone.Warning else FluidTone.Primary,
          leading = { RowGlyph(Icons.Rounded.Devices) },
          labels = if (state.pendingRequests > 0 && state.isAdmin) {
            { StatusPill(state.pendingRequests.toString(), FluidTone.Warning) }
          } else {
            null
          },
          onClick = links.openDevices,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_family),
          subtitle = stringResource(if (state.isAdmin) R.string.settings_family_detail_admin else R.string.settings_family_detail),
          tone = FluidTone.Primary,
          leading = { RowGlyph(Icons.Rounded.Groups) },
          onClick = links.openFamily,
        )
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_connection)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_nas_account),
          subtitle = stringResource(R.string.settings_nas_account_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.Dns) },
          onClick = links.openNasAccount,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_network),
          subtitle = stringResource(R.string.settings_network_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.Wifi) },
          onClick = links.openNetwork,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_quality),
          subtitle = stringResource(R.string.settings_quality_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.PhotoLibrary) },
          onClick = links.openQuality,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_rules),
          subtitle = stringResource(R.string.settings_rules_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.Schedule) },
          onClick = links.openRules,
        )
        // Fase 6 (P8, P11): "Ricerca e modelli" (ruolo, modelli, lettura del testo), solo se `:app` ha scritto dove porta.
        links.openSearch?.let { openSearch ->
          FluidListDivider()
          SearchEntryRow(summary = searchSummary, onClick = openSearch)
        }
        // Fase 8 (P7, D3): "Persone" (stato su questo dispositivo), solo se `:app` ha scritto dove porta.
        links.openPeople?.let { openPeople ->
          FluidListDivider()
          PeopleEntryRow(summary = peopleSummary, onClick = openPeople)
        }
        // Fase 9 (P9, D15, D18): "Ricordi e notifiche" (come sono le notifiche di questo dispositivo), solo se `:app` ha scritto dove porta.
        links.openMemories?.let { openMemories ->
          FluidListDivider()
          MemoriesEntryRow(summary = memoriesSummary, onClick = openMemories)
        }
        // Fase 10 (P3, D3, D6): "Widget" (cosa mostra e ogni quanto cambia), solo se la pagina c'e' (`settingsGraph` la scrive sempre).
        links.openWidget?.let { openWidget ->
          FluidListDivider()
          WidgetEntryRow(summary = widgetSummary, onClick = openWidget)
        }
      }
    }

    // Fase 6 (P14, D50, Q16 (b)): "Animazione delle foto in movimento", impostazione personale sincronizzata. Null = `:app` non l'ha collegata: niente sezione.
    if (motionAnimation != null) {
      item { GallerySectionHeader(title = stringResource(R.string.settings_section_library)) }
      item { FluidListGroup { MotionAnimationRow(on = motionAnimation, onToggle = onMotionAnimation) } }
      item { FluidSectionFootnote(text = stringResource(R.string.library_motion_animation_footnote)) }
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_protection)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_security),
          subtitle = stringResource(R.string.settings_security_detail),
          tone = FluidTone.Success,
          leading = { RowGlyph(Icons.Rounded.Security) },
          onClick = links.openSecurity,
        )
        FluidListDivider()
        // Fase 9 (P4, D24, Q1 (c)): "Mappa" (la mappa dettagliata di questo dispositivo), solo se `:app` ha scritto dove porta.
        links.openMap?.let { openMap ->
          MapEntryRow(summary = mapSummary, onClick = openMap)
          FluidListDivider()
        }
        // Fase 7 (P9, D33): "Nascosti", sempre raggiungibili da qui anche se la sezione delle Raccolte e' nascosta. Solo se `:app` ha scritto dove porta.
        links.openHidden?.let { openHidden ->
          FluidListRow(
            title = stringResource(R.string.settings_hidden),
            subtitle = stringResource(R.string.settings_hidden_detail),
            tone = FluidTone.Success,
            leading = { RowGlyph(Icons.Rounded.VisibilityOff) },
            onClick = openHidden,
          )
          FluidListDivider()
        }
        // Fase 5B (P12): "Condivisioni", con l'indicatore se la posizione si toglie.
        links.openSharing?.let { openSharing ->
          SharingEntryRow(removeLocation = removeLocation, onClick = openSharing)
          FluidListDivider()
        }
        // Fase 5A (D16): la voce "Backup" (stato in una frase), solo se `:app` ha scritto dove porta.
        links.openBackup?.let { openBackup ->
          BackupEntryRow(status = backupStatus, onClick = openBackup)
          FluidListDivider()
        }
        FluidListRow(
          title = stringResource(R.string.settings_backup_notice),
          subtitle = stringResource(R.string.settings_backup_notice_detail),
          tone = FluidTone.Warning,
          leading = { RowGlyph(Icons.Rounded.Backup) },
          onClick = links.openBackupNotice,
        )
      }
    }

    if (state.pendingSteps.isNotEmpty()) {
      item { GallerySectionHeader(title = stringResource(R.string.settings_section_pending)) }
      item {
        FluidListGroup {
          state.pendingSteps.forEachIndexed { index, step ->
            if (index > 0) FluidListDivider()
            PendingStepRow(step = step, onClick = { resumeStep(step) })
          }
        }
      }
      item { FluidSectionFootnote(text = stringResource(R.string.settings_pending_footnote)) }
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_support)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_diagnostics),
          subtitle = stringResource(R.string.settings_diagnostics_detail),
          tone = FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.MonitorHeart) },
          onClick = openDiagnostics,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_about),
          subtitle = stringResource(R.string.settings_about_detail),
          tone = FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.Info) },
          onClick = links.openAbout,
        )
      }
    }

    if (developerSection != null) {
      item { GallerySectionHeader(title = stringResource(R.string.settings_section_developer)) }
      item { developerSection() }
    }
  }
}

/** Un passo saltato dell'onboarding, con un nome che dice cosa c'e' da fare (non solo "passo 10"). */
@Composable
private fun PendingStepRow(step: OnboardingStep, onClick: () -> Unit) {
  val (title, detail, icon) = when (step) {
    OnboardingStep.FOLDERS -> Triple(R.string.pending_folders, R.string.pending_folders_detail, Icons.Rounded.FolderOff)
    OnboardingStep.BATTERY -> Triple(R.string.pending_battery, R.string.pending_battery_detail, Icons.Rounded.BatteryChargingFull)
    OnboardingStep.VPN -> Triple(R.string.pending_vpn, R.string.pending_vpn_detail, Icons.Rounded.VpnKey)
    else -> Triple(R.string.pending_generic, R.string.pending_generic_detail, Icons.Rounded.Tune)
  }
  FluidListRow(
    title = if (title == R.string.pending_generic) stringResource(title, step.number) else stringResource(title),
    subtitle = stringResource(detail),
    tone = FluidTone.Warning,
    leading = { RowGlyph(icon) },
    onClick = onClick,
  )
}

/** Come si presenta la famiglia in cima alla pagina: il tono dell'apertura e le tre frasi. */
private class FamilyHero(
  val tone: FluidHeroTone,
  val value: @Composable () -> String,
  val title: @Composable () -> String,
  val description: @Composable () -> String,
)

private fun familyHero(state: SettingsUiState): FamilyHero = when {
  state.trust is TrustStatus.NotConfigured -> FamilyHero(
    tone = FluidHeroTone.Secondary,
    value = { stringResource(R.string.hero_none_value) },
    title = { stringResource(R.string.hero_none_title) },
    description = { stringResource(R.string.hero_none_detail) },
  )
  state.trust is TrustStatus.Stale -> FamilyHero(
    tone = FluidHeroTone.Alert,
    value = { deviceCountText(state.deviceCount) },
    title = { stringResource(R.string.hero_stale_title) },
    description = { stringResource(staleDetail(state.trust.reason)) },
  )
  // R7: il Keystore non risponde. Mai l'apertura "tutto a posto" (l'ultimo ramo): niente fiducia finche' non torna.
  state.trust is TrustStatus.KeystoreUnavailable -> FamilyHero(
    tone = FluidHeroTone.Alert,
    value = { stringResource(R.string.hero_keystore_value) },
    title = { stringResource(R.string.hero_keystore_title) },
    description = { stringResource(R.string.hero_keystore_detail) },
  )
  state.trust is TrustStatus.Revoked || state.trust is TrustStatus.Retired || state.trust is TrustStatus.Lost -> FamilyHero(
    tone = FluidHeroTone.Alert,
    value = { stringResource(R.string.hero_inactive_value) },
    title = { stringResource(R.string.hero_inactive_title) },
    description = { stringResource(R.string.hero_inactive_detail) },
  )
  state.trust is TrustStatus.Pending -> FamilyHero(
    tone = FluidHeroTone.Tertiary,
    value = { stringResource(R.string.hero_pending_value) },
    title = { stringResource(R.string.hero_pending_title) },
    description = { stringResource(R.string.hero_pending_detail) },
  )
  state.isAdmin && state.pendingRequests > 0 -> FamilyHero(
    tone = FluidHeroTone.TertiaryToPrimary,
    value = { pluralStringResource(R.plurals.hero_requests_value, state.pendingRequests, state.pendingRequests) },
    title = { stringResource(R.string.hero_requests_title) },
    description = { stringResource(R.string.hero_requests_detail) },
  )
  else -> FamilyHero(
    tone = FluidHeroTone.PrimaryToSecondary,
    value = { deviceCountText(state.deviceCount) },
    title = { stringResource(if (state.isAdmin) R.string.hero_ok_title_admin else R.string.hero_ok_title) },
    description = { stringResource(if (state.isAdmin) R.string.hero_ok_detail_admin else R.string.hero_ok_detail) },
  )
}

private fun staleDetail(reason: StaleReason): Int = when (reason) {
  StaleReason.NOT_REFRESHED -> R.string.stale_not_refreshed
  StaleReason.NEWER_UNVERIFIED -> R.string.stale_newer_unverified
  StaleReason.LISTING_TRUNCATED -> R.string.stale_listing_truncated
  StaleReason.NEEDS_APP_UPDATE -> R.string.stale_needs_update
  StaleReason.EQUIVOCATION -> R.string.stale_equivocation
}

@Composable
private fun deviceCountText(count: Int): String = pluralStringResource(R.plurals.settings_devices_count, count, count)

@Composable
private fun devicesSubtitle(state: SettingsUiState): String = when {
  state.trust is TrustStatus.NotConfigured -> stringResource(R.string.settings_devices_none)
  state.pendingRequests > 0 && state.isAdmin ->
    pluralStringResource(R.plurals.settings_devices_pending, state.pendingRequests, state.pendingRequests)
  else -> pluralStringResource(R.plurals.settings_devices_count, state.deviceCount, state.deviceCount)
}
