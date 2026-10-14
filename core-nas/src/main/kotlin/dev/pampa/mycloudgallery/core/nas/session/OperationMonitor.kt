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
 * **Sorvegliante a progresso (D3 della Fase 4).** Un'operazione corta ha una scadenza totale (15 s interattiva, 60 s in
 * background). Un'operazione **a flusso** (che ha chiamato `startTransfer`) la sposta in avanti di [chunkBudgetMs] a
 * ogni blocco arrivato ([onChunk]): la scadenza vale per blocco, non per il totale, finche' i byte arrivano; resta la
 * soglia anti-blocco del watchdog (32 KiB/s per 20 s, E-R14). Un trasferimento in piu' operazioni (`readAll`) ha un
 * biglietto suo senza scadenza ([transferOnly]) che sorveglia solo il ritmo.
 *
 * @property firstDiskTouch la prima operazione sui dischi di questa sessione: tempo piu' lungo e, oltre la soglia,
 *   stato "Risveglio del NAS…".
 */
internal class OpTicket(
  val owner: Any,
  val startedAt: Long,
  deadlineAt: Long,
  val firstDiskTouch: Boolean,
  /** La scadenza di ogni blocco di un'operazione a flusso (D3): quella dell'operazione corta per lo stesso scopo. */
  val chunkBudgetMs: Long = deadlineAt - startedAt,
  /** Un biglietto che sorveglia solo il ritmo di un trasferimento fatto di piu' operazioni (niente scadenza). */
  val transferOnly: Boolean = false,
) {
  /** La scadenza attuale: fissa per le operazioni corte, spostata a ogni blocco per quelle a flusso. */
  @Volatile
  var deadlineAt: Long = deadlineAt
    private set

  /**
   * Un blocco e' arrivato adesso: la prossima scadenza e' fra [chunkBudgetMs] (solo per le operazioni a flusso). Il
   * tempo lungo della prima operazione sui dischi (E-R1) vale fino al primo blocco: poi i dischi sono svegli.
   */
  fun onChunk(nowMs: Long) {
    if (transfer != null && !transferOnly) deadlineAt = nowMs + chunkBudgetMs
  }

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

  /**
   * Un giro di controllo (anche chiamato dai test).
   *
   * Prima si decide il verdetto di TUTTE le operazioni da interrompere e lo si scrive sui loro biglietti, poi si chiude la
   * connessione (una volta per biglietto, la prima basta). Chiudere la connessione sveglia subito le letture sorelle ferme
   * sullo stesso socket: se il loro biglietto non fosse ancora segnato, scambierebbero il blocco per una caduta da
   * riprovare (`RECONNECT`) invece di riportare `Timeout`/`Stalled`, e ripartirebbero su una connessione nuova.
   */
  fun inspect(snapshot: List<OpTicket> = synchronized(lock) { tickets.toList() }) {
    val now = clock.nowMillis()
    val verdicts = ArrayList<Pair<OpTicket, NasError>>()
    for (ticket in snapshot) {
      if (ticket.abortedBy != null) continue
      when {
        now >= ticket.deadlineAt -> verdicts += ticket to NasError.Timeout
        ticket.transfer?.isStalled(now) == true -> verdicts += ticket to NasError.Stalled
        ticket.firstDiskTouch && !ticket.wakingReported && now - ticket.startedAt >= wakingThresholdMs -> {
          ticket.wakingReported = true
          onWaking(ticket)
        }
      }
    }
    for ((ticket, error) in verdicts) ticket.abortedBy = error
    for ((ticket, error) in verdicts) onAbort(ticket, error)
  }
}
