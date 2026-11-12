package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
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
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
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

  /**
   * Fase I (BF-2): prima di partire **nessuna durata** (il modello di serie era ottimista di circa tre volte): la frase in cima dice quanto c'e' da caricare,
   * la stima dice il traffico, non il tempo, ne' le notti, ne' la batteria.
   */
  @Test
  fun ilFoglioDiceLeQuantitaNonUnaDurata() {
    showSheet(BackupSamples.firstBackupSummary)
    composeRule.onNodeWithText("Da caricare: 5.418 foto e video", substring = true).assertExists()
    composeRule.onNodeWithText("Tutto sul Wi-Fi", substring = true).assertExists()
    composeRule.onAllNodes(hasText("circa", substring = true)).assertCountEquals(0)
    // Ne' le notti («In 1 notte», «In circa 2 notti»; il tasto «Stanotte» resta).
    composeRule.onAllNodes(hasText("1 notte", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("notti", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("Batteria")).assertCountEquals(0)
  }

  /**
   * Fase I (BF-2), i numeri del telefono del 12/11: 5.559 gia' sul NAS (37 GB da rileggere), 1.546 nuovi (3,0 GB), il modello di serie che diceva «Stanotte: circa
   * 50 min» per oltre due ore di lavoro vero. Il foglio dice quanto si carica e che i gia' sul NAS si rileggono, nessun numero di minuti.
   */
  @Test
  fun iNumeriDelTelefonoSenzaIlNumeroOttimista() {
    showSheet(BackupSamples.phoneSummary)
    composeRule.onNodeWithText("Da caricare: 1.546 foto e video", substring = true).assertExists()
    composeRule.onNodeWithText("5.559 già sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("si rileggono dal NAS per controllarli", substring = true).assertExists()
    composeRule.onAllNodes(hasText("50 min", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("48 min", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("circa", substring = true)).assertCountEquals(0)
  }

  @Test
  fun iDatiMobiliDiInizia_OraSiDiconoSenzaDurata() {
    showSheet(BackupSamples.twoEstimatesSummary)
    composeRule.onNodeWithText("Parte subito; 15", substring = true).assertExists()
    composeRule.onAllNodes(hasText("circa 2 h 15 min", substring = true)).assertCountEquals(0)
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
  fun senzaStimaRestaIlTotaleDaCaricare() {
    showSheet(BackupSamples.firstBackupSummary.copy(estimate = null, estimateNow = null))
    composeRule.onNodeWithText("5.418 · ", substring = true).assertExists()
    composeRule.onAllNodes(hasText("Traffico")).assertCountEquals(0)
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
    // Fase I (12/11): niente «Sto ancora contando»: una riga che dice che cosa fa, e i numeri arrivano a ricerca finita. Qui la scansione non gira ancora
    // (BF-4): la riga lo dice («Inizia fra poco»), senza barra.
    composeRule.onNodeWithText("Prima guardo cosa c’è già sul NAS.").assertExists()
    composeRule.onNodeWithText("Cerco cosa c’è già sul NAS").assertExists()
    composeRule.onNodeWithText("Inizia fra poco.").assertExists()
    composeRule.onAllNodes(hasText("Da caricare", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("Sto ancora contando", substring = true)).assertCountEquals(0)
    button("Stanotte").assertIsNotEnabled()
    button("Inizia ora").assertIsNotEnabled()
    button("Stanotte").performClick()
    assertThat(tonight).isEqualTo(0)
    // Chiudere si puo' sempre.
    button("Non ora").performClick()
    assertThat(later).isEqualTo(1)
  }

  /** Fase I: la ricerca dice quanti file del NAS ha gia' guardato. */
  @Test
  fun laRicercaDiceQuantiFileHaGuardato() {
    showSheet(BackupSamples.searchingSummary)
    // BF-12 (parole): il numero e' la cosa da leggere, le parole la didascalia sotto.
    composeRule.onNodeWithText("1.234 di 5.559").assertExists()
    composeRule.onNodeWithText("file del NAS guardati").assertExists()
  }

  @Test
  fun senzaIlTotaleLaRicercaDiceSoloQuantiNeHaGuardati() {
    showSheet(BackupSamples.searchingSummary.copy(existing = BackupSamples.searchingSummary.existing.copy(scanTotal = null)))
    composeRule.onNodeWithText("1.234").assertExists()
    composeRule.onNodeWithText("file del NAS guardati").assertExists()
  }

  /**
   * Fase I (punto 5, BF-8): ferma per una regola, la riga dice quale (la formula comune, quella della Diagnostica) e che riprende da sola (il caldo riprende
   * davvero da solo: la ripresa a tempo dei lavori del NAS), non «cerco». Il testo dato a `heldBy`: chi sceglie il motivo lo prova `ExistingCopiesLiveTest`.
   */
  @Test
  fun laRicercaFermaPerIlCaldoDiceIlMotivoEChe_RiprendeDaSola() {
    showSheet(BackupSamples.heldSummary)
    composeRule.onNodeWithText("Il telefono è troppo caldo").assertExists()
    composeRule.onNodeWithText("La ricerca riprende da sola.").assertExists()
    composeRule.onAllNodes(hasText("Cerco cosa c’è già sul NAS")).assertCountEquals(0)
    button("Stanotte").assertIsNotEnabled()
  }

  /**
   * Fase I (12/11, dal telefono dell'utente): a ricerca finita le scelte sono accese anche con copie simili da confrontare (si caricano comunque,
   * D36); i numeri ci sono subito e una riga piccola dice quante foto restano da confrontare, senza fermare niente.
   */
  @Test
  fun poiIlConfrontoDelleCopieSimiliNonFermaLeScelte() {
    showSheet(BackupSamples.comparingSummary)
    composeRule.onNodeWithText("Ancora 120 foto da confrontare").assertExists()
    composeRule.onNodeWithText("Continua da solo. Si carica lo stesso.").assertExists()
    composeRule.onAllNodes(hasText("Da caricare:", substring = true)).assertCountEquals(1)
    button("Stanotte").assertIsEnabled()
    button("Inizia ora").assertIsEnabled()
    button("Stanotte").performClick()
    assertThat(tonight).isEqualTo(1)
  }

  /** BS-3: il motivo del confronto e' quello delle anteprime del NAS (la riga lo attribuisce a loro); le scelte restano accese. */
  @Test
  fun ilConfrontoFermoDiceCheAspettanoLeAnteprime() {
    showSheet(BackupSamples.comparingSummary.copy(existing = BackupSamples.comparingSummary.existing.copy(heldBy = WaitReason.THERMAL)))
    composeRule.onNodeWithText("Il telefono è troppo caldo · le anteprime del NAS aspettano e riprendono da sole. Si carica lo stesso.").assertExists()
    button("Inizia ora").assertIsEnabled()
  }

  /** BF-9: per la rete, il NAS spento e il tetto dei dati si dice la condizione e basta, nessun «riprende da sola». */
  @Test
  fun laReteNonPromette_RiprendeDaSola() {
    showSheet(BackupSamples.heldSummary.copy(existing = BackupSamples.heldSummary.existing.copy(heldBy = WaitReason.HOME_WIFI)))
    composeRule.onNodeWithText("In attesa del Wi-Fi di casa").assertExists()
    composeRule.onAllNodes(hasText("riprende", substring = true)).assertCountEquals(0)
  }

  /** BF-9: anche il tetto dei dati (non riprende da solo fino al mese dopo). */
  @Test
  fun ilTettoDeiDatiNonPromette_RiprendeDaSola() {
    showSheet(BackupSamples.heldSummary.copy(existing = BackupSamples.heldSummary.existing.copy(heldBy = WaitReason.DATA_CAP)))
    composeRule.onNodeWithText("Tetto dati del mese raggiunto").assertExists()
    composeRule.onAllNodes(hasText("riprende", substring = true)).assertCountEquals(0)
  }

  /** BF-4: un motivo che il backup non nomina (congelamento, attivita' spenta…): «in pausa» e la Diagnostica, mai «cerco… inizia fra poco». */
  @Test
  fun unMotivoSenzaNomeDiceCheEFermo() {
    showSheet(BackupSamples.heldSummary.copy(existing = BackupSamples.heldSummary.existing.copy(heldBy = WaitReason.NEXT_RUN)))
    composeRule.onNodeWithText("Ricerca in pausa").assertExists()
    composeRule.onNodeWithText("Il motivo è nella Diagnostica.").assertExists()
    composeRule.onAllNodes(hasText("Cerco", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("Inizia fra poco", substring = true)).assertCountEquals(0)
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
