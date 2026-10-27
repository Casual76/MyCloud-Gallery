package dev.pampa.mycloudgallery.feature.people.crops

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.faces.contract.FaceCropGate
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops

/**
 * Le copertine e i ritagli come li vuole la Fase 8 (docs/fase8-progetto.md D25, D28, §2.6 "Copertine e ritagli"): [source] ritaglia **al
 * momento** dalla migliore immagine gia' sul dispositivo (l'originale del telefono con la decodifica della sola regione, l'HQ della replica,
 * l'anteprima del buffer, la miniatura M); questa classe aggiunge le due regole che chi lega la porta deve rispettare:
 *
 *  1. **solo da un media mostrabile**: prima di ogni ritaglio (anche se l'immagine e' in cache) il cancello [gate] dice se il volto sta in un
 *     media che si puo' mostrare adesso. Un media nascosto un attimo fa non da' una copertina, e il suo ritaglio gia' in memoria non esce;
 *  2. **mai su disco**: i ritagli stanno in una cache **in memoria** da [CACHE_BYTES] (LRU), che si svuota con [clear] (lo fa `:app` quando
 *     Persone si spegne, quando si cancella o a `onTrimMemory`). Nessun file, nessun database, nessun `cacheDir`.
 *
 * Le bitmap non vengono riciclate qui: possono essere ancora sullo schermo. Mai un ritaglio, un volto o un id nei log (D37).
 */
class GuardedPeopleCrops(
  private val source: PeopleCrops,
  private val gate: FaceCropGate,
  private val cache: ByteLru<String, Bitmap> = ByteLru(CACHE_BYTES) { it.allocationByteCount },
) : PeopleCrops {

  override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap? {
    val key = keyOf(face, sizePx)
    if (!gate.mayShow(face)) {
      cache.remove(key)
      return null
    }
    cache.get(key)?.let { return it }
    val bitmap = source.crop(face, sizePx) ?: return null
    cache.put(key, bitmap)
    return bitmap
  }

  /** Svuota la cache (Persone spenta, cancellazione, poca memoria). */
  fun clear() = cache.clear()

  /** Quanti byte di ritagli tiene in memoria adesso (per le prove: mai oltre il tetto). */
  val cachedBytes: Long get() = cache.weight

  private fun keyOf(face: FaceLocator, sizePx: Int): String = face.encode() + "@" + sizePx

  companion object {
    /** D25: 8 MB. */
    const val CACHE_BYTES: Long = 8L * 1024 * 1024
  }
}
