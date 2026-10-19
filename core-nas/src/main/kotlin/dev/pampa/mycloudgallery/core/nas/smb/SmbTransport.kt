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

/**
 * Una voce di cartella (`FileIdBothDirectoryInformation`). [lastWriteMs] e [changeMs] sono ore del NAS (ms
 * dall'epoca); [fileId] e' il `FileId` del server (stabile alla rinomina sullo stesso volume), [attributes] la maschera
 * `FILE_ATTRIBUTE_*` (MS-FSCC 2.6).
 */
class SmbDirEntry(
  val name: String,
  val isDirectory: Boolean,
  val size: Long,
  val lastWriteMs: Long,
  val changeMs: Long? = null,
  val fileId: Long? = null,
  val attributes: Int = if (isDirectory) ATTRIBUTE_DIRECTORY else ATTRIBUTE_ARCHIVE,
) {
  override fun toString(): String = "SmbDirEntry(dir=$isDirectory, size=$size)"

  companion object {
    const val ATTRIBUTE_DIRECTORY: Int = 0x10
    const val ATTRIBUTE_ARCHIVE: Int = 0x20
  }
}

/**
 * Lo stato di un file o di una cartella letto **su un handle** (`FileAllInformation`: una `QUERY_INFO`). Serve alla
 * lettura dei media (Fase 4, D2): dimensione, data, `fileId` e attributi visti dallo stesso handle da cui si legge.
 *
 * @property name il nome com'e' sul server (l'ultima parte di `FileNameInformation`), se il server l'ha detto.
 * @property links quanti nomi ha il file (`NumberOfLinks` di `FileStandardInformation`): piu' di 1 = un collegamento fisico (R3 della
 *   revisione 5A). Null se il trasporto non lo dice.
 */
class SmbFileInfo(
  val name: String?,
  val isDirectory: Boolean,
  val size: Long,
  val lastWriteMs: Long,
  val changeMs: Long?,
  val fileId: Long?,
  val attributes: Int,
  val links: Long? = null,
) {
  override fun toString(): String = "SmbFileInfo(dir=$isDirectory, size=$size)"
}

/**
 * Spazio libero visto dal NAS (`FileFsFullSizeInformation`): per l'utente (quota) e per tutto il volume.
 *
 * @property totalBytes la dimensione del volume (Fase 4B, Spazio NAS), se il trasporto la riporta.
 */
class SmbSpace(val callerFreeBytes: Long, val totalFreeBytes: Long, val totalBytes: Long? = null)

/**
 * Un file o una cartella **esistenti** aperti per cambiarli (Fase 4B, solo `ops/`): `CREATE` con `FILE_OPEN` e il diritto
 * `DELETE`, lo stato letto **sullo stesso handle** ([info]), poi una rinomina senza sostituire o la cancellazione alla
 * chiusura, sempre su questo handle (nessun controllo-poi-uso su due aperture diverse, formato §9.3, revisione B7).
 *
 * Se il file e' aperto da un altro programma senza condividere la cancellazione (il PC), l'apertura risponde
 * `SHARING_VIOLATION` (E-N13).
 */
interface SmbChangeHandle : Closeable {
  /** Lo stato all'apertura, letto su questo handle (`FileAllInformation`). */
  val info: SmbFileInfo

  /** Rinomina senza mai sostituire: `0xC0000035` se la destinazione esiste (anche con maiuscole diverse). */
  @Throws(IOException::class)
  fun renameNoReplace(newPath: String)

  /**
   * L'elemento sparira' alla chiusura dell'handle (`FileDispositionInformation`). Per una cartella non vuota il server
   * risponde `0xC0000101` subito.
   */
  @Throws(IOException::class)
  fun deleteOnClose()

