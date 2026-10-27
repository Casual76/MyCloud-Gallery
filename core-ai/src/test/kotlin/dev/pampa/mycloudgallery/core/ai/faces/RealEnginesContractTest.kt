package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.testing.FaceEnginesContract
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceTier
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P2, riga P2 del §4.4: "suite di contratto `FaceEnginesContract`, comune ai motori finti e a quelli veri"). La stessa suite che gira sul
 * motore finto (`FakeFaceEnginesTest`) gira qui sui motori VERI (`YuNetDetector`, `SFaceEmbedder`, `FaceAligner`, `FaceQuality`) sopra le sessioni
 * finte che guardano i pixel (`FakeFaceSessions`); sul dispositivo, con i modelli veri, la fanno `FaceEnginesContractDeviceTest` e la parita'.
 * In piu': il giro intero che fara' il calcolo (rileva, allinea, giudica, calcola il vettore) riconosce l'identita' giusta di ogni volto di una
 * foto di gruppo, qualunque sia la sua posizione e la sua scala, e assegna i livelli di D19.
 */
class RealEnginesContractTest {

  private val sessions = FakeFaceSessions()
  private val engines = FaceEngines(YuNetDetector(sessions.detectorOpener), SFaceEmbedder(sessions.embedderOpener), FaceAligner, FaceQuality)

  private fun render(w: Int, h: Int, vararg faces: PixelFace): FaceImage = PixelFaceWorld.render(PixelScene(w, h, faces.toList()))

  @Test
  fun laSuiteDiContrattoPassaSuiMotoriVeri() = runTest {
    val images = listOf(
      render(640, 480, PixelFace(1, 200, 100, 180, 220)),
      render(1024, 768, PixelFace(2, 60, 60, 140, 160), PixelFace(3, 400, 100, 120, 150), PixelFace(4, 700, 400, 200, 260)),
      render(1600, 1200, PixelFace(5, 500, 300, 300, 360)),
    )
    FaceEnginesContract.verify(engines, images, withoutFaces = listOf(render(800, 600), render(64, 64)))
    assertThat(sessions.maxConcurrent.get()).isEqualTo(1)
  }

  /** Il giro di P4 su una foto: rileva, allinea dalla stessa immagine, giudica, calcola il vettore dei volti che lo hanno. */
  private suspend fun analyze(image: FaceImage): List<Analyzed> {
    val faces = engines.detector.detect(image, DetectPass.PREVIEW)
    val out = ArrayList<Analyzed>()
    for (face in faces) {
      val side = face.box.shortSide.toInt()
      val aligned = engines.aligner.align(image, face.landmarks)
      val verdict = engines.quality.assess(face, side, aligned)
      val vector = if (verdict.tier.hasVector) engines.embedder.embed(listOf(aligned)).single() else null
      out += Analyzed((face.box.x0 + face.box.x1) / 2f, (face.box.y0 + face.box.y1) / 2f, verdict.tier, verdict.reasons, vector)
    }
    return out
  }

  private class Analyzed(val cx: Float, val cy: Float, val tier: FaceTier, val reasons: Int, val vector: FloatArray?)

  /** L'identita' il cui vettore e' piu' vicino a [vector] fra le prime [count]. */
  private fun nearestIdentity(vector: FloatArray, count: Int): Int =
    (0 until count).maxByOrNull { SyntheticIdentities.cosine(vector, sessions.identities.vector(it, 1L, 0.3f)) }!!

  @Test
  fun ognuniVoltoDiUnaFotoDiGruppoHaLaSuaIdentitaQualunqueSianoPosizioneEScala() = runTest {
    val faces = listOf(
      PixelFace(7, 30, 40, 70, 80),
      PixelFace(8, 250, 60, 160, 190),
      PixelFace(9, 520, 80, 300, 340),
      PixelFace(10, 80, 420, 90, 110),
      PixelFace(11, 500, 450, 200, 230),
    )
    for ((w, h) in listOf(1024 to 768, 2048 to 1536, 512 to 384)) {
      val scale = w / 1024f
      val scaled = faces.map { PixelFace(it.identity, (it.x * scale).toInt(), (it.y * scale).toInt(), (it.w * scale).toInt(), (it.h * scale).toInt()) }
      val result = analyze(render(w, h, *scaled.toTypedArray()))
      assertWithMessage("${w}x$h").that(result).hasSize(5)
      for (f in scaled) {
        val hit = result.single { kotlin.math.abs(it.cx - (f.x + f.w / 2f)) < 4f && kotlin.math.abs(it.cy - (f.y + f.h / 2f)) < 4f }
        assertWithMessage("${w}x$h identita ${f.identity}: vettore").that(hit.vector).isNotNull()
        assertWithMessage("${w}x$h: identita' del volto in ${f.x},${f.y}").that(nearestIdentity(hit.vector!!, 20)).isEqualTo(f.identity)
      }
    }
  }

  @Test
  fun iLivelliSeguonoLaDimensioneNellaSorgenteDelLAllineamento() = runTest {
    // A 1024 px: 160 px (CORE), 40 px (WEAK, piccolo), 20 px (NONE, nessun vettore).
    val result = analyze(
      render(1024, 768, PixelFace(1, 100, 100, 160, 190), PixelFace(2, 500, 100, 40, 46), PixelFace(3, 800, 100, 20, 24)),
    )
    val byX = result.sortedBy { it.cx }
    assertThat(byX.map { it.tier }).containsExactly(FaceTier.CORE, FaceTier.WEAK, FaceTier.NONE).inOrder()
    assertThat(byX[0].reasons).isEqualTo(0)
    assertThat(byX[1].reasons and FaceReasons.SMALL).isEqualTo(FaceReasons.SMALL)
    assertThat(byX[2].vector).isNull()
    assertThat(byX[0].vector).isNotNull()
    assertThat(byX[1].vector).isNotNull()
    // Lo stesso volto in un'immagine a 2048 px ha il lato corto di 80 px (l'anteprima la riduce a meta' e il riquadro torna alle coordinate vere): CORE.
    val hq = render(2048, 1536, PixelFace(2, 1000, 200, 80, 92))
    val face = engines.detector.detect(hq, DetectPass.PREVIEW).single()
    assertThat(engines.quality.assess(face, face.box.shortSide.toInt(), engines.aligner.align(hq, face.landmarks)).tier).isEqualTo(FaceTier.CORE)
  }

  @Test
  fun unVoltoSenzaGrana_Sfocato_EDeboleConIlMotivo() = runTest {
    val flat = render(800, 600, PixelFace(4, 200, 100, 200, 240, texture = 0))
    val face = engines.detector.detect(flat, DetectPass.PREVIEW).single()
    val aligned = engines.aligner.align(flat, face.landmarks)
    val verdict = engines.quality.assess(face, face.box.shortSide.toInt(), aligned)
    assertThat(verdict.tier).isEqualTo(FaceTier.WEAK)
    assertThat(verdict.reasons and FaceReasons.BLURRY).isEqualTo(FaceReasons.BLURRY)
  }

  @Test
  fun dopoIlRilascioTuttoRipartePulito() = runTest {
    val image = render(640, 480, PixelFace(1, 100, 100, 160, 190))
    val before = analyze(image)
    engines.release()
    engines.release()
    val after = analyze(image)
    assertThat(after).hasSize(before.size)
    assertThat(SyntheticIdentities.cosine(after.single().vector!!, before.single().vector!!)).isAtLeast(0.999f)
    assertThat(sessions.detectorOpens.get()).isEqualTo(2)
    assertThat(sessions.embedderOpens.get()).isEqualTo(2)
  }
}
