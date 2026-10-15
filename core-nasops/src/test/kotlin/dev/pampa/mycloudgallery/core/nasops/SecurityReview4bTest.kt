package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.session.ClockJumpGuard
import dev.pampa.mycloudgallery.core.nas.session.InMemoryClockJumpStore
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 4B (docs/review/4b-fase4b.md). Ogni test descrive il comportamento **sicuro** atteso e oggi
 * **fallisce** sull'ultima asserzione: quelle prima provano che lo scenario si costruisce davvero, con l'esecutore e il cestino di
 * produzione (`KitEngine.PRODUCTION`: `NasOpsEngine`, `NasTrashService`).
 */
class SecurityReview4bTest {

  private val day = 24L * 60 * 60 * 1000

  private fun kit() = NasOpsTestKit(engine = KitEngine.PRODUCTION)

  /**
   * R1. L'ora del NAS torna indietro di un anno (orologio azzerato, NTP che non lavora). D32 sospende le funzioni a scadenza ma non le
   * operazioni dell'utente: la voce del cestino prende l'ora sbagliata. Il giorno dopo l'ora si corregge, 24 ore di ora stabile tolgono
   * la sospensione (`ClockJumpGuard` vero, cancello composto come `NasGraph.opsGate`): la foto, nel cestino da due giorni, si elimina
   * per sempre al primo giro.
   */
  @Test
  fun r1_unFileCestinatoMentreLOraDelNasEraSbagliataNonSiEliminaQuandoLOraTornaGiusta() = runTest {
    val kit = kit()
    val t0 = kit.clocks.nasNow!!
    val guard = ClockJumpGuard(InMemoryClockJumpStore())
    fun anchor(nasMs: Long, elapsedMs: Long) {
      guard.onAnchor(nasMs, ClockJumpGuard.Reference(elapsedMs = elapsedMs, bootId = 7L, wallMs = elapsedMs))
      kit.clocks.nasNow = nasMs
      // Come NasOpsGates.statusOf: il salto dell'ora ferma le pulizie (blocksScheduled), non le richieste dell'utente.
      kit.gate.default = if (guard.blocked) GateStatus.CLOCK_JUMP else GateStatus.OPEN
    }
    anchor(t0, 0)
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    val item = kit.itemOf("test", "Foto/a.jpg")

    anchor(t0 - 365 * day, 60_000)
    assertThat(guard.blocked).isTrue()
    assertThat(kit.run(OpRequest.Trash(listOf(item)))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.store.allTrash().single().trashedAtNas).isLessThan(t0 - 300 * day)

    anchor(t0 + day, day)
    anchor(t0 + 2 * day + 1, 2 * day + 1)
    assertThat(guard.blocked).isFalse()
    val trashed = NasLayout.trashItemFile(kit.deviceId, kit.store.allTrash().single().trashId).value
    assertThat(kit.exists("test", trashed)).isTrue()

    val report = kit.trash.purgeExpired(1.minutes)
    assertWithMessage("una foto nel cestino da due giorni veri e' stata eliminata per sempre").that(report.purged).isEqualTo(0)
  }

  /**
   * R4. Il tetto della pulizia automatica (200 file e 20 GiB "per giro", formato §9.3) non ferma niente: quando il giro si ferma per il
   * tetto la pulizia risponde `BUDGET`, e `NasStages.purgeTrash` lo traduce in `hasMore = true`, quindi `StagedRun` chiama subito un altro
   * lotto nella stessa tappa (e poi la continuazione). Qui lo stesso ciclo di `NasStages` su 250 voci scadute.
   */
  @Test
  fun r4_unaTappaDellaPuliziaNonSuperaIlTettoDelGiro() = runTest {
    val kit = kit()
    repeat(250) { kit.putFile("test", "F/$it.jpg", byteArrayOf(it.toByte(), 1)) }
    val items = (0 until 250).map { kit.itemOf("test", "F/$it.jpg") }
    assertThat(kit.run(OpRequest.Trash(items))).isInstanceOf(StartOutcome.Started::class.java)
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)

    var purged = 0
    var rounds = 0
    do {
      val report = kit.trash.purgeExpired(1.minutes)
      purged += report.purged
      rounds++
      // NasWork.kt, NasStages.purgeTrash: BatchResult(hasMore = report.stoppedBy == PurgeStop.BUDGET).
    } while (report.stoppedBy == PurgeStop.BUDGET && rounds < 10)
    assertThat(rounds).isAtLeast(2)

    assertWithMessage("file eliminati per sempre in una sola tappa del lavoro giornaliero")
      .that(purged).isAtMost(TrustLimits.MAX_PURGE_ITEMS_PER_ROUND)
  }

  /**
   * R5. L'ora del cestino e' quella scritta **prima** della rinomina (la voce del diario e il record vengono per primi). Se la rinomina
   * non riesce (NAS sparito: l'operazione va in pausa) e riprende dopo un mese fuori casa, il file entra nel cestino adesso ma la sua
   * voce risulta gia' scaduta: il giro dopo lo elimina per sempre, senza i 30 giorni.
   */
  @Test
  fun r5_unFileEntratoNelCestinoDopoUnaPausaLungaHaComunqueTrentaGiorni() = runTest {
    val kit = kit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    val item = kit.itemOf("test", "Foto/a.jpg")
    kit.nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.TRASH_MOVE) NasError.Timeout else null }
    assertThat(kit.run(OpRequest.Trash(listOf(item)))).isInstanceOf(StartOutcome.Started::class.java)
    kit.nas.fault = null
    assertThat(kit.exists("test", "Foto/a.jpg")).isTrue()
    val entry = kit.store.allTrash().single()
    assertThat(entry.state).isEqualTo(TrashEntryState.TRASHED)

    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + day)
    kit.operations.resumeAll(5.minutes)
    assertThat(kit.exists("test", "Foto/a.jpg")).isFalse()
    assertThat(kit.exists("test", NasLayout.trashItemFile(kit.deviceId, entry.trashId).value)).isTrue()

    val report = kit.trash.purgeExpired(1.minutes)
    assertWithMessage("un file entrato nel cestino un attimo fa e' stato eliminato per sempre").that(report.purged).isEqualTo(0)
  }

  /**
   * R6. "Elimina subito" chiede due conferme (Q8), ma la macchina a tocchi accetta il secondo tocco 40 ms dopo il primo: un doppio tocco
   * sullo stesso pulsante (che cambia solo la scritta, da "Continua" a "Elimina subito") fa la doppia conferma.
   */
  @Test
  fun r6_unDoppioToccoNonValeComeDoppiaConfermaDiEliminaSubito() = runTest {
    val kit = kit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val trashId = kit.store.allTrash().single().trashId
    val plan = (kit.operations.plan(OpRequest.PurgeNow(listOf(OwnTrashRef(trashId)))) as PlanOutcome.Ready).plan
    assertThat(plan.requiredConfirmations).isEqualTo(2)

    val sheet = ConfirmationSheet(plan)
    val first = sheet.tap(nowMonotonicMs = 5_000)
    val second = sheet.tap(nowMonotonicMs = 5_040)
    assertThat(first).isNull()

    assertWithMessage("un doppio tocco di 40 ms ha dato la conferma definitiva").that(second).isNull()
  }
}
