package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.testing.ScriptedCorrections
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import dev.pampa.mycloudgallery.feature.backup.free.ConfirmTaps
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il foglio "Correggi data e luogo" e "Ruota" (`docs/fase5-progetto.md` §1.5 `CorrectionState`, §2.4, D19, E-L8, Q6): ogni stato composto e
 * provato. Si sceglie cosa correggere, si vede cosa cambia prima e dopo, e la conferma nasce solo dal tocco su "Correggi".
 */
class CorrectionSheetTest : ComposeBackupTallTest() {

  private var now = 10_000L
  private val confirmations = ArrayList<PhoneConfirmation>()
  private var closed = 0
  private var safeguarded = 0
  private val edits = ArrayList<CorrectionDraft>()
  private val searches = ArrayList<String>()
  private val chosen = ArrayList<PlaceChoice>()
  private val state = mutableStateOf<CorrectionUi>(CorrectionUi.Loading)
  private var taps: ConfirmTaps? = null
  private var shown = false
  private var currentDraft = CorrectionDraft()

  private val subject = CorrectionSubject(
    mediaId = VersionSamples.media,
    takenAt = LocalDateTime.of(2026, 10, 12, 14, 32),
    offsetMinutes = 120,
    placeLabel = "Oslo",
    hasLocation = true,
  )

  private fun actions() = CorrectionSheetActions(
    edit = { change -> currentDraft = change(currentDraft); edits += currentDraft },
    searchPlaces = { searches += it },
    choosePlace = { chosen += it },
    apply = { confirmations += it },
    safeguardNow = { safeguarded++ },
    close = { closed++ },
  )

  private fun showState(ui: CorrectionUi, fontScale: Float? = null) {
    if (shown) {
      composeRule.runOnIdle { state.value = ui }
      composeRule.waitForIdle()
      return
    }
    shown = true
    state.value = ui
    taps = (ui as? CorrectionUi.Editing)?.plan?.let { ConfirmTaps(it) { now } }
    show(fontScale = fontScale) { CorrectionSheetContent(state = state.value, taps = taps, actions = actions()) }
  }

  private fun planOf(draft: CorrectionDraft, mode: CorrectionMode = CorrectionMode.ALL): CorrectionPlan {
    val request = draft.build(subject, mode).request!!
    return runBlocking { ScriptedCorrections().plan(VersionSamples.media, request) }
  }

  private fun editing(
    draft: CorrectionDraft = CorrectionDraft.startingFrom(subject),
    mode: CorrectionMode = CorrectionMode.ALL,
    withPlan: Boolean = false,
    errors: Set<DraftError> = emptySet(),
    places: List<PlaceChoice> = emptyList(),
    canSearch: Boolean = false,
    message: CorrectionMessage? = null,
    readiness: CorrectionReadiness = CorrectionReadiness.READY,
  ): CorrectionUi.Editing {
    currentDraft = draft
    return CorrectionUi.Editing(
      subject = subject,
      mode = mode,
      draft = draft,
      errors = errors,
      plan = if (withPlan) planOf(draft, mode) else null,
      places = places,
      canSearchPlaces = canSearch,
      message = message,
      readiness = readiness,
    )
  }

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  private fun absent(text: String) = assertThat(composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes()).isEmpty()

  // --- Scrivere --------------------------------------------------------------------------------------------------------------------

  @Test
  fun siApreConLeTreSezioniEIlDettaglioDiAdesso() {
    showState(editing())
    composeRule.onNodeWithText("Correggi data e luogo").assertExists()
    composeRule.onNodeWithText("Data e ora").assertExists()
    composeRule.onNodeWithText("Adesso: 12 ottobre 2026 alle 14:32", substring = true).assertExists()
    composeRule.onNodeWithText("Luogo").assertExists()
    composeRule.onNodeWithText("Adesso: Oslo").assertExists()
    composeRule.onNodeWithText("Rotazione").assertExists()
    composeRule.onNodeWithText("Cosa cambia").assertExists()
    composeRule.onNodeWithText("Scegli cosa correggere.").assertExists()
  }

  @Test
  fun senzaPianoIlPulsanteCorreggiEspento() {
    showState(editing())
    button("Correggi").assertIsNotEnabled()
  }

