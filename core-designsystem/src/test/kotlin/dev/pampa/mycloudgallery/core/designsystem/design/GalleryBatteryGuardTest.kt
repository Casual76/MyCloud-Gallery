package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeaderDefaults
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.designsystem.state.LocalBatteryThreshold
import dev.pampa.mycloudgallery.core.designsystem.state.batteryWaitText
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pacchetto B: il controllo del livello di batteria. La logica (passi di 5, limiti, lo stato dai due interruttori) e' pura; il resto si prova a schermo: le frasi di
 * stato dette una volta sola, i due interruttori, il livello fuori griglia mostrato sul passo piu' vicino, il controllo spento che non risponde.
 */
@RunWith(AndroidJUnit4::class)
class GalleryBatteryGuardTest {

  @get:Rule
  val composeRule = createComposeRule()

  // --- La logica ----------------------------------------------------------------------------------------

  @Test
  fun iPassiVannoDa5A80DiCinqueInCinque() {
    assertThat(BatteryGuard.levels).containsExactly(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80).inOrder()
    assertThat(BatteryGuard.DEFAULT_PERCENT).isEqualTo(20)
    assertThat(BatteryGuard.DEFAULT_PERCENT in BatteryGuard.levels).isTrue()
  }

  @Test
  fun snap_vaAlPassoPiuVicino_eRestaNeiLimiti() {
    val expected = mapOf(
      -10 to 5, 0 to 5, 2 to 5, 5 to 5, 7 to 5, 8 to 10, 20 to 20, 22 to 20, 23 to 25, 33 to 35, 78 to 80, 80 to 80, 81 to 80, 100 to 80, 400 to 80,
    )
    for ((input, output) in expected) assertThat(BatteryGuard.snap(input)).isEqualTo(output)
  }

  @Test
  fun ilCursore_dalFloatAlPasso() {
    assertThat(BatteryGuard.fromSlider(4.9f)).isEqualTo(5)
    assertThat(BatteryGuard.fromSlider(17.4f)).isEqualTo(15)
    assertThat(BatteryGuard.fromSlider(17.6f)).isEqualTo(20)
    assertThat(BatteryGuard.fromSlider(80f)).isEqualTo(80)
    assertThat(BatteryGuard.fromSlider(95f)).isEqualTo(80)
  }

  @Test
  fun lo_stato_vieneDaiDueInterruttori() {
    assertThat(BatteryGuard.stateOf(forBackup = true, forAnalysis = true)).isEqualTo(BatteryGuardState.BOTH)
    assertThat(BatteryGuard.stateOf(forBackup = true, forAnalysis = false)).isEqualTo(BatteryGuardState.BACKUP_ONLY)
    assertThat(BatteryGuard.stateOf(forBackup = false, forAnalysis = true)).isEqualTo(BatteryGuardState.ANALYSIS_ONLY)
    assertThat(BatteryGuard.stateOf(forBackup = false, forAnalysis = false)).isEqualTo(BatteryGuardState.NONE)
  }

  @Test
  fun ilValoreDiSerieE_ilLivelloDiPrima_conTuttoAcceso() {
    assertThat(BatteryGuardValue()).isEqualTo(BatteryGuardValue(levelPercent = 20, forBackup = true, forAnalysis = true))
  }

  // --- A schermo ----------------------------------------------------------------------------------------

