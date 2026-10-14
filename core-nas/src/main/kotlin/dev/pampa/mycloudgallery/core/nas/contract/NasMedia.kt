package dev.pampa.mycloudgallery.core.nas.contract

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.Closeable

// Fase 4A (docs/fase4-progetto.md §1.2 "core-nas (P1, 4A)", D2, D3): la LETTURA dei media del NAS. Contratto e fake
// scritti da P0; implementazione SMB, sorvegliante a progresso e simulatore piu' fedele di P1.
//
// Separato da `NasFileSystem` (core-trust, tre operazioni sui file dell'app) e da `NasFileOps` (4B, scrittura, solo
// core-nasops): chi legge non ha in mano nessuna operazione che modifica, e un test di architettura lo verifica.
// Nessun tipo di smbj qui: i moduli nuovi vedono solo questi.

/**
 * Gli attributi di un file come li riporta il server (`FILE_ATTRIBUTE_*` di MS-FSCC, solo quelli che contano per la
 * galleria). Servono a escludere file nascosti e di sistema (E-N8, E-N16) e a non seguire mai i punti di rianalisi
 * (collegamenti, E-N10).
 */
object NasAttributes {
  const val READ_ONLY: Int = 0x1
  const val HIDDEN: Int = 0x2
  const val SYSTEM: Int = 0x4
  const val DIRECTORY: Int = 0x10
  const val ARCHIVE: Int = 0x20
  const val REPARSE_POINT: Int = 0x400
  const val OFFLINE: Int = 0x1000

  fun has(attributes: Int, flag: Int): Boolean = attributes and flag == flag
}

/**
 * Una voce di una cartella del NAS, con tutto cio' che un elenco SMB da' gratis (`FileIdBothDirectoryInformation`).
 *
 * @property name il nome com'e' sul server (non validato: chi lo usa lo passa da `SharePath`, E-N6).
 * @property modifiedAt `LastWriteTime`, ora del NAS in ms.
 * @property changedAt `ChangeTime` (cambia anche con una rinomina o un cambio di attributi), se il server lo riporta.
 * @property fileId il `FileId` SMB: stabile alla rinomina sullo stesso volume (indizio per E-N1), `null` se assente.
 * @property attributes maschera di [NasAttributes].
 */
data class NasEntry(
  val name: String,
  val isDirectory: Boolean,
  val size: Long,
  val modifiedAt: Long,
  val changedAt: Long?,
  val fileId: Long?,
  val attributes: Int,
) {
  val isHidden: Boolean get() = NasAttributes.has(attributes, NasAttributes.HIDDEN)
  val isSystem: Boolean get() = NasAttributes.has(attributes, NasAttributes.SYSTEM)
  val isReparsePoint: Boolean get() = NasAttributes.has(attributes, NasAttributes.REPARSE_POINT)

  /** Il timbro (D6, D9): cio' che decide se rileggere. */
  val stamp: NasStamp get() = NasStamp(size, modifiedAt, fileId)

  /** Mai il nome nei log: e' un dato dell'utente (§4.3). */
  override fun toString(): String = "NasEntry(dir=$isDirectory, size=$size)"
}

/**
 * Esito di un elenco completo (tutte le pagine consegnate a `onPage`).
 *
 * @property count le voci consegnate.
 * @property cappedAt se la cartella superava il tetto ([NasMediaLimits.MAX_DIR_ENTRIES]): l'elenco si e' fermato li'
 *   e chi chiama **non** deve dedurre assenze dalle voci mancanti.
 */
data class ListOutcome(val count: Int, val cappedAt: Int?)

/**
 * Un file del NAS aperto in lettura, per letture **a intervalli** (impronta `qh1`, metadati, indice dei video,
 * fotogrammi, streaming del viewer). Una sola apertura, molte letture: niente `CREATE` per ogni intervallo.
 *
 * Le letture lanciano `NasIoException` su un errore del NAS (come `NasFileSystem`): un `MediaBytes` sopra questo file
 * la trasforma in "corrotto" o "irraggiungibile" a seconda dell'errore.
 */
interface NasRandomFile : Closeable {
  /** La dimensione vista all'apertura (sullo stesso handle). */
  val size: Long

  /** Il timbro visto all'apertura: chi prepara o verifica lo confronta con quello dell'elenco. */
  val stamp: NasStamp

  /**
   * Legge al piu' [len] byte da [offset] in [buf] a partire da [off]. Restituisce i byte letti, `-1` oltre la fine.
   * Una lettura puo' restituire meno di [len] anche prima della fine.
   */
  suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int

