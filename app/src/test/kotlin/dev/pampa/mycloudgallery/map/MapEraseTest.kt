package dev.pampa.mycloudgallery.map

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.noop.NoVisibleMedia
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.graph.AppLocalDataEraser
import dev.pampa.mycloudgallery.integration.Attese
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correzione R5 della Fase 9 (31/10): l'azzeramento della mappa. `MapGraph.stop()` svuota il database di MapLibre con l'API della libreria (se e' partita) e **le impostazioni attraverso DataStore** prima che
 * l'eraser tolga la cartella, e dopo lo stop nessuna scrittura (cosi' la vecchia camera non torna sul disco); un guasto in questi passi non ferma l'azzeramento; l'eraser toglie anche i file di serie del
 * database di MapLibre (`mbgl-offline.db*`) come seconda difesa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapEraseTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val database = GalleryDatabase.build(context, null)
  private val tempDirs = ArrayList<File>()

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun graph(erase: suspend () -> Boolean) = MapGraph(
    context = context,
    parent = scope,
    database = database,
    visible = NoVisibleMedia,
    mask = HiddenMask { emptySet() },
    places = MapPlaceNames { null },
    catalogVersion = { null },
    network = { MutableStateFlow(NetworkSnapshot.OFFLINE) },
    ledger = { error("il registro del traffico non serve") },
    buildFlag = true,
    eraseEngine = erase,
  )

  private val mapDir: File get() = File(context.noBackupFilesDir, MapLibreInit.MAP_DIR)
  private val spot = CameraSpot(GeoE6(43_769_600, 11_255_800), 9.0)

  @Test
  fun lAzzeramentoSvuotaIlMotoreELeImpostazioniAttraversoDataStore_EDopoNessunaScrittura() = runBlocking<Unit> {
    mapDir.deleteRecursively()
    val calls = ArrayList<String>()
    val map = graph { calls += "motore"; true }
    val settings = map.settings
    settings.saveCamera(spot)
    settings.setWifiOnly(false)
    withTimeout(Attese.TETTO) { settings.lastCamera.first { it == spot } }
    withTimeout(Attese.TETTO) { settings.wifiOnly.first { !it } }
    assertThat(File(mapDir, "settings/map.preferences_pb").exists()).isTrue()

    map.stop()

    // Il motore e' stato svuotato con la sua API, e le impostazioni sono vuote **in DataStore** (non solo sul disco): nessuna vecchia camera, mappa dettagliata "spenta".
    assertThat(calls).containsExactly("motore")
    assertThat(settings.lastCamera.value).isNull()
    assertThat(settings.wifiOnly.value).isTrue()
    assertThat(settings.online.value).isEqualTo(MapOnline.Off)
    // L'eraser toglie la cartella da sotto; nessuna scrittura successiva (l'utente tocca Impostazioni -> Mappa nello stesso processo) la rimette sul disco con la vecchia camera.
    assertThat(mapDir.deleteRecursively()).isTrue()
    settings.saveCamera(spot)
    settings.setWifiOnly(false)
    settings.disable()
    assertWithMessage("dopo l'azzeramento nessuna scrittura ricrea la cartella o il file").that(mapDir.exists()).isFalse()
    assertThat(settings.lastCamera.value).isNull()
  }

  /** Un solo DataStore per file e per processo: ogni caso ha il suo test (e la sua cartella, Robolectric ne da' una a ogni test). */
  private fun stopsDespite(erase: suspend () -> Boolean) = runBlocking<Unit> {
    mapDir.deleteRecursively()
    val map = graph(erase)
    val settings = map.settings
    settings.saveCamera(spot)
    withTimeout(Attese.TETTO) { settings.lastCamera.first { it == spot } }
    map.stop()
    assertThat(settings.lastCamera.value).isNull()
    assertThat(settings.online.value).isEqualTo(MapOnline.Off)
    // Una seconda chiamata non rifa niente.
    map.stop()
  }

  @Test
  fun seLaLibreriaDiceDiNoLAzzeramentoVaAvanti_CiPensanoIFile() = stopsDespite { false }

  @Test
  fun seLaLibreriaLanciaLAzzeramentoVaAvanti_CiPensanoIFile() = stopsDespite { throw IllegalStateException("guasto") }

  @Test
  fun chiLeggeLeImpostazioniDopoLAzzeramentoNonScriveEHaLaMappaSpenta() = runBlocking<Unit> {
    mapDir.deleteRecursively()
    val map = graph { true }
    map.stop()
    val settings = map.settings
    settings.saveCamera(spot)
    settings.setWifiOnly(false)
    assertThat(settings.online.value).isEqualTo(MapOnline.Off)
    assertThat(settings.lastCamera.value).isNull()
    assertWithMessage("il grafo fermato non scrive niente").that(mapDir.exists()).isFalse()
    // E il "Riprova" della sezione Luoghi non rifa niente su un grafo fermato.
    map.retryPoints()
    assertThat(map.indexIfOpened()).isNull()
  }

  @Test
  fun lEraserTogliAncheIFileDiSerieDelDatabaseDiMapLibre() {
    val a = Files.createTempDirectory("serie-a").toFile().also(tempDirs::add)
    val b = Files.createTempDirectory("serie-b").toFile().also(tempDirs::add)
    for (name in listOf("mbgl-offline.db", "mbgl-offline.db-journal", "mbgl-offline.db-wal", "mbgl-offline.db-shm", "altro.db", "map")) {
      File(a, name).writeText("x")
    }
    File(b, "mbgl-offline.db").writeText("x")
    assertThat(AppLocalDataEraser.deleteMapLibreDatabases(listOf(a, null, b, a, File(a, "non-esiste")))).isTrue()
    // Restano solo i file che non sono del database della libreria (la cartella `map` la toglie l'eraser a parte).
    assertThat(a.list()!!.toSet()).containsExactly("altro.db", "map")
    assertThat(b.list()!!.toList()).isEmpty()
    assertThat(MapLibreInit.DATABASE_PREFIX).isEqualTo("mbgl-offline.db")
  }
}
