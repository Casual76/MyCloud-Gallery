package dev.pampa.mycloudgallery.window

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La pagina da cui parte una finestra nuova (D16: "Apri in una nuova finestra" dal menu della Libreria, di un album, di un ricordo e della mappa). **Mai nell'intent**:
 * `MainActivity` e' esportata e l'intent con cui parte la seconda finestra e' sempre quello pulito del launcher; la scelta viaggia **dentro il processo**, in [WindowStarts].
 * Un id di album o di ricordo qui non e' un dato che esce: sta in memoria per qualche secondo e lo prende la finestra che nasce.
 */
sealed interface WindowTarget {
  /** La Libreria (la pagina di sempre). */
  data object Library : WindowTarget

  /** La pagina di un album. */
  data class Album(val id: AlbumId) : WindowTarget

  /** La pagina di un ricordo. */
  data class Memory(val id: MemoryId) : WindowTarget

  /** La mappa. */
  data object Map : WindowTarget
}

/**
 * **L'evento interno** che dice alla finestra che nasce da dove partire (D16, §2.4: "con un evento interno, mai con dati nell'intent"): chi apre la finestra lo lascia con
 * [offer], la finestra nuova lo prende con [claim], **una volta sola** e solo se e' fresco ([MAX_AGE_MS]: un'altra app che avvia una finestra a freddo non eredita una richiesta
 * vecchia). Un solo posto: una richiesta nuova sostituisce la vecchia. L'orologio lo passa chi chiama (monotono), cosi' la regola si prova senza Android.
 */
@Singleton
class WindowStarts @Inject constructor() {

  private class Pending(val target: WindowTarget, val atMs: Long)

  private var pending: Pending? = null

  /** Lascia la pagina da cui partira' la prossima finestra. */
  @Synchronized
  fun offer(target: WindowTarget, nowMs: Long) {
    pending = Pending(target, nowMs)
  }

  /** La finestra nuova prende la sua pagina: una volta sola, e solo se la richiesta e' fresca. Senza richiesta: `null` (si parte dalla Libreria). */
  @Synchronized
  fun claim(nowMs: Long): WindowTarget? {
    val taken = pending ?: return null
    pending = null
    val age = nowMs - taken.atMs
    return if (age in 0..MAX_AGE_MS) taken.target else null
  }

  /** La finestra non e' nata (rifiutata, errore dell'avvio): la richiesta non resta in giro. */
  @Synchronized
  fun cancel() {
    pending = null
  }

  /** C'e' una richiesta in attesa (per i test e la Diagnostica). */
  val isPending: Boolean @Synchronized get() = pending != null

  companion object {
    /** Quanto puo' aspettare una richiesta prima che nessuno la prenda: dieci secondi. */
    const val MAX_AGE_MS: Long = 10_000L
  }
}
