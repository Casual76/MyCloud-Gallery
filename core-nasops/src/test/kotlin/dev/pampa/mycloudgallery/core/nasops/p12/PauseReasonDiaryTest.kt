package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Prova F16 sul NAS vero: le operazioni ferme dicevano "in pausa" e nel diario non c'era nessun motivo (`motivo=nessuno`): il motivo
 * viveva solo nella memoria del processo, e dopo una morte la schermata e la Diagnostica dicevano "condizioni" a caso. Ora ogni pausa
 * scrive il suo motivo nel diario e lo rilegge dopo un riavvio, con l'esecutore vero e con quello di riferimento.
 */
class PauseReasonDiaryTest {

  private val a = byteArrayOf(1, 2, 3, 4)

  private suspend fun NasOpsTestKit.only() = store.ops(OpState.entries.toSet()).single()

  private fun NasOpsTestKit.shown(): NasOperationState = operations.operations.value.single()

  private suspend fun pausesAreWritten(engine: KitEngine) {
    val kit = NasOpsTestKit(engine = engine)
    kit.putFile("test", "a.jpg", a)
    // 1. Un file aperto dal PC: in attesa, e il diario lo dice (LOCKED), con l'elemento che dice quando si riprova.
    kit.lock("test", "a.jpg")
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "a.jpg"))))
    assertThat(kit.only().state).isEqualTo(OpState.PAUSED)
    assertWithMessage("$engine: il motivo dell'attesa nel diario").that(kit.only().errorCode).isEqualTo(OpErrorCode.LOCKED)
    kit.restart()
    kit.operations.resumeAll(Duration.ZERO)
    assertThat(kit.shown()).isInstanceOf(NasOperationState.WaitingLocked::class.java)

    // 2. Share congelata: in pausa per il congelamento, scritto nel diario; dopo un riavvio la schermata lo dice ancora.
    kit.unlock("test", "a.jpg")
    kit.gate.byShare["test"] = GateStatus.FROZEN
    kit.advance(2.minutes.inWholeMilliseconds)
    kit.operations.resumeAll(1.minutes)
    assertWithMessage("$engine: il motivo della pausa nel diario").that(kit.only().pauseReason).isEqualTo(PauseReason.FROZEN)
    kit.restart()
    kit.operations.resumeAll(Duration.ZERO)
    assertThat((kit.shown() as NasOperationState.Paused).reason).isEqualTo(PauseReason.FROZEN)

    // 3. Il NAS non risponde (nessuna ora del NAS): in pausa per il NAS, scritto nel diario.
    kit.gate.byShare.clear()
    val anchored = kit.clocks.nasNow
    kit.clocks.nasNow = null
    kit.operations.resumeAll(1.minutes)
    assertThat(kit.only().pauseReason).isEqualTo(PauseReason.NAS_UNREACHABLE)
    kit.restart()
    kit.operations.resumeAll(Duration.ZERO)
    assertThat((kit.shown() as NasOperationState.Paused).reason).isEqualTo(PauseReason.NAS_UNREACHABLE)

    // 4. Passata la condizione, riprende da sola e arriva in fondo; il motivo sparisce con la pausa.
    kit.clocks.nasNow = anchored
    kit.operations.resumeAll(1.minutes)
    assertThat(kit.only().state).isEqualTo(OpState.DONE)
    assertThat(kit.only().pauseReason).isNull()
    assertThat(kit.exists("test", "a.jpg")).isFalse()
  }

  @Test
  fun ilMotivoDellaPausaStaNelDiarioConLEsecutoreVero() = runTest { pausesAreWritten(KitEngine.PRODUCTION) }

  @Test
  fun ilMotivoDellaPausaStaNelDiarioConLEsecutoreDiRiferimento() = runTest { pausesAreWritten(KitEngine.REFERENCE) }
}
