package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P2): il rilevatore vero (`YuNetDetector`) sopra le sessioni finte che guardano i pixel (`FakeFaceSessions`): geometria del quadro, bordi,
 * scala, tasselli dell'HQ e coordinate di ritorno, doppi fusi, errori come codici, rilascio e riapertura, richieste in fila.
 */
class YuNetDetectorTest {

  private val sessions = FakeFaceSessions()
  private val detector = YuNetDetector(sessions.detectorOpener)

  private fun render(w: Int, h: Int, vararg faces: PixelFace) = PixelFaceWorld.render(PixelScene(w, h, faces.toList()))

  private fun DetectedFace.centerX() = (box.x0 + box.x1) / 2f

  private fun DetectedFace.centerY() = (box.y0 + box.y1) / 2f

  // --- Anteprima ------------------------------------------------------------------------------------------------------------

  @Test
  fun unVoltoSuUnImmagine1024siTrovaDovePerLaSceneEConIPunti() = runTest {
    val face = PixelFace(identity = 3, x = 300, y = 200, w = 160, h = 200)
    val found = detector.detect(render(1024, 768, face), DetectPass.PREVIEW)
    assertThat(found).hasSize(1)
    val f = found.single()
    assertThat(f.box.x0).isWithin(2f).of(300f)
    assertThat(f.box.y0).isWithin(2f).of(200f)
    assertThat(f.box.x1).isWithin(2f).of(460f)
    assertThat(f.box.y1).isWithin(2f).of(400f)
    assertThat(f.score).isWithin(1e-4f).of(0.95f)
    // Occhi, naso e bocca nelle proporzioni del volto finto.
    val p = f.landmarks
    assertThat(p[0]).isWithin(2f).of(300f + 160 * 0.30f)
    assertThat(p[1]).isWithin(2f).of(200f + 200 * 0.40f)
    assertThat(p[4]).isWithin(2f).of(300f + 160 * 0.50f)
    assertThat(p[9]).isWithin(2f).of(200f + 200 * 0.80f)
    assertThat(sessions.detectorRuns.get()).isEqualTo(1)
  }

  @Test
  fun leImmaginiPiuGrandiSiRiduconoEPiuPiccoleSiIngrandiscono_LeCoordinateTornanoQuelleDellImmagine() = runTest {
    val big = detector.detect(render(2048, 1536, PixelFace(1, 600, 500, 400, 480)), DetectPass.PREVIEW).single()
    assertThat(big.box.x0).isWithin(3f).of(600f)
    assertThat(big.box.x1).isWithin(3f).of(1000f)
    assertThat(big.box.y0).isWithin(3f).of(500f)
    assertThat(big.box.y1).isWithin(3f).of(980f)
    val small = detector.detect(render(512, 384, PixelFace(2, 100, 80, 120, 150)), DetectPass.PREVIEW).single()
    assertThat(small.box.x0).isWithin(2f).of(100f)
    assertThat(small.box.x1).isWithin(2f).of(220f)
    assertThat(small.box.y0).isWithin(2f).of(80f)
    assertThat(small.box.y1).isWithin(2f).of(230f)
    // Un'immagine alta e stretta: il quadro la mette in alto a sinistra con i bordi a destra.
    val tall = detector.detect(render(300, 1000, PixelFace(4, 60, 700, 90, 120)), DetectPass.PREVIEW).single()
    assertThat(tall.centerX()).isWithin(2f).of(105f)
    assertThat(tall.centerY()).isWithin(2f).of(760f)
  }

  @Test
  fun unVoltoTagliatoDalBordoEsceTenutoDentroAlLImmagine_IPuntiNo() = runTest {
    val found = detector.detect(render(800, 600, PixelFace(5, 700, 300, 400, 200)), DetectPass.PREVIEW).single()
    assertThat(found.box.x0).isWithin(2f).of(700f)
    assertThat(found.box.x1).isAtMost(800f)
    assertThat(found.box.x1).isWithin(2f).of(800f)
    assertThat(found.box.y0).isAtLeast(0f)
  }

  @Test
  fun piuVoltiSonoPiuRilevamentiInOrdineDiPunteggio_ENessunDoppioPerLeCelleVicine() = runTest {
    val faces = (0 until 6).map { PixelFace(it, 40 + (it % 3) * 300, 60 + (it / 3) * 300, 140, 160) }
    val found = detector.detect(render(1024, 768, *faces.toTypedArray()), DetectPass.PREVIEW)
    assertThat(found).hasSize(6)
    for (face in faces) {
      assertThat(found.count { kotlin.math.abs(it.centerX() - (face.x + face.w / 2f)) < 4f && kotlin.math.abs(it.centerY() - (face.y + face.h / 2f)) < 4f }).isEqualTo(1)
    }
  }

