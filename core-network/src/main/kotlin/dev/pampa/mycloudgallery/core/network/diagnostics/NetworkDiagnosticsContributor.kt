package dev.pampa.mycloudgallery.core.network.diagnostics

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagHasher
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.network.R
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomation
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.env.NetworkPathSource
import dev.pampa.mycloudgallery.core.network.home.HomePrefixes
import dev.pampa.mycloudgallery.core.network.policy.NetworkEvaluation
import dev.pampa.mycloudgallery.core.network.policy.PathDecision
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** I codici della Diagnostica di questo modulo, con gli edge case che coprono (il catalogo di P8 li raccoglie). */
object NetworkDiagCodes {
  val PLACE = DiagCode("NET_PLACE", setOf("E-R12", "E-R13"))
  val UNTRUSTED_NETWORK = DiagCode("NET_UNTRUSTED_NETWORK", setOf("E-S10", "E-R16"))
  val VPN_OFF_AWAY = DiagCode("NET_VPN_OFF_AWAY", setOf("E-R3"))
  val FOREIGN_VPN = DiagCode("NET_FOREIGN_VPN", setOf("E-S16"))
  val LOCAL_NETWORK_DENIED = DiagCode("NET_LOCAL_NETWORK_DENIED", setOf("E-R5"))
  val LOCAL_NETWORK = DiagCode("NET_LOCAL_NETWORK", setOf("E-R5"))
  val CAPTIVE_PORTAL = DiagCode("NET_CAPTIVE_PORTAL", setOf("E-R13"))
  val OFFLINE = DiagCode("NET_OFFLINE")
  val NOT_CONFIGURED = DiagCode("NET_NOT_CONFIGURED")
  val VPN_AT_HOME = DiagCode("NET_VPN_AT_HOME")
  val IPV4_ONLY = DiagCode("NET_IPV4_ONLY", setOf("E-R11", "E-R17"))
  val VPN_CONTROL = DiagCode("NET_VPN_CONTROL", setOf("E-P20"))
  val VPN_LOCKDOWN = DiagCode("NET_VPN_LOCKDOWN", setOf("E-R3"))
  val VPN_NOT_CONTROLLABLE = DiagCode("NET_VPN_NOT_CONTROLLABLE", setOf("E-R3"))
  val QUALITY_POOR = DiagCode("NET_QUALITY_POOR", setOf("E-R4", "E-R14"))
  val HOME_FINGERPRINT = DiagCode("NET_HOME_FINGERPRINT", setOf("E-S10", "E-R16"))
  val NAS_TCP = DiagCode("NET_NAS_TCP", setOf("E-R15"))

  val ALL: List<DiagCode> = listOf(
    PLACE, UNTRUSTED_NETWORK, VPN_OFF_AWAY, FOREIGN_VPN, LOCAL_NETWORK_DENIED, LOCAL_NETWORK, CAPTIVE_PORTAL, OFFLINE,
    NOT_CONFIGURED, VPN_AT_HOME, IPV4_ONLY, VPN_CONTROL, VPN_LOCKDOWN, VPN_NOT_CONTROLLABLE, QUALITY_POOR,
    HOME_FINGERPRINT, NAS_TCP,
  )
}

/** Azioni proposte da questo modulo oltre a quelle di `DiagAction` (un'azione sconosciuta si mostra senza tasto). */
object NetworkDiagActions {
  /** Ripetere il riconoscimento della rete di casa (E-R16): porta a Impostazioni → Rete e VPN, dove c'e' la raccolta. La esegue :app. */
  val RECAPTURE_HOME = DiagAction("recapture_home")

  /**
   * Il controllo di WireGuard: consentire il permesso (RE-1) o riattivare il controllo fermo (RE-2). Entrambi stanno in Impostazioni →
   * Rete e VPN: prima la Diagnostica diceva «riattiva il controllo» o «concedi il permesso» senza un posto dove farlo. La esegue :app.
   */
  val OPEN_VPN_CONTROL = DiagAction("open_vpn_control")

  val ALL: List<DiagAction> = listOf(RECAPTURE_HOME, OPEN_VPN_CONTROL)
}

