package dev.pampa.mycloudgallery.core.backup.settings

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.plan.BackupPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * D13 come regola pura: con una recinzione (debug, account di prova) valgono solo le cartelle che ammette per l'account; **senza account si
 * fallisce chiusi** (nessuna sorgente); senza recinzione valgono tutte. La usano le impostazioni vere e quelle finte (`FakeBackupSettings`),
 * cosi' non possono divergere (BK-14).
 */
object BackupSourceFence {
  fun restrict(sources: BackupSources, fence: BackupFence?, account: String?): BackupSources {
    fence ?: return sources
    account ?: return BackupSources.NONE
    return BackupSources(sources.sources.filterTo(LinkedHashSet()) { fence.allowsPhoneFolder(account, BackupPaths.phoneFolderOf(it)) })
  }
}

/**
 * Le impostazioni del backup (Fase 5A, P4; `docs/fase5-progetto.md` §2.1, D7, D13), salvate come impostazione **del dispositivo** (non
 * personale) in [storage].
 *
 * - **Sorgenti**: quelle scelte. Si propongono dal passo 6 dell'onboarding ([BackupSourceCodec.fromOnboarding]) e dalle altre cartelle del
 *   MediaStore (Impostazioni → Backup). Toglierne una (E-B12) cambia solo cosa si carica d'ora in poi: il catalogo non si tocca e i media
 *   gia' al sicuro restano tali. Mai scelte = **nessuna** (si fallisce chiusi: il backup dice che manca la scelta).
 * - **Cartella del dispositivo** (D7): scelta **una volta**, al primo backup, con [DeviceFolderPolicy] (un nome gia' presente in `Backup/`
 *   fa chiedere "Continua li'" o "Nuova cartella", E-B6; dopo una reinstallazione si propone di continuare, E-B16). Poi non cambia: il nome
 *   del dispositivo non e' qui, quindi rinominarlo non la sposta (E-B7). Una seconda scelta diversa e' un errore di programmazione.
 * - **D13**: con la recinzione del telefono (debug con gli account di prova) [sources] contiene solo le cartelle che la recinzione
 *   ammette per l'account; senza account si fallisce chiusi. Il valore scelto resta salvato com'e'. **BK-14**: l'account puo' arrivare dopo la
 *   prima lettura del magazzino; chi sa che e' arrivato chiama [refresh], altrimenti le sorgenti sarebbero rimaste vuote per tutto il processo.
 *
 * Finche' il magazzino non ha risposto, [sources] e' vuoto e [deviceFolder] nullo (fallire chiusi: nessuna sorgente, nessuna cartella);
 * [awaitLoaded] aspetta la prima lettura. Un valore illeggibile vale "non scelto".
 */
class DefaultBackupSettings(
  private val storage: BackupSettingsStorage,
  scope: CoroutineScope,
  private val fence: BackupFence? = null,
  private val account: () -> String? = { null },
) : BackupSettings {

  private val chosen = MutableStateFlow(BackupSources.NONE)
  private val effective = MutableStateFlow(BackupSources.NONE)
  private val folder = MutableStateFlow<DeviceFolder?>(null)
  private val loadedState = MutableStateFlow(false)

  /** Le sorgenti scelte e ammesse dalla recinzione. */
  override val sources: StateFlow<BackupSources> = effective.asStateFlow()

  override val deviceFolder: StateFlow<DeviceFolder?> = folder.asStateFlow()

  /** Vero dopo la prima lettura del magazzino. */
  val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

  init {
    scope.launch { storage.data.collect(::apply) }
  }

  /** Aspetta la prima lettura del magazzino (per chi decide su cosa e' salvato). */
  suspend fun awaitLoaded() {
    loadedState.first { it }
  }

  /** Le sorgenti scelte dall'utente, senza la recinzione di D13 (per la pagina delle impostazioni). */
  val chosenSources: StateFlow<BackupSources> = chosen.asStateFlow()

  /** Vero se una recinzione sta restringendo le sorgenti (D13): la pagina lo dice. Con una recinzione e senza account si fallisce chiusi. */
  val fenced: Boolean
    get() {
      val fence = fence ?: return false
      val account = account() ?: return true
      return fence.destinationRoot(account) != null
    }

  override suspend fun setSources(sources: BackupSources) {
    apply(storage.update { it.copy(sourceIds = BackupSourceCodec.encode(sources)) })
  }

  override suspend fun chooseFolder(folder: DeviceFolder) {
    val saved = storage.update { current ->
      // D7: una volta sola. Ripetere la stessa scelta e' innocuo (una ripresa dopo un'interruzione).
      check(current.deviceFolder == null || current.deviceFolder == folder.name) { "La cartella del dispositivo e' gia' scelta." }
      current.copy(deviceFolder = folder.name)
    }
    apply(saved)
  }

  /**
   * BK-14: ricalcola le sorgenti ammesse con l'account di **adesso**. Il magazzino si legge una volta sola all'avvio e la recinzione guarda
   * l'account in quel momento: se l'account non c'era ancora (l'archivio degli account risponde dopo), [sources] restava [BackupSources.NONE]
   * finche' qualcuno non cambiava le impostazioni. Economica e idempotente: la chiamano `BackupGraph.start` e il ricalcolo dello stato.
   */
  fun refresh() {
    effective.value = restrictedByFence(chosen.value)
  }

  private fun apply(stored: StoredBackupSettings) {
    val sources = BackupSourceCodec.decode(stored.sourceIds.orEmpty())
    chosen.value = sources
    effective.value = restrictedByFence(sources)
    folder.value = stored.deviceFolder?.let { name -> runCatching { DeviceFolder(name) }.getOrNull() }
    loadedState.value = true
  }

  /** D13: solo le cartelle che la recinzione ammette per l'account; con una recinzione e senza account, nessuna ([BackupSourceFence]). */
  private fun restrictedByFence(sources: BackupSources): BackupSources = BackupSourceFence.restrict(sources, fence, account())
}
