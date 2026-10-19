package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageScope
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * L'esito di una tappa del backup: quello della tappa ([stage]) e l'ultimo giro del motore ([last], null se la tappa non e' partita).
 *
 * @property busy BK-6: la tappa non e' partita perche' il turno era occupato da un altro lavoro del backup (le verifiche, il guardiano, un altro
 *   giro) e non si e' liberato in tempo. **Non e' "niente da fare"**: il lavoro di chi aspetta (un tocco dell'utente) va ripreso, non chiuso.
 */
class BackupStageOutcome(val stage: StageOutcome, val last: BackupRunResult?, val busy: Boolean = false) {
  override fun toString(): String = "BackupStageOutcome(${stage::class.java.simpleName}, ${last?.stoppedBy?.code}${if (busy) ", occupato" else ""})"
}

/**
 * Il motore dentro i **lavori a tappe** (D11, F4-D22, `StagedRun` di core-rules): `:app` (P7) costruisce la tappa con le regole
 * dell'attivita' (`PHOTO_BACKUP`, `VIDEO_BACKUP` o `UserInitiated`, `writesToNas = true`) e il registro del traffico; qui i giri del
 * motore diventano lotti brevi ([slice]). Fra un passo e l'altro (e fra un blocco da 1 MiB e l'altro) il motore chiede le regole
 * (`StageScope.proceed`); il traffico del caricamento e della rilettura si registra **subito** con la rete usata, **prima** di ogni punto
 * di ripresa (§2.2 "Traffico"); un giro fermato dal tempo lascia "altro da fare" e la tappa dopo riprende dal diario.
 */
object UploadStages {

  /**
   * Le regole e il registro della tappa [scope] come li vede il motore. Con [yieldTo] vero (BK-6: un tocco dell'utente aspetta il turno) il
   * motore si ferma al passo dopo, come a tempo scaduto: il punto di ripresa e' nel diario, nessun file resta a meta'.
   */
  fun control(scope: StageScope<*>, yieldTo: () -> Boolean = { false }): UploadControl = object : UploadControl {
    override suspend fun proceed(): Boolean = !yieldTo() && scope.proceed()

    override suspend fun traffic(bytes: Long) = scope.record(bytes)
  }

  /**
   * Una tappa di backup: giri di [kind] (null = solo la ripresa del diario, dopo un riavvio) chiesti da [trigger], a lotti di [slice].
   * Un giro fermato da "Ferma", dal cancello, dal NAS o dal telefono chiude la tappa (lo stato del motore dice il perche').
   *
   * @param yieldTo BK-6: vero = qualcuno (l'utente, con "Inizia ora") aspetta il turno; la tappa finisce al passo dopo senza ricominciare altri
   *   lotti. Il lavoro rimasto lo riprendono il lavoro dell'utente e quelli dopo, dal diario.
   */
  suspend fun stage(
    staged: StagedRun<Unit>,
    engine: StagedBackupEngine,
    kind: BackupKind?,
    trigger: BackupTrigger,
    slice: Duration = DEFAULT_SLICE,
    yieldTo: () -> Boolean = { false },
  ): BackupStageOutcome {
    var last: BackupRunResult? = null
    val outcome = staged.run({ scope, _ ->
      val control = control(scope, yieldTo)
      val result = if (kind == null) engine.resumeAll(slice, control) else engine.run(kind, slice, trigger, control)
      last = result
      BatchResult(hasMore = result.stoppedBy == RunStop.BUDGET && !yieldTo())
    })
    return BackupStageOutcome(outcome, last)
  }

  /** Un lotto: abbastanza lungo per lavorare, abbastanza breve per fermarsi presto se le regole cambiano (come i lavori del NAS). */
  val DEFAULT_SLICE: Duration = 60.seconds
}