  private fun show(
    level: Int = 20,
    forBackup: Boolean = true,
    forAnalysis: Boolean = true,
    enabled: Boolean = true,
    onBackup: (Boolean) -> Unit = {},
    onAnalysis: (Boolean) -> Unit = {},
    loading: Boolean = false,
    saveFailed: Boolean = false,
    backupOnBattery: Boolean = true,
    analysisOnBattery: Boolean = true,
    fontScale: Float = 1f,
    backupNeedsPower: Boolean = false,
    backupEnabled: Boolean = true,
    analysisEnabled: Boolean = true,
    noteInset: Dp = Dp.Unspecified,
  ) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        androidx.compose.runtime.CompositionLocalProvider(
          androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale),
        ) {
          GalleryBatteryGuard(
            value = if (loading) null else BatteryGuardValue(level, forBackup, forAnalysis, backupOnBattery, analysisOnBattery, backupNeedsPower, backupEnabled, analysisEnabled),
            onLevelChange = {},
            onForBackupChange = onBackup,
            onForAnalysisChange = onAnalysis,
            enabled = enabled,
            saveResult = if (saveFailed) BatterySaveResult(id = 1, level = 60, ok = false) else null,
            noteInset = noteInset,
          )
        }
      }
    }
  }

  @Test
  fun conTuttoAcceso_dice_unaVoltaSola_chiSiFermaEIlNumero() {
    show(level = 20)

    composeRule.onNodeWithText("20%", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Sotto questo livello si fermano backup e analisi", useUnmergedTree = true).assertExists()
  }

  @Test
  fun leFrasiDiStato_sonoQuattro_unaPerOgniCombinazione() {
    show(level = 60, forBackup = true, forAnalysis = false)
    composeRule.onNodeWithText("Sotto questo livello si ferma il backup", useUnmergedTree = true).assertExists()
  }

  @Test
  fun soloAnalisi_dice_chiSiFerma() {
    show(level = 35, forBackup = false, forAnalysis = true)
    composeRule.onNodeWithText("Sotto questo livello si ferma l’analisi", useUnmergedTree = true).assertExists()
  }

  @Test
  fun tuttiESpenti_dice_chenonSiFermaNiente() {
    show(forBackup = false, forAnalysis = false)
    composeRule.onNodeWithText("Backup e analisi non si fermano", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bs4_unLivelloGiaSalvatoFuoriGriglia_siMostraCosiCom_e() {
    show(level = 33)
    composeRule.onNodeWithText("33%", useUnmergedTree = true).assertExists()
    assertThat(runCatching { composeRule.onNodeWithText("35%", useUnmergedTree = true).assertExists() }.isFailure).isTrue()
  }

  @Test
  fun bs4_zeroECento_siMostranoVeri_ConUnaFraseSensata() {
    show(level = 0)
    composeRule.onNodeWithText("0%", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Non si ferma niente", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bs4_cento_siMostraCento() {
    show(level = 100)
    composeRule.onNodeWithText("100%", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("A batteria non parte niente", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bs5_conIlValoreNonAncoraLetto_siVedeLoScheletro_nonUnVentiPerCentoFinto() {
    show(loading = true)
    assertThat(runCatching { composeRule.onNodeWithText("20%", useUnmergedTree = true).assertExists() }.isFailure).isTrue()
    assertThat(runCatching { composeRule.onNodeWithText("Ferma il backup", useUnmergedTree = true).assertExists() }.isFailure).isTrue()
  }

  @Test
  fun bs5_unSalvataggioNonRiuscito_loDiceConLaFraseDelleAltreImpostazioni() {
    show(saveFailed = true)
    composeRule.onNodeWithText("Non sono riuscito a salvare la scelta", substring = true, useUnmergedTree = true).assertExists()
  }

  @Test
  fun bs5_senzaErrore_nonDiceNiente() {
    show(saveFailed = false)
    assertThat(runCatching { composeRule.onNodeWithText("Non sono riuscito a salvare la scelta", substring = true, useUnmergedTree = true).assertExists() }.isFailure).isTrue()
  }

  // --- Il gesto sul cursore (BS-4, BS-5): puro, senza grafica ------------------------------------------------------

  @Test
  fun gesto_unRiscontroVecchio_nonAzzeraIlDitoCheETornatoSulCursore() {
    val g = BatteryLevelGesture(saved = 20)
    g.onDrag(40)
    assertThat(g.onRelease()).isEqualTo(40) // si scrive 40, in volo
    g.onDrag(60) // il dito e' di nuovo sul cursore prima del riscontro
    g.onSaved(40) // arriva il riscontro della scelta precedente
    assertThat(g.shown).isEqualTo(60)
    assertThat(g.onRelease()).isEqualTo(60)
    g.onSaved(60)
    assertThat(g.shown).isEqualTo(60)
  }

  @Test
  fun gesto_dueRilasciRavvicinati_siFermaSullUltimo_ancheSeIlPrimoRiscontroArrivaDopo() {
    val g = BatteryLevelGesture(saved = 20)
    g.onDrag(40)
    g.onRelease()
    g.onDrag(60)
    g.onRelease() // due scelte in volo: 40 e 60
    g.onSaved(40) // il riscontro del primo
    assertThat(g.shown).isEqualTo(60) // la scelta in volo e' l'ultima: non torna a 40
    g.onSaved(60)
    assertThat(g.shown).isEqualTo(60)
  }

  @Test
  fun gesto_unSalvataggioNonRiuscito_riportaAlValoreSalvato() {
    val g = BatteryLevelGesture(saved = 20)
    g.onDrag(60)
    assertThat(g.onRelease()).isEqualTo(60)
    assertThat(g.shown).isEqualTo(60)
    g.onFailed(60)
    assertThat(g.shown).isEqualTo(20)
  }

  // --- BS2-2: l'esito e' per richiesta -----------------------------------------------------------------------------

  @Test
  fun bs2_dueFallimentiDiFila_riportanoTutteEDueLeVolteAlSalvato_ancheConLoStessoLivello() {
    val g = BatteryLevelGesture(saved = 20)
    // Prima richiesta: 60, rifiutata subito.
    g.onDrag(60)
    assertThat(g.onRelease()).isEqualTo(60)
    g.onFailed(60)
    assertThat(g.shown).isEqualTo(20)
    // Il genitore riprova: 70, rifiutata subito.
    g.onDrag(70)
    assertThat(g.onRelease()).isEqualTo(70)
    g.onFailed(70)
    assertThat(g.shown).isEqualTo(20)
    // E ancora 70, rifiutata: lo stesso livello due volte di fila torna comunque al salvato.
    g.onDrag(70)
    assertThat(g.onRelease()).isEqualTo(70)
    g.onFailed(70)
    assertThat(g.shown).isEqualTo(20)
  }

  @Test
  fun bs2_dueEsitiInRitardo_unFallimentoVecchioNonToccaLaScelta_piuNuova() {
    val g = BatteryLevelGesture(saved = 20)
    g.onDrag(60)
    g.onRelease() // richiesta A: 60, in volo
    g.onDrag(70)
    g.onRelease() // richiesta B: 70, in volo
    // L'esito di A (fallito) arriva dopo: la scelta ancora in volo e' B, non si tocca.
    g.onFailed(60)
    assertThat(g.shown).isEqualTo(70)
    // L'esito di B (fallito): ora si torna al salvato.
    g.onFailed(70)
    assertThat(g.shown).isEqualTo(20)
  }

  @Test
  fun bs2_unFallimentoDiUnInterruttore_nonPortaIlLivelloIndietro() {
    val g = BatteryLevelGesture(saved = 20)
    g.onDrag(60)
    g.onRelease()
    g.onFailed(null)
    assertThat(g.shown).isEqualTo(60)
  }

  @Test
  fun bs2_gliEsitiDiDueRichieste_nonSonoMaiUguali_ancheSeIlLivelloEUguale() {
    assertThat(BatterySaveResult(id = 1, level = 70, ok = false)).isNotEqualTo(BatterySaveResult(id = 2, level = 70, ok = false))
  }

  @Test
  fun gesto_unFallimentoMentreIlDitoEDiNuovoSulCursore_nonLoToglie() {
    val g = BatteryLevelGesture(saved = 20)
    g.onDrag(60)
    g.onRelease()
    g.onDrag(70)
    g.onFailed(60)
    assertThat(g.shown).isEqualTo(70)
    assertThat(g.onRelease()).isEqualTo(70)
  }

  @Test
  fun gesto_rilasciareSulLivelloSalvato_nonScriveNiente() {
    val g = BatteryLevelGesture(saved = 33)
    g.onDrag(35)
    g.onDrag(33)
    assertThat(g.onRelease()).isNull()
    assertThat(g.shown).isEqualTo(33)
  }

  @Test
  fun gesto_ilLivelloVeroSalvatoFuoriGriglia_restaFinoAUnaScelta() {
    val g = BatteryLevelGesture(saved = 33)
    assertThat(g.shown).isEqualTo(33)
    g.onSaved(33)
    assertThat(g.shown).isEqualTo(33)
    g.onSaved(0)
    assertThat(g.shown).isEqualTo(0)
  }

  @Test
  fun gesto_ilSalvatoCambiaDaFuori_senzaDito_siSegue() {
    val g = BatteryLevelGesture(saved = 20)
    g.onSaved(45) // l'altra pagina ha scelto 45
    assertThat(g.shown).isEqualTo(45)
  }

  @Test
  fun unTocco_suBackup_porta_ilNuovoValore() {
    val seen = mutableListOf<Boolean>()
    show(forBackup = true, onBackup = { seen += it })

    composeRule.onNodeWithText("Ferma il backup", useUnmergedTree = true).performClick()

    assertThat(seen).containsExactly(false)
  }

  @Test
  fun unTocco_suAnalisiDelleFoto_porta_ilNuovoValore() {
    val seen = mutableListOf<Boolean>()
    show(forAnalysis = false, onAnalysis = { seen += it })

    composeRule.onNodeWithText("Ferma l’analisi", useUnmergedTree = true).performClick()

    assertThat(seen).containsExactly(true)
  }

  @Test
  fun controlloSpento_nonRisponde() {
    val seen = mutableListOf<Boolean>()
    show(enabled = false, onBackup = { seen += it })

    composeRule.onNodeWithText("Ferma il backup", useUnmergedTree = true).performClick()

    assertThat(seen).isEmpty()
  }

  // --- La frase di «fermo per la batteria» --------------------------------------------------------------

  @Test
  fun laFraseDelFermo_senzaNumero_eVeraAQualunqueLivello() {
    composeRule.setContent { MyCloudGalleryTheme(settings = EngineSettings()) { Text(batteryWaitText()) } }
    composeRule.onNodeWithText("Batteria sotto la soglia").assertExists()
  }

  @Test
  fun laFraseDelFermo_colNumero_dicePropriolIlLivelloScelto() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) { CompositionLocalProvider(LocalBatteryThreshold provides 60) { Text(batteryWaitText()) } }
    }
    composeRule.onNodeWithText("Batteria sotto il 60%").assertExists()
  }

  // --- BU-3: chi non puo' lavorare a batteria dice «Solo in carica» ------------------------------------------------

  @Test
  fun bu3_unGruppoCheLavoraSoloInCarica_dice_SoloInCarica_enonHaUnInterruttore() {
    val seen = mutableListOf<Boolean>()
    show(analysisOnBattery = false, onAnalysis = { seen += it })

    composeRule.onNodeWithText("Solo in carica", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).performClick()
    assertThat(seen).isEmpty()
    assertThat(runCatching { composeRule.onNodeWithText("Ferma l’analisi", useUnmergedTree = true).assertExists() }.isFailure).isTrue()
    // L'altro gruppo ha il suo interruttore.
    composeRule.onNodeWithText("Ferma il backup", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bu3_laFrasiDiStato_contaSoloChiPuoLavorareABatteria() {
    // Backup e analisi accesi, ma l'analisi lavora solo in carica: sotto il livello si ferma solo il backup.
    show(analysisOnBattery = false)
    composeRule.onNodeWithText("Sotto questo livello si ferma il backup", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bu3_senzaNessunoCheLavoriABatteria_laFraseDiceSoloInCarica() {
    show(backupOnBattery = false, analysisOnBattery = false)
    composeRule.onNodeWithText("Lavorano solo in carica", useUnmergedTree = true).assertExists()
    // BU2-4: lo stato lo dice la frase, una volta; le due righe tengono nome e segno (le due parole non si ripetono) e per TalkBack dicono nome e stato.
    composeRule.onAllNodesWithText("Solo in carica", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onNodeWithContentDescription("Backup: Solo in carica").assertExists()
    composeRule.onNodeWithContentDescription("Analisi delle foto: Solo in carica").assertExists()
  }

  // --- BS3-1: spento dall'utente non e' «solo in carica» ---------------------------------------------------------

  @Test
  fun bs31_unaAttivitaSpenta_dice_Spento_enonSoloInCarica_enonHaUnInterruttore() {
    show(analysisOnBattery = false, analysisEnabled = false)

    composeRule.onNodeWithContentDescription("Analisi delle foto: Spento").assertExists()
    composeRule.onAllNodesWithText("Spento", useUnmergedTree = true).assertCountEquals(1)
    composeRule.onAllNodesWithText("Solo in carica", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Ferma l’analisi", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onNodeWithText("Ferma il backup", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Sotto questo livello si ferma il backup", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bs31_tuttiSpenti_nonDiconoSoloInCarica_ne_ChiSiFerma() {
    show(backupOnBattery = false, analysisOnBattery = false, backupEnabled = false, analysisEnabled = false)

    composeRule.onNodeWithText("Backup e analisi non si fermano", useUnmergedTree = true).assertExists()
    composeRule.onAllNodesWithText("Spento", useUnmergedTree = true).assertCountEquals(2)
    composeRule.onAllNodesWithText("Solo in carica", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun bs31_unoSpentoEUnoSoloInCarica_leDueParoleRestanoSulSoloInCarica() {
    show(backupOnBattery = false, analysisOnBattery = false, backupEnabled = false)

    composeRule.onNodeWithText("Lavorano solo in carica", useUnmergedTree = true).assertExists()
    composeRule.onAllNodesWithText("Spento", useUnmergedTree = true).assertCountEquals(1)
    composeRule.onAllNodesWithText("Solo in carica", useUnmergedTree = true).assertCountEquals(1)
  }

  @Test
  fun bu5_soloInCarica_aTestoGrande_stasottoIlNome_eAtestoNormaleStaAccanto() {
    show(analysisOnBattery = false, fontScale = 1.3f)
    val title = composeRule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val only = composeRule.onNodeWithText("Solo in carica", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertThat(only.top).isAtLeast(title.bottom - 1f)
  }

  @Test
  fun bu21_soloInCarica_stasempreSottoIlNome_ancheATestoNormale() {
    show(analysisOnBattery = false)
    val title = composeRule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val only = composeRule.onNodeWithText("Solo in carica", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertThat(only.top).isAtLeast(title.bottom - 1f)
  }

  @Test
  fun bu21_ilNomeNonSiSpezza_conSoloInCarica_aTestoNormale() {
    show(analysisOnBattery = false)
    val title = composeRule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val only = composeRule.onNodeWithText("Solo in carica", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    // Il nome sta su una riga sola: e' alto come la scritta sotto, non il doppio.
    assertThat(title.height).isLessThan(only.height * 1.5f)
  }

  // --- BU-5: la frase non si taglia, ne' a testo grande ---------------------------------------------------------

  @Test
  fun bu5_aTestoMoltoGrande_laFraseCEIntera() {
    show(fontScale = 2f)
    composeRule.onNodeWithText("Sotto questo livello si fermano backup e analisi", useUnmergedTree = true).assertExists()
  }

  // --- Giro 3: scheletro, allineamenti ------------------------------------------------------------------------------

  /** BU2-5 / BU2-9: l'avviso di salvataggio sta al rientro che gli passa la pagina (icona 18 dp e 6 dp di spazio), di serie quello di una nota sotto una scheda (12 dp). */
  @Test
  fun bu25_l_avvisoStaAlRientroChePassaLaPagina() {
    show(saveFailed = true, noteInset = 4.dp)

    val left = composeRule.onNodeWithText("Non sono riuscito", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot().left.value
    assertThat(left).isWithin(0.5f).of(4f + 18f + 6f)
  }

  @Test
  fun bu25_l_avvisoDiSerieStaAlRientroDiUnaNota() {
    show(saveFailed = true)

    val left = composeRule.onNodeWithText("Non sono riuscito", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot().left.value
    assertThat(left).isWithin(0.5f).of(12f + 18f + 6f)
  }

  /** BU2-9: il rientro che le pagine usano per allineare una nota al titolo di una sezione e' quello del titolo vero dell'engine. */
  @Test
  fun bu29_ilRientroDelTitoloDellaSezione_eQuelloDichiarato() {
    composeRule.setContent { MyCloudGalleryTheme(settings = EngineSettings()) { Box { GallerySectionHeader(title = "Batteria") } } }

    val left = composeRule.onNodeWithText("BATTERIA", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value
    assertThat(left).isWithin(0.5f).of(GallerySectionHeaderDefaults.TitleInset.value)
  }

  // --- BU-4: la pista ----------------------------------------------------------------------------------------

  @Test
  fun bu4_laPistaVaDa0A85_leTacche5_80_eIlValoreScrittoRestaNeiLimiti() {
    assertThat(BatteryGuard.TRACK_START).isEqualTo(0)
    assertThat(BatteryGuard.TRACK_END).isEqualTo(85)
    assertThat(BatteryGuard.levels.first()).isEqualTo(5)
    assertThat(BatteryGuard.levels.last()).isEqualTo(80)
    // Qualunque posizione del cursore, anche agli estremi della pista, si scrive entro 5..80.
    for (position in listOf(0f, 1f, 2.4f, 2.6f, 82.4f, 84f, 85f)) {
      assertThat(BatteryGuard.fromSlider(position)).isIn(BatteryGuard.levels)
    }
    assertThat(BatteryGuard.fromSlider(0f)).isEqualTo(5)
    assertThat(BatteryGuard.fromSlider(85f)).isEqualTo(80)
  }
}
