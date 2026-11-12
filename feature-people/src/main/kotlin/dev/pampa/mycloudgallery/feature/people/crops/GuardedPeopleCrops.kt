package dev.pampa.mycloudgallery.feature.people.crops

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.faces.contract.FaceCropGate
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * I6 (fase I, rifinitura, con la review CG-I1): **ogni svuotamento apre una generazione nuova**, e un ritaglio cominciato prima non rientra nella
 * cache svuotata. Se nel frattempo c'e' stata una **revoca** ([clear]) il ritaglio non torna nemmeno a chi l'aveva chiesto.
 *
 * Le bitmap non vengono riciclate qui: possono essere ancora sullo schermo. Mai un ritaglio, un volto o un id nei log (D37).
 */
class GuardedPeopleCrops(
  private val source: PeopleCrops,
  private val gate: FaceCropGate,
  private val cache: ByteLru<String, Bitmap> = ByteLru(CACHE_BYTES) { it.allocationByteCount },
) : PeopleCrops {

  /** Tiene insieme la generazione, lo svuotamento e la pubblicazione nella cache. */
  private val lock = Any()

  /** Quante volte la cache e' stata svuotata ([trim] o [clear]); sotto [lock]. */
  private var generation = 0L

  private val producedCount = AtomicLong()

  override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap? {
    val key = keyOf(face, sizePx)
    // Il segno delle revoche all'inizio: se cambia mentre la sorgente lavora, il ritaglio non vale piu'.
    val revocation = clearsFlow.value
    if (!gate.mayShow(face)) {
      cache.remove(key)
      return null
    }
    cache.get(key)?.let { return it }
    // La generazione si legge sotto il lock prima di avviare la sorgente: uno svuotamento da qui in poi tiene il risultato fuori dalla cache.
    val startedIn = synchronized(lock) { generation }
    producedCount.incrementAndGet()
    val bitmap = source.crop(face, sizePx) ?: return null
    synchronized(lock) {
      // Una revoca mentre la sorgente lavorava: il ritaglio non torna a nessuno e non entra in cache (la privacy prima del segnaposto).
      if (clearsFlow.value != revocation) return null
      // Uno svuotamento per memoria mentre la sorgente lavorava: il ritaglio vale (torna a chi l'ha chiesto) ma la cache liberata resta libera.
      if (generation == startedIn) cache.put(key, bitmap)
    }
    return bitmap
  }

  /**
   * Svuota la cache per una ragione di **privacy** (Persone spenta, cancellazione): cambia [clears], e chi tiene una copia dei ritagli (le Raccolte)
   * la butta. Un ritaglio in corso non esce.
   */
  fun clear() {
    synchronized(lock) {
      generation++
      cache.clear()
      clearsFlow.value = clearsFlow.value + 1
    }
  }

  /**
   * I6: svuota la cache **per memoria** (`onTrimMemory`) e apre una generazione nuova, senza cambiare [clears]. La privacy non c'entra: i ritagli gia'
   * dati restano validi (una foto nascosta li toglie gia' dalla vista, Persone spenta o la cancellazione passano da [clear]), quindi le Raccolte non
   * buttano e non rifanno i volti che hanno, e nessuno chiede ritagli per lo svuotamento.
   */
  fun trim() {
    synchronized(lock) {
      generation++
      cache.clear()
    }
  }

  private val clearsFlow = MutableStateFlow(0L)

  /**
   * CU-19 (fase H, giro 3): quante volte la cache e' stata svuotata per privacy. Chi tiene una copia dei ritagli fuori da qui (le righe delle
   * Raccolte) la butta quando cambia: una cancellazione o Persone spenta non passano dalla vista del catalogo.
   */
  val clears: StateFlow<Long> get() = clearsFlow

  /** Quanti byte di ritagli tiene in memoria adesso (per le prove: mai oltre il tetto). */
  val cachedBytes: Long get() = cache.weight

  /** I6: quante volte la sorgente ha cominciato un ritaglio (per le prove: uno svuotamento per memoria non fa ritagliare niente). */
  val produced: Long get() = producedCount.get()

  private fun keyOf(face: FaceLocator, sizePx: Int): String = face.encode() + "@" + sizePx

  companion object {
    /** D25: 8 MB. */
    const val CACHE_BYTES: Long = 8L * 1024 * 1024
  }
}
