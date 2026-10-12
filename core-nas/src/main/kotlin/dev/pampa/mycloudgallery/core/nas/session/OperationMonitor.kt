package dev.pampa.mycloudgallery.core.nas.session

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Il watchdog di un trasferimento (E-R14): "bloccato" se negli ultimi [windowMs] sono passati meno di
 * [minBytesPerWindow] byte (cioe' sotto 32 KiB/s per 20 s). Un trasferimento sano sposta il punto di controllo a
 * ogni finestra piena.
 */
internal class TransferWatchdog(
  private val windowMs: Long,
  private val minBytesPerWindow: Long,
  startMs: Long,
) {
  private var checkpointAt = startMs
  private var bytesSinceCheckpoint = 0L

  @Synchronized
  fun onBytes(count: Int, nowMs: Long) {
    bytesSinceCheckpoint += count
    if (bytesSinceCheckpoint >= minBytesPerWindow) {
      checkpointAt = nowMs
      bytesSinceCheckpoint = 0
    }
  }

  @Synchronized
  fun isStalled(nowMs: Long): Boolean = nowMs - checkpointAt >= windowMs && bytesSinceCheckpoint < minBytesPerWindow
}

/**
 * Un'operazione SMB in corso, sorvegliata da [OperationMonitor].
 *
 * @property firstDiskTouch la prima operazione sui dischi di questa sessione: tempo piu' lungo e, oltre la soglia,
 *   stato "Risveglio del NAS…".
 */
internal class OpTicket(
  val owner: Any,
  val startedAt: Long,
  val deadlineAt: Long,
  val firstDiskTouch: Boolean,
) {
  /** Se il sorvegliante l'ha interrotta: l'errore da restituire (Timeout o Stalled). */
  @Volatile
  var abortedBy: NasError? = null

  @Volatile
  var transfer: TransferWatchdog? = null

  @Volatile
  var wakingReported: Boolean = false
}

/**
 * Il sorvegliante delle operazioni in corso. Gira solo finche' c'e' almeno un'operazione, a passi di [tick]
 * (orologio dello scope: tempo virtuale nei test). Per ogni operazione:
 * - oltre la scadenza → [onAbort] con `Timeout` (la connessione e' sospetta e si chiude, §3.1 "Annullamento");
 * - trasferimento sotto la soglia del watchdog → [onAbort] con `Stalled` (E-R14);
 * - prima operazione sui dischi oltre [wakingThresholdMs] → [onWaking] (E-R1).
 *
 * Chiudere la connessione e' l'unico modo di sbloccare una chiamata smbj ferma su un socket muto: e' cio' che fa
 * [onAbort] nel gestore.
 */
internal class OperationMonitor(
  private val clock: MonotonicClock,
  private val scope: CoroutineScope,
  private val tick: Duration,
  private val wakingThresholdMs: Long,
  private val onWaking: (OpTicket) -> Unit,
  private val onAbort: (OpTicket, NasError) -> Unit,
) {
  private val lock = Any()
  private val tickets = LinkedHashSet<OpTicket>()
  private var ticker: Job? = null

  fun begin(ticket: OpTicket) {
    synchronized(lock) {
      tickets += ticket
      if (ticker?.isActive != true) ticker = scope.launch { loop() }
    }
  }

  fun end(ticket: OpTicket) {
    synchronized(lock) { tickets -= ticket }
  }

  private suspend fun loop() {
    while (true) {
      delay(tick)
      val snapshot = synchronized(lock) {
        if (tickets.isEmpty()) {
          ticker = null
          return
        }
        tickets.toList()
      }
      inspect(snapshot)
    }
  }

  /** Un giro di controllo (anche chiamato dai test). */
  fun inspect(snapshot: List<OpTicket> = synchronized(lock) { tickets.toList() }) {
    val now = clock.nowMillis()
    for (ticket in snapshot) {
      if (ticket.abortedBy != null) continue
      when {
        now >= ticket.deadlineAt -> abort(ticket, NasError.Timeout)
        ticket.transfer?.isStalled(now) == true -> abort(ticket, NasError.Stalled)
        ticket.firstDiskTouch && !ticket.wakingReported && now - ticket.startedAt >= wakingThresholdMs -> {
          ticket.wakingReported = true
          onWaking(ticket)
        }
      }
    }
  }

  private fun abort(ticket: OpTicket, error: NasError) {
    ticket.abortedBy = error
    onAbort(ticket, error)
  }
}
