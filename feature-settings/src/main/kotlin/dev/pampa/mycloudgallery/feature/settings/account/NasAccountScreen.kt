package dev.pampa.mycloudgallery.feature.settings.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
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
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsStatusHeader

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
 * In cima lo stato e basta (giro 1 della fase I, CU-5): il segno e una o due parole. Se l'interruttore di sicurezza e' aperto (E-R8, E-S12) la
 * testata lo dice e porta alla nuova password: **nessun tentativo parte da solo**, perche' il NAS blocca l'account dopo pochi errori. Il perche' di
 * uno stato, le regole del cambio e la sigla del protocollo stanno dietro la «i».
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

    item { AccountHeader(state = state, onPassword = links.openPassword) }
    accountWhy(state)?.let { why -> item { SettingsInfo(label = stringResource(R.string.account_why), text = stringResource(why)) } }

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
          subtitle = "",
          tone = if (state.blockedBy != null) FluidTone.Danger else FluidTone.Primary,
          leading = { RowTile(Icons.Rounded.Key, status = if (state.blockedBy != null) GalleryIconStatus.Problem else null) },
          leadingTile = false,
          onClick = links.openPassword,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.account_change_address),
          subtitle = "",
          tone = FluidTone.Info,
          leading = { RowTile(Icons.Rounded.Dns) },
          leadingTile = false,
          onClick = links.openAddress,
        )
      }
    }
    item {
      SettingsInfo(
        label = stringResource(R.string.account_change_info),
        text = stringResource(R.string.account_change_password_detail) + ". " + stringResource(R.string.account_change_address_detail) + ". " +
          stringResource(R.string.account_footnote),
      )
    }

    (state.connection as? NasConnState.Ready)?.let { ready ->
      item { GallerySectionHeader(title = stringResource(R.string.account_section_link)) }
      item {
        FluidListGroup {
          ValueRow(
            R.string.account_link_encryption,
            stringResource(if (ready.info.encrypted) R.string.value_yes else R.string.value_no),
            Icons.Rounded.Lock,
            status = if (ready.info.encrypted) GalleryIconStatus.Done else GalleryIconStatus.Attention,
          )
          FluidListDivider()
          ValueRow(R.string.account_link_route, stringResource(ready.info.via.labelRes()), Icons.Rounded.Wifi)
          FluidListDivider()
          ValueRow(
            R.string.account_link_identity,
            stringResource(if (account.guidPinned) R.string.account_identity_pinned else R.string.account_identity_unpinned),
            Icons.Rounded.Dns,
            status = if (account.guidPinned) GalleryIconStatus.Done else null,
          )
        }
      }
      // CU-20: la sigla del protocollo non sta in pagina; chi la cerca la trova dietro la «i».
      item { SettingsInfo(label = stringResource(R.string.account_link_info), text = stringResource(ready.info.dialect.infoRes())) }
    }
  }
}

/** La testata: lo stato del collegamento in una o due parole, con il segno; con la password da rifare, il tasto. */
@Composable
private fun AccountHeader(state: NasAccountUiState, onPassword: () -> Unit) {
  val connection = state.connection
  when {
    state.blockedBy != null -> SettingsStatusHeader(
      icon = Icons.Rounded.Key,
      family = GalleryIconFamily.Security,
      value = stringResource(R.string.account_hero_blocked_title),
      status = GalleryIconStatus.Problem,
      action = {
        FluidButton(
          text = stringResource(R.string.account_hero_blocked_action),
          onClick = onPassword,
          style = FluidButtonStyle.Filled,
          size = FluidButtonSize.Large,
          fillWidth = true,
          leading = { Icon(Icons.Rounded.Key, contentDescription = null) },
        )
      },
    )
    connection is NasConnState.Ready -> SettingsStatusHeader(
      icon = Icons.Rounded.Cloud,
      family = GalleryIconFamily.Backup,
      value = stringResource(if (connection.info.encrypted) R.string.account_hero_ready_value else R.string.account_hero_unencrypted_title),
      status = if (connection.info.encrypted) GalleryIconStatus.Done else GalleryIconStatus.Attention,
    )
    connection is NasConnState.Unavailable -> SettingsStatusHeader(
      icon = Icons.Rounded.CloudOff,
      family = GalleryIconFamily.Backup,
      value = stringResource(R.string.account_hero_unavailable_value),
      status = GalleryIconStatus.Problem,
    )
    connection is NasConnState.WakingUp -> SettingsStatusHeader(
      icon = Icons.Rounded.Cloud,
      family = GalleryIconFamily.Backup,
      value = stringResource(R.string.account_hero_waking_value),
      status = GalleryIconStatus.Running,
    )
    else -> SettingsStatusHeader(
      icon = Icons.Rounded.Cloud,
      family = GalleryIconFamily.Backup,
      value = stringResource(R.string.account_hero_idle_value),
    )
  }
}

/** Il perche' dello stato, dietro la «i»: la password bloccata, il NAS che non risponde, il risveglio. Null quando non c'e' niente da spiegare. */
private fun accountWhy(state: NasAccountUiState): Int? {
  val blocked = state.blockedBy
  val connection = state.connection
  return when {
    blocked != null -> blocked.messageRes()
    connection is NasConnState.Unavailable -> connection.error.messageRes()
    connection is NasConnState.WakingUp -> R.string.account_hero_waking_detail
    else -> null
  }
}

@Composable
private fun ValueRow(label: Int, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, status: GalleryIconStatus? = null) {
  FluidListRow(
    title = stringResource(label),
    subtitle = "",
    disclosure = false,
    tone = FluidTone.Neutral,
    leading = { RowTile(icon, status = status) },
    leadingTile = false,
    badge = { FluidRowValue(text = value) },
  )
}

// Giro 1 della fase I (CU-20): prima «Protocollo di rete: SMB 3.1.1»; la sigla non serve a chi usa l'app, quanto e' recente si'.
private fun SmbDialect.infoRes(): Int = when (this) {
  SmbDialect.SMB_3_1_1 -> R.string.account_link_dialect_newest
  SmbDialect.SMB_3_0_2, SmbDialect.SMB_3_0 -> R.string.account_link_dialect_recent
  SmbDialect.SMB_2_1, SmbDialect.SMB_2_0_2 -> R.string.account_link_dialect_old
  SmbDialect.UNKNOWN -> R.string.account_link_dialect_unknown
}

private fun Via.labelRes(): Int = when (this) {
  Via.HOME_LAN -> R.string.via_home
  Via.HOME_LAN_THROUGH_VPN -> R.string.via_home_vpn
  Via.VPN -> R.string.via_vpn
}
