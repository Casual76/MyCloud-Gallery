package dev.pampa.mycloudgallery.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Shield
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
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
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
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.RowValue
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
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

    // I3 (fase I): pochi gruppi, ognuno con una famiglia di colori: prima cio' che protegge le foto (backup, NAS, rete), poi le foto, la privacy,
    // la famiglia; cio' che serve di rado sta in fondo, in «Avanzate». Ogni riga ha la piastrella espressiva della sua famiglia e lo stato a destra
    // (un valore corto, un segno, un interruttore); il sottotitolo solo dove porta uno stato che cambia.
    item { GallerySectionHeader(title = stringResource(R.string.settings_section_backup)) }
    item {
      FluidListGroup {
        // Fase 5A (D16): la voce "Backup" (lo stato a destra), solo se `:app` ha scritto dove porta.
        links.openBackup?.let { openBackup ->
          BackupEntryRow(status = backupStatus, onClick = openBackup)
          FluidListDivider()
        }
        FluidListRow(
          title = stringResource(R.string.settings_nas_account),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.Dns) },
          leadingTile = false,
          onClick = links.openNasAccount,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_network),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.Wifi) },
          leadingTile = false,
          onClick = links.openNetwork,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_rules),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.Schedule) },
          leadingTile = false,
          onClick = links.openRules,
        )
        FluidListDivider()
        // "Il NAS non e' un backup": un segno di attenzione sulla piastrella, la spiegazione nella pagina.
        FluidListRow(
          title = stringResource(R.string.settings_backup_notice),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.SdStorage, status = GalleryIconStatus.Attention, family = GalleryIconFamily.Backup) },
          leadingTile = false,
          onClick = links.openBackupNotice,
        )
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_photos)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_quality),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.PhotoLibrary) },
          leadingTile = false,
          onClick = links.openQuality,
        )
        // Fase 6 (P8, P11): "Ricerca e modelli", solo se `:app` ha scritto dove porta.
        links.openSearch?.let { openSearch ->
          FluidListDivider()
          SearchEntryRow(summary = searchSummary, onClick = openSearch)
        }
        // Fase 8 (P7, D3): "Persone" (stato su questo dispositivo), solo se `:app` ha scritto dove porta.
        links.openPeople?.let { openPeople ->
          FluidListDivider()
          PeopleEntryRow(summary = peopleSummary, onClick = openPeople)
        }
        // Fase 9 (P9, D15, D18): "Ricordi e notifiche", solo se `:app` ha scritto dove porta.
        links.openMemories?.let { openMemories ->
          FluidListDivider()
          MemoriesEntryRow(summary = memoriesSummary, onClick = openMemories)
        }
        // Fase 10 (P3, D3, D6): "Widget", solo se la pagina c'e' (`settingsGraph` la scrive sempre).
        links.openWidget?.let { openWidget ->
          FluidListDivider()
          WidgetEntryRow(summary = widgetSummary, onClick = openWidget)
        }
        // Fase 6 (P14, D50, Q16 (b)): "Animazione delle foto in movimento", impostazione personale sincronizzata. Null = `:app` non l'ha collegata.
        if (motionAnimation != null) {
          FluidListDivider()
          MotionAnimationRow(on = motionAnimation, onToggle = onMotionAnimation)
        }
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_privacy)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_security),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.Shield) },
          leadingTile = false,
          onClick = links.openSecurity,
        )
        // Fase 7 (P9, D33): "Nascosti", sempre raggiungibili da qui anche se la sezione delle Raccolte e' nascosta. Solo se `:app` ha scritto dove porta.
        links.openHidden?.let { openHidden ->
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.settings_hidden),
            subtitle = NO_SUBTITLE,
            leading = { RowTile(Icons.Rounded.VisibilityOff) },
            leadingTile = false,
            onClick = openHidden,
          )
        }
        // Fase 9 (P4, D24, Q1 (c)): "Mappa" (la mappa dettagliata di questo dispositivo), solo se `:app` ha scritto dove porta.
        links.openMap?.let { openMap ->
          FluidListDivider()
          MapEntryRow(summary = mapSummary, onClick = openMap)
        }
        // Fase 5B (P12): "Condivisioni", con il segno se la posizione si toglie.
        links.openSharing?.let { openSharing ->
          FluidListDivider()
          SharingEntryRow(removeLocation = removeLocation, onClick = openSharing)
        }
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_family)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_devices),
          subtitle = devicesSubtitle(state),
          leading = {
            RowTile(Icons.Rounded.Devices, status = if (state.pendingRequests > 0 && state.isAdmin) GalleryIconStatus.Attention else null)
          },
          leadingTile = false,
          badge = if (state.pendingRequests > 0 && state.isAdmin) {
            { RowValue(state.pendingRequests.toString()) }
          } else {
            null
          },
          onClick = links.openDevices,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_family),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.Groups) },
          leadingTile = false,
          onClick = links.openFamily,
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
    }

    item { GallerySectionHeader(title = stringResource(R.string.settings_section_advanced)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_diagnostics),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.MonitorHeart) },
          leadingTile = false,
          onClick = openDiagnostics,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_about),
          subtitle = NO_SUBTITLE,
          leading = { RowTile(Icons.Rounded.Info) },
          leadingTile = false,
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

/**
 * H5 (11/11): le voci che portano a una pagina senza uno stato da dire hanno il solo titolo. Il sottotitolo elencava cosa c'e' dentro la pagina
 * ("Password e indirizzo del NAS"): era testo da leggere che non diceva niente di nuovo. Una riga col sottotitolo vuoto e' alta una riga.
 */
private const val NO_SUBTITLE = ""

/** Un passo saltato dell'onboarding, con un nome che dice cosa c'e' da fare (non solo "passo 10"). */
@Composable
private fun PendingStepRow(step: OnboardingStep, onClick: () -> Unit) {
  val (title, icon) = when (step) {
    OnboardingStep.FOLDERS -> R.string.pending_folders to Icons.Rounded.FolderOff
    OnboardingStep.BATTERY -> R.string.pending_battery to Icons.Rounded.BatteryChargingFull
    OnboardingStep.VPN -> R.string.pending_vpn to Icons.Rounded.VpnKey
    else -> R.string.pending_generic to Icons.Rounded.Tune
  }
  FluidListRow(
    title = if (title == R.string.pending_generic) stringResource(title, step.number) else stringResource(title),
    // I3: il titolo dice gia' che cosa c'e' da fare; il perche' e' nella pagina del passo. Il segno dice che manca.
    subtitle = NO_SUBTITLE,
    leading = { RowTile(icon, status = GalleryIconStatus.Attention) },
    leadingTile = false,
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

/**
 * CS-6 (fase H): la riga dice solo cio' che la testata sopra non dice gia'. Il numero dei dispositivi (e "Nessuna famiglia") sta grande nella testata
 * "La tua famiglia": ripeterlo una riga sotto era rumore. Resta il conto delle richieste da approvare, che e' una cosa da fare.
 */
@Composable
private fun devicesSubtitle(state: SettingsUiState): String = when {
  state.pendingRequests > 0 && state.isAdmin ->
    pluralStringResource(R.plurals.settings_devices_pending, state.pendingRequests, state.pendingRequests)
  else -> NO_SUBTITLE
}
