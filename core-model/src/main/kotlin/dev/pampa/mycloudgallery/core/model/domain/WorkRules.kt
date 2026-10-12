package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkState
import dev.pampa.mycloudgallery.core.model.WorkStatus

/** Le transizioni degli stati di lavoro (piano 1.3 "stati per fase di lavoro", 4.3, 6.2). */
object WorkRules {

  /** Dopo tanti fallimenti la fase si arrende (E-M10: segnalata, mai bloccante). */
  const val MAX_ATTEMPTS: Int = 5

  /**
   * Le fasi che un media nuovo deve attraversare. [WorkStage.FACES] non c'e': i volti sono un dato
   * biometrico personale e si calcolano solo dopo l'attivazione esplicita (proposta 13, Fase 8).
   */
  @Suppress("UNUSED_PARAMETER") // Il tipo servira' quando una fase non si applichera' a tutti i tipi.
  fun initialStages(kind: MediaKind): List<WorkStage> =
    listOf(WorkStage.META, WorkStage.THUMB, WorkStage.ANALYSIS_COPY, WorkStage.EMBED, WorkStage.OCR)

  fun initialStates(kind: MediaKind, versionId: VersionId, nowMillis: Long): List<WorkState> =
    initialStages(kind).map { pending(it, versionId, nowMillis) }

  fun pending(stage: WorkStage, versionId: VersionId?, nowMillis: Long): WorkState =
    WorkState(stage, WorkStatus.PENDING, versionId, attempts = 0, lastError = null, updatedAtMillis = nowMillis)

  fun started(state: WorkState, nowMillis: Long): WorkState =
    state.copy(status = WorkStatus.IN_PROGRESS, updatedAtMillis = nowMillis)

  fun succeeded(state: WorkState, versionId: VersionId, nowMillis: Long): WorkState =
    state.copy(status = WorkStatus.DONE, versionId = versionId, lastError = null, updatedAtMillis = nowMillis)

  fun failed(state: WorkState, error: WorkErrorCode, nowMillis: Long): WorkState {
    val attempts = state.attempts + 1
    return state.copy(
      status = if (attempts >= MAX_ATTEMPTS) WorkStatus.GAVE_UP else WorkStatus.FAILED,
      attempts = attempts,
      lastError = error,
      updatedAtMillis = nowMillis,
    )
  }

  fun unsupported(state: WorkState, nowMillis: Long): WorkState =
    state.copy(status = WorkStatus.UNSUPPORTED, updatedAtMillis = nowMillis)

  /**
   * La versione corrente e' cambiata: le fasi in [stages] tornano da fare sulla nuova versione, con i
   * tentativi azzerati (anche un "non supportato" o un "arreso": i byte nuovi possono andare bene).
   * Le altre restano come sono. I vettori vecchi non si toccano qui: restano finche' non arriva il nuovo.
   */
  fun invalidate(states: List<WorkState>, stages: Set<WorkStage>, versionId: VersionId, nowMillis: Long): List<WorkState> =
    states.map { if (it.stage in stages) pending(it.stage, versionId, nowMillis) else it }

  /**
   * Gli stati di due media che si uniscono: per ogni fase vince un lavoro gia' fatto sulla versione
   * corrente del media unito, altrimenti quello del sopravvissuto, altrimenti quello del perdente.
   * Le versioni negli stati del perdente vanno gia' tradotte con la mappa dell'unione.
   */
  fun merge(survivor: List<WorkState>, loser: List<WorkState>, currentVersionId: VersionId): List<WorkState> {
    val stages = (survivor + loser).map { it.stage }.distinct()
    return stages.map { stage ->
      val mine = survivor.firstOrNull { it.stage == stage }
      val theirs = loser.firstOrNull { it.stage == stage }
      listOfNotNull(mine, theirs).firstOrNull { it.status == WorkStatus.DONE && it.versionId == currentVersionId }
        ?: mine
        ?: theirs!!
    }
  }
}
