package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.LargestFile
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceDetails
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasFolders
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasOperations
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasSpace
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasTrash
import dev.pampa.mycloudgallery.core.nasops.noop.NoOwnFilesCleaning
import dev.pampa.mycloudgallery.core.nasops.noop.WritesStoppedGate
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpClocks
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.testing.ScriptedNasOperations
import dev.pampa.mycloudgallery.core.nasops.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** I fake e i null-object che P14, P15 e P16 useranno: si comportano come i contratti dicono. */
class FakesTest {

  private val id = PlanFixtures::id

  // --- Null-object: falliscono chiusi ------------------------------------------------------------------------------------------------

  @Test
  fun iNullObjectNonPianificanoNonAvviaNoNEliminanoNiente() = runTest {
    val rejected = NoNasOperations.plan(OpRequest.Trash(listOf(NasItem("test", SharePath.parse("a.jpg"), false, 1, null))))
    assertThat((rejected as PlanOutcome.Rejected).reason).isEqualTo(PlanRejection.NAS_UNREACHABLE)
    val plan = PlanFixtures.trash()
    assertThat(NoNasOperations.start(plan, TestConfirmations.forPlan(plan, 1))).isEqualTo(StartOutcome.WritesBlocked)
    assertThat(NoNasOperations.operations.value).isEmpty()
    assertThat(NoNasOperations.resumeAll(1.minutes).stoppedBy).isEqualTo(RunStop.BLOCKED)

    assertThat(NoNasTrash.items.value).isEqualTo(NasTrashState.Offline(emptyList()))
    assertThat(NoNasTrash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(NoNasTrash.purgeExpired(1.minutes).stoppedBy).isEqualTo(PurgeStop.BLOCKED)
    assertThat(NoNasSpace.load("test")).isEqualTo(NasSpaceState.Unavailable(OpErrorCode.UNREACHABLE, emptyList()))
    assertThat(NoNasFolders.observe("test", null).first()).isEqualTo(NasFolderState.Offline(null, null))
    assertThat(NoOwnFilesCleaning.clean(listOf("test"), 1.minutes).removed).isEqualTo(0)
    assertThat(WritesStoppedGate.statusOf("test").blocksUserOps).isTrue()
  }

  // --- Operazioni a copione per la UI ----------------------------------------------------------------------------------------------------

  @Test
  fun leOperazioniACopioneVerificanoLaConfermaEMettonoInElenco() = runTest {
    val clocks = FakeOpClocks()
    val scripted = ScriptedNasOperations(clocks)
    val request = OpRequest.Trash(listOf(NasItem("test", SharePath.parse("Foto/a.jpg"), false, 10, null)))
    val plan = (scripted.plan(request) as PlanOutcome.Ready).plan
    assertThat(scripted.requests).containsExactly(request)

    val other = PlanFixtures.trash(2)
    assertThat(scripted.start(plan, TestConfirmations.forPlan(other, clocks.monotonic)))
      .isEqualTo(StartOutcome.ConfirmationRejected(ConfirmationCheck.WrongPlan))
    assertThat(scripted.started).isEmpty()

    val started = scripted.start(plan, TestConfirmations.forPlan(plan, clocks.monotonic)) as StartOutcome.Started
    assertThat(scripted.started).containsExactly(plan)
    assertThat(scripted.operations.value.single()).isInstanceOf(NasOperationState.Planned::class.java)

    scripted.emit(NasOperationState.Running(started.id, OpKind.TRASH, 0, 1, 0, 10))
    scripted.emit(NasOperationState.Paused(started.id, OpKind.TRASH, PauseReason.NAS_UNREACHABLE, 0, 1))
    assertThat(scripted.operations.value.single()).isInstanceOf(NasOperationState.Paused::class.java)
    assertThat(scripted.operations.value.single().active).isTrue()
    scripted.emit(NasOperationState.Cancelled(started.id, OpKind.TRASH, 0, 1))
    assertThat(scripted.operations.value.single().active).isFalse()

    scripted.cancel(started.id)
    assertThat(scripted.cancelled).containsExactly(started.id)
  }

  // --- Cartelle, spazio, kit -------------------------------------------------------------------------------------------------------------

  @Test
  fun leCartelleNasMostranoElencoSenzaFileDellAppELaSolaLettura() = runTest {
    val kit = NasOpsTestKit()
    kit.putFile("test", "Foto/b.jpg", byteArrayOf(1))
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(2, 2))
    kit.putDir("test", "Foto/Mare")
    kit.putFile("test", ".mycloudgallery/devices/x", byteArrayOf(1))

    val root = kit.folders.observe("test", null).first { it !is NasFolderState.Loading } as NasFolderState.Listing
    assertThat(root.entries.map { it.name }).containsExactly("Foto")
    assertThat(root.readOnly).isFalse()

    kit.readOnlyShares += "test"
    val listing = kit.folders.observe("test", SharePath.parse("Foto")).first { it !is NasFolderState.Loading } as NasFolderState.Listing
    // Cartelle prima, poi i file in ordine; in sola lettura (E-N9).
    assertThat(listing.entries.map { it.name }).containsExactly("Mare", "a.jpg", "b.jpg").inOrder()
    assertThat(listing.readOnly).isTrue()
    val item = listing.entries.first { it.name == "a.jpg" }.toItem("test")
    assertThat(item.stamp!!.size).isEqualTo(2)
    assertThat(listing.entries.first { it.name == "Mare" }.toItem("test").stamp).isNull()
  }

