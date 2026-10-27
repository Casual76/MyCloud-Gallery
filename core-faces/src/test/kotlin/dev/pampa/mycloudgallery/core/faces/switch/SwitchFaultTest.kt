package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesLocalStore
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.testing.FacesRegister
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryFacesPersonalSettings
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** Il processo muore: niente `catch (Exception)` lo ferma (e' un `Error`, come `kill -9` che nessuno vede arrivare). */
internal class ProcessDeath : Error("morte del processo")

/** Quando muore il processo rispetto al salvataggio dello stato locale: prima che arrivi al disco, o subito dopo. */
internal enum class DeathPhase { BEFORE_SAVE, AFTER_SAVE }

/** Uno stato locale che "muore" al salvataggio numero [dieAt] (0 = il primo di dopo [arm]); finche' non e' armato non muore mai. */
internal class CrashingLocalStore(private val inner: FacesLocalStore, private val phase: DeathPhase) : FacesLocalStore {
  override val local = inner.local
  private var left = Int.MAX_VALUE

  var saves = 0
    private set

  fun arm(dieAt: Int) {
    left = dieAt
    saves = 0
  }

  fun disarm() {
    left = Int.MAX_VALUE
  }

  override suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal {
    if (left == 0 && phase == DeathPhase.BEFORE_SAVE) throw ProcessDeath()
    val result = inner.update(transform)
    saves++
    if (left == 0) throw ProcessDeath()
    if (left != Int.MAX_VALUE) left--
    return result
  }
}

/** La voce dell'account con un guasto a richiesta alla scrittura. */
internal class FlakyAccount(private val inner: FacesPersonalSettings) : FacesPersonalSettings by inner {
  var failure: Throwable? = null

  override suspend fun set(setting: FacesSetting): Boolean {
    failure?.let { throw it }
    return inner.set(setting)
  }
}

/**
 * La morte del processo a ogni salvataggio e i guasti a ogni chiamata (§4.3): per ogni operazione che passa per piu' tappe, il processo muore
 * prima o subito dopo il k-esimo salvataggio dello stato locale; poi il dispositivo riparte (stesso stato locale, stessa voce, stessi dati) e
 * l'allineamento di avvio rimette in ordine. **Invarianti** a ogni punto di morte:
 *  1. i dati di una generazione esistono solo se la voce dell'account la nomina accesa e questo dispositivo non e' escluso;
 *  2. se il dispositivo e' attivo, ricorda l'avviso e i dati di quella generazione;
 *  3. se e' escluso non restano dati;
 *  4. la generazione dei dati ricordata, se la voce nomina un'altra accesa, e' stata tolta.
 */
class SwitchFaultTest {

  private val rigs = mutableListOf<SwitchRig>()

  private fun track(rig: SwitchRig) = rig.also { rigs += it }

  @After
  fun chiudi() = rigs.forEach { it.close() }

  private class Scenario(
    val name: String,
    val textVersion: Int = 1,
    /** Prepara il dispositivo (la morte non e' armata). */
    val seed: suspend (SwitchRig) -> Unit,
    val op: suspend (SwitchRig) -> Unit,
  )

  /** Cio' che farebbe il calcolo: se il dispositivo e' attivo qui crea i dati di quella generazione. */
  private fun computeIfActive(rig: SwitchRig) {
    val state = rig.switch.currentState()
    if (state.activeHere) rig.eraser.data += state.generationOrNull!!
  }

  private suspend fun activated(rig: SwitchRig) {
    rig.switch.activate(rig.consent())
    computeIfActive(rig)
  }

  private val scenarios = listOf(
    Scenario("attivare", seed = {}, op = { it.switch.activate(it.consent()) }),
    Scenario(
      "avviso",
      seed = { rig ->
        val other = SwitchRig(register = rig.register, name = "altro", seed = 9)
        other.switch.activate(other.consent())
        other.close()
        rig.account.pull()
      },
      op = { it.switch.acceptNotice(1) },
    ),
    Scenario("esclusione", seed = { activated(it) }, op = { it.switch.excludeThisDevice() }),
    Scenario(
      "riattivare qui",
      seed = {
        activated(it)
        it.switch.excludeThisDevice()
      },
      op = { it.switch.includeThisDevice() },
    ),
    Scenario(
      "voce spenta",
      seed = {
        activated(it)
        it.eraser.succeeds = false
        it.account.injectRemote(FacesSettingCodec.off(it.local.dataGeneration!!))
        it.eraser.succeeds = true
      },
      op = { it.switch.reconcile(it.account.current()) },
    ),
    Scenario(
      "generazione nuova",
      seed = {
        activated(it)
        it.eraser.succeeds = false
        it.account.injectRemote(FacesSettingCodec.on(SwitchRig.generation(900), 1))
        it.eraser.succeeds = true
      },
      op = { it.switch.reconcile(it.account.current()) },
    ),
  )

  @Test
  fun ilProcessoMuoreAOgniSalvataggio_ChiRipareTrovaTuttoInOrdine() = runTest {
    var deaths = 0
    for (scenario in scenarios) {
      for (phase in DeathPhase.entries) {
        for (dieAt in 0..3) {
          val register = FacesRegister()
          val inner = InMemoryFacesLocalStore()
          val crashing = CrashingLocalStore(inner, phase)
          val first = track(SwitchRig(register = register, localStore = crashing, textVersion = scenario.textVersion))
          scenario.seed(first)
          crashing.arm(dieAt)
          val died = runCatching { scenario.op(first) }.exceptionOrNull() is ProcessDeath
          crashing.disarm()
          if (died) deaths++
          first.close()

          // Il dispositivo riparte: stesso file locale, stessa voce, stessi dati; l'allineamento d'avvio.
          val second = track(SwitchRig(register = register, localStore = inner, eraser = first.eraser, account = first.account, textVersion = scenario.textVersion, seed = 2))
          second.switch.refreshRequirements()
          second.switch.reconcile(second.account.current())
          computeIfActive(second)
          val where = "${scenario.name} / ${phase.name} / salvataggio $dieAt (morto=$died)"
          assertInvariants(second, where)

          // Chi e' tornato spento puo' sempre riattivare: i resti di un'attivazione a meta' non la bloccano.
          if (second.switch.currentState() == FacesState.Off && scenario.name == "attivare") {
            val outcome = second.switch.activate(second.consent())
            assertWithMessage(where).that(outcome).isInstanceOf(ActivateOutcome.Activated::class.java)
            computeIfActive(second)
            assertInvariants(second, "$where, poi riattivato")
          }
        }
      }
    }
    // La prova serve solo se la morte avviene davvero: ogni scenario ha almeno un salvataggio.
    assertThat(deaths).isAtLeast(scenarios.size * 2)
  }

  private fun assertInvariants(rig: SwitchRig, where: String) {
    val voice = rig.account.current()
    val local = rig.local
    for (generation in rig.eraser.data) {
      val named = voice is FacesSetting.On && voice.generation == generation
      assertWithMessage("$where: dati di una generazione non corrente o di un dispositivo escluso").that(named && !local.excluded).isTrue()
    }
    val state = rig.switch.currentState()
    if (state.activeHere) {
      assertWithMessage("$where: attivo senza avviso e dati ricordati").that(local.dataGeneration).isEqualTo(state.generationOrNull)
      assertWithMessage("$where: attivo senza l'avviso visto").that(local.noticedGeneration).isEqualTo(state.generationOrNull)
    }
    if (local.excluded) assertWithMessage("$where: escluso con dei dati").that(rig.eraser.data).isEmpty()
    if (voice is FacesSetting.On && local.dataGeneration != null) {
      assertWithMessage("$where: la generazione dei dati non e' quella della voce").that(local.dataGeneration).isEqualTo(voice.generation)
    }
  }

  // --- Guasti a ogni chiamata --------------------------------------------------------------------------------------------------------

  @Test
  fun seLaVoceNonSiScriveL_AttivazioneRestaIndietroSenzaResti() = runTest {
    val register = FacesRegister()
    val account = InMemoryFacesPersonalSettings(register, "A")
    val flaky = FlakyAccount(account)
    val rig = track(SwitchRig(register = register, account = account, port = flaky))
    flaky.failure = IllegalStateException("registro non disponibile")
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Unavailable)
    assertThat(rig.account.current()).isNull()
    assertThat(rig.local).isEqualTo(FacesLocal.EMPTY)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    flaky.failure = null
    assertThat(rig.switch.activate(rig.consent())).isInstanceOf(ActivateOutcome.Activated::class.java)
  }

  @Test
  fun unGuastoDeiRequisitiNonEUnEccezioneEPrimaDelleScrittureNienteSiScrive() = runTest {
    val rig = track(SwitchRig(requirementsPort = ThrowingRequirements()))
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Unavailable)
    assertThat(rig.register.writes()).isEqualTo(0L)
    assertThat(rig.local).isEqualTo(FacesLocal.EMPTY)
  }

  @Test
  fun unGuastoDelloStatoLocaleNonEUnEccezioneNeUnaAttivazioneAMeta() = runTest {
    val rig = track(SwitchRig(localStore = FailingLocalStore()))
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Unavailable)
    assertThat(rig.register.writes()).isEqualTo(0L)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    // Le scelte senza esito non lanciano: lo stato resta com'era.
    rig.switch.excludeThisDevice()
    rig.switch.includeThisDevice()
    rig.switch.acceptNotice(1)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
  }

  @Test
  fun unaCancellazioneCheFallisceDueVolteEPoiRiesceNonDimenticaMaiLaGenerazione() = runTest {
    val rig = track(SwitchRig())
    rig.switch.activate(rig.consent())
    val generation = rig.local.dataGeneration!!
    rig.eraser.data += generation
    rig.eraser.succeeds = false
    rig.switch.excludeThisDevice()
    assertThat(rig.local.excluded).isTrue()
    assertThat(rig.local.dataGeneration).isEqualTo(generation)
    rig.eraser.throws = IllegalStateException("disco")
    rig.switch.reconcile(rig.account.current())
    assertThat(rig.local.dataGeneration).isEqualTo(generation)
    assertThat(rig.eraser.data).containsExactly(generation)
    // Al terzo tentativo riesce: ora non resta niente e la generazione si dimentica.
    rig.eraser.throws = null
    rig.eraser.succeeds = true
    rig.switch.reconcile(rig.account.current())
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.local.dataGeneration).isNull()
    assertThat(rig.local.excluded).isTrue()
  }
}

private class ThrowingRequirements : FacesRequirements {
  override suspend fun privateArea(): PrivateAreaState = throw IllegalStateException("profilo non disponibile")

  override suspend fun indexers(): IndexerState = throw IllegalStateException("registro non disponibile")

  override suspend fun localOnly(share: String): Boolean = throw IllegalStateException("profilo non disponibile")
}

private class FailingLocalStore : FacesLocalStore {
  override val local = MutableStateFlow(FacesLocal.EMPTY)

  override suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal = throw IOException("disco pieno")
}
