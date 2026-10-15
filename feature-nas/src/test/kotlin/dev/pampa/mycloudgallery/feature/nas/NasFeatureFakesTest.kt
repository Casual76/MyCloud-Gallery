package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.testing.ScriptedNasOperations
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Cio' che P14 fa con i fake di core-nasops da questo modulo, che **non vede core-nas**: il kit mette in piedi il NAS finto, le operazioni, il
 * cestino, lo spazio e le cartelle; la conferma la costruisce il foglio (`ConfirmationSheet`) dopo i tocchi dell'utente, come fara' la UI.
 */
class NasFeatureFakesTest {

  @Test
  fun ilKitBastaPerProvareIlGiroInteroEliminaConfermaCestinoEliminaSubito() = runTest {
    val kit = NasOpsTestKit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))

    // Il ViewModel chiede il piano, il foglio mostra il riepilogo, l'utente tocca, il ViewModel avvia.
    val trash = (kit.operations.plan(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg")))) as PlanOutcome.Ready).plan
    val sheet = ConfirmationSheet(trash)
    assertThat(trash.summary.itemCount).isEqualTo(1)
    val confirmation = sheet.tap(kit.clocks.monotonicMs())!!
    assertThat(kit.operations.start(trash, confirmation)).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.exists("test", "Foto/a.jpg")).isFalse()
    assertThat(kit.operations.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)

    kit.trash.refresh()
    val item = (kit.trash.items.value as NasTrashState.Ready).items.single()
    assertThat(item.deletableNow).isTrue()

    // "Elimina subito": due tocchi, e senza il secondo non c'e' conferma.
    val purge = (kit.operations.plan(OpRequest.PurgeNow(listOf(OwnTrashRef(item.trashId)))) as PlanOutcome.Ready).plan
    val purgeSheet = ConfirmationSheet(purge)
    assertThat(purgeSheet.tap(kit.clocks.monotonicMs())).isNull()
    assertThat(purgeSheet.tapsLeft).isEqualTo(1)
    // R6: il secondo tocco e' un secondo gesto, non il secondo colpo di un doppio tocco.
    kit.clocks.monotonic += dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules.MIN_TAP_GAP_MS
    val second = purgeSheet.tap(kit.clocks.monotonicMs())!!
    assertThat(kit.operations.start(purge, second)).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.filesIn("test")).isEmpty()
  }

  @Test
  fun leOperazioniACopioneBastanoPerIViewModel() = runTest {
    val scripted = ScriptedNasOperations()
    assertThat(scripted.operations.value).isEmpty()
    assertThat(scripted.requests).isEmpty()
  }
}
