package dev.pampa.mycloudgallery.feature.onboarding

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.testing.FakeAdminRecovery
import dev.pampa.mycloudgallery.core.family.testing.FakeJoinFlow
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.testing.FakeQrPayloadCodec
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnController
import dev.pampa.mycloudgallery.core.network.testing.InMemoryVpnControlConfigStore
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.rules.platform.health.testing.FakeBackgroundHealthReader
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.feature.onboarding.join.ScannerSlot
import dev.pampa.mycloudgallery.feature.onboarding.platform.OnboardingPlatform
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionKind
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionState
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionsSnapshot

/** I finti di tutto cio' che un [OnboardingViewModel] usa: ogni test li pilota e poi li guarda. */
internal class TestEnv(
  initial: OnboardingProgress = OnboardingProgress.START,
) {
  val coordinator = FakeOnboardingCoordinator(initial)
  val join = FakeJoinFlow()
  val recovery = FakeAdminRecovery()
  val inviteCodec = FakeQrPayloadCodec<FamilyInvite>()
  val familyStateCodec = FakeQrPayloadCodec<FamilyStateQr>()
  val vpn = FakeVpnController()
  val health = FakeBackgroundHealthReader()
  val localNetwork = FakeLocalNetworkPermission()
  val vpnConfig = InMemoryVpnControlConfigStore()
  val platform = FakeOnboardingPlatform()

  fun viewModel(): OnboardingViewModel = OnboardingViewModel(
    coordinator = coordinator,
    joinFlow = join,
    adminRecovery = recovery,
    inviteCodec = inviteCodec,
    familyStateCodec = familyStateCodec,
    vpnController = vpn,
    healthReader = health,
    localNetwork = localNetwork,
    vpnConfig = vpnConfig,
  )
}

/** Android finto: nessun dialogo di sistema, ogni richiesta e ogni "apri" e' registrata. */
internal class FakeOnboardingPlatform : OnboardingPlatform {
  var snapshot: PermissionsSnapshot = PermissionsSnapshot.ALL_GRANTED
  var tablet: Boolean = false
  val opened: MutableList<String> = mutableListOf()
  val requested: MutableList<PermissionKind> = mutableListOf()

  override fun permissions(): PermissionsSnapshot = snapshot

  override fun requestPermission(kind: PermissionKind) {
    requested += kind
    snapshot = snapshot.copy(
      states = snapshot.states + (kind to PermissionState.GRANTED),
      requested = snapshot.requested + kind,
    )
  }

  override fun openAppSettings(): Boolean = opened.add("app").let { true }

  override fun openSecuritySettings(): Boolean = opened.add("security").let { true }

  override fun openWifiSettings(): Boolean = opened.add("wifi").let { true }

  override fun open(intent: Intent): Boolean = opened.add("intent").let { true }

  override fun openBatteryExemption(): Boolean = opened.add("battery").let { true }

  override fun openBackgroundLimits(): Boolean = opened.add("limits").let { true }

  override fun openUnusedAppSettings(): Boolean = opened.add("unused").let { true }

  override fun isTablet(): Boolean = tablet

  companion object {
    fun withoutScreenLock(): FakeOnboardingPlatform = FakeOnboardingPlatform().apply {
      snapshot = PermissionsSnapshot.ALL_GRANTED.copy(screenLock = ScreenLockStatus.NOT_SECURE)
    }
  }
}

/**
 * Lo scanner finto: un riquadro vuoto (una fotocamera non gira su Robolectric) che offre un tasto per "leggere" il
 * testo [next]. Il verdetto ricevuto resta in [verdicts].
 */
internal class FakeScanner(var next: String = "") {
  val verdicts: MutableList<ScanVerdict> = mutableListOf()

  val slot: ScannerSlot = { accept, _, modifier ->
    FakeScannerBox(modifier = modifier, onRead = { verdicts += accept(next) })
  }
}

@Composable
private fun FakeScannerBox(modifier: Modifier, onRead: () -> Unit) {
  androidx.compose.foundation.layout.Box(modifier = modifier) {
    androidx.compose.material3.TextButton(onClick = onRead) {
      androidx.compose.material3.Text(SCAN_BUTTON)
    }
  }
}

internal const val SCAN_BUTTON = "simula lettura"

/** Un invito di prova, completo. */
internal fun sampleInvite(
  user: String = "marta",
  device: String = "Tablet — profilo Mamma",
  expiresAtNas: Long = 1_792_000_000_000L,
): FamilyInvite = FamilyInvite(
  familyId = "famiglia-rossi",
  familyKeyFingerprint = "K7QD2M9XFA4TZ8WC5P3N6R1V",
  minRegistryVersion = 3L,
  nasIp = "192.168.178.58",
  nasName = "WDMyCloudEX2Ultra",
  nasGuid = "7b1f4e0c-2a4d-4a8e-9c1f-0d3e5a7b9c11",
  homeUla = listOf("fd12:3456:789a::/48"),
  commonShare = "Public",
  privateShare = user,
  nasUser = user,
  deviceName = device,
  presetName = "NIGHT",
  expiresAtNas = expiresAtNas,
)

internal fun sampleFamilyState(): FamilyStateQr = FamilyStateQr(
  familyId = "famiglia-rossi",
  familyKeyFingerprint = "K7QD2M9XFA4TZ8WC5P3N6R1V",
  registryVersion = 12L,
  registryHash = "ab12cd34",
)
