package dev.pampa.mycloudgallery.core.library.similar

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.VisualSignature
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La tabella del §2.12 in test (Fase 5A, P15): "devono unirsi" / "non devono unirsi", con immagini generate (`SceneFixtures`) e JPEG e
 * WebP veri (Skia nativo, come sul telefono). Per ogni caso si misurano le tre statistiche della distanza e si calcola il **margine**
 * dalla soglia: i positivi stanno sotto, i negativi sopra. Il resoconto dei margini va in stdout e in `p15-margini.txt` (cartella
 * temporanea) e nel report del pacchetto.
 *
 * Fixture: 6 scene normali, 3 di fogliame (dettaglio fine ovunque: il caso peggiore per il ricampionamento) e 3 al buio. Non c'e' un
 * encoder di HEIC nel test (Skia di Robolectric non lo scrive): "HEIC convertito in JPEG" e' una catena di due compressioni forti e
 * una perdita di saturazione, la stessa cosa che fa un convertitore; il HEIC vero si prova sul campo (F31-bis).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignatureTableTest {

  private class Case(val scene: String, val name: String, val mustMerge: Boolean, val spec: Boolean, val distance: SignatureDistance) {
    /** Quanto il caso e' vicino alla soglia: sotto 1 e' dalla parte dei positivi, sopra 1 dei negativi (la statistica peggiore). */
    val ratio: Float
      get() = maxOf(distance.mean / SimilarityRules.MEAN_MAX, distance.peak / SimilarityRules.PEAK_MAX, distance.tail / SimilarityRules.TAIL_MAX)
  }

  private val cases = ArrayList<Case>()

  /** Casi solo informativi (sotto la sensibilita' che la tabella promette): si riporta quanti si unirebbero, senza imporlo. */
  private val info = ArrayList<Case>()

  private fun sig(r: Raster): VisualSignature = VisualSignatures.of(r)

  private fun add(scene: String, name: String, mustMerge: Boolean, a: Raster, b: Raster, spec: Boolean = true) {
    cases += Case(scene, name, mustMerge, spec, SignatureComparison.distance(sig(a), sig(b))!!)
  }

  private val w = 1024
  private val h = 768

  private fun shot(scene: Scene, seed: Long, framing: Framing = Framing(), tweak: (Raster) -> Raster = { it }): Raster =
    JpegCodec.roundTrip(tweak(RasterOps.noise(SceneRenderer.render(scene, w, h, framing), 0.006, seed)), 95)

  /** Tutti i casi di una scena normale (o di fogliame, o al buio: [adjust] e' l'esposizione di scena). */
  private fun table(name: String, seed: Long, busy: Boolean, adjust: (Raster) -> Raster = { it }) {
    val subjectX = 0.45 + (seed % 7) * 0.03
    val scene = Scene(seed, Subject(subjectX, 0.78), busy = busy)
    val original = shot(scene, seed, tweak = adjust)

    // --- Devono unirsi ---
    for (q in listOf(50, 70, 85, 95)) add(name, "ricompresso q$q", true, original, JpegCodec.roundTrip(original, q))
    add(name, "ridotto al 50%", true, original, RasterOps.resizeArea(original, w / 2, h / 2))
    add(name, "ridotto al 50% (bilineare ingenuo)", true, original, RasterOps.resizeBilinear(original, w / 2, h / 2))
    add(name, "ridotto a 16 MP (da 50 MP: x0,566)", true, original, RasterOps.resizeArea(original, (w * 0.566).toInt(), (h * 0.566).toInt()))
    add(name, "50% e q50", true, original, JpegCodec.roundTrip(RasterOps.resizeArea(original, w / 2, h / 2), 50))
    add(name, "HEIC convertito in JPEG", true, original, JpegCodec.roundTrip(RasterOps.blur(RasterOps.desaturate(JpegCodec.roundTrip(original, 35), 0.03), 0.5), 90))
    add(name, "miniatura M dell'indice (WebP 78)", true, original, JpegCodec.webpRoundTrip(RasterOps.resizeArea(original, 533, 400), 78))
    add(name, "miniatura S dell'indice (WebP 75)", true, original, JpegCodec.webpRoundTrip(RasterOps.resizeArea(original, 267, 200), 75))

    // --- Non devono unirsi ---
    add(name, "raffica: soggetto +1,5%", false, original, shot(Scene(seed, Subject(subjectX + 0.015, 0.78), busy = busy), seed + 100, Framing(shiftX = 0.003, shiftY = 0.002), adjust))
    add(name, "raffica: soggetto +3%", false, original, shot(Scene(seed, Subject(subjectX + 0.03, 0.78), busy = busy), seed + 101, Framing(shiftX = 0.006, shiftY = -0.004), adjust))
    add(name, "esposizione +0,3 EV", false, original, RasterOps.exposure(original, 0.3))
    add(name, "esposizione -0,3 EV", false, original, RasterOps.exposure(original, -0.3))
    add(name, "ritaglio del 3%", false, original, shot(scene, seed + 7, Framing(zoom = 0.97), adjust))
    add(name, "raddrizzamento di 1 grado", false, original, shot(scene, seed + 8, Framing(zoom = SceneRenderer.straightenZoom(1.0), rotationDegrees = 1.0), adjust))
    add(name, "specchiatura", false, original, shot(scene, seed + 9, Framing(mirrored = true), adjust))
    add(name, "altro scatto nello stesso secondo (altra scena)", false, original, shot(Scene(seed + 50, Subject(0.5, 0.78), busy = busy), seed + 11, tweak = adjust))
    add(name, "altro scatto nello stesso secondo (spostato del 12%)", false, original, shot(scene, seed + 10, Framing(shiftX = 0.12), adjust))
    if (!busy) {
      add(name, "bianco e nero", false, original, RasterOps.grayscale(original))
      add(name, "filtro di colore caldo", false, original, RasterOps.tint(original, 1.08, 1.0, 0.88))
      add(name, "filtro di colore freddo", false, original, RasterOps.tint(original, 0.92, 1.0, 1.08))
    }
    // Informativi: quanto poco deve muoversi un soggetto perche' due fotogrammi di una raffica senza altri segni si uniscano.
    for ((label, move) in listOf("0,2%" to 0.002, "0,5%" to 0.005)) {
      val moved = shot(Scene(seed, Subject(subjectX + move, 0.78), busy = busy), seed + 300, Framing(shiftX = move * 0.2, shiftY = move * 0.13), adjust)
      info += Case(name, "informativo: soggetto +$label", false, false, SignatureComparison.distance(sig(original), sig(moved))!!)
    }
    // Oltre la tabella (stress): il margine dei casi piu' vicini.
    add(name, "stress: ritaglio dell'1,5%", false, original, shot(scene, seed + 7, Framing(zoom = 0.985), adjust), spec = false)
    add(name, "stress: soggetto +1% della raffica", false, original, shot(Scene(seed, Subject(subjectX + 0.01, 0.78), busy = busy), seed + 200, Framing(shiftX = 0.002, shiftY = 0.0013), adjust), spec = false)
  }

  @Test
  fun laTabellaDelParagrafo212() {
    for (seed in 1L..4L) table("s$seed", seed, busy = false)
    for (seed in 21L..22L) table("fogliame$seed", seed, busy = true)
    for (seed in 31L..32L) table("buio$seed", seed, busy = false) { RasterOps.exposure(it, -2.4) }

    val report = StringBuilder()
    fun line(text: String) {
      report.appendLine(text)
      println(text)
    }
    line("SOGLIE media<=${SimilarityRules.MEAN_MAX} p98<=${SimilarityRules.PEAK_MAX} p99,5<=${SimilarityRules.TAIL_MAX} struttura>=${SimilarityRules.MIN_STRUCTURE}")
    val structureOk = cases.filter { it.distance.structure >= SimilarityRules.MIN_STRUCTURE }
    line("casi: ${cases.size}; con struttura sufficiente: ${structureOk.size}")

    // Ogni caso, nel verdetto giusto (i casi senza struttura sono UNDECIDABLE o DIFFERENT: mai SAME).
    for (c in cases) {
      val verdict = SimilarityRules.verdict(c.distance)
      if (c.mustMerge) {
        if (c.distance.structure >= SimilarityRules.MIN_STRUCTURE) {
          assertWithMessage("${c.scene} / ${c.name}: deve unirsi (${c.distance})").that(verdict).isEqualTo(VisualVerdict.SAME)
        }
      } else {
        assertWithMessage("${c.scene} / ${c.name}: non deve unirsi (${c.distance})").that(verdict).isNotEqualTo(VisualVerdict.SAME)
      }
    }

    // I margini, per tipo di caso: il peggiore di ciascun lato.
    val positives = structureOk.filter { it.mustMerge }
    val negatives = structureOk.filter { !it.mustMerge }
    val worstPositive = positives.maxBy { it.ratio }
    val nearestSpec = negatives.filter { it.spec }.minBy { it.ratio }
    val nearestStress = negatives.filter { !it.spec }.minBy { it.ratio }
    line("MARGINE positivi: statistica/soglia piu' alta = %.3f (%s / %s)".format(worstPositive.ratio, worstPositive.name, worstPositive.scene))
    line("MARGINE negativi di tabella: statistica/soglia piu' bassa = %.3f (%s / %s)".format(nearestSpec.ratio, nearestSpec.name, nearestSpec.scene))
    line("MARGINE negativi di stress: statistica/soglia piu' bassa = %.3f (%s / %s)".format(nearestStress.ratio, nearestStress.name, nearestStress.scene))
    for (family in listOf("s", "fogliame", "buio")) {
      val inFamily = structureOk.filter { it.scene.startsWith(family) && (family != "s" || it.scene.length <= 3) }
      val pos = inFamily.filter { it.mustMerge }.maxOf { it.ratio }
      val neg = inFamily.filter { !it.mustMerge && it.spec }.minOf { it.ratio }
      line("FAMIGLIA %-9s positivi al massimo %.3f della soglia, negativi di tabella almeno %.3f".format(family, pos, neg))
    }
    for (kind in cases.map { it.name }.distinct()) {
      val group = structureOk.filter { it.name == kind }
      if (group.isEmpty()) continue
      val mean = group.map { it.distance.mean }
      val peak = group.map { it.distance.peak }
      val tail = group.map { it.distance.tail }
      line(
        "CASO %-52s %s media %.4f..%.4f  p98 %.4f..%.4f  p99,5 %.4f..%.4f  rapporto %.2f..%.2f (n=%d)".format(
          kind, if (group.first().mustMerge) "UNIRE " else "TENERE", mean.min(), mean.max(), peak.min(), peak.max(), tail.min(), tail.max(),
          group.minOf { it.ratio }, group.maxOf { it.ratio }, group.size,
        ),
      )
    }
    for (kind in info.map { it.name }.distinct()) {
      val group = info.filter { it.name == kind && it.distance.structure >= SimilarityRules.MIN_STRUCTURE }
      val merged = group.count { SimilarityRules.verdict(it.distance) == VisualVerdict.SAME }
      line("INFO %-40s si unirebbero %d su %d (rapporto %.2f..%.2f)".format(kind, merged, group.size, group.minOf { it.ratio }, group.maxOf { it.ratio }))
    }
    val lowStructure = cases.filter { it.distance.structure < SimilarityRules.MIN_STRUCTURE }
    line("senza struttura sufficiente (mai uniti): ${lowStructure.size} casi, struttura massima ${lowStructure.maxOfOrNull { it.distance.structure }}")
    File(System.getProperty("java.io.tmpdir"), "p15-margini.txt").writeText(report.toString())

    // Margini minimi richiesti: 10% dai positivi, 8% dai negativi della tabella (il caso piu' vicino e' un filtro di colore su una foto buia).
    assertThat(worstPositive.ratio).isLessThan(1f / 1.10f)
    assertThat(nearestSpec.ratio).isGreaterThan(1.08f)
  }

  @Test
  fun unaFotoSenzaStrutturaNonProvaNienteEUnoScattoVuotoNonSiUnisce() {
    // Cielo uniforme: la ricompressione e' identica, ma qualunque altro cielo somiglierebbe lo stesso.
    val flat = Raster(w, h, IntArray(w * h) { 0x6495ED })
    val other = Raster(w, h, IntArray(w * h) { 0x6396EC })
    val d = SignatureComparison.distance(sig(flat), sig(other))!!
    assertThat(d.structure).isLessThan(SimilarityRules.MIN_STRUCTURE)
    assertThat(SimilarityRules.verdict(d)).isEqualTo(VisualVerdict.UNDECIDABLE)
    // Le stesse due, molto diverse fra loro, sono DIFFERENT (non UNDECIDABLE).
    val red = Raster(w, h, IntArray(w * h) { 0xCC2222 })
    assertThat(SimilarityRules.verdict(SignatureComparison.distance(sig(flat), sig(red))!!)).isEqualTo(VisualVerdict.DIFFERENT)
  }

  @Test
  fun unVideoRicodificatoSiUnisceSuiDueFotogrammiEUnAltroClipNo() {
    val clipA = Scene(41, Subject(0.4, 0.78))
    val clipB = Scene(42, Subject(0.6, 0.78))
    fun frames(scene: Scene, seed: Long, pan: Double): List<Raster> = listOf(
      RasterOps.noise(SceneRenderer.render(scene, 1280, 720, Framing(shiftX = 0.0)), 0.006, seed),
      RasterOps.noise(SceneRenderer.render(scene, 1280, 720, Framing(shiftX = pan)), 0.006, seed + 1),
    )
    val original = frames(clipA, 1, 0.08)
    // Ricodificato: da 1280x720 a 854x480, meno bitrate (JPEG q35 come blocchettatura), un po' di sfocatura.
    val reencoded = original.map { JpegCodec.roundTrip(RasterOps.blur(RasterOps.resizeArea(JpegCodec.roundTrip(it, 35), 854, 480), 0.6), 60) }
    val a = VisualSignatures.of(original)
    assertThat(a.frames).isEqualTo(2)
    assertThat(a.size).isAtMost(VisualSignature.MAX_BYTES)
    val d = SignatureComparison.distance(a, VisualSignatures.of(reencoded))!!
    println("VIDEO ricodificato: media ${d.mean} p98 ${d.peak} p99,5 ${d.tail} struttura ${d.structure}")
    assertThat(SimilarityRules.verdict(d)).isEqualTo(VisualVerdict.SAME)
    // Un altro clip dello stesso secondo e della stessa durata.
    val other = VisualSignatures.of(frames(clipB, 5, 0.08))
    assertThat(SimilarityRules.verdict(SignatureComparison.distance(a, other)!!)).isEqualTo(VisualVerdict.DIFFERENT)
    // Il primo fotogramma uguale e il secondo diverso (un clip tagliato altrove): devono combaciare tutti e due.
    val halfSame = VisualSignatures.of(listOf(original[0], frames(clipB, 7, 0.08)[1]))
    assertThat(SimilarityRules.verdict(SignatureComparison.distance(a, halfSame)!!)).isEqualTo(VisualVerdict.DIFFERENT)
    // Una firma da una foto non si confronta con una da un video.
    assertThat(SignatureComparison.distance(a, VisualSignatures.of(original[0]))).isNull()
  }
}