/** Il confronto con l'impronta di casa come valore esportabile (`HomeMatch` e' un'interfaccia sigillata, non un enum). */
enum class HomeMatchKind {
  MATCH,
  WEAK,
  NO_MATCH,
  UNKNOWN,
}

/** L'esito della sonda TCP come valore esportabile. */
enum class ProbeKind {
  OPEN,
  REFUSED,
  TIMED_OUT,
  NO_ROUTE,
}

internal fun HomeMatch.kind(): HomeMatchKind =
  when (this) {
    HomeMatch.Match -> HomeMatchKind.MATCH
    is HomeMatch.Weak -> HomeMatchKind.WEAK
    HomeMatch.NoMatch -> HomeMatchKind.NO_MATCH
    HomeMatch.Unknown -> HomeMatchKind.UNKNOWN
  }

/**
 * Il contributo di `core-network` alla sezione "Rete e NAS" della Diagnostica (docs/fase2-progetto.md 2.6).
 *
 * **Niente dati personali nell'esportazione**, per costruzione: i valori sono solo numeri, si'/no, costanti di enum e
 * impronte HMAC ([DiagHasher]). Gli indirizzi, il dominio e i prefissi ULA non compaiono mai in chiaro: ne entra
 * solo l'impronta, che dice "e' la stessa rete di prima" senza dire quale. Nei dettagli a schermo ([DiagText])
 * non ci sono argomenti.
 *
 * [collect] legge stati gia' in memoria e, se c'e' un NAS e il percorso e' consentito, fa **una sola sonda TCP alla
 * 445** (E-R15): non tocca i dischi.
 *
 * @param nasAddress l'indirizzo del NAS configurato, per la sonda; null se non c'e'.
 */
