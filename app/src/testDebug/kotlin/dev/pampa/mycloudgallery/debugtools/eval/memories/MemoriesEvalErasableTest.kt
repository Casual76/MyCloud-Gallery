package dev.pampa.mycloudgallery.debugtools.eval.memories

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
 * I giudizi sui ricordi veri sono dati privati sul telefono (`noBackupFilesDir/eval/memories/`): l'azzeramento li toglie. Stanno dentro `eval/`, la cartella che le build di
 * debug dichiarano gia' ([DeveloperTools.erasableDirs], I11 della revisione della Fase 6): qui si prova che tutto cio' che lo strumento scrive -- sessione, giudizi, resoconti --
 * se ne va con l'azzeramento, e che la cartella dello strumento e' quella dichiarata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoriesEvalErasableTest {

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
    val store = MemoriesEvalStore.of(app)
    store.saveLabels(MemoriesLabelSet())
    store.saveReport("numeri")
    val declared = File(app.noBackupFilesDir, DeveloperTools.erasableDirs().single())
    assertThat(File(declared, "memories/${MemoriesLabelFormat.FILE_NAME}").isFile).isTrue()
    assertThat(File(declared, "memories/report-01.txt").isFile).isTrue()
    // E non finisce nei backup: la cartella e' quella che Android lascia fuori.
    assertThat(declared.path).startsWith(app.noBackupFilesDir.path)
  }

  @Test
  fun lAzzeramentoTogliIGiudiziSuiRicordiEIResoconti() = runBlocking<Unit> {
    val device = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { devices += it }
    val memories = File(File(device.context.noBackupFilesDir, "eval"), "memories")
    val files = listOf(File(memories, MemoriesLabelFormat.FILE_NAME), File(memories, "report-01.txt"), File(memories, "report-02.txt"))
      .onEach {
        it.parentFile!!.mkdirs()
        it.writeText("x")
      }

    assertThat(device.graph.eraser.eraseAll()).isTrue()

    files.forEach { assertThat(it.exists()).isFalse() }
    assertThat(memories.exists()).isFalse()
  }
}
