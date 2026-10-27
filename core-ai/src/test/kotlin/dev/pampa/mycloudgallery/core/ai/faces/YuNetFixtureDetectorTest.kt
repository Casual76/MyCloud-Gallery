package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P2): il rilevatore vero (`YuNetDetector`) sopra una sessione che risponde con le uscite delle fixture (le stesse della prova del decodificatore,
 * con l'atteso del decodificatore di riferimento di P1): dalle uscite grezze ai volti che escono, con la conversione alle coordinate dell'immagine, il taglio
 * al bordo e il tetto dei volti. Su un'immagine di 1024 px il quadro e' l'immagine stessa: le coordinate che escono sono quelle dell'atteso di P1.
 */
class YuNetFixtureDetectorTest {

  private fun detectorFor(outputs: YuNetOutputs) = YuNetDetector(
    DetectorOpener {
      object : DetectorSession {
        override fun run(frame: FloatArray): YuNetOutputs = outputs

        override fun close() = Unit
      }
    },
  )

  private val blank = FaceImage(IntArray(1024 * 1024) { 0xFF101010.toInt() }, 1024, 1024)

  @Test
  fun dalleUsciteGrezzeAiVoltiDellAtteso() = runTest {
    for (scene in FaceFixtures.tensorScenes()) {
      val faces = detectorFor(scene.outputs()).detect(blank, DetectPass.PREVIEW)
      assertWithMessage("$scene: quanti").that(faces).hasSize(scene.expected.size)
      for ((face, want) in faces.zip(scene.expected)) {
        assertWithMessage("$scene: punteggio").that(face.score).isWithin(1e-5f).of(want.score)
        // I riquadri escono tenuti dentro l'immagine; quelli dell'atteso sono tutti dentro (le composizioni lo sono).
        val x1 = want.x + want.w
        val y1 = want.y + want.h
        assertWithMessage("$scene: x0").that(face.box.x0).isWithin(2e-3f).of(want.x.coerceAtLeast(0f))
        assertWithMessage("$scene: y0").that(face.box.y0).isWithin(2e-3f).of(want.y.coerceAtLeast(0f))
        assertWithMessage("$scene: x1").that(face.box.x1).isWithin(2e-3f).of(x1.coerceAtMost(1024f))
        assertWithMessage("$scene: y1").that(face.box.y1).isWithin(2e-3f).of(y1.coerceAtMost(1024f))
        for (i in 0 until 10) assertWithMessage("$scene: punto $i").that(face.landmarks[i]).isWithin(2e-3f).of(want.landmarks[i])
      }
    }
  }

  @Test
  fun leCoordinateDelQuadroTornanoAlLImmagineDa2048() = runTest {
    // Lo stesso quadro su un'immagine di 2048x2048: l'anteprima la porta a meta' e i riquadri tornano al doppio.
    val big = FaceImage(IntArray(2048 * 2048) { 0xFF101010.toInt() }, 2048, 2048)
    val scene = FaceFixtures.tensorScenes().first { it.name == "composizione-a-img-001" }
    val faces = detectorFor(scene.outputs()).detect(big, DetectPass.PREVIEW)
    assertThat(faces).hasSize(scene.expected.size)
    for ((face, want) in faces.zip(scene.expected)) {
      assertThat(face.box.x0).isWithin(5e-3f).of((want.x * 2f).coerceAtLeast(0f))
      assertThat(face.box.y1).isWithin(5e-3f).of(((want.y + want.h) * 2f).coerceAtMost(2048f))
      assertThat(face.landmarks[4]).isWithin(5e-3f).of(want.landmarks[4] * 2f)
    }
  }

  @Test
  fun unVoltoTagliatoDalBordoSfora_ILuiTenutoDentro() = runTest {
    val outputs = YuNetOutputs.allocate()
    val box = floatArrayOf(-30f, 100f, 130f, 140f) // 30 px fuori a sinistra
    TensorSynth.putFace(outputs, box, TensorSynth.faceLandmarks(box), 0.9f, neighbours = false)
    val face = detectorFor(outputs).detect(blank, DetectPass.PREVIEW).single()
    assertThat(face.box.x0).isEqualTo(0f)
    assertThat(face.box.x1).isWithin(2e-3f).of(100f)
    assertThat(face.landmarks[0]).isWithin(2e-3f).of(-30f + 130f * 0.30f)
    // Un riquadro del tutto fuori dall'immagine non e' un volto.
    val outside = YuNetOutputs.allocate()
    val far = floatArrayOf(-200f, 100f, 100f, 100f)
    TensorSynth.put(outside, 16, 8, 0, 0.9f, far, TensorSynth.faceLandmarks(far)) // una cella vera (colonna 0) che prevede un riquadro tutto fuori
    assertThat(detectorFor(outside).detect(blank, DetectPass.PREVIEW)).isEmpty()
  }

  @Test
  fun alPiuSessantaquattroVoltiInOrdineDiPunteggio() = runTest {
    val outputs = YuNetOutputs.allocate()
    for (i in 0 until 100) {
      val box = floatArrayOf(10f + (i % 10) * 100f, 10f + (i / 10) * 100f, 60f, 60f)
      TensorSynth.putFace(outputs, box, TensorSynth.faceLandmarks(box), 0.62f + i * 0.003f, neighbours = false)
    }
    val faces = detectorFor(outputs).detect(blank, DetectPass.PREVIEW)
    assertThat(faces).hasSize(64)
    assertThat(faces.map { it.score }).isInOrder(compareByDescending<Float> { it })
    assertThat(faces.first().score).isWithin(1e-5f).of(0.62f + 99 * 0.003f)
  }
}
