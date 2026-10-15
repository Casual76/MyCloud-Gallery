package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.nas.confirm.NameCheck
import dev.pampa.mycloudgallery.feature.nas.confirm.NameDraft
import dev.pampa.mycloudgallery.feature.nas.confirm.NamePurpose
import dev.pampa.mycloudgallery.feature.nas.confirm.NasConfirmSheet
import dev.pampa.mycloudgallery.feature.nas.confirm.NasNameSheet
import java.io.File
import org.junit.Test

/**
 * "`ConfirmationIssuer` chiamato solo da un tocco" (riga P14 del §4.4, D24, Q8): una [UserConfirmation] nasce solo dal gestore del
 * pulsante di conferma di un foglio, dopo il numero di tocchi che il piano chiede (due per "Elimina subito"), e mai da dati letti
 * dal NAS. Si prova in due modi: sul comportamento (compongo il foglio, aspetto, tocco) e sul codice (nessun'altra riga di questo
 * modulo costruisce una conferma; `Phase4ArchitectureTest` in core-common vieta il resto del progetto).
 */
class ConfirmationTapTest : ComposeNasTest() {

  private val areas = FakeNasAreas.defaultAreas()
  private val confirmed = mutableListOf<Pair<OpPlan, UserConfirmation>>()
  private var clock = 5_000L

  private fun showSheet(plan: OpPlan?, onDismiss: () -> Unit = {}) {
    show {
      NasConfirmSheet(
        plan = plan,
        areas = areas,
        monotonicMs = { clock },
        onConfirmed = { p, c -> confirmed += p to c },
        onDismiss = onDismiss,
      )
    }
  }

  // --- Il comportamento --------------------------------------------------------------------------------------------------

  @Test
  fun senzaUnTocco_neSoloComponendo_neAspettando_nonNasceNessunaConferma() {
    showSheet(NasFixtures.trashPlan())
    composeRule.onNodeWithText("Elimina").assertIsDisplayed()
    clock += 60_000
    composeRule.waitForIdle()
    assertThat(confirmed).isEmpty()
  }

