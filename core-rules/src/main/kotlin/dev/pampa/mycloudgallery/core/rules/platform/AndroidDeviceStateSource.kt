package dev.pampa.mycloudgallery.core.rules.platform

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.rules.DeviceReading
import dev.pampa.mycloudgallery.core.rules.DeviceStateSource
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.PowerState
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Le letture vere del dispositivo (piano 1.6, adattatori Android):
 *
 *  - **Alimentazione**: `ACTION_BATTERY_CHANGED` (broadcast "sticky", letto senza registrare nulla) con
 *    `EXTRA_PLUGGED`, non lo stato di carica.
 *  - **Risparmio energetico e temperatura**: `PowerManager.isPowerSaveMode`, `getCurrentThermalStatus` e
 *    `getThermalHeadroom` (al massimo una chiamata ogni 10 secondi, come vuole Android: nel frattempo
 *    vale l'ultima lettura).
 *  - **Rete**: `ConnectivityManager` sulla rete predefinita (quella davvero usata, E-R12): trasporti,
 *    VPN, `NOT_METERED`, `NOT_ROAMING`, `VALIDATED`.
 *
 * **Permesso**: leggere la rete richiede `ACCESS_NETWORK_STATE`. Questo modulo NON lo dichiara di
 * proposito: ogni permesso nuovo dell'app e' una decisione di sicurezza (docs/sicurezza.md §4-bis e
 * `releaseManifestPolicy`), quindi lo dichiara `:app`. Senza il permesso la rete risulta OFFLINE e il
 * lavoro in background non parte: fallisce in modo sicuro.
 */
class AndroidDeviceStateSource(
  context: Context,
  /** XS3-3: come si registra l'ascoltatore termico (di serie quello di sistema); le prove ne danno uno che rifiuta, per provare che le altre registrazioni si tolgono. */
  private val addThermalListener: (PowerManager, Executor, PowerManager.OnThermalStatusChangedListener) -> Unit =
    { pm, executor, listener -> pm.addThermalStatusListener(executor, listener) },
) : DeviceStateSource {

  private val appContext: Context = context.applicationContext ?: context
  private val powerManager: PowerManager? = appContext.getSystemService(PowerManager::class.java)
  private val connectivity: ConnectivityManager? =
    appContext.getSystemService(ConnectivityManager::class.java)

  private var headroomReadAtMillis: Long = NEVER
  private var lastHeadroom: Float? = null

  override fun read(): DeviceReading = DeviceReading(readPower(), readNetwork())

  // --- Alimentazione, risparmio energetico, temperatura -------------------------------------------

  internal fun readPower(): PowerState {
    val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

    val pm = powerManager
    return PowerState(
      pluggedIn = AndroidMappers.isPluggedIn(plugged),
      levelPercent = AndroidMappers.levelPercent(level, scale),
      chargingPaused = AndroidMappers.isChargingPaused(plugged, status),
      powerSaveMode = pm?.isPowerSaveMode ?: false,
      thermalStatus = AndroidMappers.thermalStatus(pm?.currentThermalStatus ?: 0),
      thermalHeadroom = readHeadroom(pm),
    )
  }

  @Synchronized
  private fun readHeadroom(pm: PowerManager?): Float? {
    if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val now = SystemClock.elapsedRealtime()
    if (headroomReadAtMillis == NEVER || now - headroomReadAtMillis >= HEADROOM_MIN_INTERVAL_MILLIS) {
      headroomReadAtMillis = now
      // Android risponde NaN se non supportato o se chiamato troppo spesso: in quel caso si tiene la
      // lettura precedente invece di perdere l'informazione.
      AndroidMappers.thermalHeadroom(pm.getThermalHeadroom(HEADROOM_FORECAST_SECONDS))?.let {
        lastHeadroom = it
      }
    }
    return lastHeadroom
  }

  // --- Rete -----------------------------------------------------------------------------------------

  // ACCESS_NETWORK_STATE lo dichiara :app (vedi la documentazione della classe).
  @SuppressLint("MissingPermission")
  internal fun readNetwork(): NetworkState {
    val cm = connectivity ?: return NetworkState.OFFLINE
    return try {
      val active = cm.activeNetwork ?: return NetworkState.OFFLINE
      val caps = cm.getNetworkCapabilities(active) ?: return NetworkState.OFFLINE
      val flags = flagsOf(caps)
      val underlying =
        if (flags.vpn && !flags.hasUnderlyingTransport) underlyingOf(cm, active, flags.validated) else null
      AndroidMappers.network(flags, underlying)
    } catch (_: SecurityException) {
      // Manca ACCESS_NETWORK_STATE: nel dubbio, offline.
      NetworkState.OFFLINE
    }
  }

  /** Una VPN senza trasporto proprio: cerca la rete reale (non VPN, con internet) su cui poggia. */
  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION") // getAllNetworks e' deprecato ma resta l'unico modo con un solo valore letto.
  private fun underlyingOf(cm: ConnectivityManager, vpn: Network, validated: Boolean): AndroidMappers.CapabilityFlags? =
    cm.allNetworks
      .asSequence()
      .filter { it != vpn }
      .mapNotNull { network -> cm.getNetworkCapabilities(network) }
      .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
      .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
      .map { flagsOf(it) }
      .sortedByDescending { it.validated }
      .firstOrNull()
      ?.copy(validated = validated)

  private fun flagsOf(caps: NetworkCapabilities): AndroidMappers.CapabilityFlags {
    val notMetered =
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
    return AndroidMappers.CapabilityFlags(
      wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
      cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
      ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
      vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
      notMetered = notMetered,
      notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
      validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
  }

  // --- Cambi ----------------------------------------------------------------------------------------

  @SuppressLint("MissingPermission")
  override fun changes(): Flow<Unit> =
    callbackFlow {
        val notify = { trySend(Unit) }

        val receiver =
          object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
              notify()
            }
          }
        // XS3-3: ogni registrazione si segna **dopo** che e' riuscita e si toglie con lo stesso conto, anche se una successiva fallisce: una registrazione che
        // rifiuta (il servizio termico, la rete) lasciava prima il receiver acceso, senza nessuno che lo togliesse.
        var receiverRegistered = false
        var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null

        fun release() {
          if (receiverRegistered) {
            receiverRegistered = false
            try {
              appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
              // Gia' tolto.
            }
          }
          thermalListener?.let { listener ->
            thermalListener = null
            try {
              powerManager?.removeThermalStatusListener(listener)
            } catch (_: RuntimeException) {
              // Il servizio non c'e' piu': non c'e' niente da togliere.
            }
          }
          networkCallback?.let { callback ->
            networkCallback = null
            try {
              connectivity?.unregisterNetworkCallback(callback)
            } catch (_: RuntimeException) {
              // Gia' tolta.
            }
          }
        }

        try {
          // Solo broadcast di sistema: non servono permessi, e il flag evita che altre app possano
          // inviarcene di finti.
          val filter =
            IntentFilter().apply {
              addAction(Intent.ACTION_BATTERY_CHANGED)
              addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
          } else {
            appContext.registerReceiver(receiver, filter)
          }
          receiverRegistered = true

          val directExecutor = Executor { it.run() }
          val listener = PowerManager.OnThermalStatusChangedListener { notify() }
          if (powerManager != null) {
            addThermalListener(powerManager, directExecutor, listener)
            thermalListener = listener
          }

          val callback =
            object : ConnectivityManager.NetworkCallback() {
              override fun onAvailable(network: Network) {
                notify()
              }

              override fun onLost(network: Network) {
                notify()
              }

              override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                notify()
              }
            }
          try {
            if (connectivity != null) {
              connectivity.registerDefaultNetworkCallback(callback)
              networkCallback = callback
            }
          } catch (_: SecurityException) {
            // senza ACCESS_NETWORK_STATE la rete non si osserva (e risulta offline)
          }
        } catch (t: Throwable) {
          release()
          throw t
        }

        awaitClose { release() }
      }
      .conflate()

  private companion object {
    const val NEVER = Long.MIN_VALUE

    /** Android accetta una chiamata a `getThermalHeadroom` ogni 10 secondi al massimo. */
    const val HEADROOM_MIN_INTERVAL_MILLIS = 10_000L

    /** Quanto avanti guardare: un valore breve, per reagire al calore di adesso. */
    const val HEADROOM_FORECAST_SECONDS = 10
  }
}