  @Test
  fun unImmagineSenzaVoltiDaUnaListaVuota_EAlMassimoSessantaquattro() = runTest {
    assertThat(detector.detect(render(640, 480), DetectPass.PREVIEW)).isEmpty()
    val many = (0 until 100).map { PixelFace(it, 10 + (it % 10) * 100, 10 + (it / 10) * 100, 40, 40) }
    val found = detector.detect(render(1024, 1024, *many.toTypedArray()), DetectPass.PREVIEW)
    assertThat(found.size).isEqualTo(FaceEngineLimits.MAX_FACES)
  }

  // --- Seconda passata a tasselli ---------------------------------------------------------------------------------------------

  @Test
  fun iTasselliDiUnaImmagine2048x1536SonoQuattroConSovrapposizione128() {
    val plan = FaceTiling.plan(2048, 1536)
    assertThat(plan.columns).isEqualTo(2)
    assertThat(plan.rows).isEqualTo(2)
    assertThat(plan.regions.map { listOf(it.x, it.y, it.width, it.height) }).containsExactly(
      listOf(0, 0, 1088, 832),
      listOf(960, 0, 1088, 832),
      listOf(0, 704, 1088, 832),
      listOf(960, 704, 1088, 832),
    ).inOrder()
    // Un'immagine che entra nel quadro e' un tassello solo; una larga e bassa, due.
    assertThat(FaceTiling.plan(1024, 1024).regions).hasSize(1)
    assertThat(FaceTiling.plan(2048, 700).regions).hasSize(2)
    for (width in listOf(1025, 1300, 1700, 2048)) {
      val axis = FaceTiling.plan(width, 100).regions
      assertThat(axis.first().x).isEqualTo(0)
      assertThat(axis.last().x + axis.last().width).isEqualTo(width)
      assertThat(axis[0].x + axis[0].width - axis[1].x).isAtLeast(FaceEngineLimits.TILE_OVERLAP)
    }
  }

  @Test
  fun unaSecondaPassataTrovaIVoltiPiccoliCheL_AnteprimaNonVede_ENelleCoordinateDell_HQ() = runTest {
    // 2048x1536: tre volti piccoli, uno per tassello diverso, e uno nella sovrapposizione verticale (x fra 960 e 1088).
    val faces = listOf(
      PixelFace(10, 300, 200, 36, 40),
      PixelFace(11, 1500, 250, 40, 44),
      PixelFace(12, 400, 1100, 34, 38),
      PixelFace(13, 1000, 700, 40, 40), // nella sovrapposizione fra i due tasselli: due tasselli lo vedono intero, esce una volta
    )
    val found = detector.detect(render(2048, 1536, *faces.toTypedArray()), DetectPass.HQ_TILES)
    assertThat(sessions.detectorRuns.get()).isEqualTo(4)
    assertThat(found).hasSize(4)
    for (face in faces) {
      val hit = found.filter { kotlin.math.abs(it.centerX() - (face.x + face.w / 2f)) < 3f && kotlin.math.abs(it.centerY() - (face.y + face.h / 2f)) < 3f }
      assertThat(hit).hasSize(1)
      assertThat(hit.single().box.shortSide).isWithin(3f).of(minOf(face.w, face.h).toFloat())
    }
  }

  @Test
  fun unVoltoAGavallDiUnaGiunturaNonEsceDallHQMaEsceDallAnteprima() = runTest {
    // Largo 300 px a cavallo di x = 1024 (la giunzione fra tasselli e' fra 960 e 1088): tagliato in entrambi i tasselli.
    val face = PixelFace(20, 880, 600, 300, 300)
    val image = render(2048, 1536, face)
    val hq = detector.detect(image, DetectPass.HQ_TILES)
    val preview = detector.detect(image, DetectPass.PREVIEW)
    assertThat(hq).isEmpty()
    assertThat(preview).hasSize(1)
    assertThat(preview.single().box.x0).isWithin(3f).of(880f)
    assertThat(preview.single().box.x1).isWithin(3f).of(1180f)
  }

  @Test
  fun unVoltoAlBordoDell_ImmagineNonEUnaGiunturaEUnVoltoTutto() = runTest {
    val faces = listOf(PixelFace(30, 0, 0, 60, 70), PixelFace(31, 1988, 1466, 60, 70))
    val found = detector.detect(render(2048, 1536, *faces.toTypedArray()), DetectPass.HQ_TILES)
    assertThat(found).hasSize(2)
  }

