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
 *
 * **Dove si usa (K5).** L'errore di scrittura si traduce **una volta sola, nello strato che puo' mostrare un esito o riprovare**:
 * - **si':** nel controller o nel view-model che lancia la scrittura da un ambito di interfaccia, guardando l'esito ([attempt] falso, [attemptResult] null) per dire "non
 *   salvato", rimettere il valore di prima o tenere la scelta in attesa;
 * - **si':** dentro un deposito il cui setter **restituisce l'esito** a chi chiama (`Boolean`, un numero, un `Outcome`);
 * - **si', senza guardare l'esito:** solo se **nessun** chiamante distingue il successo dal fallimento e non c'e' dove dirlo (un interruttore che legge lo stato salvato e
 *   torna dov'era; una scrittura che l'app fa da sola, come un "visto" o un contatore);
 * - **no:** dentro un setter che restituisce `Unit` quando chi sta sopra ha un `try`, un `attempt { }`, un `runCatching` o un tentativo ripetuto costruiti sull'eccezione:
 *   il fallimento diventa un successo muto, chi doveva riprovare crede di aver consegnato e chi doveva dirlo all'utente tace (K3b l'aveva fatto in `core-personal`: l'ordine
 *   delle Raccolte non si ritentava piu', "Annulla" compariva per uno scarto non salvato). Li' il setter lascia passare l'`IOException` e lo dichiara (`@Throws`).
 *
 * Prima di avvolgere una scrittura, o di togliere l'involucro, si guardano **tutti** i chiamanti fino all'interfaccia: se uno non ha un confine, il confine va messo in quel
 * controller o view-model (mai nella composizione), non piu' in basso.
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

  /**
   * Come [attempt], ma per una scrittura che da' un valore (l'esito di una modifica agli album, per esempio): null se non si e' riusciti a scrivere, e allora **non e' cambiato niente**
   * (la scrittura deve essere "tutto o niente": `LedgerPersonalStore` lo e', anche in memoria). K3b, SOL-10 e OA-11 della review generale: le azioni di un album scrivono il registro dei dati
   * personali, che con la memoria piena lancia `IOException`.
   */
  suspend fun <T : Any> attemptResult(label: String, write: suspend () -> T): T? = try {
    write()
  } catch (e: CancellationException) {
    throw e
  } catch (e: IOException) {
    GalleryLog.w(TAG) { "scrittura non salvata: $label (${e.javaClass.simpleName})" }
    null
  }
}
