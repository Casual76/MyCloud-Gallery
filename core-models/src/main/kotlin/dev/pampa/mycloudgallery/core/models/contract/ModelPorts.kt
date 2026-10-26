package dev.pampa.mycloudgallery.core.models.contract

import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

// Le PORTE e i TETTI di core-models (Fase 6, P3; docs/fase6-progetto.md §2.1, D16-D20, §6.1). P0 ha scritto il catalogo, il gestore, i
// file verificati, la sorgente e lo scaffale; qui ci sono le porte che servono alla logica vera e che solo `:app` (P11) puo' legare:
// il traffico, l'area comune del NAS, lo spazio, gli asset dell'APK, il cancello di scrittura e lo scrittore dello scaffale. Nessun
// tipo di client HTTP in queste firme: l'HTTPS sta tutto in `download/https/` (classi `internal`, D33); la sola eccezione e' la `Network` di
// Android in [ModelHttpsRoute] (R12), un'identita' di rete, non un client.

/**
 * I tetti, le scadenze e i nomi fissi dei modelli, in un posto solo (si rivedono qui, si provano nei test). Cambiarne uno e' una
 * decisione di sicurezza o di costo: va scritta in `docs/decisioni.md`.
 */
object ModelLimits {
  /** Il blocco dello scaricamento e della ripresa (§2.1): si scrive, si sincronizza e si registra il traffico a ogni blocco. */
  const val BLOCK_BYTES: Int = 1 shl 20

  /** E-A3: prima di scaricare servono la dimensione mancante piu' questo margine, altrimenti `NO_SPACE`. */
  const val SPACE_MARGIN_BYTES: Long = 300L * 1024 * 1024

  /** Scadenze dello scaricamento HTTPS (§2.1): 15 s per la connessione, 30 s per ogni lettura. */
  const val CONNECT_TIMEOUT_MS: Int = 15_000
  const val READ_TIMEOUT_MS: Int = 30_000

  /**
   * Quanto dura al massimo una chiamata al fetcher HTTPS: fra una chiamata e l'altra il gestore richiede il verdetto del cancello di
   * rete (se si passa ai dati mobili si smette) e rilegge il tempo che resta.
   */
  val HTTPS_SLICE: Duration = 30.seconds

  /** Una redirezione piu' lunga di questo (caratteri) si rifiuta; i CDN firmano gli indirizzi con una query di qualche centinaio. */
  const val MAX_URL_CHARS: Int = 4096

  /** Quante cartelle di dispositivo si guardano al massimo quando si cercano le copie sul NAS (una famiglia ne ha una manciata). */
  const val MAX_NAS_DEVICES: Int = 256

  /**
   * I5 (revisione di sicurezza della Fase 6): quante copie sul NAS si provano al massimo per uno scaricamento. Dopo il primo errore su una
   * copia (byte sbagliati, NAS che non risponde) non se ne provano altre: si passa a HTTPS, cosi' un dispositivo che mette copie rotte nella
   * sua cartella costa al piu' un tentativo, mai l'intero budget di "Scarica ora".
   */
  const val MAX_SHELF_TRIES: Int = 3

  /** E-A2: dopo questo numero di errori della stessa sorgente la sorgente si salta per [QUARANTINE] (non per l'utente che preme). */
  const val STRIKES_BEFORE_SKIP: Int = 2
  val QUARANTINE: Duration = 24.hours

  /** L'unica intestazione che identifica l'app presso l'host dei modelli (§2.1): nessun identificativo di installazione. */
  const val USER_AGENT: String = "MyCloudGallery"

  /** La cartella visibile dell'area comune dove l'utente copia i file dal PC (D17): non fa parte del formato, l'app la legge soltanto. */
  val PC_FOLDER: List<String> = listOf("MyCloud Gallery", "Modelli")
}

/**
 * I motivi stabili di un [FetchResult.Refused] (codici, mai messaggi con indirizzi o nomi). Quelli in [HOSTILE] sono un file o un
 * server che non si comporta come il catalogo dice (E-S16): il gestore li conta come `AI_MODEL_MISMATCH` e li tratta come un
 * errore della sorgente.
 */