  override fun close()
}

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

  /**
   * Lo stato di un file o di una cartella: `CREATE` con il solo `FILE_READ_ATTRIBUTES` + `QUERY_INFO` + `CLOSE`.
   * `0xC0000034`/`0xC000003A` se non c'e'. Nessun diritto di lettura dei dati: non blocca chi scrive (E-N13).
   */
  @Throws(IOException::class)
  fun stat(path: String): SmbFileInfo

  /**
   * Toglie una cartella **vuota** (`0xC0000101` se non lo e'). Solo per `ops/` (4B, `rmdirIfEmpty`) e per la pulizia
   * dell'area di prova dei test d'integrazione: nessun altro chiamante (E-S11).
   */
  @Throws(IOException::class)
  fun removeEmptyDirectory(path: String)

  /** Cancella un file. Usata SOLO per i temporanei scritti da questo dispositivo (E-S11). */
  @Throws(IOException::class)
  fun deleteFile(path: String)

  /** Spazio libero. Tocca i dischi: si chiama solo dopo un `DISK_FULL` (§3.4) o su richiesta dell'utente (Spazio NAS, 4B). */
  @Throws(IOException::class)
  fun space(): SmbSpace

  /**
   * Fase 4B, solo per le primitive di scrittura (`ops/SmbNasFileOps`): apre il file o la cartella **esistente** [path] per
   * rinominarlo o cancellarlo sullo stesso handle ([SmbChangeHandle]). Con [exclusive] nessun altro lo puo' aprire finche'
   * l'handle e' aperto (cancellazione con il controllo della dimensione). `0xC0000034`/`0xC000003A` se non c'e',
   * `0xC0000043` se e' aperto da un altro programma.
   *
   * Di default non c'e' (`NOT_SUPPORTED`): un trasporto che non la implementa non cambia niente.
   */
  @Throws(IOException::class)
  fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle = throw SmbStatusException(NtStatus.NOT_SUPPORTED)

  /**
   * Fase 5A (D5), solo per la ripresa del **proprio** temporaneo del backup (`ops/SmbNasFileOps.reopenBackupTemp`): apre il file
   * **esistente** [path] per scriverci **senza crearlo** (`FILE_OPEN`, mai `FILE_OPEN_IF`), in esclusiva come il temporaneo appena
   * creato ([SmbOpenMode.CREATE_EXCLUSIVE]), senza seguire un punto di rianalisi (`FILE_OPEN_REPARSE_POINT`). Lo stato si legge sullo
   * stesso handle ([SmbWriteFile.info]): chi chiama rifiuta una cartella, un punto di rianalisi e (R3 della revisione 5A) un file con piu'
   * di un nome o con un'identita' diversa da quella del proprio temporaneo. `0xC0000034`/`0xC000003A` se non c'e', `0xC0000043` se e'
   * aperto da un altro programma.
   *
   * Di default non c'e' (`NOT_SUPPORTED`): un trasporto che non la implementa non riprende niente (il backup ricomincia da zero).
   */
  @Throws(IOException::class)
  fun openExistingForWrite(path: String): SmbWriteFile = throw SmbStatusException(NtStatus.NOT_SUPPORTED)

  override fun close()
}

interface SmbReadFile : Closeable {
  /** La dimensione dichiarata dal server (`EndOfFile`). Non ci si fida: si legge comunque con un tetto. */
  val declaredSize: Long

  /** Lo stato visto all'apertura **sullo stesso handle** (data, `fileId`, attributi); null se il server non l'ha dato. */
  val info: SmbFileInfo? get() = null

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

  /**
   * Fase 5A (D5): lo stato visto all'apertura **sullo stesso handle** (attributi, `fileId`), per [SmbShare.openExistingForWrite]: chi
   * riprende un temporaneo rifiuta una cartella o un punto di rianalisi. Null se il trasporto non lo dice (un file appena creato).
   */
  val info: SmbFileInfo? get() = null

  /**
   * Fase 5A (D5): la fine del file a [length] byte **sullo stesso handle** (`SET_INFO` con `FileEndOfFileInformation`): accorcia il
   * proprio temporaneo al punto di ripresa salvato. Di default non c'e' (`NOT_SUPPORTED`).
   */
  @Throws(IOException::class)
  fun setLength(length: Long): Unit = throw SmbStatusException(NtStatus.NOT_SUPPORTED)

  /**
   * R3 della revisione 5A: lo stato del file letto **adesso, sullo stesso handle** (una `QUERY_INFO` con `FileAllInformation`): lunghezza,
   * identita' (`fileId`) e nomi (`NumberOfLinks`). Il punto di ripresa del backup ci legge l'identita' del proprio temporaneo, che la
   * riapertura confronta prima di accorciare. Di default non c'e' (`NOT_SUPPORTED`).
   */
  @Throws(IOException::class)
  fun query(): SmbFileInfo = throw SmbStatusException(NtStatus.NOT_SUPPORTED)

  override fun close()
}

/** Una cartella aperta. [entries] scorre l'elenco a pagine (`Directory.iterator`), senza `.` e `..`. */
interface SmbDirectory : Closeable {
  @Throws(IOException::class)
  fun entries(): Iterator<SmbDirEntry>

  /**
   * R3 della revisione 4B: il `FileId` della voce `..` di questa cartella come la riporta il server, cioe' della cartella che la contiene
   * **sul disco**. Samba (`follow symlinks = yes`) presenta un collegamento simbolico a una cartella come una cartella normale, ma risolve
   * `..` dopo il collegamento: il genitore riportato e' quello della destinazione, non quello da cui si e' arrivati. Null se il server non
   * lo dice (o il trasporto non lo sa leggere): allora non prova niente.
   */
  @Throws(IOException::class)
  fun parentFileId(): Long? = null

  override fun close()
}

