package dev.pampa.mycloudgallery.feature.settings.network

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingComposeBase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina "Rete e VPN" (verifica funzionale, RE-1, RE-2, RE-5): i tasti che mancavano. Il permesso di WireGuard si chiede con un tasto, e
 * dopo un rifiuto c'e' sempre la via per riprovare; il controllo fermo si riattiva da qui; chi cambia l'impronta vede fin dove vale.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h2400dp-xhdpi")
class NetworkScreenTest : SharingComposeBase() {

  private fun state(
    capability: VpnCapability = VpnCapability.READY,
    automation: VpnAutomationStatus = VpnAutomationStatus.Idle,
    permission: PermissionUi = PermissionUi.IDLE,
    capture: CaptureUi = CaptureUi.Idle,
    admin: Boolean = false,
  ) = NetworkUiState(
    snapshot = Snapshots.home(),
    route = RouteUi.Allowed(Via.HOME_LAN),
    fingerprint = null,
    vpnCapability = capability,
    automation = automation,
    capture = capture,
    canOpenWireGuard = true,
    admin = admin,
    permission = permission,
  )

  private fun page(state: NetworkUiState, actions: NetworkActions) = show { NetworkScreen(state, actions) }

  private fun actions(
    onGrant: () -> Unit = {},
    onSettings: () -> Unit = {},
    onReactivate: () -> Unit = {},
    onRetry: () -> Unit = {},
  ) = NetworkActions(
    onBack = {},
    onCapture = {},
    onConfirmCapture = {},
    onCancelCapture = {},
    onTunnel = {},
    onOpenWireGuard = {},
    onConfigureVpn = {},
    onGrantControl = onGrant,
    onOpenAppSettings = onSettings,
    onReactivate = onReactivate,
    onRetryShare = onRetry,
  )

  @Test
  fun senzaIlPermessoUnTastoApreIlDialogoDiSistema() {
    var asked = 0
    page(state(capability = VpnCapability.PERMISSION_MISSING), actions(onGrant = { asked++ }))

    rule.onNodeWithText(string(R.string.wg_grant)).assertIsDisplayed().performClick()

    assertThat(asked).isEqualTo(1)
    // Senza il permesso le prove a mano non ci sono.
    // Giro 1 della fase I: la VPN di casa e' un interruttore, che senza il permesso non si tocca.
    assertThat(rule.onAllNodes(isToggleable()).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun dopoUnRifiutoSiPuoRiprovareConUnMessaggio() {
    var asked = 0
    page(state(capability = VpnCapability.PERMISSION_MISSING, permission = PermissionUi.DENIED), actions(onGrant = { asked++ }))

    assertThat(has(string(R.string.wg_grant_denied))).isTrue()
    rule.onNodeWithText(string(R.string.wg_grant_retry)).assertIsDisplayed().performClick()

    assertThat(asked).isEqualTo(1)
  }

  @Test
  fun dopoUnRifiutoPerSempreSiApreLaSchedaDellApp() {
    var settings = 0
    var asked = 0
    page(
      state(capability = VpnCapability.PERMISSION_MISSING, permission = PermissionUi.DENIED_FOR_GOOD),
      actions(onGrant = { asked++ }, onSettings = { settings++ }),
    )

    assertThat(has(string(R.string.wg_grant_blocked))).isTrue()
    rule.onNodeWithText(string(R.string.wg_open_app_settings)).assertIsDisplayed().performClick()

    assertThat(settings).isEqualTo(1)
    assertThat(asked).isEqualTo(0)
  }

  @Test
  fun conIlPermessoIlTastoNonCe() {
    page(state(capability = VpnCapability.READY), actions())

    assertThat(has(string(R.string.wg_grant))).isFalse()
    assertThat(rule.onAllNodes(isToggleable()).fetchSemanticsNodes()).hasSize(1)
  }

  @Test
  fun ilControlloFermoSiRiattivaDaQui() {
    var reactivated = 0
    page(state(automation = VpnAutomationStatus.Suspended(VpnSuspendReason.NOT_CONTROLLABLE)), actions(onReactivate = { reactivated++ }))

    rule.onNodeWithText(string(R.string.network_reactivate)).assertIsDisplayed().performClick()

    assertThat(reactivated).isEqualTo(1)
  }

  @Test
  fun seIlControlloNonERFermoNonSiOffreDiRiattivarlo() {
    page(state(automation = VpnAutomationStatus.Idle), actions())

    assertThat(has(string(R.string.network_reactivate))).isFalse()
  }

  @Test
  fun chiCambiaLImprontaVedeFinDoveVale() {
    page(state(capture = CaptureUi.Saved(SaveScope.FAMILY), admin = true), actions())
    assertThat(has(string(R.string.capture_saved_family))).isTrue()
  }

  @Test
  fun unFamiliareNonAdminVedeCheVaSoloSuQuestoTelefono() {
    page(state(capture = CaptureUi.Saved(SaveScope.THIS_PHONE)), actions())

    assertThat(has(string(R.string.capture_saved_phone))).isTrue()
    assertThat(has(string(R.string.capture_saved_family))).isFalse()
  }

  @Test
  fun seLaFirmaDellAdminNonRiescePropone_riprova() {
    var retried = 0
    page(state(capture = CaptureUi.Saved(SaveScope.FAMILY_FAILED), admin = true), actions(onRetry = { retried++ }))

    assertThat(has(string(R.string.capture_saved_failed))).isTrue()
    rule.onNodeWithText(string(R.string.capture_retry)).assertIsDisplayed().performClick()

    assertThat(retried).isEqualTo(1)
  }
}
