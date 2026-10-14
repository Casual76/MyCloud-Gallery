package dev.pampa.mycloudgallery.core.library.contract

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea

/**
 * Lo stato del NAS mostrato nel piede della Libreria (Fase 4, `docs/fase4-progetto.md` §1.5, D10, D31): **cosa** dire e
 * **quando**; **come** dirlo e' del filone grafico (`statusFooter`, slot di :app). Contratto di P0: P7 lo mette in
 * `LibraryTabState` (solo dati), :app (P11) lo compone da `NasScanner.status`, `IndexSync.status`, `FreezeState` e dai
 * flag.
 *
 * Sta in `core-library` (e non in `feature-library/.../library/LibraryNasStatus*`, dove il §4.4 lo metteva) perche' quei
 * file li sta cambiando il filone grafico: cosi' il contratto c'e' subito e la schermata lo riceve come dato.
 *
 * Tempi in ms; istanti in **ora del NAS**. `eta` null = non ancora stimabile.
 */
@Immutable
sealed interface LibraryNasStatus {

  /** Nessun NAS configurato o niente da dire (il piede non mostra nulla). */
  data object Idle : LibraryNasStatus

  /** Prima scansione in corso: [total] noto solo dopo il primo elenco completo. */
  data class FirstScan(val done: Int, val total: Int?, val etaMillis: Long?) : LibraryNasStatus

  /** Primo allineamento (o arretrato) dell'indice condiviso: byte noti dalle teste (E-I9, E-I10). */
  data class Aligning(val bytesDone: Long, val bytesTotal: Long, val etaMillis: Long?) : LibraryNasStatus

  /** Tutto aggiornato all'istante [atNasMillis]. */
  data class UpToDate(val atNasMillis: Long) : LibraryNasStatus

  /**
   * Una share congelata per cambiamenti di massa (D10): in **evidenza** (Q6: l'avviso va nel riepilogo giornaliero, non
   * in una notifica a se'), con l'ingresso a "Ho controllato, continua".
   */
  data class Frozen(val reason: FreezeReason, val sinceNasMillis: Long) : LibraryNasStatus

  /** Aree non raggiungibili adesso (NAS spento, fuori casa senza VPN, share negata): la Libreria mostra la replica. */
  data class AreasUnavailable(val areas: Set<NasArea>) : LibraryNasStatus

  /** D31: si lavora in solo locale (pubblicazione solo sulle share di prova finche' l'utente non chiude la 4A). */
  data object LocalOnly : LibraryNasStatus
}
