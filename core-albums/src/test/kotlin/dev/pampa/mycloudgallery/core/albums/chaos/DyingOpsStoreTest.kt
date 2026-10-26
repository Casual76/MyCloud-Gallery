package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il diario di core-nasops con la morte ([DyingOpsStore]) e' un proxy dinamico: se non riconosce una scrittura, quella scrittura non e' mai un punto dove il
 * processo muore, e il banco resta verde senza provarla. Lo ha gia' fatto una volta: i metodi che prendono un `OpId` (un tipo inline) hanno il nome
 * storpiato dal compilatore (`create-<hash>`, `setOpState-<hash>`) e il primo proxy ne saltava un quarto. Qui: ogni metodo di `OpsStore` e' classificato
 * (scrittura o lettura), e la prima scrittura di un'operazione, `create`, e' davvero un punto dove si muore.
 */
class DyingOpsStoreTest {

  @Test
  fun ogniMetodoDelDiarioESiaUnaScritturaCheSiColpisceSiaUnaLettura() {
    val names = OpsStore::class.java.methods.map { it.name.substringBefore('-') }.toSet()
    val unknown = names - DyingOpsStore.WRITES - READS
    assertWithMessage("metodi di OpsStore che il banco non ha classificato (scrittura o lettura?): $unknown").that(unknown).isEmpty()
    assertWithMessage("scritture che OpsStore non ha piu'").that(DyingOpsStore.WRITES - names).isEmpty()
  }

  @Test
  fun laPrimaScritturaDiUnOperazioneEUnPuntoDoveSiMuore() = runBlocking {
    for (moment in Moment.entries) {
      val stats = ShareChaosStats()
      val blow = Blow(Surface.OPS_STORE, 1, moment, ShareFaultKind.KILL)
      val config = ShareChaosConfig(
        SimulatedChaosTargets.capabilities, minSteps = 1, maxSteps = 1, hazardPercent = 0, scriptedBlows = { _ -> Blows(listOf(blow), emptyList()) },
      )
      // Il primo mondo del banco con uno spostamento (un passo "condividi" con almeno un file dell'area privata).
      var fired = false
      for (index in 0 until 40) {
        val report = ShareChaosRound(index, ShareChaosBench.seedOf(31L, index), SimulatedChaosTargets, config, stats).run()
        assertWithMessage(report.describe()).that(report.ok).isTrue()
        if (report.fired.any { it.blow == blow }) {
          fired = true
          break
        }
      }
      assertWithMessage("la morte alla prima scrittura del diario di core-nasops ($moment) non e' mai scattata").that(fired).isTrue()
      assertThat(stats.callsOn(Surface.OPS_STORE)).isAtLeast(1)
    }
  }

  private companion object {
    /** Le letture di `OpsStore`: non si colpiscono. */
    val READS = setOf("op", "opByPlanHash", "ops", "observeOps", "items", "itemsToResume", "trashEntry", "observeTrash", "expiredTrash", "trashManifest", "purgedSince")
  }
}
