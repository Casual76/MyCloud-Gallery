package dev.pampa.mycloudgallery.debugtools.eval.faces

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
 * Le etichette delle persone sono posti di volti nelle foto vere: dati privati sul telefono (`noBackupFilesDir/eval/faces/`). L'azzeramento le toglie. Stanno dentro
 * `eval/`, la cartella che le build di debug dichiarano gia' ([DeveloperTools.erasableDirs], I11 della revisione della Fase 6): qui si prova che tutto cio' che lo
 * strumento scrive -- etichette e resoconti -- se ne va con l'azzeramento, che la cartella dello strumento e' quella dichiarata, e che i file stanno fuori dai backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FacesEvalErasableTest {

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
  fun loStrumentoScriveDentroLaCartellaChePerQuestoSiAzzeraEFuoriDaiBackup() {
    val store = FacesEvalStore.of(app)
    store.saveLabels(FacesLabelSet().with(SessionLabels(EvalSessionKind.CALIBRATION, 1L, EvalTotals.EMPTY, emptyList())))
    store.saveReport("numeri")
    val declared = File(app.noBackupFilesDir, DeveloperTools.erasableDirs().single())
    assertThat(File(declared, "faces/${FacesLabelFormat.FILE_NAME}").isFile).isTrue()
    assertThat(File(declared, "faces/report-01.txt").isFile).isTrue()
    // noBackupFilesDir e' fuori dal backup automatico di Android: le etichette non seguono il telefono nel cloud.
    assertThat(declared.canonicalPath).startsWith(app.noBackupFilesDir.canonicalPath)
    // Le etichette ci stanno e si rileggono uguali.
    assertThat(store.loadLabels().session(EvalSessionKind.CALIBRATION)!!.seed).isEqualTo(1L)
  }

  @Test
  fun lAzzeramentoTogliLeEtichetteDelleSessioniEIResoconti() = runBlocking<Unit> {
    val device = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { devices += it }
    val faces = File(File(device.context.noBackupFilesDir, "eval"), "faces")
    val files = listOf(File(faces, FacesLabelFormat.FILE_NAME), File(faces, "report-01.txt"), File(faces, "report-02.txt")).onEach {
      it.parentFile!!.mkdirs()
      it.writeText("x")
    }

    assertThat(device.graph.eraser.eraseAll()).isTrue()

    files.forEach { assertThat(it.exists()).isFalse() }
    assertThat(faces.exists()).isFalse()
  }

  @Test
  fun ilTastoCancellaTogliLeEtichetteELasciaStareLeAltreValutazioni() {
    val faces = FacesEvalStore.of(app)
    faces.saveLabels(FacesLabelSet())
    faces.saveReport("numeri")
    val other = File(File(app.noBackupFilesDir, "eval"), "labels.mcgeval").apply { parentFile!!.mkdirs(); writeText("altra valutazione") }
    faces.wipe()
    assertThat(faces.reports()).isEmpty()
    assertThat(File(File(File(app.noBackupFilesDir, "eval"), "faces"), FacesLabelFormat.FILE_NAME).exists()).isFalse()
    assertThat(other.isFile).isTrue()
  }
}
