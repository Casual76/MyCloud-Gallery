package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.faces.contract.FaceGrouping
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunControl
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FaceInputs
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.store.FacesRecovery
import dev.pampa.mycloudgallery.core.faces.store.FacesStoreOpen
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.faces.store.FacesUnavailable
import dev.pampa.mycloudgallery.core.faces.store.FacesUnitsDone
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// LE PORTE DEL CALCOLO DEI VOLTI (docs/fase8-progetto.md §2.3, D16-D19; P4). Si aggiungono a quelle di P0 (`contract/Ports.kt`,
// `contract/LocalPorts.kt`): le lega :app (P13). Qui solo firme, valori di serie e la loro documentazione.

/** Dove sta il lavoro che questo dispositivo ha gia' fatto per la generazione: l'archivio cifrato e le unita' finite (P6). */
class ComputeStore(val archive: FacesArchive, val units: FacesUnitsDone) {
  override fun toString(): String = "ComputeStore"
}

/** L'esito di [FacesStoreSource.open]. Mai un'eccezione per un caso previsto (E-F19). */
sealed interface ComputeStoreOpen {
  /** Il negozio c'e'. [recovery] != null = i dati locali erano illeggibili e sono stati rifatti da capo (il calcolo riparte da zero). */
  class Ready(val store: ComputeStore, val recovery: FacesRecovery?) : ComputeStoreOpen {
    override fun toString(): String = "ComputeStoreOpen.Ready(${recovery?.code})"
  }

  /** Non adesso (Keystore che non risponde, file bloccato, database piu' nuovo dell'app): si riprova alla tappa dopo, niente e' cancellato. */
  data class Unavailable(val reason: FacesUnavailable) : ComputeStoreOpen
}

/** Chi apre il negozio di una generazione: in :app `FacesStores` (un solo oggetto per processo, [FacesStoresSource]). */
fun interface FacesStoreSource {
  suspend fun open(generation: FaceGeneration): ComputeStoreOpen
}

/** L'adattatore sul negozio vero di P6: `FacesStores.open(G)` con tutti i suoi esiti. */
class FacesStoresSource(private val stores: FacesStores) : FacesStoreSource {
  override suspend fun open(generation: FaceGeneration): ComputeStoreOpen = when (val opened = stores.open(generation)) {
    is FacesStoreOpen.Ready -> ComputeStoreOpen.Ready(ComputeStore(opened.store.archive, opened.store.units), null)
    is FacesStoreOpen.Recovered -> ComputeStoreOpen.Ready(ComputeStore(opened.store.archive, opened.store.units), opened.reason)
    is FacesStoreOpen.Unavailable -> ComputeStoreOpen.Unavailable(opened.reason)
  }
}

/**
 * Le aree di questo dispositivo che il calcolo non puo' ricavare da solo (D14, D16): la share dell'**area comune** del registro. L'area
 * privata e' quella dichiarata ([FacesRequirements.privateArea]). Un ambito che non e' una di queste due non si tocca mai.
 */
fun interface FacesAreas {
  /** Il nome della share dell'area comune, o null se non si sa adesso. */
  suspend fun commonShare(): String?
}

/** Lo spazio libero del telefono dove sta il database dei volti (`noBackupFilesDir`): sotto 1 GiB il calcolo si ferma (E-A17). */
fun interface FacesFreeSpace {
  fun usableBytes(): Long

  companion object {
    val UNLIMITED: FacesFreeSpace = FacesFreeSpace { Long.MAX_VALUE }

    /** E-A17, come per il calcolo AI. */
    const val MIN_FREE_BYTES: Long = 1024L * 1024 * 1024
  }
}

/**
 * I segnali una tantum per la Diagnostica (D37): [code] e' uno dei `FacesDiag.FACES_*`, [count] quanti. Solo numeri e codici, mai un
 * nome, una chiave o un'immagine. :app li porta negli eventi della Diagnostica (`FACES_KEY_LOST`, `FACES_ENGINE_FAILED`...).
 */
fun interface FacesSignals {
  fun report(code: String, count: Int)

  companion object {
    val NONE: FacesSignals = FacesSignals { _, _ -> }
  }
}

/**
 * L'impronta di un corpo `faces` (`FaceBody.digest`, §2.4: le prime 8 byte, big endian, dello SHA-256 del corpo codificato). Entra nei
 * riferimenti dell'istantanea (`FaceRef.bodyDigest`): **deve essere la stessa su ogni dispositivo** che rilegge il corpo dal NAS.
 * [Sha256FaceBodyDigest] la calcola sul formato del §2.4; :app (P13) puo' legare il codec vero di P3 se la sua codifica fosse diversa.
 */
