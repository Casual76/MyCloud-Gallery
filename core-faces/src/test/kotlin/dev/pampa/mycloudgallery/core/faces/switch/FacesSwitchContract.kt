package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * La suite di contratto dell'interruttore (Fase 8, P7, §5.2): le righe del §2.1 che l'interruttore vero (`DefaultFacesSwitch`) e il fake di P0
 * (`FakeFacesSwitch`, che usano le schermate e i ViewModel per provare) devono dire allo stesso modo. Le differenze note del fake (una voce
 * assente cancella, "Attiva qui" non rivede l'avviso, un account gia' acceso ottiene una generazione nuova, nessuna cancellazione in corso) NON
 * sono nella suite: stanno nel resoconto di P7 come modifiche richieste ai contratti degli altri.
 */
abstract class FacesSwitchContract {

  interface Rig {
    val switch: FacesSwitch
    val requirements: FakeFacesRequirements

    fun consent(): FacesActivationConsent

    /** Le generazioni di cui questo dispositivo ha tolto i dati locali, nell'ordine. */
    fun erasedGenerations(): List<FaceGeneration>

    /** Un altro dispositivo dell'account ha cambiato la voce (null = assente): arriva qui con il giro dei dati personali, e il gancio gira. */
    suspend fun remoteSays(setting: FacesSetting?)

    fun close()
  }

  /**
   * Un dispositivo spento, o ([activeOn] non nullo) gia' attivo sulla generazione [activeOn] con i suoi dati locali: l'account l'ha acceso, l'avviso
   * e' visto, il calcolo ha creato i dati.
   */
  protected abstract fun rig(activeOn: FaceGeneration?): Rig

  private val rigs = mutableListOf<Rig>()

  protected fun newRig(activeOn: FaceGeneration? = null): Rig = rig(activeOn).also { rigs += it }

  @After
  fun chiudi() = rigs.forEach { it.close() }

  protected val g1: FaceGeneration = SwitchRig.generation(41)
  protected val g2: FaceGeneration = SwitchRig.generation(42)

  @Test
  fun spentaDiSerie() {
    val rig = newRig()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(rig.switch.state.value).isEqualTo(FacesState.Off)
    assertThat(rig.switch.currentState().activeHere).isFalse()
  }

  @Test
  fun attivareVaSubito() = runTest {
    val rig = newRig()
    val outcome = rig.switch.activate(rig.consent())
    assertThat(outcome).isInstanceOf(ActivateOutcome.Activated::class.java)
    val generation = (outcome as ActivateOutcome.Activated).generation
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    assertThat(rig.switch.state.value).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    assertThat(rig.switch.currentState().activeHere).isTrue()
  }

