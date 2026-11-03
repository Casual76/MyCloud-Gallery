package dev.pampa.mycloudgallery.release

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelGateReason
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CA-3 della revisione funzionale della Fase 10: la finestra "stato del manifest non ancora letto". I cancelli delle scritture la trattano da "fermo" (fallire chiusi,
 * millisecondi); le regole delle tappe e il cancello dei modelli prima rispondevano "fermo" **subito**, e la prima tappa di un lavoro nato con il processo si perdeva
 * (`TrustSyncRun` prende `Stop` per "niente da fare"). Adesso aspettano lo stato letto, al piu' [ReleaseGraph.LOAD_WAIT]; dopo, "fermo". Tempo virtuale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReleaseLoadWindowTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val request = WorkRequest.Background(BackgroundActivity.INDEX_SYNC)
  private val run = RuleDecision.Run(network = NetworkClass.HOME_WIFI)
  private val inner = object : StageRules {
    override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision = run

    override suspend fun isPluggedIn(): Boolean = false
  }
  private val models = object : ModelNetworkGate {
    override suspend fun verdict(trigger: ModelTrigger, bytes: Long, source: ModelSourceKind): ModelNetworkVerdict = ModelNetworkVerdict.Allowed
  }

  private fun TestScope.graph(state: GatedReleaseState): ReleaseGraph = testReleaseGraph(temp.newFolder(), state, StandardTestDispatcher(testScheduler))

  @Test
  fun ca3_laPrimaTappaAspettaLoStatoSalvatoEPoiParte() = runTest {
    val state = GatedReleaseState(ReleaseStatus.Normal, open = false)
    val release = graph(state)
    launch { release.load() }
    val decision = async { ReleaseStageRules(inner) { release }.decide(request, running = false) }
    runCurrent()
    assertThat(decision.isCompleted).isFalse()

    state.loadGate.complete(Unit)
    assertThat(decision.await()).isEqualTo(run)
  }

  @Test
  fun ca3_loScaricamentoDeiModelliAspettaLoStatoSalvatoEPoiParte() = runTest {
    val state = GatedReleaseState(ReleaseStatus.Normal, open = false)
    val release = graph(state)
    launch { release.load() }
    val verdict = async { ReleaseModelNetworkGate(models) { release }.verdict(ModelTrigger.entries.first(), 1_000L, ModelSourceKind.HTTPS) }
    runCurrent()
    assertThat(verdict.isCompleted).isFalse()

    state.loadGate.complete(Unit)
    assertThat(verdict.await()).isEqualTo(ModelNetworkVerdict.Allowed)
  }

  @Test
  fun ca3_unoStatoCheNonSiLeggeVale_fermo_dopoLAttesa() = runTest {
    val release = graph(GatedReleaseState(ReleaseStatus.Normal, open = false))
    val loading = launch { release.load() }
    val decision = async { ReleaseStageRules(inner) { release }.decide(request, running = false) }
    advanceTimeBy(ReleaseGraph.LOAD_WAIT - 1.milliseconds)
    runCurrent()
    assertThat(decision.isCompleted).isFalse()
    advanceTimeBy(2.milliseconds)
    assertThat(decision.await()).isInstanceOf(RuleDecision.Stop::class.java)
    // La lettura che non finisce mai (lo stato non si legge): si chiude a fine prova.
    loading.cancel()
  }

  @Test
  fun ca3_conLaVersioneFermataLeRegoleFermanoEIModelliNonScaricano() = runTest {
    val release = graph(GatedReleaseState(ReleaseStatus.Stopped(ReleaseMessage.SECURITY)))
    release.load()
    assertThat(ReleaseStageRules(inner) { release }.decide(request, running = false)).isInstanceOf(RuleDecision.Stop::class.java)
    assertThat(ReleaseModelNetworkGate(models) { release }.verdict(ModelTrigger.entries.first(), 1_000L, ModelSourceKind.HTTPS))
      .isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.OFFLINE))
  }
}
