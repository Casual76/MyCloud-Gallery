package dev.pampa.mycloudgallery.core.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesPermit
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesScope
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.faces.noop.ClosedFacesArchive
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesCatalog
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesComputeRun
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesEraseOps
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesEraser
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesLeases
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesPublisher
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesRemote
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesRequirements
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesRunControl
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesSwitch
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesSync
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesWriteGate
import dev.pampa.mycloudgallery.core.faces.noop.NoPeople
import dev.pampa.mycloudgallery.core.faces.noop.NoPeopleIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** I null-object di core-faces falliscono CHIUSI (Fase 8, P0): niente si accende, si calcola, si pubblica, si cancella o si mostra. */
class NullObjectsTest {
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 1 })

  @Test
  fun personeSpentaENonAccendibile() = runTest {
    assertThat(NoFacesSwitch.state.value).isEqualTo(FacesState.Off)
    assertThat(NoFacesSwitch.activate(FacesActivationConsent(1, 0))).isEqualTo(ActivateOutcome.Unavailable)
    assertThat(NoFacesSwitch.reconcile(null)).isEqualTo(ReconcileOutcome.Done)
    assertThat(NoFacesComputeRun.run(FacesScope.PhoneOnly, 1.minutes).stop).isEqualTo(FacesStop.NOT_ACTIVE)
    assertThat(NoFacesRunControl.permit()).isEqualTo(FacesPermit.Stop(FacesStop.NOT_ACTIVE))
    assertThat(NoFacesRequirements.privateArea()).isEqualTo(PrivateAreaState.Missing)
    assertThat(NoFacesRequirements.localOnly("test")).isTrue()
    assertThat(NoFacesCatalog.months(FacesArea.PRIVATE)).isEmpty()
  }

  @Test
  fun nessunaPersonaENessunNome() = runTest {
    val id = PersonId.random()
    assertThat(NoPeople.list.value.named).isEmpty()
    assertThat(NoPeople.person(id).first()).isNull()
    assertThat(NoPeople.rename(id, "Anna")).isEqualTo(EditOutcome.NotActive)
    assertThat(NoPeople.split(id, emptyList())).isEqualTo(SplitOutcome.Failed(EditOutcome.NotActive))
    assertThat(NoPeople.assign(dummyLocator(), PersonTarget.Existing(id))).isEqualTo(EditOutcome.NotActive)
    assertThat(NoPeopleIndex.available.value).isFalse()
    assertThat(NoPeopleIndex.dictionary()).isEmpty()
    assertThat(NoPeopleIndex.contentWith(setOf(id), PeopleMode.ANY, MatchLevel.LIKELY)).isEmpty()
    assertThat(NoPeopleIndex.resolve(id)).isNull()
  }

  @Test
  fun niente_siPubblicaOSiCancellaSulNas() = runTest {
    val denied = RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    val remote = NoFacesRemote()
    assertThat(remote.generations()).isEqualTo(denied)
    assertThat(remote.publishHead(g, 1, byteArrayOf(1))).isEqualTo(denied)
    assertThat(NoFacesPublisher.flush(FlushReason.DEBOUNCE)).isEqualTo(FacesPublishOutcome.Blocked(FacesWriteBlock.NO_NAS))
    assertThat(NoFacesWriteGate.blockFor("test")).isEqualTo(FacesWriteBlock.NO_NAS)
    assertThat(NoFacesSync.sync(1.minutes).stop).isEqualTo(FacesSyncStop.NOT_ACTIVE)
    val work = FacesResource.WorkUnit(AreaKind.PRIVATE, "2025-01", 0)
    assertThat(NoFacesLeases.acquire(g, work)).isEqualTo(FacesLease.Blocked(FacesWriteBlock.NO_NAS))
    assertThat(NoFacesEraseOps.generations()).isNull()
    assertThat(NoFacesEraseOps.verify(g)).isNull()
    val confirmation = FacesEraseConfirmation(0, 0)
    assertThat(NoFacesEraser.erase(confirmation, PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.NothingToErase)
  }

  @Test
  fun lArchivioChiusoNonScrive() = runTest {
    val archive = ClosedFacesArchive(g)
    assertThat(archive.groupFaces()).isEmpty()
    assertThat(archive.people()).isEmpty()
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { archive.markUnsupported(dummyLocator().contentKey, 1) } }
  }

  private fun dummyLocator() = dev.pampa.mycloudgallery.core.model.FaceLocator(
    dev.pampa.mycloudgallery.core.model.ContentKey("a".repeat(64)),
    0,
    dev.pampa.mycloudgallery.core.model.FaceBox(0, 0, 10, 10),
  )
}
