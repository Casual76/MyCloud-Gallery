package dev.pampa.mycloudgallery.feature.diagnostics.recording

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.ActivityReason
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagMapping
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** I lavori che scrivono nell'anello: nomi fissi del codice, mai un dato (l'anello si esporta). */
enum class DiagJob {
  NAS_SCAN,
  INDEX_SYNC,
  PREPARATION,
  HQ_PREVIEWS,
  TRASH_PURGE,
  INDEX_MAINTENANCE,
  PERSONAL_SYNC,
  PHOTO_BACKUP,
  VIDEO_BACKUP,
  QUICK_PHOTOS,
  BACKUP_SAFETY,
  BACKUP_USER,
  TRUST_SYNC,

  /** Fase 5B (P14, D24): il caricamento dei file ricevuti da un'altra app ("Salva sul NAS"). */
  BACKUP_INCOMING,

  /** Fase 6 (P11): lo scaricamento dei modelli della ricerca e il calcolo AI. */
  MODEL_FETCH,
  AI_COMPUTE,

  /** Fase 7 (revisione di fine fase, DG-2): un giro della condivisione di un album (`ShareWorker`: avanza il diario, accoda, pubblica). */
  ALBUM_SHARE,

  /** Fase 7 (DG-2): la manutenzione notturna della Libreria (riconciliazione, META, hash, viaggi, ricalcolo degli smart, Utility). */
  LIBRARY_MAINTENANCE,
}

/** Com'e' finita una tappa di un lavoro (`StageOutcome` in parole fisse). */
enum class DiagJobResult { FINISHED, YIELDED, STOPPED, WAITING, NEEDS_CONFIRMATION, FAILED }

/** Perche' Android ha fermato un lavoro (`WorkInfo.STOP_REASON_*`, in parole fisse). */
enum class DiagStopCause {
  /** La quota dei job del bucket e' finita: e' il "limite di Android" delle regole (E-P14). */
  QUOTA,

  /** Il bucket dell'app e' sceso (`APP_STANDBY`) o l'utente ha limitato lo sfondo (`BACKGROUND_RESTRICTION`). */
  RESTRICTED,

  /** Il lavoro ha superato il tempo concesso. */
  TIMEOUT,

  /** Un vincolo e' venuto meno (rete, carica, batteria, spazio). */
  CONSTRAINT,

  /** L'utente l'ha fermato dal Task Manager. */
  USER,

  /** Sostituito da un lavoro piu' importante, stato del dispositivo, o altro. */
  SYSTEM,
}

/**
 * Chi scrive nell'anello della Diagnostica, "Ultimi esiti ed errori" (verifica funzionale, RE-4): fino ad oggi era legato ma nessuno ci
 * scriveva, quindi la sezione era sempre vuota e gli errori dei lavori finivano solo in `GalleryLog`, muto in release.
 *
 * **Solo codici e numeri.** Ogni evento e' un [DiagCode], una gravita' e valori tipizzati (`ExportValue`: numeri, si'/no, durate,
 * costanti di enum): per costruzione niente nomi di file, percorsi, account, share o indirizzi. Gli errori del NAS passano dalla stessa
 * tabella della Diagnostica ([DiagMapping.code]), quindi hanno gli stessi codici e gli stessi titoli.
 *
 * **Senza rumore.** Un lavoro che ha girato a vuoto non scrive niente; lo stesso esito identico dello stesso lavoro (o lo stesso errore)
 * si scrive di nuovo solo dopo [QUIET]: l'anello e' di 500 eventi e una notte di "in attesa" non deve cacciare l'unico errore vero.
 */
