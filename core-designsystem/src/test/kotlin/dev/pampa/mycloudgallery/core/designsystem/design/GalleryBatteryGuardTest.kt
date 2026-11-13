package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
  ) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        GalleryBatteryGuard(
          value = if (loading) null else BatteryGuardValue(level, forBackup, forAnalysis),
          onLevelChange = {},
          onForBackupChange = onBackup,
          onForAnalysisChange = onAnalysis,
          enabled = enabled,
          saveFailed = saveFailed,
        )
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
    composeRule.onNodeWithText("Con lo 0% non si ferma niente", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bs4_cento_siMostraCento() {
    show(level = 100)
    composeRule.onNodeWithText("100%", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Sotto questo livello si fermano backup e analisi", useUnmergedTree = true).assertExists()
  }

  @Test
  fun bs5_conIlValoreNonAncoraLetto_siVedeLoScheletro_nonUnVentiPerCentoFinto() {
    show(loading = true)
    assertThat(runCatching { composeRule.onNodeWithText("20%", useUnmergedTree = true).assertExists() }.isFailure).isTrue()
    assertThat(runCatching { composeRule.onNodeWithText("Backup", useUnmergedTree = true).assertExists() }.isFailure).isTrue()
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
    g.onFailed()
    assertThat(g.shown).isEqualTo(20)
  }

  @Test
  fun gesto_unFallimentoMentreIlDitoEDiNuovoSulCursore_nonLoToglie() {
    val g = BatteryLevelGesture(saved = 20)
    g.onDrag(60)
    g.onRelease()
    g.onDrag(70)
    g.onFailed()
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

    composeRule.onNodeWithText("Backup", useUnmergedTree = true).performClick()

    assertThat(seen).containsExactly(false)
  }

  @Test
  fun unTocco_suAnalisiDelleFoto_porta_ilNuovoValore() {
    val seen = mutableListOf<Boolean>()
    show(forAnalysis = false, onAnalysis = { seen += it })

    composeRule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).performClick()

    assertThat(seen).containsExactly(true)
  }

  @Test
  fun controlloSpento_nonRisponde() {
    val seen = mutableListOf<Boolean>()
    show(enabled = false, onBackup = { seen += it })

    composeRule.onNodeWithText("Backup", useUnmergedTree = true).performClick()

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
}
