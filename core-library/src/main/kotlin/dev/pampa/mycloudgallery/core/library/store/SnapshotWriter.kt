package dev.pampa.mycloudgallery.core.library.store

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La scrittura **accorpata** dell'istantanea (§1.2: "accorpata a 5 s"): una raffica di cambiamenti (una scansione,
 * una selezione di 300 preferiti) produce una scrittura sola, con l'indice piu' recente, al piu' [delayMs] dopo il
 * primo. Il ritardo parte dal primo cambiamento e non si sposta con i successivi: un flusso continuo scrive comunque
 * ogni [delayMs].
 *
 * [writeNow] scrive subito e scavalca l'attesa: serve quando l'indice ha **perso** elementi (un nascosto, E-H1) e il
 * file non deve contenerli un istante in piu'. Se la scrittura fallisce (disco pieno) il file si cancella: meglio un
 * avvio a freddo piu' lento che un nascosto sul disco.
 *
 * Ogni scrittura ha un numero d'ordine: una scrittura piu' vecchia non sovrascrive mai una piu' nuova, anche se
 * arriva dopo al disco.
 */
class SnapshotWriter(
  private val store: SnapshotStore,
  private val scope: CoroutineScope,
  private val delayMs: Long = DEFAULT_DELAY_MS,
) {

  private class Pending(val snapshot: TimelineSnapshot, val order: Long)

  private val counter = AtomicLong()
  private val pending = AtomicReference<Pending?>(null)
  private val mutex = Mutex()
  private var lastWritten = 0L
  private var job: Job? = null
  private val written = AtomicInteger()

  /** Quante scritture sono arrivate al disco (test e Diagnostica). */
  val writes: Int get() = written.get()

  /** L'indice e' cambiato: si scrivera' entro [delayMs], insieme ai cambiamenti che arrivano nel frattempo. */
  fun schedule(snapshot: TimelineSnapshot) {
    pending.set(Pending(snapshot, counter.incrementAndGet()))
    synchronized(this) {
      if (job?.isActive == true) return
      job = scope.launch {
        delay(delayMs)
        flush()
      }
    }
  }

  /**
   * Scrive subito [snapshot]. [sensitive]: se la scrittura fallisce il file si cancella (l'indice ha perso elementi
   * che il file vecchio contiene ancora). Vero se il file su disco non contiene piu' cio' che e' stato tolto.
   */
  suspend fun writeNow(snapshot: TimelineSnapshot, provisional: Boolean = false, sensitive: Boolean = true): Boolean {
    val order = counter.incrementAndGet()
    pending.updateAndGet { p -> if (p != null && p.order < order) null else p }
    val ok = write(snapshot, order, provisional)
    return if (ok || !sensitive) ok else store.delete()
  }

  /** Scrive subito cio' che e' in attesa, se c'e'. */
  suspend fun flush() {
    val p = pending.getAndSet(null) ?: return
    write(p.snapshot, p.order, provisional = false)
  }

  private suspend fun write(snapshot: TimelineSnapshot, order: Long, provisional: Boolean): Boolean = mutex.withLock {
    if (order <= lastWritten) return@withLock true
    val ok = store.write(snapshot, provisional)
    if (ok) {
      lastWritten = order
      written.incrementAndGet()
    }
    ok
  }

  companion object {
    const val DEFAULT_DELAY_MS: Long = 5_000L
  }
}
