package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Fase 5A (docs/fase5-progetto.md §1.2 "core-backup"): i servizi del backup. Contratti di P0; motore e caricamento di P2, verifiche e
// guardiano di P3, piano e impostazioni di P4. **Nessuna eccezione verso fuori per un errore del NAS o del telefono**: gli esiti sono
// valori (un errore di programmazione resta un'eccezione).

/** Perche' un giro si e' fermato. */
enum class RunStop(override val code: String) : StableCode {
  /** Non c'e' altro da fare adesso. */
  COMPLETE("complete"),

  /** La tappa e' finita (8 minuti, `StagedRun`): si riprende al prossimo lavoro dal diario. */
  BUDGET("budget"),

  /** "Ferma" dell'utente o di Android ([StopBy]). */
  STOPPED("stopped"),

  /** Il cancello e' chiuso ([BackupGateState]): congelamento, emergenza, D12, area in sola lettura o assente. */
  GATE("gate"),

  /** NAS irraggiungibile, pieno, quota, password: il giro si ferma, non l'elemento (§2.2 "Rete"). */
  NAS("nas"),

  /** Permessi del telefono (E-P15, E-P21): il giro si ferma. */
  PHONE("phone"),

  /**
   * D-13 (prove sul campo): un file sul NAS e' ancora occupato (il proprio temporaneo tenuto da una connessione morta, un file aperto dal PC,
   * E-N13) e l'elemento non e' andato avanti in questo giro. **Non e' "finito"**: niente e' stato tralasciato, si riprova presto con un'attesa
   * crescente (WorkManager, `JobScheduler`), nel rispetto delle regole. Lo stato dice "Il NAS tiene ancora occupato un file: riprovo tra poco".
   */
  BUSY("busy"),
}

/**
 * L'esito di un giro: soli numeri e codici.
 *
 * @property done elementi arrivati a `DONE`; [linked] a `LINKED_EXISTING`; [gone] a `GONE`; [failed] a `FAILED`; [waiting] ancora
 *   aperti nel diario.
 * @property bytesUploaded byte scritti sul NAS (riscritture comprese); [bytesReread] riletti per la verifica (il traffico conta
 *   entrambi, §2.9).
 */
data class BackupRunResult(
  val done: Int,
  val linked: Int,
  val gone: Int,
  val failed: Int,
  val waiting: Int,
  val bytesUploaded: Long,
  val bytesReread: Long,
  val stoppedBy: RunStop,
  val problem: BackupProblemCode? = null,
) {
  companion object {
    fun nothing(stop: RunStop, problem: BackupProblemCode? = null): BackupRunResult =
      BackupRunResult(0, 0, 0, 0, 0, 0, 0, stop, problem)
  }
}

/**
 * Il motore del backup (D3, D11). Un giro pianifica i candidati del tipo [BackupKind] (foto prima dei video, dal piu' recente), li
 * scrive nel diario e li porta avanti uno alla volta (due in volo nel motore vero); [resumeAll] riprende solo cio' che c'e' nel diario
 * (dopo un riavvio, E-P4, E-P13). [stop] ferma prima del file successivo e lascia ogni temporaneo con il suo punto di ripresa.
 */
interface BackupEngine {
  val status: StateFlow<BackupStatus>

  suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult

  suspend fun resumeAll(budget: Duration): BackupRunResult

  suspend fun stop(by: StopBy)
}

/** Il piano (D6, D7, D11, §2.1, §2.11): candidati, riepilogo del primo backup, destinazioni. */
interface BackupPlanner {

  /**
   * Il riepilogo del primo backup con i tre numeri (§2.11) e la stima. Prima della fine della scansione dell'area privata e del
   * confronto delle copie simili dice "sto ancora confrontando" ([BackupSummary.comparing]).
   */
  suspend fun summary(): BackupSummary

  /** I prossimi [limit] candidati di [kind] (dal piu' recente; l'originale prima della sua versione). */
  suspend fun next(kind: BackupKind, limit: Int): List<BackupCandidate>

  /**
   * Come [next], ma per un giro avviato da [trigger]. Aggiunta compatibile di P4: di default [trigger] non cambia i candidati. Il piano
   * vero la specializza per D34: con [BackupTrigger.QUICK_NEW_PHOTOS] solo foto della fotocamera, pochi e recenti, mai video.
   * Il motore la chiama con il trigger del giro (`BackupEngine.run`).
   */
  suspend fun next(kind: BackupKind, limit: Int, trigger: BackupTrigger): List<BackupCandidate> = next(kind, limit)

