package dev.pampa.mycloudgallery.core.faces.noop

import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.EraseReport
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStatus
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.ErasePreview
import dev.pampa.mycloudgallery.core.faces.contract.FaceChip
import dev.pampa.mycloudgallery.core.faces.contract.FaceGrouping
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeRun
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraser
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunResult
import dev.pampa.mycloudgallery.core.faces.contract.FacesScope
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatusSource
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesSync
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncReport
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.OthersView
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PersonName
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.PersonView
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// I null-object delle persone (Fase 8, P0): falliscono CHIUSI. Persone resta spenta, niente si calcola, si raggruppa, si pubblica o si
// cancella, nessuna persona compare, nessun nome entra nella ricerca. Sono i default finche' :app (P13) non lega i pezzi veri.

/** Persone spenta e non accendibile. La riconciliazione non ha niente da cancellare: risponde fatto. */
object NoFacesSwitch : FacesSwitch {
  override val state: StateFlow<FacesState> = MutableStateFlow(FacesState.Off)
  override val fresh: StateFlow<Boolean> = MutableStateFlow(false)
  override val syncPending: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome = ActivateOutcome.Unavailable

  override suspend fun acceptNotice(textVersion: Int) = Unit

  override suspend fun excludeThisDevice() = Unit

  override suspend fun includeThisDevice() = Unit

  override suspend fun reconcile(setting: FacesSetting?): ReconcileOutcome = ReconcileOutcome.Done
}

object NoFacesComputeRun : FacesComputeRun {
  override suspend fun run(scope: FacesScope, budget: Duration): FacesRunResult = FacesRunResult.stopped(FacesStop.NOT_ACTIVE)
}

object NoFacesStatusSource : FacesStatusSource {
  override val status: StateFlow<FacesStatus> = MutableStateFlow(FacesStatus.Off)
}

object NoFaceGrouping : FaceGrouping {
  override val holder: StateFlow<HolderState> = MutableStateFlow(HolderState.Unknown)

  override suspend fun incremental(budget: Duration): GroupingReport = GroupingReport.EMPTY

  override suspend fun nightly(budget: Duration): GroupingReport = GroupingReport.EMPTY

  override suspend fun recluster(budget: Duration): GroupingReport = GroupingReport.EMPTY
}

/** Nessuna persona: elenchi vuoti, ogni azione `NotActive`. */
object NoPeople : People {
  override val list: StateFlow<PeopleList> = MutableStateFlow(PeopleList.EMPTY)

  override fun person(id: PersonId): Flow<PersonView?> = flowOf(null)

  override fun others(): Flow<OthersView> = flowOf(OthersView(emptyList()))

  override suspend fun facesOf(media: MediaId): List<FaceChip> = emptyList()

  override suspend fun rename(id: PersonId, name: String?): EditOutcome = EditOutcome.NotActive

  override suspend fun merge(ids: List<PersonId>, keepNameOf: PersonId?): EditOutcome = EditOutcome.NotActive

  override suspend fun split(id: PersonId, faces: List<FaceLocator>): SplitOutcome = SplitOutcome.Failed(EditOutcome.NotActive)

  override suspend fun assign(face: FaceLocator, to: PersonTarget): EditOutcome = EditOutcome.NotActive

  override suspend fun reject(face: FaceLocator, from: PersonId): EditOutcome = EditOutcome.NotActive

  override suspend fun ignore(face: FaceLocator): EditOutcome = EditOutcome.NotActive

  override suspend fun setHidden(id: PersonId, hidden: Boolean): EditOutcome = EditOutcome.NotActive

  override suspend fun forget(id: PersonId): EditOutcome = EditOutcome.NotActive

  override suspend fun setCover(id: PersonId, face: FaceLocator?): EditOutcome = EditOutcome.NotActive

  override suspend fun proposals(): List<MergeProposal> = emptyList()

  override suspend fun answer(proposal: MergeProposal, same: Boolean): EditOutcome = EditOutcome.NotActive

  override suspend fun knows(id: PersonId): Boolean = false
}

/** Nessun indice: il dizionario e' vuoto, nessuna chiave, nessun alias (il nome resta testo nella ricerca, E-F37). */
object NoPeopleIndex : PeopleIndex {
  override val available: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun dictionary(): List<PersonName> = emptyList()

  override suspend fun contentWith(persons: Set<PersonId>, mode: PeopleMode, level: MatchLevel): Map<ContentKey, MatchLevel> = emptyMap()

  override fun resolve(id: PersonId): PersonId? = null
}

object NoFacesSync : FacesSync {
  override suspend fun sync(budget: Duration): FacesSyncReport =
    FacesSyncReport(0, 0, emptyMap(), emptyMap(), 0, 0, null, FacesSyncStop.NOT_ACTIVE)
}

/** Nessun NAS per i volti: niente si pubblica (e niente si accoda). */
object NoFacesPublisher : FacesPublisher {
  override suspend fun stage(change: FacesChange) = Unit

  override suspend fun flush(reason: FlushReason): FacesPublishOutcome = FacesPublishOutcome.Blocked(FacesWriteBlock.NO_NAS)

  override val pending: StateFlow<Int> = MutableStateFlow(0)
}

/** Il cancello chiuso: nessuna scrittura dei volti sul NAS. */
object NoFacesWriteGate : FacesWriteGate {
  override suspend fun blockFor(share: String): FacesWriteBlock = FacesWriteBlock.NO_NAS
}

/** Niente da cancellare (Persone non e' mai stata legata qui). */
object NoFacesEraser : FacesEraser {
  override val status: StateFlow<EraseStatus?> = MutableStateFlow(null)

  override suspend fun preview(): ErasePreview = ErasePreview(0, 0, false, emptyList(), localOnly = true, digest = 0)

  override suspend fun erase(confirmation: FacesEraseConfirmation, albums: PeopleAlbumsChoice): EraseStart = EraseStart.NothingToErase

  override suspend fun resume(budget: Duration): EraseReport = EraseReport(EraseStep.DONE, 0, null, done = true)
}
