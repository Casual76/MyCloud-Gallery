package dev.pampa.mycloudgallery.feature.settings.family

import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoRow
import androidx.compose.ui.Modifier
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
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
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.getValue
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.feature.settings.ui.DescribedRow
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsStatusHeader

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
        // Giro 2 della fase I (CS-17): la forma unica dello stato vuoto (figura e titolo); la frase dietro la «i» del componente.
        GalleryFigureState(
          icon = Icons.Rounded.Groups,
          title = stringResource(R.string.family_none_title),
          tone = GalleryIllustrationTone.Brand,
          fill = false,
          why = stringResource(R.string.family_none_detail),
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

    // Giro 1 della fase I (CG-4): in cima quanti dispositivi e chi sei; l'unico avvertimento che resta in pagina e' quello sulle chiavi (l'unico admin).
    val soleAdmin = state.isAdmin && state.adminCount <= 1
    item {
      SettingsStatusHeader(
        icon = Icons.Rounded.Groups,
        family = GalleryIconFamily.People,
        value = pluralStringResource(R.plurals.family_hero_value, state.deviceCount, state.deviceCount),
        label = stringResource(if (state.isAdmin) R.string.family_hero_title_admin else R.string.family_hero_title),
        status = if (soleAdmin) GalleryIconStatus.Attention else null,
      )
    }
    if (soleAdmin) {
      item { Note(title = stringResource(R.string.family_sole_admin_title), message = pluralStringResource(R.plurals.family_hero_detail_admin, 1, 1), tone = FluidTone.Warning) }
    }

    if (state.isAdmin) {
      item { GallerySectionHeader(title = stringResource(R.string.family_section_invite)) }
      item {
        FluidListGroup {
          DescribedRow(
            title = stringResource(R.string.family_invite),
            detail = stringResource(R.string.family_invite_detail),
            icon = Icons.Rounded.PersonAdd,
            tone = FluidTone.Primary,
            onClick = links.openInvite,
          )
        }
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.family_section_protect)) }
    item {
      FluidListGroup {
        DescribedRow(
          title = stringResource(R.string.family_kit),
          detail = stringResource(R.string.family_kit_detail),
          icon = Icons.Rounded.Key,
          tone = FluidTone.Warning,
          status = if (soleAdmin) GalleryIconStatus.Attention else null,
          onClick = links.openRecoveryKit,
        )
        FluidListDivider()
        DescribedRow(
          title = stringResource(R.string.family_state),
          detail = stringResource(R.string.family_state_detail),
          icon = Icons.Rounded.QrCode2,
          onClick = links.openFamilyState,
        )
      }
    }
    item {
      GalleryInfoRow(label = stringResource(R.string.family_protect_info), detail = stringResource(R.string.family_kit_detail) + ". " + stringResource(R.string.family_state_detail) + ". " +
          pluralStringResource(if (state.isAdmin) R.plurals.family_hero_detail_admin else R.plurals.family_hero_detail, state.adminCount, state.adminCount), modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsDimens.CardPadding))
    }

    if (!state.isAdmin) {
      item { GallerySectionHeader(title = stringResource(R.string.family_section_admin)) }
      item {
        FluidListGroup {
          DescribedRow(
            title = stringResource(R.string.family_second_admin),
            detail = stringResource(R.string.family_second_admin_detail),
            icon = Icons.Rounded.AdminPanelSettings,
            tone = FluidTone.Primary,
            onClick = links.openSecondAdmin,
          )
          FluidListDivider()
          DescribedRow(
            title = stringResource(R.string.family_replace_admin),
            detail = stringResource(R.string.family_replace_admin_detail),
            icon = Icons.Rounded.SwitchAccount,
            tone = FluidTone.Warning,
            onClick = links.openReplaceAdmin,
          )
          FluidListDivider()
          // L'admin ha perso il telefono e non c'e' piu' ne' kit ne' codice: si riparte dal PC (ON-6).
          DescribedRow(
            title = stringResource(R.string.family_start_over),
            detail = stringResource(R.string.family_start_over_detail),
            icon = Icons.Rounded.RestartAlt,
            tone = FluidTone.Danger,
            onClick = links.openStartOverGuide,
          )
        }
      }
      // Le chiavi: serve il codice del kit (resta in pagina, CG-4).
      item { FluidSectionFootnote(text = stringResource(R.string.family_admin_footnote)) }
    }

    if (state.isAdmin) {
      item { GallerySectionHeader(title = stringResource(R.string.family_section_danger)) }
      item {
        FluidListGroup {
          DescribedRow(
            title = stringResource(R.string.family_reset),
            detail = stringResource(R.string.family_reset_detail),
            icon = Icons.Rounded.RestartAlt,
            tone = FluidTone.Danger,
            onClick = links.openReset,
          )
        }
      }
      // L'azione distruttiva: quando si usa resta in pagina (CG-4).
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
      DescribedRow(
        title = stringResource(R.string.family_rejoin),
        detail = stringResource(R.string.family_rejoin_detail),
        icon = Icons.Rounded.QrCodeScanner,
        tone = FluidTone.Warning,
        onClick = links.openRejoin,
      )
    }
  }
}
