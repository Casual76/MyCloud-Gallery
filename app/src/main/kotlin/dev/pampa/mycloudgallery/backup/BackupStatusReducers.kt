package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason as RulesWait

/**
 * Le riduzioni dello stato del backup (Fase 5A, P7; docs/fase5-progetto.md §1.5): funzioni pure, provate su JVM. Cosa dire lo decide il
 * contratto; qui solo traduzioni di tipi, mai un nome o un percorso.
 */
object BackupStatusReducers {

  /**
   * **`BackupStatus` ridotto a `LibraryBackupStatus` per il piede** (terza voce, D16): "Tutto al sicuro", "312 in coda", "Primo backup: 1.240
   * di 6.658", il problema piu' grave, "Backup in prova". Un backup non configurato non dice niente (la pagina lo spiega).
   *
   * Fase I (12/11): il primo backup "si prepara" solo finche' la scansione dell'area privata non e' finita (lo stesso cancello del piano); il
   * confronto delle copie simili non ferma niente e il piede non lo dice. Se la scansione e' ferma per una regola, il piede dice quale.
   *
   * @param incomingProblems IN-7: quanti file ricevuti da un'altra app non si sono caricati e aspettano un "Riprova" o uno "Scarta". Il piede lo dice
   *   (porta alla pagina Backup, dove sta "Ricevuti in coda") **quando non ha niente di piu' urgente**: un problema del backup, "Backup in prova", il
   *   confronto o un giro in corso restano al loro posto; "Tutto al sicuro", "N in coda", il backup fermo e il piede muto lasciano il posto ai file non
   *   salvati (un problema pesa piu' di una pausa, e porta alla stessa pagina).
   */
  fun footer(status: BackupStatus, incomingProblems: Int = 0): LibraryBackupStatus {
    val base = footerOf(status)
    if (incomingProblems <= 0) return base
    return when (base) {
      LibraryBackupStatus.Idle, is LibraryBackupStatus.AllSafe, is LibraryBackupStatus.Queued, is LibraryBackupStatus.Paused ->
        LibraryBackupStatus.Problem(INCOMING_FAILED_CODE, incomingProblems)
      else -> base
    }
  }

  /** Il codice stabile con cui il piede dice "N file ricevuti non salvati" (nessun `BackupProblemCode`: non e' un problema del backup delle foto). */
  const val INCOMING_FAILED_CODE: String = "incoming_failed"

  private fun footerOf(status: BackupStatus): LibraryBackupStatus = when (status) {
    is BackupStatus.NotConfigured -> LibraryBackupStatus.Idle
    BackupStatus.TestOnly -> LibraryBackupStatus.TestOnly
    is BackupStatus.FirstBackupReady -> when {
      // Giro 2 (BF2-2): appena aperta l'app, finche' il riepilogo non c'e', il piede tace come le Impostazioni (prima: «Cerco… · Inizia fra poco» con la
      // clessidra, una promessa, mentre la pagina diceva altro).
      !status.summary.counted -> LibraryBackupStatus.Idle
      !status.summary.canStart -> {
        // BF-7: la ricerca, con i file del NAS gia' guardati se gira; ferma per una regola, quale (un motivo che il backup non nomina: «altro»).
        val existing = status.summary.existing
        LibraryBackupStatus.Comparing(
          seen = existing.scanSeen,
          total = existing.scanTotal,
          wait = existing.heldBy?.let { if (it == WaitReason.NEXT_RUN) LibraryBackupWait.OTHER else waitOf(it) },
        )
      }
      else -> {
        // BF-6: il primo backup aspetta il tocco dell'utente, non il prossimo giro: «da avviare», con quanto c'e' da caricare (come il foglio).
        val items = status.summary.toUpload.items.takeIf { it > 0 } ?: status.summary.linked.items
        if (items > 0) LibraryBackupStatus.Queued(items, LibraryBackupWait.FIRST_BACKUP) else LibraryBackupStatus.Idle
      }
    }
    is BackupStatus.UpToDate -> LibraryBackupStatus.AllSafe(status.atNasMillis)
    is BackupStatus.Waiting -> LibraryBackupStatus.Queued(status.items, waitOf(status.reason))
    is BackupStatus.Running -> when (status.trigger) {
      dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger.FIRST_BACKUP ->
        LibraryBackupStatus.FirstBackup(status.done, status.total, status.etaMillis, starting = status.starting)
      else -> LibraryBackupStatus.Running(status.done, status.total, status.etaMillis, starting = status.starting)
    }
    // OA-10 (review della beta): un backup fermo **si dice**. Prima diventava "niente da dire" e la nuvola, guardando solo il NAS, mostrava la spunta di
    // "tutto salvato" con le foto ancora da salvare. La pausa dell'utente ("Ferma", fino a "Riprendi") e il lavoro fermato da Android (riprende da solo)
    // hanno la loro voce; dopo l'azzeramento dell'app non c'e' piu' un backup di cui parlare.
    is BackupStatus.Stopped -> when (status.by) {
      StopBy.USER -> LibraryBackupStatus.Paused(byUser = true)
      StopBy.SYSTEM -> LibraryBackupStatus.Paused(byUser = false)
      StopBy.RESET -> LibraryBackupStatus.Idle
    }
    is BackupStatus.Problem -> LibraryBackupStatus.Problem(status.code.code, status.count)
  }

