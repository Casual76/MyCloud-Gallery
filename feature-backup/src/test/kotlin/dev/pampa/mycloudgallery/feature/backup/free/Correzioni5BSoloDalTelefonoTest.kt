package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.SafeguardStart
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.backup.testing.ScriptedCorrections
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * "Elimina… → Solo dal telefono" dopo le correzioni 5B (`docs/review/funzionale-5b.md` LS-1, LS-4; decisione dell'utente del 20/10 "Elimina con
 * avviso"): dal viewer un media che non risulta al sicuro sul NAS va nel cestino di Android come nella 4B, ma solo dopo un avviso chiaro e un
 * tocco in piu'; un media al sicuro segue il flusso della 5B. "Metti al sicuro" fa partire davvero cio' che serve a quel media.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Correzioni5BSoloDalTelefonoTest : ComposeBackupTallTest() {

  private val dispatcher = UnconfinedTestDispatcher()
  private val trashed = ArrayList<Set<MediaId>>()
  private val continued = ArrayList<FreeSpaceFilter>()
  private var safeguards = 0

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun id(n: Int) = FreeSpaceSamples.mediaId(n)

  private fun advice(advice: PhoneOnlyAdvice, note: SafeguardNote? = null): PhoneOnlyUi.Advice {
    val assessment = PhoneOnlyAssessment(mapOf(id(1) to advice))
    return PhoneOnlyUi.Advice(assessment, safeFirst = assessment.preselectSafeFirst, note = note)
  }

  private fun manyAdvice(count: Int, advice: PhoneOnlyAdvice): PhoneOnlyUi.Advice {
    val assessment = PhoneOnlyAssessment((1..count).associate { id(it) to advice })
    return PhoneOnlyUi.Advice(assessment, safeFirst = assessment.preselectSafeFirst, note = null)
  }

  private fun showSheet(ui: PhoneOnlyUi, isVideo: Boolean = false, withTrash: Boolean = true) {
    show {
      PhoneOnlyContent(
        state = ui,
        onChoose = {},
        onSafeguard = { safeguards++ },
        onContinue = { continued += it },
        onClose = {},
        onTrash = if (withTrash) ({ ids -> trashed += ids }) else null,
        isVideo = isVideo,
      )
    }
  }

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  private fun noButton(text: String) = assertThat(composeRule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes()).isEmpty()

  /**
   * LS-1: in release (D12, backup "in prova") il foglio diceva "non si può togliere ora" e aveva solo "Chiudi": una regressione rispetto alla 4B.
   * Adesso: l'avviso "Questa foto non e' sul NAS", e "Elimina dal telefono" porta al cestino di Android (il dialogo del sistema dopo).
   */
  @Test
  fun ls1_conIlBackupNonAttivo_avvisoEPoiIlCestinoDiAndroid() {
    showSheet(advice(PhoneOnlyAdvice.UNAVAILABLE))
    composeRule.onNodeWithText("Questa foto non è sul NAS").assertExists()
    composeRule.onNodeWithText("Il backup non è attivo su questo telefono", substring = true).assertExists()
    // GF-13: al singolare e senza genere (vale per una foto come per un video): si parla del "file".
    composeRule.onNodeWithText("lo recuperi da Eliminati di recente per 30 giorni", substring = true).assertExists()
    noButton("Continua")
    noButton("Metti al sicuro")
    button("Elimina dal telefono").performClick()
    assertThat(trashed).containsExactly(setOf(id(1)))
  }

  /**
   * GF-13: con piu' elementi le frasi sono al plurale. Prima il titolo diceva «Non sono sul NAS» ma il testo «Se la elimini dal telefono... la recuperi... non ci sara' piu'»
   * (singolare e femminile), e per un video «la» era sbagliato lo stesso.
   */
  @Test
  fun gf13_conPiuElementiLeFrasiSonoAlPlurale() {
    showSheet(manyAdvice(5, PhoneOnlyAdvice.UNAVAILABLE))
    composeRule.onNodeWithText("Non sono sul NAS").assertExists()
    composeRule.onNodeWithText("Se elimini i file dal telefono, vanno nel cestino di Android", substring = true).assertExists()
    composeRule.onNodeWithText("li recuperi da Eliminati di recente per 30 giorni, poi non ci saranno più da nessuna parte", substring = true).assertExists()
    assertThat(composeRule.onAllNodes(hasText("Se la elimini", substring = true)).fetchSemanticsNodes()).isEmpty()
    button("Elimina dal telefono").performClick()
    assertThat(trashed.single()).hasSize(5)
  }

  /**
   * FAB-22: l'avviso dell'attesa conta i file del foglio. Prima diceva «sta ancora caricando i file» anche per un file solo (e qui, con un file,
   * il titolo e' «Questa foto non e' sul NAS»).
   */
  @Test
  fun fab22_l_attesaDiUnFileDiceIlFileEQuellaDiPiuDiceIFile() {
    showSheet(advice(PhoneOnlyAdvice.WAIT))
    composeRule.onNodeWithText("Il backup sta ancora caricando il file, o ha avuto un problema.", substring = true).assertExists()
    assertThat(composeRule.onAllNodes(hasText("caricando i file", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun fab22_l_attesaDiPiuFileDiceIFile() {
    showSheet(manyAdvice(3, PhoneOnlyAdvice.WAIT))
    composeRule.onNodeWithText("Il backup sta ancora caricando i file, o ha avuto un problema.", substring = true).assertExists()
    assertThat(composeRule.onAllNodes(hasText("caricando il file", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  private fun assertSheetPhraseNeutral(count: Int) {
    showSheet(manyAdvice(count, PhoneOnlyAdvice.SAFE_FIRST), withTrash = false)
    composeRule.onNodeWithText("Sul NAS non cambia niente", substring = true).assertExists()
    assertThat(composeRule.onAllNodes(hasText("Il file resta", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  /** GF-13: la frase del foglio "Elimina solo dal telefono" vale per uno e per cinque elementi: non dice "il file" ne' "la copia" al singolare. */
  @Test
  fun gf13_laFraseDelFoglioVaBeneConUnElemento() = assertSheetPhraseNeutral(1)

  @Test
  fun gf13_laFraseDelFoglioVaBeneConCinqueElementi() = assertSheetPhraseNeutral(5)

  @Test
  fun ls1_unVideoHaLeSueParole() {
    showSheet(advice(PhoneOnlyAdvice.UNAVAILABLE), isVideo = true)
    composeRule.onNodeWithText("Questo video non è sul NAS").assertExists()
  }

  /** LS-1 con il backup attivo: un media non ancora al sicuro ha "Metti al sicuro" (preselezionata, E-D14) e "Elimina dal telefono" lo stesso. */
  @Test
  fun ls1_nonAncoraAlSicuro_mettiAlSicuroOppureEliminaLoStesso() {
    showSheet(advice(PhoneOnlyAdvice.SAFE_FIRST))
    composeRule.onNodeWithText("Questa foto non è sul NAS").assertExists()
    composeRule.onNodeWithText("Sul NAS non c’è ancora una copia controllata dall’app.", substring = true).assertExists()
    button("Metti al sicuro").performClick()
    assertThat(safeguards).isEqualTo(1)
    button("Elimina dal telefono").performClick()
    assertThat(trashed).containsExactly(setOf(id(1)))
  }

  /** LS-1: un media al sicuro segue il flusso della 5B (verifica, guardiano, timbro, cestino dopo): niente avviso, niente cestino diretto. */
  @Test
  fun ls1_alSicuroSegueIlFlussoDellaQuintaB() {
    showSheet(advice(PhoneOnlyAdvice.GUARD_FIRST))
    assertThat(composeRule.onAllNodes(hasText("Questa foto non è sul NAS")).fetchSemanticsNodes()).isEmpty()
    noButton("Elimina dal telefono")
    button("Continua").performClick()
    assertThat(continued.single().selection).containsExactly(id(1))
    assertThat(trashed).isEmpty()
  }

  /** Fuori dal viewer (nessun cestino offerto) il foglio resta quello della 5B: dice perche' e basta. */
  @Test
  fun senzaIlCestinoDelViewer_nienteEliminaDalTelefono() {
    showSheet(advice(PhoneOnlyAdvice.UNAVAILABLE), withTrash = false)
    composeRule.onNodeWithText("1 non si può togliere ora", substring = true).assertExists()
    noButton("Elimina dal telefono")
  }

  /** LS-4: fuori dalle sorgenti del backup il foglio lo dice (prima "Prima mettilo al sicuro" chiudeva il foglio e non succedeva niente). */
  @Test
  fun ls4_fuoriDalleSorgentiIlFoglioLoDice() {
    showSheet(advice(PhoneOnlyAdvice.SAFE_FIRST, note = SafeguardNote.OUTSIDE_SOURCES))
    composeRule.onNodeWithText("Questa cartella non è nel backup").assertExists()
    noButton("Metti al sicuro")
    button("Elimina dal telefono").assertExists()
  }

  // --- Il ViewModel (LS-4) ---------------------------------------------------------------------------------------------------------

  private class Assessor(var now: PhoneOnlyAdvice) : PhoneOnlyAssessor {
    override suspend fun assess(ids: Set<MediaId>): PhoneOnlyAssessment = PhoneOnlyAssessment(ids.associateWith { now })
  }

  /**
   * LS-4: "Metti al sicuro" fa partire cio' che serve a **quel** media (non piu' il solo "Fai ora" generale) e il foglio resta aperto: quando il
   * media e' al sicuro si aggiorna da solo, e si puo' continuare con il flusso della 5B.
   */
  @Test
  fun ls4_mettiAlSicuroFaPartireQuelMedia_eIlFoglioSiAggiornaDaSolo() = runTest(dispatcher) {
    val assessor = Assessor(PhoneOnlyAdvice.SAFE_FIRST)
    val corrections = ScriptedCorrections().apply { safeguardAnswer = SafeguardStart.STARTED }
    val actions = RecordingBackupActions()
    val vm = PhoneOnlyViewModel(Optional.of(assessor), Optional.of(actions), Optional.of(corrections))
    vm.bind(setOf(id(1)))
    runCurrent()
    vm.safeguard()
    runCurrent()
    assertThat(corrections.safeguarded).containsExactly(id(1))
    assertThat(actions.backupNowCalls).isEqualTo(0)
    assertThat(vm.state.value).isInstanceOf(PhoneOnlyUi.Safeguarding::class.java)
    assessor.now = PhoneOnlyAdvice.FREE
    advanceTimeBy(3_500)
    val after = vm.state.value as PhoneOnlyUi.Advice
    assertThat(after.assessment.freeFilter?.selection).containsExactly(id(1))
  }

  @Test
  fun ls4_seNonParteNienteIlFoglioDicePerche() = runTest(dispatcher) {
    val corrections = ScriptedCorrections().apply { safeguardAnswer = SafeguardStart.NOT_IN_SOURCES }
    val vm = PhoneOnlyViewModel(Optional.of(Assessor(PhoneOnlyAdvice.SAFE_FIRST)), Optional.empty(), Optional.of(corrections))
    vm.bind(setOf(id(1)))
    runCurrent()
    vm.safeguard()
    runCurrent()
    assertThat((vm.state.value as PhoneOnlyUi.Advice).note).isEqualTo(SafeguardNote.OUTSIDE_SOURCES)
  }
}
