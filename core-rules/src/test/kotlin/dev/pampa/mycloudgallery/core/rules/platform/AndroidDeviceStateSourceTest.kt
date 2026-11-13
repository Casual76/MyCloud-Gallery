package dev.pampa.mycloudgallery.core.rules.platform

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Le letture vere di Android (BatteryManager, PowerManager, ConnectivityManager) su Robolectric. */
@RunWith(RobolectricTestRunner::class)
class AndroidDeviceStateSourceTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val source = AndroidDeviceStateSource(context)

  @Suppress("DEPRECATION")
  private fun sendBattery(plugged: Int, status: Int, level: Int, scale: Int = 100) {
    val intent =
      Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
        .putExtra(BatteryManager.EXTRA_STATUS, status)
        .putExtra(BatteryManager.EXTRA_LEVEL, level)
        .putExtra(BatteryManager.EXTRA_SCALE, scale)
    context.sendStickyBroadcast(intent)
  }

  // --- Alimentazione ------------------------------------------------------------------------------------

  @Test
  fun inCarica_conIlCavo_equellaCheContaPerLeRegole() {
    sendBattery(BatteryManager.BATTERY_PLUGGED_AC, BatteryManager.BATTERY_STATUS_CHARGING, level = 62)

    val power = source.readPower()

    assertThat(power.pluggedIn).isTrue()
    assertThat(power.levelPercent).isEqualTo(62)
    assertThat(power.chargingPaused).isFalse()
  }

  @Test
  fun caricaFermaAll80ConCavoCollegato_resta_inCarica() {
    // Protezione batteria Samsung: status NOT_CHARGING con l'USB collegato (docs/android.md 2.2 bis).
    sendBattery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_NOT_CHARGING, level = 80)

    val power = source.readPower()

    assertThat(power.pluggedIn).isTrue()
    assertThat(power.chargingPaused).isTrue()
    assertThat(power.levelPercent).isEqualTo(80)
  }

  @Test
  fun aBatteria() {
    sendBattery(plugged = 0, status = BatteryManager.BATTERY_STATUS_DISCHARGING, level = 17)

    val power = source.readPower()

    assertThat(power.pluggedIn).isFalse()
    assertThat(power.chargingPaused).isFalse()
    assertThat(power.levelPercent).isEqualTo(17)
  }

  @Test
  fun livelloSuUnaScalaDiversa() {
    sendBattery(plugged = 0, status = BatteryManager.BATTERY_STATUS_DISCHARGING, level = 3, scale = 5)

    assertThat(source.readPower().levelPercent).isEqualTo(60)
  }

  // --- Risparmio energetico e temperatura -----------------------------------------------------------------

  @Test
  fun risparmioEnergetico() {
    val powerManager = context.getSystemService(PowerManager::class.java)

    shadowOf(powerManager).setIsPowerSaveMode(false)
    assertThat(source.readPower().powerSaveMode).isFalse()

    shadowOf(powerManager).setIsPowerSaveMode(true)
    assertThat(source.readPower().powerSaveMode).isTrue()
  }

  @Test
  fun statoTermico() {
    val powerManager = context.getSystemService(PowerManager::class.java)

    shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
    assertThat(source.readPower().thermalStatus).isEqualTo(ThermalStatus.NONE)

    shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
    assertThat(source.readPower().thermalStatus).isEqualTo(ThermalStatus.SEVERE)
  }

  // --- Rete -----------------------------------------------------------------------------------------------

  private fun connectivity() = context.getSystemService(ConnectivityManager::class.java)

  private fun setActiveCapabilities(transports: List<Int>, capabilities: List<Int>) {
    val cm = connectivity()
    val caps = ShadowNetworkCapabilities.newInstance()
    val shadow = shadowOf(caps)
    transports.forEach { shadow.addTransportType(it) }
    capabilities.forEach { shadow.addCapability(it) }
    shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
  }

  @Test
  fun wifiNonAConsumo() {
    setActiveCapabilities(
      transports = listOf(NetworkCapabilities.TRANSPORT_WIFI),
      capabilities =
        listOf(
          NetworkCapabilities.NET_CAPABILITY_INTERNET,
          NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
          NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
          NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        ),
    )

    val network = source.readNetwork()

    assertThat(network.transport).isEqualTo(NetworkTransport.WIFI)
    assertThat(network.metered).isFalse()
    assertThat(network.roaming).isFalse()
    assertThat(network.validated).isTrue()
    assertThat(network.viaVpn).isFalse()
    assertThat(network.isHome).isFalse() // lo decide il segnale della Fase 2, non Android
  }

  @Test
  fun wifiConVpn() {
    setActiveCapabilities(
      transports = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN),
      capabilities =
        listOf(
          NetworkCapabilities.NET_CAPABILITY_INTERNET,
          NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
          NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
          NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        ),
    )

    val network = source.readNetwork()

    assertThat(network.transport).isEqualTo(NetworkTransport.WIFI)
    assertThat(network.viaVpn).isTrue()
  }

  @Test
  fun datiMobiliInRoaming() {
    setActiveCapabilities(
      transports = listOf(NetworkCapabilities.TRANSPORT_CELLULAR),
      capabilities = listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )

    val network = source.readNetwork()

    assertThat(network.transport).isEqualTo(NetworkTransport.CELLULAR)
    assertThat(network.metered).isTrue()
    assertThat(network.roaming).isTrue() // manca NOT_ROAMING
  }

  @Test
  fun wifiSenzaValidazione_paginaDiAccesso() {
    setActiveCapabilities(
      transports = listOf(NetworkCapabilities.TRANSPORT_WIFI),
      capabilities = listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )

    assertThat(source.readNetwork().validated).isFalse()
  }

  @Test
  fun nessunaReteAttiva_eOffline() {
    shadowOf(connectivity()).setActiveNetworkInfo(null)

    assertThat(source.readNetwork()).isEqualTo(NetworkState.OFFLINE)
  }

  @Test
  fun lettureCompleteNonLanciano() {
    sendBattery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_NOT_CHARGING, level = 80)

    val reading = source.read()

    assertThat(reading.power.pluggedIn).isTrue()
  }

  // --- Acquisizione parziale (XS3-3) --------------------------------------------------------------------

  /**
   * Una registrazione che rifiuta **dopo** quella del receiver non lascia il receiver acceso: l'errore arriva a chi raccoglie, e il receiver registrato prima e' stato tolto.
   * Mutazione: togliere il rilascio nel ramo dell'errore (il receiver resta registrato senza nessuno che lo tolga).
   */
  @Test
  fun unaRegistrazioneCheRifiutaNonLasciaAcceseQuelleGiaFatte() = kotlinx.coroutines.runBlocking<Unit> {
    val app = context.applicationContext as android.app.Application
    val failing = AndroidDeviceStateSource(context) { _, _, _ -> throw IllegalStateException("registrazione rifiutata") }
    val before = shadowOf(app).registeredReceivers.size

    val failure = runCatching { failing.changes().first() }.exceptionOrNull()

    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    assertThat(shadowOf(app).registeredReceivers.size).isEqualTo(before)
  }

  /** Con tutte le registrazioni riuscite, chi smette di raccogliere le toglie tutte (il rilascio di sempre). */
  @Test
  fun chiSmetteDiRaccoglierePulisceTutto() = kotlinx.coroutines.runBlocking<Unit> {
    val app = context.applicationContext as android.app.Application
    val ok = AndroidDeviceStateSource(context) { _, _, _ -> Unit }
    val before = shadowOf(app).registeredReceivers.size
    val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { ok.changes().collect { } }
    assertThat(shadowOf(app).registeredReceivers.size).isEqualTo(before + 1)
    job.cancel()
    job.join()
    assertThat(shadowOf(app).registeredReceivers.size).isEqualTo(before)
  }
}
