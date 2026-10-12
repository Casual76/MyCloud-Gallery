package dev.pampa.mycloudgallery.status

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.SessionInfo
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

/** Una riga per riga della tabella del §3.5 (docs/fase2-progetto.md). */
class GlobalStatusTest {

  private val ready = NasConnState.Ready(
    SessionInfo(SmbDialect.SMB_3_1_1, encrypted = true, cipher = "AES-128-GCM", signingRequired = true, serverGuid = "g", nasTimeOffsetMs = 0, via = Via.HOME_LAN),
  )

  @Test
  fun casaENasPronto() {
    assertThat(GlobalStatus.from(Snapshots.home(), ready)).isEqualTo(GlobalStatus.HOME_CONNECTED)
    assertThat(GlobalStatus.from(Snapshots.home(), NasConnState.Idle)).isEqualTo(GlobalStatus.HOME_IDLE)
  }

  @Test
  fun fuoriCasaViaVpn() {
    assertThat(GlobalStatus.from(Snapshots.awayVpnWifi(), ready)).isEqualTo(GlobalStatus.AWAY_VPN_WIFI)
    assertThat(GlobalStatus.from(Snapshots.awayVpnMobile(), NasConnState.Idle)).isEqualTo(GlobalStatus.AWAY_VPN_MOBILE)
  }

  @Test
  fun retiDaCuiIlNasNonSiContatta() {
    assertThat(GlobalStatus.from(Snapshots.roaming(), ready)).isEqualTo(GlobalStatus.ROAMING)
    assertThat(GlobalStatus.from(Snapshots.vpnOff(), NasConnState.Idle)).isEqualTo(GlobalStatus.VPN_OFF)
    assertThat(GlobalStatus.from(Snapshots.untrusted(), NasConnState.Idle)).isEqualTo(GlobalStatus.UNTRUSTED)
    assertThat(GlobalStatus.from(Snapshots.foreignVpn(), NasConnState.Idle)).isEqualTo(GlobalStatus.FOREIGN_VPN)
    assertThat(GlobalStatus.from(Snapshots.offline(), NasConnState.Idle)).isEqualTo(GlobalStatus.OFFLINE)
  }

  @Test
  fun statiDelNas() {
    assertThat(GlobalStatus.from(Snapshots.home(), NasConnState.WakingUp(since = 1))).isEqualTo(GlobalStatus.WAKING_NAS)
    assertThat(GlobalStatus.from(Snapshots.awayVpnWifi(), NasConnState.Unavailable(NasError.Timeout)))
      .isEqualTo(GlobalStatus.NAS_UNREACHABLE)
    assertThat(GlobalStatus.from(Snapshots.home(), NasConnState.Unavailable(NasError.LocalNetworkBlocked)))
      .isEqualTo(GlobalStatus.LOCAL_NETWORK_BLOCKED)
  }

  /** P9: "percorso consentito ma la sonda fallisce" (§3.5) e la rete locale negata detta prima (android.md 7.3). */
  @Test
  fun laSondaTcpELaReteLocaleNegataDaUnaSessioneARiposo() {
    assertThat(GlobalStatus.from(Snapshots.home(), NasConnState.Idle, probe = null)).isEqualTo(GlobalStatus.HOME_IDLE)
    assertThat(GlobalStatus.from(Snapshots.home(), NasConnState.Idle, probe = ProbeResult.Open(1.milliseconds))).isEqualTo(GlobalStatus.HOME_IDLE)
    for (failed in listOf(ProbeResult.TimedOut, ProbeResult.Refused, ProbeResult.NoRoute)) {
      assertThat(GlobalStatus.from(Snapshots.home(), NasConnState.Idle, probe = failed)).isEqualTo(GlobalStatus.NAS_UNREACHABLE)
      assertThat(GlobalStatus.from(Snapshots.awayVpnWifi(), NasConnState.Idle, probe = failed)).isEqualTo(GlobalStatus.NAS_UNREACHABLE)
    }
    // Una sessione aperta dice piu' della sonda.
    assertThat(GlobalStatus.from(Snapshots.home(), ready, probe = ProbeResult.TimedOut)).isEqualTo(GlobalStatus.HOME_CONNECTED)
    val denied = Snapshots.home().copy(localNetwork = LocalNetworkAccess.DENIED)
    assertThat(GlobalStatus.from(denied, NasConnState.Idle, probe = ProbeResult.TimedOut)).isEqualTo(GlobalStatus.LOCAL_NETWORK_BLOCKED)
  }

  /** P9: l'interruttore e' persistente; dopo un riavvio la sessione e' a riposo ma la password va reinserita. */
  @Test
  fun lInterruttoreApertoChiedeLaPasswordAncheASessioneFerma() {
    assertThat(GlobalStatus.from(Snapshots.home(), NasConnState.Idle, breakerOpen = true)).isEqualTo(GlobalStatus.NEEDS_PASSWORD)
    assertThat(GlobalStatus.from(Snapshots.vpnOff(), NasConnState.Idle, breakerOpen = true)).isEqualTo(GlobalStatus.NEEDS_PASSWORD)
  }

  @Test
  fun laPasswordVinceSuTutto() {
    val blocked = NasConnState.AuthBlocked(AuthBlockReason.AUTH_FAILED)
    assertThat(GlobalStatus.from(Snapshots.offline(), blocked)).isEqualTo(GlobalStatus.NEEDS_PASSWORD)
    assertThat(GlobalStatus.NEEDS_PASSWORD.action).isEqualTo(StatusAction.ENTER_PASSWORD)
    assertThat(GlobalStatus.VPN_OFF.action).isEqualTo(StatusAction.OPEN_WIREGUARD)
  }
}
