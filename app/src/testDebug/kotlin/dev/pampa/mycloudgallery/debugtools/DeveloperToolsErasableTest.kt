package dev.pampa.mycloudgallery.debugtools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.debugtools.eval.EvalStore
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
 * I11 della revisione di sicurezza della Fase 6: lo strumento di valutazione della ricerca (solo debug, che gira sul telefono vero durante le
 * prove) tiene in `noBackupFilesDir/eval/` le etichette e le query del set privato; l'azzeramento non le toglieva perche' `LocalData` non
 * elencava la cartella. Ora le build di debug la dichiarano ([DeveloperTools.erasableDirs]) e l'azzeramento la toglie; in release l'elenco e'
 * vuoto (nessuno strumento, nessun dato).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeveloperToolsErasableTest {

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
  fun leBuildDiDebugDichiaranoLaCartellaDelloStrumentoDiValutazione() {
    assertThat(DeveloperTools.erasableDirs()).containsExactly("eval")
    // Il nome e' quello che lo strumento usa davvero: se cambiasse, l'azzeramento lascerebbe di nuovo le etichette sul telefono.
    EvalStore.of(app)
    assertThat(File(app.noBackupFilesDir, DeveloperTools.erasableDirs().single()).isDirectory).isTrue()
  }

  @Test
  fun lAzzeramentoTogliLeEtichetteEIResocontiDelloStrumentoDiValutazione() = runBlocking<Unit> {
    val device = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { devices += it }
    val eval = File(device.context.noBackupFilesDir, "eval")
    val files = listOf(File(eval, "labels.mcgeval"), File(eval, "report-01.txt"), File(File(eval, "ocr"), "manifest.tsv")).onEach {
      it.parentFile!!.mkdirs()
      it.writeText("x")
    }

    assertThat(device.graph.eraser.eraseAll()).isTrue()

    files.forEach { assertThat(it.exists()).isFalse() }
    assertThat(eval.exists()).isFalse()
  }
}
