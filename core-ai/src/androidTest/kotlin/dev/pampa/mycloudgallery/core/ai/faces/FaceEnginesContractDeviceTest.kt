package dev.pampa.mycloudgallery.core.ai.faces

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.cutout.ModelBytes
import dev.pampa.mycloudgallery.core.ai.testing.FaceEnginesContract
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8 (P2, "Fatto quando": suite di contratto comune a motori finti e veri). La STESSA suite `FaceEnginesContract` che gira sul motore finto (JVM) e sui
 * motori veri sopra sessioni finte (JVM, `RealEnginesContractTest`), qui sui due modelli veri dell'APK con LiteRT 2.3.0: dimensioni, norme, determinismo,
 * una richiesta alla volta, rilascio che riscalda, errori come codici. Per i volti servono immagini con volti veri: le composizioni di P1 che l'operatore ha
 * messo in `/data/local/tmp/mcg-faces/` (nessun volto nel repository; senza, queste prove si saltano: vedi `FacesDevice`). In piu', le prove che non hanno
 * bisogno di volti: immagini sintetiche, seconda passata a quattro tasselli, modello alterato o diverso da quello atteso.
 */
@RunWith(AndroidJUnit4::class)
class FaceEnginesContractDeviceTest {

  @Test
  fun laSuiteDiContrattoSuiModelliVeri() = runBlocking<Unit> {
    val refs = FacesDevice.requireReferences()
    val withFaces = refs.images.filter { it.detections.isNotEmpty() }.take(6).map { FacesDevice.loadFixture(it.name) }
    assumeTrue("servono almeno tre composizioni con volti in ${FacesDevice.FIXTURES_DIR}/img", withFaces.size >= 3)
    val without = refs.images.filter { it.detections.isEmpty() }.take(2).map { FacesDevice.loadFixture(it.name) } + listOf(flat(640, 480, 0x808080), flat(300, 900, 0xFFFFFF))
    val engines = FacesDevice.engines()
    try {
      FaceEnginesContract.verify(engines, withFaces, without)
    } finally {
      engines.release()
    }
  }

  @Test
  fun leImmaginiSinteticheSenzaVoltiNonDannoVoltiNeErrori() = runBlocking<Unit> {
    val engines = FacesDevice.engines()
    try {
      for (image in listOf(flat(1024, 768, 0x000000), flat(1024, 768, 0xFFFFFF), flat(640, 480, 0x808080), gradient(1024, 1024), gradient(300, 1200), gradient(2048, 1536))) {
        for (pass in DetectPass.entries) {
          val faces = engines.detector.detect(image, pass)
          assertTrue("sfondi senza volti: ${faces.size} rilevamenti (${image}, $pass)", faces.isEmpty())
        }
      }
    } finally {
      engines.release()
    }
  }

  @Test
  fun unaSecondaPassataSuUnHqDa2048FaQuattroCorseEUnaSuUnaImmagineCheEntra() = runBlocking<Unit> {
    val detector = YuNetDetector.create(FacesDevice.files())
    try {
      detector.detect(gradient(2048, 1536), DetectPass.HQ_TILES)
      assertEquals(4, detector.lastTimings!!.tiles)
      detector.detect(gradient(1024, 700), DetectPass.HQ_TILES)
      assertEquals(1, detector.lastTimings!!.tiles)
      detector.detect(gradient(2048, 1536), DetectPass.PREVIEW)
      assertEquals(1, detector.lastTimings!!.tiles)
      assertEquals("una sessione sola per tutte le richieste", 1, detector.opened)
    } finally {
      detector.release()
    }
  }

  @Test
  fun unModelloAlteratoNonSiApreEDopoLaReestrazioneDallApkTuttoTorna() = runBlocking<Unit> {
    val files = FacesDevice.files()
    val detector = YuNetDetector.create(files)
    val image = gradient(640, 480)
    try {
      detector.detect(image, DetectPass.PREVIEW)
      detector.release()
      // Un byte cambiato nel file verificato (il file e' in sola lettura: lo si rende scrivibile apposta, come farebbe chi lo manomette).
      val file = FacesDevice.modelFile(ModelCatalog.FACE_YUNET)
      assertTrue(file.isFile)
      file.setWritable(true)
      RandomAccessFile(file, "rw").use { raf ->
        raf.seek(raf.length() / 2)
        val b = raf.read()
        raf.seek(raf.length() / 2)
        raf.write(b xor 0x01)
      }
      val error = runCatching { detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as? AiEngineException
      assertNotNull("un modello alterato deve fermare il motore", error)
      assertEquals(AiEngineError.MODEL_UNAVAILABLE, error!!.error)
    } finally {
      // Il gestore lo toglie e lo riestrae dall'APK (E-A11): poi il motore riparte.
      FacesDevice.files()
    }
    assertEquals(0, detector.detect(image, DetectPass.PREVIEW).size)
    detector.release()
  }

  @Test
  fun unModelloDiversoDaQuelloAtteso_NonSiLeggeComeSeFosseQuello() = runBlocking<Unit> {
    val files = FacesDevice.files()
    val models = FacesDevice.models()
    // MagicTouch e' un terzo modello dell'APK: serve il suo file verificato (si estrae come gli altri).
    models.manager.ensure(ModelCatalog.CUTOUT_MAGICTOUCH.id, ModelTrigger.USER_NOW, 60.seconds)
    val yunet = checkNotNull(files.open(ModelCatalog.FACE_YUNET.id))
    val sface = checkNotNull(files.open(ModelCatalog.FACE_SFACE.id))
    val magic = checkNotNull(files.open(ModelCatalog.CUTOUT_MAGICTOUCH.id))
    try {
      // Il rilevatore con il file dell'embedder o del ritaglio, e l'embedder con il file del rilevatore o del ritaglio: MODEL_UNAVAILABLE, mai uscite sbagliate.
      for (alien in listOf(sface, magic)) {
        val error = runCatching { FaceLiteRtSessions.detector(ModelBytes.map(alien), FaceEngineLimits.CPU_THREADS) }.exceptionOrNull() as? AiEngineException
        assertEquals("rilevatore con ${alien.spec.id}", AiEngineError.MODEL_UNAVAILABLE, error?.error)
      }
      for (alien in listOf(yunet, magic)) {
        val error = runCatching { FaceLiteRtSessions.embedder(ModelBytes.map(alien), FaceEngineLimits.CPU_THREADS) }.exceptionOrNull() as? AiEngineException
        assertEquals("embedder con ${alien.spec.id}", AiEngineError.MODEL_UNAVAILABLE, error?.error)
      }
      // E con i file giusti le sessioni si aprono e si chiudono.
      FaceLiteRtSessions.detector(ModelBytes.map(yunet), FaceEngineLimits.CPU_THREADS).close()
      FaceLiteRtSessions.embedder(ModelBytes.map(sface), FaceEngineLimits.CPU_THREADS).close()
    } finally {
      yunet.close()
      sface.close()
      magic.close()
    }
  }

  // --- Immagini disegnate dalla prova (nessun volto) ------------------------------------------------------------------------------------

  private fun flat(width: Int, height: Int, rgb: Int): FaceImage = FaceImage(IntArray(width * height) { (0xFF shl 24) or rgb }, width, height)

  private fun gradient(width: Int, height: Int): FaceImage =
    FaceImage(
      IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        (0xFF shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or (((x + y) * 128 / (width + height)) + 30)
      },
      width,
      height,
    )
}