  @Test
  fun accenderLaDataMostraIDueCampi_EScrivereLaCambia() {
    showState(editing(draft = CorrectionDraft.startingFrom(subject).copy(changeDate = true)))
    composeRule.onNodeWithText("Data (gg/mm/aaaa)").assertExists()
    composeRule.onNodeWithText("Ora (hh:mm)").assertExists()
    composeRule.onNodeWithText("Data e ora").performClick()
    assertThat(edits.last().changeDate).isFalse()
  }

  @Test
  fun unaDataSbagliataDiceCosaScrivere() {
    showState(
      editing(
        draft = CorrectionDraft.startingFrom(subject).copy(changeDate = true, dateText = "31/02/2026", timeText = "25:00"),
        errors = setOf(DraftError.DATE_INVALID, DraftError.TIME_INVALID),
      ),
    )
    composeRule.onNodeWithText("Scrivi una data vera, per esempio 03/10/2026.").assertExists()
    composeRule.onNodeWithText("Scrivi un’ora vera, per esempio 14:30.").assertExists()
  }

  @Test
  fun ilLuogoHaTreScelte_EQuelloSceltoEIndicato() {
    showState(editing(draft = CorrectionDraft.startingFrom(subject).copy(place = PlaceEdit.CLEAR)))
    composeRule.onNodeWithText("Lascia com’è").assertExists()
    composeRule.onNodeWithText("Cambia il luogo").assertExists()
    composeRule.onNodeWithText("Togli il luogo").assertExists()
    composeRule.onNodeWithText("La foto non dirà più dove è stata scattata.").assertExists()
    composeRule.onNodeWithText("Cambia il luogo").performClick()
    assertThat(edits.last().place).isEqualTo(PlaceEdit.CHANGE)
  }

  @Test
  fun cambiareIlLuogoOffreLaRicercaSeCeEComunqueLeCoordinate() {
    val bergen = PlaceChoice("Bergen", GeoPoint(60.39, 5.32))
    showState(
      editing(
        draft = CorrectionDraft.startingFrom(subject).copy(place = PlaceEdit.CHANGE, placeQuery = "Ber"),
        places = listOf(bergen, PlaceChoice("Berlino", GeoPoint(52.52, 13.4))),
        canSearch = true,
      ),
    )
    composeRule.onNodeWithText("Cerca un luogo").assertExists()
    composeRule.onNodeWithText("Bergen").assertExists()
    composeRule.onNodeWithText("Berlino").assertExists()
    composeRule.onNodeWithText("Oppure scrivi le coordinate").assertExists()
    composeRule.onNodeWithText("Bergen").performClick()
    assertThat(chosen).containsExactly(bergen)
  }

  @Test
  fun senzaRicercaDeiLuoghiCeSoloLeCoordinate() {
    showState(editing(draft = CorrectionDraft.startingFrom(subject).copy(place = PlaceEdit.CHANGE), canSearch = false))
    absent("Cerca un luogo")
    composeRule.onNodeWithText("Oppure scrivi le coordinate").assertExists()
  }

  @Test
  fun leCoordinateSbagliateOMancantiDiconoCosaFare() {
    showState(
      editing(
        draft = CorrectionDraft.startingFrom(subject).copy(place = PlaceEdit.CHANGE, coordinatesText = "91 10"),
        errors = setOf(DraftError.COORDINATES_INVALID),
      ),
    )
    composeRule.onNodeWithText("Le coordinate non vanno bene.", substring = true).assertExists()
    showState(
      editing(
        draft = CorrectionDraft.startingFrom(subject).copy(place = PlaceEdit.CHANGE),
        errors = setOf(DraftError.PLACE_MISSING),
      ),
    )
    composeRule.onNodeWithText("Scegli un luogo o scrivi le coordinate.").assertExists()
  }

  @Test
  fun laRotazioneHaDueTastiEDiceDiQuantoRuota() {
    showState(editing(draft = CorrectionDraft.startingFrom(subject).copy(quarterTurns = 1)))
    composeRule.onNodeWithText("Ruotata di 90° a destra").assertExists()
    composeRule.onNodeWithText("Ruota a destra").performClick()
    assertThat(edits.last().quarterTurns).isEqualTo(2)
    composeRule.onNodeWithText("Ruota a sinistra").performClick()
    assertThat(edits.last().quarterTurns).isEqualTo(1)
  }

  @Test
  fun ruotareADestraDaTreQuartiTornaAZero_ESinistraDaZeroVaATre() {
    showState(editing())
    composeRule.onNodeWithText("Ruota a sinistra").performClick()
    assertThat(edits.last().quarterTurns).isEqualTo(3)
    currentDraft = currentDraft.copy(quarterTurns = 3)
    composeRule.onNodeWithText("Ruota a destra").performClick()
    assertThat(edits.last().quarterTurns).isEqualTo(0)
  }

