package dev.pampa.mycloudgallery.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewLevel
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualitySettings
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualityState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * F12 delle prove sul campo (S25, 22/10):
 *  - O-B17: dopo il passaggio ad "Alta" dalle Impostazioni la Diagnostica diceva ancora `ACT_DISABLED` per `HQ_PREVIEWS`, perche' le regole le
 *    spegneva o accendeva solo l'onboarding;
 *  - O-B16: la pagina diceva "sto adattando lo spazio" ma la convergenza aspettava il giro periodico di 6 ore.
 */
class ConvergingPreviewQualityTest {

  private class MemoryRules(initial: RuleSettings) : RuleSettingsStore {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<RuleSettings> = state.map { it }
    override suspend fun current(): RuleSettings = state.value
    override suspend fun save(settings: RuleSettings) {
      state.value = settings
    }
    override suspend fun update(transform: (RuleSettings) -> RuleSettings) {
      state.value = transform(state.value)
    }
  }

  private class Recording : PreviewQualitySettings {
    val chosen = mutableListOf<PreviewLevel>()
    override val state: StateFlow<PreviewQualityState?> = MutableStateFlow(null)
    override suspend fun choose(level: PreviewLevel) {
      chosen += level
    }
    override suspend fun showAlbumCovers(show: Boolean) = Unit
  }

  private fun settingsAtStandard() = RuleSettings(preset = Preset.BALANCED, disabledActivities = setOf(BackgroundActivity.HQ_PREVIEWS, BackgroundActivity.PREPARATION))

  private fun isEnabled(settings: RuleSettings, activity: BackgroundActivity): Boolean = settings.toRuleSet().rules[activity]?.enabled == true

  @Test
  fun o_b17_ilPassaggioAdAltaAccendeLeAnteprimeHqEIlPassaggioIndietroLeSpegne() = runTest {
    val rules = MemoryRules(settingsAtStandard())
    val inner = Recording()
    val quality = ConvergingPreviewQuality(inner, rules, started = { true }) { }
    assertThat(isEnabled(rules.current(), BackgroundActivity.HQ_PREVIEWS)).isFalse()

    quality.choose(PreviewLevel.HIGH)

    assertThat(inner.chosen).containsExactly(PreviewLevel.HIGH)
    assertThat(BackgroundActivity.HQ_PREVIEWS in rules.current().disabledActivities).isFalse()
    assertThat(isEnabled(rules.current(), BackgroundActivity.HQ_PREVIEWS)).isTrue()

    quality.choose(PreviewLevel.STANDARD)
    assertThat(BackgroundActivity.HQ_PREVIEWS in rules.current().disabledActivities).isTrue()
    assertThat(isEnabled(rules.current(), BackgroundActivity.HQ_PREVIEWS)).isFalse()
    quality.choose(PreviewLevel.HIGH)
    quality.choose(PreviewLevel.SAVER)
    assertThat(BackgroundActivity.HQ_PREVIEWS in rules.current().disabledActivities).isTrue()
  }

  @Test
  fun o_b17_ilResto_delleRegoleNonSiTocca() = runTest {
    val rules = MemoryRules(settingsAtStandard().copy(batteryThresholdPercent = 40, allowRoaming = true))
    val quality = ConvergingPreviewQuality(Recording(), rules, started = { true }) { }

    quality.choose(PreviewLevel.HIGH)

    val after = rules.current()
    assertThat(after.preset).isEqualTo(Preset.BALANCED)
    assertThat(after.batteryThresholdPercent).isEqualTo(40)
    assertThat(after.allowRoaming).isTrue()
    // Un'altra attivita' spenta dall'utente resta spenta.
    assertThat(after.disabledActivities).containsExactly(BackgroundActivity.PREPARATION)
  }

  @Test
  fun o_b16_ognuCambioDiQualitaAccodaSubitoLaConvergenzaConIlLivelloNuovo() = runTest {
    val asked = mutableListOf<QualityLevel>()
    val quality = ConvergingPreviewQuality(Recording(), MemoryRules(settingsAtStandard()), started = { true }) { asked += it }

    quality.choose(PreviewLevel.HIGH)
    quality.choose(PreviewLevel.SAVER)
    quality.choose(PreviewLevel.STANDARD)

    assertThat(asked).containsExactly(QualityLevel.HIGH, QualityLevel.SAVER, QualityLevel.STANDARD).inOrder()
  }

  @Test
  fun senzaIlNasNellaLibreriaPartitoNonCambiaNienteDelleRegoleNeAccodaNiente() = runTest {
    val rules = MemoryRules(settingsAtStandard())
    val inner = Recording()
    var asked = 0
    val quality = ConvergingPreviewQuality(inner, rules, started = { false }) { asked++ }

    quality.choose(PreviewLevel.HIGH)

    assertThat(inner.chosen).containsExactly(PreviewLevel.HIGH)
    assertThat(rules.current()).isEqualTo(settingsAtStandard())
    assertThat(asked).isEqualTo(0)
  }
}
