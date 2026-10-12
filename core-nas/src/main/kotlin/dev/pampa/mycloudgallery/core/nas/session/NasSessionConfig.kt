package dev.pampa.mycloudgallery.core.nas.session

import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * I tempi e i limiti della sessione (docs/fase2-progetto.md §3.1). I valori di default sono quelli del progetto;
 * i test li accorciano.
 */
data class NasSessionConfig(
  /** Connect TCP (6 s), o 20 s quando il NAS si sta risvegliando o l'ultima connect e' scaduta. */
  val tcpConnectTimeout: Duration = 6.seconds,
  val tcpConnectTimeoutWaking: Duration = 20.seconds,
  /** NEGOTIATE (oltre alla connect TCP) e autenticazione: ognuna al massimo questo. */
  val handshakeTimeout: Duration = 20.seconds,
  /** Timeout di una singola operazione: 15 s con l'utente che aspetta, 60 s in background. */
  val interactiveOpTimeout: Duration = 15.seconds,
  val backgroundOpTimeout: Duration = 60.seconds,
  /** La prima operazione che tocca i dischi di una sessione puo' trovarli fermi (E-R1). */
  val firstDiskTouchTimeout: Duration = 60.seconds,
  /** Oltre questo, la prima operazione sui dischi mostra "Risveglio del NAS…" (E-R1, E-R15). */
  val wakingThreshold: Duration = 2.seconds,
  /** Sessione chiusa dopo questa inattivita'. */
  val idleTimeout: Duration = 5.minutes,
  /** Nuovi tentativi di un'operazione idempotente dopo una caduta. */
  val maxRetries: Int = 2,
  /** Attese prima del nuovo tentativo n (0, 1, 2...): 1, 2 e 4 s. */
  val retryBackoff: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds),
  /** Richieste SMB in volo al massimo (i crediti di smbj danno errore dopo 5 s di attesa). */
  val maxInFlight: Int = 8,
  /** Watchdog dei trasferimenti (E-R14): sotto [stallMinBytesPerSecond] per [stallWindow] = bloccato. */
  val stallWindow: Duration = 20.seconds,
  val stallMinBytesPerSecond: Long = 32L * 1024,
  /** Ogni quanto il sorvegliante controlla scadenze, blocchi e risveglio. */
  val monitorTick: Duration = 1.seconds,
) {
  init {
    require(maxRetries >= 0 && maxInFlight >= 1 && retryBackoff.isNotEmpty())
  }

  fun opTimeout(purpose: Purpose): Duration = when (purpose) {
    Purpose.INTERACTIVE, Purpose.ONBOARDING -> interactiveOpTimeout
    Purpose.BACKGROUND -> backgroundOpTimeout
  }

  fun backoff(attempt: Int): Duration = retryBackoff[attempt.coerceIn(0, retryBackoff.lastIndex)]
}
