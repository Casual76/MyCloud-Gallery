package dev.pampa.mycloudgallery.core.library.status

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Lo stato NAS del piede della Libreria (Fase 4, P7, docs/fase4-progetto.md §1.5): COSA dire e QUANDO, mai l'aspetto.
//
// `LibraryNasStatus` (il contratto, P0) e' il dato che la schermata riceve. Qui c'e' la regola che lo compone dai segnali
// dei moduli del NAS. Per non far dipendere `core-library` da `core-nasmedia` e `core-index` (grafo del §1.1) la regola
// lavora su ingressi PIATTI e piccoli ([NasStatusInputs]); `:app` (P11) li riempie dai tipi veri, una riga ciascuno:
//
//   NasScanStatus.Scanning(mode = Full, filesSeen, filesTotal)       → ScanProgress.First(done = filesSeen, total = filesTotal)
//   NasScanStatus.Scanning(mode = Verify | Folder, ...)              → ScanProgress.Verifying
//   NasScanStatus.Idle | Finished                                    → ScanProgress.Idle
//   NasScanStatus.Frozen(freeze)  /  FreezeState.frozen              → FrozenInfo(freeze.reason, freeze.sinceNasMillis)
//   NasScanStatus.Unavailable(code)  /  IndexSyncStatus.Unavailable  → NasAreaProgress.unavailable = true (l'area della share)
//   IndexSyncStatus.Aligning(bytesDone, bytesTotal, _)               → SyncProgress.Aligning(bytesDone, bytesTotal)
//   IndexSyncStatus.UpToDate(atNasMillis)                            → SyncProgress.UpToDate(atNasMillis)
//   IndexSyncStatus.Idle | Unavailable                               → SyncProgress.Idle
//   FeatureFlags.indexPublishTestSharesOnly con share non di prova   → NasStatusInputs.localOnly = true (D31)
//
// e lo collegano a `LibraryTabState.nas` (il campo lo aggiunge l'integrazione): `composer.statusFlow(inputs)`.

/** Come va la scansione di un'area, per il piede. */
sealed interface ScanProgress {
  /** Niente in corso (o l'ultimo giro e' finito). */
  data object Idle : ScanProgress

  /** La **prima** scansione: i file visti finora e il totale, noto solo dopo il primo elenco completo (null prima). */
  data class First(val done: Int, val total: Int?) : ScanProgress

  /** Una verifica o una cartella: lavoro di routine, non si mostra come avanzamento. */
  data object Verifying : ScanProgress
}

/** Una share congelata per cambiamenti di massa (D10): perche' e da quando, in ora del NAS. */
@Immutable
data class FrozenInfo(val reason: FreezeReason, val sinceNasMillis: Long)

/** Come va l'allineamento dell'indice condiviso (le teste e i segmenti, E-I9, E-I10). */
sealed interface SyncProgress {
  data object Idle : SyncProgress

  data class Aligning(val bytesDone: Long, val bytesTotal: Long) : SyncProgress

  data class UpToDate(val atNasMillis: Long) : SyncProgress
}

/** Lo stato di un'area: privata o comune. [unavailable]: non raggiungibile adesso (NAS spento, fuori casa, share negata). */
@Immutable
data class NasAreaProgress(
  val area: NasArea,
  val scan: ScanProgress = ScanProgress.Idle,
  val frozen: FrozenInfo? = null,
  val unavailable: Boolean = false,
)

/**
 * Tutto cio' che serve a decidere cosa dice il piede.
 *
 * @property configured c'e' un NAS configurato (famiglia pronta, almeno un'area): senza, il piede non dice niente.
 * @property localOnly D31: si lavora in solo locale (pubblicazione solo sulle share di prova).
 * @property lastVerifiedNasMillis l'ultima verifica riuscita di tutte le aree, se l'allineamento non la dice.
 */
@Immutable
data class NasStatusInputs(
  val configured: Boolean,
  val areas: List<NasAreaProgress>,
  val sync: SyncProgress = SyncProgress.Idle,
  val localOnly: Boolean = false,
  val lastVerifiedNasMillis: Long? = null,
)

/**
 * La regola che compone [LibraryNasStatus] da [NasStatusInputs]. **Precedenza**, dalla piu' importante:
 *
 * 1. nessun NAS → [LibraryNasStatus.Idle];
 * 2. un'area **congelata** → [LibraryNasStatus.Frozen] (la piu' vecchia): e' l'unica cosa che l'utente deve decidere
 *    (D10, risposta Q6: in evidenza in Libreria, non una notifica a se');
 * 3. la **prima scansione** in corso → [LibraryNasStatus.FirstScan] (file visti, totale se noto per tutte le aree, tempo
 *    stimato);
 * 4. l'**allineamento** dell'indice in corso → [LibraryNasStatus.Aligning] (byte, tempo stimato);
 * 5. aree **non raggiungibili** → [LibraryNasStatus.AreasUnavailable]: la Libreria mostra la replica (E-L4);
 * 6. **solo locale** (D31) → [LibraryNasStatus.LocalOnly];
 * 7. tutto fermo e aggiornato → [LibraryNasStatus.UpToDate]; se non si sa quando → [LibraryNasStatus.Idle].
 *
 * L'avanzamento viene prima della non-raggiungibilita': un'attivita' in corso e' piu' interessante di una situazione
 * che dura, e finito il lavoro il piede mostra comunque cosa non va.
 *
 * Il tempo stimato (`etaMillis` di `FirstScan` e `Aligning`) viene dalla velocita' vista negli ultimi
 * [EtaEstimator.WINDOW_MS] ms di osservazioni e resta null finche' non ce ne sono abbastanza
 * ([EtaEstimator.MIN_ELAPSED_MS]). [monotonicMs] e' l'orologio monotono (mai l'ora del telefono, mai quella del NAS:
 * e' una durata). Un compositore ha uno stato (le osservazioni): uno per Libreria, non condiviso fra thread.
 */
