package dev.pampa.mycloudgallery.feature.settings.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
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
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.getValue

/** L'account come lo mostra la pagina: utente e indirizzo (mai la password, che non esce dal Keystore). */
internal data class AccountUi(
  val user: String,
  val address: String,
  val serverName: String?,
  val guidPinned: Boolean,
)

internal data class NasAccountUiState(
  /** La porta [NasAccountSettings] e' legata? Senza, la pagina dice che la funzione non e' ancora collegata. */
  val available: Boolean,
  val account: AccountUi?,
  val connection: NasConnState,
  val breaker: BreakerState,
) {
  /** L'interruttore di sicurezza e' aperto: nessun tentativo parte finche' non si salva una password nuova. */
  val blockedBy: AuthBlockReason? get() = (breaker as? BreakerState.Open)?.reason
}

@HiltViewModel
internal class NasAccountViewModel @Inject constructor(
  accountSettings: Optional<NasAccountSettings>,
  sessions: NasSessionManager,
  breaker: AuthCircuitBreaker,
) : ViewModel() {

  private val accounts: NasAccountSettings? = accountSettings.orElse(null)

  val state: StateFlow<NasAccountUiState> = combine(
    accounts?.account ?: flowOf(null),
    sessions.state,
    breaker.state,
  ) { account, connection, breakerState ->
    NasAccountUiState(
      available = accounts != null,
      account = account?.let(::toUi),
      connection = connection,
      breaker = breakerState,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = NasAccountUiState(
      available = accounts != null,
      account = accounts?.account?.value?.let(::toUi),
      connection = sessions.state.value,
      breaker = breaker.state.value,
    ),
  )

  private fun toUi(account: NasAccount) = AccountUi(
    user = account.user,
    address = account.address.ip,
    serverName = account.address.name,
    guidPinned = account.pinnedGuid != null,
  )

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

internal class NasAccountLinks(
  val onBack: () -> Unit,
  val openPassword: () -> Unit,
  val openAddress: () -> Unit,
) {
  companion object {
    val None = NasAccountLinks({}, {}, {})
  }
}

@Composable
internal fun NasAccountRoute(links: NasAccountLinks) {
  val viewModel: NasAccountViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  NasAccountScreen(state = state, links = links)
}

/**
 * Impostazioni → Account del NAS: chi e' l'account, dove sta il NAS e come va il collegamento.
 *
 * In cima dice la cosa che conta adesso. Se l'interruttore di sicurezza e' aperto (E-R8, E-S12) la pagina lo
 * dice per primo e porta alla nuova password: **nessun tentativo parte da solo**, perche' il NAS blocca
 * l'account dopo pochi errori.
 */
@Composable
internal fun NasAccountScreen(state: NasAccountUiState, links: NasAccountLinks) {
  val account = state.account
  FluidScreen(
    title = stringResource(R.string.account_title),
    onBack = links.onBack,
    ambient = FluidAmbient(
      tone = if (state.blockedBy != null) FluidHeroTone.Alert else FluidHeroTone.Secondary,
      motif = FluidHeroMotif.Glow,
    ),
  ) {
    if (!state.available || account == null) {
      item {
        FluidEmptyState(
          title = stringResource(if (!state.available) R.string.account_unavailable_title else R.string.account_none_title),
          detail = stringResource(if (!state.available) R.string.account_unavailable_detail else R.string.account_none_detail),
        )
      }
      return@FluidScreen
    }

    item { AccountHero(state = state, onPassword = links.openPassword) }

    item { GallerySectionHeader(title = stringResource(R.string.account_section_account)) }
    item {
      FluidListGroup {
        ValueRow(R.string.account_user, account.user, Icons.Rounded.Person)
        FluidListDivider()
        ValueRow(R.string.account_address, account.address, Icons.Rounded.Dns)
        if (account.serverName != null) {
          FluidListDivider()
          ValueRow(R.string.account_server_name, account.serverName, Icons.Rounded.Cloud)
        }
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.account_section_change)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.account_change_password),
          subtitle = stringResource(
            if (state.blockedBy != null) R.string.account_change_password_needed else R.string.account_change_password_detail,
          ),
          tone = if (state.blockedBy != null) FluidTone.Danger else FluidTone.Primary,
          leading = { RowGlyph(Icons.Rounded.Key) },
          badge = if (state.blockedBy != null) {
            { StatusPill(stringResource(R.string.account_pill_needed), FluidTone.Danger) }
          } else {
            null
          },
          onClick = links.openPassword,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.account_change_address),
          subtitle = stringResource(R.string.account_change_address_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.Dns) },
          onClick = links.openAddress,
        )
      }
    }

    (state.connection as? NasConnState.Ready)?.let { ready ->
      item { GallerySectionHeader(title = stringResource(R.string.account_section_link)) }
      item {
        FluidListGroup {
          ValueRow(
            R.string.account_link_encryption,
            stringResource(if (ready.info.encrypted) R.string.value_yes else R.string.value_no),
            Icons.Rounded.Lock,
          )
          FluidListDivider()
          ValueRow(R.string.account_link_dialect, ready.info.dialect.label(), Icons.Rounded.VpnKey)
          FluidListDivider()
          ValueRow(R.string.account_link_route, stringResource(ready.info.via.labelRes()), Icons.Rounded.Wifi)
          FluidListDivider()
          ValueRow(
            R.string.account_link_identity,
            stringResource(if (account.guidPinned) R.string.account_identity_pinned else R.string.account_identity_unpinned),
            Icons.Rounded.Dns,
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.account_footnote)) }
  }
}