object FetchRefusal {
  /** La sorgente non e' di questo tipo (un indirizzo HTTPS dato al lettore del NAS, o il contrario). */
  const val SOURCE_KIND: String = "source_kind"

  /** Qualcuno ha installato un gestore globale dei cookie: i cookie non si usano mai, quindi non si parte. */
  const val COOKIE_HANDLER: String = "cookie_handler"

  /** Redirezione verso un host fuori dall'elenco ammesso, verso `http` o verso un indirizzo con utente, porta o frammento. */
  const val REDIRECT_HOST: String = "redirect_host"
  const val REDIRECT_SCHEME: String = "redirect_scheme"
  const val REDIRECT_FORMAT: String = "redirect_format"
  const val REDIRECT_LIMIT: String = "redirect_limit"

  /** Il server ha applicato una codifica (gzip, ...) a un file che si chiede grezzo: i conti dei byte non tornerebbero. */
  const val ENCODING: String = "encoding"

  /** Una risposta `206` che non e' quella chiesta: inizio diverso dal punto di ripresa, intervallo che non torna con la lunghezza. */
  const val CONTENT_RANGE: String = "content_range"

  /** Un codice HTTP inatteso (accesso negato, rimosso per motivi legali, ...). */
  const val HTTP_STATUS: String = "http_status"

  /** L'identificativo del dispositivo letto dal NAS non ha la forma di un id (E-S11: un nome del NAS non diventa un percorso). */
  const val DEVICE_ID: String = "device_id"

  /** Il NAS non lascia leggere il file a questo account. */
  const val PERMISSION: String = "permission"

  /** Al posto del file c'e' un collegamento (punto di rianalisi): non si segue mai. */
  const val REPARSE_POINT: String = "reparse_point"

  val HOSTILE: Set<String> = setOf(REDIRECT_HOST, REDIRECT_SCHEME, REDIRECT_FORMAT, REDIRECT_LIMIT, ENCODING, CONTENT_RANGE, REPARSE_POINT)

  fun isHostile(reason: String): Boolean = reason in HOSTILE
}

/**
 * Il traffico dei modelli (§2.1 "Traffico registrato"): chi scarica registra QUANTO a ogni blocco, prima del punto di ripresa, perche'
 * se il processo muore il traffico gia' passato conti. `:app` lo lega al `TrafficLedger` con `TrafficSource.AI_COMPUTE` e la rete
 * effettivamente usata (E-R12); `LedgerModelTraffic` fa proprio questo.
 */
fun interface ModelTraffic {
  suspend fun record(bytes: Long)
}

/**
 * L'area comune della famiglia sul NAS, dove stanno le copie dei modelli (`devices/<id>/models/`) e la cartella dal PC
 * (`MyCloud Gallery/Modelli/`). Null = la famiglia non e' pronta o non c'e' un NAS: restano gli indirizzi HTTPS.
 */
fun interface ModelNasArea {
  suspend fun commonShare(): String?
}

/** Lo spazio libero sul volume di [dir] (E-A3). Di norma `dir.usableSpace`; un fake lo sceglie il test. */
fun interface ModelFreeSpace {
  fun usableBytes(dir: File): Long
}

/** Gli asset dell'APK (`AssetManager.open`): il ritaglio viaggia con l'app (Q11 (a)); `:app` o core-ai la legano. Null = asset assente. */
fun interface BundledAssets {
  fun open(asset: String): InputStream?
}

/**
 * La rete su cui escono le connessioni HTTPS dei modelli (R12 della revisione di sicurezza della Fase 6): quella che il cancello di rete ha
 * giudicato (non a consumo, non in roaming, validata), **non** la rete predefinita del sistema. A casa, con il Wi-Fi riconosciuto, One UI puo'
 * aver reso predefiniti i dati mobili ("Passa ai dati mobili" con un Wi-Fi lento): una connessione aperta sulla rete predefinita esce li', fino a
 * 370 MB senza la conferma, e il registro del traffico li segna come Wi-Fi di casa. [network] si rilegge a **ogni connessione** (ogni fetta
 * di 30 s); `null` vuol dire "nessuna rete approvata adesso": la connessione non si apre e **non** si ripiega sulla rete predefinita.
 * Chi non la da' (i test, un telefono senza il registro di rete) lascia la rete predefinita, com'era.
 */
