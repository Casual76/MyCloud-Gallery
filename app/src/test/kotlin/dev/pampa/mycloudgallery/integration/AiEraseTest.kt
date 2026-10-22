package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.AiGraph
import dev.pampa.mycloudgallery.ai.FileAiUnsupported
import dev.pampa.mycloudgallery.ai.TextReadingMemory
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.search.SearchGraph
import dev.pampa.mycloudgallery.work.AiStages
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
 * L'azzeramento della Fase 6 (§1.4, P11): `AiGraph.stop()` e `SearchGraph.stop()` **per primi** (prima della galleria), poi l'eraser toglie
 * modelli, buffer, indice di ricerca (vettori, registro, righe del testo letto, corpi propri), "non supportati", memoria della lettura del
 * testo e battito del calcolo. Le tabelle `ai_*` e la cronologia stanno nel database della galleria, tolto con lui. Se l'AI non si ferma,
 * non si cancella niente (la fase resta da fare, come per la galleria).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AiEraseTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()

  private fun device(): TestDevice = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { devices += it }

  @After
  fun tearDown() {
    devices.forEach { it.close() }
  }

  private fun aiFiles(device: TestDevice): List<File> {
    val noBackup = device.context.noBackupFilesDir
    return listOf(
      File(File(noBackup, AiGraph.MODELS_DIR), "abc"),
      File(File(File(noBackup, AiGraph.MODELS_DIR), "partial"), "abc.part"),
      File(File(noBackup, AiGraph.BUFFER_DIR), "${"a".repeat(64)}.analysis"),
      File(File(File(noBackup, SearchGraph.DIR), SearchGraph.INDEX_DIR), "ledger.log"),
      File(File(File(noBackup, SearchGraph.DIR), SearchGraph.LAYOUT_DIR), "${"b".repeat(64)}.ocr"),
      File(noBackup, FileAiUnsupported.FILE),
      File(noBackup, TextReadingMemory.FILE),
      File(noBackup, AiStages.HEARTBEAT_AI),
    ).onEach { file ->
      file.parentFile!!.mkdirs()
      file.writeText("x")
    }
  }

  @Test
  fun primaSiFermaLAiPoiLaGalleriaPoiSiCancellaTutto() = runBlocking<Unit> {
    val device = device()
    val files = aiFiles(device)
    val order = mutableListOf<String>()
    var filesWhenAiStopped: Boolean? = null
    device.graph.aiStopper = GalleryStopper {
      order += "ai"
      filesWhenAiStopped = files.all { it.exists() }
    }
    device.graph.galleryStopper = GalleryStopper { order += "galleria" }

    assertThat(device.graph.eraser.eraseAll()).isTrue()

    assertThat(order).containsExactly("ai", "galleria").inOrder()
    assertThat(filesWhenAiStopped).isTrue()
    files.forEach { assertThat(it.exists()).isFalse() }
    assertThat(File(device.context.noBackupFilesDir, AiGraph.MODELS_DIR).exists()).isFalse()
    assertThat(File(device.context.noBackupFilesDir, SearchGraph.DIR).exists()).isFalse()
  }

  @Test
  fun seLAiNonSiFermaNonSiCancellaNiente() = runBlocking<Unit> {
    val device = device()
    val files = aiFiles(device)
    device.graph.aiStopper = GalleryStopper { throw IllegalStateException("non si ferma") }
    device.graph.galleryStopper = GalleryStopper { error("la galleria non si deve fermare prima dell'AI") }

    assertThat(device.graph.eraser.eraseAll()).isFalse()
    // Niente si toglie (nemmeno i modelli, che un motore ancora vivo potrebbe avere aperti): si riprova al prossimo avvio.
    files.forEach { assertThat(it.exists()).isTrue() }
  }

  /** Un cambio di account (R6) toglie i dati dell'AI ma non i modelli (pubblici, uguali per tutti). */
  @Test
  fun iDatiDiUnAltroAccountSiTolgonoIModelliRestano() = runBlocking<Unit> {
    val device = device()
    val files = aiFiles(device)
    val eraser = device.graph.eraser as dev.pampa.mycloudgallery.graph.AppLocalDataEraser
    assertThat(eraser.eraseAccountData(stopGallery = false)).isTrue()
    files.filter { !it.path.contains(AiGraph.MODELS_DIR) }.forEach { assertThat(it.exists()).isFalse() }
    files.filter { it.path.contains(AiGraph.MODELS_DIR) }.forEach { assertThat(it.exists()).isTrue() }
  }
}
