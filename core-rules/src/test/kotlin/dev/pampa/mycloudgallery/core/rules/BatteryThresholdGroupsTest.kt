package dev.pampa.mycloudgallery.core.rules

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.AI_COMPUTE
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.HQ_PREVIEWS
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.INDEX_SYNC
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PHOTO_BACKUP
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.PREPARATION
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity.VIDEO_BACKUP
import dev.pampa.mycloudgallery.core.rules.settings.DataStoreRuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.quickPhotosRuleSet
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pacchetto B (seconda beta): la soglia della batteria si sceglie per gruppo (backup, analisi delle foto). Il valutatore, le impostazioni e, soprattutto,
 * **«senza chiavi salvate le decisioni sono quelle di prima»** per tutte e sei le attivita'.
 */
class BatteryThresholdGroupsTest {

  @get:Rule val tmp = TemporaryFolder()

  private val evaluator = RuleEvaluator()
  private val daytime = at(12)

  private fun TestScope.dataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "rules.preferences_pb") }

  /** Le regole di "Wi-Fi sempre" (di giorno, anche a batteria, Wi-Fi di casa) con la soglia scelta dai due interruttori. */
  private fun rules(forBackup: Boolean, forAnalysis: Boolean, threshold: Int = 20): RuleSet =
    RuleSettings(
      preset = Preset.WIFI_ALWAYS,
      batteryThresholdPercent = threshold,
      batteryThresholdForBackup = forBackup,
      batteryThresholdForAnalysis = forAnalysis,
    ).toRuleSet()

  /** Il verdetto di prima del Pacchetto B, scritto a mano: a batteria e sotto la soglia aspetta, sempre, per tutte le attivita'. */
  private fun legacyBatteryBlocks(plugged: Boolean, level: Int, threshold: Int): Boolean = !plugged && level < threshold

  private fun blockedByBattery(decision: RuleDecision): Boolean = decision.blockingReason is WaitReason.BatteryLow

  // --- I gruppi ---------------------------------------------------------------------------------------------

  @Test
  fun iGruppi_sonoQuelliDellaTabella() {
    assertThat(BatteryGroups.BACKUP).containsExactly(PHOTO_BACKUP, VIDEO_BACKUP)
    assertThat(BatteryGroups.ANALYSIS).containsExactly(AI_COMPUTE)
    // Le tre che servono a tutti e due i gruppi non stanno in nessuno: seguono sempre il livello.
    val outside = BackgroundActivity.entries.toSet() - BatteryGroups.BACKUP - BatteryGroups.ANALYSIS
    assertThat(outside).containsExactly(INDEX_SYNC, PREPARATION, HQ_PREVIEWS)
  }

  @Test
  fun activitiesFor_tuttiEDueAccesi_sonoTutteLeSei() {
    assertThat(BatteryGroups.activitiesFor(forBackup = true, forAnalysis = true)).containsExactlyElementsIn(BackgroundActivity.entries)
  }

  // --- La matrice del valutatore -----------------------------------------------------------------------------

  @Test
  fun matrice_ogniAttivita_ogniInterruttore_cavoSiNo_sopraESottoIlLivello() {
    for (forBackup in listOf(true, false)) {
      for (forAnalysis in listOf(true, false)) {
        val ruleSet = rules(forBackup, forAnalysis)
        for (activity in BackgroundActivity.entries) {
          val applies = when (activity) {
            PHOTO_BACKUP, VIDEO_BACKUP -> forBackup
            AI_COMPUTE -> forAnalysis
            else -> true
          }
          for (plugged in listOf(true, false)) {
            for (level in listOf(5, 19, 20, 21, 80)) {
              val decision = evaluator.background(activity, ruleSet, state(daytime, plugged = plugged, level = level))
              val label = "$activity backup=$forBackup analisi=$forAnalysis cavo=$plugged livello=$level"
              assertWithMessage(label).that(blockedByBattery(decision)).isEqualTo(applies && !plugged && level < 20)
              // E quando la batteria non c'entra, il lavoro parte (Wi-Fi sempre, di giorno, Wi-Fi di casa, NAS acceso).
              if (!(applies && !plugged && level < 20)) assertWithMessage(label).that(decision.isRun).isTrue()
            }
          }
        }
      }
    }
  }

  @Test
  fun interruttoreSpento_nonBlocca_ilBackupPartePerFinoAlCinquePerCento() {
    val ruleSet = rules(forBackup = false, forAnalysis = true)

    for (activity in BatteryGroups.BACKUP) {
      assertThat(evaluator.background(activity, ruleSet, state(daytime, plugged = false, level = 5)).isRun).isTrue()
    }
    // L'analisi, accesa, continua a fermarsi.
    assertThat(blockedByBattery(evaluator.background(AI_COMPUTE, ruleSet, state(daytime, plugged = false, level = 5)))).isTrue()
  }

  @Test
  fun interruttoreSpento_nonBlocca_neppureIlPiuAltoDeiLivelli() {
    // Il livello e' alto (60) ma il gruppo e' escluso: telefono al 55% a batteria, il backup parte.
    val ruleSet = rules(forBackup = false, forAnalysis = true, threshold = 60)

    assertThat(evaluator.background(PHOTO_BACKUP, ruleSet, state(daytime, plugged = false, level = 55)).isRun).isTrue()
    assertThat(blockedByBattery(evaluator.background(AI_COMPUTE, ruleSet, state(daytime, plugged = false, level = 55)))).isTrue()
  }

  @Test
  fun cavoCollegato_lasogliaNonSiApplica_conQualunqueLivelloEInterruttore() {
    for (forBackup in listOf(true, false)) {
      for (forAnalysis in listOf(true, false)) {
        val ruleSet = rules(forBackup, forAnalysis, threshold = 80)
        for (activity in BackgroundActivity.entries) {
          val decision = evaluator.background(activity, ruleSet, state(daytime, plugged = true, level = 3))
          assertWithMessage("$activity backup=$forBackup analisi=$forAnalysis").that(blockedByBattery(decision)).isFalse()
        }
      }
    }
  }

  @Test
  fun lavoroInCorso_sottoIlLivello_siFermaSoloSeIlGruppoEAcceso() {
    val low = state(daytime, plugged = false, level = 12)

    val on = rules(forBackup = true, forAnalysis = true)
    val off = rules(forBackup = false, forAnalysis = false)

    for (activity in BatteryGroups.BACKUP + BatteryGroups.ANALYSIS) {
      val stopped = evaluator.background(activity, on, low, running = true)
      assertThat(stopped).isInstanceOf(RuleDecision.Stop::class.java)
      assertThat((stopped as RuleDecision.Stop).reason).isEqualTo(WaitReason.BatteryLow(12, 20))
      assertThat(evaluator.background(activity, off, low, running = true).isRun).isTrue()
    }
  }

  @Test
  fun leAttivitaFuoriDaiDueGruppi_seguonoSempreIlLivello_ancheConTuttiEDueSpenti() {
    val off = rules(forBackup = false, forAnalysis = false)

    for (activity in listOf(INDEX_SYNC, PREPARATION, HQ_PREVIEWS)) {
      val decision = evaluator.background(activity, off, state(daytime, plugged = false, level = 10))
      assertWithMessage("$activity").that(decision.blockingReason).isEqualTo(WaitReason.BatteryLow(10, 20))
    }
  }

  @Test
  fun leRichiesteDellUtente_nonGuardanoMaiLaSoglia_conQualunqueInterruttore() {
    // "Fai ora" / "Analizza adesso" (RuleEvaluator.userInitiated) non ha mai avuto il motivo della batteria: resta cosi'.
    for (forBackup in listOf(true, false)) {
      for (forAnalysis in listOf(true, false)) {
        val ruleSet = rules(forBackup, forAnalysis, threshold = 80)
        for (activity in BackgroundActivity.entries) {
          val decision = evaluator.evaluate(
            WorkRequest.UserInitiated(activity, confirmed = true),
            ruleSet,
            state(daytime, plugged = false, level = 3),
            usage(),
          )
          assertWithMessage("$activity backup=$forBackup analisi=$forAnalysis").that(decision.isRun).isTrue()
        }
      }
    }
  }

  // --- Senza chiavi = come oggi -------------------------------------------------------------------------------

  @Test
  fun senzaChiaviSalvate_ilValutatoreDiceQuelloDiPrima_perTutteESeiLeAttivita() = runTest {
    // Un DataStore vuoto, come dopo l'aggiornamento: nessuna chiave nuova. Il confronto e' con la regola di prima scritta a mano.
    val store = DataStoreRuleSettingsStore(dataStore())
    val settings = store.current()
    assertThat(settings.batteryThresholdForBackup).isTrue()
    assertThat(settings.batteryThresholdForAnalysis).isTrue()

    for (preset in Preset.entries) {
      val ruleSet = settings.copy(preset = preset).toRuleSet()
      assertThat(ruleSet.batteryThresholdActivities).containsExactlyElementsIn(BackgroundActivity.entries)
      for (activity in BackgroundActivity.entries) {
        for (plugged in listOf(true, false)) {
          for (level in 0..100 step 5) {
            val decision = evaluator.background(activity, ruleSet, state(daytime, plugged = plugged, level = level))
            assertWithMessage("$preset $activity cavo=$plugged livello=$level")
              .that(allReasons(decision).any { it is WaitReason.BatteryLow })
              .isEqualTo(legacyBatteryBlocks(plugged, level, threshold = 20))
          }
        }
      }
    }
  }

  /** Tutti i motivi di un'attesa (il primo e gli altri): la batteria puo' non essere la prima, con la fascia o la carica che bloccano prima. */
  private fun allReasons(decision: RuleDecision): List<WaitReason> = when (decision) {
    is RuleDecision.Wait -> listOf(decision.reason) + decision.alsoBlockedBy
    is RuleDecision.Stop -> listOf(decision.reason) + decision.alsoBlockedBy
    else -> emptyList()
  }

  @Test
  fun senzaChiavi_tuttiEDuePresettiESoglieSonoIdenticiAlRuleSetDiPrima() {
    // Il RuleSet costruito dalle impostazioni predefinite e' uguale, campo per campo, a quello del catalogo dei preset (che non conosce i gruppi).
    for (preset in Preset.entries) {
      assertThat(RuleSettings(preset = preset).toRuleSet()).isEqualTo(PresetCatalog.ruleSet(preset, NightWindows.DEFAULT))
    }
  }

  @Test
  fun laProtezioneRapidaDelleFotoNuove_portaConSeLaSceltaDeiGruppi() {
    // "Proteggi subito le foto nuove" parte da toRuleSet(): la soglia scelta per il backup vale anche per lei.
    val withBackupOff = RuleSettings(batteryThresholdForBackup = false).quickPhotosRuleSet()!!
    val withBackupOn = RuleSettings().quickPhotosRuleSet()!!

    assertThat(withBackupOff.batteryThresholdApplies(PHOTO_BACKUP)).isFalse()
    assertThat(withBackupOn.batteryThresholdApplies(PHOTO_BACKUP)).isTrue()
  }

  // --- Andata e ritorno delle impostazioni --------------------------------------------------------------------

  @Test
  fun gliInterruttori_siSalvanoESiRileggono_conIlLivello() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())
    val settings = RuleSettings(batteryThresholdPercent = 55, batteryThresholdForBackup = false, batteryThresholdForAnalysis = true)

    store.save(settings)

    val read = store.current()
    assertThat(read).isEqualTo(settings)
    assertThat(read.toRuleSet().batteryThresholdActivities).containsExactly(AI_COMPUTE, INDEX_SYNC, PREPARATION, HQ_PREVIEWS)
  }

  @Test
  fun update_cambiaUnInterruttoreSenzaToccareGliAltri() = runTest {
    val store = DataStoreRuleSettingsStore(dataStore())
    store.save(RuleSettings(preset = Preset.BALANCED, batteryThresholdPercent = 35))

    store.update { it.copy(batteryThresholdForAnalysis = false) }

    val now = store.current()
    assertThat(now.batteryThresholdForAnalysis).isFalse()
    assertThat(now.batteryThresholdForBackup).isTrue()
    assertThat(now.batteryThresholdPercent).isEqualTo(35)
    assertThat(now.preset).isEqualTo(Preset.BALANCED)
  }

  @Test
  fun valoriFuoriIntervallo_ripiegano_sulDefault() = runTest {
    val ds = dataStore()
    ds.edit { it[intPreferencesKey("rule.battery.threshold")] = -1 }
    assertThat(DataStoreRuleSettingsStore(ds).current().batteryThresholdPercent).isEqualTo(20)

    ds.edit { it[intPreferencesKey("rule.battery.threshold")] = 101 }
    assertThat(DataStoreRuleSettingsStore(ds).current().batteryThresholdPercent).isEqualTo(20)

    // I due interruttori sono booleani: una chiave presente vale quello che dice, una assente vale "acceso".
    ds.edit { it[booleanPreferencesKey("rule.battery.threshold.backup")] = false }
    val read = DataStoreRuleSettingsStore(ds).current()
    assertThat(read.batteryThresholdForBackup).isFalse()
    assertThat(read.batteryThresholdForAnalysis).isTrue()
  }

  @Test
  fun ilRuleSet_rifiutaUnLivelloFuoriDa0a100_comeSempre() {
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { RuleSettings(batteryThresholdPercent = 101) }
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { RuleSettings(batteryThresholdPercent = -1) }
  }
}