fun interface FaceBodyDigest {
  fun digestOf(body: FaceBodyDraft): Long
}

/**
 * Porta qui i dati dei volti pubblicati dagli altri dispositivi dell'account da quando si e' sincronizzato l'ultima volta (:app: `FacesSync.sync` con
 * un budget breve). Il calcolo la chiama **solo** dopo aver preso una prenotazione che qualcun altro ha gia' tenuto (numero oltre il primo): quell'unita'
 * puo' essere stata finita da lui e rilasciata, e senza questa il suo lavoro si rifarebbe (le prenotazioni dividono le unita' in corso, non
 * quelle finite: "fatto" lo dicono i corpi dell'archivio). Di serie non fa niente. Un `IOException` vale "il NAS non risponde".
 */
fun interface FacesRefresh {
  suspend fun refresh()

  companion object {
    val NONE: FacesRefresh = FacesRefresh { }
  }
}

/**
 * Capacita' facoltativa di [dev.pampa.mycloudgallery.core.faces.contract.FaceInputs]: mettere insieme in un colpo solo le anteprime di
 * un gruppo di media (lotti di 50 dall'indice della share, D17) prima che il calcolo le chieda una per una. Se manca, [preview] le prende
 * una alla volta. Mai un'eccezione per un caso previsto; un [java.io.IOException] vale "il NAS non risponde".
 */
interface FacesPrefetching {
  suspend fun prefetch(items: List<FacesItem>)
}

/**
 * Come si trovano gli [FacesItem] di un insieme di chiavi di contenuto (per chiave, non per mese): serve al buffer, per sapere quali delle
 * anteprime che sta per buttare sono ancora **da analizzare** (non esclusi: screenshot, documenti, ritagli) e a nessun altro. :app la lega
 * sopra `MediaCatalog` (le stesse celle di [dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog]).
 */
fun interface FacesItemLookup {
  suspend fun itemsOf(keys: Collection<ContentKey>): Collection<FacesItem>
}

/** Tutto cio' che serve al calcolo dei volti. Un oggetto solo, cosi' :app lo compone una volta e i test ne cambiano un pezzo con `copy`. */
data class FacesComputeParts(
  /** Lo stato di Persone adesso (si legge di nuovo a ogni lotto, R7): il calcolo parte solo con `On(G, ACTIVE o LOCAL_ONLY)`. */
  val switch: FacesSwitch,
  /** `faces-local`: la generazione dei dati locali e la cancellazione in corso (§2.3 "Cancello"). */
  val localStore: FacesLocalStore,
  val requirements: FacesRequirements,
  val areas: FacesAreas,
  val catalog: FacesCatalog,
  val inputs: FaceInputs,
  /** I motori dei volti, chiesti **solo** quando c'e' qualcosa da calcolare e rilasciati a fine tappa; null = il motore non parte. */
  val engines: suspend () -> FaceEngines?,
  /** I due modelli ci sono e tornano con l'impronta (`ModelFiles`, P1). */
  val modelsReady: suspend () -> Boolean,
  val stores: FacesStoreSource,
  val publisher: FacesPublisher,
  val leases: FacesLeases,
  val grouping: FaceGrouping,
  val control: FacesRunControl,
  val clock: FacesClock,
  /** L'id di questo dispositivo: l'autore dei corpi `faces` che scrive. */
  val deviceId: String,
  val digest: FaceBodyDigest,
  /** Il rilevatore che i motori usano (la scelta fra le due versioni di YuNet e' di P1). */
  val detector: FaceDetectorCode = FaceDetectorCode.YUNET_2023MAR,
  val freeSpace: FacesFreeSpace = FacesFreeSpace.UNLIMITED,
  val signals: FacesSignals = FacesSignals.NONE,
  val refresh: FacesRefresh = FacesRefresh.NONE,
)

/** I ritmi e i tetti del calcolo (§2.3, E-A5, F6-D22). */
data class FacesComputeConfig(
  /** Media per lotto, e col caldo (`LIGHT`): lotti piccoli con una pausa. Le regole si chiedono una volta per lotto. */
  val batch: Int = 20,
  val reducedBatch: Int = 5,
  val reducedPause: Duration = 2.seconds,
  /** Ogni quanto si rinnova la prenotazione di un'unita' (monotono). */
  val keepAliveEvery: Duration = FacesComputeLimits.LEASE_RENEW_MILLIS.milliseconds,
  /** Quante unita' al piu' in una tappa. */
  val maxUnitsPerRun: Int = 1_000,
  /** Quante anteprime si mettono insieme in anticipo da [FacesPrefetching] (lotti di 50, D17). */
  val prefetchAhead: Int = 50,
  /** IX-7: errori passeggeri dello stesso media in un processo, oltre i quali vale come "non supportato". */
  val maxTransient: Int = 3,
  /** Media per unita' (200, gli stessi confini su ogni dispositivo). */
  val unitSize: Int = FacesComputeLimits.UNIT_MAX_ITEMS,
  /** La ricetta degli ingressi (E-F24: se cresce, si ricalcola da capo). */
  val recipe: Int = FacesComputeLimits.RECIPE,
) {
  init {
    require(batch > 0 && reducedBatch > 0 && unitSize > 0 && maxUnitsPerRun > 0 && maxTransient > 0 && prefetchAhead > 0) { "Config non valida." }
    require(recipe in 1..255) { "Ricetta fuori intervallo." }
  }

  companion object {
    val DEFAULT = FacesComputeConfig()
  }
}