  fun waitOf(reason: WaitReason): LibraryBackupWait = when (reason) {
    WaitReason.CHARGING -> LibraryBackupWait.CHARGING
    WaitReason.WIFI -> LibraryBackupWait.WIFI
    WaitReason.HOME_WIFI -> LibraryBackupWait.HOME_WIFI
    WaitReason.NIGHT_WINDOW -> LibraryBackupWait.NIGHT_WINDOW
    WaitReason.BATTERY -> LibraryBackupWait.BATTERY
    WaitReason.NAS -> LibraryBackupWait.NAS
    WaitReason.NEXT_RUN -> LibraryBackupWait.NEXT_RUN
    WaitReason.NAS_BUSY -> LibraryBackupWait.NAS_BUSY
    WaitReason.POWER_SAVER -> LibraryBackupWait.POWER_SAVER
    // Fase I (12/11): il caldo e i dati del mese hanno la loro frase (prima: "Attesa delle regole").
    WaitReason.THERMAL -> LibraryBackupWait.THERMAL
    WaitReason.DATA_CAP -> LibraryBackupWait.DATA_CAP
    WaitReason.COMPARING -> LibraryBackupWait.OTHER
    WaitReason.NOT_ON_PHONE -> LibraryBackupWait.NOT_ON_PHONE
  }

  // --- T-1 (12/11): che cosa contano l'anello, le righe e la coda --------------------------------------------------------------------------

  /**
   * I media di una sorgente (o di tutte) per l'anello e le righe: **quelli che si possono ancora salvare o sono al sicuro**. Prima contava anche i persi
   * (`lost`: nessuna posizione da nessuna parte, le foto cancellate dal telefono prima del backup): tenevano l'anello sotto il 100% e la pagina lontana da
   * «Al sicuro» fino alla pulizia (60 giorni), senza niente che l'utente potesse fare. Si dicono a parte, fra i problemi.
   */
  fun mediaOf(counts: SafetyCounts): Int = counts.safe + counts.unverified + counts.onlyCommonCopy + counts.noNasCopy

  /**
   * I media in coda: senza copia sul NAS o con la copia privata da verificare, meno quelli che un problema ferma ([BackupProblemCode.marksMedia]) e meno
   * [held] (le versioni che aspettano il PC). I problemi dei media persi ([BackupProblemCode.lostMedia]) **non** si sottraggono: quei media non sono in coda
   * (prima toglievano dalla coda media veri: una foto persa nascondeva una foto da caricare).
   */
  fun queuedOf(counts: SafetyCounts, held: Int = 0): Int {
    val stopping = counts.problems.filterKeys { it.marksMedia && !it.lostMedia }.values.sum()
    return (counts.noNasCopy + counts.unverified - stopping - held).coerceAtLeast(0)
  }

