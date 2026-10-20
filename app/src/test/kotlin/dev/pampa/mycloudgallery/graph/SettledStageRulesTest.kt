package dev.pampa.mycloudgallery.graph

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * D-2 delle prove sul campo: le regole di una tappa preparano l'ora del NAS ([SettledStageRules.ready]) **una volta**, quando la prima valutazione
 * dice «vai» e prima del primo lotto. Se le regole dicono di aspettare non si apre niente; le valutazioni a lavoro iniziato passano dritte.
 */
class SettledStageRulesTest {

  private val request = WorkRequest.Background(BackgroundActivity.INDEX_SYNC)

  private class Scripted(var next: RuleDecision) : StageRules {
    override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision = next

    override suspend fun isPluggedIn(): Boolean = true
  }

  @Test
  fun lOraDelNasSiPreparaSoloDopoIlViaEUnaVoltaSola() = runBlocking<Unit> {
    val events = ArrayList<String>()
    val inner = Scripted(RuleDecision.Wait(WaitReason.ProfileInactive))
    val rules = SettledStageRules(inner, settle = { events += "rete" }, ready = { events += "ora del NAS" })

    // Le regole dicono di aspettare: la rete si aspetta, la sessione no.
    rules.decide(request, running = false)
    assertThat(events).containsExactly("rete")

    // «Vai»: prima del primo lotto si prepara l'ora del NAS, una volta.
    inner.next = RuleDecision.Run(network = NetworkClass.HOME_WIFI)
    rules.decide(request, running = false)
    rules.decide(request, running = true)
    rules.decide(request, running = false)
    assertThat(events).containsExactly("rete", "ora del NAS").inOrder()
  }

  @Test
  fun senzaPreparazioneLeRegoleRestanoQuelleDiPrima() = runBlocking<Unit> {
    val inner = Scripted(RuleDecision.Run(network = NetworkClass.HOME_WIFI))
    var settled = 0
    val rules = SettledStageRules(inner, settle = { settled++ })
    assertThat(rules.decide(request, running = false)).isEqualTo(inner.next)
    assertThat(rules.decide(request, running = true)).isEqualTo(inner.next)
    assertThat(settled).isEqualTo(1)
  }
}