  @Test
  fun unTocco_confermaUnPianoNormale_equellaConfermaVaisoloPerQuelPiano() {
    val plan = NasFixtures.trashPlan()
    showSheet(plan)
    composeRule.onNodeWithText("Elimina").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed).hasSize(1)
    val (confirmedPlan, confirmation) = confirmed.single()
    assertThat(confirmedPlan).isSameInstanceAs(plan)
    assertThat(confirmation.confirmedSteps).isEqualTo(1)
    assertThat(confirmation.issuedAtMonotonicMs).isEqualTo(5_000L)
    assertThat(ConfirmationRules.check(plan, confirmation, clock)).isEqualTo(ConfirmationCheck.Valid)
    // Non vale per un altro piano (un altro elemento, un'altra destinazione).
    assertThat(ConfirmationRules.check(NasFixtures.moveToCommonPlan(), confirmation, clock)).isEqualTo(ConfirmationCheck.WrongPlan)
    // Ne' dopo troppo tempo.
    assertThat(ConfirmationRules.check(plan, confirmation, clock + ConfirmationRules.MAX_AGE_MS + 1)).isEqualTo(ConfirmationCheck.Expired)
  }

  @Test
  fun spostareNellAreaComuneSiConfermaConUnTocco() {
    showSheet(NasFixtures.moveToCommonPlan())
    composeRule.onNodeWithText("Le vedranno tutti i familiari").assertIsDisplayed()
    composeRule.onNodeWithText("Sposta nell'area comune").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed.single().second.confirmedSteps).isEqualTo(1)
  }

  @Test
  fun eliminareSubitoVuoleDueTocchi_ELUltimoPannelloAvvisaDelBackup() {
    val plan = NasFixtures.purgePlan()
    showSheet(plan)
    // Primo pannello: l'avviso sul backup esterno c'e' gia', e il pulsante non conferma ancora.
    composeRule.onNodeWithText("Eliminare subito 2 elementi?").assertIsDisplayed()
    composeRule.onNodeWithText("Hai un backup esterno?").assertExists()
    composeRule.onNodeWithText("Continua").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed).isEmpty()

    // Secondo pannello: "Ultima conferma", l'avviso sul backup e il pulsante che elimina davvero.
    composeRule.onNodeWithText("Ultima conferma").assertIsDisplayed()
    composeRule.onNodeWithText("Hai un backup esterno?").assertExists()
    composeRule.onNodeWithText("Elimina subito").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed).hasSize(1)
    assertThat(confirmed.single().second.confirmedSteps).isEqualTo(2)
    assertThat(ConfirmationRules.check(plan, confirmed.single().second, clock)).isEqualTo(ConfirmationCheck.Valid)
  }

  @Test
  fun annullareAlSecondoPannelloFaRicominciareDaCapo() {
    val plan = NasFixtures.purgePlan()
    var shown by mutableStateOf<OpPlan?>(plan)
    show {
      NasConfirmSheet(plan = shown, areas = areas, monotonicMs = { clock }, onConfirmed = { p, c -> confirmed += p to c }, onDismiss = { shown = null })
    }
    composeRule.onNodeWithText("Continua").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()
    assertThat(shown).isNull()
    // Lo stesso piano, riaperto: il primo pannello, nessun tocco "ricordato".
    shown = plan
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Continua").assertIsDisplayed()
    composeRule.onNodeWithText("Ultima conferma").assertDoesNotExist()
    assertThat(confirmed).isEmpty()
  }

  @Test
  fun ilFoglioDelNomeConfermaSoloConUnPianoValidoEUnTocco() {
    val plan = NasFixtures.trashPlan(0)
    var draft by mutableStateOf(NameDraft(NamePurpose.NEW_FOLDER, "", NameCheck.Empty, shareName = "test"))
    show {
      NasNameSheet(
        draft = draft,
        areas = areas,
        monotonicMs = { clock },
        onTextChange = {},
        onUseSuggestion = {},
        onConfirmed = { p, c -> confirmed += p to c },
        onDismiss = {},
      )
    }
    // Campo vuoto, o nome ancora in controllo, o rifiutato: il pulsante e' spento e un tocco non fa niente.
    for (check in listOf(NameCheck.Empty, NameCheck.Checking, NameCheck.Exists, NameCheck.Invalid("A_B"))) {
      draft = draft.copy(text = "x", check = check)
      composeRule.waitForIdle()
      composeRule.onNodeWithText("Crea cartella").assertIsNotEnabled()
      composeRule.onNodeWithText("Crea cartella").performClick()
    }
    assertThat(confirmed).isEmpty()

    draft = draft.copy(text = "Scuola", check = NameCheck.Ready(plan))
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Crea cartella").assertIsEnabled()
    composeRule.onNodeWithText("Crea cartella").performClick()
    composeRule.waitForIdle()
    assertThat(confirmed).hasSize(1)
    assertThat(confirmed.single().first).isSameInstanceAs(plan)
  }

  // --- Il codice ---------------------------------------------------------------------------------------------------------

  private val sources: List<File> by lazy {
    File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  /** Il codice senza i commenti (quelli di blocco e di riga): possono nominare cio' che qui si vieta. */
  private fun code(file: File): String = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString(separator = System.lineSeparator()) { it.substringBefore("//") }

  private fun filesMatching(pattern: Regex): List<String> = sources.filter { pattern.containsMatchIn(code(it)) }.map { it.name }.sorted()

  @Test
  fun nessunaRigaDiQuestoModuloCostruisceUnaConfermaSenzaPassareDalFoglio() {
    // `ConfirmationIssuer` e' quello che il foglio usa dietro le quinte: qui non si nomina mai.
    assertWithMessage("ConfirmationIssuer nominato").that(filesMatching(Regex("""\bConfirmationIssuer\b"""))).isEmpty()
    // I costruttori della conferma e del piano accettato: mai.
    assertWithMessage("costruttori di UserConfirmation o AcceptedPlan").that(filesMatching(Regex("""\b(UserConfirmation|AcceptedPlan)\s*\("""))).isEmpty()
    // Il foglio a tocchi si costruisce e si tocca solo nei due fogli (la conferma e il nome).
    val allowed = listOf("NasConfirm.kt", "NasNameSheet.kt")
    assertThat(filesMatching(Regex("""\bConfirmationSheet\s*\("""))).containsExactlyElementsIn(allowed)
    assertThat(filesMatching(Regex("""\.tap\s*\("""))).containsExactlyElementsIn(allowed)
  }

  @Test
  fun ogniTocco_staDentroIlGestoreDelPulsanteDiConferma() {
    // Ogni `.tap(` e' preceduto, entro poche righe, dal gestore `onConfirm = {`: nessuna chiamata in una composizione o in un effetto.
    for (name in listOf("NasConfirm.kt", "NasNameSheet.kt")) {
      val lines = sources.single { it.name == name }.readLines()
      lines.forEachIndexed { index, line ->
        if (Regex("""\.tap\s*\(""").containsMatchIn(line.substringBefore("//"))) {
          val before = lines.subList(maxOf(0, index - 4), index + 1).joinToString("\n")
          assertWithMessage("$name:${index + 1}: tap fuori dal gestore del pulsante").that(before).contains("onConfirm = {")
        }
      }
    }
  }

  @Test
  fun leViewModelNonNominanoNeIlFoglioNeLaConferma() {
    // I ViewModel ricevono una conferma gia' fatta (da `confirmed`): non ne fabbricano, non la tengono fra uno stato e l'altro.
    val viewModels = sources.filter { it.name.endsWith("ViewModel.kt") }
    assertThat(viewModels).isNotEmpty()
    for (file in viewModels) {
      assertWithMessage("${file.name} nomina il foglio a tocchi").that(Regex("""\bConfirmationSheet\b|\bConfirmationIssuer\b""").containsMatchIn(code(file))).isFalse()
    }
    // E nessuno stato (le classi che finiscono in Ui o State) ha un campo UserConfirmation: una conferma non si tiene.
    for (file in sources) {
      val text = code(file)
      assertWithMessage("${file.name}: una conferma tenuta in uno stato").that(Regex("""\bval\s+\w+\s*:\s*UserConfirmation\??\s*[,)=]""").containsMatchIn(text)).isFalse()
    }
  }
}
