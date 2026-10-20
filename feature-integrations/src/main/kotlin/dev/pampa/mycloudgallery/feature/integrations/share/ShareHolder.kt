package dev.pampa.mycloudgallery.feature.integrations.share

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Correzioni 5B (MC-1, docs/review/funzionale-5b.md): QUANDO si toglie una copia consegnata a un'altra app. Prima la copia si cancellava e il
// permesso si revocava quando si spegneva l'avviso "Pronta" del viewer, 3,2 secondi dopo l'apertura della scelta dell'app: WhatsApp e Telegram,
// che leggono il file dopo che l'utente ha scelto la chat, trovavano un file che non c'era piu'. Adesso spegnere l'avviso e' solo lo stato della
// schermata, e la copia si rilascia **quando l'altra app ha finito**:
//  - al ritorno dell'utente nell'app (un'activity dell'app torna davanti dopo essere andata in pausa), con un margine ([afterReturnMs], un minuto)
//    per chi finisce di leggere in background subito dopo (Gmail che invia, il foglio traslucido di Telegram);
//  - comunque dopo un tempo lungo e sicuro ([maxHoldMs], mezz'ora) se l'utente non torna;
//  - in ogni caso all'avvio successivo del processo (`ShareCache.sweepAtStart` svuota `share/`).
// Le copie sono gia' senza posizione (o sono un solo-NAS scaricato): tenerle qualche minuto in piu' nella cache privata non espone niente; il
// permesso resta solo all'app scelta dall'utente.

/**
 * Le condivisioni consegnate (la scelta dell'app e' partita) che aspettano che l'altra app abbia finito. Una per processo. Le chiamate arrivano
 * dal thread principale (ciclo di vita delle activity) e dai timer: e' sincronizzato.
 */
class ShareHolder(
  private val scope: CoroutineScope,
  private val io: CoroutineContext = Dispatchers.IO,
  private val afterReturnMs: Long = AFTER_RETURN_MS,
  private val maxHoldMs: Long = MAX_HOLD_MS,
) {

  private class Held(val lease: ShareLease) {
    var left = false
    var timer: Job? = null
    var returnTimer: Job? = null
  }

  private val lock = Any()
  private val held = ArrayList<Held>()

  /** Quante condivisioni aspettano (per i test e la Diagnostica: solo un numero). */
  val count: Int get() = synchronized(lock) { held.size }

  /** La scelta dell'app e' partita con [lease]: la copia resta finche' l'altra app non ha finito, al piu' per [maxHoldMs]. */
  fun hold(lease: ShareLease) {
    val entry = Held(lease)
    synchronized(lock) { held += entry }
    entry.timer = scope.launch {
      delay(maxHoldMs)
      release(entry)
    }
  }

  /** Un'activity dell'app va in pausa (la scelta dell'app, l'altra app, la schermata home): l'utente e' uscito. */
  fun appLeft() {
    synchronized(lock) { held.forEach { it.left = true } }
  }

  /**
   * Un'activity dell'app torna davanti: le condivisioni consegnate **prima di uscire** sono finite, e si rilasciano fra [afterReturnMs]. Quelle
   * appena consegnate (l'app non e' ancora uscita) aspettano il loro ritorno.
   */
  fun appReturned() {
    val due = synchronized(lock) { held.filter { it.left && it.returnTimer == null } }
    for (entry in due) {
      entry.returnTimer = scope.launch {
        delay(afterReturnMs)
        release(entry)
      }
    }
  }

  /** Tutte subito: l'azzeramento dell'app. */
  fun releaseAll() {
    val all = synchronized(lock) { held.toList().also { held.clear() } }
    for (entry in all) {
      entry.timer?.cancel()
      entry.returnTimer?.cancel()
      entry.lease.release()
    }
  }

  private suspend fun release(entry: Held) {
    val mine = synchronized(lock) { held.remove(entry) }
    if (!mine) return
    // Prima il rilascio (revoca e cancellazione), che non si interrompe; poi l'altro timer, che non serve piu'.
    withContext(NonCancellable + io) { entry.lease.release() }
    entry.timer?.cancel()
    entry.returnTimer?.cancel()
  }

  companion object {
    /** Dopo il ritorno nell'app: il margine per chi finisce di leggere in background. */
    const val AFTER_RETURN_MS: Long = 60_000L

    /** Se l'utente non torna: dopo mezz'ora l'altra app ha finito da un pezzo. */
    const val MAX_HOLD_MS: Long = 30L * 60 * 1000
  }
}