  /**
   * Legge [range] (estremi inclusi, come `MediaBytes.read`); un intervallo oltre la fine si accorcia, uno vuoto o fuori
   * dal file da' un array vuoto. Non alloca mai piu' di `range` ∩ `[0, size)`.
   */
  suspend fun readRange(range: LongRange): ByteArray

  override fun close()
}

/**
 * La lettura dei media del NAS (D2). Ogni metodo apre (o riusa) la sessione dell'account per [share] secondo il
 * [Purpose] (tempi, risveglio dei dischi, E-R15) e passa dal cancello di rete e dall'interruttore come `withShare`.
 * In debug, con un account di prova, la recinzione D33 (`ShareFence`, P1) rifiuta le share fuori da quelle di prova
 * **prima** di qualunque richiesta.
 *
 * Nessun errore come eccezione verso chi chiama: [NasResult]. Le letture dentro un [NasRandomFile] sono l'eccezione
 * (vedi sopra).
 */
interface NasMediaReader {

  /**
   * Elenca [dir] ([dir] null = radice della share) consegnando le voci a pagine di al massimo [pageSize] a [onPage],
   * nell'ordine del server (non garantito), **senza mai tenere l'elenco intero in memoria** (E-N5: 8.910 file in una
   * cartella). `.` e `..` non arrivano. Si ferma a [NasMediaLimits.MAX_DIR_ENTRIES] voci (`cappedAt`).
   * Un'eccezione lanciata da [onPage] interrompe l'elenco e risale a chi chiama.
   */
  suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int = NasMediaLimits.DEFAULT_PAGE_SIZE,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome>

  /** Lo stato di un file o di una cartella: `null` se non esiste. Una `CREATE` + `QUERY_INFO` + `CLOSE`. */
  suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?>

  /** Apre [path] in lettura (condivisione lettura e scrittura: un file aperto dal PC si legge, E-N13). */
  suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile>

  /**
   * Legge [file] per intero, in ordine, consegnando i blocchi a [sink] (buffer e lunghezza valida; il buffer si
   * riusa dopo il ritorno) e calcolando lo SHA-256 mentre arriva. Implementazione vera: 4 flussi paralleli a blocchi
   * di 1 MiB (16 MB/s misurati), riordinati prima del [sink]; sorvegliante **per blocco** (D3), mai una scadenza sul
   * totale finche' i byte arrivano. [progress] riceve i byte consegnati finora.
   */
  suspend fun readAll(
    file: NasRandomFile,
    sink: (ByteArray, Int) -> Unit,
    progress: ((Long) -> Unit)? = null,
  ): NasResult<Sha256>
}

/** I tetti della lettura dei media (§1.2, §2.3). */
object NasMediaLimits {
  /** Pagina di un elenco (E-N5). */
  const val DEFAULT_PAGE_SIZE: Int = 1000

  /** Tetto per cartella: oltre, l'elenco e' "troncato" e la scansione non segna assenze in quella cartella. */
  const val MAX_DIR_ENTRIES: Int = 200_000

  /** `readAll`: flussi paralleli e dimensione del blocco (§2.10: 16 MB/s con 4 flussi). */
  const val READ_ALL_STREAMS: Int = 4
  const val READ_ALL_CHUNK_BYTES: Int = 1024 * 1024

  /** Sorvegliante a progresso (D3): scadenza per blocco e soglia anti-blocco (E-R14, invariata). */
  const val PER_CHUNK_TIMEOUT_INTERACTIVE_MS: Long = 15_000
  const val PER_CHUNK_TIMEOUT_BACKGROUND_MS: Long = 60_000
  const val STALL_MIN_BYTES_PER_SECOND: Long = 32 * 1024
  const val STALL_WINDOW_MS: Long = 20_000
}

/**
 * La copia lato server (`SmbServerSideCopy`, oggi classe concreta senza contratto): P1 la fa implementare da quella
 * classe; la usa solo `core-nasops` (4B, D24 e E-D12). Come `publishExclusive`: temporaneo, verifica della lunghezza,
 * rinomina **senza sovrascrivere** (`NasError.Exists`); sorgente assente = `NasError.NotFound`. Nessuna operazione
 * distruttiva sulla sorgente. Il risultato e' il numero di byte copiati.
 */
interface ServerCopy {
  suspend fun copy(fromShare: String, from: SharePath, toShare: String, to: SharePath): NasResult<Long>
}