  @Test
  fun sulleShareVereLaRecinzioneDaLocalOnly() = runTest {
    val rig = newRig()
    rig.requirements.localOnly = true
    val generation = (rig.switch.activate(rig.consent()) as ActivateOutcome.Activated).generation
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.LOCAL_ONLY))
  }

  @Test
  fun iRequisitiDelD5() = runTest {
    val rig = newRig()
    rig.requirements.privateArea = PrivateAreaState.Missing
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NoPrivateArea)
    rig.requirements.privateArea = PrivateAreaState.SameAsCommon
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NoPrivateArea)
    rig.requirements.privateArea = PrivateAreaState.Declared("test")
    rig.requirements.indexers = IndexerState(accountIndexers = 0, thisDevice = false, canBecomeIndexer = true)
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NoIndexer(canBecomeIndexer = true))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
  }

  @Test
  fun unConsensoDiUnAltroTesto() = runTest {
    val rig = newRig()
    val stale = FacesActivationConsent(FacesSwitch.CURRENT_TEXT_VERSION + 1, 0L)
    assertThat(rig.switch.activate(stale)).isEqualTo(ActivateOutcome.StaleConsent)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
  }

  @Test
  fun unAltroDispositivoAspettaLAvvisoEPoiVaBene() = runTest {
    val rig = newRig()
    rig.remoteSays(FacesSetting.On(g1, 1))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.AwaitingNotice(g1, 1))
    assertThat(rig.switch.currentState().activeHere).isFalse()
    assertThat(rig.erasedGenerations()).isEmpty()
    rig.switch.acceptNotice(1)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(g1, HereState.ACTIVE))
  }

  @Test
  fun nonSuQuestoDispositivoCancellaInLocaleEAttivaQuiTorna() = runTest {
    val rig = newRig(activeOn = g1)
    rig.switch.excludeThisDevice()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(g1, HereState.EXCLUDED))
    assertThat(rig.switch.currentState().activeHere).isFalse()
    assertThat(rig.erasedGenerations()).containsExactly(g1)
    rig.switch.includeThisDevice()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(g1, HereState.ACTIVE))
  }

  @Test
  fun siPuoEscludereAncheDallAvviso() = runTest {
    val rig = newRig()
    rig.remoteSays(FacesSetting.On(g1, 1))
    rig.switch.excludeThisDevice()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(g1, HereState.EXCLUDED))
    assertThat(rig.erasedGenerations()).isEmpty()
  }

  @Test
  fun laVoceSpentaTogliIDatiELoStatoEOff() = runTest {
    val rig = newRig(activeOn = g1)
    rig.remoteSays(FacesSetting.Off(g1))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(rig.erasedGenerations()).containsExactly(g1)
  }

  @Test
  fun unaGenerazioneNuovaTogliIDatiDellaVecchiaEAspettaLAvviso() = runTest {
    val rig = newRig(activeOn = g1)
    rig.remoteSays(FacesSetting.On(g2, 1))
    assertThat(rig.erasedGenerations()).containsExactly(g1)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.AwaitingNotice(g2, 1))
  }

  @Test
  fun unaVoceSconosciutaEDaAggiornareENonCancellaNiente() = runTest {
    val rig = newRig(activeOn = g1)
    rig.remoteSays(FacesSetting.Unknown)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.NeedsUpdate)
    assertThat(rig.erasedGenerations()).isEmpty()
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NeedsUpdate)
    assertThat(rig.switch.reconcile(FacesSetting.Unknown)).isEqualTo(ReconcileOutcome.Done)
  }
}

/** L'interruttore vero. */
class DefaultFacesSwitchContractTest : FacesSwitchContract() {
  override fun rig(activeOn: FaceGeneration?): Rig = object : Rig {
    private val inner = SwitchRig()
    override val switch: FacesSwitch get() = inner.switch
    override val requirements: FakeFacesRequirements get() = inner.requirements

    init {
      if (activeOn != null) runBlocking {
        inner.account.set(FacesSetting.On(activeOn, 1))
        inner.switch.acceptNotice(1)
        inner.eraser.data += activeOn
        inner.eraser.calls.clear()
      }
    }

    override fun consent() = inner.consent()

    override fun erasedGenerations() = inner.eraser.calls.map { it.first }

    override suspend fun remoteSays(setting: FacesSetting?) {
      val raw: PersonalValue? = when (setting) {
        null -> null
        FacesSetting.Unknown -> PersonalValue.Text("2|forma|nuova")
        else -> FacesSettingCodec.valueOf(setting)
      }
      inner.account.injectRemote(raw)
      // Il gancio prima della pubblicazione riceve la stessa voce; e' idempotente con l'allineamento che parte da solo.
      inner.switch.reconcile(setting)
    }

    override fun close() = inner.close()
  }
}

/** Il fake di P0, che le schermate usano per provare: dice le stesse cose nelle righe comuni. */
class FakeFacesSwitchContractTest : FacesSwitchContract() {
  override fun rig(activeOn: FaceGeneration?): Rig = object : Rig {
    override val requirements = FakeFacesRequirements()
    // Il fake ricorda da solo la generazione che ha nello stato iniziale (`localData`): i suoi dati "ci sono" gia'.
    private val fake = FakeFacesSwitch(
      requirements = requirements,
      initial = if (activeOn != null) FacesState.On(activeOn, HereState.ACTIVE) else FacesState.Off,
    )
    override val switch: FacesSwitch get() = fake

    override fun consent() = FacesActivationConsent(FacesSwitch.CURRENT_TEXT_VERSION, 0L)

    override fun erasedGenerations() = fake.erasedLocally.toList()

    override suspend fun remoteSays(setting: FacesSetting?) {
      fake.reconcile(setting)
    }

    override fun close() = Unit
  }
}
