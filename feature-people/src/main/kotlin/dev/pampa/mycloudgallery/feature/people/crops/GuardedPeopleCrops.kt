package dev.pampa.mycloudgallery.feature.people.crops

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.faces.contract.FaceCropGate
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Le copertine e i ritagli come li vuole la Fase 8 (docs/fase8-progetto.md D25, D28, §2.6 "Copertine e ritagli"): [source] ritaglia **al
 * momento** dalla migliore immagine gia' sul dispositivo (l'originale del telefono con la decodifica della sola regione, l'HQ della replica,
 * l'anteprima del buffer, la miniatura M); questa classe aggiunge le due regole che chi lega la porta deve rispettare:
 *
 *  1. **solo da un media mostrabile**: prima di ogni ritaglio (anche se l'immagine e' in cache) il cancello [gate] dice se il volto sta in un
 *     media che si puo' mostrare adesso. Un media nascosto un attimo fa non da' una copertina, e il suo ritaglio gia' in memoria non esce;
 *  2. **mai su disco**: i ritagli stanno in una cache **in memoria** da [CACHE_BYTES] (LRU), che si svuota con [clear] (lo fa `:app` quando
 *     Persone si spegne o quando si cancella: una ragione di privacy) o con [trim] (a `onTrimMemory`: solo memoria). Nessun file, nessun database,
 *     nessun `cacheDir`.
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
    if (pausedFlow.value) {
      // I6: dopo uno svuotamento per memoria con l'app nascosta non si ritaglia niente finche' l'app non torna visibile ([resume]). Poi il cancello
      // si richiede: durante l'attesa la foto puo' essere stata nascosta.
      pausedFlow.first { !it }
      if (!gate.mayShow(face)) {
        cache.remove(key)
        return null
      }
      cache.get(key)?.let { return it }
    }
    val bitmap = source.crop(face, sizePx) ?: return null
    cache.put(key, bitmap)
    return bitmap
  }

  /**
   * Svuota la cache per una ragione di **privacy** (Persone spenta, cancellazione): cambia [clears], e chi tiene una copia dei ritagli (le Raccolte)
   * la butta.
   */
  fun clear() {
    cache.clear()
    clearsFlow.value = clearsFlow.value + 1
  }

  /**
   * I6: svuota la cache **per memoria** (`onTrimMemory`). La privacy non c'entra: i ritagli gia' dati restano validi (una foto nascosta li toglie gia'
   * dalla vista, Persone spenta o la cancellazione passano da [clear]), quindi [clears] non cambia e le Raccolte non buttano e non rifanno i volti che
   * hanno. Con [appHidden] i ritagli nuovi aspettano che l'app torni visibile ([resume]): niente lavoro ad app nascosta, e la memoria liberata resta
   * libera.
   */
  fun trim(appHidden: Boolean) {
    cache.clear()
    if (appHidden) pausedFlow.value = true
  }

  /** I6: l'app e' di nuovo visibile, i ritagli in attesa ripartono (dopo il cancello). */
  fun resume() {
    pausedFlow.value = false
  }

  private val clearsFlow = MutableStateFlow(0L)

  private val pausedFlow = MutableStateFlow(false)

  /** I6: se i ritagli nuovi aspettano che l'app torni visibile (dopo [trim] con l'app nascosta). */
  val paused: StateFlow<Boolean> get() = pausedFlow

  /**
   * CU-19 (fase H, giro 3): quante volte la cache e' stata svuotata. Chi tiene una copia dei ritagli fuori da qui (le righe delle Raccolte) la butta
   * quando cambia: una cancellazione o Persone spenta non passano dalla vista del catalogo.
   */
  val clears: StateFlow<Long> get() = clearsFlow

  /** Quanti byte di ritagli tiene in memoria adesso (per le prove: mai oltre il tetto). */
  val cachedBytes: Long get() = cache.weight

  private fun keyOf(face: FaceLocator, sizePx: Int): String = face.encode() + "@" + sizePx

  companion object {
    /** D25: 8 MB. */
    const val CACHE_BYTES: Long = 8L * 1024 * 1024
  }
}
