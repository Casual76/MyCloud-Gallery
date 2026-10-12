package dev.pampa.mycloudgallery.core.nas.smb

import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import java.io.Closeable
import java.io.IOException
import kotlin.time.Duration

// Il confine tra la logica della sessione (P1) e la libreria SMB (docs/fase2-progetto.md §5.1).
//
// Tutto cio' che sta sopra (`SmbNasSessionManager`, `SmbNasFileSystem`, il test di connessione) parla solo con
// queste interfacce: in produzione le implementa `SmbjTransport` (adattatore sottile su smbj, provato contro il
// NAS vero), nei test `testing.InMemorySmbBackend` (simulatore con semantica Samba). Nessun tipo di smbj compare
// qui. Le chiamate sono BLOCCANTI: chi le usa e' gia' su un dispatcher di I/O.
//
// Gli errori arrivano come eccezioni di questo file (o come `ConnectException`/`SocketTimeoutException` del
// connect TCP): la traduzione in `NasError` (tabella del §3.4) e' una sola, in `session/SmbErrors.kt`.

/** Apre connessioni SMB. Una connessione = un socket legato a una rete (D1) + la NEGOTIATE. */
interface SmbTransport {

  /**
   * TCP verso [address] attraverso [binding] (mai la rete predefinita, mai DNS: l'indirizzo e' un IP letterale),
   * con [connectTimeout] sulla connect; poi la NEGOTIATE (solo SMB 3.1.1, cifratura annunciata, firma
   * obbligatoria). **Non autentica**: nessuna credenziale passa di qui.
   */
  @Throws(IOException::class)
  fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection
}

/**
 * Cosa ha detto il NAS alla NEGOTIATE, prima di qualunque autenticazione (D3).
 *
 * @property serverGuid il GUID del server in forma testuale minuscola (`xxxxxxxx-xxxx-...`).
 * @property nasTimeOffsetMs l'ora del NAS meno l'ora del dispositivo (null se il server non l'ha detta).
 * @property nasTimeAtNegotiateMs l'ora del NAS (ms dall'epoca) all'arrivo della risposta, stimata con
 *   l'orologio del dispositivo in quel momento: serve ad ancorare `SessionNasClock` all'orologio monotono.
 */
class Negotiation(
  val dialect: SmbDialect,
  val encryptionOffered: Boolean,
  val cipher: String?,
  val serverGuid: String,
  val serverRequiresSigning: Boolean,
  val nasTimeOffsetMs: Long?,
  val nasTimeAtNegotiateMs: Long?,
) {
  override fun toString(): String =
    "Negotiation($dialect, cifratura offerta=$encryptionOffered, cifrario=$cipher, guid=${serverGuid.take(GUID_SHOWN)}…)"

  private companion object {
    const val GUID_SHOWN = 8
  }
}

/** Una connessione negoziata e non ancora autenticata. La chiude solo il gestore della sessione. */
interface SmbConnection : Closeable {
  val negotiation: Negotiation

  /** Falso dopo [close] o dopo una caduta osservata dalla libreria. */
  val isConnected: Boolean

  /**
   * UNA autenticazione NTLMv2: ogni chiamata e' un tentativo di login sul NAS (rischio di blocco, §3.3). Chi la
   * chiama deve essere passato dall'interruttore di sicurezza. [password] non viene conservata dall'adattatore
   * oltre la durata della chiamata (smbj ne tiene una copia che l'adattatore azzera subito dopo).
   */
  @Throws(IOException::class)
  fun authenticate(user: String, password: CharArray): SmbSession

  /** Chiude il socket: ogni chiamata in corso su questa connessione fallisce subito. Mai eccezioni. */
  override fun close()
}

/**
 * Come e' venuta la sessione dopo l'autenticazione (controllo 7 del §3.1).
 *
 * @property encrypted la cifratura e' davvero attiva (`Session.shouldEncryptData()`): `withEncryptData(true)` la
 *   annuncia soltanto e, se il server non la concede, smbj ripiega in silenzio sulla firma (research §1.4).
 * @property signingRequired la firma e' obbligatoria per la sessione. smbj la mette a falso quando e' il server a
 *   imporre la cifratura (che autentica gia' ogni messaggio).
 */