  /**
   * Il problema dei media che la testata dice al posto di «Al sicuro», o null. Le foto **cancellate prima del backup** non ci sono: quando tutto cio' che
   * esiste e' al sicuro la pagina dice «Al sicuro» (e il 100%), e quelle si dicono una volta nella sezione dei problemi («Da controllare»).
   */
  fun heroProblem(problems: Map<BackupProblemCode, Int>): Pair<BackupProblemCode, Int>? =
    problems.filterKeys { it.marksMedia && it != BackupProblemCode.LOST_BEFORE_BACKUP }.entries.minByOrNull { it.key.ordinal }?.toPair()

  /**
   * Perche' aspetta una coda che non sta caricando: il motivo delle regole; se le regole lasciano partire ([WaitReason.NEXT_RUN]) e il NAS tiene occupato
   * un file ([busy], D-13), «il NAS e' occupato»; se l'ultimo giro non ha trovato niente da caricare per questa coda ([unserved], T-1), «non li trovo sul
   * telefono»: mai «al prossimo giro» per un giro che non caricherebbe niente.
   */
  fun queueWait(rules: WaitReason, busy: Boolean, unserved: Boolean): WaitReason = when {
    rules != WaitReason.NEXT_RUN -> rules
    busy -> WaitReason.NAS_BUSY
    unserved -> WaitReason.NOT_ON_PHONE
    else -> WaitReason.NEXT_RUN
  }

  /**
   * Fase I (BS2-2): il motivo che ferma **adesso** il lavoro chiesto dall'utente ([decision] = il verdetto delle regole di «Fai ora», gia' confermato), o null se
   * il lavoro puo' partire. Con un motivo, la pagina lo dice al posto di «sto partendo».
   */
  fun userStartBlock(decision: RuleDecision): WaitReason? = when (decision) {
    is RuleDecision.Wait, is RuleDecision.Stop -> waitReasonOf(decision)
    is RuleDecision.Run, is RuleDecision.NeedsConfirmation -> null
  }

  /**
   * Perche' le regole non lasciano partire il backup adesso, nelle parole del backup. Un verdetto `Run` (o una conferma da chiedere) non
   * e' un'attesa: il prossimo giro parte da solo.
   */
  fun waitReasonOf(decision: RuleDecision): WaitReason {
    val reason = when (decision) {
      is RuleDecision.Wait -> decision.reason
      is RuleDecision.Stop -> decision.reason
      is RuleDecision.Run, is RuleDecision.NeedsConfirmation -> return WaitReason.NEXT_RUN
    }
    return when (reason) {
      RulesWait.NeedsPower -> WaitReason.CHARGING
      is RulesWait.OutsideWindow -> WaitReason.NIGHT_WINDOW
      is RulesWait.BatteryLow -> WaitReason.BATTERY
      // FAB-4: il risparmio energetico ha la sua attesa (prima: "Aspetta che la batteria sia piu' carica", anche con la batteria piena).
      RulesWait.PowerSaver -> WaitReason.POWER_SAVER
      is RulesWait.Thermal -> WaitReason.THERMAL
      is RulesWait.CapReached -> WaitReason.DATA_CAP
      RulesWait.HeavyNeedsHomeWifi -> WaitReason.HOME_WIFI
      is RulesWait.NetworkNotAllowed -> if (reason.current == NetworkClass.MOBILE) WaitReason.WIFI else WaitReason.HOME_WIFI
      RulesWait.Offline, RulesWait.NetworkNotTrusted, RulesWait.PoorNetwork -> WaitReason.WIFI
      RulesWait.NasUnreachable -> WaitReason.NAS
      is RulesWait.Frozen, RulesWait.ProfileInactive, RulesWait.ActivityDisabled, RulesWait.Roaming, RulesWait.AppNotVisible,
      is RulesWait.RoleDisabled, is RulesWait.AndroidLimit, is RulesWait.ConfirmationRequired,
      -> WaitReason.NEXT_RUN
    }
  }
}
