package dev.pampa.mycloudgallery.feature.onboarding.steps

import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.estimate.DefaultConsumptionEstimator
import dev.pampa.mycloudgallery.core.rules.estimate.LibraryBacklog
import dev.pampa.mycloudgallery.core.rules.estimate.Workload
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings

/** Le tre stime che si mostrano per un preset (piano §4: batteria, dati mobili, tempo per arrivare in fondo). */
internal data class PresetEstimateUi(
  /** Batteria al giorno a regime, in percentuale. */
  val batteryPercentPerDay: Double,
  /** Dati mobili al mese, in byte (entro il tetto del preset). */
  val mobileBytesPerMonth: Long,
  /** Giorni per mettere al sicuro tutto l'arretrato; null = non finisce mai con queste regole. */
  val daysToFinish: Double?,
)

/**
 * Le stime dell'onboarding.
 *
 * Al passo 8 la Libreria del telefono non e' ancora stata letta (arriva in Fase 3): le stime si fanno su una
 * **libreria tipica** dichiarata qui (15.000 foto e 300 video da mettere al sicuro, qualche centinaio di media
 * nuovi al mese) e il testo lo dice ("su una libreria tipica"). Le formule sono quelle di `core-rules`
 * (`DefaultConsumptionEstimator`, misure di Fase 0): niente numeri inventati nella schermata.
 */
internal object PresetEstimates {

  private const val GB = 1_000_000_000L
  private const val MB = 1_000_000L

  /** 15.000 foto da 4 MB e 300 video da 100 MB da mettere al sicuro; 300 foto e 20 video nuovi al mese. */
  val TypicalBacklog: LibraryBacklog = LibraryBacklog(
    pending = mapOf(
      BackgroundActivity.PHOTO_BACKUP to Workload(items = 15_000, bytes = 60 * GB),
      BackgroundActivity.VIDEO_BACKUP to Workload(items = 300, bytes = 30 * GB),
      BackgroundActivity.INDEX_SYNC to Workload(items = 15_000, bytes = 1 * GB),
      BackgroundActivity.PREPARATION to Workload(items = 15_000, bytes = 15 * GB),
      BackgroundActivity.AI_COMPUTE to Workload(items = 15_000, bytes = 2 * GB),
      BackgroundActivity.HQ_PREVIEWS to Workload(items = 15_000, bytes = 20 * GB),
    ),
    monthlyNew = mapOf(
      BackgroundActivity.PHOTO_BACKUP to Workload(items = 300, bytes = 1_200 * MB),
      BackgroundActivity.VIDEO_BACKUP to Workload(items = 20, bytes = 2 * GB),
      BackgroundActivity.INDEX_SYNC to Workload(items = 320, bytes = 20 * MB),
      BackgroundActivity.PREPARATION to Workload(items = 320, bytes = 300 * MB),
      BackgroundActivity.AI_COMPUTE to Workload(items = 320, bytes = 50 * MB),
      BackgroundActivity.HQ_PREVIEWS to Workload(items = 320, bytes = 400 * MB),
    ),
  )

  private val estimator = DefaultConsumptionEstimator()

  /** I preset fra cui si sceglie all'onboarding: "Personalizzato" si regola dopo, dalle Impostazioni. */
  val choices: List<Preset> = listOf(Preset.NIGHT, Preset.BALANCED, Preset.WIFI_ALWAYS, Preset.MAXIMUM)

  /** Le anteprime ad alta qualita' si scaricano solo con la qualita' "Alta". */
  fun estimate(preset: Preset, quality: PreviewQuality): PresetEstimateUi {
    val settings = RuleSettings(
      preset = preset,
      disabledActivities = if (quality == PreviewQuality.HIGH) emptySet() else setOf(BackgroundActivity.HQ_PREVIEWS),
    )
    val result = estimator.estimate(settings.toRuleSet(), TypicalBacklog)
    return PresetEstimateUi(
      batteryPercentPerDay = result.steadyBatteryPercentPerDay,
      mobileBytesPerMonth = result.mobileBytesPerMonth,
      daysToFinish = result.daysToFinish,
    )
  }
}