class SessionFacts(
  val encrypted: Boolean,
  val guest: Boolean,
  val anonymous: Boolean,
  val signingRequired: Boolean,
) {
  override fun toString(): String =
    "SessionFacts(cifrata=$encrypted, guest=$guest, anonima=$anonymous, firmaObbligatoria=$signingRequired)"
}

/** Una sessione autenticata. Le share si aprono una volta e si riusano (le chiude solo il gestore). */
interface SmbSession : Closeable {
  val facts: SessionFacts

  /** TREE_CONNECT. `0xC00000CC` se la share non esiste, `0xC0000022` se non e' accessibile. */
  @Throws(IOException::class)
  fun connectShare(name: String): SmbShare

  override fun close()
}

/** Che cosa si chiede aprendo una cartella. */
enum class DirAccess {
  /** `FILE_LIST_DIRECTORY`: elencare. */
  LIST,

  /**
   * `FILE_ADD_FILE`: il diritto di creare file nella cartella. Aprire la cartella con questo diritto prova la
   * scrittura **senza creare nessun file** (`ShareProbe`).
   */
  ADD_FILE,
}

/** Una voce di cartella. [lastWriteMs] e' l'ora del NAS (ms dall'epoca). */
class SmbDirEntry(val name: String, val isDirectory: Boolean, val size: Long, val lastWriteMs: Long)

/** Spazio libero visto dal NAS (`FileFsFullSizeInformation`): per l'utente (quota) e per tutto il volume. */
class SmbSpace(val callerFreeBytes: Long, val totalFreeBytes: Long)

/**
 * Una share connessa. I percorsi sono relativi alla radice della share, con `\` come separatore e `""` per la
 * radice. Nessuna operazione segue collegamenti DFS (disattivato).
 */
interface SmbShare : Closeable {
  val name: String

  /** Apre un file in sola lettura (`FILE_OPEN`, mai una cartella). `0xC0000034`/`0xC000003A` se non c'e'. */
  @Throws(IOException::class)
  fun openRead(path: String): SmbReadFile

  /**
   * Crea un file nuovo in esclusiva (`FILE_CREATE` = `O_CREAT|O_EXCL` in Samba): `0xC0000035` se il nome
   * esiste gia', anche con maiuscole diverse. L'handle ha il diritto `DELETE` (serve alla rinomina e alla
   * pulizia del proprio temporaneo).
   */
  @Throws(IOException::class)
  fun createExclusive(path: String): SmbWriteFile

  /** Crea una cartella (`FILE_CREATE`): `0xC0000035` se esiste gia'. */
  @Throws(IOException::class)
  fun mkdir(path: String)

  @Throws(IOException::class)
  fun openDirectory(path: String, access: DirAccess): SmbDirectory

  /** Cancella un file. Usata SOLO per i temporanei scritti da questo dispositivo (E-S11). */
  @Throws(IOException::class)
  fun deleteFile(path: String)

  /** Spazio libero. Tocca i dischi: si chiama solo dopo un `DISK_FULL` (§3.4). */
  @Throws(IOException::class)
  fun space(): SmbSpace

  override fun close()
}

interface SmbReadFile : Closeable {
  /** La dimensione dichiarata dal server (`EndOfFile`). Non ci si fida: si legge comunque con un tetto. */
  val declaredSize: Long

  /** Legge al piu' [length] byte da [fileOffset]; -1 a fine file. */
  @Throws(IOException::class)
  fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int

  /**
   * La chiave di ripresa del file (`FSCTL_SRV_REQUEST_RESUME_KEY`, 24 byte) per la copia lato server: vale
   * nella stessa sessione, anche verso un'altra share (Samba controlla la sessione, non la share).
   */
  @Throws(IOException::class)
  fun resumeKey(): ByteArray

  override fun close()
}

/** Un tratto di copia lato server: [length] byte da [sourceOffset] del file sorgente a [targetOffset] della destinazione. */
class SmbCopyChunk(val sourceOffset: Long, val targetOffset: Long, val length: Long)

interface SmbWriteFile : Closeable {
  /** Scrive [length] byte a [fileOffset]; restituisce quanti ne ha scritti il server. */
  @Throws(IOException::class)
  fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int

  @Throws(IOException::class)
  fun flush()

  /** La lunghezza secondo il server (verifica dopo la scrittura). */
  @Throws(IOException::class)
  fun length(): Long

  /** Rinomina senza mai sovrascrivere: `0xC0000035` se la destinazione esiste (anche con maiuscole diverse). */
  @Throws(IOException::class)
  fun renameNoReplace(newPath: String)