@Composable
private fun AccountHero(state: NasAccountUiState, onPassword: () -> Unit) {
  val blocked = state.blockedBy
  val connection = state.connection
  when {
    blocked != null -> FluidHero(
      tone = FluidHeroTone.Alert,
      motif = FluidHeroMotif.Ripples,
      eyebrow = stringResource(R.string.account_eyebrow),
      value = stringResource(R.string.account_hero_blocked_value),
      title = stringResource(R.string.account_hero_blocked_title),
      description = stringResource(blocked.messageRes()),
      icon = Icons.Rounded.Key,
      urgent = true,
      actionLabel = stringResource(R.string.account_hero_blocked_action),
      onAction = onPassword,
    )
    connection is NasConnState.Ready -> FluidHero(
      tone = FluidHeroTone.PrimaryToSecondary,
      motif = FluidHeroMotif.Dots,
      eyebrow = stringResource(R.string.account_eyebrow),
      value = stringResource(R.string.account_hero_ready_value),
      title = stringResource(if (connection.info.encrypted) R.string.account_hero_ready_title else R.string.account_hero_unencrypted_title),
      description = stringResource(connection.info.via.heroDetailRes()),
      icon = Icons.Rounded.Cloud,
    )
    connection is NasConnState.Unavailable -> FluidHero(
      tone = FluidHeroTone.Secondary,
      motif = FluidHeroMotif.Ripples,
      eyebrow = stringResource(R.string.account_eyebrow),
      value = stringResource(R.string.account_hero_unavailable_value),
      title = stringResource(R.string.account_hero_unavailable_title),
      description = stringResource(connection.error.messageRes()),
      icon = Icons.Rounded.CloudOff,
    )
    connection is NasConnState.WakingUp -> FluidHero(
      tone = FluidHeroTone.Tertiary,
      motif = FluidHeroMotif.Ripples,
      eyebrow = stringResource(R.string.account_eyebrow),
      value = stringResource(R.string.account_hero_waking_value),
      title = stringResource(R.string.account_hero_waking_title),
      description = stringResource(R.string.account_hero_waking_detail),
      icon = Icons.Rounded.Cloud,
    )
    else -> FluidHero(
      tone = FluidHeroTone.Secondary,
      motif = FluidHeroMotif.Glow,
      eyebrow = stringResource(R.string.account_eyebrow),
      value = stringResource(R.string.account_hero_idle_value),
      title = stringResource(R.string.account_hero_idle_title),
      description = stringResource(R.string.account_hero_idle_detail),
      icon = Icons.Rounded.Cloud,
    )
  }
}

@Composable
private fun ValueRow(label: Int, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
  FluidListRow(
    title = stringResource(label),
    subtitle = "",
    disclosure = false,
    tone = FluidTone.Neutral,
    leading = { RowGlyph(icon) },
    badge = { FluidRowValue(text = value) },
  )
}

private fun SmbDialect.label(): String = when (this) {
  SmbDialect.SMB_3_1_1 -> "SMB 3.1.1"
  SmbDialect.SMB_3_0_2 -> "SMB 3.0.2"
  SmbDialect.SMB_3_0 -> "SMB 3.0"
  SmbDialect.SMB_2_1 -> "SMB 2.1"
  SmbDialect.SMB_2_0_2 -> "SMB 2.0.2"
  SmbDialect.UNKNOWN -> "?"
}

private fun Via.labelRes(): Int = when (this) {
  Via.HOME_LAN -> R.string.via_home
  Via.HOME_LAN_THROUGH_VPN -> R.string.via_home_vpn
  Via.VPN -> R.string.via_vpn
}

private fun Via.heroDetailRes(): Int = when (this) {
  Via.HOME_LAN -> R.string.account_hero_via_home
  Via.HOME_LAN_THROUGH_VPN -> R.string.account_hero_via_home_vpn
  Via.VPN -> R.string.account_hero_via_vpn
}
