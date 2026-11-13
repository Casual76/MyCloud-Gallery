package dev.pampa.mycloudgallery.feature.backup.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardPort
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import dev.pampa.mycloudgallery.feature.backup.contract.BackupRules
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolder
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolders
import dev.pampa.mycloudgallery.feature.backup.noop.NoBackupActions
import dev.pampa.mycloudgallery.feature.backup.noop.NoBackupRules
import dev.pampa.mycloudgallery.feature.backup.noop.NoPhoneFolders
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCovers
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Impostazioni -> Backup (docs/fase5-progetto.md §2.1, D7, D34, E-B12, E-B20; D30): le sorgenti (fotocamera, screenshot e le altre cartelle
// del telefono con numero e GB), la cartella del dispositivo e la regola "Proteggi subito le foto nuove". Il ViewModel salva le scelte nelle
// impostazioni del backup (`BackupSettings`, del dispositivo: non personali) e non decide altro.

/** Le cartelle del telefono che si possono scegliere: in lettura, pronte o non disponibili. */
internal sealed interface FoldersState {
  /** La lettura delle cartelle (puo' durare, il MediaStore ha migliaia di righe). */
  data object Loading : FoldersState

  data class Ready(val folders: List<PhoneFolder>) : FoldersState

  /** Non si e' potuto leggere (nessun accesso ai media, nessun elenco legato): si vedono solo le sorgenti gia' scelte. */
  data object Unavailable : FoldersState
}

/** Una riga di sorgente delle impostazioni: la sorgente, se e' scelta e, se la si conosce, quanti media e quanti byte. */
@Immutable
internal class SourceChoice(val source: BackupSource, val chosen: Boolean, val media: Int?, val bytes: Long?)

@Immutable
internal data class SettingsUi(
  val folders: FoldersState,
  val chosen: BackupSources,
  val deviceFolder: DeviceFolder?,
  val protectNewPhotos: Boolean,
  /** `:app` ha legato le regole: senza, l'interruttore non c'e'. */
  val rulesAvailable: Boolean,
  /** `:app` ha legato le impostazioni: senza, niente si puo' scegliere. */
  val editable: Boolean,
  /** L'ultima scelta non si e' salvata (memoria piena, OB-4 della review generale): l'interruttore e' tornato com'era e la nota sotto le sorgenti lo dice. */
  val saveFailed: Boolean = false,
  /** Pacchetto B: il livello di batteria e i due interruttori (backup, analisi) **salvati**; null = non ancora letti ([batteryLoading]) o `:app` non ha legato la porta (il controllo non c'e'). */
  val battery: BatteryGuardValue? = null,
  /** La porta della batteria c'e' ma le impostazioni non sono ancora state lette: il controllo si vede come scheletro, mai con un 20% finto (BS-5). */
  val batteryLoading: Boolean = false,
  /** L'ultima scelta della batteria non si e' salvata (BS-5): il controllo torna al valore salvato e lo dice con la stessa frase delle altre impostazioni. */
  val batterySaveFailed: Boolean = false,
) {
  /**
   * Le righe delle sorgenti nell'ordine in cui si mostrano: fotocamera, screenshot, poi le cartelle aggiuntive dalla piu' grande. Una
   * cartella gia' scelta che il telefono non elenca piu' (E-B12) resta in lista, cosi' si puo' togliere.
   */
  val choices: List<SourceChoice> by lazy(LazyThreadSafetyMode.NONE) {
    val known = (folders as? FoldersState.Ready)?.folders.orEmpty().associateBy { it.source.id }
    val base = listOf(BackupSource.Camera, BackupSource.Screenshots)
    val extra = known.values.map { it.source }.filter { it !in base } + chosen.sources.filter { it !in base && it.id !in known }
    (base + extra.distinctBy { it.id }.sortedByDescending { known[it.id]?.media ?: 0 }).map { source ->
      val folder = known[source.id]
      SourceChoice(source, chosen = source in chosen, media = folder?.media, bytes = folder?.bytes)
    }
  }
}

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
  settings: Optional<BackupSettings>,
  phoneFolders: Optional<PhoneFolders>,
  rules: Optional<BackupRules>,
  actions: Optional<BackupActions>,
  coverPorts: Optional<BackupCoverPorts> = Optional.empty(),
  batteryGuard: Optional<BatteryGuardPort> = Optional.empty(),
) : ViewModel() {

  /** I3: le copertine delle cartelle (la foto piu' recente, dalla Libreria visibile); null = le piastrelle. */
  internal val covers: BackupCovers? = coverPorts.map { BackupCovers(it) }.orElse(null)

  private val port: BackupSettings? = settings.orElse(null)
  private val folders: PhoneFolders = phoneFolders.orElse(NoPhoneFolders)
  private val rulesPort: BackupRules? = rules.orElse(null)
  private val rulesOrNone: BackupRules = rulesPort ?: NoBackupRules
  private val acts: BackupActions = actions.orElse(NoBackupActions)
  private val batteryPort: BatteryGuardPort? = batteryGuard.orElse(null)
  private val batteryFlow: Flow<BatteryGuardValue?> = batteryPort?.value ?: flowOf(null)
  private val batteryFailed = MutableStateFlow(false)

  private val foldersState = MutableStateFlow<FoldersState>(FoldersState.Loading)
  private val saveFailed = MutableStateFlow(false)
  private val noSources: StateFlow<BackupSources> = MutableStateFlow(BackupSources.NONE)
  private val noFolder: StateFlow<DeviceFolder?> = MutableStateFlow<DeviceFolder?>(null)

  internal val state: StateFlow<SettingsUi> = combine(
    combine(
      foldersState,
      port?.sources ?: noSources,
      port?.deviceFolder ?: noFolder,
      rulesOrNone.protectNewPhotos,
      saveFailed,
    ) { found, chosen, deviceFolder, protect, failed ->
      SettingsUi(
        folders = found,
        chosen = chosen,
        deviceFolder = deviceFolder,
        protectNewPhotos = protect,
        rulesAvailable = rulesPort != null,
        editable = port != null,
        saveFailed = failed,
      )
    },
    batteryFlow,
    batteryFailed,
  ) { ui, battery, failed -> ui.copy(battery = battery, batteryLoading = batteryPort != null && battery == null, batterySaveFailed = failed) }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(StopTimeoutMillis),
    initialValue = SettingsUi(
      folders = FoldersState.Loading,
      chosen = (port?.sources ?: noSources).value,
      deviceFolder = (port?.deviceFolder ?: noFolder).value,
      protectNewPhotos = rulesOrNone.protectNewPhotos.value,
      rulesAvailable = rulesPort != null,
      editable = port != null,
      battery = batteryPort?.value?.value,
      batteryLoading = batteryPort != null && batteryPort.value.value == null,
    ),
  )

  init {
    reloadFolders()
  }

  /** Rilegge le cartelle del telefono (all'apertura e quando l'utente torna dal permesso). */
  fun reloadFolders() {
    foldersState.value = FoldersState.Loading
    viewModelScope.launch {
      foldersState.value = try {
        FoldersState.Ready(folders.folders())
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        FoldersState.Unavailable
      }
    }
  }

  /** Accende o spegne una sorgente (E-B12: togliere una cartella non cancella niente, i media nuovi non si caricano piu'). */
  fun setSource(source: BackupSource, on: Boolean) {
    val settings = port ?: return
    val current = settings.sources.value.sources
    val next = if (on) current + source else current - source
    if (next == current) return
    viewModelScope.launch {
      saveFailed.value = false
      settings.setSources(BackupSources(next))
      // OB-4 (review generale): con la memoria piena la scelta non si salva (l'archivio non lancia piu': restituisce il valore di prima) e lo stato resta com'era, quindi
      // l'interruttore torna indietro. La nota sotto le sorgenti dice perche', con i componenti che ci sono.
      saveFailed.value = settings.sources.value.sources != next
    }
  }

  /** Pacchetto B: il livello di batteria sotto cui il backup e l'analisi non partono (a passi di 5; il controllo lo da' gia' sul passo). */
  fun setBatteryLevel(percent: Int) = saveBattery { setLevel(percent) }

  fun setBatteryForBackup(on: Boolean) = saveBattery { setForBackup(on) }

  fun setBatteryForAnalysis(on: Boolean) = saveBattery { setForAnalysis(on) }

  /** BS-5: ogni scelta ha un esito; se non e' riuscita il controllo lo sa ([SettingsUi.batterySaveFailed]) e torna al valore salvato. Una nuova scelta toglie l'avviso. */
  private fun saveBattery(write: suspend BatteryGuardPort.() -> Boolean) {
    val battery = batteryPort ?: return
    viewModelScope.launch {
      batteryFailed.value = false
      batteryFailed.value = !battery.write()
    }
  }

  /** D34: "Proteggi subito le foto nuove". */
  fun setProtectNewPhotos(on: Boolean) {
    if (rulesPort == null) return
    viewModelScope.launch { rulesPort.setProtectNewPhotos(on) }
  }

  /**
   * La cartella del dispositivo (D7) si sceglie con il primo backup: apre il suo riepilogo (la pagina Backup con il foglio "Primo backup"), da cui
   * "Stanotte" o "Inizia ora" la scelgono. D-9 (prove sul campo): prima apriva la scelta della cartella da sola e il primo backup partiva al
   * giro delle regole senza che l'utente vedesse la stima (§2.11).
   */
  fun openDeviceFolder() = acts.open(BackupScreen.FIRST_BACKUP)

  private companion object {
    const val StopTimeoutMillis = 5_000L
  }
}
