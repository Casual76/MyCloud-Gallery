package dev.pampa.mycloudgallery.core.network.diagnostics

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagHasher
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.env.LiveNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.gate.DefaultNasRouteGate
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkObservationSource
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnAutomation
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnController
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.network.testing.RecordingBindingFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Il contributo alla Diagnostica: voci con il loro codice, e un'esportazione che non porta dati personali. */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDiagnosticsContributorTest {

  private val source = FakeNetworkObservationSource()
  private val store = FakeHomeFingerprintStore(LinkFactsSamples.homeFingerprint())
  private val permission = FakeLocalNetworkPermission()
  private val controller = FakeVpnController()
  private val automation = FakeVpnAutomation()
  private val hasher = DiagHasher(ByteArray(32) { it.toByte() })
  private var nas: NasAddress? = LinkFactsSamples.nas()

  private class CountingProbe(var result: ProbeResult = ProbeResult.Open(12.milliseconds)) : ReachabilityProbe {
    var calls = 0

    override suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult {
      calls++
      return result
    }
  }

  private val probe = CountingProbe()

  private fun TestScope.contributor(vararg networks: LinkFacts): NetworkDiagnosticsContributor {
    source.set(*networks)
    val environment = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })
    val gate = DefaultNasRouteGate(environment, RecordingBindingFactory(), backgroundScope)
    return NetworkDiagnosticsContributor(environment, gate, controller, automation, probe, hasher, { nas })
  }

  private fun List<DiagItem>.byCode(code: String) = firstOrNull { it.code.id == code }

  private fun List<DiagItem>.codes() = map { it.code.id }

  // --- Voci ----------------------------------------------------------------------------------------------

  @Test
  fun acasa_tuttoVa_ilNasRisponde() = runTest {
    val items = contributor(LinkFactsSamples.homeWifi()).collect()

    assertThat(items.map { it.section }.toSet()).containsExactly(DiagSection.NETWORK_AND_NAS)
    val place = items.byCode("NET_PLACE")!!
    assertThat(place.severity).isEqualTo(DiagSeverity.OK)
    assertThat(place.export["place"]).isEqualTo(ExportValue.Enum.of(NetPlace.HOME))
    assertThat(place.export["allowed"]).isEqualTo(ExportValue.Flag(true))
    assertThat(items.byCode("NET_NAS_TCP")!!.severity).isEqualTo(DiagSeverity.OK)
    assertThat(items.filter { it.severity >= DiagSeverity.WARNING }).isEmpty()
  }

  @Test
  fun E_S10_laReteCheSomigliaACasa_dicePerche_eProponeLaNuovaRaccolta() = runTest {
    val items = contributor(LinkFactsSamples.friendWifi()).collect()

    val item = items.byCode("NET_UNTRUSTED_NETWORK")!!
    assertThat(item.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(item.action).isEqualTo(NetworkDiagActions.RECAPTURE_HOME)
    assertThat(item.export["lookAlike"]).isEqualTo(ExportValue.Flag(true))
    assertThat(item.export["denyReason"]).isEqualTo(ExportValue.Enum.of(DenyReason.UNTRUSTED_NETWORK))
    assertThat(items.byCode("NET_PLACE")!!.export["weakClues"]).isEqualTo(ExportValue.Num(3))
    // Percorso negato: nessuna sonda, nessun traffico verso la rete estranea.
    assertThat(probe.calls).isEqualTo(0)
    assertThat(items.byCode("NET_NAS_TCP")).isNull()
  }

  @Test
  fun E_R16_ilCodiceCopreGliEdgeCaseGiusti() {
    assertThat(NetworkDiagCodes.UNTRUSTED_NETWORK.edgeCases).containsAtLeast("E-S10", "E-R16")
    assertThat(NetworkDiagCodes.HOME_FINGERPRINT.edgeCases).contains("E-R16")
  }

  @Test
  fun E_S16_laVpnDiTerzi() = runTest {
    val items = contributor(LinkFactsSamples.foreignVpn(), LinkFactsSamples.cafeWifi()).collect()

    val item = items.byCode("NET_FOREIGN_VPN")!!
    assertThat(item.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(item.action).isEqualTo(DiagAction.OPEN_WIREGUARD)
    assertThat(items.byCode("NET_PLACE")!!.export["vpn"].toString()).contains("FOREIGN_VPN")
  }

  @Test
  fun E_R3_fuoriCasaConLaVpnSpenta_apriWireGuard() = runTest {
    val items = contributor(LinkFactsSamples.cellular()).collect()

    val item = items.byCode("NET_VPN_OFF_AWAY")!!
    assertThat(item.action).isEqualTo(DiagAction.OPEN_WIREGUARD)
    assertThat(item.export["denyReason"]).isEqualTo(ExportValue.Enum.of(DenyReason.VPN_OFF_AWAY))
  }

  @Test
  fun E_R11_E_R17_soloIpv4ConLaVpnEIlNasMuto() = runTest {
    probe.result = ProbeResult.TimedOut

    val items = contributor(LinkFactsSamples.homeVpnFullTunnel(), LinkFactsSamples.cellular(ipv6 = false)).collect()

    val item = items.byCode("NET_IPV4_ONLY")!!
    assertThat(item.severity).isEqualTo(DiagSeverity.ERROR)
    assertThat(item.export["nasSilent"]).isEqualTo(ExportValue.Flag(true))
    assertThat(item.export["vpnUp"]).isEqualTo(ExportValue.Flag(true))
    assertThat(items.byCode("NET_NAS_TCP")!!.severity).isEqualTo(DiagSeverity.ERROR)
  }

  @Test
  fun E_R11_soloIpv4ConLaVpnSpenta_avvisoSenzaSonda() = runTest {
    val ipv4Only = LinkFacts("w", setOf(NetTransport.WIFI), addresses = LinkFactsSamples.cafeWifi().addresses.filter { it.ip.isV4 })

    val items = contributor(ipv4Only).collect()

    assertThat(items.byCode("NET_IPV4_ONLY")!!.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(probe.calls).isEqualTo(0)
  }

  @Test
  fun conIpv6Globale_nessunaDiagnosiIpv4() = runTest {
    probe.result = ProbeResult.TimedOut

    val items = contributor(LinkFactsSamples.homeVpnFullTunnel(), LinkFactsSamples.cellular(ipv6 = true)).collect()

    assertThat(items.byCode("NET_IPV4_ONLY")).isNull()
  }

  @Test
  fun reteLocaleNegata_unErroreConLaSuaAzione() = runTest {
    permission.access = LocalNetworkAccess.DENIED

    val items = contributor(LinkFactsSamples.homeWifi()).collect()

    assertThat(items.byCode("NET_LOCAL_NETWORK_DENIED")!!.severity).isEqualTo(DiagSeverity.ERROR)
    assertThat(items.byCode("NET_LOCAL_NETWORK")!!.action).isEqualTo(DiagAction.OPEN_APP_DETAILS)
    assertThat(probe.calls).isEqualTo(0)
  }

  @Test
  fun vpnAccesaInCasa_dicheEPiuLenta() = runTest {
    val items = contributor(LinkFactsSamples.homeVpn(), LinkFactsSamples.homeWifi()).collect()

    assertThat(items.codes()).contains("NET_VPN_AT_HOME")
  }

  @Test
  fun captivePortalOffline_NessunaConfigurazione() = runTest {
    assertThat(contributor(LinkFactsSamples.cafeWifi(validated = false, captivePortal = true)).collect().codes()).contains("NET_CAPTIVE_PORTAL")
    assertThat(contributor().collect().codes()).contains("NET_OFFLINE")
    store.fingerprint.value = null
    assertThat(contributor(LinkFactsSamples.homeWifi()).collect().codes()).contains("NET_NOT_CONFIGURED")
  }

  @Test
  fun ilControlloDiWireGuard_lacapacitaEIlMotivoDellaSospensione() = runTest {
    controller.capability.value = VpnCapability.PERMISSION_MISSING
    automation.status.value = VpnAutomationStatus.Suspended(VpnSuspendReason.LOCKDOWN_SUSPECTED)

    val items = contributor(LinkFactsSamples.cellular()).collect()

    assertThat(items.byCode("NET_VPN_CONTROL")!!.export["capability"]).isEqualTo(ExportValue.Enum.of(VpnCapability.PERMISSION_MISSING))
    assertThat(items.byCode("NET_VPN_LOCKDOWN")!!.severity).isEqualTo(DiagSeverity.WARNING)

    automation.status.value = VpnAutomationStatus.Suspended(VpnSuspendReason.NOT_CONTROLLABLE)
    assertThat(contributor(LinkFactsSamples.cellular()).collect().codes()).contains("NET_VPN_NOT_CONTROLLABLE")
  }

  @Test
  fun laRetePoor() = runTest {
    val items = contributor(LinkFactsSamples.homeWifi(congested = true)).collect()

    assertThat(items.codes()).contains("NET_QUALITY_POOR")
  }

  @Test
  fun laSondaTcpEUnaSola_esoloSeIlPercorsoEConsentito() = runTest {
    contributor(LinkFactsSamples.homeWifi()).collect()
    assertThat(probe.calls).isEqualTo(1)

    nas = null
    contributor(LinkFactsSamples.homeWifi()).collect()
    assertThat(probe.calls).isEqualTo(1) // senza NAS configurato non sonda
  }

  // --- L'esportazione ------------------------------------------------------------------------------------

  @Test
  fun nessunDatoPersonaleNellEsportazione_soloNumeriEnumEImpronte() = runTest {
    val sensitive = listOf("fd12", "3456", "789a", "192.168", "fritz", "2001:db8", "NasDiProva", "wifi-home", "wifi-friend", "vpn-home")
    val scenarios = listOf(
      arrayOf(LinkFactsSamples.homeWifi()),
      arrayOf(LinkFactsSamples.friendWifi()),
      arrayOf(LinkFactsSamples.homeVpn(), LinkFactsSamples.homeWifi()),
      arrayOf(LinkFactsSamples.homeVpn(), LinkFactsSamples.friendWifi()),
      arrayOf(LinkFactsSamples.foreignVpn(), LinkFactsSamples.cafeWifi()),
      arrayOf(LinkFactsSamples.cellular()),
    )
    for (networks in scenarios) {
      val items = contributor(*networks).collect()
      for (item in items) {
        for ((key, value) in item.export) {
          // Per costruzione il tipo non ha una stringa libera: qui si controlla che nessuna impronta o costante
          // contenga cio' che e' scritto nelle reti.
          assertThat(value is ExportValue.Num || value is ExportValue.Flag || value is ExportValue.Dur || value is ExportValue.Enum || value is ExportValue.Hashed).isTrue()
          for (secret in sensitive) {
            assertThat(value.toString().contains(secret, ignoreCase = true)).isFalse()
            assertThat(key.contains(secret, ignoreCase = true)).isFalse()
          }
        }
        // Neanche nel resto della voce (toString, argomenti del dettaglio).
        for (secret in sensitive) assertThat(item.toString().contains(secret, ignoreCase = true)).isFalse()
        assertThat(item.detail?.args.orEmpty()).isEmpty()
      }
    }
  }

  @Test
  fun leImpronteDiCasaSonoHMAC_stabiliPerLaStessaChiave_diverseSeCambiaLaRete() = runTest {
    val home = contributor(LinkFactsSamples.homeWifi()).collect().byCode("NET_HOME_FINGERPRINT")!!
    val friend = contributor(LinkFactsSamples.friendWifi()).collect().byCode("NET_HOME_FINGERPRINT")!!

    assertThat(home.export["configured"]).isEqualTo(ExportValue.Flag(true))
    assertThat(home.export["ulaCount"]).isEqualTo(ExportValue.Num(1))
    // L'ULA memorizzato e quello della rete attuale a casa hanno la stessa impronta; quello dell'amico no.
    assertThat(home.export["ula0"]).isEqualTo(hasher.hash(LinkFactsSamples.HOME_ULA))
    assertThat(home.export["currentUla0"]).isEqualTo(home.export["ula0"])
    assertThat(friend.export["currentUla0"]).isEqualTo(hasher.hash(LinkFactsSamples.FRIEND_ULA))
    assertThat(friend.export["currentUla0"]).isNotEqualTo(friend.export["ula0"])
    assertThat(home.export["gateway"]).isEqualTo(hasher.hash("192.168.178.1"))
    assertThat(home.export["domain"]).isEqualTo(hasher.hash("fritz.box"))
    // Un'altra chiave, altre impronte: non si confrontano fra esportazioni diverse.
    assertThat(DiagHasher(ByteArray(32) { (it + 1).toByte() }).hash(LinkFactsSamples.HOME_ULA)).isNotEqualTo(home.export["ula0"])
  }

  @Test
  fun senzaImpronta_ilCodiceDiceCheNonCe() = runTest {
    store.fingerprint.value = null

    val item = contributor(LinkFactsSamples.homeWifi()).collect().byCode("NET_HOME_FINGERPRINT")!!

    assertThat(item.export["configured"]).isEqualTo(ExportValue.Flag(false))
    assertThat(item.export).doesNotContainKey("ula0")
  }

  // --- Il catalogo ---------------------------------------------------------------------------------------

  @Test
  fun ilCatalogoDeiCodici_unici_eConGliEdgeCaseDiFase2() {
    val ids = NetworkDiagCodes.ALL.map { it.id }
    assertThat(ids).containsNoDuplicates()

    val covered = NetworkDiagCodes.ALL.flatMap { it.edgeCases }.toSet()
    assertThat(covered).containsAtLeast("E-S10", "E-S16", "E-R3", "E-R4", "E-R11", "E-R13", "E-R14", "E-R15", "E-R16", "E-R17", "E-P20")
  }

  @Test
  fun ogniVoceUsaUnCodiceDelCatalogo() = runTest {
    val all = listOf(
      arrayOf(LinkFactsSamples.homeWifi()),
      arrayOf(LinkFactsSamples.friendWifi()),
      arrayOf(LinkFactsSamples.cellular()),
      arrayOf(LinkFactsSamples.foreignVpn(), LinkFactsSamples.cafeWifi()),
      arrayOf(LinkFactsSamples.homeVpn(), LinkFactsSamples.homeWifi()),
      arrayOf(LinkFactsSamples.cafeWifi(validated = false, captivePortal = true)),
    )
    for (networks in all) {
      for (item in contributor(*networks).collect()) assertThat(NetworkDiagCodes.ALL).contains(item.code)
    }
  }
}
