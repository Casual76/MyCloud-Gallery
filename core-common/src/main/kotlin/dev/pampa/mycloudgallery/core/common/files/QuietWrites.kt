package dev.pampa.mycloudgallery.core.common.files

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Le scritture delle **preferenze** (DataStore) che non fanno cadere l'app (OB-4, R-1 della review generale).
 *
 * `DataStore.edit` lancia `IOException` se il file temporaneo non si scrive (memoria piena, cartella non scrivibile), e una scrittura lanciata da un `viewModelScope` o
 * da un `rememberCoroutineScope` senza `try` chiude l'app. Con la memoria piena salvare un filtro o un interruttore non puo' essere un crash: la preferenza non si
 * salva, e chi chiama lo sa dall'esito.
 *
 * - Solo `IOException` (compresa `CorruptionException` di DataStore): un errore di programmazione (`IllegalStateException`...) resta un errore.
 * - La **cancellazione** non si inghiotte mai: se la coroutine viene annullata, l'annullamento passa.
 * - Nel log (livello warn) c'e' solo l'etichetta scelta da chi chiama e il tipo dell'errore, mai il messaggio: quello del sistema ha il percorso del file (C14, C21).
 */
object QuietWrites {

  private const val TAG = "QuietWrites"

  /**
   * Esegue [write] (una scrittura di preferenze). Vero se e' andata; falso se non si e' riusciti a scrivere: in quel caso il valore di prima e' ancora quello salvato.
   * [label] dice cosa si stava salvando (`"filtro della Libreria"`): niente nomi o percorsi dell'utente.
   */
  suspend fun attempt(label: String, write: suspend () -> Unit): Boolean = try {
    write()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: IOException) {
    GalleryLog.w(TAG) { "preferenza non salvata: $label (${e.javaClass.simpleName})" }
    false
  }
}
