package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewLevel
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualitySettings

/**
 * Cio' che segue a un cambio di qualita' delle anteprime (F12 delle prove sul campo, 22/10), oltre a ricordare il livello:
 *
 *  1. **O-B17**: le anteprime HQ sono un'attivita' a parte (`HQ_PREVIEWS`) che le regole tengono spenta se la qualita' non e' "Alta"
 *     (`docs/regole-attivita.md`, `disabledActivities`). La spegneva o accendeva solo l'onboarding: chi passava ad "Alta" dalle Impostazioni
 *     la trovava ancora `ACT_DISABLED` in Diagnostica e lo scaricamento dell'HQ non partiva mai. Ora la qualita' scelta accende o spegne
 *     `HQ_PREVIEWS` insieme al resto.
 *  2. **O-B16**: la pagina dice "sto adattando lo spazio" e la convergenza (scaricare le classi nuove, rilasciare le vecchie) la fanno i giri di
 *     sincronizzazione, che aspettavano il giro periodico di 6 ore. Ora il cambio ne accoda subito uno ([converge]): lo stesso lavoro del periodico,
 *     con le regole delle attivita' (non le scavalca).
 *
 * Solo se il NAS nella Libreria e' partito ([started]): senza, `choose` non ricorda niente e nemmeno questo cambia niente.
 */
class ConvergingPreviewQuality(
  private val inner: PreviewQualitySettings,
  private val rules: RuleSettingsStore,
  private val started: () -> Boolean,
  private val converge: (QualityLevel) -> Unit,
) : PreviewQualitySettings by inner {

  override suspend fun choose(level: PreviewLevel) {
    if (!started()) return inner.choose(level)
    inner.choose(level)
    val wanted = NasPreviewQuality.toLevel(level)
    rules.update { it.withHqPreviews(wanted == QualityLevel.HIGH) }
    converge(wanted)
  }

  companion object {
    /** Le regole con `HQ_PREVIEWS` accesa o spenta; il resto delle impostazioni non si tocca. */
    fun RuleSettings.withHqPreviews(on: Boolean): RuleSettings =
      copy(disabledActivities = if (on) disabledActivities - BackgroundActivity.HQ_PREVIEWS else disabledActivities + BackgroundActivity.HQ_PREVIEWS)
  }
}
