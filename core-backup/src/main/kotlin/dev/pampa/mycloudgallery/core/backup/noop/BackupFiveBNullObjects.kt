package dev.pampa.mycloudgallery.core.backup.noop

import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRoute
import dev.pampa.mycloudgallery.core.backup.contract.EditBegin
import dev.pampa.mycloudgallery.core.backup.contract.EditOutcome
import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditSessions
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.EditUnavailable
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.IncomingUploads
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskId
import dev.pampa.mycloudgallery.core.backup.contract.PendingTasks
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhonePlanHash
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Fase 5B (P0-B), i null-object dei servizi della seconda meta' (release-safe, fuori da `testing`): cio' che `:app` lega finche' P14 non porta
// le versioni, le correzioni, "Libera spazio", le attivita' in sospeso, gli arrivi e le sessioni di modifica veri. **Falliscono chiusi**: nessuna
// azione sul telefono, nessun elemento nel diario, nessuna copia liberata; un piano vuoto, un'attivita' non disponibile, mai un "fatto" finto.
// Un null-object non controlla la conferma (non fa niente che la richieda), ma ogni risposta dice che non e' disponibile.

/** Nessuna versione conosciuta: "Ripristina originale" non e' disponibile. */
object NoVersionKeeper : VersionKeeper {
  override suspend fun plan(mediaId: MediaId): NasVersionPlan =
    NasVersionPlan(mediaId, emptyList(), VersionArea.OWN_PRIVATE, RestoreAvailability.NOT_AVAILABLE)

  override suspend fun restoreOriginal(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): RestoreOutcome =
    RestoreOutcome.Unavailable(RestoreAvailability.NOT_AVAILABLE)
}

/** Nessuna correzione scritta: il piano dice "non disponibile" e `apply` non tocca niente. */
object NoCorrections : Corrections {
  override suspend fun plan(mediaId: MediaId, change: CorrectionRequest): CorrectionPlan = CorrectionPlan(
    mediaId = mediaId,
    request = change,
    baseVersion = null,
    route = CorrectionRoute.NAS_ONLY,
    readiness = CorrectionReadiness.NOT_AVAILABLE,
    phoneRows = emptyList(),
  )

  override suspend fun apply(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome =
    CorrectionOutcome.Unavailable(CorrectionReadiness.NOT_AVAILABLE)
}

/**
 * Nessun "Libera spazio": il piano e' **vuoto** (con l'avviso forte, che c'e' in ogni piano), il giro e' bloccato "non disponibile" e non ci sono
 * lotti. Niente si libera.
 */
object NoFreeSpace : FreeSpace {
  override suspend fun plan(filter: FreeSpaceFilter, permanent: Boolean): FreeSpacePlan = FreeSpacePlan(filter, permanent, emptyList(), emptyMap())

  override suspend fun verify(plan: FreeSpacePlan, confirmation: PhoneConfirmation, budget: Duration): FreeSpaceRun = blocked(plan.hash, plan.permanent)

  override suspend fun resume(run: FreeSpaceRun, budget: Duration): FreeSpaceRun = run

  override suspend fun nextBatch(run: FreeSpaceRun): TrashBatch? = null

  override suspend fun recordOutcome(batch: TrashBatch, outcome: TrashOutcome): FreeSpaceRun =
    blocked(PhonePlanHash.builder("none").build(), batch.mode == TrashMode.DELETE_NOW)

  private fun blocked(hash: PhonePlanHash, permanent: Boolean): FreeSpaceRun = FreeSpaceRun(
    id = FreeSpaceRunId(1),
    planHash = hash,
    state = FreeSpaceRunState.BLOCKED,
    permanent = permanent,
    guardedDone = 0, guardedTotal = 0, verifiedDone = 0, verifiedTotal = 0, verifiedBytes = 0,
    readyItems = 0, readyBytes = 0, batchesTotal = 0, batchesDone = 0, freedItems = 0, freedBytes = 0,
    blocked = FreeSpaceBlock.NOT_AVAILABLE,
  )
}

/** Nessuna attivita' in sospeso: la lista "Da decidere" e' vuota e le azioni non fanno niente. */
object NoPendingTasks : PendingTasks {
  override val tasks: StateFlow<List<PendingTask>> = MutableStateFlow<List<PendingTask>>(emptyList()).asStateFlow()

  override suspend fun dismiss(id: PendingTaskId) = Unit

  override suspend fun complete(id: PendingTaskId) = Unit
}

/** Nessun arrivo: ogni flusso e' rifiutato "non disponibile" **senza aprirlo**, e salvare non fa niente. */
object NoIncomingUploads : IncomingUploads {
  override suspend fun stage(streams: List<IncomingStream>): IncomingStage =
    IncomingStage(IncomingStageId("none"), emptyList(), if (streams.isEmpty()) emptyMap() else mapOf(IncomingReject.NOT_AVAILABLE to streams.size))

  override suspend fun save(stage: IncomingStage, dest: NasDestination, confirmation: PhoneConfirmation): IncomingSaveOutcome =
    IncomingSaveOutcome.Unavailable

  override suspend fun discard(stage: IncomingStageId) = Unit
}

/** Nessuna sessione di modifica: "Modifica con…" non e' disponibile. */
object NoEditSessions : EditSessions {
  override suspend fun begin(mediaId: MediaId, target: EditTarget): EditBegin = EditBegin.Unavailable(EditUnavailable.NOT_AVAILABLE)

  override suspend fun settle(session: EditSession): EditOutcome = EditOutcome.Unreadable
}

/** Nessuna riscrittura del telefono: "nessun permesso", la riga non e' toccata e il file non e' aperto. */
object NoPhoneRewriter : PhoneRewriter {
  override suspend fun replace(ref: PhoneRef, mime: String?, file: File, expectedSha256: Sha256, currentSha256: Sha256): PhoneRewrite =
    PhoneRewrite.NotGranted
}