class NetworkDiagnosticsContributor(
  private val paths: NetworkPathSource,
  private val gate: NasRouteGate,
  private val vpnController: VpnController,
  private val vpnAutomation: VpnAutomation,
  private val probe: ReachabilityProbe,
  private val hasher: DiagHasher,
  private val nasAddress: () -> NasAddress?,
  private val probeTimeout: Duration = 3.seconds,
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.NETWORK_AND_NAS

  override suspend fun collect(): List<DiagItem> {
    val evaluation = paths.evaluateNow()
    val items = ArrayList<DiagItem>()
    items += placeItem(evaluation)
    val decision = evaluation.decision
    if (decision is PathDecision.Denied) items += deniedItem(evaluation, decision.reason)
    if (decision is PathDecision.Allowed && decision.via == Via.HOME_LAN_THROUGH_VPN) {
      items += item(NetworkDiagCodes.VPN_AT_HOME, DiagSeverity.INFO, R.string.net_diag_vpn_at_home, R.string.net_diag_vpn_at_home_detail, DiagAction.OPEN_WIREGUARD)
    }
    items += localNetworkItem(evaluation)
    items += probeAndIpv6Items(evaluation)
    items += vpnControlItems()
    if (evaluation.poor) {
      items += item(NetworkDiagCodes.QUALITY_POOR, DiagSeverity.WARNING, R.string.net_diag_quality_poor, R.string.net_diag_quality_poor_detail)
    }
    items += fingerprintItem(evaluation)
    return items
  }

  // --- Voci ---------------------------------------------------------------------------------------------

  private fun placeItem(e: NetworkEvaluation): DiagItem {
    val s = e.snapshot
    val u = s.underlying
    val export = LinkedHashMap<String, ExportValue>()
    export["place"] = ExportValue.Enum.of(s.place)
    export["transport"] = ExportValue.Enum.of(u?.transport ?: NetTransport.OTHER)
    export["metered"] = ExportValue.Flag(u?.metered == true)
    export["roaming"] = ExportValue.Flag(u?.roaming == true)
    export["validated"] = ExportValue.Flag(u?.validated == true)
    export["captivePortal"] = ExportValue.Flag(u?.captivePortal == true)
    export["ipv6Global"] = ExportValue.Flag(s.ipv6Global)
    export["vpn"] = ExportValue.Enum.of(s.vpn)
    export["home"] = ExportValue.Enum.of(s.home.kind())
    export["weakClues"] = ExportValue.Num(e.weakClues.size.toLong())
    export["vpnClues"] = ExportValue.Num(e.vpnClues.size.toLong())
    when (val decision = e.decision) {
      is PathDecision.Allowed -> {
        export["allowed"] = ExportValue.Flag(true)
        export["via"] = ExportValue.Enum.of(decision.via)
      }
      is PathDecision.Denied -> {
        export["allowed"] = ExportValue.Flag(false)
        export["denyReason"] = ExportValue.Enum.of(decision.reason)
      }
    }
    val (severity, title) = when (s.place) {
      NetPlace.HOME -> DiagSeverity.OK to R.string.net_diag_place_home
      NetPlace.AWAY_VPN_WIFI -> DiagSeverity.OK to R.string.net_diag_place_away_vpn_wifi
      NetPlace.AWAY_VPN_MOBILE -> DiagSeverity.OK to R.string.net_diag_place_away_vpn_mobile
      NetPlace.ROAMING -> DiagSeverity.INFO to R.string.net_diag_place_roaming
      NetPlace.VPN_OFF -> DiagSeverity.WARNING to R.string.net_diag_place_vpn_off
      NetPlace.UNTRUSTED -> DiagSeverity.WARNING to R.string.net_diag_place_untrusted
      NetPlace.OFFLINE -> DiagSeverity.INFO to R.string.net_diag_place_offline
    }
    return DiagItem(NetworkDiagCodes.PLACE, section, severity, title, export = export)
  }

  /** Perche' il percorso e' negato, con il motivo e il rimedio. */
  private fun deniedItem(e: NetworkEvaluation, reason: DenyReason): DiagItem {
    val export = mapOf("allowed" to ExportValue.Flag(false), "denyReason" to ExportValue.Enum.of(reason))
    val lookAlike = e.weakClues.isNotEmpty()
    return when (reason) {
      DenyReason.UNTRUSTED_NETWORK ->
        DiagItem(
          NetworkDiagCodes.UNTRUSTED_NETWORK, section, DiagSeverity.WARNING, R.string.net_diag_untrusted_network,
          DiagText(R.string.net_diag_untrusted_network_detail),
          if (lookAlike) NetworkDiagActions.RECAPTURE_HOME else DiagAction.OPEN_WIREGUARD,
          export + ("lookAlike" to ExportValue.Flag(lookAlike)),
        )
      DenyReason.VPN_OFF_AWAY ->
        DiagItem(NetworkDiagCodes.VPN_OFF_AWAY, section, DiagSeverity.WARNING, R.string.net_diag_vpn_off_away, DiagText(R.string.net_diag_vpn_off_away_detail), DiagAction.OPEN_WIREGUARD, export)
      DenyReason.FOREIGN_VPN ->
        DiagItem(NetworkDiagCodes.FOREIGN_VPN, section, DiagSeverity.WARNING, R.string.net_diag_foreign_vpn, DiagText(R.string.net_diag_foreign_vpn_detail), DiagAction.OPEN_WIREGUARD, export)
      DenyReason.LOCAL_NETWORK_DENIED ->
        DiagItem(NetworkDiagCodes.LOCAL_NETWORK_DENIED, section, DiagSeverity.ERROR, R.string.net_diag_local_network_denied, DiagText(R.string.net_diag_local_network_denied_detail), DiagAction.OPEN_APP_DETAILS, export)
      DenyReason.CAPTIVE_PORTAL ->
        DiagItem(NetworkDiagCodes.CAPTIVE_PORTAL, section, DiagSeverity.WARNING, R.string.net_diag_captive_portal, DiagText(R.string.net_diag_captive_portal_detail), export = export)
      DenyReason.OFFLINE ->
        DiagItem(NetworkDiagCodes.OFFLINE, section, DiagSeverity.INFO, R.string.net_diag_offline, export = export)
      DenyReason.NOT_CONFIGURED ->
        DiagItem(NetworkDiagCodes.NOT_CONFIGURED, section, DiagSeverity.INFO, R.string.net_diag_not_configured, DiagText(R.string.net_diag_not_configured_detail), DiagAction.RESUME_ONBOARDING, export)
    }
  }

  private fun localNetworkItem(e: NetworkEvaluation): DiagItem {
    val access = e.snapshot.localNetwork
    val denied = access == LocalNetworkAccess.DENIED
    return DiagItem(
      code = NetworkDiagCodes.LOCAL_NETWORK,
      section = section,
      severity = if (denied) DiagSeverity.ERROR else DiagSeverity.OK,
      titleRes = if (denied) R.string.net_diag_local_network_denied else R.string.net_diag_local_network_granted,
      action = if (denied) DiagAction.OPEN_APP_DETAILS else null,
      export = mapOf("access" to ExportValue.Enum.of(access)),
    )
  }

  /** Una sola sonda TCP: dice se il NAS risponde e, se non risponde da una rete solo IPv4, la causa probabile (E-R17). */
  private suspend fun probeAndIpv6Items(e: NetworkEvaluation): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    val route = gate.current() as? NasRoute.Allowed
    val nas = nasAddress()
    var probeFailed = false
    if (route != null && nas != null) {
      val result = probe.tcp445(route, nas, probeTimeout)
      val kind = when (result) {
        is ProbeResult.Open -> ProbeKind.OPEN
        ProbeResult.Refused -> ProbeKind.REFUSED
        ProbeResult.TimedOut -> ProbeKind.TIMED_OUT
        ProbeResult.NoRoute -> ProbeKind.NO_ROUTE
      }
      probeFailed = kind == ProbeKind.TIMED_OUT || kind == ProbeKind.NO_ROUTE
      val export = LinkedHashMap<String, ExportValue>()
      export["result"] = ExportValue.Enum.of(kind)
      if (result is ProbeResult.Open) export["elapsed"] = ExportValue.Dur.of(result.elapsed)
      val (severity, title) = when (kind) {
        ProbeKind.OPEN -> DiagSeverity.OK to R.string.net_diag_nas_tcp_open
        ProbeKind.REFUSED -> DiagSeverity.WARNING to R.string.net_diag_nas_tcp_refused
        ProbeKind.TIMED_OUT -> DiagSeverity.ERROR to R.string.net_diag_nas_tcp_timed_out
        ProbeKind.NO_ROUTE -> DiagSeverity.ERROR to R.string.net_diag_nas_tcp_no_route
      }
      items += DiagItem(NetworkDiagCodes.NAS_TCP, section, severity, title, action = if (severity == DiagSeverity.ERROR) DiagAction.RUN_CONNECTION_TEST else null, export = export)
    }
    // E-R11 / E-R17: il FRITZ!Box in CGNAT si raggiunge dalla VPN solo in IPv6. Se la rete sotto e' solo IPv4 e il
    // NAS non risponde (o la VPN e' spenta fuori casa), e' la causa piu' probabile.
    val s = e.snapshot
    val away = s.place == NetPlace.AWAY_VPN_WIFI || s.place == NetPlace.AWAY_VPN_MOBILE || s.place == NetPlace.VPN_OFF
    if (away && s.underlying != null && !s.ipv6Global && (probeFailed || s.place == NetPlace.VPN_OFF)) {
      items += DiagItem(
        code = NetworkDiagCodes.IPV4_ONLY,
        section = section,
        severity = if (probeFailed) DiagSeverity.ERROR else DiagSeverity.WARNING,
        titleRes = R.string.net_diag_ipv4_only,
        detail = DiagText(R.string.net_diag_ipv4_only_detail),
        export = mapOf("vpnUp" to ExportValue.Flag(s.vpn == VpnObservation.HOME_TUNNEL), "nasSilent" to ExportValue.Flag(probeFailed)),
      )
    }
    return items
  }

  private fun vpnControlItems(): List<DiagItem> {
    val capability = vpnController.capability.value
    val status = vpnAutomation.status.value
    val export = mapOf("capability" to ExportValue.Enum.of(capability), "automation" to ExportValue.Enum.of(statusKind(status)))
    val items = ArrayList<DiagItem>()
    val (severity, title, detail, action) = when (capability) {
      VpnCapability.READY -> Quad(DiagSeverity.OK, R.string.net_diag_vpn_control_ready, null, null)
      VpnCapability.WIREGUARD_MISSING -> Quad(DiagSeverity.INFO, R.string.net_diag_vpn_control_missing_app, null, null)
      VpnCapability.PERMISSION_MISSING ->
        Quad(DiagSeverity.INFO, R.string.net_diag_vpn_control_missing_permission, R.string.net_diag_vpn_control_missing_permission_detail, NetworkDiagActions.OPEN_VPN_CONTROL)
      VpnCapability.NOT_CONFIGURED -> Quad(DiagSeverity.INFO, R.string.net_diag_vpn_control_not_configured, null, null)
    }
    items += DiagItem(NetworkDiagCodes.VPN_CONTROL, section, severity, title, detail?.let { DiagText(it) }, action, export)
    if (status is VpnAutomationStatus.Suspended) {
      items += when (status.reason) {
        VpnSuspendReason.LOCKDOWN_SUSPECTED ->
          DiagItem(NetworkDiagCodes.VPN_LOCKDOWN, section, DiagSeverity.WARNING, R.string.net_diag_vpn_lockdown, DiagText(R.string.net_diag_vpn_lockdown_detail), NetworkDiagActions.OPEN_VPN_CONTROL, export)
        VpnSuspendReason.NOT_CONTROLLABLE ->
          DiagItem(NetworkDiagCodes.VPN_NOT_CONTROLLABLE, section, DiagSeverity.WARNING, R.string.net_diag_vpn_not_controllable, DiagText(R.string.net_diag_vpn_not_controllable_detail), NetworkDiagActions.OPEN_VPN_CONTROL, export)
      }
    }
    return items
  }

  private fun fingerprintItem(e: NetworkEvaluation): DiagItem {
    val fp = paths.fingerprintNow()
    val export = LinkedHashMap<String, ExportValue>()
    export["configured"] = ExportValue.Flag(fp != null)
    if (fp != null) {
      val ula = HomePrefixes.ula(fp).map { it.toText() }.sorted()
      export["ulaCount"] = ExportValue.Num(ula.size.toLong())
      ula.take(MAX_HASHED).forEachIndexed { i, prefix -> export["ula$i"] = hasher.hash(prefix) }
      fp.gatewayV4?.let { export["gateway"] = hasher.hash(it) }
      fp.dnsDomain?.let { export["domain"] = hasher.hash(it) }
    }
    // Per confrontare "la rete di adesso" con "la rete di casa" senza dire quale e': l'impronta del suo ULA.
    val lan = e.lanNetworkId?.let { paths.observationNow().byId(it) }
    lan?.ulaPrefixes?.map { it.toText() }?.sorted()?.firstOrNull()?.let { export["currentUla0"] = hasher.hash(it) }
    export["currentHasUla"] = ExportValue.Flag(lan?.ulaPrefixes?.isNotEmpty() == true)
    return DiagItem(
      code = NetworkDiagCodes.HOME_FINGERPRINT,
      section = section,
      severity = if (fp != null) DiagSeverity.OK else DiagSeverity.INFO,
      titleRes = if (fp != null) R.string.net_diag_home_fingerprint_set else R.string.net_diag_home_fingerprint_missing,
      export = export,
    )
  }

  // --- Utilita' -----------------------------------------------------------------------------------------

  private fun item(code: DiagCode, severity: DiagSeverity, title: Int, detail: Int? = null, action: DiagAction? = null) =
    DiagItem(code, section, severity, title, detail?.let { DiagText(it) }, action)

  private enum class StatusKind {
    OFF,
    IDLE,
    WAITING_FOR_IDLE_SESSION,
    COMMANDING,
    DONE,
    SUSPENDED,
  }

  private fun statusKind(status: VpnAutomationStatus): StatusKind =
    when (status) {
      VpnAutomationStatus.Off -> StatusKind.OFF
      VpnAutomationStatus.Idle -> StatusKind.IDLE
      is VpnAutomationStatus.WaitingForIdleSession -> StatusKind.WAITING_FOR_IDLE_SESSION
      is VpnAutomationStatus.Commanding -> StatusKind.COMMANDING
      is VpnAutomationStatus.Done -> StatusKind.DONE
      is VpnAutomationStatus.Suspended -> StatusKind.SUSPENDED
    }

  private data class Quad(val severity: DiagSeverity, val title: Int, val detail: Int?, val action: DiagAction?)

  private companion object {
    const val MAX_HASHED = 4
  }
}
