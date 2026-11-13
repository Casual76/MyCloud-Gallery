package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.ai.VisibilitySignal
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.mediastore.contract.RereadLedger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Confine sincrono: nessuna derivazione Flow fra il proprietario e il nastro. Decide solo **quando il nastro puo' risolvere le anteprime**
 * ([canResolve]); il neutro, la generazione e il ricalcolo li fa chi ascolta ([register], `AppAnalysisLive`).
 *
 * Il nastro e' chiuso (neutro) in quattro casi, e riapre **solo** quando il motivo e' finito davvero, mai a tempo:
 * - **ritirato** ([retire]): per sempre;
 * - **non autorizzato** (`authorized`: permessi non completi, selezione, galleria ferma): finche' i permessi non tornano completi;
 * - **un avviso di Android** ([contentChanged]: foto tolte, cestinate, cambiate): il nastro chiude **nello stesso istante** e riapre quando la rilettura del
 *   telefono **partita dopo l'avviso** e **finita bene** ([rereadFinished], dichiarata dalla sincronizzazione) e l'istantanea che la Libreria pubblica ha
 *   raggiunto il `change_seq` del catalogo di quel momento (la rilettura e' nel catalogo e il catalogo e' nell'istantanea). Un'istantanea gia' in viaggio,
 *   precedente alla rilettura, non riapre niente; una rilettura finita **senza cambiamenti** riapre lo stesso (XS5-1, XS5-2);
 * - **una transizione in corso** ([transition]: «Nascondi» o «Mostra»): dall'**inizio** dell'azione, prima che la foto esca dalla Libreria, fino alla sua
 *   fine (riuscita, fallita o annullata). Un contatore: piu' azioni insieme chiudono finche' l'ultima non e' finita (XS5-3).
 *
 * Se la rilettura fallisce o viene interrotta, o l'accesso non e' completo, nessuno dichiara niente e il nastro resta neutro fino alla prossima rilettura
 * riuscita (lato sicuro). I richiami sono leggeri: letture di variabile e qualche campo, nessun I/O, nessuna attesa.
 */
class AnalysisVisibilityBoundary(private val authorized: () -> Boolean, private val snapshot: () -> TimelineSnapshot?) : VisibilitySignal, RereadLedger {

  /** Dopo un avviso di Android: [Open] = niente da aspettare, [Waiting] = aspetta un giro che copra l'avviso [Waiting.order], [Settling] = il giro e' finito, aspetta l'istantanea. */
  private sealed interface Gate {
    data object Open : Gate

    class Waiting(val order: Long) : Gate

    class Settling(val seq: Long) : Gate
  }

  private val listeners = CopyOnWriteArrayList<() -> Unit>()
  internal val listenerCount: Int get() = listeners.size

  /** Le scritture di [order], [gate] e [transitions] stanno sotto questo lucchetto (mai si chiamano i listener tenendolo); le letture sono senza. */
  private val lock = Any()
  @Volatile private var retired = false
  @Volatile private var order = 0L
  @Volatile private var gate: Gate = Gate.Open
  @Volatile private var transitions = 0

  override fun register(onChange: () -> Unit): AutoCloseable {
    listeners += onChange
    return AutoCloseable { listeners -= onChange }
  }

  override fun canResolve(): Boolean {
    if (retired || transitions > 0 || !authorized()) return false
    return when (val g = gate) {
      Gate.Open -> true
      is Gate.Waiting -> false
      is Gate.Settling -> (snapshot()?.seq ?: Long.MIN_VALUE) >= g.seq
    }
  }

  fun changed() { listeners.forEach { it() } }

  /** Android ha avvisato che le foto sono cambiate, prima della rilettura: si numera l'avviso e si chiude, subito, fino a un giro che lo copra. */
  fun contentChanged() {
    synchronized(lock) {
      order += 1
      gate = Gate.Waiting(order)
    }
    changed()
  }

  override fun noticeOrder(): Long = order

  override fun rereadFinished(order: Long, catalogSeq: Long) {
    val moved = synchronized(lock) {
      val g = gate
      if (g is Gate.Waiting && order >= g.order) {
        gate = Gate.Settling(catalogSeq)
        true
      } else {
        false
      }
    }
    // Senza cambiamenti l'istantanea e' gia' al `change_seq`; con un'istantanea ancora indietro il ricalcolo parte quando arriva (la raccolta dell'istantanea).
    if (moved) changed()
  }

  /**
   * Una transizione che toglie foto alla Libreria («Nascondi» o «Mostra»): il nastro chiude **prima** di [block] (la foto esce dalla Libreria dentro il blocco,
   * un po' prima della scrittura) e riapre quando [block] e' finito, comunque finisca. La fine ricalcola: il filtro finale dei Nascosti toglie la foto.
   */
  suspend fun <T> transition(block: suspend () -> T): T {
    synchronized(lock) { transitions += 1 }
    changed()
    try {
      return block()
    } finally {
      val last = synchronized(lock) {
        transitions -= 1
        transitions == 0
      }
      if (last) changed()
    }
  }

  fun retire() {
    retired = true
    changed()
  }
}
