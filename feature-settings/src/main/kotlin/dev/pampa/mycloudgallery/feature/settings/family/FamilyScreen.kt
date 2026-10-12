package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCode2
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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
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
    if (!state.isMember) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.family_none_title),
          detail = stringResource(R.string.family_none_detail),
        )
      }
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
      item { FluidSectionHeader(title = stringResource(R.string.family_section_invite)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.family_invite),
            subtitle = stringResource(R.string.family_invite_detail),
            tone = FluidTone.Primary,
            leading = { RowGlyph(Icons.Rounded.PersonAdd) },
            onClick = links.openInvite,
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.family_section_protect)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.family_kit),
          subtitle = stringResource(R.string.family_kit_detail),
          tone = FluidTone.Warning,
          leading = { RowGlyph(Icons.Rounded.Key) },
          onClick = links.openRecoveryKit,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.family_state),
          subtitle = stringResource(R.string.family_state_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.QrCode2) },
          onClick = links.openFamilyState,
        )
      }
    }

    if (!state.isAdmin) {
      item { FluidSectionHeader(title = stringResource(R.string.family_section_admin)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.family_second_admin),
            subtitle = stringResource(R.string.family_second_admin_detail),
            tone = FluidTone.Primary,
            leading = { RowGlyph(Icons.Rounded.AdminPanelSettings) },
            onClick = links.openSecondAdmin,
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.family_replace_admin),
            subtitle = stringResource(R.string.family_replace_admin_detail),
            tone = FluidTone.Warning,
            leading = { RowGlyph(Icons.Rounded.SwitchAccount) },
            onClick = links.openReplaceAdmin,
          )
        }
      }
      item { FluidSectionFootnote(text = stringResource(R.string.family_admin_footnote)) }
    }

    if (state.isAdmin) {
      item { FluidSectionHeader(title = stringResource(R.string.family_section_danger)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.family_reset),
            subtitle = stringResource(R.string.family_reset_detail),
            tone = FluidTone.Danger,
            leading = { RowGlyph(Icons.Rounded.RestartAlt) },
            onClick = links.openReset,
          )
        }
      }
      item { FluidSectionFootnote(text = stringResource(R.string.family_danger_footnote)) }
    }
  }
}
