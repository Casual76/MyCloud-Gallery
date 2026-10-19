package dev.pampa.mycloudgallery.feature.settings.rules

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.rules.MonthlyCaps
import dev.pampa.mycloudgallery.core.rules.NasSchedule
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// "Quando lavora l'app" (verifica funzionale, RE-8): il preset, il tetto dei dati mobili e il roaming, che dopo l'onboarding non si
// potevano piu' cambiare ("Notte" restava per sempre). Le scelte finiscono nelle stesse `RuleSettings` dell'onboarding
// (docs/regole-attivita.md §3, §5): il motore delle regole le rilegge da solo, nessun lavoro da riavviare.

/** I preset che si scelgono (gli stessi del passo 8 dell'onboarding): "Personalizzato" non ha una pagina. */
internal val PresetChoices: List<Preset> = listOf(Preset.NIGHT, Preset.BALANCED, Preset.WIFI_ALWAYS, Preset.MAXIMUM)

/** I tetti dei dati mobili tra cui si sceglie, in byte decimali (1 GB = 10^9, come nei piani dati). */
internal val MobileCapOptions: List<Long> = listOf(500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L, 10_000_000_000L)

/** La scelta del tetto dei dati mobili. */
sealed interface CapChoice {
  /** Quello del preset (1 GB; 10 GB con "Massimo"). */
  data object FromPreset : CapChoice

  data class Limit(val bytes: Long) : CapChoice

  data object Unlimited : CapChoice
}

/**
 * Le tre scelte come cambiano le [RuleSettings]: pure, provate senza schermo. Il preset ha **lo stesso effetto** del passo 8
 * dell'onboarding (`DefaultOnboardingCoordinator.preset`): preset nuovo e fascia notte ricavata dall'orario del NAS (oggi sempre acceso:
 * 23-07). Le anteprime ad alta qualita' non c'entrano: le decide la pagina "Qualita' delle anteprime".
 */
object RulesChoices {

  fun withPreset(settings: RuleSettings, preset: Preset): RuleSettings {
    val night = NightWindows.derive(NasSchedule(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)).window
    return settings.copy(preset = preset, nightWindow = night)
  }

  fun withMobileCap(settings: RuleSettings, choice: CapChoice): RuleSettings {
    val wifi = effectiveCaps(settings).wifiBytes
    return when (choice) {
      CapChoice.FromPreset -> settings.copy(capsOverride = null)
      is CapChoice.Limit -> settings.copy(capsOverride = MonthlyCaps(mobileBytes = choice.bytes, wifiBytes = wifi))
      CapChoice.Unlimited -> settings.copy(capsOverride = MonthlyCaps(mobileBytes = null, wifiBytes = wifi))
    }
  }

  fun withRoaming(settings: RuleSettings, allow: Boolean): RuleSettings = settings.copy(allowRoaming = allow)

  /** I tetti che il motore applica adesso: quelli scelti dall'utente, altrimenti quelli del preset. */
  fun effectiveCaps(settings: RuleSettings): MonthlyCaps =
    settings.capsOverride ?: PresetCatalog.ruleSet(settings.preset, settings.nightWindow).caps

  fun choiceOf(settings: RuleSettings): CapChoice {
    val override = settings.capsOverride ?: return CapChoice.FromPreset
    return override.mobileBytes?.let { CapChoice.Limit(it) } ?: CapChoice.Unlimited
  }
}

/** Cio' che la pagina mostra. */
@Immutable
internal data class RulesUiState(
  val preset: Preset,
  val cap: CapChoice,
  /** Il tetto del preset di adesso, per dire cosa vuol dire "come il preset". */
  val presetCapBytes: Long?,
  val allowRoaming: Boolean,
)

internal fun RuleSettings.toUi(): RulesUiState = RulesUiState(
  preset = preset,
  cap = RulesChoices.choiceOf(this),
  presetCapBytes = PresetCatalog.ruleSet(preset, nightWindow).caps.mobileBytes,
  allowRoaming = allowRoaming,
)

@HiltViewModel
internal class RulesViewModel @Inject constructor(
  private val store: RuleSettingsStore,
) : ViewModel() {

  val state: StateFlow<RulesUiState?> = store.observe()
    .map { it.toUi() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

  fun choosePreset(preset: Preset) {
    viewModelScope.launch { store.update { RulesChoices.withPreset(it, preset) } }
  }

  fun chooseMobileCap(choice: CapChoice) {
    viewModelScope.launch { store.update { RulesChoices.withMobileCap(it, choice) } }
  }

  fun setRoaming(allow: Boolean) {
    viewModelScope.launch { store.update { RulesChoices.withRoaming(it, allow) } }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
