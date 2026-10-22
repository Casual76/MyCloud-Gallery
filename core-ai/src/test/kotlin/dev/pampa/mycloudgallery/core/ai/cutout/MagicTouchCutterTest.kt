package dev.pampa.mycloudgallery.core.ai.cutout

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixtures
import dev.pampa.mycloudgallery.core.ai.testing.CutoutQuality
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 6 (P13, D44, E-V10): la POLITICA del ritagliatore (una richiesta alla volta, errori come codici, rilascio idempotente e riapertura,
 * niente soggetto dove il modello non da' un soggetto chiaro) con sessioni finte: LiteRT non gira sulla JVM, il modello vero si prova
 * sul dispositivo (`CutoutFixturesDeviceTest`).
 */
class MagicTouchCutterTest {

  /**
   * Una sessione finta che fa "da modello": dato il quadro disegna come probabilita' la forma che l'immagine mostra al punto toccato
   * (i pixel simili a quello sotto il dito, con una tolleranza), alla risoluzione [side]. Conta le chiamate e quante girano insieme.
   */
  private class ColorSession(override val side: Int = 128, private val shared: Shared = Shared()) : MaskSession {
    class Shared {
      val running = AtomicInteger()
      val maxRunning = AtomicInteger()
      val runs = AtomicInteger()
      val closes = AtomicInteger()
      var failRun: Throwable? = null
      var delayMs: Long = 0
    }

    override val label: String = "finto"

    override fun run(frame: ModelFrame, nx: Float, ny: Float, identity: Any?): FloatArray {
      shared.runs.incrementAndGet()
      val now = shared.running.incrementAndGet()
      shared.maxRunning.updateAndGet { max(it, now) }
      try {
        if (shared.delayMs > 0) Thread.sleep(shared.delayMs)
        shared.failRun?.let { throw it }
        val s = frame.side
        val px = (nx * s).toInt().coerceIn(0, s - 1)
        val py = (ny * s).toInt().coerceIn(0, s - 1)
        val c = (py * s + px) * 3
        val target = floatArrayOf(frame.rgb[c], frame.rgb[c + 1], frame.rgb[c + 2])
        return FloatArray(s * s) { i ->
          val d = hypot(hypot(frame.rgb[i * 3] - target[0], frame.rgb[i * 3 + 1] - target[1]), frame.rgb[i * 3 + 2] - target[2])
          if (d < 70f) 1f else 0f
        }
      } finally {
        shared.running.decrementAndGet()
      }
    }

    override fun close() {
      shared.closes.incrementAndGet()
    }
  }

  private fun cutter(shared: ColorSession.Shared = ColorSession.Shared(), side: Int = 128, tuning: MagicTouchTuning = MagicTouchTuning.DEFAULT) =
    MagicTouchCutter.forTesting({ ColorSession(side, shared) }, tuning)

  @Test
  fun suUnaFormaPienaDaLaMascheraEIlRettangolo() = runBlocking {
    val fixture = CutoutFixtures.named("due-oggetti")
    val result = cutter(side = 256).cut(fixture.image(), fixture.point)
    result as CutoutResult.Subject
    // La sessione finta non e' MagicTouch (un'ombra e sfumature la confondono): basta che il soggetto sia quello toccato.
    assertThat(CutoutQuality.iou(result.mask, fixture.truth!!)).isGreaterThan(0.8f)
    assertThat(result.mask.width).isEqualTo(fixture.width)
    assertThat(result.bounds.left).isLessThan(190)
    assertThat(result.bounds.right).isGreaterThan(190)
  }

  @Test
  fun doveIlModelloNonDaUnSoggettoChiaroNonSucedeNiente() = runBlocking {
    // E-V10: un cielo uniforme: la sessione finta dice "tutto" (copertura sopra il 90%): nessun soggetto, e nessun errore.
    val pixels = IntArray(200 * 150) { 0xFF87CEEB.toInt() }
    val result = cutter().cut(AiTestInputs.cutout(200, 150, pixels), NormalizedPoint(0.5f, 0.5f))
    assertThat(result).isEqualTo(CutoutResult.NoClearSubject)
  }

  @Test
  fun lePressioniContemporaneeSiMettonoInFila() = runBlocking {
    val shared = ColorSession.Shared().apply { delayMs = 15 }
    val cutter = cutter(shared)
    val fixture = CutoutFixtures.named("due-oggetti")
    val image = fixture.image()
    val calls: List<Deferred<CutoutResult>> = (0 until 6).map { async { cutter.cut(image, fixture.point) } }
    calls.awaitAll()
    assertThat(shared.runs.get()).isEqualTo(6)
    assertThat(shared.maxRunning.get()).isEqualTo(1)
    // Una sola sessione per tutte.
    assertThat(cutter.opened).isEqualTo(1)
  }

  @Test
  fun ilRilascioEIdempotenteEDopoIlRilascioSiRiapre() = runBlocking {
    val shared = ColorSession.Shared()
    val cutter = cutter(shared)
    val fixture = CutoutFixtures.named("due-oggetti")
    cutter.cut(fixture.image(), fixture.point)
    assertThat(cutter.opened).isEqualTo(1)
    cutter.release()
    cutter.release()
    assertThat(shared.closes.get()).isEqualTo(1)
    cutter.cut(fixture.image(), fixture.point)
    assertThat(cutter.opened).isEqualTo(2)
    cutter.release()
    assertThat(shared.closes.get()).isEqualTo(2)
  }

  @Test
  fun ilRilascioSenzaAverMaiLavoratoNonFaNiente() = runBlocking {
    val shared = ColorSession.Shared()
    cutter(shared).release()
    assertThat(shared.closes.get()).isEqualTo(0)
  }

  @Test
  fun unGuastoDelModelloELaScintillaDiUnCodiceEIlMotoreResta() = runBlocking {
    val shared = ColorSession.Shared().apply { failRun = IllegalStateException("dentro la libreria") }
    val cutter = cutter(shared)
    val fixture = CutoutFixtures.named("due-oggetti")
    val result = cutter.cut(fixture.image(), fixture.point)
    assertThat(result).isEqualTo(CutoutResult.Failed(AiEngineError.BAD_INPUT))
    // Un'inferenza che lancia su un ingresso valido non chiude il motore: la prossima funziona.
    shared.failRun = null
    assertThat(cutter.cut(fixture.image(), fixture.point)).isInstanceOf(CutoutResult.Subject::class.java)
    assertThat(cutter.opened).isEqualTo(1)
  }

  @Test
  fun laMemoriaFinitaChiudeIlMotoreEGliDaUnCodice() = runBlocking {
    val shared = ColorSession.Shared().apply { failRun = OutOfMemoryError() }
    val cutter = cutter(shared)
    val fixture = CutoutFixtures.named("due-oggetti")
    assertThat(cutter.cut(fixture.image(), fixture.point)).isEqualTo(CutoutResult.Failed(AiEngineError.OUT_OF_MEMORY))
    assertThat(shared.closes.get()).isEqualTo(1)
    shared.failRun = null
    assertThat(cutter.cut(fixture.image(), fixture.point)).isInstanceOf(CutoutResult.Subject::class.java)
    assertThat(cutter.opened).isEqualTo(2)
  }

  @Test
  fun senzaIlModelloOSeNonParteIlCodiceEGiusto() = runBlocking {
    val missing = MagicTouchCutter.forTesting({ throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE) })
    val fixture = CutoutFixtures.named("due-oggetti")
    assertThat(missing.cut(fixture.image(), fixture.point)).isEqualTo(CutoutResult.Failed(AiEngineError.MODEL_UNAVAILABLE))
    val broken = MagicTouchCutter.forTesting({ throw UnsatisfiedLinkError("libreria nativa") })
    assertThat(broken.cut(fixture.image(), fixture.point)).isEqualTo(CutoutResult.Failed(AiEngineError.INIT_FAILED))
    val noMemory = MagicTouchCutter.forTesting({ throw OutOfMemoryError() })
    assertThat(noMemory.cut(fixture.image(), fixture.point)).isEqualTo(CutoutResult.Failed(AiEngineError.OUT_OF_MEMORY))
  }

  @Test
  fun lAnnullamentoPassaComEEIlMotoreResta() {
    val shared = ColorSession.Shared().apply { delayMs = 200 }
    val cutter = cutter(shared)
    val fixture = CutoutFixtures.named("due-oggetti")
    assertThrows(CancellationException::class.java) {
      runBlocking {
        val job = async { cutter.cut(fixture.image(), fixture.point) }
        delay(30)
        job.cancel()
        job.await()
      }
    }
    shared.delayMs = 0
    runBlocking {
      assertThat(cutter.cut(fixture.image(), fixture.point)).isInstanceOf(CutoutResult.Subject::class.java)
      cutter.release()
    }
  }

  @Test
  fun unaPanoramicaSiGuardaAFinestreEIlModelloVedeProporzioniNormali() = runBlocking {
    val seen = ArrayList<Triple<ModelFrame, Float, Float>>()
    val cutter = MagicTouchCutter.forTesting({
      object : MaskSession {
        override val label = "finto"
        override val side = 128

        override fun run(frame: ModelFrame, nx: Float, ny: Float, identity: Any?): FloatArray {
          seen += Triple(frame, nx, ny)
          return FloatArray(frame.side * frame.side)
        }

        override fun close() = Unit
      }
    })
    // 2400x600 (4:1): la finestra e' 900x600 intorno al punto.
    val pixels = IntArray(2400 * 600) { 0xFF222222.toInt() }
    cutter.cut(AiTestInputs.cutout(2400, 600, pixels), NormalizedPoint(0.5f, 0.5f))
    val (frame, nx, ny) = seen.single()
    assertThat(frame.contentWidth).isEqualTo(128)
    assertThat(frame.contentHeight).isEqualTo(128)
    assertThat(nx).isWithin(1e-3f).of(0.5f)
    assertThat(ny).isWithin(1e-3f).of(0.5f)
  }

  @Test
  fun ilSoggettoDiUnaPanoramicaSiRitagliaNelleCoordinateDellImmagineIntera() = runBlocking {
    // 2400x600: sfondo scuro con un rettangolo chiaro a x = 1900..2100, y = 200..400; il dito e' sul rettangolo, vicino al bordo destro.
    val w = 2400
    val h = 600
    val inside = { i: Int -> (i % w) in 1900 until 2100 && (i / w) in 200 until 400 }
    val pixels = IntArray(w * h) { if (inside(it)) 0xFFEEEEEE.toInt() else 0xFF222222.toInt() }
    val truth = BooleanArray(w * h) { inside(it) }
    val result = cutter(side = 256).cut(AiTestInputs.cutout(w, h, pixels), NormalizedPoint(2000f / w, 0.5f))
    result as CutoutResult.Subject
    assertThat(result.mask.width).isEqualTo(w)
    assertThat(CutoutQuality.iou(result.mask, truth)).isGreaterThan(0.9f)
    assertThat(result.bounds.left).isIn(1890..1910)
    assertThat(result.bounds.right).isIn(2090..2110)
    assertThat(result.bounds.top).isIn(190..210)
  }

  @Test
  fun leMisureDellUltimoRitaglioSiLeggono() = runBlocking {
    val cutter = cutter(side = 256)
    val fixture = CutoutFixtures.named("due-oggetti")
    assertThat(cutter.lastTimings).isNull()
    cutter.cut(fixture.image(), fixture.point)
    val t = cutter.lastTimings!!
    assertThat(t.totalMs).isAtLeast(0)
    assertThat(cutter.lastStats!!.stats!!.coverage).isGreaterThan(0.01f)
  }
}
