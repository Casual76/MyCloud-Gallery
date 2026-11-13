package dev.pampa.mycloudgallery.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuard
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardPort
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Pacchetto B (seconda beta): il livello di batteria e i due interruttori (backup, analisi) nelle impostazioni delle regole del dispositivo, dietro la porta del controllo
 * che le pagine del Backup e dell'analisi ospitano. Il controllo non conosce le regole: scrive il livello gia' sul passo di 5 piu' vicino, e ogni scrittura e' un
 * `update` atomico (non cancella le altre scelte, e non ne dipende il momento in cui il file e' stato letto).
 */
class AppBatteryGuard(private val store: RuleSettingsStore, scope: CoroutineScope) : BatteryGuardPort {

  override val value: StateFlow<BatteryGuardValue> =
    store.observe()
      .map { BatteryGuardValue(it.batteryThresholdPercent, it.batteryThresholdForBackup, it.batteryThresholdForAnalysis) }
      .distinctUntilChanged()
      .stateIn(scope, SharingStarted.Eagerly, BatteryGuardValue())

  override suspend fun setLevel(percent: Int) {
    store.update { it.copy(batteryThresholdPercent = BatteryGuard.snap(percent)) }
  }

  override suspend fun setForBackup(on: Boolean) {
    store.update { it.copy(batteryThresholdForBackup = on) }
  }

  override suspend fun setForAnalysis(on: Boolean) {
    store.update { it.copy(batteryThresholdForAnalysis = on) }
  }
}

/**
 * Il livello scelto, per la radice dell'app: lo fornisce a `LocalBatteryThreshold`, cosi' ogni posto in cui un lavoro e' fermo per la batteria dice il numero vero
 * («Batteria sotto il 60%») e non «Batteria scarica» con la batteria al 55%. Null finche' le impostazioni non sono lette: allora vale la frase senza numero.
 */
@HiltViewModel
class BatteryThresholdViewModel @Inject constructor(store: RuleSettingsStore) : ViewModel() {
  val percent: StateFlow<Int?> =
    store.observe()
      .map<_, Int?> { it.batteryThresholdPercent }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), null)

  private companion object {
    const val StopTimeoutMillis = 5_000L
  }
}
