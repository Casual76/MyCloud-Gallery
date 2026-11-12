package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneKeptReason
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import dev.pampa.mycloudgallery.feature.backup.free.ConfirmTaps
import org.junit.Test

/**
 * Il foglio delle versioni (`docs/fase5-progetto.md` §1.5 `VersionsState`, §2.4, D17, D18, E-E10): ogni stato composto e provato. La lista dice
 * dall'originale all'attuale cosa c'e', dove sta e se e' al sicuro; "Ripristina originale" ha il suo riepilogo e la conferma nasce solo dal tocco.
 */
class VersionsSheetTest : ComposeBackupTallTest() {

  private var now = 10_000L
  private val confirmations = ArrayList<PhoneConfirmation>()
  private var closed = 0
  private var asked = 0
  private var backToList = 0
  private var deleted = 0
  private val viewed = ArrayList<VersionId>()
  private val state = mutableStateOf<VersionsUi>(VersionsUi.Loading)
  private var taps: ConfirmTaps? = null
  private var shown = false

  private fun actions() = VersionsSheetActions(
    askRestore = { asked++ },
    backToList = { backToList++ },
    confirmRestore = { confirmations += it },
    view = { viewed += it },
    deleteIntermediates = { deleted++ },
    close = { closed++ },
  )

  /** La scena si monta una volta per prova; lo stato cambia da [state]. */
  private fun showState(ui: VersionsUi, fontScale: Float? = null, tapsEnabled: Boolean = true) {
    if (shown) {
      composeRule.runOnIdle { state.value = ui }
      composeRule.waitForIdle()
      return
    }
    shown = true
    state.value = ui
    taps = (ui as? VersionsUi.ConfirmRestore)?.takeIf { tapsEnabled }?.let { ConfirmTaps(it.plan) { now } }
    show(fontScale = fontScale) { VersionsSheetContent(state = state.value, taps = taps, actions = actions()) }
  }

