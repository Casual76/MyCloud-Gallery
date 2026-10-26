package dev.pampa.mycloudgallery.core.models

import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.FileStatReader
import dev.pampa.mycloudgallery.core.models.contract.ModelCounters
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelFreeSpace
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.manager.LocalModelManager
import dev.pampa.mycloudgallery.core.models.manager.SourceStrikes
import dev.pampa.mycloudgallery.core.models.noop.NoModelShelf
import dev.pampa.mycloudgallery.core.models.store.ModelStore
import dev.pampa.mycloudgallery.core.models.verify.LocalModelFiles
import dev.pampa.mycloudgallery.core.models.verify.ModelVerifier
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * I modelli di questo dispositivo, montati insieme (docs/fase6-progetto.md §2.1, D16-D20): la cartella `models/`, la verifica a ogni
 * apertura ([files]), il gestore dello scaricamento ([manager]) e lo scaffale sul NAS ([shelf]). `:app` (P11) ne costruisce UNO per
 * profilo Android, con la cartella `noBackupFilesDir/models`, e passa a `ModelManager` e `ModelFiles` il resto dell'app.
 *
 * @param root `noBackupFilesDir/models` (fuori dai backup).
 * @param fetcher le sorgenti (`ModelFetchers.create`).
 * @param networkGate il cancello di rete per HTTPS (dati mobili, roaming): lo legge ogni 30 s di scaricamento.
 * @param shelfFor lo scaffale sul NAS, costruito con i file verificati (che pubblica); senza NAS: nessuno.
 * @param area l'area comune (copie dei dispositivi e cartella dal PC); null = niente NAS, restano gli indirizzi HTTPS.
 * @param freeSpace lo spazio libero del volume (E-A3).
 * @param clock l'ora del NAS, per "due errori in un giorno" (E-A2). Senza (o quando l'ora del NAS non c'e' ancora: fuori casa, senza VPN) gli
 *   errori si ricordano solo nel processo, con l'orologio monotono (R10).
 * @param statReader lo `stat` del file preso durante la verifica (I1): `PATH_CHECKED` lo confronta con quello del percorso. Solo `:app` lo da'
 *   (`AndroidFileStatReader`); sulla JVM nessuno.
 * @param progressPoll ogni quanto lo stato legge l'avanzamento dal file parziale (zero = mai).
 */
class LocalModels(
  root: File,
  fetcher: ModelFetcher,
  networkGate: ModelNetworkGate,
  shelfFor: (ModelFiles) -> ModelShelf = { NoModelShelf },
  area: ModelNasArea = ModelNasArea { null },
  catalog: List<ModelSpec> = ModelCatalog.ALL,
  freeSpace: ModelFreeSpace = ModelFreeSpace { it.usableSpace },
  clock: NasClock? = null,
  io: CoroutineDispatcher = Dispatchers.IO,
  nanos: () -> Long = System::nanoTime,
  progressPoll: Duration = 500.milliseconds,
  val counters: ModelCounters = ModelCounters(),
  statReader: FileStatReader = FileStatReader.None,
) {
  private val store = ModelStore(root, catalog, nanos, counters)
  private val verifier = ModelVerifier(store, statReader)

  /** Il solo modo di caricare un modello: verifica dimensione e SHA-256 a OGNI apertura (E-A11). */
  val files: ModelFiles = LocalModelFiles(store, verifier, io)

  /** Lo scaffale sul NAS (`publish`, `copies`, `pruneOwn`). */
  val shelf: ModelShelf = shelfFor(files)

  private val local = LocalModelManager(
    store = store,
    verifier = verifier,
    fetcher = fetcher,
    shelf = shelf,
    area = area,
    gate = networkGate,
    freeSpace = freeSpace,
    strikes = SourceStrikes(
      file = store.strikesFile(),
      // R10: senza l'ora del NAS `nowMillis()` lancia; per le quarantene vuol dire "non so" (null), non un errore.
      nowMillis = { clock?.let { c -> runCatching { c.nowMillis() }.getOrNull() } },
      monotonicMillis = { nanos() / 1_000_000 },
    ),
    io = io,
    progressPoll = progressPoll,
  )

  val manager: ModelManager get() = local

  /**
   * Da chiamare una volta all'avvio e quando si apre la pagina Ricerca e modelli: toglie i modelli che l'app non elenca piu' (D20) e
   * verifica quelli presenti, cosi' `manager.states` dice il vero (prima di questa e di un `ensure` o `open` un file presente e'
   * `Verifying`, mai `Ready`).
   */
  suspend fun refresh() = local.refresh()

  /**
   * D18, MO-4: pubblica sul NAS le copie dei modelli verificati qui (se nessun'altra famiglia di dispositivi ce l'ha gia') e toglie le proprie che
   * l'app non elenca piu'. Quante copie ha scritto. Lo chiama il lavoro dei modelli in background.
   */
  suspend fun shareOnNas(): Int = local.shareOnNas()

  private val refreshed = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * [refresh] una volta sola per questo oggetto (cioe' per processo): il lavoro periodico dei modelli lo chiamava a ogni giro e rileggeva
   * per intero circa 1,1 GB (MO-7). Chi apre un modello lo verifica comunque, a ogni apertura.
   */
  suspend fun refreshOnce() {
    if (refreshed.compareAndSet(false, true)) {
      try {
        local.refresh()
      } catch (t: Throwable) {
        refreshed.set(false)
        throw t
      }
    }
  }
}
