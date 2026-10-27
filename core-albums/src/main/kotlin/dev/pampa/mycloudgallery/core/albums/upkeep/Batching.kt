package dev.pampa.mycloudgallery.core.albums.upkeep

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// DUE PICCOLI STRUMENTI PER TENERE AL PASSO GLI ALBUM (Fase 7, revisione di fine fase; AS-3, VG-1). Niente orologio proprio: l'attesa e' `delay`, quindi
// i test la guidano con il tempo virtuale di `runTest`. Mai niente di personale qui dentro.

/**
 * Raccoglie i cambiamenti a raffica e li consegna a gruppi: il primo cambiamento fa partire un'attesa di [quiet], poi tutto cio' che si e' raccolto
 * va a [deliver] **in un colpo solo**. **Cio' che arriva mentre [deliver] lavora non si perde** (AS-3): finito il giro, se c'e' altro si ricomincia con
 * una nuova attesa. Prima il giro vedeva `pending` ancora attivo, non ne lanciava un altro, e le novita' arrivate nel frattempo aspettavano la novita'
 * dopo (che poteva non venire mai).
 *
 * Un solo [deliver] per volta. Un errore di [deliver] non ferma il resto: chi consegna lo conta (`SmartUpkeep` ha il suo `onError`).
 */
class ChangeBatcher<T>(
  private val scope: CoroutineScope,
  private val quiet: Duration,
  private val deliver: suspend (List<T>) -> Unit,
) {
  private val lock = Any()
  private val pending = LinkedHashSet<T>()
  private var running = false

  fun add(items: Collection<T>) {
    if (items.isEmpty()) return
    val launchNow = synchronized(lock) {
      pending += items
      !running && run { running = true; true }
    }
    if (launchNow) scope.launch { drain() }
  }

  /** Quanti elementi aspettano di essere consegnati (per i test). */
  val waiting: Int get() = synchronized(lock) { pending.size }

  private suspend fun drain() {
    while (true) {
      delay(quiet)
      val batch = synchronized(lock) { pending.toList().also { pending.clear() } }
      try {
        if (batch.isNotEmpty()) deliver(batch)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Chi consegna conta i suoi errori; il prossimo gruppo arriva lo stesso.
      }
      // La decisione di fermarsi e il segno "non gira piu'" sono un passo solo: un `add` che arriva fra i due non resta senza giro.
      val done = synchronized(lock) { pending.isEmpty().also { if (it) running = false } }
      if (done) return
    }
  }
}

/**
 * Esegue [action] quando per [quiet] non succede piu' niente: ogni [poke] riparte dall'inizio dell'attesa (il fronte di discesa, non quello di salita
 * di [ChangeBatcher]). Per VG-1: i viaggi si cercano quando la scansione si e' fermata, non al primo gruppo di foto. Un'azione gia' partita **non si
 * annulla** da un [poke] nuovo: finisce, e il [poke] ne prepara un'altra.
 */
class Debouncer(
  private val scope: CoroutineScope,
  private val quiet: Duration,
  private val action: suspend () -> Unit,
) {
  private val lock = Any()
  private var timer: Job? = null

  fun poke() {
    synchronized(lock) {
      timer?.cancel()
      timer = scope.launch {
        delay(quiet)
        // Fuori dal timer: un `poke` che arriva mentre l'azione lavora cancella il timer, non l'azione.
        scope.launch {
          try {
            action()
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            // Chi passa l'azione conta i suoi errori; un'eccezione non deve arrivare al gestore dell'ambito (chiuderebbe l'app).
          }
        }
      }
    }
  }
}
