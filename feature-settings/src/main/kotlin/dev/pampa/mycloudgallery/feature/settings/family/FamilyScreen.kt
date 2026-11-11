package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.getValue

/** Lo stato della pagina Famiglia: chi sono io nella famiglia e quanti siamo. */
internal data class FamilyUiState(
  val trust: TrustStatus,
  val deviceCount: Int,
  val adminCount: Int,
) {
  val isAdmin: Boolean get() = trust is TrustStatus.Approved && trust.admin

  /** Approvato (admin o no): puo' mostrare lo stato della famiglia e usare il kit. */
  val isMember: Boolean get() = trust is TrustStatus.Approved

  /**
   * Configurato in una famiglia (approvato, anche con il registro "vecchio"): puo' uscirne e rientrare con un QR nuovo
   * (ON-5). Dopo un reset fatto da un altro dispositivo questo e' proprio lo stato "vecchio".
   */
  val canRejoin: Boolean get() = trust is TrustStatus.Approved || trust is TrustStatus.Stale

  /**
   * Sul NAS c'e' un registro piu' nuovo che non si verifica con la chiave di questa famiglia: puo' essere un reset fatto
   * dall'admin (una famiglia nuova, firmata da un'altra chiave). La pagina lo dice, senza deciderlo (ON-5).
   */
  val newerUnverified: Boolean get() = trust == TrustStatus.Stale(StaleReason.NEWER_UNVERIFIED)
}

@HiltViewModel
internal class FamilyViewModel @Inject constructor(registry: RegistryService) : ViewModel() {

  val state: StateFlow<FamilyUiState> = combine(registry.status, registry.devices) { trust, devices ->
    build(trust, devices.count { it.status == DeviceStatus.APPROVED }, devices.count { it.admin && it.status == DeviceStatus.APPROVED })
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = build(
      registry.status.value,
      registry.devices.value.count { it.status == DeviceStatus.APPROVED },
      registry.devices.value.count { it.admin && it.status == DeviceStatus.APPROVED },
    ),
  )

