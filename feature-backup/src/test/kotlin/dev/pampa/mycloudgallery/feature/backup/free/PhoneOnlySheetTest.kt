package dev.pampa.mycloudgallery.feature.backup.free

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * "Elimina solo dal telefono" (`docs/fase5-progetto.md` D22, E-D14, E-B14): il foglio dice per ogni media se si puo' liberare, se prima serve il
 * guardiano, se non e' al sicuro ("Prima mettilo al sicuro", **preselezionata**) o perche' aspetta. Non toglie niente da solo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneOnlySheetTest : ComposeBackupTallTest() {

  private val dispatcher = UnconfinedTestDispatcher()
  private var safeguard = 0
  private val continued = ArrayList<FreeSpaceFilter>()
  private var closed = 0
  private val choices = ArrayList<Boolean>()
  private val state = mutableStateOf<PhoneOnlyUi>(PhoneOnlyUi.Loading)
  private var shown = false

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun id(n: Int) = FreeSpaceSamples.mediaId(n)

  private fun assessment(vararg advice: PhoneOnlyAdvice) = PhoneOnlyAssessment(advice.withIndex().associate { (i, a) -> id(i + 1) to a })

  private fun showState(ui: PhoneOnlyUi) {
    if (shown) {
      composeRule.runOnIdle { state.value = ui }
      composeRule.waitForIdle()
      return
    }
    shown = true
    state.value = ui
    show {
      PhoneOnlyContent(
        state = state.value,
        onChoose = { choices += it; (state.value as? PhoneOnlyUi.Advice)?.let { advice -> state.value = advice.copy(safeFirst = it) } },
        onSafeguard = { safeguard++ },
        onContinue = { continued += it },
        onClose = { closed++ },
      )
    }
  }

  private fun advice(vararg values: PhoneOnlyAdvice): PhoneOnlyUi.Advice = assessment(*values).let { PhoneOnlyUi.Advice(it, safeFirst = it.preselectSafeFirst) }

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  @Test
  fun unMediaAlSicuroSiPuoTogliere() {
    showState(advice(PhoneOnlyAdvice.FREE))
    composeRule.onNodeWithText("Elimina solo dal telefono").assertExists()
    composeRule.onNodeWithText("1 si può togliere: la copia sul NAS è al sicuro.").assertExists()
    button("Continua").performClick()
    // Senza giorni esclusi: li ha scelti l'utente (D22).
    assertThat(continued).hasSize(1)
    assertThat(continued.single().excludeRecentDays).isEqualTo(0)
    assertThat(continued.single().kind).isEqualTo(FreeSpaceKind.ALL)
    assertThat(continued.single().selection).containsExactly(id(1))
  }

  @Test
  fun unMediaNonAlSicuroHaPrimaMettiloAlSicuroPreselezionato() {
    showState(advice(PhoneOnlyAdvice.SAFE_FIRST))
    composeRule.onNodeWithText("1 non è ancora al sicuro sul NAS: prima va messo al sicuro.").assertExists()
    button("Metti al sicuro").performClick()
    assertThat(safeguard).isEqualTo(1)
    assertThat(continued).isEmpty()
  }

  @Test
  fun conUnMixPreselezionaMettiloAlSicuro_MaSiPuoTogliereSoloQuelloGiaAlSicuro() {
    showState(advice(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.SAFE_FIRST))
    composeRule.onNodeWithText("2 si possono togliere: le copie sul NAS sono al sicuro.").assertExists()
    composeRule.onNodeWithText("Prima mettilo al sicuro").assertExists()
    composeRule.onNodeWithText("Togli solo quelli già al sicuro").assertExists()
    // La scelta di partenza e' "Prima mettilo al sicuro": il pulsante e' "Metti al sicuro".
    button("Metti al sicuro").assertExists()
    composeRule.onNodeWithText("Togli solo quelli già al sicuro").performClick()
    assertThat(choices).containsExactly(false)
    button("Continua").performClick()
    assertThat(continued.single().selection).containsExactly(id(1), id(2))
  }

  @Test
  fun ilGuardianoESpiegato() {
    showState(advice(PhoneOnlyAdvice.GUARD_FIRST, PhoneOnlyAdvice.GUARD_FIRST))
    composeRule.onNodeWithText("2 hanno solo la copia nell’area comune: prima ne faccio una copia privata.").assertExists()
    button("Continua").assertExists()
  }

  @Test
  fun ogniConsiglioHaLaSuaFrase() {
    val expected = mapOf(
      PhoneOnlyAdvice.FREE to "1 si può togliere",
      PhoneOnlyAdvice.GUARD_FIRST to "1 ha solo la copia nell’area comune",
      PhoneOnlyAdvice.SAFE_FIRST to "1 non è ancora al sicuro",
      PhoneOnlyAdvice.WAIT to "1 si sta caricando o ha un problema",
      PhoneOnlyAdvice.FROZEN to "1 è fermo",
      PhoneOnlyAdvice.UNAVAILABLE to "1 non si può togliere ora",
      PhoneOnlyAdvice.OUTSIDE_TEST_FENCE to "1 è fuori dalla cartella di prova",
      PhoneOnlyAdvice.NOT_ON_PHONE to "1 non è sul telefono",
    )
    assertThat(expected.keys).containsExactlyElementsIn(PhoneOnlyAdvice.entries)
    for ((a, text) in expected) {
      showState(advice(a))
      composeRule.onNodeWithText(text, substring = true).assertExists()
    }
  }

  @Test
  fun quandoNienteSiPuoFareCeSoloChiudi() {
    showState(advice(PhoneOnlyAdvice.FROZEN))
    assertThat(composeRule.onAllNodes(hasText("Continua") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodes(hasText("Metti al sicuro") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
    button("Chiudi").performClick()
    assertThat(closed).isEqualTo(1)
  }

  @Test
  fun caricamentoEFunzioneNonPronta() {
    showState(PhoneOnlyUi.Loading)
    composeRule.onNodeWithText("Controllo cosa si può togliere…").assertExists()
    showState(PhoneOnlyUi.Unavailable)
    composeRule.onNodeWithText("Per ora non è disponibile").assertExists()
  }

  // --- Il ViewModel -----------------------------------------------------------------------------------------------------------------

  @Test
  fun ilViewModelValutaIMediaEPreselezionaLaScelta() = runTest(dispatcher) {
    val assessor = PhoneOnlyAssessor { assessment(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.SAFE_FIRST) }
    val actions = RecordingBackupActions()
    val vm = PhoneOnlyViewModel(Optional.of(assessor), Optional.of(actions))
    vm.bind(setOf(id(1), id(2)))
    val ui = vm.state.value as PhoneOnlyUi.Advice
    assertThat(ui.safeFirst).isTrue()
    vm.chooseSafeFirst(false)
    assertThat((vm.state.value as PhoneOnlyUi.Advice).safeFirst).isFalse()
    vm.safeguard()
    assertThat(actions.backupNowCalls).isEqualTo(1)
  }

  @Test
  fun senzaLaValutazioneLaFunzioneNonEPronta() = runTest(dispatcher) {
    val vm = PhoneOnlyViewModel(Optional.empty(), Optional.empty())
    vm.bind(setOf<MediaId>(id(1)))
    assertThat(vm.state.value).isEqualTo(PhoneOnlyUi.Unavailable)
  }
}
