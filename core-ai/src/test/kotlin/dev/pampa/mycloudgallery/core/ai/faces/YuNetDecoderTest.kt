package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Random
import org.junit.Test

/**
 * Fase 8 (P2, riga P2 del §4.4: "decodifica delle uscite e NMS su tensori registrati"). La decodifica di YuNet e l'NMS contro il
 * decodificatore di riferimento di P1 (`common.decodifica_yunet`, provato da P1 contro `FaceDetectorYN` di OpenCV) sulle fixture di
 * `src/test/resources/faces/` (solo numeri), le due soglie, le proprieta' dell'NMS su uscite a caso e le uscite ostili.
 */
class YuNetDecoderTest {

  private val threshold = 0.6f
  private val nmsIou = 0.3f

  // --- Contro il riferimento di P1 --------------------------------------------------------------------------------------------

  @Test
  fun leFixtureCiSonoESonoTutteLeScene() {
    val scenes = FaceFixtures.tensorScenes()
    assertThat(scenes.map { it.name }).containsAtLeast("composizione-a-img-000", "composizione-b-img-002", "senza-volti", "soglia-e-nms")
    assertThat(scenes.maxOf { it.expected.size }).isAtLeast(8)
    assertThat(scenes.sumOf { it.cells.size }).isGreaterThan(400)
  }

  @Test
  fun ilDecodificatoreDaGliStessiRilevamentiDelRiferimentoDiP1() {
    for (scene in FaceFixtures.tensorScenes()) {
      val found = YuNetDecoder.decode(scene.outputs(), threshold, nmsIou)
      assertWithMessage("$scene: punteggi").that(found.map { it.score }).isInOrder(compareByDescending<Float> { it })
      assertWithMessage("$scene: quanti").that(found).hasSize(scene.expected.size)
      for ((got, want) in found.zip(scene.expected)) {
        assertWithMessage("$scene: punteggio").that(got.score).isWithin(1e-5f).of(want.score)
        assertWithMessage("$scene: x").that(got.x).isWithin(TOLERANCE_PX).of(want.x)
        assertWithMessage("$scene: y").that(got.y).isWithin(TOLERANCE_PX).of(want.y)
        assertWithMessage("$scene: larghezza").that(got.width).isWithin(TOLERANCE_PX).of(want.w)
        assertWithMessage("$scene: altezza").that(got.height).isWithin(TOLERANCE_PX).of(want.h)
        for (i in 0 until 10) assertWithMessage("$scene: punto $i").that(got.landmarks[i]).isWithin(TOLERANCE_PX).of(want.landmarks[i])
      }
    }
  }

  @Test
  fun leDueSogliePassanoDoveDeve() {
    val scene = FaceFixtures.tensorScenes().first { it.name == "soglia-e-nms" }
    val candidates = YuNetDecoder.candidates(scene.outputs(), threshold)
    // 0,598 non e' un candidato, 0,603 si'.
    assertThat(candidates.none { it.score in 0.597f..0.599f }).isTrue()
    assertThat(candidates.count { it.score in 0.602f..0.604f }).isEqualTo(1)
    // L'NMS: A (0,95) resta, B (IoU 0,34 con A) sparisce, C (IoU 0,26 con A) resta; le tre celle uguali a 0,80 diventano un volto.
    val kept = YuNetDecoder.nms(candidates, nmsIou)
    assertThat(kept).hasSize(4)
    for ((got, want) in kept.zip(listOf(0.95f, 0.85f, 0.80f, 0.603f))) assertThat(got.score).isWithin(1e-5f).of(want)
    val a = kept[0]
    val b = candidates.single { it.score in 0.899f..0.901f }
    val c = kept[1]
    assertThat(YuNetDecoder.iou(a, b)).isGreaterThan(0.3f)
    assertThat(YuNetDecoder.iou(a, c)).isLessThan(0.3f)
  }

