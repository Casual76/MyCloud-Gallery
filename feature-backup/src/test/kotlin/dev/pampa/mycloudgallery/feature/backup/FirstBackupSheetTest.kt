package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupEstimate
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.SummaryWarning
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.first.FirstBackupSheetContent
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import org.junit.Test

/**
 * Il foglio del primo backup (`docs/fase5-progetto.md` §2.11 punti 4 e 6, D11): i **tre numeri** con il loro peso, il totale da caricare,
 * la stima per "Stanotte" e per "Inizia ora", gli avvisi e le due scelte. Finche' si confronta con il NAS dice "Sto cercando cosa c'e'
 * gia'…" e le scelte aspettano. Si prova il contenuto, senza il pop-up (che e' `GalleryConfirmSheet`, provato dal design system).
 */
class FirstBackupSheetTest : ComposeBackupTest() {

  private var tonight = 0
  private var now = 0
  private var later = 0

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  private fun showSheet(summary: BackupSummary, fontScale: Float? = null) {
    show(fontScale = fontScale) {
      FirstBackupSheetContent(summary = summary, onTonight = { tonight++ }, onNow = { now++ }, onCancel = { later++ })
    }
  }

  @Test
  fun iTreNumeriDelPrimoBackupConIlLoroPeso() {
    showSheet(BackupSamples.firstBackupSummary)
    composeRule.onNodeWithText("Primo backup").assertExists()
    composeRule.onNodeWithText("1.240 già sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("Identici byte per byte", substring = true).assertExists()
    composeRule.onNodeWithText("312 con una copia simile sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("5.106 nuovi", substring = true).assertExists()
    // Da caricare: M + K = 312 + 5.106.
    composeRule.onNodeWithText("5.418 · ", substring = true).assertExists()
  }

  @Test
  fun leCopieNellAreaComuneSiCitanoAParte() {
    showSheet(BackupSamples.firstBackupSummary)
    composeRule.onNodeWithText("40 sono già nell’area comune", substring = true).assertExists()
  }

  @Test
  fun laStimaDiceTempoNottiETraffico() {
    showSheet(BackupSamples.firstBackupSummary)
    // Il tempo e' nella frase in cima (si vede senza scorrere) e nella riga della stima.
    composeRule.onNodeWithText("Da caricare: 5.418 foto e video", substring = true).assertExists()
    composeRule.onAllNodes(hasText("circa 2 h 36 min", substring = true)).assertCountEquals(2)
    composeRule.onNodeWithText("In 1 notte, con le regole di adesso.", substring = true).assertExists()
    composeRule.onNodeWithText("Tutto sul Wi-Fi", substring = true).assertExists()
  }

  @Test
  fun lePartenzeSonoDueEOgnunaHaLaSuaStima() {
    showSheet(BackupSamples.twoEstimatesSummary)
    composeRule.onNodeWithText("Parte subito; 15", substring = true).assertExists()
    composeRule.onNodeWithText("circa 2 h 15 min", substring = true).assertExists()
    composeRule.onNodeWithText("Sui dati mobili chiedo conferma con la stima.").assertExists()
    composeRule.onNodeWithText("Meglio in carica e sul Wi-Fi di casa.").assertExists()
  }

  @Test
  fun seLeRegoleNonLasciano_PartireIlBackup_ILFoglioLoDice() {
    showSheet(BackupSamples.neverFinishesSummary)
    composeRule.onNodeWithText("Con le regole di adesso il backup non parte: controlla le attività.").assertExists()
    composeRule.onNodeWithText("Con le regole di adesso il backup non parte.", substring = true).assertExists()
    assertThat(composeRule.onAllNodes(hasText("0 notti", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun senzaStimaDiceChiaramenteCheNonCe() {
    showSheet(BackupSamples.firstBackupSummary.copy(estimate = null, estimateNow = null))
    composeRule.onNodeWithText("La stima arriva quando ho finito di confrontare.").assertExists()
  }

  @Test
  fun leDueScelteChiamanoLaPortaUnaVoltaAlloSteso() {
    showSheet(BackupSamples.firstBackupSummary)
    button("Stanotte").performClick()
    assertThat(tonight).isEqualTo(1)
    assertThat(now).isEqualTo(0)
    button("Inizia ora").performClick()
    assertThat(now).isEqualTo(1)
    button("Non ora").performClick()
    assertThat(later).isEqualTo(1)
    assertThat(tonight).isEqualTo(1)
  }

  @Test
  fun fincheSiCercaSulNasIlFoglioDicePerche_ELeScelteAspettano() {
    showSheet(BackupSamples.firstBackupSummary.copy(existing = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)))
    composeRule.onNodeWithText("Sto cercando cosa c’è già sul NAS…").assertExists()
    composeRule.onNodeWithText("I numeri sono provvisori finché non ho finito.").assertExists()
    button("Stanotte").assertIsNotEnabled()
    button("Inizia ora").assertIsNotEnabled()
    button("Stanotte").performClick()
    assertThat(tonight).isEqualTo(0)
    // Chiudere si puo' sempre.
    button("Non ora").performClick()
    assertThat(later).isEqualTo(1)
  }

  @Test
  fun poiIlConfrontoDelleCopieSimiliDiceQuanteFoto() {
    showSheet(BackupSamples.comparingSummary)
    composeRule.onNodeWithText("Sto confrontando 120 foto con le copie sul NAS…").assertExists()
    button("Stanotte").assertIsNotEnabled()
  }

  @Test
  fun gliAvvisiSiDiconoNelloStessoOrdineDelContratto() {
    val all = BackupSamples.firstBackupSummary.copy(warnings = SummaryWarning.entries.toSet())
    showSheet(all)
    for (text in listOf(
      "Sui dati mobili chiedo conferma con la stima.",
      "Meglio in carica e sul Wi-Fi di casa.",
      "La memoria del telefono è quasi piena.",
      "Hai dato accesso solo ad alcune foto: si salvano solo quelle.",
      "Alcune foto aspettano l’accesso alla posizione.",
      "Con le regole di adesso servono più notti.",
    )) {
      composeRule.onNodeWithText(text).assertExists()
    }
  }

  @Test
  fun perSorgenteDiceDaCaricare() {
    showSheet(BackupSamples.firstBackupSummary)
    composeRule.onNodeWithText("Fotocamera").assertExists()
    composeRule.onNodeWithText("Screenshot").assertExists()
    composeRule.onNodeWithText("Da caricare, per sorgente").assertExists()
  }

  @Test
  fun unRiepilogoVuotoNonRompeNiente() {
    showSheet(BackupSummary.EMPTY.copy(estimate = BackupEstimate(0, 0, 0, 0, null)))
    composeRule.onNodeWithText("Primo backup").assertExists()
    composeRule.onNodeWithText("0 nuovi", substring = true).assertExists()
  }

  @Test
  fun aUnoETreDecimiIBottoniRestanoDa48DpEDentroLoSchermo() {
    showSheet(BackupSamples.twoEstimatesSummary, fontScale = LARGE_TEXT)
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val width = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
    val buttons = clickable()
    assertThat(buttons).hasSize(3)
    for (node in buttons) {
      val b = node.boundsInRoot
      assertWithMessage("bottone ${node.name()} $b").that(b.height).isAtLeast(min - 1f)
      assertWithMessage("bottone ${node.name()} dentro lo schermo").that(b.left >= 0f && b.right <= width + 1f).isTrue()
    }
    // Uno sopra l'altro, mai sovrapposti.
    val ordered = buttons.sortedBy { it.boundsInRoot.top }
    for (i in 0 until ordered.size - 1) assertThat(ordered[i].boundsInRoot.bottom).isAtMost(ordered[i + 1].boundsInRoot.top + 1f)
  }

  @Test
  fun unNumeroPuoEssereZeroSenzaRompereIlTotale() {
    val summary = BackupSamples.firstBackupSummary.copy(similar = SummaryCount.ZERO)
    showSheet(summary)
    composeRule.onNodeWithText("0 con una copia simile sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("5.106 · ", substring = true).assertExists()
  }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onRoot() = onNode(androidx.compose.ui.test.isRoot())
