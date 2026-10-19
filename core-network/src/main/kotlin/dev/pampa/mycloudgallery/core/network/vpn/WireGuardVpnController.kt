package dev.pampa.mycloudgallery.core.network.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.contract.VpnSkipReason
import dev.pampa.mycloudgallery.core.network.contract.WireGuardPermission
import dev.pampa.mycloudgallery.core.network.contract.WireGuardTunnelNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Cio' che serve di Android per parlare con WireGuard. Dietro un'interfaccia perche' la logica del controllo
 * (capacita', verifica osservata, ripristino) si prova su JVM, e la parte Android ([AndroidWireGuardPlatform]) e'
 * sottile.
 */
interface WireGuardPlatform {
  /** L'app WireGuard e' installata (e visibile: serve il `<queries>` del manifest). */
  fun isInstalled(): Boolean

  /** L'app ha il permesso `CONTROL_TUNNELS` di WireGuard (e' "dangerous": lo concede l'utente a runtime). */
  fun hasControlPermission(): Boolean

  /** Manda l'intent di controllo. Non c'e' risposta: WireGuard non risponde al mittente. */
  fun sendTunnelCommand(up: Boolean, tunnelName: String)

  /** L'intent che apre WireGuard, o null se non c'e'. */
  fun launchIntent(): Intent?
}

/**
 * I veri intent di WireGuard (docs/research/smb-vpn.md §3.1): un broadcast **esplicito** (`setPackage`) con
 * l'azione `com.wireguard.android.action.SET_TUNNEL_UP` o `SET_TUNNEL_DOWN` e il nome del tunnel nell'extra
 * `tunnel`. Il ricevitore di WireGuard e' protetto dal permesso `CONTROL_TUNNELS`, che il sistema controlla alla
 * consegna: senza, il broadcast non arriva. Con "Allow remote control apps" spento in WireGuard l'intent e'
 * ignorato senza avviso: l'unico modo di accorgersene e' non vedere cambiare la rete ([WireGuardVpnController]).
 */
class AndroidWireGuardPlatform(
  context: Context,
  private val wireGuardPackage: String = WIREGUARD_PACKAGE,
) : WireGuardPlatform {

  private val context: Context = context.applicationContext ?: context

  override fun isInstalled(): Boolean =
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(wireGuardPackage, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(wireGuardPackage, 0)
      }
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

  override fun hasControlPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, "$wireGuardPackage.permission.CONTROL_TUNNELS") == PackageManager.PERMISSION_GRANTED

  override fun sendTunnelCommand(up: Boolean, tunnelName: String) {
    require(WireGuardTunnelNames.isValid(tunnelName)) { "nome del tunnel non valido" }
    val intent = Intent(if (up) ACTION_UP else ACTION_DOWN).setPackage(wireGuardPackage).putExtra(EXTRA_TUNNEL, tunnelName)
    context.sendBroadcast(intent)
  }

  override fun launchIntent(): Intent? =
    context.packageManager.getLaunchIntentForPackage(wireGuardPackage)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  companion object {
    const val WIREGUARD_PACKAGE = WireGuardPermission.PACKAGE
    const val ACTION_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
    const val ACTION_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
    const val EXTRA_TUNNEL = "tunnel"
  }
}

/**
 * Il controllo di WireGuard (§2.4), "progettato per fallire bene": ogni comando ha una verifica osservata.
 *
 * - **Capacita'**: app mancante -> permesso mancante -> nome del tunnel mancante -> pronto. Si rilegge con
 *   [refresh] (al ritorno in primo piano, dopo la richiesta del permesso o l'installazione di WireGuard) e a ogni
 *   comando.
 * - **Verifica**: "VPN accesa" non si dice perche' l'intent e' partito. Dopo `UP` entro il tempo deve comparire una
 *   VPN **di casa** ([VpnObservation.HOME_TUNNEL]); dopo `DOWN` non deve piu' esserci nessuna VPN **e** la rete
 *   sottostante deve risultare validata. Senza il primo cambiamento: [VpnCommandResult.NotObserved]; senza il
 *   secondo: [VpnCommandResult.LostConnectivity].
 * - **Ripristino**: se spegnere il tunnel toglie la rete (probabile "VPN sempre attiva con blocco", che da fuori
 *   non si vede), il controllore rimanda **subito** `UP` prima di restituire `LostConnectivity`: il telefono non
 *   resta mai senza rete per colpa dell'app.
 * - Un solo comando alla volta (un [Mutex]); un tunnel che e' gia' nello stato chiesto non riceve nessun intent.
 *
 * Non applica `VpnControlConfig.enabled`: quello e' dell'automazione. La prova guidata del passo 11 comanda il
 * tunnel anche prima di abilitare il controllo.
 */