  @Test
  fun laSogliaEMaggioreOUguale_ESottoNo() {
    val out = YuNetOutputs.allocate()
    val box = floatArrayOf(100f, 100f, 80f, 80f)
    TensorSynth.put(out, 16, 8, 8, 0.7f, box, TensorSynth.faceLandmarks(box))
    val score = kotlin.math.sqrt(out.cls[1][8 * 64 + 8] * out.obj[1][8 * 64 + 8])
    // Con la soglia esattamente uguale al punteggio la cella entra (>=); un'unita' di ultima cifra sopra no.
    assertThat(YuNetDecoder.candidates(out, score)).hasSize(1)
    assertThat(YuNetDecoder.candidates(out, Math.nextUp(score))).isEmpty()
  }

  @Test
  fun ilPunteggioEIlProdottoDiClsEObjSottoRadice() {
    val out = YuNetOutputs.allocate()
    val box = floatArrayOf(300f, 300f, 64f, 64f)
    TensorSynth.put(out, 16, 20, 20, 0.5f, box, TensorSynth.faceLandmarks(box))
    out.cls[1][20 * 64 + 20] = 0.9f
    out.obj[1][20 * 64 + 20] = 0.5f // radice(0,45) = 0,67
    val found = YuNetDecoder.decode(out, 0.6f, 0.3f)
    assertThat(found.single().score).isWithin(1e-6f).of(Math.sqrt(0.45).toFloat())
    // Fuori da 0..1 si porta dentro: cls = 3 vale 1.
    out.cls[1][20 * 64 + 20] = 3f
    out.obj[1][20 * 64 + 20] = 0.7f
    assertThat(YuNetDecoder.decode(out, 0.6f, 0.3f).single().score).isWithin(1e-6f).of(Math.sqrt(0.7).toFloat())
  }

  // --- Le proprieta' dell'NMS su uscite a caso ----------------------------------------------------------------------------------

  @Test
  fun nmsTieneIMigliori_NessunaCoppiaSiSovrappone_EChiVieneScartatoHaUnoMigliore() {
    val random = Random(20261028)
    repeat(40) { round ->
      val out = YuNetOutputs.allocate()
      repeat(12 + random.nextInt(25)) {
        val w = 20f + random.nextFloat() * 200f
        val h = w * (0.8f + random.nextFloat() * 0.5f)
        val box = floatArrayOf(random.nextFloat() * 800f, random.nextFloat() * 800f, w, h)
        TensorSynth.putFace(out, box, TensorSynth.faceLandmarks(box), 0.62f + random.nextFloat() * 0.35f, neighbours = random.nextBoolean())
      }
      val all = YuNetDecoder.candidates(out, threshold)
      val kept = YuNetDecoder.nms(all, nmsIou)
      assertWithMessage("round $round").that(kept).isNotEmpty()
      assertWithMessage("ordine").that(kept.map { it.score }).isInOrder(compareByDescending<Float> { it })
      for (i in kept.indices) for (j in i + 1 until kept.size) {
        assertWithMessage("coppia $i,$j").that(YuNetDecoder.iou(kept[i], kept[j])).isAtMost(nmsIou)
      }
      // Massimalita': ogni candidato scartato ha un riquadro tenuto, di punteggio non minore, con IoU sopra la soglia.
      val keptSet = kept.toSet()
      for (candidate in all) {
        if (candidate in keptSet) continue
        assertWithMessage("scartato senza motivo").that(kept.any { it.score >= candidate.score && YuNetDecoder.iou(it, candidate) > nmsIou }).isTrue()
      }
    }
  }

