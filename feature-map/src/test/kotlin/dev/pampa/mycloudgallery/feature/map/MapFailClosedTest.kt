package dev.pampa.mycloudgallery.feature.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapPrivacyNote
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.MapSettingsProblem
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.testing.RecordingMapTraffic
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.map.MapConnectivity
import dev.pampa.mycloudgallery.feature.map.map.MapController
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import dev.pampa.mycloudgallery.feature.map.net.FakeTileServer
import dev.pampa.mycloudgallery.feature.map.net.MapRequestHeld
import dev.pampa.mycloudgallery.feature.map.net.MapTileHttp
import dev.pampa.mycloudgallery.feature.map.net.TestClock
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyController
import dev.pampa.mycloudgallery.feature.map.settings.DataStoreMapSettings
import dev.pampa.mycloudgallery.feature.map.support.FakeMapPoints
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.support.TestPlaceNames
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Correzioni R2 e R5 della Fase 9 (revisione di sicurezza, 31/10): la scelta "mappa dettagliata spenta" **fallisce chiusa**, e l'azzeramento svuota le impostazioni attraverso DataStore.
 *
 *  - prima della prima lettura riuscita il cancello e `setConnected` valgono "spenta", e la carta online nasce solo dopo;
 *  - un errore di lettura vale "spenta" per il processo (il flusso non finisce, la lettura si riprova);
 *  - un file rovinato vale "spenta finche' non la riaccendi", anche dopo un riavvio;
 *  - in tutti i casi, **con la scelta salvata "spenta", zero richieste escono dal cancello** (il server finto sta dopo l'intercettore: "ha visto" = `proceed()` chiamato).
 *
 * Robolectric solo per `org.json` (il TileJSON dei casi in cui la richiesta deve uscire). Tempo virtuale o stati attesi, mai attese a tempo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MapFailClosedTest {

  private val tempDirs = ArrayList<File>()

  @After
  fun clean() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempFile(): File = Files.createTempDirectory("mappa-chiusa").toFile().also(tempDirs::add).resolve("map/settings/map.preferences_pb")

  /** Il client vero con il cancello vero e le porte di [settings]; ogni richiesta che il cancello lascia uscire arriva a [server]. */
  private class Rig(val settings: MapSettings, regions: () -> MapRegions = { NoMapRegions }) {
    val server = FakeTileServer()
    val wiring = MapNetWiring(TilePolicy, true, settings, FakeMapNetworkState(MapNetwork.WIFI), regions, RecordingMapTraffic())
    val http = MapTileHttp(TilePolicy, wiring::state, RecordingMapTraffic(), { MapNetwork.WIFI }, null, TestClock())
    private val client = http.client.newBuilder().addInterceptor(server).build()

    fun tile(): Result<Response> = runCatching {
      client.newCall(okhttp3.Request.Builder().url("https://tiles.openfreemap.org/planet/v/9/271/186.pbf").build()).execute()
    }

    /** Vero se la richiesta e' stata fermata dal cancello (nessuna richiesta fuori). */
    fun heldAndNothingLeft(): Boolean = tile().exceptionOrNull() is MapRequestHeld && server.seen.isEmpty()
  }

  /** Il DataStore vero su un file, con i suoi scope reali (si aspetta lo stato preciso, mai un'attesa a tempo). */
  private fun withStore(file: File, block: suspend (DataStoreMapSettings) -> Unit) = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val settings = DataStoreMapSettings.create(file, scope, true, storeScope = scope)
      withTimeout(TIMEOUT) { settings.loaded() }
      block(settings)
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }

  // --- Un file rovinato: spenta finche' non la riaccendi ------------------------------------------------------------------------------

  @Test
  fun unFileRovinatoConLaScelta_Spenta_NonRiaccendeLaMappa_NessunaRichiestaEsce() {
    val file = tempFile()
    withStore(file) { s ->
      s.disable()
      withTimeout(TIMEOUT) { s.online.first { it == MapOnline.Off } }
    }
    // Il file si rovina (un aggiornamento, un guasto della memoria).
    file.writeBytes(ByteArray(64) { b -> (b * 7 + 3).toByte() })
    withStore(file) { s ->
      assertThat(s.online.value).isEqualTo(MapOnline.Off)
      assertThat(s.problem.value).isEqualTo(MapSettingsProblem.DAMAGED)
      val rig = Rig(s)
      assertThat(rig.wiring.onlineNow()).isFalse()
      assertWithMessage("a mappa spenta per un file rovinato niente esce").that(rig.heldAndNothingLeft()).isTrue()
      assertThat(rig.http.snapshot().allowed).isEqualTo(0)
      assertThat(rig.http.snapshot().refused[RefuseReason.OFF]).isEqualTo(1)
    }
    // Un riavvio: il file rifatto dice "spenta" e porta il segno del danno, quindi niente cambia da solo.
    withStore(file) { s ->
      assertThat(s.online.value).isEqualTo(MapOnline.Off)
      assertThat(s.problem.value).isEqualTo(MapSettingsProblem.DAMAGED)
      assertThat(Rig(s).heldAndNothingLeft()).isTrue()
      // L'utente la riaccende (un tocco sull'interruttore): da qui la scelta e' sua, il segno sparisce e la richiesta puo' uscire.
      s.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1))
      withTimeout(TIMEOUT) { s.online.first { it == MapOnline.On(byDefault = false) } }
      assertThat(s.problem.value).isEqualTo(MapSettingsProblem.NONE)
      val rig = Rig(s)
      assertThat(rig.tile().getOrThrow().code).isEqualTo(200)
      assertThat(rig.server.tiles()).hasSize(1)
    }
    withStore(file) { s ->
      assertThat(s.online.value).isEqualTo(MapOnline.On(byDefault = false))
      assertThat(s.problem.value).isEqualTo(MapSettingsProblem.NONE)
    }
  }

  @Test
  fun unFileRovinatoSiRiaccendeSoloConUnTocco_ESpegnerloLoRendeSuoDiNuovo() {
    val file = tempFile().also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(40) { b -> (b * 5 + 1).toByte() }) }
    withStore(file) { s ->
      assertThat(s.problem.value).isEqualTo(MapSettingsProblem.DAMAGED)
      s.disable()
      withTimeout(TIMEOUT) { s.problem.first { it == MapSettingsProblem.NONE } }
      assertThat(s.online.value).isEqualTo(MapOnline.Off)
    }
  }

  // --- Un errore di lettura: spenta per il processo, il flusso non finisce ------------------------------------------------------------

  /** Un DataStore finto: le prime [failures] letture lanciano [failure], poi dice quello che c'e' salvato (la scelta e' "accesa"); `updateData` scrive in memoria. */
  private class ScriptedStore(var failures: Int, private val failure: () -> Throwable) : DataStore<Preferences> {
    private val saved = MutableStateFlow<Preferences>(preferencesOf(stringPreferencesKey("online") to "on", booleanPreferencesKey("wifi_only") to false))
    var reads = 0

    override val data: Flow<Preferences> = flow {
      reads++
      if (reads <= failures) throw failure()
      emitAll(saved)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(saved.value).also { saved.value = it }
  }

  @Test
  fun unaLetturaCheLancia_ValeSpentaPerIlProcesso_ILFlussoNonFinisceELaLetturaSiRiprova() {
    for (failure in listOf<() -> Throwable>({ IOException("disco") }, { IllegalStateException("guasto") })) {
      runTest {
        val store = ScriptedStore(failures = 2, failure = failure)
        val settings = DataStoreMapSettings(store, backgroundScope, true, retryMs = RETRY_MS)
        runCurrent()
        // La prima lettura e' fallita: nessuna attesa eterna, e la mappa dettagliata vale "spenta" con il suo codice.
        settings.loaded()
        assertThat(settings.online.value).isEqualTo(MapOnline.Off)
        assertThat(settings.problem.value).isEqualTo(MapSettingsProblem.UNREADABLE)
        val rig = Rig(settings)
        assertThat(rig.heldAndNothingLeft()).isTrue()
        // Il flusso non e' finito: si riprova. Il secondo tentativo fallisce ancora, il terzo riesce e dice "accesa"...
        advanceTimeBy(RETRY_MS)
        runCurrent()
        assertThat(store.reads).isEqualTo(2)
        assertThat(settings.online.value).isEqualTo(MapOnline.Off)
        advanceTimeBy(RETRY_MS)
        runCurrent()
        assertThat(store.reads).isEqualTo(3)
        // ... ma per questo processo una lettura che e' fallita non basta per riaccenderla: "Solo sul Wi-Fi" si sa, la scelta no.
        assertThat(settings.wifiOnly.value).isFalse()
        assertThat(settings.online.value).isEqualTo(MapOnline.Off)
        assertThat(settings.problem.value).isEqualTo(MapSettingsProblem.UNREADABLE)
        assertThat(rig.wiring.onlineNow()).isFalse()
        assertThat(rig.heldAndNothingLeft()).isTrue()
        // MapLibre non si crede in rete (setConnected(false)).
        assertThat(MapConnectivity.connected(rig.wiring.onlineNow(), settings.wifiOnly.value, MapNetwork.WIFI, rig.wiring.activeDownload())).isFalse()
        // Solo un tocco dell'utente la riaccende, e toglie il codice.
        settings.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1))
        assertThat(settings.online.value).isEqualTo(MapOnline.On(byDefault = false))
        assertThat(settings.problem.value).isEqualTo(MapSettingsProblem.NONE)
        assertThat(Rig(settings).tile().getOrThrow().code).isEqualTo(200)
      }
    }
  }

  // --- Prima della prima lettura: spenta, e la carta online nasce solo dopo ------------------------------------------------------------

  /** Un DataStore la cui prima lettura e' lenta: finche' [gate] non si apre non dice niente. */
  private class SlowStore(private val gate: CompletableDeferred<Unit>, saved: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(saved)

    override val data: Flow<Preferences> = flow {
      gate.await()
      emitAll(state)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
  }

  @Test
  fun primaDellaPrimaLetturaLaMappaValeSpenta_NessunaRichiesta_PoiAccesa() = runTest {
    val gate = CompletableDeferred<Unit>()
    val settings = DataStoreMapSettings(SlowStore(gate, preferencesOf()), backgroundScope, true)
    runCurrent()
    val rig = Rig(settings)
    // Il file non e' ancora stato letto: potrebbe dire "spenta", quindi vale "spenta". Ne' il cancello ne' MapLibre si fidano del valore di serie.
    assertThat(settings.online.value).isEqualTo(MapOnline.Off)
    assertThat(rig.wiring.onlineNow()).isFalse()
    assertThat(MapConnectivity.connected(rig.wiring.onlineNow(), settings.wifiOnly.value, MapNetwork.WIFI, rig.wiring.activeDownload())).isFalse()
    assertThat(rig.heldAndNothingLeft()).isTrue()
    assertThat(rig.http.snapshot().allowed).isEqualTo(0)
    val waiting = backgroundScope.launch { settings.loaded() }
    runCurrent()
    assertThat(waiting.isCompleted).isFalse()
    // La lettura riesce: "accesa di serie", e da qui in poi la richiesta puo' uscire.
    gate.complete(Unit)
    runCurrent()
    assertThat(waiting.isCompleted).isTrue()
    assertThat(settings.online.value).isEqualTo(MapOnline.On(byDefault = true))
    assertThat(settings.problem.value).isEqualTo(MapSettingsProblem.NONE)
    assertThat(rig.tile().getOrThrow().code).isEqualTo(200)
  }

  @Test
  fun primaDellaPrimaLetturaConLaScelta_Spenta_NienteEsceMaiNemmenoDopo() = runTest {
    val gate = CompletableDeferred<Unit>()
    val saved = preferencesOf(stringPreferencesKey("online") to "off")
    val settings = DataStoreMapSettings(SlowStore(gate, saved), backgroundScope, true)
    runCurrent()
    val rig = Rig(settings)
    assertThat(rig.heldAndNothingLeft()).isTrue()
    gate.complete(Unit)
    runCurrent()
    assertThat(settings.online.value).isEqualTo(MapOnline.Off)
    assertThat(rig.heldAndNothingLeft()).isTrue()
  }

  @Test
  fun laCartaOnlineNasceSoloDopoLaPrimaLetturaRiuscita() = runTest {
    val rig = MapRig()
    val gate = CompletableDeferred<Unit>()
    val settings = DataStoreMapSettings(SlowStore(gate, preferencesOf()), backgroundScope, true)
    val controller = MapController(
      points = FakeMapPoints(rig.ready), settings = settings, regions = NoMapRegions, network = FakeMapNetworkState(MapNetwork.WIFI), names = TestPlaceNames(),
      filter = FinalFilter(rig.visibility), positions = dev.pampa.mycloudgallery.feature.map.contract.NoMapMediaPositions, snapshot = rig.visibility.snapshot,
      scope = backgroundScope, compute = UnconfinedTestDispatcher(testScheduler),
    )
    runCurrent()
    // La camera non e' decisa (la pagina non crea la carta) e lo stile e' quello offline: nessuna carta online prima della prima lettura.
    assertThat(controller.ui.value.initial).isNull()
    assertThat(controller.ui.value.style.offline).isTrue()
    gate.complete(Unit)
    runCurrent()
    val ui = controller.ui.value
    assertThat(ui.initial).isNotNull()
    assertWithMessage("quando la carta nasce lo stile e' gia' quello giusto").that(ui.style.offline).isFalse()
  }

  // --- La pagina delle impostazioni ----------------------------------------------------------------------------------------------------

  @Test
  fun laPaginaDiceCheLaSceltaSiEraRovinata_FinoAlTocco() = runTest {
    val gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
    val saved = preferencesOf(stringPreferencesKey("online") to "off", booleanPreferencesKey("damaged") to true)
    val settings = DataStoreMapSettings(SlowStore(gate, saved), backgroundScope, true)
    val controller = MapPrivacyController(settings, NoMapRegions, backgroundScope + UnconfinedTestDispatcher(testScheduler))
    runCurrent()
    assertThat(controller.state.value).isEqualTo(MapPrivacyState.Shown(MapOnline.Off, wifiOnly = true, noteVersion = MapPrivacyNote.VERSION, regions = 0, damaged = true))
    controller.setOnline(true)
    runCurrent()
    assertThat(controller.state.value).isEqualTo(MapPrivacyState.Shown(MapOnline.On(byDefault = false), wifiOnly = true, noteVersion = MapPrivacyNote.VERSION, regions = 0, damaged = false))
  }

  /** Una scrittura che non riesce (il file non si legge) non fa crollare l'app: la scelta resta com'era. */
  @Test
  fun unaScritturaCheNonRiesceNonFaCrollare() = runTest {
    val failing = object : MapSettings by InMemoryMapSettings() {
      override suspend fun disable() {
        throw IOException("disco pieno")
      }

      override suspend fun setWifiOnly(on: Boolean) {
        throw IOException("disco pieno")
      }
    }
    val controller = MapPrivacyController(failing, NoMapRegions, backgroundScope + UnconfinedTestDispatcher(testScheduler))
    controller.setOnline(false)
    controller.setWifiOnly(false)
    runCurrent()
    assertThat(controller.state.value).isInstanceOf(MapPrivacyState.Shown::class.java)
  }

  // --- R5: l'azzeramento passa da DataStore, e dopo niente si scrive ----------------------------------------------------------------

  @Test
  fun lAzzeramentoSvuotaAttraversoDataStore_EDopoNessunaScrittura() {
    val file = tempFile()
    val spot = CameraSpot(GeoE6(45_464_200, 9_190_000), 11.5)
    withStore(file) { s ->
      s.saveCamera(spot)
      s.setWifiOnly(false)
      s.disable()
      withTimeout(TIMEOUT) { s.lastCamera.first { it == spot } }
      withTimeout(TIMEOUT) { s.online.first { it == MapOnline.Off } }
      s.clear()
      // Subito: valori di serie e mappa "spenta" (nessuna richiesta), non piu' la vecchia camera.
      assertThat(s.lastCamera.value).isNull()
      assertThat(s.wifiOnly.value).isTrue()
      assertThat(s.online.value).isEqualTo(MapOnline.Off)
      // L'eraser toglie il file da sotto (DataStore non lo tollera): cio' che scrive dopo non deve ricrearlo.
      assertThat(file.delete()).isTrue()
      s.saveCamera(spot)
      s.setWifiOnly(false)
      s.disable()
      s.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1))
      assertWithMessage("dopo l'azzeramento nessuna scrittura rimette un file sul disco").that(file.exists()).isFalse()
      assertThat(s.lastCamera.value).isNull()
      assertThat(s.online.value).isEqualTo(MapOnline.Off)
    }
  }

  @Test
  fun dopoLAzzeramentoIlFileEVuoto_ECioCheCeraNonTornaPiu() {
    val file = tempFile()
    val spot = CameraSpot(GeoE6(43_769_600, 11_255_800), 9.0)
    withStore(file) { s ->
      s.saveCamera(spot)
      withTimeout(TIMEOUT) { s.lastCamera.first { it == spot } }
      s.clear()
      s.saveCamera(spot) // una scrittura dopo lo stop: ignorata
    }
    // Il nuovo account nello stesso telefono trova i valori di serie, senza la camera del vecchio.
    withStore(file) { again ->
      assertThat(again.lastCamera.value).isNull()
      assertThat(again.online.value).isEqualTo(MapOnline.On(byDefault = true))
      assertThat(again.wifiOnly.value).isTrue()
    }
  }

  @Test
  fun chiNasceDopoLAzzeramentoNonScriveEValeSpenta() {
    val file = tempFile()
    withStore(file) { _ -> }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val late = DataStoreMapSettings.create(file, scope, true, storeScope = scope)
      late.markStopped()
      runBlocking {
        late.saveCamera(CameraSpot(GeoE6(1_000_000, 1_000_000), 3.0))
        late.setWifiOnly(false)
        assertThat(late.online.value).isEqualTo(MapOnline.Off)
        assertThat(late.lastCamera.value).isNull()
      }
    } finally {
      runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    withStore(file) { again ->
      assertThat(again.lastCamera.value).isNull()
      assertThat(again.wifiOnly.value).isTrue()
    }
  }

  @Test
  fun leImpostazioniInMemoriaFannoLoStessoDopoClear() = runTest {
    val settings = InMemoryMapSettings()
    settings.saveCamera(CameraSpot(GeoE6(1_000_000, 1_000_000), 3.0))
    settings.clear()
    settings.saveCamera(CameraSpot(GeoE6(2_000_000, 2_000_000), 4.0))
    settings.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1))
    assertThat(settings.lastCamera.value).isNull()
    assertThat(settings.online.value).isEqualTo(MapOnline.Off)
    assertThat(settings.choices).isEmpty()
  }

  // --- R5: la cache fuori posto chiude la rete e lascia la carta semplice -------------------------------------------------------------

  @Test
  fun conLaCacheFuoriPostoNessunaRichiestaEsce_AncheConLaMappaAccesa() {
    val settings = InMemoryMapSettings()
    var failed = false
    val rig = Rig(settings)
    val guarded = rig.wiring.withCacheGuard { failed }
    assertThat(guarded.onlineNow()).isTrue()
    failed = true
    assertThat(guarded.onlineNow()).isFalse()
    assertThat(guarded.state(null).online).isFalse()
    assertThat(guarded.activeDownload()).isNull()
    val client = MapTileHttp(TilePolicy, guarded::state, RecordingMapTraffic(), { MapNetwork.WIFI }, null, TestClock())
    val server = FakeTileServer()
    val result = runCatching {
      client.client.newBuilder().addInterceptor(server).build().newCall(okhttp3.Request.Builder().url("https://tiles.openfreemap.org/planet/v/9/271/186.pbf").build()).execute()
    }
    assertThat(result.exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
    assertThat(server.seen).isEmpty()
  }

  @Test
  fun conLaCacheFuoriPostoLaMappaGrandeRestaSulloStileOffline() = runTest {
    val rig = MapRig()
    val failed = MutableStateFlow(false)
    val controller = MapController(
      points = FakeMapPoints(rig.ready), settings = InMemoryMapSettings(), regions = NoMapRegions, network = FakeMapNetworkState(MapNetwork.WIFI), names = TestPlaceNames(),
      filter = FinalFilter(rig.visibility), positions = dev.pampa.mycloudgallery.feature.map.contract.NoMapMediaPositions, snapshot = rig.visibility.snapshot,
      scope = backgroundScope, compute = UnconfinedTestDispatcher(testScheduler), cacheFailed = failed,
    )
    runCurrent()
    assertThat(controller.ui.value.style.offline).isFalse()
    failed.value = true
    runCurrent()
    assertThat(controller.ui.value.style.offline).isTrue()
    val screen = controller.ui.value.screen as dev.pampa.mycloudgallery.feature.map.contract.MapScreenState.Ready
    assertThat(screen.online).isEqualTo(MapOnline.Off)
    assertThat(MapController.effectiveOnline(MapOnline.Unavailable, true)).isEqualTo(MapOnline.Unavailable)
  }

  private companion object {
    const val TIMEOUT = 10_000L
    const val RETRY_MS = 1_000L
  }
}
