package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.rules.CapKind
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import org.junit.Test

/**
 * Pacchetto X: lo stato ridotto dell'analisi, un caso per riga. Il punto fermo: una pausa dice il motivo **vero** delle regole (caldo,
 * carica, batteria, risparmio, NAS), mai "attesa delle regole"; i numeri sono solo quelli noti.
 */
class AnalysisStatusReducersTest {

  private fun facts(
    indexer: Boolean = true,
    status: AiStatus = AiStatus.Idle(null),
    remaining: Int? = 10,
    coverage: SearchCoverage = SearchCoverage.Partial(covered = 40, total = 100, reasons = emptyMap()),
    decision: RuleDecision? = null,
  ) = AnalysisStatusReducers.Facts(indexer, status, remaining, coverage, decision)

  private fun wait(reason: WaitReason) = RuleDecision.Wait(reason)

  @Test
  fun chiNonAnalizzaDiceLePronte() {
    val out = AnalysisStatusReducers.reduce(facts(indexer = false, status = AiStatus.Off))
    assertThat(out).isEqualTo(LibraryAnalysisStatus.NotHere(ready = 40, total = 100))
  }

  @Test
  fun inCorsoPortaRestoETempo() {
    val out = AnalysisStatusReducers.reduce(facts(status = AiStatus.Running(done = 3, total = 40, etaMinutes = 25, backend = AiBackend.CPU)))
    assertThat(out).isEqualTo(LibraryAnalysisStatus.Running(remaining = 10, etaMinutes = 25, ready = 40, total = 100))
  }

  @Test
  fun modelloCheMancaEProblema() {
    assertThat(AnalysisStatusReducers.reduce(facts(status = AiStatus.ModelMissing))).isEqualTo(LibraryAnalysisStatus.ModelMissing)
    assertThat(AnalysisStatusReducers.reduce(facts(status = AiStatus.Problem(AiDiag.AI_STORAGE_LOW))))
      .isEqualTo(LibraryAnalysisStatus.Problem("AI_STORAGE_LOW"))
  }

  @Test
  fun nienteDaFareETuttoPronto() {
    assertThat(AnalysisStatusReducers.reduce(facts(remaining = 0))).isEqualTo(LibraryAnalysisStatus.AllReady)
    assertThat(AnalysisStatusReducers.reduce(facts(remaining = null, coverage = SearchCoverage.Complete)))
      .isEqualTo(LibraryAnalysisStatus.AllReady)
  }

  @Test
  fun laPausaDiceIlMotivoVeroDelleRegole() {
    val hot = AnalysisStatusReducers.reduce(facts(decision = wait(WaitReason.Thermal(ThermalStatus.SEVERE))))
    assertThat(hot).isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.THERMAL, 10, 40, 100))
    val power = AnalysisStatusReducers.reduce(facts(decision = wait(WaitReason.NeedsPower)))
    assertThat((power as LibraryAnalysisStatus.Paused).reason).isEqualTo(LibraryAnalysisWait.CHARGING)
    val battery = AnalysisStatusReducers.reduce(facts(decision = wait(WaitReason.BatteryLow(12, 20))))
    assertThat((battery as LibraryAnalysisStatus.Paused).reason).isEqualTo(LibraryAnalysisWait.BATTERY)
    val saver = AnalysisStatusReducers.reduce(facts(decision = wait(WaitReason.PowerSaver)))
    assertThat((saver as LibraryAnalysisStatus.Paused).reason).isEqualTo(LibraryAnalysisWait.POWER_SAVER)
    val cap = AnalysisStatusReducers.reduce(facts(decision = wait(WaitReason.CapReached(CapKind.MOBILE, 10, 10))))
    assertThat((cap as LibraryAnalysisStatus.Paused).reason).isEqualTo(LibraryAnalysisWait.DATA_CAP)
    val nas = AnalysisStatusReducers.reduce(facts(decision = wait(WaitReason.NasUnreachable)))
    assertThat((nas as LibraryAnalysisStatus.Paused).reason).isEqualTo(LibraryAnalysisWait.NAS)
  }

  @Test
  fun attesaGenericaDelMotorePrendeIlMotivoDelleRegole() {
    val out = AnalysisStatusReducers.reduce(
      facts(status = AiStatus.Waiting(AiWaitReason.RULES), decision = wait(WaitReason.BatteryLow(12, 20))),
    )
    assertThat(out).isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.BATTERY, 10, 40, 100))
    // Il caldo detto dal motore non ha bisogno del verdetto.
    val hot = AnalysisStatusReducers.reduce(facts(status = AiStatus.Waiting(AiWaitReason.HOT)))
    assertThat(hot).isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.THERMAL, 10, 40, 100))
    // Fra una tappa e l'altra: in coda, non in pausa.
    val next = AnalysisStatusReducers.reduce(facts(status = AiStatus.Waiting(AiWaitReason.NEXT_STAGE)))
    assertThat(next).isEqualTo(LibraryAnalysisStatus.Queued(10, 40, 100))
  }

  @Test
  fun inCodaSpentaESenzaNas() {
    val run = RuleDecision.Run(RunScope.FULL, network = NetworkClass.HOME_WIFI)
    assertThat(AnalysisStatusReducers.reduce(facts(decision = run))).isEqualTo(LibraryAnalysisStatus.Queued(10, 40, 100))
    // Senza verdetto non si inventa un motivo: in coda.
    assertThat(AnalysisStatusReducers.reduce(facts())).isEqualTo(LibraryAnalysisStatus.Queued(10, 40, 100))
    assertThat(AnalysisStatusReducers.reduce(facts(decision = wait(WaitReason.ActivityDisabled))))
      .isEqualTo(LibraryAnalysisStatus.Disabled)
    val buffer = AnalysisStatusReducers.reduce(facts(status = AiStatus.BufferOnlyDone(4)))
    assertThat(buffer).isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.NAS, 10, 40, 100))
  }
}
