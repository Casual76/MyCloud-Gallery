package dev.pampa.mycloudgallery.rules

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardPort
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.settings.batteryReach
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.work.BatteryResume
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pacchetto B (seconda beta): il livello di batteria e i due interruttori (backup, analisi) nelle impostazioni delle regole del dispositivo, dietro la porta del controllo
 * che le pagine del Backup e dell'analisi ospitano. Il controllo non conosce le regole.
 *
 * - **Il livello si normalizza qui, nel dominio** (BS-4): ogni scrittura passa da [RuleSettings.withBatteryThreshold] (5..80, passi di 5); lo storico non si tocca.
 * - **Ogni scrittura ha un esito** (BS-5): vero se il valore salvato e' quello chiesto, falso se la scrittura non e' riuscita (memoria piena: `QuietWrites` non lancia). Ogni scrittura e' un `update` atomico
 *   (non cancella le altre scelte).
 * - **«Sto caricando»** (BS-5): [value] e' null finche' le impostazioni non sono lette, mai un 20% e tutto acceso inventati.
 * - **La ripresa** (BS-2): un cambio che toglie il blocco a un'attivita' ([BatteryResume.relaxed]) chiede, dopo [calm] di calma (gli eventi del cursore si accorpano), la ripresa dei lavori accodati con il vincolo della
 *   carica ([onRelaxed]).
 */
class AppBatteryGuard(
  private val store: RuleSettingsStore,
  scope: CoroutineScope,
  private val onRelaxed: suspend (Set<BackgroundActivity>) -> Unit = {},
  private val calm: Duration = 2.seconds,
) : BatteryGuardPort {

  override val value: StateFlow<BatteryGuardValue?> =
    store.observe()
      .map<_, BatteryGuardValue?> { settings ->
        // BU-3: chi puo' lavorare a batteria con le regole di adesso (con «Notte» l'analisi no): il controllo non mostra un interruttore che non farebbe niente.
        val reach = settings.batteryReach()
        BatteryGuardValue(
          levelPercent = settings.batteryThresholdPercent,
          forBackup = settings.batteryThresholdForBackup,
          forAnalysis = settings.batteryThresholdForAnalysis,
          backupOnBattery = reach.backupOnBattery,
          analysisOnBattery = reach.analysisOnBattery,
          backupNeedsPower = reach.backupNeedsPower,
          backupEnabled = reach.backupEnabled,
          analysisEnabled = reach.analysisEnabled,
        )
      }
      .distinctUntilChanged()
      .stateIn(scope, SharingStarted.Eagerly, null)

  private val relaxedEvents = Channel<Set<BackgroundActivity>>(Channel.UNLIMITED)

  init {
    scope.launch {
      for (first in relaxedEvents) {
        var all = first
        while (true) {
          val more = withTimeoutOrNull(calm) { relaxedEvents.receive() } ?: break
          all = all + more
        }
        onRelaxed(all)
      }
    }
  }

  override suspend fun setLevel(percent: Int): Boolean = write { it.withBatteryThreshold(percent) }

  override suspend fun setForBackup(on: Boolean): Boolean = write { it.copy(batteryThresholdForBackup = on) }

  override suspend fun setForAnalysis(on: Boolean): Boolean = write { it.copy(batteryThresholdForAnalysis = on) }

  private suspend fun write(change: (RuleSettings) -> RuleSettings): Boolean {
    val before = store.current()
    val wanted = change(before)
    store.update(change)
    val after = store.current()
    val saved = same(after, wanted)
    if (saved) {
      val freed = BatteryResume.relaxed(before, after)
      if (freed.isNotEmpty()) relaxedEvents.trySend(freed)
    }
    return saved
  }

  private fun same(a: RuleSettings, b: RuleSettings) =
    a.batteryThresholdPercent == b.batteryThresholdPercent &&
      a.batteryThresholdForBackup == b.batteryThresholdForBackup &&
      a.batteryThresholdForAnalysis == b.batteryThresholdForAnalysis
}

/**
 * Il livello **che ha fermato** il lavoro, per la radice dell'app (BU-7): lo fornisce a `LocalBatteryThreshold`, cosi' ogni posto in cui un lavoro e' fermo per la batteria dice il numero vero
 * («Batteria sotto il 60%») e non «Batteria scarica» con la batteria al 55%. Il numero e' quello del **verdetto** del motore delle regole (`ActivityRulesEngine.batteryStopThreshold`: il motivo
 * `BatteryLow` porta gia' livello e soglia), non l'impostazione di adesso: subito dopo che l'utente cambia il livello, e finche' il lavoro non e' rivalutato, la frase non dice un numero nuovo accanto a un
 * fermo che non gli corrisponde. Null finche' nessun lavoro e' stato fermato per la batteria: allora vale la frase senza numero.
 */
@HiltViewModel
class BatteryThresholdViewModel @Inject constructor(graph: AppGraph) : ViewModel() {
  val percent: StateFlow<Int?> = graph.rulesEngine.batteryStopThreshold
}
