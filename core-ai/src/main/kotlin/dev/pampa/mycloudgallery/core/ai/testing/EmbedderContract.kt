package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.model.SpaceCode
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * La SUITE DI CONTRATTO dei motori dei vettori (docs/fase6-progetto.md §5.2, `EmbedderContract`): la stessa gira sul fake (JVM,
 * test di core-ai) e sul motore vero (strumentato, P4). Sta in `testing/` di src/main perche' la usano due insiemi di test
 * (`src/test` e `src/androidTest`); mai nella release (tools/check.ps1). Lancia `AssertionError` con un messaggio in italiano.
 *
 * Controlli: dimensione dello spazio, valori finiti, norma 1 +- [normTolerance], determinismo (coseno >= 0,999), testi diversi =
 * vettori diversi, chiamate in parallelo servite in fila con risultati corretti, `warmUp`/`release` idempotenti, un `embed` dopo
 * `release` che riscalda il motore, un testo oltre i 200 caratteri accettato (troncato), un guasto sempre come [AiEngineException].
 */
object EmbedderContract {

  const val NORM_TOLERANCE: Float = 1e-3f

  /** Il motore del testo delle query. */
  suspend fun verifyText(embedder: TextEmbedder, normTolerance: Float = NORM_TOLERANCE) {
    val first = embedder.warmUp()
    check(first is WarmUpResult.Ready, "warmUp: atteso Ready, avuto $first")
    check(embedder.warmUp() is WarmUpResult.Ready, "warmUp due volte: atteso Ready")

    val space = SpaceCode.CURRENT.space
    val a = embedder.embed("un cane che corre sulla spiaggia")
    check(a.space == space, "spazio: atteso ${space.id}/${space.dimension}")
    val va = a.values
    checkVector(va, space.dimension, normTolerance, "testo")

    val again = embedder.embed("un cane che corre sulla spiaggia").values
    check(cosine(va, again) >= 0.999f, "determinismo: coseno ${cosine(va, again)} < 0,999")

    val other = embedder.embed("uno scontrino del supermercato").values
    check(cosine(va, other) < 0.999f, "testi diversi danno lo stesso vettore")

    // Chiamate in parallelo: il motore le mette in fila, ognuna ha il suo risultato.
    val texts = listOf("tramonto in montagna", "torta di compleanno", "tramonto in montagna", "gatto sul divano")
    val parallel = coroutineScope { texts.map { async { embedder.embed(it).values } }.awaitAll() }
    parallel.forEach { checkVector(it, space.dimension, normTolerance, "parallelo") }
    check(cosine(parallel[0], parallel[2]) >= 0.999f, "parallelo: stesso testo, vettori diversi")

    val long = embedder.embed("parola ".repeat(80)).values
    checkVector(long, space.dimension, normTolerance, "testo lungo")

    embedder.release()
    embedder.release()
    val afterRelease = embedder.embed("un cane che corre sulla spiaggia").values
    check(cosine(va, afterRelease) >= 0.999f, "dopo release il motore non torna uguale")

    try {
      val empty = embedder.embed("")
      checkVector(empty.values, space.dimension, normTolerance, "testo vuoto")
    } catch (e: AiEngineException) {
      check(e.error == AiEngineError.BAD_INPUT, "testo vuoto: errore inatteso ${e.error}")
    }
  }

  /** Il motore delle immagini: [images] sono almeno due immagini diverse (sintetiche, nessun dato personale). */
  suspend fun verifyImages(embedder: ImageEmbedder, images: List<ModelImage>, normTolerance: Float = NORM_TOLERANCE) {
    check(images.size >= 2, "servono almeno due immagini")
    val space = SpaceCode.CURRENT.space
    val first = embedder.embed(images)
    check(first.size == images.size, "un vettore per immagine")
    first.forEach { checkVector(it, space.dimension, normTolerance, "immagine") }
    val again = embedder.embed(images)
    for (i in images.indices) check(cosine(first[i], again[i]) >= 0.999f, "immagine $i: non deterministico")
    check(cosine(first[0], first[1]) < 0.999f, "immagini diverse danno lo stesso vettore")
    embedder.release()
    embedder.release()
  }

  fun cosine(a: FloatArray, b: FloatArray): Float = FakeConcepts.cosine(a, b)

  private fun checkVector(v: FloatArray, dimension: Int, tolerance: Float, what: String) {
    check(v.size == dimension, "$what: ${v.size} componenti, attese $dimension")
    check(v.all { it.isFinite() }, "$what: valori non finiti")
    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    check(abs(norm - 1f) <= tolerance, "$what: norma $norm fuori da 1 +- $tolerance")
  }

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError("EmbedderContract: $message")
  }
}