class DiagnosticsRecorder(
  private val log: DiagnosticsLog,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) {

  private val lastByKey = HashMap<String, Long>()
  private var nasFailing = false

  /** La fine di una tappa di un lavoro a tappe. */
  fun stage(job: DiagJob, outcome: StageOutcome) {
    val stats = outcome.stats
    val (result, reason) = when (outcome) {
      is StageOutcome.Finished -> DiagJobResult.FINISHED to null
      is StageOutcome.Yielded -> DiagJobResult.YIELDED to null
      is StageOutcome.Stopped -> DiagJobResult.STOPPED to ActivityReason.of(outcome.reason)
      is StageOutcome.Waiting -> DiagJobResult.WAITING to ActivityReason.of(outcome.reason)
      is StageOutcome.NeedsConfirmation -> DiagJobResult.NEEDS_CONFIRMATION to null
    }
    // Girato a vuoto (niente da fare): non e' un esito da ricordare.
    if (result == DiagJobResult.FINISHED && stats.batches == 0 && stats.bytes == 0L) return
    val values = LinkedHashMap<String, ExportValue>()
    values["job"] = ExportValue.Enum.of(job)
    values["result"] = ExportValue.Enum.of(result)
    reason?.let { values["reason"] = ExportValue.Enum.of(it) }
    values["batches"] = ExportValue.Num(stats.batches.toLong())
    values["bytes"] = ExportValue.Num(stats.bytes)
    values["elapsed"] = ExportValue.Dur.of(stats.elapsed)
    values["resumed"] = ExportValue.Flag(stats.resumed)
    record(DiagCodes.EVT_JOB_RESULT, DiagSeverity.INFO, values, key = "job.$job.$result.$reason", quiet = QUIET)
  }

  /** Un risultato senza i dettagli della tappa (il controllo del registro). */
  fun result(job: DiagJob, result: DiagJobResult, reason: Enum<*>? = null) {
    val values = LinkedHashMap<String, ExportValue>()
    values["job"] = ExportValue.Enum.of(job)
    values["result"] = ExportValue.Enum.of(result)
    reason?.let { values["reason"] = ExportValue.Enum.of(it) }
    record(DiagCodes.EVT_JOB_RESULT, DiagSeverity.INFO, values, key = "job.$job.$result.$reason", quiet = QUIET)
  }

  /** Un errore che nessuno prevedeva ha interrotto il lavoro (R1): si sa quale, non perche' (il perche' puo' avere dati). */
  fun jobFailed(job: DiagJob) {
    record(
      DiagCodes.EVT_JOB_FAILED,
      DiagSeverity.WARNING,
      mapOf("job" to ExportValue.Enum.of(job), "result" to ExportValue.Enum.of(DiagJobResult.FAILED)),
      key = "failed.$job",
      quiet = QUIET,
    )
  }

  /** Android ha fermato un lavoro (`stopReason`, che prima non si salvava): il motivo resta anche dopo la morte del processo. */
  fun jobStopped(job: DiagJob, cause: DiagStopCause) {
    record(
      DiagCodes.ACT_JOB_STOPPED,
      DiagSeverity.INFO,
      mapOf("job" to ExportValue.Enum.of(job), "cause" to ExportValue.Enum.of(cause)),
      key = "stopped.$job.$cause",
      quiet = STOP_QUIET,
    )
  }

  /** La sessione con il NAS non e' riuscita: lo stesso codice della Diagnostica (`NAS_TIMEOUT`, `NAS_AUTH_FAILED`...). */
  fun nasFailure(error: NasError) {
    val code = DiagMapping.code(error)
    nasFailing = true
    record(code, severityOf(code, DiagSeverity.WARNING), emptyMap(), key = "nas.${code.id}", quiet = NAS_QUIET)
  }

  /** L'interruttore di sicurezza si e' aperto (il NAS rifiuta la password): serve una password nuova, mai un tentativo da soli. */
  fun nasBlocked(reason: AuthBlockReason) {
    val code = DiagMapping.authCode(reason)
    nasFailing = true
    record(code, severityOf(code, DiagSeverity.ERROR), emptyMap(), key = "nas.${code.id}", quiet = NAS_QUIET)
  }

  /** La sessione e' tornata pronta dopo un guasto: solo questo cambio si scrive, non ogni connessione riuscita. */
  fun nasRecovered() {
    if (!nasFailing) return
    nasFailing = false
    record(DiagCodes.NAS_SESSION_OK, DiagSeverity.OK, emptyMap(), key = "nas.ok", quiet = Duration.ZERO)
  }

  /** Un problema del backup (`BackupProblemCode.name`, per esempio `NAS_FULL`): lo stesso codice della sezione "Backup". */
  fun backupProblem(problemName: String) {
    val code = DiagCode("BACKUP_$problemName")
    val known = code in DiagCatalog
    record(
      if (known) code else DiagCodes.BACKUP_UNEXPECTED,
      if (known) severityOf(code, DiagSeverity.WARNING) else DiagSeverity.WARNING,
      emptyMap(),
      key = "backup.$problemName",
      quiet = QUIET,
    )
  }

  /** Il controllo automatico della VPN si e' fermato da solo (RE-2): il motivo, non i dettagli della rete. */
  fun vpnSuspended(reason: VpnSuspendReason) {
    val code = when (reason) {
      VpnSuspendReason.LOCKDOWN_SUSPECTED -> DiagCodes.VPN_LOCKDOWN_SUSPECTED
      VpnSuspendReason.NOT_CONTROLLABLE -> DiagCodes.VPN_NOT_CONTROLLABLE
    }
    record(code, DiagSeverity.WARNING, emptyMap(), key = "vpn.${code.id}", quiet = Duration.ZERO)
  }

  /** L'avvio non ha potuto leggere lo stato salvato (`AppGraph.start`). */
  fun startFailed() {
    record(DiagCodes.EVT_START_FAILED, DiagSeverity.WARNING, emptyMap(), key = "start", quiet = QUIET)
  }

  /**
   * Fase 6 (P11): un codice dei motori, del calcolo, dei modelli o della ricerca (`AiDiag`, `ModelDiag`, `SearchDiag`, `AiIndexDiag`), con
   * la gravita' del catalogo. Solo il codice, mai testo, vettori o chiavi (D40); lo stesso codice al piu' una volta ogni dieci minuti.
   */
  fun code(code: DiagCode) {
    record(code, severityOf(code, DiagSeverity.WARNING), emptyMap(), key = "f6.${code.id}", quiet = STOP_QUIET)
  }

  private fun severityOf(code: DiagCode, fallback: DiagSeverity): DiagSeverity = DiagCatalog.entry(code)?.severity ?: fallback

  @Synchronized
  private fun record(code: DiagCode, severity: DiagSeverity, values: Map<String, ExportValue>, key: String, quiet: Duration) {
    val now = nowMillis()
    val last = lastByKey[key]
    if (last != null && quiet.isPositive() && now - last < quiet.inWholeMilliseconds) return
    lastByKey[key] = now
    log.record(DiagEvent(now, code, severity, values))
  }

  companion object {
    /** Lo stesso esito dello stesso lavoro non si riscrive prima di tanto. */
    val QUIET: Duration = 6.hours

    /** Lo stesso errore del NAS non si riscrive prima di tanto (ogni tentativo fallito rifarebbe lo stesso errore). */
    val NAS_QUIET: Duration = 10.minutes

    /** Un lavoro fermato da Android per lo stesso motivo: ogni volta e' un'informazione, ma non piu' di una ogni dieci minuti. */
    val STOP_QUIET: Duration = 10.minutes
  }
}
