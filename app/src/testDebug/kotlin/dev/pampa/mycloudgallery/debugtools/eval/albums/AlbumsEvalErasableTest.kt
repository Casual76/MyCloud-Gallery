package dev.pampa.mycloudgallery.debugtools.eval.albums

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.integration.TestDevice
import dev.pampa.mycloudgallery.integration.TestNas
import dev.pampa.mycloudgallery.integration.TestPlatform
import dev.pampa.mycloudgallery.integration.TestTime
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
 * Le etichette dei viaggi veri sono dati privati sul telefono (`noBackupFilesDir/eval/albums/`): l'azzeramento le toglie. Stanno dentro `eval/`, la cartella che le
 * build di debug dichiarano gia' ([DeveloperTools.erasableDirs], I11 della revisione della Fase 6): qui si prova che tutto cio' che lo strumento scrive --
 * etichette, resoconti, prova generale e file portato dentro con `adb` -- se ne va con l'azzeramento, e che la cartella dello strumento e' quella dichiarata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumsEvalErasableTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()

  @After
  fun tearDown() {
    devices.forEach { it.close() }
  }

  @Test
  fun loStrumentoScriveDentroLaCartellaChePerQuestoSiAzzera() {
    val store = AlbumsEvalStore.of(app)
    store.saveLabels(AlbumsLabelSet())
    store.saveReport("numeri")
    val declared = File(app.noBackupFilesDir, DeveloperTools.erasableDirs().single())
    assertThat(File(declared, "albums/${AlbumsLabelFormat.FILE_NAME}").isFile).isTrue()
    assertThat(File(declared, "albums/report-01.txt").isFile).isTrue()
    // E la prova generale sta accanto, nella stessa cartella.
    AlbumsEvalStore.rehearsalOf(app).saveReport("numeri")
    assertThat(File(declared, "albums/prova/report-01.txt").isFile).isTrue()
  }

  @Test
  fun lAzzeramentoTogliLeEtichetteDeiViaggiEIResoconti() = runBlocking<Unit> {
    val device = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { devices += it }
    val albums = File(File(device.context.noBackupFilesDir, "eval"), "albums")
    val files = listOf(
      File(albums, AlbumsLabelFormat.FILE_NAME),
      File(albums, "report-01.txt"),
      File(File(albums, "prova"), AlbumsLabelFormat.FILE_NAME),
      File(File(albums, "import"), AlbumsEvalStore.IMPORT_FILE),
    ).onEach {
      it.parentFile!!.mkdirs()
      it.writeText("x")
    }

    assertThat(device.graph.eraser.eraseAll()).isTrue()

    files.forEach { assertThat(it.exists()).isFalse() }
    assertThat(albums.exists()).isFalse()
  }
}