  /** Il file sparira' alla chiusura dell'handle (pulizia del proprio temporaneo). */
  @Throws(IOException::class)
  fun deleteOnClose()

  /**
   * Copia lato server (`FSCTL_SRV_COPYCHUNK_WRITE`) dei [chunks] del file con la chiave [resumeKey] dentro questo
   * file: i byte non passano dal telefono. Restituisce i byte scritti dal server.
   */
  @Throws(IOException::class)
  fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long

  override fun close()
}

/** Una cartella aperta. [entries] scorre l'elenco a pagine (`Directory.iterator`), senza `.` e `..`. */
interface SmbDirectory : Closeable {
  @Throws(IOException::class)
  fun entries(): Iterator<SmbDirEntry>

  override fun close()
}

// --- Errori del trasporto ----------------------------------------------------------------------------------

/** Radice degli errori SMB. Il messaggio non contiene mai nomi, percorsi, utenti o indirizzi. */
open class SmbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Il server ha risposto con un codice NTSTATUS di errore ([status] senza segno, es. `0xC000006D`). */
class SmbStatusException(val status: Long, cause: Throwable? = null) :
  SmbException("NTSTATUS 0x%08X".format(java.util.Locale.ROOT, status), cause)

/** La connessione e' caduta (`TransportException`, `SocketException`, socket chiuso). */
class SmbConnectionLostException(cause: Throwable? = null) : SmbException("connessione SMB persa", cause)

/** Una richiesta non ha avuto risposta in tempo (timeout della libreria): la connessione e' sospetta. */
class SmbTimeoutException(cause: Throwable? = null) : SmbException("nessuna risposta SMB in tempo", cause)

/** Il server ha offerto una sessione guest con la firma obbligatoria (`SMB2GuestSigningRequiredException`). */
class SmbGuestRejectedException(cause: Throwable? = null) : SmbException("sessione guest rifiutata", cause)

/** I crediti SMB sono finiti ("Not enough credits"): si riprova, non e' un guasto del NAS. */
class SmbCreditsExhaustedException(cause: Throwable? = null) : SmbException("crediti SMB esauriti", cause)

/** La NEGOTIATE non e' andata a buon fine (nessun dialetto comune: il server non offre SMB 3.1.1). */
class SmbNegotiationException(cause: Throwable? = null) : SmbException("negoziazione SMB fallita", cause)

/** I codici NTSTATUS che la sessione distingue (MS-ERREF; tabella del §3.4). */
object NtStatus {
  const val LOGON_FAILURE: Long = 0xC000006DL
  const val WRONG_PASSWORD: Long = 0xC000006AL
  const val ACCOUNT_LOCKED_OUT: Long = 0xC0000234L
  const val ACCOUNT_DISABLED: Long = 0xC0000072L
  const val PASSWORD_EXPIRED: Long = 0xC0000071L
  const val PASSWORD_MUST_CHANGE: Long = 0xC0000224L
  const val ACCOUNT_RESTRICTION: Long = 0xC000006EL
  const val INVALID_LOGON_HOURS: Long = 0xC000006FL
  const val INVALID_WORKSTATION: Long = 0xC0000070L
  const val ACCESS_DENIED: Long = 0xC0000022L
  const val BAD_NETWORK_NAME: Long = 0xC00000CCL
  const val DISK_FULL: Long = 0xC000007FL
  const val SHARING_VIOLATION: Long = 0xC0000043L
  const val FILE_LOCK_CONFLICT: Long = 0xC0000054L
  const val OBJECT_NAME_COLLISION: Long = 0xC0000035L
  const val OBJECT_NAME_NOT_FOUND: Long = 0xC0000034L
  const val OBJECT_PATH_NOT_FOUND: Long = 0xC000003AL
  const val USER_SESSION_DELETED: Long = 0xC0000203L
  const val NETWORK_SESSION_EXPIRED: Long = 0xC000035CL
  const val NOT_A_DIRECTORY: Long = 0xC0000103L
  const val FILE_IS_A_DIRECTORY: Long = 0xC00000BAL
  const val MEDIA_WRITE_PROTECTED: Long = 0xC00000A2L
  const val DELETE_PENDING: Long = 0xC0000056L
  const val NO_SUCH_FILE: Long = 0xC000000FL
}