  private fun listing(
    plan: dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan = VersionSamples.plan(),
    canView: Boolean = false,
    canDelete: Boolean = false,
    message: VersionsMessage? = null,
  ) = VersionsUi.Listing(plan, canView, canDelete, message)

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  private fun spoken(): List<String> = composeRule.onAllNodes(SemanticsMatcher("descrizione") { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
    .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

  @Test
  fun laListaVaDallAttualeAlLOriginale_ConLeIntermedieInMezzo() {
    showState(listing())
    composeRule.onNodeWithText("Versioni").assertExists()
    val current = composeRule.onNodeWithText("Versione di adesso").fetchSemanticsNode().boundsInRoot.top
    val between = composeRule.onNodeWithText("Versione intermedia").fetchSemanticsNode().boundsInRoot.top
    val original = composeRule.onNodeWithText("Originale").fetchSemanticsNode().boundsInRoot.top
    assertThat(current).isLessThan(between)
    assertThat(between).isLessThan(original)
  }

  @Test
  fun ogniRigaDiceQuandoQuantoDoveEPerTalkBackEUnaFraseSola() {
    showState(listing())
    val rows = spoken()
    assertThat(rows.any { it.startsWith("Versione di adesso,") && "sul telefono e sul NAS" in it && it.endsWith("Al sicuro") }).isTrue()
    assertThat(rows.any { it.startsWith("Originale,") && "nella cartella Versioni" in it && "4,8 MB" in it }).isTrue()
    assertThat(rows.any { it.startsWith("Versione intermedia,") }).isTrue()
  }

  @Test
  fun unaVersioneNonControllataDice_DaControllare() {
    showState(listing(VersionSamples.plan(versions = listOf(VersionSamples.original(safe = false), VersionSamples.current()))))
    composeRule.onNodeWithText("Da controllare").assertExists()
    composeRule.onNodeWithText("Al sicuro").assertExists()
  }

  @Test
  fun ripristinaOriginaleSiOffreEFaPartireIlRiepilogo() {
    showState(listing())
    button("Ripristina originale").assertIsEnabled().performClick()
    assertThat(asked).isEqualTo(1)
    // Nessuna conferma da una lista.
    assertThat(confirmations).isEmpty()
  }

  @Test
  fun seLOriginaleNonEAlSicuroIlTastoESpentoEDicePerche() {
    showState(listing(VersionSamples.plan(restore = RestoreAvailability.ORIGINAL_NOT_SAFE)))
    button("Ripristina originale").assertIsNotEnabled()
    composeRule.onNodeWithText("L’originale non ha ancora una copia controllata sul NAS", substring = true).assertExists()
  }

  @Test
  fun leRagioniPerCuiNonSiPuoRipristinareDiconoLaLoroFrase() {
    val expected = mapOf(
      RestoreAvailability.NAS_UNREACHABLE to "Il NAS non risponde. Riprova quando sei a casa.",
      RestoreAvailability.FROZEN to "I lavori sul NAS sono fermi",
      RestoreAvailability.READ_ONLY_AREA to "Questa area è in sola lettura",
      RestoreAvailability.NOT_MINE to "Questa foto non è tua",
      RestoreAvailability.NOT_AVAILABLE to "Il ripristino non è ancora pronto.",
      RestoreAvailability.ALREADY_ORIGINAL to "L’originale è già quello di adesso.",
    )
    for ((reason, text) in expected) {
      showState(listing(VersionSamples.plan(versions = listOf(VersionSamples.original(current = true)), restore = reason)))
      composeRule.onNodeWithText(text, substring = true).assertExists()
    }
  }

  @Test
  fun conUnaSolaVersioneNonCeNienteDaRipristinare() {
    showState(listing(VersionSamples.plan(versions = listOf(VersionSamples.current()), restore = RestoreAvailability.ONLY_ONE_VERSION)))
    composeRule.onNodeWithText("C’è una sola versione").assertExists()
    assertThat(composeRule.onAllNodes(hasText("Ripristina originale") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun leAreeSpecialiHannoLoroNota() {
    showState(listing(VersionSamples.plan(area = VersionArea.COMMON_NEEDS_NOTICE)))
    composeRule.onNodeWithText("Area comune").assertExists()
    composeRule.onNodeWithText("la nuova versione la vedranno tutti i familiari", substring = true).assertExists()
    showState(listing(VersionSamples.plan(area = VersionArea.READ_ONLY_NEW_MEDIA)))
    composeRule.onNodeWithText("Area in sola lettura").assertExists()
  }

  @Test
  fun vediEEliminaLeIntermedieCompaionoSoloSeApplePuo() {
    showState(listing(canView = false, canDelete = false))
    assertThat(composeRule.onAllNodes(hasText("Elimina le intermedie…") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
    showState(listing(canView = true, canDelete = true))
    button("Elimina le intermedie…").performClick()
    assertThat(deleted).isEqualTo(1)
    composeRule.onNodeWithText("Versione intermedia").performClick()
    assertThat(viewed).containsExactly(VersionSamples.versionId(2))
  }

  @Test
  fun leIntermedieSiTolgonoSoloAMano_ELaListaLoDice() {
    showState(listing())
    composeRule.onNodeWithText("Le versioni intermedie non si tolgono da sole.", substring = true).assertExists()
  }

  @Test
  fun unMessaggioDopoUnCambioSiVedeInCima() {
    showState(listing(message = VersionsMessage.PHONE_PERMISSION_MISSING))
    composeRule.onNodeWithText("Senza il permesso del telefono non ho ripristinato niente.").assertExists()
  }

  // --- Il riepilogo e la conferma ----------------------------------------------------------------------------------------------------

  @Test
  fun ilRiepilogoDiceCosaTornaECosaVaInVersioni() {
    showState(VersionsUi.ConfirmRestore(VersionSamples.plan()))
    composeRule.onNodeWithText("Ripristinare l’originale?").assertExists()
    // R3: il foglio dice il vero. La versione di adesso ha gia' una copia controllata (altrimenti il ripristino non si offre) e la riga del
    // telefono si rimette com'era solo se e' ancora quella al sicuro.
    composeRule.onNodeWithText("che ha già una copia controllata", substring = true).assertExists()
    composeRule.onNodeWithText("Non si carica niente.", substring = true).assertExists()
    composeRule.onNodeWithText("Torna al suo posto").assertExists()
    composeRule.onNodeWithText("Va nella cartella Versioni").assertExists()
    composeRule.onNodeWithText("Sul telefono c’è la versione modificata").assertExists()
    composeRule.onNodeWithText("Una modifica più nuova non la tocco.", substring = true).assertExists()
  }

  @Test
  fun laConfermaNasceSoloDalTocco() {
    showState(VersionsUi.ConfirmRestore(VersionSamples.plan()))
    assertThat(confirmations).isEmpty()
    now += 100
    button("Ripristina originale").assertIsEnabled().performClick()
    assertThat(confirmations).hasSize(1)
    assertThat(confirmations.single().confirmedSteps).isEqualTo(1)
  }

  @Test
  fun annullareTornaAllaListaSenzaConfermare() {
    showState(VersionsUi.ConfirmRestore(VersionSamples.plan()))
    button("Annulla").performClick()
    assertThat(backToList).isEqualTo(1)
    assertThat(confirmations).isEmpty()
  }

  @Test
  fun senzaTocchiLaConfermaNonPuoNascere() {
    showState(VersionsUi.ConfirmRestore(VersionSamples.plan()), tapsEnabled = false)
    button("Ripristina originale").assertIsNotEnabled()
    assertThat(confirmations).isEmpty()
  }

  @Test
  fun unMessaggioSiVedeAncheNelRiepilogo() {
    showState(VersionsUi.ConfirmRestore(VersionSamples.plan(), VersionsMessage.PLAN_CHANGED))
    composeRule.onNodeWithText("Nel frattempo qualcosa è cambiato", substring = true).assertExists()
  }

  // --- Gli altri passi ---------------------------------------------------------------------------------------------------------------

  @Test
  fun ilPermessoDelTelefonoSiSpiega() {
    showState(VersionsUi.AwaitingSystemDialog(VersionSamples.plan(), rows = 1))
    composeRule.onNodeWithText("Il telefono ti chiede un permesso").assertExists()
    composeRule.onNodeWithText("il telefono tiene la modifica", substring = true).assertExists()
    // A meta' di uno scambio non si chiude con un tocco.
    assertThat(composeRule.onAllNodes(hasText("Chiudi") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun mentreLoScambioPartePaginaAspetta() {
    showState(VersionsUi.Restoring(VersionSamples.plan()))
    composeRule.onNodeWithText("Ripristino l’originale…").assertExists()
    composeRule.onNodeWithText("Sposto i file sul NAS. Non carico niente.", substring = true).assertExists()
    // VE-8: se il backup sta lavorando, il foglio dice che aspetta il lotto.
    composeRule.onNodeWithText("aspetto che finisca il lotto", substring = true).assertExists()
  }

  @Test
  fun fattoDiceCheNienteESuNaCaricato() {
    showState(VersionsUi.Restored(VersionSamples.plan()))
    composeRule.onNodeWithText("Fatto").assertExists()
    composeRule.onNodeWithText("Non è stato caricato niente.", substring = true).assertExists()
    button("Chiudi").performClick()
    assertThat(closed).isEqualTo(1)
  }

  @Test
  fun seIlTelefonoTieneLaModificaLoDice() {
    showState(VersionsUi.Restored(VersionSamples.plan(), kept = PhoneKeptReason.PERMISSION_DENIED))
    composeRule.onNodeWithText("Sul telefono resta la versione modificata, perché non hai dato il permesso.").assertExists()
  }

  /**
   * R3 della revisione 5B: ogni ragione per cui il telefono tiene la modifica ha la sua frase (prima: sempre "non hai dato il permesso", anche
   * quando il permesso c'era). La riga cambiata e non toccata dice che niente e' perso.
   */
  @Test
  fun r3_ilFoglioDiceIlVeroSuCiocheResSulTelefono() {
    showState(VersionsUi.Restored(VersionSamples.plan(), kept = PhoneKeptReason.PHONE_CHANGED))
    composeRule.onNodeWithText("non l’ho toccata", substring = true).assertExists()
    composeRule.onNodeWithText("perché non hai dato il permesso", substring = true).assertDoesNotExist()
    showState(VersionsUi.Restored(VersionSamples.plan(), kept = PhoneKeptReason.SWAP_NOT_FINISHED))
    composeRule.onNodeWithText("Ripristino avviato").assertExists()
    composeRule.onNodeWithText("L’originale è tornato al suo posto sul NAS", substring = true).assertDoesNotExist()
    showState(VersionsUi.Restored(VersionSamples.plan(), kept = PhoneKeptReason.REWRITE_FAILED))
    composeRule.onNodeWithText("resta la versione modificata, intatta", substring = true).assertExists()
  }

  /** R3: la lista dice perche' non si puo' ripristinare quando la versione di adesso non e' al sicuro; il riepilogo dice cosa succede al telefono. */
  @Test
  fun r3_laVersioneNonAlSicuroELaRigaDelTelefono() {
    showState(VersionsUi.Listing(VersionSamples.plan(restore = RestoreAvailability.CURRENT_NOT_SAFE), canView = false, canDeleteIntermediates = false))
    composeRule.onNodeWithText("ripristinando l’originale la perderesti", substring = true).assertExists()
    showState(VersionsUi.Listing(VersionSamples.plan(restore = RestoreAvailability.PAUSED), canView = false, canDeleteIntermediates = false))
    composeRule.onNodeWithText("Il backup è in pausa", substring = true).assertExists()
  }

  @Test
  fun caricamentoEFunzioneNonPronta() {
    showState(VersionsUi.Loading)
    composeRule.onNodeWithText("Leggo le versioni…").assertExists()
    showState(VersionsUi.Unavailable)
    composeRule.onNodeWithText("Le versioni non sono ancora pronte").assertExists()
    button("Chiudi").performClick()
    assertThat(closed).isEqualTo(1)
  }
}