/** Il corpo `faces` prima di avere l'impronta (la calcola [FaceBodyDigest] sul formato del §2.4). */
class FaceBodyDraft(
  val contentKey: ContentKey,
  val space: FaceSpaceCode,
  val detector: FaceDetectorCode,
  val recipe: Int,
  val source: FaceSource,
  val faces: List<dev.pampa.mycloudgallery.core.faces.contract.FaceEntry>,
) {
  override fun toString(): String = "FaceBodyDraft(${faces.size} volti, ricetta $recipe)"
}

/** I contatori del calcolo per la Diagnostica (§2.10, D37): solo numeri. */
class FacesComputeCounters {
  /** Ambiti rifiutati perche' non sono un'area di questo account (D14, D16): devono restare a zero. */
  val foreignScopes = AtomicInteger()

  /** Media con almeno una passata del rilevatore. */
  val detected = AtomicInteger()

  /** Seconde passate sull'HQ fatte e volute-ma-senza-HQ (si tengono i volti della prima, D17). */
  val hqPasses = AtomicInteger()
  val hqMissing = AtomicInteger()

  /** Fotogrammi di video analizzati (D18). */
  val videoFrames = AtomicInteger()

  /** Screenshot, registrazioni dello schermo, documenti, ritagli e immagini animate saltati (D18, E-F16). */
  val excluded = AtomicInteger()

  /** Volti senza vettore perche' non valido: valori non finiti o norma lontana da 1 (`FACES_VECTOR_INVALID`). */
  val vectorsInvalid = AtomicInteger()

  /** Media che aspettano un'anteprima (`FACES_WAITING_PREVIEWS`). */
  val waitingPreviews = AtomicInteger()

  /** IX-7: errori passeggeri dei motori (memoria, guasto della libreria). */
  val engineFailures = AtomicInteger()

  /** Tappe fermate per i motori (`FACES_ENGINE_FAILED`) o per i modelli (`FACES_MODEL_MISMATCH`). */
  val engineStops = AtomicInteger()
  val modelStops = AtomicInteger()

  /** Dati locali illeggibili rifatti da capo: chiave sparita (`FACES_KEY_LOST`, E-F19) o file danneggiato. */
  val keyLost = AtomicInteger()
  val databaseRebuilt = AtomicInteger()

  /** CA-7: tappe che non sono partite perche' l'archivio dei volti non si apre adesso (`FACES_STORE_UNAVAILABLE`): Keystore, dati piu' nuovi dell'app, file. */
  val storeUnavailable = AtomicInteger()

  /** Un errore del database a meta' di un salvataggio (il corpo, la messa in coda, il segno di unita' finita, la pubblicazione, O1): il media o l'unita' resta da fare. */
  val storeFailures = AtomicInteger()

  /** Prenotazioni perse a meta' unita', e unita' tenute da un altro (E-I2). */
  val unitsLost = AtomicInteger()
  val unitsBusy = AtomicInteger()

  /** Quante volte si e' chiesto di aggiornare i dati degli altri dispositivi prima di un'unita' gia' tenuta da altri ([FacesRefresh]). */
  val refreshes = AtomicInteger()

  /** Prenotazioni che non si sono potute prendere o rinnovare (NAS, cancello): si lavora senza (danno solo d'efficienza). */
  val leaseTroubles = AtomicInteger()

  /** Pubblicazioni o messe in coda fallite: il corpo e' salvato, si rimette in coda alla prossima visita dell'unita'. */
  val publishFailures = AtomicInteger()

  /** Corpi rimessi in coda senza ricalcolo (morte fra il salvataggio e la messa in coda, media solo telefono arrivato sul NAS). */
  val restaged = AtomicInteger()

  /** Volti salvati, per livello. */
  val facesSaved = AtomicInteger()

  /** Errori del raggruppamento a fine unita' (non fermano il calcolo). */
  val groupingFailures = AtomicInteger()
}