  private fun build(trust: TrustStatus, approved: Int, admins: Int) = FamilyUiState(trust, approved, admins)

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** Le destinazioni della pagina Famiglia. */
internal class FamilyLinks(
  val onBack: () -> Unit,
  val openInvite: () -> Unit,
  val openRecoveryKit: () -> Unit,
  val openFamilyState: () -> Unit,
  val openSecondAdmin: () -> Unit,
  val openReplaceAdmin: () -> Unit,
  val openReset: () -> Unit,
  /** "Rientra con un QR nuovo" (ON-5). */
  val openRejoin: () -> Unit = {},
  /** "Ricominciare da zero" dal PC, senza admin ne' kit (ON-6). */
  val openStartOverGuide: () -> Unit = {},
) {
  companion object {
    val None = FamilyLinks({}, {}, {}, {}, {}, {}, {})
  }
}

@Composable
internal fun FamilyRoute(links: FamilyLinks) {
  val viewModel: FamilyViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  FamilyScreen(state = state, links = links)
}

/**
 * Impostazioni → Famiglia (piano 2.3 e 2.4): invitare un familiare, il kit di recupero, lo stato della
 * famiglia da mostrare a chi entra senza QR, il secondo admin e il reset.
 *
 * Le voci da admin (invito, reset) compaiono solo sul dispositivo che tiene la chiave di famiglia; le voci per
 * diventare admin solo su un dispositivo approvato che non lo e'.
 */
@Composable
internal fun FamilyScreen(state: FamilyUiState, links: FamilyLinks) {
  FluidScreen(
    title = stringResource(R.string.family_title),
    onBack = links.onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Tertiary, motif = FluidHeroMotif.Glow),
  ) {
    if (!state.isMember && !state.canRejoin) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.family_none_title),
          detail = stringResource(R.string.family_none_detail),
        )
      }
      return@FluidScreen
    }

    // Un registro piu' nuovo firmato da un'altra chiave: forse l'admin ha reimpostato la famiglia (ON-5).
    if (state.newerUnverified) {
      item {
        Note(
          title = stringResource(R.string.family_newer_title),
          message = stringResource(R.string.family_newer_message),
          tone = FluidTone.Warning,
        )
      }
    }

    if (!state.isMember) {
      rejoinSection(links)
      return@FluidScreen
    }

    item {
      FluidHero(
        tone = FluidHeroTone.TertiaryToPrimary,
        motif = FluidHeroMotif.Dots,
        eyebrow = stringResource(R.string.family_hero_eyebrow),
        value = pluralStringResource(R.plurals.family_hero_value, state.deviceCount, state.deviceCount),
        title = stringResource(if (state.isAdmin) R.string.family_hero_title_admin else R.string.family_hero_title),
        description = pluralStringResource(
          if (state.isAdmin) R.plurals.family_hero_detail_admin else R.plurals.family_hero_detail,
          state.adminCount,
          state.adminCount,
        ),
        icon = Icons.Rounded.Groups,
      )
    }

    if (state.isAdmin) {
      item { GallerySectionHeader(title = stringResource(R.string.family_section_invite)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.family_invite),
            subtitle = stringResource(R.string.family_invite_detail),
            tone = FluidTone.Primary,
            leading = { RowTile(Icons.Rounded.PersonAdd) },
            leadingTile = false,
            onClick = links.openInvite,
          )
        }
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.family_section_protect)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.family_kit),
          subtitle = stringResource(R.string.family_kit_detail),
          tone = FluidTone.Warning,
          leading = { RowTile(Icons.Rounded.Key) },
          leadingTile = false,
          onClick = links.openRecoveryKit,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.family_state),
          subtitle = stringResource(R.string.family_state_detail),
          tone = FluidTone.Info,
          leading = { RowTile(Icons.Rounded.QrCode2) },
          leadingTile = false,
          onClick = links.openFamilyState,
        )
      }
    }

    if (!state.isAdmin) {
      item { GallerySectionHeader(title = stringResource(R.string.family_section_admin)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.family_second_admin),
            subtitle = stringResource(R.string.family_second_admin_detail),
            tone = FluidTone.Primary,
            leading = { RowTile(Icons.Rounded.AdminPanelSettings) },
            leadingTile = false,
            onClick = links.openSecondAdmin,
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.family_replace_admin),
            subtitle = stringResource(R.string.family_replace_admin_detail),
            tone = FluidTone.Warning,
            leading = { RowTile(Icons.Rounded.SwitchAccount) },
            leadingTile = false,
            onClick = links.openReplaceAdmin,
          )
          FluidListDivider()
          // L'admin ha perso il telefono e non c'e' piu' ne' kit ne' codice: si riparte dal PC (ON-6).
          FluidListRow(
            title = stringResource(R.string.family_start_over),
            subtitle = stringResource(R.string.family_start_over_detail),
            tone = FluidTone.Danger,
            leading = { RowTile(Icons.Rounded.RestartAlt) },
            leadingTile = false,
            onClick = links.openStartOverGuide,
          )
        }
      }
      item { FluidSectionFootnote(text = stringResource(R.string.family_admin_footnote)) }
    }

    if (state.isAdmin) {
      item { GallerySectionHeader(title = stringResource(R.string.family_section_danger)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.family_reset),
            subtitle = stringResource(R.string.family_reset_detail),
            tone = FluidTone.Danger,
            leading = { RowTile(Icons.Rounded.RestartAlt) },
            leadingTile = false,
            onClick = links.openReset,
          )
        }
      }
      item { FluidSectionFootnote(text = stringResource(R.string.family_danger_footnote)) }
    }

    rejoinSection(links)
  }
}

/** "Rientra con un QR nuovo" (ON-5): dopo un reset, o per passare a un'altra famiglia. */
private fun androidx.compose.foundation.lazy.LazyListScope.rejoinSection(links: FamilyLinks) {
  item { GallerySectionHeader(title = stringResource(R.string.family_section_rejoin)) }
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.family_rejoin),
        subtitle = stringResource(R.string.family_rejoin_detail),
        tone = FluidTone.Warning,
        leading = { RowTile(Icons.Rounded.QrCodeScanner) },
        leadingTile = false,
        onClick = links.openRejoin,
      )
    }
  }
}
