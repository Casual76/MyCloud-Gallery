package dev.pampa.mycloudgallery.core.backup.noop

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupDiaryCounts
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.CopyToVerify
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesProgress
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.GuardCandidate
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.MediaSafety
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts
import dev.pampa.mycloudgallery.core.backup.contract.SafetyVerifier
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.contract.VerifyReport
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Fase 5A, i null-object di core-backup (release-safe, fuori da `testing`): cio' che `:app` lega finche' P7 non porta il backup vero, o
// senza NAS. **Falliscono chiusi**: nessuna scrittura, nessun "al sicuro", nessun elemento nel diario; dove non sanno, non promettono
// (il confronto prima del primo backup non risulta mai "finito", il cancello e' chiuso).

private val notConfigured: BackupStatus = BackupStatus.NotConfigured(setOf(BackupSetupStep.NAS))

/** Nessun backup: lo stato dice "non configurato", un giro non fa niente. */
object NoBackupEngine : BackupEngine {
  override val status: StateFlow<BackupStatus> = MutableStateFlow(notConfigured).asStateFlow()

  override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult = BackupRunResult.nothing(RunStop.GATE)

  override suspend fun resumeAll(budget: Duration): BackupRunResult = BackupRunResult.nothing(RunStop.GATE)

  override suspend fun stop(by: StopBy) = Unit
}

/** Nessun diario: nessun elemento si crea, niente da riprendere, nessun temporaneo citato. */
object NoBackupStore : BackupStore {
  override suspend fun create(items: List<NewBackupItem>): List<BackupItem> = emptyList()

  override suspend fun item(id: BackupItemId): BackupItem? = null

  override suspend fun itemsToResume(limit: Int): List<BackupItem> = emptyList()

  override suspend fun update(item: BackupItem) = error("Nessun diario del backup: niente da aggiornare.")

  override suspend fun openFor(versionId: VersionId): BackupItem? = null

  override suspend fun citedTemps(): Set<SharePath> = emptySet()

  override suspend fun counts(): BackupDiaryCounts = BackupDiaryCounts.EMPTY
}

/** Nessun piano: niente da caricare. */
object NoBackupPlanner : BackupPlanner {
  override suspend fun summary(): BackupSummary = BackupSummary.EMPTY

  override suspend fun next(kind: BackupKind, limit: Int): List<BackupCandidate> = emptyList()

  override fun destination(candidate: BackupCandidate, folder: DeviceFolder): SharePath =
    error("Nessun piano del backup: nessuna destinazione.")
}

/** Nessuna impostazione: nessuna sorgente, nessuna cartella del dispositivo; le scelte non si salvano. */
object NoBackupSettings : BackupSettings {
  override val sources: StateFlow<BackupSources> = MutableStateFlow(BackupSources.NONE).asStateFlow()

  override val deviceFolder: StateFlow<DeviceFolder?> = MutableStateFlow<DeviceFolder?>(null).asStateFlow()

  override suspend fun setSources(sources: BackupSources) = Unit

  override suspend fun chooseFolder(folder: DeviceFolder) = Unit
}

/** Nessuna verifica: nessuna copia diventa "al sicuro". */
object NoSafetyVerifier : SafetyVerifier {
  override suspend fun verify(scope: VerifyScope, budget: Duration): VerifyReport = VerifyReport(0, 0, 0, 0, 0, RunStop.GATE)
}

/** Nessun guardiano: nessuna copia privata creata. */
object NoPrivateCopyGuardian : PrivateCopyGuardian {
  override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport = GuardReport(0, 0, 0, 0, 0, RunStop.GATE)
}

/**
 * Nessun telefono legato: ogni riga "non piu' valida" ([PhoneLookup.Stale]) e l'elemento aspetta. Mai [PhoneLookup.Gone]: un
 * telefono che non si legge non e' un media perso prima del backup.
 */
object NoPhoneBytes : PhoneBytes {
  override suspend fun lookup(ref: PhoneRef): PhoneLookup = PhoneLookup.Stale

  override suspend fun open(ref: PhoneRef): PhoneOpen = PhoneOpen.Stale
}

/** Nessun catalogo: nessun candidato, nessuna copia attaccata, nessuna verifica. */
object NoBackupCatalog : BackupCatalog {
  override suspend fun candidates(query: CandidateQuery): List<BackupCandidate> = emptyList()

  override suspend fun safetyCounts(sources: BackupSources): SafetyCounts = SafetyCounts.ZERO

  override suspend fun phoneRefOf(mediaId: MediaId, versionId: VersionId): PhoneRef? = null

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord = HashRecord.UNKNOWN_VERSION

  override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int = 0

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) = Unit

  override suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<NasCopyRef> = emptyList()

  override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> = emptyList()

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean = false

  override suspend fun onlyCommonCopies(scope: GuardScope, limit: Int): List<GuardCandidate> = emptyList()

  override suspend fun mediaSafety(mediaId: MediaId): MediaSafety? = null
}

/** Il cancello chiuso: la famiglia non e' pronta, niente si scrive. */
object ClosedBackupGate : BackupGate {
  override fun stateOf(share: String): BackupGateState = BackupGateState.NOT_READY
}

/** Il confronto con cio' che c'e' sul NAS non si sa: il riepilogo del primo backup dice "sto ancora confrontando". */
object UnknownExistingCopies : ExistingCopiesProgress {
  override suspend fun current(): ExistingCopiesState = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)
}

/** L'orologio monotono senza ora del NAS (nessuna sessione): le durate si misurano, nessuna copia si segna "verificata adesso". */
object MonotonicOnlyBackupClock : BackupClock {
  override fun monotonicMs(): Long = System.nanoTime() / 1_000_000

  override fun nasNowMs(): Long? = null
}