class LibraryNasStatusComposer(
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

  private val scanEta = EtaEstimator(monotonicMs)
  private val syncEta = EtaEstimator(monotonicMs)

  fun compose(inputs: NasStatusInputs): LibraryNasStatus {
    if (!inputs.configured || inputs.areas.isEmpty()) {
      scanEta.reset()
      syncEta.reset()
      return LibraryNasStatus.Idle
    }

    inputs.areas.mapNotNull { it.frozen }.minWithOrNull(compareBy<FrozenInfo>({ it.sinceNasMillis }, { it.reason.code }))?.let { frozen ->
      return LibraryNasStatus.Frozen(frozen.reason, frozen.sinceNasMillis)
    }

    val first = inputs.areas.mapNotNull { it.scan as? ScanProgress.First }
    if (first.isNotEmpty()) {
      syncEta.reset()
      val done = first.sumOf { it.done.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      val total = if (first.all { it.total != null }) first.sumOf { it.total!!.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
      val eta = if (total != null && total > 0) {
        scanEta.update(done.toDouble(), total.toDouble())
      } else {
        // Senza un totale non c'e' una stima, e le osservazioni di prima non valgono per quando il totale arrivera'.
        scanEta.reset()
        null
      }
      return LibraryNasStatus.FirstScan(done = done, total = total, etaMillis = eta)
    }
    scanEta.reset()

    val sync = inputs.sync
    if (sync is SyncProgress.Aligning && sync.bytesTotal > 0 && sync.bytesDone < sync.bytesTotal) {
      val eta = syncEta.update(sync.bytesDone.toDouble(), sync.bytesTotal.toDouble())
      return LibraryNasStatus.Aligning(sync.bytesDone, sync.bytesTotal, eta)
    }
    syncEta.reset()

    val unavailable = inputs.areas.filter { it.unavailable }.map { it.area }.toSet()
    if (unavailable.isNotEmpty()) return LibraryNasStatus.AreasUnavailable(unavailable)

    if (inputs.localOnly) return LibraryNasStatus.LocalOnly

    val at = (sync as? SyncProgress.UpToDate)?.atNasMillis ?: inputs.lastVerifiedNasMillis
    return if (at != null) LibraryNasStatus.UpToDate(at) else LibraryNasStatus.Idle
  }

  /** Lo stato per ogni cambiamento degli ingressi, senza ripetere lo stesso valore. */
  fun statusFlow(inputs: Flow<NasStatusInputs>): Flow<LibraryNasStatus> = inputs.map(::compose).distinctUntilChanged()
}

/**
 * Il tempo che manca, dalla velocita' recente: la differenza fra l'osservazione piu' vecchia della finestra e quella
 * nuova. Null finche' sono passati meno di [MIN_ELAPSED_MS] dalla prima osservazione o non c'e' stato progresso (un
 * numero di "mancano 3 secondi" a 200 file su 12.000 sarebbe una bugia). Se il valore torna indietro (una scansione
 * ripartita) si ricomincia.
 */
internal class EtaEstimator(private val monotonicMs: () -> Long) {

  private val times = ArrayDeque<Long>()
  private val values = ArrayDeque<Double>()

  fun reset() {
    times.clear()
    values.clear()
  }

  /** Registra [progress] su [total] e restituisce i millisecondi che mancano, o null se non si puo' ancora dire. */
  fun update(progress: Double, total: Double): Long? {
    val now = monotonicMs()
    if (values.isNotEmpty() && progress < values.last()) reset()
    times.addLast(now)
    values.addLast(progress)
    // Fuori dalla finestra le osservazioni vecchie, ma ne resta sempre una prima di quella nuova.
    while (times.size > 2 && now - times[1] >= WINDOW_MS) {
      times.removeFirst()
      values.removeFirst()
    }
    val elapsed = now - times.first()
    val advanced = progress - values.first()
    if (elapsed < MIN_ELAPSED_MS || advanced <= 0.0) return null
    val remaining = (total - progress).coerceAtLeast(0.0)
    return (remaining * elapsed / advanced).toLong()
  }

  companion object {
    const val MIN_ELAPSED_MS: Long = 5_000L
    const val WINDOW_MS: Long = 30_000L
  }
}
