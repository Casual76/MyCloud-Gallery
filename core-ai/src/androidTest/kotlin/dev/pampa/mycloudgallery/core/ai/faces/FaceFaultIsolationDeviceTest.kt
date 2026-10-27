package dev.pampa.mycloudgallery.core.ai.faces

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.testing.FaceEnginesContract
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8 (P2, "Fatto quando": un interprete che lancia un'eccezione su un volto non fa perdere gli altri). L'interprete VERO di LiteRT con i modelli veri,
 * avvolto in una sessione che lancia a comando alla corsa scelta: la politica di `SFaceEmbedder` e `YuNetDetector` (un volto alla volta, null per quello che
 * lancia, gli altri uguali a quelli senza guasto, tre guasti di fila = motore da riaprire, codici d'errore) sul motore vero. Non servono volti: la corsa del
 * modello e' la stessa su qualunque ingresso, e gli stessi ingressi danno gli stessi vettori con e senza il guasto.
 */
@RunWith(AndroidJUnit4::class)
class FaceFaultIsolationDeviceTest {

  /**
   * Un embedder vero che lancia alle corse indicate (1, 2, 3...). Le corse si contano in [runs], che vale per tutte le sessioni di una prova: una sessione
   * riaperta dopo il guasto non ricomincia da uno (altrimenti ricadrebbe nello stesso guasto).
   */
  private class FlakyEmbedder(private val real: EmbedderSession, private val failOn: Set<Int>, private val runs: AtomicInteger) : EmbedderSession {
    override fun run(rgb: FloatArray): FloatArray {
      if (runs.incrementAndGet() in failOn) throw IllegalStateException("interprete di prova")
      return real.run(rgb)
    }

    override fun close() = real.close()
  }

  private class FlakyDetector(private val real: DetectorSession, private val failOn: Set<Int>, private val runs: AtomicInteger) : DetectorSession {
    override fun run(frame: FloatArray): YuNetOutputs {
      if (runs.incrementAndGet() in failOn) throw IllegalStateException("interprete di prova")
      return real.run(frame)
    }

    override fun close() = real.close()
  }

  /** Un volto allineato disegnato (nessun volto vero): ovali, righe e grana, diversi per [seed]. */
  private fun drawn(seed: Int): AlignedFace {
    val side = FaceEngineLimits.ALIGNED_SIDE
    val random = Random(seed.toLong() * 7919 + 13)
    val base = intArrayOf(120 + random.nextInt(100), 90 + random.nextInt(100), 80 + random.nextInt(100))
    val pixels = IntArray(side * side) { i ->
      val x = i % side
      val y = i / side
      val inFace = ((x - 56) / 38.0) * ((x - 56) / 38.0) + ((y - 60) / 46.0) * ((y - 60) / 46.0) <= 1.0
      val eyes = (y in 48..56) && (x in 28..46 || x in 66..84)
      val mouth = y in 88..94 && x in 42..70
      val noise = random.nextInt(21) - 10
      val c = IntArray(3) { k ->
        (if (!inFace) 60 + seed * 3 else if (eyes) 35 else if (mouth) 150 else base[k]) + noise
      }
      (0xFF shl 24) or (c[0].coerceIn(0, 255) shl 16) or (c[1].coerceIn(0, 255) shl 8) or c[2].coerceIn(0, 255)
    }
    return AlignedFace(pixels)
  }

  @Test
  fun unInterpreteCheLanciaSuUnVoltoNonFaPerdereGliAltri() = runBlocking<Unit> {
    val files = FacesDevice.files()
    val faces = (0 until 6).map(::drawn)
    val clean = SFaceEmbedder.create(files)
    val baseline = clean.embed(faces)
    clean.release()
    baseline.forEach { FaceEnginesContract.checkVector(checkNotNull(it)) }

    val real = FaceModelOpeners.embedder(files, ModelCatalog.FACE_SFACE, FaceEngineLimits.CPU_THREADS)
    val runs = AtomicInteger()
    val flaky = SFaceEmbedder(EmbedderOpener { FlakyEmbedder(real.open(), setOf(2, 5), runs) })
    val got = flaky.embed(faces)
    assertEquals(6, got.size)
    assertNull("il secondo volto lancia", got[1])
    assertNull("il quinto volto lancia", got[4])
    for (i in listOf(0, 2, 3, 5)) {
      val vector = checkNotNull(got[i]) { "il volto $i non deve perdersi" }
      assertTrue("volto $i: coseno ${FaceEnginesContract.cosine(vector, baseline[i]!!)}", FaceEnginesContract.cosine(vector, baseline[i]!!) >= 0.9999f)
    }
    assertEquals(2, flaky.thrown)
    assertEquals(6, flaky.inferences)
    // La sessione e' ancora buona: la richiesta dopo risponde per tutti.
    val again = flaky.embed(faces)
    assertTrue(again.all { it != null })
    assertEquals(1, flaky.opened)
    flaky.release()
  }

  @Test
  fun treGuastiDiFilaFermanoIlMotoreEQuelloDopoRiparteDaUnaSessioneNuova() = runBlocking<Unit> {
    val files = FacesDevice.files()
    val faces = (0 until 5).map(::drawn)
    val real = FaceModelOpeners.embedder(files, ModelCatalog.FACE_SFACE, FaceEngineLimits.CPU_THREADS)
    val runs = AtomicInteger()
    val flaky = SFaceEmbedder(EmbedderOpener { FlakyEmbedder(real.open(), setOf(1, 2, 3), runs) })
    val error = runCatching { flaky.embed(faces) }.exceptionOrNull() as? AiEngineException
    assertEquals(AiEngineError.BAD_INPUT, error?.error)
    // La sessione nuova e' una sessione vera con i suoi contatori da zero: la richiesta dopo va a buon fine.
    val after = flaky.embed(faces)
    assertTrue(after.all { it != null })
    assertEquals(2, flaky.opened)
    flaky.release()
  }

  @Test
  fun unGuastoDelRilevatoreEUnCodiceELaRichiestaDopoSiAprePulita() = runBlocking<Unit> {
    val files = FacesDevice.files()
    val real = FaceModelOpeners.detector(files, ModelCatalog.FACE_YUNET, FaceEngineLimits.CPU_THREADS)
    val runs = AtomicInteger()
    val detector = YuNetDetector(DetectorOpener { FlakyDetector(real.open(), setOf(1), runs) })
    val image = FaceImage(IntArray(640 * 480) { i -> (0xFF shl 24) or (((i % 640) * 255 / 640) shl 16) or (((i / 640) * 255 / 480) shl 8) or 90 }, 640, 480)
    val error = runCatching { detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as? AiEngineException
    assertEquals(AiEngineError.BAD_INPUT, error?.error)
    // La sessione guasta si e' chiusa; quella dopo e' nuova e risponde (qui: nessun volto in un gradiente).
    assertTrue(detector.detect(image, DetectPass.PREVIEW).isEmpty())
    assertEquals(2, detector.opened)
    assertEquals(1, detector.inferences)
    detector.release()
  }

  @Test
  fun ilRilascioEIlRiavvioSonoIdempotentiELaMemoriaTorna() = runBlocking<Unit> {
    val engines = FacesDevice.engines()
    val faces = (0 until 3).map(::drawn)
    val image = FaceImage(IntArray(1024 * 768) { (0xFF shl 24) or 0x303030 }, 1024, 768)
    engines.release() // senza motori caricati
    val before = FacesDevice.pssMb()
    repeat(3) {
      engines.detector.detect(image, DetectPass.PREVIEW)
      engines.embedder.embed(faces)
      engines.release()
      engines.release()
    }
    val vectors = engines.embedder.embed(faces)
    assertTrue(vectors.all { it != null })
    engines.release()
    // Tre cicli di carico e rilascio non fanno crescere la memoria a dismisura (modelli ~20 MB + buffer ~40 MB: margine largo per l'allocatore).
    val grown = FacesDevice.pssMb() - before
    FacesDevice.record("pss_dopo_cicli_di_rilascio") { put("pss_prima_mb", before); put("crescita_mb", grown) }
    assertTrue("PSS cresciuta di $grown MB dopo 3 cicli", grown < 120.0)
  }
}
