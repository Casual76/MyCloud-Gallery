package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

// LE PORTE DI shared/ (P2). Le lega :app (P13) come quelle di core-index e core-personal: l'identita' di questo dispositivo (chiave nel
// Keystore, registro accettato), il permesso di leggere gli album di una share (D30), le scelte locali dell'utente sugli album degli altri.
// La replica e il pubblicatore tengono il loro stato dietro due store ([AlbumReplicaStore], [AlbumPublishStore]): finche' lo schema v6 di
// P1 non e' su main valgono le implementazioni in memoria di questo pacchetto; gli adattatori Room sono di P1/P13 (docs/decisioni.md,
// "Fase 7 P2").

/** Chi sono per gli album condivisi: il dispositivo, la sua chiave di firma e il registro di famiglia accettato (null = famiglia non pronta). */
interface AlbumIdentity {
  val deviceId: String
  val signer: Signer

  fun registry(): RegistryState?
}

/**
 * D30: si leggono gli album della share [share]? :app lo lega a `FeatureFlags.sharedAlbumsMayUse` (solo le share di prova finche' vale la
 * recinzione): sulle share vere non si mostra nessun album, vero o falso, prima del consenso (Q7).
 */
fun interface AlbumReadGate {
  fun mayRead(share: String): Boolean
}

/**
 * Le scelte di questo account sugli album degli altri (dati personali, P3/P11): album lasciati (`shared_album_left:<id>`, E-SH21) e avvisi
 * gia' visti. Le scelte stanno nell'area privata, mai nella definizione comune.
 */
interface SharedAlbumChoices {
  suspend fun left(): Set<AlbumId>

  suspend fun setLeft(album: AlbumId, left: Boolean)

  suspend fun dismissed(): Set<String>

  suspend fun dismiss(notice: String)
}

/** Le scelte in memoria (prove e build senza dati personali legati). */
class MemorySharedAlbumChoices : SharedAlbumChoices {
  private val lock = Any()
  private val leftSet = LinkedHashSet<AlbumId>()
  private val seen = LinkedHashSet<String>()

  override suspend fun left(): Set<AlbumId> = synchronized(lock) { leftSet.toSet() }

  override suspend fun setLeft(album: AlbumId, left: Boolean) {
    synchronized(lock) { if (left) leftSet += album else leftSet -= album }
  }

  override suspend fun dismissed(): Set<String> = synchronized(lock) { seen.toSet() }

  override suspend fun dismiss(notice: String) {
    synchronized(lock) { seen += notice }
  }
}

/**
 * I tetti di un giro della replica (D8, A4; isolamento degli errori come la revisione A della Fase 6): un album ostile o enorme non ferma gli
 * altri ne' la sincronizzazione dell'indice, e un dispositivo compromesso non riempie il telefono. R2 e R13 della revisione di sicurezza della
 * Fase 7: i tetti in byte si controllano **prima** di scaricare (le dimensioni le dichiara la testa firmata), e un contributo che non si puo'
 * leggere adesso aspetta a intervalli crescenti invece di farsi riscaricare a ogni giro.
 */
data class AlbumSyncLimits(
  /** Cartelle in `albums/` lette per giro (oltre: l'elenco e' "inondato", si leggono comunque gli album gia' noti e i propri). */
  val maxAlbumsListed: Int = 1_024,
  /** Letture per nome delle teste, per giro e per share (come `MAX_HEAD_READS_PER_SHARE` dell'indice, ma per tutti gli album). */
  val maxHeadReads: Int = 4_096,
  /** Byte di segmenti scaricati per giro (il resto si riprende al giro dopo). */
  val maxBytesPerRound: Long = 64L * 1024 * 1024,
  /**
   * R2: byte di segmenti di **un** dispositivo per giro. Il suo primo contributo del giro passa comunque (entro [maxBytesPerRound]: un
   * contributo onesto grande si scarica sempre), gli altri aspettano il giro dopo: un dispositivo non si prende il giro di tutti.
   */
  val maxBytesPerDeviceRound: Long = 32L * 1024 * 1024,
  /** R13: byte dei segmenti tenuti nella replica per dispositivo, in tutti gli album insieme; si controlla prima di scaricare. */
  val maxBytesPerDevice: Long = 128L * 1024 * 1024,
  /** Voci tenute nella replica per dispositivo, in tutti gli album insieme. */
  val maxEntriesPerDevice: Int = 100_000,
  /** Album a cui un dispositivo contribuisce, nella replica (R13: si controlla prima di scaricare). */
  val maxAlbumsPerDevice: Int = 512,
  /** R2: la prima attesa di un contributo che non si legge adesso (un segmento sparito, un file illeggibile); poi raddoppia. */
  val holdFirstMs: Long = 15L * 60 * 1000,
  /** R2: l'attesa piu' lunga fra due tentativi dello stesso contributo. */
  val holdMaxMs: Long = 24L * 60 * 60 * 1000,
  /**
   * R7: ogni quanto si aggiorna, al piu', l'ora in cui si e' visto che la serie di un dispositivo non aveva niente di nuovo (una scrittura per
   * contributo e per ora, non per giro). Le voci si possono retrodatare al piu' di questo, piu' l'eta' massima di una modifica.
   */
  val seenRefreshMs: Long = 60L * 60 * 1000,
) {
  /** L'attesa dopo il tentativo numero [attempts] (1 = il primo fallito). */
  fun holdFor(attempts: Int): Long {
    var wait = holdFirstMs
    repeat((attempts - 1).coerceIn(0, 30)) { wait = minOf(holdMaxMs, wait * 2) }
    return minOf(holdMaxMs, wait)
  }
}

/**
 * R2: un errore di I/O e' di **un file** (quel contributo aspetta, il giro continua: un permesso negato, un file tenuto aperto dal PC) o della
 * **sessione** (il NAS non risponde: il giro si ferma)? core-albums non vede core-nas: lo dice :app, che conosce gli errori del NAS. Senza
 * legame ogni errore vale "sessione" (il comportamento di prima: il giro si ferma e riprova).
 */
fun interface AlbumIoErrors {
  fun isFileError(error: java.io.IOException): Boolean

  companion object {
    val SESSION: AlbumIoErrors = AlbumIoErrors { false }
  }
}
