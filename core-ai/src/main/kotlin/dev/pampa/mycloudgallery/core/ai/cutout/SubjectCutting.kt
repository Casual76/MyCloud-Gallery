package dev.pampa.mycloudgallery.core.ai.cutout

import android.content.ContentResolver
import android.net.Uri
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import java.io.File
import java.lang.ref.SoftReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Dove sta l'immagine da ritagliare: la migliore GIA' sul dispositivo (mai uno scaricamento solo per ritagliare, D44). */
sealed interface CutoutSource {
  /** Un file del dispositivo: l'originale portato dal NAS o l'HQ della replica. */
  class OfFile(val file: File) : CutoutSource

  /** Una riga del MediaStore del telefono (`content://media/...`). */
  class OfUri(val resolver: ContentResolver, val uri: Uri) : CutoutSource

  /**
   * I byte di un'immagine gia' in memoria: l'HQ della replica di un media del NAS (2.048 px, qualche centinaio di KB), copiata dal pacco
   * perche' la decodifica non dipenda da un file mappato che la replica potrebbe chiudere (P11b).
   */
  class OfBytes(val bytes: ByteArray) : CutoutSource {
    override fun toString(): String = "OfBytes(${bytes.size} B)"
  }
}

/**
 * Il ritaglio per chi lo usa (P11: gli adattatori delle porte del viewer): **decodifica** la sorgente ([CutoutImages]), la **tiene** per
 * i passi dopo (altri punti di prova, "Copia", "Condividi", "Salva come nuova foto": la stessa foto non si decodifica cinque volte) e
 * la **rende** in PNG ([CutoutRender]). L'immagine decodificata e' trattenuta con un riferimento morbido (50 MB per una foto da 12
 * megapixel): il sistema la riprende quando la memoria serve, e allora si decodifica di nuovo.
 *
 * Una sola chiave alla volta: chi chiede un'altra foto fa dimenticare la precedente. Un'operazione alla volta (le richieste si
 * mettono in fila).
 */
class SubjectCutting(
  private val cutter: SubjectCutter,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  /**
   * I pixel massimi dell'immagine decodificata (tetto di `ImagingBudget`, E-M1-M4): `:app` passa `ImagingBudget.of(context).viewerMaxPixels`
   * (12,8 MP sull'S25, meno dove `memoryClass` e' piu' piccola); il lato lungo resta entro 4096.
   */
  private val maxPixels: Long = CutoutImages.MAX_RESULT_PIXELS,
) {

  private class Held(val key: String, val image: CutoutImage)

  private val gate = Mutex()
  private var held: SoftReference<Held>? = null

  /** Il soggetto sotto il punto ([x], [y]) (normalizzati sull'immagine raddrizzata). */
  suspend fun cut(key: String, source: CutoutSource, x: Float, y: Float): CutoutResult = gate.withLock {
    val image = imageFor(key, source) ?: return@withLock lastFailure
    cutter.cut(image, NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
  }

  /**
   * L'azione di TalkBack "Ritaglia il soggetto" (D46): il soggetto piu' grande fra cinque punti di prova, il centro e i quattro punti
   * a un terzo. Nessun soggetto in nessuno dei cinque = [CutoutResult.NoClearSubject].
   */
  suspend fun largest(key: String, source: CutoutSource): CutoutResult = gate.withLock {
    val image = imageFor(key, source) ?: return@withLock lastFailure
    var best: CutoutResult.Subject? = null
    var bestCoverage = -1f
    var failure: CutoutResult.Failed? = null
    for ((x, y) in PROBES) {
      when (val result = cutter.cut(image, NormalizedPoint(x, y))) {
        is CutoutResult.Subject -> {
          val coverage = result.mask.coverage
          if (coverage > bestCoverage) {
            best = result
            bestCoverage = coverage
          }
        }
        is CutoutResult.Failed -> failure = result
        CutoutResult.NoClearSubject -> Unit
      }
    }
    best ?: failure ?: CutoutResult.NoClearSubject
  }

  /**
   * Il PNG del soggetto [mask] della foto [key] (la stessa di [cut]); null se la sorgente non si legge piu' o la memoria non basta.
   * [mask] deve essere grande come l'immagine decodificata (e' quella restituita da [cut]).
   */
  suspend fun png(key: String, source: CutoutSource, mask: AlphaMask, outline: Boolean): ByteArray? = gate.withLock {
    val image = imageFor(key, source) ?: return@withLock null
    if (image.width != mask.width || image.height != mask.height) return@withLock null
    try {
      withContext(dispatchers.default) { CutoutRender.png(image, mask, outline) }
    } catch (_: OutOfMemoryError) {
      null
    }
  }

  /** Dimentica l'immagine trattenuta (memoria bassa, viewer chiuso). */
  fun clear() {
    held = null
  }

  /** Rilascia anche il modello. */
  suspend fun release() {
    clear()
    cutter.release()
  }

  @Volatile
  private var lastFailure: CutoutResult = CutoutResult.Failed(AiEngineError.BAD_INPUT)

  private suspend fun imageFor(key: String, source: CutoutSource): CutoutImage? {
    held?.get()?.let { if (it.key == key) return it.image }
    held = null
    val decoded = withContext(dispatchers.io) {
      when (source) {
        is CutoutSource.OfFile -> CutoutImages.decode(source.file, maxPixels = maxPixels)
        is CutoutSource.OfUri -> CutoutImages.decode(source.resolver, source.uri, maxPixels = maxPixels)
        is CutoutSource.OfBytes -> CutoutImages.decode(source.bytes, maxPixels = maxPixels)
      }
    }
    return when (decoded) {
      is CutoutDecode.Decoded -> decoded.image.also { held = SoftReference(Held(key, it)) }
      else -> {
        lastFailure = CutoutResult.Failed(decoded.error ?: AiEngineError.BAD_INPUT)
        null
      }
    }
  }

  companion object {
    /** Il centro e i quattro punti a un terzo (D46). */
    val PROBES: List<Pair<Float, Float>> = listOf(
      0.5f to 0.5f,
      1f / 3f to 1f / 3f,
      2f / 3f to 1f / 3f,
      1f / 3f to 2f / 3f,
      2f / 3f to 2f / 3f,
    )
  }
}