  @Test
  fun inModoRuotaCeSoloLaRotazione() {
    showState(editing(mode = CorrectionMode.ROTATE))
    composeRule.onNodeWithText("Ruota").assertExists()
    composeRule.onNodeWithText("Rotazione").assertExists()
    absent("Data e ora")
    absent("Luogo")
  }

  // --- Cosa cambia e la conferma ----------------------------------------------------------------------------------------------

  @Test
  fun conIlPianoVedeCosaCambiaPrimaEDopo() {
    val draft = CorrectionDraft.startingFrom(subject).copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00", place = PlaceEdit.CLEAR, quarterTurns = 1)
    showState(editing(draft = draft, withPlan = true))
    composeRule.onNodeWithText("Data e ora: da 12 ottobre 2026 alle 14:32 a 3 ottobre 2026 alle 10:00").assertExists()
    composeRule.onNodeWithText("Luogo: da Oslo a senza luogo").assertExists()
    composeRule.onNodeWithText("Rotazione: Ruotata di 90° a destra").assertExists()
  }

  @Test
  fun laConfermaNasceSoloDalTocco() {
    val draft = CorrectionDraft.startingFrom(subject).copy(quarterTurns = 1)
    showState(editing(draft = draft, withPlan = true))
    assertThat(confirmations).isEmpty()
    now += 100
    button("Correggi").assertIsEnabled().performClick()
    assertThat(confirmations).hasSize(1)
    assertThat(confirmations.single().confirmedSteps).isEqualTo(1)
  }

  @Test
  fun annullareChiudeSenzaConfermare() {
    showState(editing(draft = CorrectionDraft.startingFrom(subject).copy(quarterTurns = 1), withPlan = true))
    button("Annulla").performClick()
    assertThat(closed).isEqualTo(1)
    assertThat(confirmations).isEmpty()
  }

  @Test
  fun unFormatoCheNonSiScriveLoDiceInCima() {
    showState(editing(readiness = CorrectionReadiness.FORMAT_NOT_WRITABLE))
    composeRule.onNodeWithText("Questo tipo di file non si scrive: la correzione resta nell’app e il file non cambia.").assertExists()
  }

  @Test
  fun ognuMessaggioDopoUnCambioSiVede() {
    val expected = mapOf(
      CorrectionMessage.PLAN_CHANGED to "Nel frattempo il file è cambiato",
      CorrectionMessage.CONFIRMATION_EXPIRED to "È passato troppo tempo.",
      CorrectionMessage.PERMISSION_REFUSED to "Hai detto di no al telefono: non è cambiato niente.",
      CorrectionMessage.NO_SYSTEM_DIALOG to "Su questo telefono non posso chiedere il permesso",
      CorrectionMessage.SYSTEM_DIALOG_FAILED to "Il telefono non ha accettato la richiesta di permesso",
    )
    assertThat(expected.keys).containsExactlyElementsIn(CorrectionMessage.entries)
    for ((message, text) in expected) {
      showState(editing(message = message))
      composeRule.onNodeWithText(text, substring = true).assertExists()
    }
  }

  // --- Gli altri stati ---------------------------------------------------------------------------------------------------------------

  @Test
  fun prima_LaMettoAlSicuro() {
    showState(CorrectionUi.NeedsSafeFirst(subject))
    composeRule.onNodeWithText("Prima la metto al sicuro").assertExists()
    button("Metti al sicuro adesso").performClick()
    assertThat(safeguarded).isEqualTo(1)
    button("Chiudi").performClick()
    assertThat(closed).isEqualTo(1)
  }

  @Test
  fun scrittura_Permesso_Caricamento_Fatto() {
    showState(CorrectionUi.AwaitingSystemDialog(1))
    composeRule.onNodeWithText("Il telefono ti chiede un permesso").assertExists()
    showState(CorrectionUi.Writing)
    composeRule.onNodeWithText("Scrivo la correzione…").assertExists()
    showState(CorrectionUi.Uploading(onlyInApp = false))
    composeRule.onNodeWithText("Sto portando la versione corretta sul NAS.", substring = true).assertExists()
    showState(CorrectionUi.Done(onlyInApp = false))
    composeRule.onNodeWithText("La versione corretta è al sicuro sul NAS.", substring = true).assertExists()
    showState(CorrectionUi.Done(onlyInApp = true))
    composeRule.onNodeWithText("Corretta solo nell’app", substring = false).assertExists()
    composeRule.onNodeWithText("il file non è cambiato", substring = true).assertExists()
  }