  @Test
  fun leCartelleNasOfflineMostranoLUltimoElencoNoto() = runTest {
    val kit = NasOpsTestKit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1))
    kit.folders.observe("test", SharePath.parse("Foto")).first { it !is NasFolderState.Loading }
    kit.nas.failWith = dev.pampa.mycloudgallery.core.nas.contract.NasError.Timeout
    val offline = kit.folders.observe("test", SharePath.parse("Foto")).first { it !is NasFolderState.Loading } as NasFolderState.Offline
    assertThat(offline.cached!!.map { it.name }).containsExactly("a.jpg")
    kit.nas.failWith = dev.pampa.mycloudgallery.core.nas.contract.NasError.PermissionDenied
    assertThat(kit.folders.observe("test", SharePath.parse("Foto")).first { it !is NasFolderState.Loading }).isEqualTo(NasFolderState.Denied)
  }

  @Test
  fun lSpazioNasHaLaQuotaEIlCestinoDelWd() = runTest {
    val kit = NasOpsTestKit(shares = listOf("test", "NAS"))
    val space = dev.pampa.mycloudgallery.core.nasops.testing.InMemoryNasSpace(
      fileOps = kit.fileOps,
      clocks = kit.clocks,
      largest = { share -> (1..60).map { LargestFile(share, SharePath.of("f$it.mkv"), it * 1_000L, null) } },
      trashBytes = { 123L },
      indexBytes = { 456L },
    )
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 8_000, freeBytes = 5_000, callerFreeBytes = 1_000) }
    val ready = space.load("NAS") as NasSpaceState.Ready
    assertThat(ready.details.quotaBytes).isEqualTo(1_000)
    assertThat(ready.details.wdTrashMayHoldSpace).isTrue()
    assertThat(ready.details.largest).hasSize(NasSpaceDetails.MAX_LARGEST)
    assertThat(ready.details.largest.first().size).isEqualTo(60_000)
    assertThat(ready.details.trashBytes).isEqualTo(123)
    assertThat(ready.details.versionsBytes).isEqualTo(0)
    assertThat((space.load("test") as NasSpaceState.Ready).details.wdTrashMayHoldSpace).isFalse()

    kit.nas.failWith = dev.pampa.mycloudgallery.core.nas.contract.NasError.Timeout
    val unavailable = space.load("test") as NasSpaceState.Unavailable
    assertThat(unavailable.largest).isNotEmpty()
  }

  @Test
  fun ilKitRiavviaSenzaPerdereIlDiarioNeIlNas() = runTest {
    val kit = NasOpsTestKit()
    kit.putFile("test", "x.jpg", byteArrayOf(1))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "x.jpg"))))
    val before = kit.store.allTrash().single()
    kit.restart()
    assertThat(kit.store.allTrash().single()).isEqualTo(before)
    assertThat(kit.exists("test", NasLayout.trashItemFile(kit.deviceId, before.trashId).value)).isTrue()
    assertThat(kit.operations).isNotNull()
    assertThat(id(1)).isNotEqualTo(id(2))
  }
}