/**
 * Con quali diritti e quali **condivisioni** il trasporto apre un file o una cartella (`DesiredAccess` e `ShareAccess` della `CREATE`).
 * Le scrive qui, una volta, sia `SmbjTransport` (che le traduce per smbj) sia il simulatore (che le applica come Samba): un handle
 * ancora aperto sul NAS (una chiusura in volo, una connessione che il NAS non ha ancora visto cadere) fa rispondere
 * `SHARING_VIOLATION` a chi apre lo stesso file con un diritto che quell'handle non condivide, **anche se e' dello stesso telefono**.
 *
 * Contano solo i diritti sui dati e la cancellazione: leggere gli attributi (`stat`) non entra in conflitto con nessuno.
 */
data class SmbOpenMode(
  val read: Boolean,
  val write: Boolean,
  val delete: Boolean,
  val shareRead: Boolean,
  val shareWrite: Boolean,
  val shareDelete: Boolean,
) {
  /** Due aperture dello stesso file non stanno insieme (regola di Windows e di Samba, in tutte e due le direzioni). */
  fun conflictsWith(other: SmbOpenMode): Boolean =
    (read && !other.shareRead) || (write && !other.shareWrite) || (delete && !other.shareDelete) ||
      (other.read && !shareRead) || (other.write && !shareWrite) || (other.delete && !shareDelete)

  companion object {
    /**
     * La lettura (media, verifiche, copia lato server): condivide **anche la cancellazione**. Un nostro handle di lettura (una chiusura
     * ancora in volo, la Libreria che sta leggendo, una connessione caduta che il NAS tiene ancora) non deve mai impedire la rinomina o
     * il cestino di quel file, a noi (P16, prova F16 sul NAS vero) o al PC. Chi cambia il contenuto (scrittura) resta escluso dalle regole
     * di chi cambia ([change]): leggere non da' a nessuno il permesso di scrivere.
     */
    val READ: SmbOpenMode = SmbOpenMode(read = true, write = false, delete = false, shareRead = true, shareWrite = true, shareDelete = true)

    /** Il temporaneo creato in esclusiva: nessuno lo apre finche' lo si scrive. */
    val CREATE_EXCLUSIVE: SmbOpenMode =
      SmbOpenMode(read = true, write = true, delete = true, shareRead = false, shareWrite = false, shareDelete = false)

    /** Una cartella aperta per elencarla (o per provare il diritto di aggiungere file, [DirAccess.ADD_FILE]): condivide tutto. */
    fun directory(access: DirAccess): SmbOpenMode = SmbOpenMode(
      read = access == DirAccess.LIST, write = access == DirAccess.ADD_FILE, delete = false,
      shareRead = true, shareWrite = true, shareDelete = true,
    )

    /**
     * L'handle per cambiare un file esistente ([SmbShare.openForChange]): il diritto `DELETE`; condivide solo la lettura (nessuno ci
     * scrive mentre si controlla il timbro e si rinomina), niente con [exclusive] (la cancellazione con il controllo della dimensione).
     */
    fun change(exclusive: Boolean): SmbOpenMode =
      SmbOpenMode(read = false, write = false, delete = true, shareRead = !exclusive, shareWrite = false, shareDelete = false)

    /** La cancellazione per nome ([SmbShare.deleteFile], [SmbShare.removeEmptyDirectory]): `DELETE`, condivide tutto. */
    val DELETE_BY_NAME: SmbOpenMode =
      SmbOpenMode(read = false, write = false, delete = true, shareRead = true, shareWrite = true, shareDelete = true)
  }
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

/** Perche' una sessione autenticata non e' credibile (domanda aperta 1 della revisione 2, P1 della Fase 4). */
enum class SessionDefect {
  /**
   * La risposta finale di `SESSION_SETUP` non e' firmata, o la firma non torna con la chiave della sessione. In SMB
   * 3.1.1 la firma e' obbligatoria e prova che il server conosce la password: un impostore che ha solo raccolto la
   * risposta NTLMv2 non la sa calcolare. smbj 0.15 non la controlla.
   */
  UNSIGNED_FINAL_RESPONSE,

  /**
   * Una risposta in chiaro (ne' cifrata ne' firmata) su una sessione cifrata. smbj 0.15 la accetta quando il server ha
   * chiesto la cifratura (non pretende piu' la firma) o quando la risposta dichiara la sessione 0.
   */
  PLAINTEXT_RESPONSE,
}

/**
 * La sessione si e' rivelata insicura **dopo** il login (vedi [SessionDefect]): la connessione e' gia' chiusa, nessun
 * dato ricevuto e' stato consegnato. Non si riprova sulla stessa rete (come gli altri rifiuti di sicurezza).
 */
class SmbInsecureSessionException(val defect: SessionDefect, cause: Throwable? = null) :
  SmbException("sessione SMB insicura: $defect", cause)

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
  const val DIRECTORY_NOT_EMPTY: Long = 0xC0000101L
  const val NOT_SUPPORTED: Long = 0xC00000BBL
  const val INVALID_PARAMETER: Long = 0xC000000DL
}