class WireGuardVpnController(
  private val platform: WireGuardPlatform,
  private val configStore: VpnControlConfigStore,
  private val environment: NetworkEnvironment,
  scope: CoroutineScope? = null,
) : VpnController {

  private val mutex = Mutex()
  private val state = MutableStateFlow(computeCapability())

  override val capability: StateFlow<VpnCapability> = state

  init {
    scope?.launch { configStore.config.collect { refresh() } }
  }

  /** Rilegge installazione e permesso. */
  override fun refresh() {
    state.value = computeCapability()
  }

  private fun computeCapability(): VpnCapability =
    when {
      !platform.isInstalled() -> VpnCapability.WIREGUARD_MISSING
      !platform.hasControlPermission() -> VpnCapability.PERMISSION_MISSING
      configStore.config.value.tunnelName.isNullOrBlank() -> VpnCapability.NOT_CONFIGURED
      else -> VpnCapability.READY
    }

  override fun openWireGuardIntent(): Intent? = platform.launchIntent()

  override suspend fun setTunnel(up: Boolean, timeout: Duration): VpnCommandResult =
    mutex.withLock {
      refresh()
      when (state.value) {
        VpnCapability.WIREGUARD_MISSING -> return@withLock VpnCommandResult.Skipped(VpnSkipReason.WIREGUARD_MISSING)
        VpnCapability.PERMISSION_MISSING -> return@withLock VpnCommandResult.Skipped(VpnSkipReason.PERMISSION_MISSING)
        VpnCapability.NOT_CONFIGURED -> return@withLock VpnCommandResult.Skipped(VpnSkipReason.NOT_CONFIGURED)
        VpnCapability.READY -> Unit
      }
      val tunnel = configStore.config.value.tunnelName ?: return@withLock VpnCommandResult.Skipped(VpnSkipReason.NOT_CONFIGURED)

      val before = environment.snapshot.value.vpn
      // Un tunnel di casa gia' acceso (o gia' spento): nessun intent. Una VPN estranea non e' la nostra: non la
      // si tocca, e non si finge di averla spenta.
      if (up && before == VpnObservation.HOME_TUNNEL) return@withLock VpnCommandResult.Skipped(VpnSkipReason.ALREADY_IN_STATE)
      if (!up && before != VpnObservation.HOME_TUNNEL) return@withLock VpnCommandResult.Skipped(VpnSkipReason.ALREADY_IN_STATE)

      platform.sendTunnelCommand(up, tunnel)
      if (up) verifyUp(timeout) else verifyDown(timeout, tunnel)
    }

  private suspend fun verifyUp(timeout: Duration): VpnCommandResult {
    val seen = withTimeoutOrNull(timeout) { environment.snapshot.first { it.vpn == VpnObservation.HOME_TUNNEL } }
    return if (seen != null) VpnCommandResult.Confirmed else VpnCommandResult.NotObserved
  }

  private suspend fun verifyDown(timeout: Duration, tunnel: String): VpnCommandResult {
    var tunnelGone = false
    val ok = withTimeoutOrNull(timeout) {
      environment.snapshot.first { it.vpn == VpnObservation.NONE }
      tunnelGone = true
      environment.snapshot.first { it.vpn == VpnObservation.NONE && it.underlying?.let { u -> u.validated && !u.captivePortal } == true }
      true
    }
    return when {
      ok == true -> VpnCommandResult.Confirmed
      tunnelGone -> {
        // Il tunnel e' sceso ma la rete no: si riaccende subito.
        platform.sendTunnelCommand(up = true, tunnelName = tunnel)
        VpnCommandResult.LostConnectivity
      }
      else -> VpnCommandResult.NotObserved
    }
  }
}