fun interface ModelHttpsRoute {
  fun network(): android.net.Network?
}

/**
 * I dispositivi della famiglia da cui si accettano copie dei modelli (I5): gli id dei dispositivi **approvati e non dismessi** del registro
 * firmato (non i revocati, non i persi, non i dismessi, non una cartella con la forma di un id che qualcuno ha messo in `devices/`). Null =
 * il registro non e' pronto: nessuna copia si prende dal NAS (restano HTTPS e la cartella dal PC).
 */
fun interface ModelShelfDevices {
  suspend fun trusted(): Set<String>?
}

/**
 * Il cancello di scrittura dello scaffale (F4-D31): `:app` lo lega a `IndexWriteRules.blockFor` (share non pubblicabile, congelata,
 * ora del NAS saltata, area in sola lettura, emergenza). Restituisce un codice del motivo, o null se si puo' scrivere.
 */
fun interface ModelShelfGate {
  suspend fun blockFor(share: String): String?
}

/**
 * I byte di un modello da scrivere sul NAS, letti **per posizione** dal file gia' verificato (nessuno spostamento condiviso: due
 * letture insieme non si disturbano).
 */
interface ShelfSource {
  val size: Long

  /** Legge al piu' [len] byte da [offset]; `-1` oltre la fine. */
  fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int
}

/** L'esito di [ShelfWriter.publishExclusive]. */
sealed interface ShelfWrite {
  /** Il file c'e' con tutti i suoi byte (temporaneo scritto, lunghezza controllata, rinomina senza sostituire). */
  data object Written : ShelfWrite

  /** Il nome esisteva gia': nessun byte e' stato sovrascritto. */
  data object Exists : ShelfWrite

  data object NoSpace : ShelfWrite

  /** Rete, sessione, cancello del NAS: non adesso. */
  data object NetworkError : ShelfWrite

  /** Rifiutato (percorso fuori dalla cartella del proprio dispositivo, sola lettura): niente e' stato scritto. */
  data class Refused(val reason: String) : ShelfWrite
}

/** L'esito di [ShelfWriter.deleteOwn]. */
sealed interface ShelfDelete {
  data object Deleted : ShelfDelete

  data object Missing : ShelfDelete

  /** Il file non ha piu' la dimensione vista dall'elenco: non si tocca. */
  data object Changed : ShelfDelete

  data object Failed : ShelfDelete
}

/**
 * Le due cose che lo scaffale deve poter SCRIVERE sul NAS, solo nella cartella del proprio dispositivo (D18). Sta dietro una porta
 * perche' le primitive di scrittura del NAS le vede soltanto chi ne ha il permesso (`NasFileOps` solo in core-nas e core-nasops,
 * Fase 4) e perche' un modello pesa fino a 370 MiB: serve una scrittura **a flusso**, con un temporaneo, la lunghezza controllata e la
 * rinomina senza sostituire, che oggi `NasFileSystem.publishExclusive` (un `ByteArray`) non puo' fare. L'adattatore vero sta in
 * core-nas (richiesta nel report di P3); il fake e' `InMemoryShelfWriter`.
 *
 * Chi implementa deve rifiutare ([ShelfWrite.Refused]) ogni [SharePath] che non sia `devices/<ownerDeviceId>/models/<sha256>`.
 */
interface ShelfWriter {
  /** Il dispositivo proprietario della cartella in cui si scrive: mai un altro. */
  val ownerDeviceId: String

  /** Scrive [source] in [path] **senza sostituire** mai un file esistente. */
  suspend fun publishExclusive(share: String, path: SharePath, source: ShelfSource): ShelfWrite

  /** Toglie [path] (un file di `devices/<ownerDeviceId>/models/`) solo se ha ancora dimensione [size]. */
  suspend fun deleteOwn(share: String, path: SharePath, size: Long): ShelfDelete
}