  @Test
  fun unaSecondaPassataSuUnaImmagineDa1024EUnQuadroSolo() = runTest {
    val found = detector.detect(render(1024, 768, PixelFace(40, 100, 100, 50, 60)), DetectPass.HQ_TILES)
    assertThat(found).hasSize(1)
    assertThat(sessions.detectorRuns.get()).isEqualTo(1)
    assertThat(detector.lastTimings!!.tiles).isEqualTo(1)
  }

  // --- Errori, rilascio, richieste in fila ----------------------------------------------------------------------------------------

  @Test
  fun unGuastoDellaSessioneEUnCodice_LaSessioneSiChiudeEQuellaDopoSiRiapre() = runTest {
    val image = render(640, 480, PixelFace(1, 100, 100, 120, 120))
    sessions.detectorFailOnRuns += 1
    val error = runCatching { detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException
    assertThat(error.error).isEqualTo(AiEngineError.BAD_INPUT)
    assertThat(error.message).isEqualTo("BAD_INPUT") // solo il codice: mai il messaggio della libreria
    assertThat(sessions.detectorCloses.get()).isEqualTo(1)
    assertThat(detector.detect(image, DetectPass.PREVIEW)).hasSize(1)
    assertThat(sessions.detectorOpens.get()).isEqualTo(2)
  }

  @Test
  fun laMemoriaFinitaEUnCodiceEUnaLibreriaCheNonCaricaEInitFailed() = runTest {
    val image = render(640, 480, PixelFace(1, 100, 100, 120, 120))
    sessions.failure = { OutOfMemoryError("troppa") }
    sessions.detectorFailOnRuns += 1
    assertThat((runCatching { detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.OUT_OF_MEMORY)
    sessions.failure = { UnsatisfiedLinkError("libreria") }
    sessions.detectorFailOnRuns += 2
    assertThat((runCatching { detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.INIT_FAILED)
  }

  @Test
  fun unModelloCheNonSiApreEModelUnavailable_EDopoCheTornaSiRiprova() = runTest {
    val image = render(640, 480, PixelFace(1, 100, 100, 120, 120))
    sessions.detectorOpenFailure = AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    assertThat((runCatching { detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
    sessions.detectorOpenFailure = IllegalStateException("la libreria si lamenta")
    assertThat((runCatching { detector.detect(image, DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.INIT_FAILED)
    sessions.detectorOpenFailure = null
    assertThat(detector.detect(image, DetectPass.PREVIEW)).hasSize(1)
  }

  @Test
  fun ilRilascioEIdempotenteEDopoIlRilascioLaProssimaRichiestaRiapre() = runTest {
    val image = render(640, 480, PixelFace(1, 100, 100, 120, 120))
    detector.release() // senza sessione: niente da fare
    assertThat(sessions.detectorCloses.get()).isEqualTo(0)
    detector.detect(image, DetectPass.PREVIEW)
    detector.release()
    detector.release()
    assertThat(sessions.detectorCloses.get()).isEqualTo(1)
    assertThat(detector.detect(image, DetectPass.PREVIEW)).hasSize(1)
    assertThat(sessions.detectorOpens.get()).isEqualTo(2)
  }

  @Test
  fun leRichiesteInParallelaSiMettonoInFila() = runTest {
    val image = render(640, 480, PixelFace(1, 100, 100, 120, 120))
    val results = coroutineScope { (0 until 12).map { async { detector.detect(image, DetectPass.PREVIEW).size } }.awaitAll() }
    assertThat(results).containsExactlyElementsIn(List(12) { 1 })
    assertThat(sessions.maxConcurrent.get()).isEqualTo(1)
    assertThat(sessions.detectorOpens.get()).isEqualTo(1)
  }

  @Test
  fun unaRichiestaAnnullataFraDueTassellineLasciaLaSessioneBuona() = runTest {
    val image = render(2048, 1536, PixelFace(1, 300, 200, 40, 40))
    val started = CompletableDeferred<Unit>()
    sessions.onDetectorRun = { started.complete(Unit) }
    val job = launch(start = CoroutineStart.UNDISPATCHED) { detector.detect(image, DetectPass.HQ_TILES) }
    started.await()
    job.cancelAndJoin()
    sessions.onDetectorRun = {}
    assertThat(sessions.detectorRuns.get()).isAtMost(4)
    // La sessione e' ancora buona: la richiesta dopo risponde.
    assertThat(detector.detect(image, DetectPass.HQ_TILES)).hasSize(1)
  }

  @Test
  fun leMisureDellUltimaRichiestaCiSono() = runTest {
    detector.detect(render(1024, 768, PixelFace(1, 100, 100, 100, 100)), DetectPass.PREVIEW)
    val t = detector.lastTimings!!
    assertThat(t.tiles).isEqualTo(1)
    assertThat(t.totalMs).isAtLeast(t.inferMs)
  }
}