  @Test
  fun ogniGuastoDiceLaSuaFrase() {
    val expected = mapOf(
      CorrectionFailure.SOURCE_UNREADABLE to "Non riesco a leggere il file",
      CorrectionFailure.RESULT_NOT_VALID to "La copia corretta non si apre bene",
      CorrectionFailure.PHONE_WRITE_FAILED to "Non sono riuscito a scrivere sul telefono",
      CorrectionFailure.PHONE_NOT_RESTORED to "Attenzione: il file sul telefono si è rovinato.",
      CorrectionFailure.PHONE_FULL to "Sul telefono non c’è abbastanza spazio.",
      CorrectionFailure.NAS_FAILED to "Il NAS non ha accettato la versione nuova.",
      CorrectionFailure.PHONE_CHANGED to "non l’ho toccata",
    )
    assertThat(expected.keys).containsExactlyElementsIn(CorrectionFailure.entries)
    for ((code, text) in expected) {
      showState(CorrectionUi.Failed(code))
      composeRule.onNodeWithText(text, substring = true).assertExists()
    }
  }

  @Test
  fun ogniMotivoPerCuiNonSiPuoDiceLaSuaFrase() {
    val expected = mapOf(
      CorrectionReadiness.COMMON_AREA_NOTICE to "Questo file è nell’area comune",
      CorrectionReadiness.READ_ONLY_AREA to "Questa area è in sola lettura",
      CorrectionReadiness.NAS_UNREACHABLE to "Il NAS non risponde.",
      CorrectionReadiness.FROZEN to "I lavori sul NAS sono fermi",
      CorrectionReadiness.NOT_MINE to "Questo media non è tuo",
      CorrectionReadiness.NOT_AVAILABLE to "La correzione non è ancora pronta.",
      // VE-7: pausa, prova e lavoro in corso hanno la loro frase (prima: tutti "non è ancora pronta").
      CorrectionReadiness.PAUSED to "Il backup è in pausa",
      CorrectionReadiness.TEST_ONLY to "Il backup vero non è ancora acceso",
      CorrectionReadiness.BUSY to "Sto ancora lavorando su questo media",
      // VE-4: la rotazione di un formato che non si scrive non si fa.
      CorrectionReadiness.ROTATION_NOT_WRITABLE to "non si ruota dall’app",
    )
    for ((reason, text) in expected) {
      showState(CorrectionUi.Blocked(reason))
      composeRule.onNodeWithText(text, substring = true).assertExists()
    }
  }

  /** VE-4: un formato che non si scrive (HEIC, RAW) non offre la rotazione: nessuno la vedrebbe, e il foglio non dice "fatto". */
  @Test
  fun ve4_unFormatoCheNonSiScriveNonOffreLaRotazione() {
    showState(editing(readiness = CorrectionReadiness.FORMAT_NOT_WRITABLE))
    absent("Ruota a destra")
    absent("Rotazione")
    composeRule.onNodeWithText("Data e ora").assertExists()
  }

  /** VE-3: "Metti al sicuro adesso" fa vedere che lavora; fuori dalle sorgenti lo dice e non offre un tasto che non puo' fare niente. */
  @Test
  fun ve3_laMessaAlSicuroSiVedeEFuoriDalleSorgentiLoDice() {
    showState(CorrectionUi.Safeguarding(subject))
    composeRule.onNodeWithText("La metto al sicuro…").assertExists()
    showState(CorrectionUi.NeedsSafeFirst(subject, outsideSources = true))
    composeRule.onNodeWithText("in una cartella che il backup non guarda", substring = true).assertExists()
    absent("Metti al sicuro adesso")
    showState(CorrectionUi.NeedsSafeFirst(subject, stillWaiting = true))
    composeRule.onNodeWithText("non è ancora finito", substring = true).assertExists()
  }

  @Test
  fun caricamentoEFunzioneNonPronta() {
    showState(CorrectionUi.Loading)
    composeRule.onNodeWithText("Un attimo…").assertExists()
    showState(CorrectionUi.Unavailable)
    composeRule.onNodeWithText("Le correzioni non sono ancora pronte").assertExists()
  }
}