  /**
   * La destinazione di [candidate] nella cartella del dispositivo [folder] (D6): `Backup/<cartella>/AAAA/MM/<nome>` per la fotocamera,
   * `Backup/<cartella>/<Nome della sorgente>/AAAA/MM/<nome>` per screenshot e cartelle aggiuntive (Q8), `Senza data/` per una data
   * assente o assurda (E-B17). Il nome e' quello del telefono validato con `SharePath` (E-N6). Pura: nessuna lettura del NAS (il
   * nome occupato lo scopre la pubblicazione, E-B3).
   */
  fun destination(candidate: BackupCandidate, folder: DeviceFolder): SharePath
}

/** Le impostazioni del backup (§2.1, D7, D13): del dispositivo, non personali (come preset e qualita'). */
interface BackupSettings {
  val sources: StateFlow<BackupSources>

  /** La cartella del dispositivo (D7), null finche' il primo backup non l'ha scelta. */
  val deviceFolder: StateFlow<DeviceFolder?>

  suspend fun setSources(sources: BackupSources)

  /** Sceglie la cartella del dispositivo una volta (E-B6: "Continua li'" o "Nuova cartella"); poi non cambia con il nome (E-B7). */
  suspend fun chooseFolder(folder: DeviceFolder)
}

/** Che cosa verifica [SafetyVerifier] (D14). */
sealed interface VerifyScope {
  /** Le copie private non ancora rilette (`PRIVATE_COPY_UNVERIFIED`). */
  data object Unverified : VerifyScope

  /** La fase `FULL_HASH` che il catalogo mette in coda (F4-D21, gruppi `PROBABLE_DUPLICATE`). */
  data object FullHash : VerifyScope

  /** La riverifica a rotazione (Q10): dalla verifica piu' vecchia, circa 1/365 dei byte protetti a notte (almeno 100 MB). */
  data object Rotation : VerifyScope

  /** 5B (D21): la verifica sul momento dei media di "Libera spazio". */
  data class OnTheSpot(val media: Set<MediaId>) : VerifyScope {
    override fun toString(): String = "OnTheSpot(${media.size})"
  }
}

/** L'esito di un giro di verifiche: soli numeri. */
data class VerifyReport(
  val checked: Int,
  val verified: Int,
  /** Copie che non tornano: escono da "al sicuro" (E-B8). */
  val mismatched: Int,
  val unreadable: Int,
  val bytesRead: Long,
  val stoppedBy: RunStop,
  /**
   * Aggiunta compatibile di P7: perche' la rete si e' fermata ([RunStop.NAS]) quando lo si sa (pieno, quota, password, sola lettura,
   * irraggiungibile), cosi' la pagina Backup e il riepilogo distinguono i casi. Null = nessuna fermata da segnalare.
   */
  val problem: BackupProblemCode? = null,
)

/** Le verifiche e gli hash completi (D14, P3): "al sicuro" nasce solo da una rilettura fatta qui (L28). */
interface SafetyVerifier {
  suspend fun verify(scope: VerifyScope, budget: Duration): VerifyReport
}

/** Su quali media lavora il guardiano (D9). */
sealed interface GuardScope {
  data object All : GuardScope

  data class Media(val ids: Set<MediaId>) : GuardScope {
    override fun toString(): String = "GuardScope.Media(${ids.size})"
  }
}

/** L'esito di un giro del guardiano: soli numeri. */
data class GuardReport(
  val created: Int,
  val linked: Int,
  val skipped: Int,
  val failed: Int,
  val bytesCopied: Long,
  val stoppedBy: RunStop,
  /** Aggiunta compatibile di P7: come [VerifyReport.problem]. */
  val problem: BackupProblemCode? = null,
)

/**
 * Il guardiano delle copie private (D9, P3): per un media **proprio** la cui sola copia sul NAS e' nell'area comune, crea la copia
 * privata con la copia lato server, la verifica e la pubblica in `Backup/Area comune/AAAA/MM/`. Crea, non cancella; 20 GB per giro.
 */
interface PrivateCopyGuardian {
  suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport
}
