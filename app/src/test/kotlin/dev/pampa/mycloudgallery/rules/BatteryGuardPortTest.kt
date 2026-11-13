package dev.pampa.mycloudgallery.rules

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pacchetto B, BS-4 e BS-5 (critico Sol): la porta del controllo con le impostazioni **in ritardo** e **che rifiutano la scrittura**, e con il dispatcher **standard** (non `Unconfined`, che nasconde il
 * caricamento provvisorio e i riscontri in ritardo). BS-2: gli eventi del cursore si accorpano in una sola richiesta di ripresa. BS-4: il livello nuovo e' normalizzato dal dominio, lo storico no.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatteryGuardPortTest {

  /** Impostazioni in memoria: [gate] fa aspettare la scrittura, [rejects] la scarta senza lanciare (come `QuietWrites` a memoria piena), [firstReadDelay] ritarda la prima lettura. */
  private class FakeStore(
    initial: RuleSettings = RuleSettings(),
    private val firstReadDelay: kotlin.time.Duration = kotlin.time.Duration.ZERO,
    var rejects: Boolean = false,
  ) : RuleSettingsStore {
    val state = MutableStateFlow(initial)
    var gate: CompletableDeferred<Unit>? = null
    val writes = mutableListOf<RuleSettings>()

    override fun observe(): Flow<RuleSettings> = flow {
      if (firstReadDelay.isPositive()) delay(firstReadDelay)
      state.collect { emit(it) }
    }

    override suspend fun current(): RuleSettings = state.value

    override suspend fun save(settings: RuleSettings) {
      gate?.await()
      if (!rejects) state.value = settings
    }

    override suspend fun update(transform: (RuleSettings) -> RuleSettings) {
      gate?.await()
      if (rejects) return
      state.value = transform(state.value).also { writes += it }
    }
  }

  // --- Sto caricando (BS-5) -----------------------------------------------------------------------------------

  @Test
  fun fincheLeImpostazioniNonSonoLette_ilValoreEnullo_nonUnVentiPerCentoFinto() = runTest {
    val port = AppBatteryGuard(FakeStore(RuleSettings(batteryThresholdPercent = 55), firstReadDelay = 3.seconds), backgroundScope)

    runCurrent()
    assertThat(port.value.value).isNull()

    advanceTimeBy(3_001)
    runCurrent()
    assertThat(port.value.value?.levelPercent).isEqualTo(55)
  }

  // --- Esito della scrittura (BS-5) -----------------------------------------------------------------------------

  @Test
  fun unaScritturaCheLeImpostazioniRifiutano_restituisceFalso_eIlValoreSalvatoNonCambia() = runTest {
    val store = FakeStore(rejects = true)
    val port = AppBatteryGuard(store, backgroundScope)
    runCurrent()

    assertThat(port.setLevel(60)).isFalse()
    assertThat(port.setForBackup(false)).isFalse()
    assertThat(port.setForAnalysis(false)).isFalse()
    runCurrent()
    assertThat(port.value.value?.levelPercent).isEqualTo(20)
    assertThat(port.value.value?.forBackup).isTrue()
  }

  @Test
  fun unaScritturaInRitardo_nonCambiaIlValoreSalvatoPrimaDelRiscontro_epoiRestituisceVero() = runTest {
    val store = FakeStore()
    val port = AppBatteryGuard(store, backgroundScope)
    runCurrent()
    store.gate = CompletableDeferred()

    val result = async { port.setLevel(60) }
    runCurrent()
    // In volo: il salvato e' ancora 20 e la scrittura non e' finita.
    assertThat(result.isCompleted).isFalse()
    assertThat(port.value.value?.levelPercent).isEqualTo(20)

    store.gate!!.complete(Unit)
    advanceUntilIdle()
    runCurrent() // advanceUntilIdle non aspetta i lavori di sottofondo: il flusso del valore salvato si aggiorna qui
    assertThat(result.await()).isTrue()
    assertThat(port.value.value?.levelPercent).isEqualTo(60)
  }

  @Test
  fun dueScrittureRavvicinate_finisconoNellOrdineEIlSalvatoEL_ultima() = runTest {
    val store = FakeStore()
    val port = AppBatteryGuard(store, backgroundScope)
    runCurrent()

    val first = async { port.setLevel(40) }
    val second = async { port.setLevel(60) }
    advanceUntilIdle()
    runCurrent()

    assertThat(first.await()).isTrue()
    assertThat(second.await()).isTrue()
    assertThat(port.value.value?.levelPercent).isEqualTo(60)
  }

  // --- Dominio (BS-4) -----------------------------------------------------------------------------------------

  @Test
  fun leScrittureNuove_sonoNormalizzateDalDominio_enonToccanoLeAltreScelte() = runTest {
    val store = FakeStore(RuleSettings(preset = Preset.BALANCED, allowRoaming = true))
    val port = AppBatteryGuard(store, backgroundScope)
    runCurrent()

    for ((asked, saved) in mapOf(33 to 35, 2 to 5, 100 to 80, 0 to 5, 81 to 80)) {
      assertThat(port.setLevel(asked)).isTrue()
      assertThat(store.state.value.batteryThresholdPercent).isEqualTo(saved)
    }
    assertThat(store.state.value.preset).isEqualTo(Preset.BALANCED)
    assertThat(store.state.value.allowRoaming).isTrue()
  }

  @Test
  fun ilValoreGiaSalvatoFuoriGriglia_siMostraVero_eCambiareUnInterruttoreNonLoRiscrive() = runTest {
    val store = FakeStore(RuleSettings(batteryThresholdPercent = 33))
    val port = AppBatteryGuard(store, backgroundScope)
    runCurrent()
    assertThat(port.value.value?.levelPercent).isEqualTo(33)

    assertThat(port.setForBackup(false)).isTrue()
    runCurrent()

    assertThat(port.value.value?.levelPercent).isEqualTo(33)
    assertThat(store.state.value.batteryThresholdPercent).isEqualTo(33)
  }

  // --- La ripresa (BS-2) --------------------------------------------------------------------------------------

  @Test
  fun gliEventiDelCursore_siAccorpanoInUnaSolaRichiestaDiRipresa_dopoLaCalma() = runTest {
    val store = FakeStore(RuleSettings(batteryThresholdPercent = 80))
    val resumed = mutableListOf<Set<BackgroundActivity>>()
    val port = AppBatteryGuard(store, backgroundScope, onRelaxed = { resumed += it }, calm = 2.seconds)
    runCurrent()

    port.setLevel(70)
    advanceTimeBy(500)
    port.setLevel(60)
    advanceTimeBy(500)
    port.setLevel(50)
    // Ancora nella calma: nessuna richiesta.
    runCurrent()
    assertThat(resumed).isEmpty()

    advanceTimeBy(2_500)
    runCurrent()
    assertThat(resumed).hasSize(1)
    assertThat(resumed.single()).containsExactlyElementsIn(BackgroundActivity.entries)
  }

  @Test
  fun alzareIlLivello_oAccendereUnInterruttore_nonChiedeNienteDaRiprendere() = runTest {
    val store = FakeStore(RuleSettings(batteryThresholdPercent = 40, batteryThresholdForBackup = false))
    val resumed = mutableListOf<Set<BackgroundActivity>>()
    val port = AppBatteryGuard(store, backgroundScope, onRelaxed = { resumed += it }, calm = 1.seconds)
    runCurrent()

    port.setLevel(60)
    port.setForBackup(true)
    advanceTimeBy(5_000)
    runCurrent()

    assertThat(resumed).isEmpty()
  }

  @Test
  fun unaScritturaCheNonRiesce_nonChiedeLaRipresa() = runTest {
    val store = FakeStore(RuleSettings(batteryThresholdPercent = 80), rejects = true)
    val resumed = mutableListOf<Set<BackgroundActivity>>()
    val port = AppBatteryGuard(store, backgroundScope, onRelaxed = { resumed += it }, calm = 1.seconds)
    runCurrent()

    assertThat(port.setLevel(40)).isFalse()
    advanceTimeBy(5_000)
    runCurrent()

    assertThat(resumed).isEmpty()
  }
}
