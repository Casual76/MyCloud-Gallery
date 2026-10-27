package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * La SUITE DI CONTRATTO dei motori dei volti (docs/fase8-progetto.md §5.2, `FaceEnginesContract`): la stessa gira sul fake (JVM, test di
 * core-ai) e sui motori veri di P2 (strumentato, sui ritratti di pubblico dominio di P1). Sta in `testing/` di src/main perche' la usano
 * due insiemi di test (`src/test` e `src/androidTest`); mai nella release (tools/check.ps1). Lancia `AssertionError` in italiano.
 *
 * Controlli: al piu' 64 volti, riquadri dentro l'immagine, cinque punti finiti, punteggi in 0..1, determinismo del rilevatore; un volto
 * allineato per volto, un giudizio di qualita' valido (motivi noti, nessun attributo), un risultato dell'embedder per volto; i vettori
 * presenti hanno 128 componenti finite, norma 1 +- [FaceEngineLimits.NORM_TOLERANCE] e sono deterministici (coseno >= 0,999); le
 * chiamate in parallelo hanno i loro risultati; `release` idempotente e un `detect` dopo `release` che riscalda; i guasti solo come
 * [AiEngineException]. [withFaces] sono immagini con almeno un volto (sintetiche o di pubblico dominio: nessun dato personale).
 *
 * P2 aggiunge: la seconda passata ([DetectPass.HQ_TILES]) rispetta gli stessi limiti della prima e, su un'immagine che entra in 1024 px (un
 * tassello solo), non trova meno volti dell'anteprima; un volto allineato per volto e nessun lotto vuoto ([FaceEmbedder.embed] di una lista vuota
 * da' una lista vuota, senza aprire il motore); dopo `release` anche la seconda passata e l'embedder riscaldano.
 */
object FaceEnginesContract {

  suspend fun verify(engines: FaceEngines, withFaces: List<FaceImage>, withoutFaces: List<FaceImage> = emptyList()) {
    check(withFaces.isNotEmpty(), "servono immagini con volti")
    check(engines.embedder.space == FaceSpaceCode.CURRENT, "spazio dell'embedder diverso da quello corrente")
    val first = withFaces.map { detect(engines, it) }
    check(first.all { it.isNotEmpty() }, "un'immagine con volti non ne ha nessuno")
    for ((image, faces) in withFaces.zip(first)) {
      val again = detect(engines, image)
      check(again.size == faces.size, "rilevatore non deterministico: ${faces.size} poi ${again.size}")
      for ((a, b) in faces.zip(again)) check(abs(a.score - b.score) < 1e-4f, "punteggio non deterministico")
      val vectors = vectorsOf(engines, image, faces)
      check(vectors.size == faces.size, "un risultato dell'embedder per volto")
      val repeat = vectorsOf(engines, image, faces)
      for (i in vectors.indices) {
        val v = vectors[i] ?: continue
        checkVector(v)
        val w = repeat[i] ?: throw AssertionError("embedder non deterministico: un vettore sparito")
        check(cosine(v, w) >= 0.999f, "embedder non deterministico: coseno ${cosine(v, w)}")
      }
    }
    check(first.flatten().isNotEmpty(), "nessun volto in nessuna immagine")
    for (image in withoutFaces) check(detect(engines, image).isEmpty(), "volti in un'immagine che non ne ha")

    // La seconda passata: gli stessi limiti; su un'immagine che entra nel quadro (un tassello solo) non perde i volti dell'anteprima.
    for ((image, faces) in withFaces.zip(first)) {
      val hq = detect(engines, image, DetectPass.HQ_TILES)
      if (maxOf(image.width, image.height) <= FaceEngineLimits.DETECT_SIDE) {
        check(hq.size >= faces.size, "la seconda passata trova ${hq.size} volti, l'anteprima ${faces.size}")
      }
    }
    check(engines.embedder.embed(emptyList()).isEmpty(), "una lista vuota non da' una lista vuota")

    // Chiamate in parallelo: il motore le mette in fila, ognuna ha il suo risultato.
    val parallel = coroutineScope { withFaces.map { async { engines.detector.detect(it, DetectPass.PREVIEW).size } }.awaitAll() }
    check(parallel == first.map { it.size }, "rilevamenti in parallelo diversi da quelli in fila")

    engines.release()
    engines.release()
    val afterRelease = detect(engines, withFaces.first())
    check(afterRelease.size == first.first().size, "dopo release il rilevatore non torna uguale")
    check(vectorsOf(engines, withFaces.first(), afterRelease).size == afterRelease.size, "dopo release l'embedder non torna")
    engines.release()
    detect(engines, withFaces.first(), DetectPass.HQ_TILES)
  }

  private suspend fun detect(engines: FaceEngines, image: FaceImage, pass: DetectPass = DetectPass.PREVIEW): List<DetectedFace> {
    val faces = try {
      engines.detector.detect(image, pass)
    } catch (e: AiEngineException) {
      throw AssertionError("rilevatore in errore su un'immagine valida: ${e.error}")
    }
    check(faces.size <= FaceEngineLimits.MAX_FACES, "oltre ${FaceEngineLimits.MAX_FACES} volti")
    for (face in faces) {
      val b = face.box
      val slack = 2f
      check(b.x0 >= -slack && b.y0 >= -slack && b.x1 <= image.width + slack && b.y1 <= image.height + slack, "riquadro fuori dall'immagine")
      check(face.landmarks.size == FaceEngineLimits.LANDMARK_VALUES && face.landmarks.all { it.isFinite() }, "cinque punti non validi")
      check(face.score in 0f..1f, "punteggio fuori intervallo")
    }
    return faces
  }

  private suspend fun vectorsOf(engines: FaceEngines, image: FaceImage, faces: List<DetectedFace>): List<FloatArray?> {
    val aligned = faces.map { engines.aligner.align(image, it.landmarks) }
    for ((face, a) in faces.zip(aligned)) {
      val verdict = engines.quality.assess(face, face.box.shortSide.toInt(), a)
      check(FaceReasons.isValid(verdict.reasons), "motivi della qualita' sconosciuti")
    }
    return try {
      engines.embedder.embed(aligned)
    } catch (e: AiEngineException) {
      throw AssertionError("embedder in errore su volti validi: ${e.error}")
    }
  }

  fun checkVector(v: FloatArray) {
    check(v.size == FaceSpaceCode.CURRENT.dimension, "dimensione ${v.size} invece di ${FaceSpaceCode.CURRENT.dimension}")
    check(v.all { it.isFinite() }, "componenti non finite")
    val norm = sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat()
    check(abs(norm - 1f) <= FaceEngineLimits.NORM_TOLERANCE, "norma $norm lontana da 1")
  }

  fun cosine(a: FloatArray, b: FloatArray): Float = SyntheticIdentities.cosine(a, b)

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
  }
}