  @Test
  fun nmsRispettaIlTettoDeiCandidati() {
    // 20.000 candidati con riquadri diversi e lontani: ne entrano al piu' 5.000 e restano i migliori.
    val items = (0 until 20_000).map { i ->
      RawDetection((i % 200) * 10f, (i / 200) * 10f, 2f, 2f, FloatArray(10), 0.6f + (i % 1000) * 0.0003f)
    }
    val kept = YuNetDecoder.nms(items, 0.3f)
    assertThat(kept.size).isAtMost(YuNetDecoder.MAX_CANDIDATES)
    assertThat(kept.minOf { it.score }).isAtLeast(items.sortedByDescending { it.score }[YuNetDecoder.MAX_CANDIDATES - 1].score)
  }

  // --- Uscite ostili --------------------------------------------------------------------------------------------------------

  @Test
  fun usciteNonFiniteONonsenseNonDannoMaiEccezioniNeRiquadriInvalidi() {
    val out = YuNetOutputs.allocate()
    val box = floatArrayOf(200f, 200f, 80f, 80f)
    TensorSynth.putFace(out, box, TensorSynth.faceLandmarks(box), 0.9f)
    // Il volto sta al passo 16, centro (240, 240): cella 15, 15. La centrale ha un riquadro non finito; le vicine (0,75) lo portano uguale.
    val s = 1
    out.bbox[s][(15 * 64 + 15) * 4 + 2] = Float.NaN
    // Una cella con l'esponente che trabocca, una con un punto infinito, una con NaN nel punteggio: nessuna esce.
    val overflow = 40 * 64 + 40
    out.cls[s][overflow] = 1f
    out.obj[s][overflow] = 1f
    out.bbox[s][overflow * 4 + 2] = 1000f
    val badPoint = 50 * 64 + 10
    out.cls[s][badPoint] = 1f
    out.obj[s][badPoint] = 1f
    out.kps[s][badPoint * 10 + 3] = Float.POSITIVE_INFINITY
    val nan = 10 * 64 + 50
    out.cls[s][nan] = Float.NaN
    out.obj[s][nan] = 1f
    val found = YuNetDecoder.decode(out, threshold, nmsIou)
    assertThat(found).isNotEmpty()
    for (d in found) {
      assertThat(d.width).isGreaterThan(0f)
      assertThat(d.height).isGreaterThan(0f)
      assertThat((listOf(d.x, d.y, d.width, d.height, d.score) + d.landmarks.toList()).all { it.isFinite() }).isTrue()
    }
    // Nessun candidato da quelle tre celle: restano i soli del volto vero (punteggio 0,75: il centro e' escluso).
    assertThat(found.all { it.score in 0.74f..0.76f }).isTrue()
    assertThat(found).hasSize(1)
  }

  @Test
  fun tuttiICandidatiAUno_ilTettoTieneBassaLaSpesa() {
    val out = YuNetOutputs.allocate()
    for (scale in 0 until 3) {
      java.util.Arrays.fill(out.cls[scale], 1f)
      java.util.Arrays.fill(out.obj[scale], 1f)
    }
    // bbox e kps a zero: ogni cella prevede un quadrato di lato `passo` centrato sul suo angolo: 21.504 candidati, i primi 5.000 (tutti a
    // punteggio 1: l'ordine di arrivo) entrano nell'NMS. Resta un insieme senza sovrapposizioni.
    val started = System.nanoTime()
    val found = YuNetDecoder.decode(out, threshold, nmsIou)
    val millis = (System.nanoTime() - started) / 1_000_000
    assertThat(found).isNotEmpty()
    assertThat(found.size).isAtMost(YuNetDecoder.MAX_CANDIDATES)
    for (i in found.indices) if (i % 97 == 0) for (j in found.indices) if (i != j) assertThat(YuNetDecoder.iou(found[i], found[j])).isAtMost(nmsIou)
    assertThat(millis).isLessThan(30_000)
  }

  private companion object {
    /** L'atteso di P1 e' in virgola mobile a 64 bit con gli ingressi float32; in float32 le coordinate (fino a ~1000) hanno 6e-5 di risoluzione. */
    const val TOLERANCE_PX = 2e-3f
  }
}
