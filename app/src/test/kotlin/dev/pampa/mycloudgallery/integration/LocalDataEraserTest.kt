package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.graph.GalleryStopper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Revisione di sicurezza della Fase 3, R7, lato azzeramento: `AppLocalDataEraser` (fase 5) ferma la galleria viva **prima**
 * di cancellare `replica/` e i database, e se non si ferma non cancella niente (la fase resta da fare e si riprova al
 * prossimo avvio). L'altro lato, che la galleria ferma non riscriva piu' niente, e' in `GalleryShutdownTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocalDataEraserTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()

  private fun device(): TestDevice =
    TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { devices += it }

  @After
  fun tearDown() {
    devices.forEach { it.close() }
  }

  private fun replicaFile(device: TestDevice): File =
    File(File(device.context.noBackupFilesDir, "replica"), "timeline.idx").apply {
      parentFile!!.mkdirs()
      writeText("indice")
    }

  @Test
  fun primaSiFermaLaGalleriaPoiSiCancellaLaReplica() = runBlocking<Unit> {
    val device = device()
    val replica = replicaFile(device)
    var replicaWhenStopped: Boolean? = null
    device.graph.galleryStopper = GalleryStopper { replicaWhenStopped = replica.exists() }

    val done = device.graph.eraser.eraseAll()

    assertThat(done).isTrue()
    // Quando la galleria e' stata fermata i file c'erano ancora: l'ordine e' "ferma, poi cancella".
    assertThat(replicaWhenStopped).isTrue()
    assertThat(replica.exists()).isFalse()
    assertThat(replica.parentFile!!.exists()).isFalse()
  }

  @Test
  fun seLaGalleriaNonSiFermaNonSiCancellaNienteELaFaseRestaDaFare() = runBlocking<Unit> {
    val device = device()
    val replica = replicaFile(device)
    device.graph.galleryStopper = GalleryStopper { throw IllegalStateException("non si ferma") }

    val done = device.graph.eraser.eraseAll()

    assertThat(done).isFalse()
    assertThat(replica.exists()).isTrue()
  }

  @Test
  fun senzaGalleriaRegistrataSiCancellaLoStesso() = runBlocking<Unit> {
    val device = device()
    val replica = replicaFile(device)

    assertThat(device.graph.galleryStopper).isNull()
    assertThat(device.graph.eraser.eraseAll()).isTrue()
    assertThat(replica.exists()).isFalse()
  }
}
