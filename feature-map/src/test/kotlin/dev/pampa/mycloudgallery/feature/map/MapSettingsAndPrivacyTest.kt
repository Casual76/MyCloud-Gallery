package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapCameraRules
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapPrivacyNote
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.noop.NoMapSettings
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyController
import dev.pampa.mycloudgallery.feature.map.settings.DataStoreMapSettings
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * Le impostazioni della mappa su DataStore e la pagina Impostazioni -> Mappa (Fase 9, P4; docs/fase9-progetto.md §1.5 `MapPrivacyState`, D24, D29, **risposta Q1 (c) del 27/10**). Le stesse regole della suite di
 * contratto `MapSettingsContract` di core-map (accesa di serie, spegnere e riaccendere, interruttore di build, camera e Wi-Fi) sul file vero, piu' il file rovinato, l'ultima camera illeggibile, il riavvio, e che
 * **la scelta di riaccendere la costruisce solo l'interruttore** (mai un codice da solo, Phase9ArchitectureTest regola 5) e che spegnere ferma gli scaricamenti (E-MAP20).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapSettingsAndPrivacyTest {

  private val tempDirs = ArrayList<File>()

  @After
  fun clean() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempFile(): File = Files.createTempDirectory("mappa").toFile().also(tempDirs::add).resolve("map/settings/map.preferences_pb")

  /** Il DataStore vero su un file, con i suoi scope reali (si aspetta lo stato preciso, mai un'attesa a tempo). */
  private fun withStore(file: File, buildFlag: Boolean = true, block: suspend (DataStoreMapSettings) -> Unit) = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val settings = DataStoreMapSettings.create(file, scope, buildFlag, storeScope = scope)
      withTimeout(TETTO) { settings.loaded() }
      block(settings)
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }

  /**
   * Aspetta lo stato **preciso** di una `StateFlow` delle impostazioni, mai "il valore di adesso": `loaded()` dice che il DataStore ha letto il file, ma le `StateFlow` si
   * aggiornano da un altro raccoglitore (`stateIn` su `Dispatchers.IO`) che sotto carico arriva dopo, e `.value` letto subito e' ancora quello di serie. Il tetto e' solo la
   * rete: un file che non dice mai cio' che si aspetta fa fallire il test con la domanda, non lo lascia appeso.
   */
  private suspend fun <T> StateFlow<T>.until(what: String, predicate: (T) -> Boolean): T = try {
    withTimeout(TETTO) { first(predicate) }
  } catch (e: TimeoutCancellationException) {
    throw AssertionError("Dopo $TETTO la impostazione non e' diventata: $what", e)
  }

  // --- La stessa suite del contratto, sul file vero ----------------------------------------------------------------------------------

  @Test
  fun accesaDiDefault_SenzaPaginaDiConsenso() = withStore(tempFile()) { s ->
    assertThat(s.online.value).isEqualTo(MapOnline.On(byDefault = true))
    assertThat(s.wifiOnly.value).isTrue()
    assertThat(s.lastCamera.value).isNull()
  }

  @Test
  fun spegnereERiaccendere_ESiRicordaFraUnProcessoEL_Altro() {
    val file = tempFile()
    withStore(file) { s ->
      s.disable()
      s.online.until("spenta") { it == MapOnline.Off }
      s.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1_000))
      s.online.until("riaccesa dall'utente") { it == MapOnline.On(byDefault = false) }
      s.disable()
      s.online.until("spenta") { it == MapOnline.Off }
    }
    // Un altro processo (un altro DataStore sullo stesso file) la trova spenta.
    withStore(file) { again -> assertThat(again.online.until("spenta, come l'ha lasciata l'altro processo") { it == MapOnline.Off }).isEqualTo(MapOnline.Off) }
  }

  @Test
  fun senzaLInterruttoreDiBuildNonCe_QualunqueCosaDicaIlFile() {
    val file = tempFile()
    withStore(file) { s ->
      s.disable()
      s.online.until("spenta") { it == MapOnline.Off }
    }
    withStore(file, buildFlag = false) { s ->
      assertThat(s.online.value).isEqualTo(MapOnline.Unavailable)
      s.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1_000))
      s.disable()
      assertThat(s.online.value).isEqualTo(MapOnline.Unavailable)
    }
  }

  @Test
  fun cameraEWifi_SoloInLocale() {
    val file = tempFile()
    val spot = CameraSpot(GeoE6(45_464_200, 9_190_000), 11.5)
    withStore(file) { s ->
      s.saveCamera(spot)
      s.lastCamera.until("l'ultima camera salvata") { it == spot }
      s.setWifiOnly(false)
      s.wifiOnly.until("solo sul Wi-Fi spento") { !it }
    }
    withStore(file) { again ->
      assertThat(again.lastCamera.until("l'ultima camera, come l'ha lasciata l'altro processo") { it == spot }).isEqualTo(spot)
      assertThat(again.wifiOnly.until("solo sul Wi-Fi spento, come l'ha lasciato l'altro processo") { !it }).isFalse()
      again.saveCamera(null)
      again.lastCamera.until("nessuna camera") { it == null }
    }
    assertThat(MapCameraRules.FIRST_MAX_ZOOM).isEqualTo(5.0)
  }

  @Test
  fun unFileRovinatoOUnaCameraIllegibileTornanoAdI_Valori_DiSerie() {
    val file = tempFile().also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(64) { b -> (b * 7 + 3).toByte() }) }
    withStore(file) { s ->
      // Correzioni R2 (31/10): un file rovinato non torna "di serie" (accesa): la scelta "spenta" non si riaccende da sola, quindi vale "spenta" finche' non la riaccendi
      // (le prove di questo caso, con i codici e le richieste, sono in `MapFailClosedTest`). La camera illeggibile resta "nessuna camera".
      assertThat(s.online.value).isEqualTo(MapOnline.Off)
      assertThat(s.lastCamera.value).isNull()
    }
    for (bad in listOf("", "1,2", "a,b,c", "999999999,0,5", "0,0,5", "43000000,11000000,NaN", "43000000,11000000,99", "43000000,11000000,-1", "43000000,11000000,5,7")) {
      assertThat(DataStoreMapSettings.cameraOf(bad)).isNull()
    }
    assertThat(DataStoreMapSettings.cameraOf("43769600,11255800,7.5")).isEqualTo(CameraSpot(GeoE6(43_769_600, 11_255_800), 7.5))
    assertThat(DataStoreMapSettings.cameraOf(null)).isNull()
  }

  @Test
  fun leCoordinateDellaCameraNonSonoNelToString() {
    assertThat(CameraSpot(GeoE6(43_000_000, 11_000_000), 5.0).toString()).isEqualTo("CameraSpot")
    assertThat(GeoE6(43_000_000, 11_000_000).toString()).isEqualTo("GeoE6")
  }

  // --- Senza legame: fallisce chiusa, non salva niente ------------------------------------------------------------------------------

  @Test
  fun senzaLegameLaMappaDettagliataNonCe_ENonSiSalvaNiente() = runTest {
    assertThat(NoMapSettings.online.value).isEqualTo(MapOnline.Unavailable)
    NoMapSettings.saveCamera(CameraSpot(GeoE6(1_000_000, 1_000_000), 3.0))
    assertThat(NoMapSettings.lastCamera.value).isNull()
    NoMapSettings.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1))
    assertThat(NoMapSettings.online.value).isEqualTo(MapOnline.Unavailable)
  }

  // --- La pagina Impostazioni -> Mappa (Q1 (c)) ---------------------------------------------------------------------------------------

  private class RecordingRegions : MapRegions by NoMapRegions {
    var paused = 0

    override suspend fun pauseAll() {
      paused++
    }
  }

  private fun privacyController(settings: MapSettings, regions: MapRegions, scope: CoroutineScope, now: () -> Long = { 42L }) = MapPrivacyController(settings, regions, scope, now)

  @Test
  fun lAccensioneDiSerieNonCostruisceNessunaScelta_SoloUnTocco() = runTest {
    val settings = InMemoryMapSettings()
    val controller = privacyController(settings, NoMapRegions, backgroundScope + UnconfinedTestDispatcher(testScheduler))
    runCurrent()
    assertThat(controller.state.value).isEqualTo(MapPrivacyState.Shown(MapOnline.On(byDefault = true), wifiOnly = true, noteVersion = MapPrivacyNote.VERSION, regions = 0))
    // Spegnere non chiede niente e non costruisce nessuna scelta.
    controller.setOnline(false)
    runCurrent()
    assertThat(settings.online.value).isEqualTo(MapOnline.Off)
    assertThat(settings.choices).isEmpty()
    // Riaccendere e' un tocco sull'interruttore: la scelta porta la versione della nota che l'utente aveva davanti e l'ora monotona.
    controller.setOnline(true)
    runCurrent()
    assertThat(settings.online.value).isEqualTo(MapOnline.On(byDefault = false))
    assertThat(settings.choices).hasSize(1)
    assertThat(settings.choices.single().noteVersion).isEqualTo(MapPrivacyNote.VERSION)
    assertThat(settings.choices.single().atMs).isEqualTo(42L)
    assertThat(settings.choices.single().toString()).isEqualTo("MapOnlineChoice[nota ${MapPrivacyNote.VERSION}]")
  }

  @Test
  fun spegnereFermaGliScaricamentiInCorso_E20() = runTest {
    val regions = RecordingRegions()
    val controller = privacyController(InMemoryMapSettings(), regions, backgroundScope + UnconfinedTestDispatcher(testScheduler))
    controller.setOnline(false)
    runCurrent()
    assertThat(regions.paused).isEqualTo(1)
    controller.setOnline(true)
    runCurrent()
    assertThat(regions.paused).isEqualTo(1)
  }

  @Test
  fun soloSulWifiSiSpegneESiRiaccende_EIConDelleZoneScaricate() = runTest {
    val settings = InMemoryMapSettings()
    val controller = privacyController(settings, NoMapRegions, backgroundScope + UnconfinedTestDispatcher(testScheduler))
    controller.setWifiOnly(false)
    runCurrent()
    assertThat((controller.state.value as MapPrivacyState.Shown).wifiOnly).isFalse()
    controller.setWifiOnly(true)
    runCurrent()
    assertThat((controller.state.value as MapPrivacyState.Shown).wifiOnly).isTrue()
  }

  @Test
  fun conLInterruttoreDiBuildSpentoLaPaginaDiceNonDisponibile() = runTest {
    val settings = InMemoryMapSettings(buildFlag = false)
    val controller = privacyController(settings, NoMapRegions, backgroundScope + UnconfinedTestDispatcher(testScheduler))
    runCurrent()
    assertThat(controller.state.value).isEqualTo(MapPrivacyState.Unavailable)
    controller.setOnline(true)
    runCurrent()
    assertThat(settings.online.value).isEqualTo(MapOnline.Unavailable)
  }

  private companion object {
    /** Il tetto di ogni attesa su un evento del DataStore vero: non misura la velocita', e' la rete contro un test appeso (con altre build sul PC un giro diventa molto piu' lento). */
    val TETTO: Duration = 2.minutes
  }
}
