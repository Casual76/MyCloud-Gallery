package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
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
   * @param similarPending quanti candidati aspettano il confronto delle copie simili (§2.11 punto 6).
   * @param incomingProblems IN-7: quanti file ricevuti da un'altra app non si sono caricati e aspettano un "Riprova" o uno "Scarta". Il piede lo dice
   *   (porta alla pagina Backup, dove sta "Ricevuti in coda") **quando non ha niente di piu' urgente**: un problema del backup, "Backup in prova", il
   *   confronto o un giro in corso restano al loro posto; "Tutto al sicuro", "N in coda" e il piede muto lasciano il posto ai file non salvati.
   */
  fun footer(status: BackupStatus, similarPending: Int, incomingProblems: Int = 0): LibraryBackupStatus {
    val base = footerOf(status, similarPending)
    if (incomingProblems <= 0) return base
    return when (base) {
      LibraryBackupStatus.Idle, is LibraryBackupStatus.AllSafe, is LibraryBackupStatus.Queued -> LibraryBackupStatus.Problem(INCOMING_FAILED_CODE, incomingProblems)
      else -> base
    }
  }

  /** Il codice stabile con cui il piede dice "N file ricevuti non salvati" (nessun `BackupProblemCode`: non e' un problema del backup delle foto). */
  const val INCOMING_FAILED_CODE: String = "incoming_failed"

  private fun footerOf(status: BackupStatus, similarPending: Int): LibraryBackupStatus = when (status) {
    is BackupStatus.NotConfigured -> LibraryBackupStatus.Idle
    BackupStatus.TestOnly -> LibraryBackupStatus.TestOnly
    is BackupStatus.FirstBackupReady ->
      if (status.summary.comparing) {
        LibraryBackupStatus.Comparing(similarPending.takeIf { status.summary.existing.privateAreaScanned && it > 0 })
      } else {
        // Il primo backup aspetta il tocco dell'utente: il piede lo conta fra le cose in coda e porta alla pagina.
        val items = status.summary.toUpload.items + status.summary.linked.items
        if (items > 0) LibraryBackupStatus.Queued(items, LibraryBackupWait.NEXT_RUN) else LibraryBackupStatus.Idle
      }
    is BackupStatus.UpToDate -> LibraryBackupStatus.AllSafe(status.atNasMillis)
    is BackupStatus.Waiting -> LibraryBackupStatus.Queued(status.items, waitOf(status.reason))
    is BackupStatus.Running -> when (status.trigger) {
      dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger.FIRST_BACKUP -> LibraryBackupStatus.FirstBackup(status.done, status.total, status.etaMillis)
      else -> LibraryBackupStatus.Running(status.done, status.total, status.etaMillis)
    }
    // Fermato dall'utente o da Android: il piede non sbandiera niente, la pagina ha "Riprendi".
    is BackupStatus.Stopped -> LibraryBackupStatus.Idle
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
    WaitReason.THERMAL, WaitReason.DATA_CAP, WaitReason.COMPARING -> LibraryBackupWait.OTHER
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
      is RulesWait.BatteryLow, RulesWait.PowerSaver -> WaitReason.BATTERY
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
