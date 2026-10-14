package dev.pampa.mycloudgallery.core.rules.estimate

import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings

/** Una riga della tabella dei preset: le stime di un preset sull'arretrato vero. */
data class PresetEstimateRow(val preset: Preset, val estimate: ConsumptionEstimate) {
  /** La frase per l'utente ([EstimateText]). */
  val summary: String get() = EstimateText.describe(estimate)
}

/**
 * La tabella dei preset calcolata sull'**arretrato vero** ([BacklogSource]) e non sulla libreria tipica dell'onboarding
 * (§2.8 di docs/fase4-progetto.md): per ogni preset batteria per notte e a regime, dati mobili e giorni per finire. Le
 * formule e le misure sono quelle di [DefaultConsumptionEstimator] e [DeviceMeasures.PHASE_0].
 *
 * Le anteprime HQ si scaricano solo con la qualita' Alta: con le altre, l'attivita' e' spenta e non pesa.
 */
class PresetEstimateTable(
  private val estimator: ConsumptionEstimator = DefaultConsumptionEstimator(),
  private val measures: DeviceMeasures = DeviceMeasures.PHASE_0,
  private val profile: UsageProfile = UsageProfile(),
) {

  suspend fun build(
    source: BacklogSource,
    highQuality: Boolean = false,
    night: TimeWindow = NightWindows.DEFAULT,
    presets: List<Preset> = DEFAULT_PRESETS,
  ): List<PresetEstimateRow> {
    val backlog = source.current()
    return presets.map { preset ->
      val settings =
        RuleSettings(
          preset = preset,
          nightWindow = night,
          disabledActivities = if (highQuality) emptySet() else setOf(BackgroundActivity.HQ_PREVIEWS),
        )
      PresetEstimateRow(preset, estimator.estimate(settings.toRuleSet(), backlog, measures, profile))
    }
  }

  companion object {
    /** I preset che si propongono all'onboarding; "Personalizzato" si regola dopo. */
    val DEFAULT_PRESETS: List<Preset> = listOf(Preset.NIGHT, Preset.BALANCED, Preset.WIFI_ALWAYS, Preset.MAXIMUM)
  }
}
