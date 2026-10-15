package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.contract.DoneSummary
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.nas.NasOpsGates
import dev.pampa.mycloudgallery.notify.NasOpsProgress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I pezzi puri delle operazioni fuori dalla schermata (Fase 4B, P16): quando un'operazione e' "lunga" (UIDT), come gira il lavoro che
 * riprende dal diario, che cosa dice la notifica (solo numeri) e il cancello delle operazioni (D10, D31, D32).
 */
class NasOpsJobsTest {

  private fun plan(kind: OpKind, items: Int, flow: ItemFlow): OpPlan {
    val planned = (0 until items).map { i ->
      PlannedItem(
        index = i,
        flow = flow,
        srcShare = "test-comune",
        srcPath = SharePath.of("Foto", "IMG_$i.jpg"),
        dstShare = if (flow == ItemFlow.MOVE_ACROSS_SHARES) "test" else "test-comune",
        dstPath = SharePath.of("Altro", "IMG_$i.jpg"),
        isFolder = false,
        size = 1_000,
        stamp = NasStamp(1_000, 1L),
      )
    }
    return OpPlan(kind, planned, emptyList(), PlanSummary(kind, items, 0, items * 1_000L, null, null), emptySet())
  }

  @Test
  fun unSpostamentoTraShareEUnaMossaGrandeSonoLunghe_unaRinominaNo() {
    assertThat(NasOpsJobs.isLong(plan(OpKind.MOVE, 1, ItemFlow.MOVE_ACROSS_SHARES))).isTrue()
    assertThat(NasOpsJobs.isLong(plan(OpKind.MOVE, 3, ItemFlow.RENAME))).isFalse()
    assertThat(NasOpsJobs.isLong(plan(OpKind.MOVE, NasOpsJobs.LONG_OPERATION_ITEMS, ItemFlow.RENAME))).isTrue()
    assertThat(NasOpsJobs.isLong(plan(OpKind.TRASH, 2, ItemFlow.TRASH))).isFalse()
    assertThat(NasOpsJobs.isLong(plan(OpKind.TRASH, 2_000, ItemFlow.TRASH))).isTrue()
    assertThat(NasOpsJobs.isLong(plan(OpKind.RENAME, 1, ItemFlow.RENAME))).isFalse()
  }

  @Test
  fun gliIdDelLavoroStannoFuoriDaQuelliDiWorkManager() {
    assertThat(JobSchedulerNasOpsJobs.JOB_USER in JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS).isFalse()
    assertThat(JobSchedulerNasOpsJobs.JOB_RESUME in JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS).isFalse()
    assertThat(JobSchedulerNasOpsJobs.JOB_USER).isNotEqualTo(JobSchedulerNasOpsJobs.JOB_RESUME)
  }

  private class Script(vararg stops: RunStop?) {
    val queue = ArrayDeque(stops.toList())
    var calls = 0
    val budgets = ArrayList<Duration>()

    suspend fun resume(budget: Duration): RunResult? {
      calls++
      budgets += budget
      val stop = queue.removeFirstOrNull() ?: RunStop.COMPLETE
      return stop?.let { RunResult(1, 1, 0, 0, it) }
    }
  }

  @Test
  fun ilLavoroDellUtenteRiprendeATappeFinoAllaFine() = runBlocking<Unit> {
    val script = Script(RunStop.BUDGET, RunStop.BUDGET, RunStop.COMPLETE)
    val end = NasOpsJobRun(script::resume, { null }, userInitiated = true).run()
    assertThat(end).isEqualTo(NasOpsJobEnd.Done)
    assertThat(script.calls).isEqualTo(3)
    assertThat(script.budgets.toSet()).containsExactly(NasOpsJobRun.SLICE)
  }

  @Test
  fun unLavoroNormaleFaUnaTappaSolaESiFaRiprogrammare() = runBlocking<Unit> {
    val script = Script(RunStop.BUDGET)
    assertThat(NasOpsJobRun(script::resume, { null }, userInitiated = false).run()).isEqualTo(NasOpsJobEnd.Reschedule)
    assertThat(script.calls).isEqualTo(1)
  }

  @Test
  fun nasSparitoOScrittureFermeSiRiprovaConIlRitardoDelSistema() = runBlocking<Unit> {
    assertThat(NasOpsJobRun(Script(RunStop.NAS_UNREACHABLE)::resume, { null }, userInitiated = true).run()).isEqualTo(NasOpsJobEnd.Reschedule)
    assertThat(NasOpsJobRun(Script(RunStop.BLOCKED)::resume, { null }, userInitiated = true).run()).isEqualTo(NasOpsJobEnd.Reschedule)
  }

  @Test
  fun fileApertiDalPcSiRiprovaQuandoScadeLAttesa_almenoUnMinuto() = runBlocking<Unit> {
    val later = NasOpsJobRun(Script(RunStop.WAITING_LOCKED)::resume, { 5.minutes.inWholeMilliseconds }, userInitiated = true).run()
    assertThat(later).isEqualTo(NasOpsJobEnd.Later(5.minutes.inWholeMilliseconds))
    val soon = NasOpsJobRun(Script(RunStop.WAITING_LOCKED)::resume, { 0L }, userInitiated = true).run()
    assertThat(soon).isEqualTo(NasOpsJobEnd.Later(60_000L))
  }

  @Test
  fun senzaNasOGalleriaFermaIlLavoroFinisce() = runBlocking<Unit> {
    assertThat(NasOpsJobRun({ null }, { null }, userInitiated = true).run()).isEqualTo(NasOpsJobEnd.Done)
  }

  @Test
  fun laNotificaContaGliElementiDelleOperazioniAttive() {
    val a = OpId(TrustIds.random())
    val b = OpId(TrustIds.random())
    val progress = NasOpsProgress.of(
      listOf(
        NasOperationState.Running(a, OpKind.MOVE, done = 120, total = 2_000, bytesDone = 0, bytesTotal = 0),
        NasOperationState.WaitingLocked(b, OpKind.TRASH, lockedCount = 2, done = 3, total = 5),
        NasOperationState.Done(OpId(TrustIds.random()), OpKind.RENAME, DoneSummary(1, 0, 0, 10)),
      ),
    )
    assertThat(progress).isEqualTo(NasOpsProgress(done = 123, total = 2_005, locked = 2, paused = false, active = 2))
    val paused = NasOpsProgress.of(listOf(NasOperationState.Paused(a, OpKind.MOVE, PauseReason.NAS_UNREACHABLE, 1, 4)))
    assertThat(paused.paused).isTrue()
    assertThat(NasOpsProgress.of(listOf(NasOperationState.Cancelled(a, OpKind.MOVE, 1, 4)))).isEqualTo(NasOpsProgress.NONE)
  }

  @Test
  fun ilCancelloDelleOperazioni() {
    assertThat(NasOpsGates.statusOf(emergency = true, frozen = true, clockJump = true)).isEqualTo(GateStatus.WRITES_STOPPED)
    assertThat(NasOpsGates.statusOf(emergency = false, frozen = true, clockJump = true)).isEqualTo(GateStatus.FROZEN)
    val jump = NasOpsGates.statusOf(emergency = false, frozen = false, clockJump = true)
    assertThat(jump).isEqualTo(GateStatus.CLOCK_JUMP)
    // Un salto dell'ora ferma le pulizie, non le richieste dell'utente (D32).
    assertThat(jump.blocksUserOps).isFalse()
    assertThat(jump.blocksScheduled).isTrue()
    assertThat(NasOpsGates.statusOf(emergency = false, frozen = false, clockJump = false)).isEqualTo(GateStatus.OPEN)
  }
}
