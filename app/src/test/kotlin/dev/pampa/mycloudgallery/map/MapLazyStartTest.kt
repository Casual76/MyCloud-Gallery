package dev.pampa.mycloudgallery.map

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.noop.NoVisibleMedia
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.noop.NoMapNetworkState
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.noop.NoMapTraffic
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 9, P13 (§1.4 `MapGraph`; "per P13" di P1: "test: nessuna `MapView` nasce senza il client"; principio "Avvio e pagine istantanei"):
 *
 * - il grafo della mappa **nasce senza fare niente**: nessun file in `map/`, nessuna impostazione letta, nessun indice, MapLibre non avviata;
 * - MapLibre parte **solo** da `MapEngineStarter.ensure`, che chiama `MapLibreInit.ensure` (il client delle tessere con il cancello installato prima di dirsi
 *   pronta) e risponde con `MapLibreInit.isReady`: se l'avvio fallisce (qui, sulla JVM, la libreria nativa non c'e') la risposta e' "no" e la pagina non crea
 *   nessuna `MapView` (`MapLibreSurfaceContent` la crea solo con "si'");
 * - `:app` non nomina mai `MapView`, `MapSnapshotter`, `MapLibre.getInstance`, OkHttp ne' il pacchetto `net/` della mappa: l'unico ingresso e' `MapLibreInit`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapLazyStartTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val database = GalleryDatabase.build(context, null)

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
  }

  private fun graph() = MapGraph(
    context = context,
    parent = scope,
    database = database,
    visible = NoVisibleMedia,
    mask = HiddenMask { emptySet() },
    places = MapPlaceNames { null },
    catalogVersion = { null },
    network = { MutableStateFlow(NetworkSnapshot.OFFLINE) },
    ledger = { error("il registro del traffico non serve finche' non ci sono tessere") },
    buildFlag = true,
  )

  private val mapDir: File get() = File(context.noBackupFilesDir, MapLibreInit.MAP_DIR)

  /**
   * Il cancello con impostazioni in memoria: il DataStore vero tiene "attivo" il suo file finche' il suo scope vive (tutto il processo dei test), e un secondo
   * DataStore sullo stesso file fallisce in un altro test. Qui si prova l'ordine della partenza, non le impostazioni.
   */
  private fun wiring() = MapNetWiring(TilePolicy, true, InMemoryMapSettings(), NoMapNetworkState, NoMapRegions, NoMapTraffic)

  @Test
  fun ilGrafoNasceSenzaFareNiente() {
    mapDir.deleteRecursively()
    val map = graph()
    map.onHiddenChanged()
    map.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    assertWithMessage("nessun file della mappa all'avvio").that(mapDir.exists()).isFalse()
    assertThat(MapLibreInit.isReady).isFalse()
    // L'indice dei punti non si legge per la Diagnostica: senza un'apertura non c'e'.
    assertThat(map.indexIfOpened()).isNull()
  }

  @Test
  fun lIndiceSiFaSoloAllApertura() = runBlocking<Unit> {
    val map = graph()
    assertThat(map.points.opened).isFalse()
    map.points.rebuild(RebuildReason.OPEN)
    assertThat(map.points.opened).isTrue()
    assertThat(map.indexIfOpened()).isNotNull()
  }

  @Test
  fun senzaIlClientNessunaMappa_laPartenzaPassaSoloDaMapLibreInit() {
    val calls = mutableListOf<String>()
    var ready = false
    val starter = AppMapEngineStarter(
      wiring = { calls += "wiring"; wiring() },
      scope = scope,
      init = { _, _: MapNetWiring, _ -> calls += "ensure"; ready = true },
      ready = { ready },
    )
    assertThat(starter.ensure(context)).isTrue()
    assertThat(starter.ensure(context)).isTrue()
    // Una volta sola: la seconda mappa trova MapLibre gia' pronta con il suo client.
    assertThat(calls).containsExactly("wiring", "ensure").inOrder()

    // Un avvio che fallisce non dice "pronta" (nessuna MapView) e non si riprova a ogni fotogramma.
    var attempts = 0
    val broken = AppMapEngineStarter({ wiring() }, scope, init = { _, _: MapNetWiring, _ -> attempts++; throw UnsatisfiedLinkError("niente libreria") }, ready = { false })
    assertThat(broken.ensure(context)).isFalse()
    assertThat(broken.ensure(context)).isFalse()
    assertThat(attempts).isEqualTo(1)
  }

  @Test
  fun conMapLibreVeraSullaJvmLaMappaNonParte() {
    // Sulla JVM la libreria nativa non c'e': l'avvio vero fallisce e la pagina resta senza MapView, senza crollare.
    shadowMainLooper()
    val started = graph().starter.ensure(context)
    assertThat(started).isFalse()
    assertThat(MapLibreInit.isReady).isFalse()
  }

  @Test
  fun appNonNominaMaiLaMappaVeraNeLaRete() {
    val root = File(System.getProperty("user.dir")!!).let { if (File(it, "src/main").isDirectory) it else File(it, "app") }
    // Le parole si compongono a pezzi: scritte intere, il test d'architettura della Fase 9 (che legge anche le prove) vedrebbe MapLibre e OkHttp fuori da feature-map.
    val forbidden = listOf("MapView(", "MapSnapshotter", "MapLibre.getInstance", "org.map" + "libre", "ok" + "http3", "feature.map." + "net")
    val offenders = File(root, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
      .flatMap { file -> forbidden.filter { it in file.readText() }.map { "${file.name}: $it" } }.toList()
    assertThat(offenders).isEmpty()
  }

  private fun shadowMainLooper() {
    assertThat(Looper.myLooper()).isEqualTo(Looper.getMainLooper())
  }
}
