package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.similar.Framing
import dev.pampa.mycloudgallery.core.library.similar.JpegCodec
import dev.pampa.mycloudgallery.core.library.similar.Raster
import dev.pampa.mycloudgallery.core.library.similar.RasterOps
import dev.pampa.mycloudgallery.core.library.similar.Scene
import dev.pampa.mycloudgallery.core.library.similar.SceneRenderer
import dev.pampa.mycloudgallery.core.library.similar.SignatureComparison
import dev.pampa.mycloudgallery.core.library.similar.SignatureDistance
import dev.pampa.mycloudgallery.core.library.similar.SimilarityRules
import dev.pampa.mycloudgallery.core.library.similar.Subject
import dev.pampa.mycloudgallery.core.library.similar.VisualSignatures
import java.io.File
import java.util.Random
import kotlin.math.sqrt
import kotlin.math.tanh
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La CALIBRAZIONE di `Sdup` e delle soglie della firma visiva per gli scatti simili (Fase 7, P8, D25, §2.1.1: "soglia calibrata in P8 sul set
 * sintetico, valore di partenza 0,95").
 *
 * **Il set sintetico.** Le scene generate di `SceneFixtures` (6 normali, 2 di fogliame, 2 al buio) e, per ognuna, gli scatti che un fotografo fa
 * davvero a distanza di secondi: l'originale e le varianti. Le varianti sono **etichettate per costruzione**, e l'etichetta dipende da chi
 * giudica:
 *  - *stesso momento* (devono finire nello stesso gruppo): il soggetto che si muove (1,5%, 3%, 5%, 20%, 30%), la camera che si sposta del 2% e
 *    del 4%, un ritaglio del 2% e del 3%, un raddrizzamento di 0,5 e 1 grado, l'esposizione automatica che oscilla di ±0,3 EV;
 *  - *scena diversa* (non devono finire insieme, per nessuno dei due): un'altra scena;
 *  - *zona grigia*: la camera spostata del 15% e del 25%, uno zoom 0,8, ±1,5 EV, il bianco e nero, un filtro di colore forte. Per la **firma**
 *    (che guarda i pixel) sono "diversi": non si unisce nel dubbio. Per i **vettori** (che guardano il contenuto) possono essere ancora "lo
 *    stesso momento": non si contano ne' come giusti ne' come sbagliati, si riporta dove cadono.
 *
 * **I vettori.** Non c'e' il modello vero in una prova su JVM: un embedder FINTO (`FakeEmbedder`) trasforma ogni immagine in un vettore unitario
 * da 768 dimensioni: la disposizione della luce (8x8, senza la media e senza il contrasto: un'esposizione diversa non cambia il contenuto) e del
 * colore (4x4), un poco di tono globale, una proiezione casuale fissa, una non linearita' e una componente comune (come in un vero spazio di
 * embedding, dove due foto qualunque hanno gia' un coseno positivo). Serve a misurare dove cadono i coseni di scatti "dello stesso momento" e
 * di "scene diverse" **su immagini vere**, non a dire quanto vale il coseno del modello vero: quello lo conferma F77 sul telefono.
 *
 * Il resoconto con le bande va in stdout e in `p8-calibrazione.txt` (cartella temporanea) e in `docs/decisioni.md`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimilarShotsCalibrationTest {

  private enum class Label { SAME_MOMENT, DIFFERENT_SCENE, GRAY }

  private class Item(
    val scene: String,
    val kind: String,
    val vectorLabel: Label,
    val signatureLabel: Label,
    val copies: SignatureDistance,
    val layout: LayoutDistance,
    val cosine: Float,
  ) {
    val id: String get() = "$scene/$kind"
  }

  private val items = ArrayList<Item>()

  private val w = 512
  private val h = 384

  private fun shot(scene: Scene, seed: Long, framing: Framing = Framing(), tweak: (Raster) -> Raster = { it }): Raster =
    JpegCodec.roundTrip(tweak(RasterOps.noise(SceneRenderer.render(scene, w, h, framing), 0.006, seed)), 92)

  /**
   * [signatureLabel]: per la firma (che guarda i pixel) la zona grigia e' "diversi" (non si unisce nel dubbio), tranne zoom, bianco e nero e
   * filtro di colore, che restano grigi anche per lei: stanno a meta' fra "una modifica dello stesso scatto" e "un altro scatto".
   */
  private fun add(scene: String, kind: String, label: Label, original: Raster, variant: Raster, signatureLabel: Label = if (label == Label.GRAY) Label.DIFFERENT_SCENE else label) {
    val a = VisualSignatures.of(original)
    val b = VisualSignatures.of(variant)
    items += Item(
      scene, kind, label, signatureLabel, SignatureComparison.distance(a, b)!!, SignatureLayout.distance(a, b)!!,
      SimilarShotsRules.cosine(FakeEmbedder.embed(original), FakeEmbedder.embed(variant)),
    )
  }

  private fun scenes(name: String, seed: Long, busy: Boolean, adjust: (Raster) -> Raster = { it }) {
    val sx = 0.45 + (seed % 7) * 0.03
    val scene = Scene(seed, Subject(sx, 0.78), busy = busy)
    val original = shot(scene, seed, tweak = adjust)
    val same = Label.SAME_MOMENT
    val gray = Label.GRAY

    fun moved(delta: Double, camera: Double, seed2: Long) =
      shot(Scene(seed, Subject(sx + delta, 0.78), busy = busy), seed2, Framing(shiftX = camera, shiftY = camera * 0.6), adjust)

    // --- Stesso momento ---
    add(name, "soggetto +1,5%", same, original, moved(0.015, 0.003, seed + 100))
    add(name, "soggetto +3%", same, original, moved(0.03, 0.005, seed + 101))
    add(name, "soggetto +5%", same, original, moved(0.05, 0.008, seed + 102))
    add(name, "soggetto +20%", same, original, moved(0.20, 0.0, seed + 114))
    add(name, "soggetto +30%", same, original, moved(0.30, 0.0, seed + 115))
    add(name, "camera spostata 2%", same, original, shot(scene, seed + 103, Framing(shiftX = 0.02, shiftY = 0.01), adjust))
    add(name, "camera spostata 4%", same, original, shot(scene, seed + 104, Framing(shiftX = 0.04, shiftY = 0.02), adjust))
    add(name, "ritaglio del 2%", same, original, shot(scene, seed + 105, Framing(zoom = 0.98), adjust))
    add(name, "ritaglio del 3%", same, original, shot(scene, seed + 106, Framing(zoom = 0.97), adjust))
    add(name, "raddrizzamento di 0,5 gradi", same, original, shot(scene, seed + 107, Framing(zoom = SceneRenderer.straightenZoom(0.5), rotationDegrees = 0.5), adjust))
    add(name, "raddrizzamento di 1 grado", same, original, shot(scene, seed + 108, Framing(zoom = SceneRenderer.straightenZoom(1.0), rotationDegrees = 1.0), adjust))
    add(name, "esposizione +0,3 EV", same, original, RasterOps.exposure(shot(scene, seed + 109, tweak = adjust), 0.3))
    add(name, "esposizione -0,3 EV", same, original, RasterOps.exposure(shot(scene, seed + 110, tweak = adjust), -0.3))

    // --- Un'altra scena ---
    add(name, "altra scena", Label.DIFFERENT_SCENE, original, shot(Scene(seed + 50, Subject(0.5, 0.78), busy = busy), seed + 111, tweak = adjust))

    // --- Zona grigia ---
    add(name, "camera spostata 15%", gray, original, shot(scene, seed + 112, Framing(shiftX = 0.15, shiftY = 0.08), adjust))
    add(name, "camera spostata 25%", gray, original, shot(scene, seed + 113, Framing(shiftX = 0.25, shiftY = 0.12), adjust))
    add(name, "zoom 0,8", gray, original, shot(scene, seed + 116, Framing(zoom = 0.8), adjust), signatureLabel = gray)
    add(name, "esposizione +1,5 EV", gray, original, RasterOps.exposure(shot(scene, seed + 117, tweak = adjust), 1.5))
    add(name, "esposizione -1,5 EV", gray, original, RasterOps.exposure(shot(scene, seed + 118, tweak = adjust), -1.5))
    if (!busy) {
      add(name, "bianco e nero", gray, original, RasterOps.grayscale(shot(scene, seed + 119, tweak = adjust)), signatureLabel = gray)
      add(name, "filtro di colore forte", gray, original, RasterOps.tint(shot(scene, seed + 120, tweak = adjust), 1.25, 1.0, 0.7), signatureLabel = gray)
    }
  }

  private fun fill() {
    for (seed in 1L..6L) scenes("s$seed", seed, busy = false)
    for (seed in 21L..22L) scenes("fogliame$seed", seed, busy = true)
    for (seed in 31L..32L) scenes("buio$seed", seed, busy = false) { RasterOps.exposure(it, -2.0) }
  }

  private fun signatureSays(item: Item): Affinity {
    val d = item.layout
    if (d.structure < SimilarShotsRules.SIGNATURE_MIN_STRUCTURE) return Affinity.UNKNOWN
    val same = d.mean <= SimilarShotsRules.SIGNATURE_LAYOUT_MEAN_MAX && d.changed <= SimilarShotsRules.SIGNATURE_LAYOUT_CHANGED_MAX
    return if (same) Affinity.SIMILAR else Affinity.DIFFERENT
  }

  @Test
  fun calibraLeSoglieSulSetSintetico() {
    fill()
    val report = StringBuilder()
    fun line(text: String) {
      report.appendLine(text)
      println(text)
    }
    val usable = items.filter { it.layout.structure >= SimilarShotsRules.SIGNATURE_MIN_STRUCTURE }
    line("SET: ${items.size} coppie di scatti, ${usable.size} con struttura sufficiente")
    line("IN USO: Sdup=${SimilarShotsRules.SDUP}; disposizione media<=${SimilarShotsRules.SIGNATURE_LAYOUT_MEAN_MAX} celle cambiate<=${SimilarShotsRules.SIGNATURE_LAYOUT_CHANGED_MAX}")

    // --- Per tipo di variante ---
    for (kind in items.map { it.kind }.distinct()) {
      val group = usable.filter { it.kind == kind }
      if (group.isEmpty()) continue
      line(
        "%-28s %-6s coseno %.4f..%.4f | disp media %.4f..%.4f cambiate %.4f..%.4f | >0,03: %.2f..%.2f >0,05: %.2f..%.2f >0,08: %.2f..%.2f (n=%d)".format(
          kind, group.first().vectorLabel.name.take(6),
          group.minOf { it.cosine }, group.maxOf { it.cosine },
          group.minOf { it.layout.mean }, group.maxOf { it.layout.mean },
          group.minOf { it.layout.changed }, group.maxOf { it.layout.changed },
          group.minOf { it.layout.shareAbove(0.03f) }, group.maxOf { it.layout.shareAbove(0.03f) },
          group.minOf { it.layout.shareAbove(0.05f) }, group.maxOf { it.layout.shareAbove(0.05f) },
          group.minOf { it.layout.shareAbove(0.08f) }, group.maxOf { it.layout.shareAbove(0.08f) }, group.size,
        ),
      )
    }

    // --- Le bande, per chi giudica ---
    val sameMoment = usable.filter { it.vectorLabel == Label.SAME_MOMENT }
    val otherScene = usable.filter { it.vectorLabel == Label.DIFFERENT_SCENE }
    val sigDifferent = usable.filter { it.signatureLabel == Label.DIFFERENT_SCENE }
    val minSame = sameMoment.minOf { it.cosine }
    val maxOther = otherScene.maxOf { it.cosine }
    line("VETTORI  stesso momento da %.4f (%s), altra scena fino a %.4f (%s)".format(minSame, sameMoment.minBy { it.cosine }.id, maxOther, otherScene.maxBy { it.cosine }.id))
    val maxSameMean = sameMoment.maxOf { it.layout.mean }
    val maxSameChanged = sameMoment.maxOf { it.layout.changed }
    val minDiffMean = sigDifferent.minOf { it.layout.mean }
    val minDiffChanged = sigDifferent.minOf { it.layout.changed }
    line("FIRMA    stesso momento fino a media %.4f cambiate %.4f (%s / %s)".format(maxSameMean, maxSameChanged, sameMoment.maxBy { it.layout.mean }.id, sameMoment.maxBy { it.layout.changed }.id))
    line("FIRMA    diversi da media %.4f (%s) cambiate %.4f (%s)".format(minDiffMean, sigDifferent.minBy { it.layout.mean }.id, minDiffChanged, sigDifferent.minBy { it.layout.changed }.id))
    line("CONSIGLIATE disposizione: media %.4f cambiate %.4f; Sdup (punto medio) %.4f".format(sqrt(maxSameMean * minDiffMean), sqrt(maxSameChanged * minDiffChanged), (minSame + maxOther) / 2))
    for (sdup in listOf(0.80f, 0.85f, 0.90f, 0.92f, 0.94f, 0.95f, 0.96f, 0.97f, 0.98f)) {
      val gray = usable.filter { it.vectorLabel == Label.GRAY }
      line(
        "  Sdup %.2f: stesso momento %d/%d, altra scena unita per errore %d/%d, zona grigia unita %d/%d".format(
          sdup, sameMoment.count { it.cosine >= sdup }, sameMoment.size, otherScene.count { it.cosine >= sdup }, otherScene.size,
          gray.count { it.cosine >= sdup }, gray.size,
        ),
      )
    }
    val sigWrongSame = sameMoment.filter { signatureSays(it) != Affinity.SIMILAR }
    val sigWrongDifferent = sigDifferent.filter { signatureSays(it) == Affinity.SIMILAR }
    line("FIRMA in uso: stesso momento riconosciuto ${sameMoment.size - sigWrongSame.size}/${sameMoment.size}, diversi uniti per errore ${sigWrongDifferent.size}/${sigDifferent.size}")
    if (sigWrongSame.isNotEmpty()) line("  non riconosciuti: " + sigWrongSame.joinToString { it.id })
    if (sigWrongDifferent.isNotEmpty()) line("  uniti per errore: " + sigWrongDifferent.joinToString { it.id })
    // Per memoria: le soglie strette delle copie simili sul "stesso momento" (non reggono: e' il motivo della lettura a grande).
    val tight = sameMoment.count { SimilarityRules.verdict(it.copies) == dev.pampa.mycloudgallery.core.library.similar.VisualVerdict.SAME }
    line("FIRMA STRETTA delle copie simili: stesso momento riconosciuto $tight/${sameMoment.size}")
    File(System.getProperty("java.io.tmpdir"), "p8-calibrazione.txt").writeText(report.toString())

    // La precisione prima di tutto: un'unione sbagliata propone di buttare una foto. Nessuna scena diversa si unisce, per nessuno dei due.
    assertWithMessage("diversi uniti per errore dalla firma").that(sigWrongDifferent.map { it.id }).isEmpty()
    assertWithMessage("scene diverse unite per errore dal coseno").that(otherScene.filter { it.cosine >= SimilarShotsRules.SDUP }.map { it.id }).isEmpty()
    // I margini: le soglie in uso stanno dentro le bande misurate, con almeno l'8% (firma) o 0,10 (coseno) da ogni lato.
    assertThat(SimilarShotsRules.SDUP - maxOther).isAtLeast(0.10f)
    assertThat(SimilarShotsRules.SIGNATURE_LAYOUT_MEAN_MAX).isAtLeast(maxSameMean * 1.08f)
    assertThat(SimilarShotsRules.SIGNATURE_LAYOUT_MEAN_MAX).isAtMost(minDiffMean * 0.92f)
    assertThat(SimilarShotsRules.SIGNATURE_LAYOUT_CHANGED_MAX).isAtLeast(maxSameChanged * 1.08f)
    assertThat(SimilarShotsRules.SIGNATURE_LAYOUT_CHANGED_MAX).isAtMost(minDiffChanged * 0.92f)
    // E lo stesso momento si riconosce: tutto con la firma; con i vettori, a 0,95 (prudente), l'85% (restano fuori i soggetti che si
    // spostano di un quinto del fotogramma o piu' e la camera spostata del 4%: in quel caso l'utente non vede il suggerimento, non perde niente).
    assertThat(sameMoment.count { signatureSays(it) == Affinity.SIMILAR }.toDouble() / sameMoment.size).isAtLeast(0.97)
    assertThat(sameMoment.count { it.cosine >= SimilarShotsRules.SDUP }.toDouble() / sameMoment.size).isAtLeast(0.85)
  }

  /**
   * L'embedder finto (vedi la descrizione della classe). Deterministico: i semi sono fissi.
   */
  private object FakeEmbedder {
    private const val DIM = 768
    private const val FEATURES = 64 + 32 + 3
    private const val COMMON_WEIGHT = 0.55f

    private val projection: Array<FloatArray> = Random(2026).let { random ->
      Array(DIM) { FloatArray(FEATURES) { (random.nextGaussian() / sqrt(FEATURES.toDouble())).toFloat() } }
    }
    private val common: FloatArray = Random(7).let { random -> FloatArray(DIM) { random.nextGaussian().toFloat() } }.also { v ->
      val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
      for (i in v.indices) v[i] /= norm
    }

    fun embed(raster: Raster): FloatArray {
      val bytes = VisualSignatures.of(raster).bytes()
      val side = VisualSignatures.LUMA_SIDE
      val chromaSide = VisualSignatures.CHROMA_SIDE
      val features = FloatArray(FEATURES)
      // Disposizione della luce 8x8: le medie dei blocchi 8x8 della griglia 64x64, tolta la media e normalizzata (il contenuto, non l'esposizione).
      val luma = FloatArray(64)
      for (by in 0 until 8) for (bx in 0 until 8) {
        var sum = 0f
        for (y in 0 until 8) for (x in 0 until 8) sum += (bytes[(by * 8 + y) * side + bx * 8 + x].toInt() and 0xFF) / 255f
        luma[by * 8 + bx] = sum / 64f
      }
      val lumaMean = luma.average().toFloat()
      val lumaStd = sqrt(luma.sumOf { ((it - lumaMean) * (it - lumaMean)).toDouble() } / 64.0).toFloat() + 0.02f
      for (i in 0 until 64) features[i] = (luma[i] - lumaMean) / lumaStd
      // Disposizione del colore 4x4 per a e per b, normalizzata allo stesso modo (con un peso minore).
      val chromaBase = side * side
      var toneA = 0f
      var toneB = 0f
      for (plane in 0..1) {
        val cells = FloatArray(16)
        for (by in 0 until 4) for (bx in 0 until 4) {
          var sum = 0f
          for (y in 0 until 8) for (x in 0 until 8) {
            sum += ((bytes[chromaBase + plane * chromaSide * chromaSide + (by * 8 + y) * chromaSide + bx * 8 + x].toInt() and 0xFF) - 128) / 320f
          }
          cells[by * 4 + bx] = sum / 64f
        }
        val mean = cells.average().toFloat()
        val std = sqrt(cells.sumOf { ((it - mean) * (it - mean)).toDouble() } / 16.0).toFloat() + 0.01f
        for (i in 0 until 16) features[64 + plane * 16 + i] = 0.5f * (cells[i] - mean) / std
        if (plane == 0) toneA = mean else toneB = mean
      }
      // Il tono globale: chiaro o scuro, caldo o freddo (un poco, un modello semantico non e' cieco al colore).
      features[96] = (lumaMean - 0.45f) * 2f
      features[97] = toneA * 12f
      features[98] = toneB * 12f
      val out = FloatArray(DIM)
      for (i in 0 until DIM) {
        var dot = 0f
        val row = projection[i]
        for (j in 0 until FEATURES) dot += row[j] * features[j]
        out[i] = tanh(dot.toDouble()).toFloat() + COMMON_WEIGHT * common[i]
      }
      val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
      for (i in out.indices) out[i] /= norm
      return out
    }
  }
}
