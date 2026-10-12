package dev.pampa.mycloudgallery.spike.nasprobe

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

const val OWNED_PREFIX = ".nasprobe-"
const val MIB = 1024 * 1024

/** Connessione SMB autenticata verso la share principale. Si chiude tutto con `close()`. */
class SmbConn(
    val client: SMBClient,
    val connection: Connection,
    val session: Session,
    val share: DiskShare?,
    val encryptRequested: Boolean,
) : AutoCloseable {
    private var share2: DiskShare? = null

    /** Apre (una sola volta) la seconda share sulla stessa sessione. */
    fun openShare2(name: String): DiskShare {
        share2?.let { return it }
        val sh = session.connectShare(name) as DiskShare
        share2 = sh
        return sh
    }

    override fun close() {
        listOf<() -> Unit>(
            { share2?.close() },
            { share?.close() },
            { session.close() },
            { connection.close() },
            { client.close() },
        ).forEach { step ->
            try {
                step()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * Configurazione smbj: solo SMB 3.x, firma obbligatoria, cifratura secondo l'interruttore.
 * Il resto resta ai valori predefiniti della libreria (buffer da 1 MiB, timeout 60 s).
 */
fun buildSmbConfig(encrypt: Boolean, socketFactory: SocketFactory?, directoryLeasing: Boolean = true): SmbConfig {
    val b = SmbConfig.builder()
        .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
        .withSigningRequired(true)
        .withEncryptData(encrypt)
        .withDfsEnabled(false)
        .withMultiProtocolNegotiate(false)
        .withTimeout(60, TimeUnit.SECONDS)
        // Con il leasing smbj tiene aperti gli handle delle cartelle elencate: per percorsi enormi va spento.
        .withDirectoryLeasingEnabled(directoryLeasing)
    if (socketFactory != null) b.withSocketFactory(socketFactory)
    return b.build()
}

fun joinPath(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder\\$name"

private fun baseName(path: String): String = path.substringAfterLast('\\')

// ---------------------------------------------------------------------------------------------
// Regole di sicurezza sui file: l'app tocca in scrittura solo file `.nasprobe-*` creati da lei.
// ---------------------------------------------------------------------------------------------

/**
 * Registro dei file creati dall'app. Ogni creazione e ogni cancellazione passa da qui e rifiuta qualunque
 * percorso il cui nome non inizi con `.nasprobe-`.
 */
class OwnedFiles {
    private val items = mutableListOf<Pair<DiskShare, String>>()

    private fun requireOwnedName(path: String) {
        check(baseName(path).startsWith(OWNED_PREFIX)) { "rifiutato: il file non e' un file .nasprobe-" }
    }

    /** Registra un percorso che l'app sta per creare (o per ottenere con una rinomina). */
    fun register(share: DiskShare, path: String) {
        requireOwnedName(path)
        items += share to path
    }

    /** Apre in creazione esclusiva (mai sovrascrive) un file `.nasprobe-` gia' registrato. */
    fun createExclusive(share: DiskShare, path: String): File {
        requireOwnedName(path)
        check(items.any { it.first === share && it.second == path }) { "file non registrato" }
        return share.openFile(
            path,
            EnumSet.of(
                AccessMask.FILE_WRITE_DATA,
                AccessMask.FILE_READ_DATA,
                AccessMask.FILE_READ_ATTRIBUTES,
                AccessMask.FILE_WRITE_ATTRIBUTES,
                AccessMask.DELETE,
            ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_CREATE,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
    }

    /** Cancella un file registrato se esiste. Restituisce true se ora non esiste piu'. */
    fun deleteIfExists(share: DiskShare, path: String): Boolean {
        requireOwnedName(path)
        check(items.any { it.first === share && it.second == path }) { "file non registrato" }
        return try {
            if (share.fileExists(path)) share.rm(path)
            !share.fileExists(path)
        } catch (_: Throwable) {
            false
        }
    }

    /** Pulizia finale di tutto cio' che questa istanza ha creato. Restituisce (rimasti sul NAS). */
    fun cleanupAll(): Int {
        var left = 0
        for ((share, path) in items) {
            if (!deleteIfExists(share, path)) left++
        }
        items.clear()
        return left
    }
}

/** Apertura in sola lettura: nessun diritto di scrittura o cancellazione su file altrui. */
fun DiskShare.openReadOnly(path: String): File = openFile(
    path,
    EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
    SMB2CreateDisposition.FILE_OPEN,
    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
)

/** Dati casuali da 50 MiB generati una volta sola per esecuzione, con il loro SHA-256. */
class TestData(val bytes: ByteArray, val sha256: ByteArray)

fun generateTestData(sizeBytes: Int = 50 * MIB): TestData {
    val data = ByteArray(sizeBytes)
    Random(System.nanoTime()).nextBytes(data)
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    return TestData(data, digest)
}

/** Legge un file per intero calcolando l'SHA-256. Restituisce (byte letti, ms, digest). */
fun readAndHash(share: DiskShare, path: String): Triple<Long, Double, ByteArray> {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(MIB)
    var total = 0L
    val t0 = System.nanoTime()
    share.openReadOnly(path).use { f ->
        f.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf, 0, buf.size)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
    }
    return Triple(total, (System.nanoTime() - t0) / 1_000_000.0, md.digest())
}

/** Legge fino a `len` byte a `offset` con richieste dirette (accesso casuale). Restituisce i byte letti. */
fun readAt(file: File, offset: Long, len: Int, buf: ByteArray): Int {
    var got = 0
    while (got < len) {
        val n = file.read(buf, offset + got, got, len - got)
        if (n <= 0) break
        got += n
    }
    return got
}
