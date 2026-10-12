package dev.pampa.mycloudgallery.spike.nasprobe

import android.content.Context
import com.hierynomus.mssmb2.copy.CopyChunkRequest
import com.hierynomus.mssmb2.copy.CopyChunkResponse
import com.hierynomus.smb.SMBBuffer
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.io.ArrayByteChunkProvider
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.SocketFactory

// =================================================================================================
// Contesto e tipi condivisi
// =================================================================================================

/** Contesto condiviso dai test SMB di una esecuzione. */
class SmbEnv(
    val ctx: Context,
    val settings: ProbeSettings,
    val owned: OwnedFiles,
) {
    private var data: TestData? = null

    /** 50 MiB casuali, generati alla prima richiesta e riusati (non fanno parte dei tempi misurati). */
    fun testData(): TestData = data ?: generateTestData().also { data = it }
}

/** File remoto visto dal listing. Il percorso resta in memoria e non finisce mai nei risultati. */
class RemoteFile(val path: String, val size: Long, val ext: String)

class ConnectOutcome(val result: TestResult, val conn: SmbConn?, val authFailed: Boolean)

class ListingOutcome(val result: TestResult, val files: List<RemoteFile>)

private const val MAX_LISTING_ENTRIES = 5000
private const val SEGMENT_BYTES = 32L * MIB

// =================================================================================================
// Test 2 (e 8a): connessione e autenticazione
// =================================================================================================

/**
 * Connette e autentica con UN solo tentativo. Se l'autenticazione fallisce non si riprova (rischio di blocco
 * dell'account): `authFailed` e' vero e il chiamante deve fermare tutto.
 */
suspend fun testConnectAuth(
    env: SmbEnv,
    id: String,
    title: String,
    encrypt: Boolean,
    shareName: String? = env.settings.share,
    directoryLeasing: Boolean = true,
): ConnectOutcome =
    withContext(Dispatchers.IO) {
        val s = env.settings
        val b = ResultBuilder(id, title)
        b.put("firmaRichiesta", true)
        b.put("cifraturaRichiesta", encrypt)
        b.put("socketLegatoAWifi", s.bindToWifi)

        var client: SMBClient? = null
        var connection: Connection? = null
        var session: Session? = null
        var stage = "preparazione"
        try {
            var target = s.host.trim()
            var factory: SocketFactory? = null
            if (s.bindToWifi) {
                val wifi = wifiNetworks(env.ctx).firstOrNull()
                    ?: throw IllegalStateException("nessuna rete Wi-Fi disponibile per il binding")
                val res = resolveHost(target, wifi)
                target = res.address?.hostAddress
                    ?: throw IllegalStateException("DNS non risolto sulla rete Wi-Fi (${res.error})")
                factory = wifi.socketFactory
            }

            val c = SMBClient(buildSmbConfig(encrypt, factory, directoryLeasing))
            client = c

            stage = "connessione"
            val (conn, connectMs) = timed { c.connect(target) }
            connection = conn
            b.put("connessioneMs", r1(connectMs)) // TCP + negoziazione del dialetto

            stage = "autenticazione"
            val auth = AuthenticationContext(s.user, s.password.toCharArray(), "")
            val (sess, authMs) = timed { conn.authenticate(auth) }
            session = sess
            b.put("autenticazioneMs", r1(authMs))

            // Valori reali letti da Connection e Session.
            val sc = sess.sessionContext
            val cc = conn.connectionContext
            val np = conn.negotiatedProtocol
            val encryptedNow: Boolean? = try {
                sess.shouldEncryptData()
            } catch (_: Throwable) {
                null
            }
            b.put("dialetto", np.dialect.name)
            b.put("maxLetturaBytes", np.maxReadSize)
            b.put("maxScritturaBytes", np.maxWriteSize)
            b.put("maxTransazioneBytes", np.maxTransactSize)
            b.put("firmaObbligatoriaInSessione", sess.isSigningRequired)
            b.put("chiaveDiFirmaPresente", sc.signingKey != null)
            b.put("serverRichiedeFirma", cc.isServerRequiresSigning)
            b.put("serverAbilitaFirma", cc.isServerSigningEnabled)
            b.put("cifraturaAttiva", encryptedNow)
            b.put("serverSupportaCifratura", cc.supportsEncryption())
            b.put("cifrario", cc.cipherId?.name)
            b.put("cifraturaImpostaDalServer", sc.isEncryptData)
            b.put("sessioneGuest", sess.isGuest)
            b.put("sessioneAnonima", sess.isAnonymous)
            b.put("multiCredit", cc.supportsMultiCredit())
            b.put("multiCanale", cc.supportsMultiChannel())
            if (encrypt && encryptedNow != true) {
                b.put("nota", "cifratura richiesta ma non attiva: il server non la supporta o non l'ha negoziata")
            }

            stage = "share"
            val shareOrNull: DiskShare? = if (shareName == null) null else try {
                val (sh, shareMs) = timed { sess.connectShare(shareName) as DiskShare }
                b.put("connessioneShareMs", r1(shareMs))
                sh
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                b.put("erroreShare", describeError(t, s.sensitiveTokens))
                null
            }
            val smb = SmbConn(c, conn, sess, shareOrNull, encrypt)
            if (shareName == null) {
                ConnectOutcome(b.ok(), smb, false) // nessuna share richiesta: la scelgono i test successivi
            } else if (shareOrNull == null) {
                ConnectOutcome(b.fail("share non accessibile (vedi erroreShare); autenticazione riuscita"), smb, false)
            } else {
                ConnectOutcome(b.ok(), smb, false)
            }
        } catch (t: Throwable) {
            for (step in listOf<() -> Unit>({ session?.close() }, { connection?.close() }, { client?.close() })) {
                try {
                    step()
                } catch (_: Throwable) {
                }
            }
            if (t is CancellationException) throw t
            val authFailed = stage == "autenticazione"
            val msg = describeError(t, s.sensitiveTokens)
            b.put("faseFallita", stage)
            val text = if (authFailed) "AUTENTICAZIONE FALLITA, nessun nuovo tentativo: $msg" else "fallito in fase '$stage': $msg"
            ConnectOutcome(b.fail(text), null, authFailed)
        }
    }

// =================================================================================================
// Test 3: listing ricorsivo
// =================================================================================================

private fun listDirectory(share: DiskShare, dir: String) = try {
    share.list(dir)
} catch (e: Exception) {
    if (dir.isEmpty()) share.list(".") else throw e
}

suspend fun testListing(env: SmbEnv, conn: SmbConn): ListingOutcome = withContext(Dispatchers.IO) {
    val b = ResultBuilder("3", "Listing ricorsivo")
    val share = conn.share!!
    val files = ArrayList<RemoteFile>()
    try {
        val extCount = HashMap<String, Int>()
        val sizeBuckets = linkedMapOf("<1MB" to 0, "1-10MB" to 0, "10-100MB" to 0, ">=100MB" to 0)
        var dirs = 0
        var entries = 0
        var leftover = 0
        var truncated = false
        var maxDepth = 0
        var dirErrors = 0
        var maxInDir = 0
        var totalBytes = 0L
        var firstListMs = -1.0
        val listTimes = ArrayList<Double>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(env.settings.folderPath to 0)
        var first = true

        outer@ while (queue.isNotEmpty()) {
            ensureActive()
            val (dir, depth) = queue.removeFirst()
            val listed = try {
                val (l, ms) = timed { listDirectory(share, dir) }
                if (first) firstListMs = ms
                listTimes += ms
                l
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (first) throw t // la cartella di partenza deve esistere
                dirErrors++
                continue@outer
            } finally {
                first = false
            }
            maxDepth = maxOf(maxDepth, depth)
            var inDir = 0
            for (info in listed) {
                val name = info.fileName
                if (name == "." || name == "..") continue
                if (entries >= MAX_LISTING_ENTRIES) {
                    truncated = true
                    break@outer
                }
                entries++
                inDir++
                val isDir = (info.fileAttributes and 0x10L) != 0L
                if (isDir) {
                    dirs++
                    queue.addLast(joinPath(dir, name) to depth + 1)
                } else if (name.startsWith(OWNED_PREFIX)) {
                    leftover++ // residuo di una esecuzione precedente: non viene toccato
                } else {
                    val size = info.endOfFile
                    val ext = extensionOf(name)
                    files += RemoteFile(joinPath(dir, name), size, ext)
                    extCount.merge(ext, 1) { a, c -> a + c }
                    totalBytes += size
                    val bucket = when {
                        size < 1_000_000L -> "<1MB"
                        size < 10_000_000L -> "1-10MB"
                        size < 100_000_000L -> "10-100MB"
                        else -> ">=100MB"
                    }
                    sizeBuckets[bucket] = sizeBuckets.getValue(bucket) + 1
                }
            }
            maxInDir = maxOf(maxInDir, inDir)
        }

        val sortedExt = extCount.entries.sortedByDescending { it.value }
        b.put("file", files.size)
        b.put("cartelle", dirs)
        b.put("voci", entries)
        b.put("troncatoA5000", truncated)
        b.put("byteTotaliFile", totalBytes)
        b.put("fasceDimensione", sizeBuckets)
        b.put("profonditaMax", maxDepth)
        b.put("cartelleInterrogate", listTimes.size)
        b.put("cartelleNonLeggibili", dirErrors)
        b.put("vociMaxInUnaCartella", maxInDir)
        b.put("primaInterrogazioneMs", r1(firstListMs)) // utile per l'accesso con dischi in standby
        b.put("interrogazioni", statsOf(listTimes))
        b.put("estensioni", sortedExt.take(25).associate { it.key to it.value })
        b.put("fileInAltreEstensioni", sortedExt.drop(25).sumOf { it.value })
        b.put("residuiNasprobeEsistenti", leftover)
        val total = b.elapsedMs().coerceAtLeast(1)
        b.put("vociAlSecondo", r1(entries * 1000.0 / total))
        ListingOutcome(b.ok(), files)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        ListingOutcome(b.fail(t, env.settings.sensitiveTokens), files)
    }
}

// =================================================================================================
// Test 4 (e 8b): lettura sequenziale, 1 flusso poi 4 flussi
// =================================================================================================

class ReadTask(val path: String, val offset: Long, val length: Long)

class ReadPlan(val tasks: List<ReadTask>, val bytes: Long, val reused: Boolean)

/**
 * Sceglie i file da leggere: ogni piano prende file non ancora letti (cosi' la cache del NAS non falsa il
 * confronto). Se non ne restano abbastanza riusa il primo piano e lo segnala.
 */
class ReadPlanner(files: List<RemoteFile>, private val budget: Long = 200L * MIB) {
    private val candidates = files.filter { it.size > 0 }
    private var cursor = 0
    private var firstPlan: ReadPlan? = null

    fun next(): ReadPlan? {
        if (candidates.isEmpty()) return null
        val remaining = (cursor until candidates.size).sumOf { candidates[it].size }
        firstPlan?.let { first ->
            if (remaining < budget / 2) return ReadPlan(first.tasks, first.bytes, reused = true)
        }
        val tasks = ArrayList<ReadTask>()
        var bytes = 0L
        while (cursor < candidates.size && bytes < budget) {
            val f = candidates[cursor++]
            var off = 0L
            while (off < f.size && bytes < budget) {
                val len = minOf(SEGMENT_BYTES, f.size - off, budget - bytes)
                tasks += ReadTask(f.path, off, len)
                off += len
                bytes += len
            }
        }
        val plan = ReadPlan(tasks, bytes, reused = false)
        if (firstPlan == null) firstPlan = plan
        return plan
    }
}

private class PassStats(val bytes: Long, val wallMs: Double, val flowMbps: List<Double>, val errors: Map<String, Int>)

private fun readTask(share: DiskShare, t: ReadTask, buf: ByteArray): Long {
    var total = 0L
    share.openReadOnly(t.path).use { f ->
        f.inputStream.use { ins ->
            if (t.offset > 0) ins.skip(t.offset)
            var remaining = t.length
            while (remaining > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                remaining -= n
                total += n
            }
        }
    }
    return total
}

private suspend fun runReadPass(share: DiskShare, tasks: List<ReadTask>, workers: Int, sensitive: List<String>): PassStats =
    coroutineScope {
        val queue = ConcurrentLinkedQueue(tasks)
        val errors = ConcurrentHashMap<String, Int>()
        val t0 = System.nanoTime()
        val perWorker = (1..workers).map {
            async(Dispatchers.IO) {
                val buf = ByteArray(MIB)
                var bytes = 0L
                val w0 = System.nanoTime()
                while (isActive) {
                    val task = queue.poll() ?: break
                    try {
                        bytes += readTask(share, task, buf)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        errors.merge(describeError(e, sensitive), 1) { a, c -> a + c }
                    }
                }
                bytes to (System.nanoTime() - w0) / 1_000_000.0
            }
        }.awaitAll()
        PassStats(
            bytes = perWorker.sumOf { it.first },
            wallMs = (System.nanoTime() - t0) / 1_000_000.0,
            flowMbps = perWorker.map { mbps(it.first, it.second) },
            errors = errors,
        )
    }

private fun passToMap(p: PassStats, plan: ReadPlan, workers: Int): Map<String, Any?> {
    val m = linkedMapOf<String, Any?>()
    m["flussi"] = workers
    m["byteLetti"] = p.bytes
    m["ms"] = r1(p.wallMs)
    m["MBs"] = mbps(p.bytes, p.wallMs)
    m["segmenti"] = plan.tasks.size
    m["fileGiaLettiInPrecedenza"] = plan.reused
    if (workers > 1) m["MBsPerFlusso"] = p.flowMbps
    if (p.errors.isNotEmpty()) m["errori"] = p.errors
    return m
}

suspend fun testSequentialRead(env: SmbEnv, conn: SmbConn, planner: ReadPlanner, id: String, title: String): TestResult =
    withContext(Dispatchers.IO) {
        val b = ResultBuilder(id, title)
        val share = conn.share!!
        val sens = env.settings.sensitiveTokens
        try {
            val plan1 = planner.next() ?: return@withContext b.skipped("nessun file con dimensione > 0 nella cartella")
            b.put("limiteByte", 200L * MIB)
            b.put("note", "MB/s con 1 MB = 1.000.000 byte; se fileGiaLettiInPrecedenza e' true, la cache del NAS puo' falsare il confronto")
            val pass1 = runReadPass(share, plan1.tasks, 1, sens)
            b.put("unFlusso", passToMap(pass1, plan1, 1))

            val plan4 = planner.next() ?: plan1
            val pass4 = runReadPass(share, plan4.tasks, 4, sens)
            b.put("quattroFlussi", passToMap(pass4, plan4, 4))

            val m1 = mbps(pass1.bytes, pass1.wallMs)
            val m4 = mbps(pass4.bytes, pass4.wallMs)
            if (m1 > 0) b.put("rapportoQuattroSuUno", r2(m4 / m1))
            if (pass1.bytes == 0L && pass4.bytes == 0L) b.fail("nessun byte letto") else b.ok()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            b.fail(t, sens)
        }
    }

// =================================================================================================
// Test 5: accesso casuale (immagini 3 x 64 KB, video 3 x 1 MB)
// =================================================================================================

private fun <T> evenly(list: List<T>, n: Int): List<T> =
    if (list.size <= n) list else List(n) { list[it * list.size / n] }

private class AccessSample(val openMs: Double, val first: Double, val middle: Double, val last: Double)

private fun sampleAccess(share: DiskShare, f: RemoteFile, chunk: Int, buf: ByteArray): AccessSample {
    val (file, openMs) = timed { share.openReadOnly(f.path) }
    file.use { fl ->
        val len = minOf(chunk.toLong(), f.size).toInt()
        val tail = maxOf(0L, f.size - chunk)
        val mid = maxOf(0L, (f.size - chunk) / 2)
        val (_, first) = timed { readAt(fl, 0, len, buf) }
        val (_, last) = timed { readAt(fl, tail, len, buf) }
        val (_, middle) = timed { readAt(fl, mid, len, buf) }
        return AccessSample(openMs, first, middle, last)
    }
}

private fun summarize(samples: List<AccessSample>, chunk: Int, errors: Int): Map<String, Any?> = linkedMapOf(
    "file" to samples.size,
    "byteLettiPerAccesso" to chunk,
    "erroriDiLettura" to errors,
    "aperturaMs" to statsOf(samples.map { it.openMs }),
    "primiMs" to statsOf(samples.map { it.first }),
    "metaMs" to statsOf(samples.map { it.middle }),
    "ultimiMs" to statsOf(samples.map { it.last }),
    "totalePerFileMs" to statsOf(samples.map { it.openMs + it.first + it.middle + it.last }),
)

suspend fun testRandomAccess(env: SmbEnv, conn: SmbConn, files: List<RemoteFile>): TestResult =
    withContext(Dispatchers.IO) {
        val b = ResultBuilder("5", "Accesso casuale")
        val share = conn.share!!
        try {
            val images = evenly(files.filter { it.ext in IMAGE_EXTENSIONS && it.size > 0 }, 20)
            val videos = evenly(files.filter { it.ext in VIDEO_EXTENSIONS && it.size > 0 }, 5)
            if (images.isEmpty() && videos.isEmpty()) {
                return@withContext b.skipped("nessuna immagine o video riconosciuti dall'estensione")
            }
            val buf = ByteArray(MIB)
            for ((label, list, chunk) in listOf(
                Triple("immagini", images, 64 * 1024),
                Triple("video", videos, MIB),
            )) {
                if (list.isEmpty()) {
                    b.put(label, "nessun file")
                    continue
                }
                val samples = ArrayList<AccessSample>()
                var errors = 0
                for (f in list) {
                    ensureActive()
                    try {
                        samples += sampleAccess(share, f, chunk, buf)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        errors++
                    }
                }
                b.put(label, summarize(samples, chunk, errors))
            }
            b.ok()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            b.fail(t, env.settings.sensitiveTokens)
        }
    }

// =================================================================================================
// Test 6 (e 8c): scrittura, rinomina, rilettura con SHA-256, cancellazione
// =================================================================================================

private suspend fun cleanupOwned(env: SmbEnv): Int = withContext(NonCancellable + Dispatchers.IO) { env.owned.cleanupAll() }

/**
 * Esegue `body` e, comunque vada, cancella tutto cio' che l'app ha creato. `body` restituisce null se tutto e'
 * andato bene, altrimenti il messaggio di errore.
 */
private suspend fun runOwned(env: SmbEnv, b: ResultBuilder, body: suspend () -> String?): TestResult {
    var failure: String? = null
    try {
        failure = body()
    } catch (c: CancellationException) {
        cleanupOwned(env)
        throw c
    } catch (t: Throwable) {
        failure = describeError(t, env.settings.sensitiveTokens)
    }
    val left = cleanupOwned(env)
    b.put("residuiSulNas", left)
    val problems = listOfNotNull(failure, if (left > 0) "$left file .nasprobe- non cancellati" else null)
    return if (problems.isEmpty()) b.ok() else b.fail(problems.joinToString("; "))
}

private class WriteTimings(val openMs: Double, val writeMs: Double, val flushMs: Double, val renameMs: Double?)

/** Crea un file `.nasprobe-`, vi scrive i dati (scritture in pipeline), fa flush e, se richiesto, lo rinomina. */
private fun writeOwnedFile(env: SmbEnv, share: DiskShare, path: String, data: TestData, renameTo: String?): WriteTimings {
    env.owned.register(share, path)
    if (renameTo != null) env.owned.register(share, renameTo)
    val (file, openMs) = timed { env.owned.createExclusive(share, path) }
    file.use { f ->
        val (written, writeMs) = timed { f.write(ArrayByteChunkProvider(data.bytes, 0, data.bytes.size, 0)) }
        check(written == data.bytes.size.toLong()) { "scritti $written byte su ${data.bytes.size}" }
        val (_, flushMs) = timed { f.flush() }
        val renameMs = renameTo?.let { timed { f.rename(it, false) }.second }
        return WriteTimings(openMs, writeMs, flushMs, renameMs)
    }
}

suspend fun testWrite(env: SmbEnv, conn: SmbConn, id: String, title: String): TestResult = withContext(Dispatchers.IO) {
    val data = env.testData()
    val b = ResultBuilder(id, title)
    val share = conn.share!!
    val folder = env.settings.folderPath
    val uuid = UUID.randomUUID().toString()
    val tmp = joinPath(folder, "$OWNED_PREFIX$uuid.tmp")
    val bin = joinPath(folder, "$OWNED_PREFIX$uuid.bin")
    runOwned(env, b) {
        val size = data.bytes.size.toLong()
        val w = writeOwnedFile(env, share, tmp, data, renameTo = bin)
        b.put("byteScritti", size)
        b.put("aperturaMs", r1(w.openMs))
        b.put("scritturaMs", r1(w.writeMs))
        b.put("scritturaMBs", mbps(size, w.writeMs))
        b.put("flushMs", r1(w.flushMs))
        b.put("rinominaMs", r1(w.renameMs ?: 0.0))

        val (bytes, readMs, digest) = readAndHash(share, bin)
        b.put("byteRiletti", bytes)
        b.put("riletturaMs", r1(readMs))
        b.put("riletturaMBs", mbps(bytes, readMs))
        val match = digest.contentEquals(data.sha256)
        b.put("sha256Corrisponde", match)

        val (deleted, delMs) = timed { env.owned.deleteIfExists(share, bin) }
        b.put("eliminatoMs", r1(delMs))
        b.put("eliminato", deleted)
        if (!match) "SHA-256 della rilettura diverso da quello scritto" else null
    }
}

// =================================================================================================
// Test 7: copia lato server
// =================================================================================================

private const val FSCTL_SRV_REQUEST_RESUME_KEY = 0x00140078

/**
 * Copia lato server tra due share diverse. `File.remoteCopyTo` di smbj la rifiuta (richiede la stessa share),
 * quindi si ripete il protocollo a mano con l'API pubblica: chiave di ripresa dalla sorgente, poi
 * FSCTL_SRV_COPYCHUNK_WRITE inviato sull'handle di destinazione (cioe' sull'albero della seconda share).
 */
private fun copyAcrossShares(src: File, dst: File, length: Long): Long {
    val key = src.ioctl(FSCTL_SRV_REQUEST_RESUME_KEY, true, ByteArray(0), 0, 0, 32).copyOf(24)
    val maxChunk = 1L * MIB
    val maxChunks = 16
    var off = 0L
    while (off < length) {
        val chunks = ArrayList<CopyChunkRequest.Chunk>()
        var o = off
        while (chunks.size < maxChunks && o < length) {
            val len = minOf(maxChunk, length - o)
            chunks += CopyChunkRequest.Chunk(o, o, len)
            o += len
        }
        val buffer = SMBBuffer()
        CopyChunkRequest(key, chunks).write(buffer)
        val bytes = buffer.compactData
        val out = dst.ioctl(CopyChunkRequest.getCtlCode().toInt(), true, bytes, 0, bytes.size, 12)
        val resp = CopyChunkResponse()
        resp.read(SMBBuffer(out))
        check(resp.totalBytesWritten > 0) { "il server non ha copiato alcun byte" }
        off += resp.totalBytesWritten
    }
    return off
}

private fun runCopy(
    env: SmbEnv,
    srcShare: DiskShare,
    srcPath: String,
    dstShare: DiskShare,
    dstPath: String,
    data: TestData,
    method: String,
    acrossShares: Boolean,
): MutableMap<String, Any?> {
    val m = linkedMapOf<String, Any?>()
    m["metodo"] = method
    val size = data.bytes.size.toLong()
    env.owned.register(dstShare, dstPath)
    val t0 = System.nanoTime()
    try {
        srcShare.openReadOnly(srcPath).use { s ->
            env.owned.createExclusive(dstShare, dstPath).use { d ->
                val (_, ms) = timed {
                    if (acrossShares) copyAcrossShares(s, d, size) else s.remoteCopyTo(d)
                }
                m["supportata"] = true
                m["copiaMs"] = r1(ms)
                m["copiaMBs"] = mbps(size, ms)
                m["dimensioneCorretta"] = d.length == size
            }
        }
        val (bytes, _, digest) = readAndHash(dstShare, dstPath)
        m["byteCopia"] = bytes
        m["sha256Corrisponde"] = digest.contentEquals(data.sha256)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        m["supportata"] = false
        m["tentativoMs"] = r1((System.nanoTime() - t0) / 1_000_000.0)
        m["errore"] = describeError(t, env.settings.sensitiveTokens)
    }
    m["copiaEliminata"] = env.owned.deleteIfExists(dstShare, dstPath)
    return m
}

suspend fun testServerSideCopy(env: SmbEnv, conn: SmbConn): TestResult = withContext(Dispatchers.IO) {
    val data = env.testData()
    val b = ResultBuilder("7", "Copia lato server")
    val share = conn.share!!
    val s = env.settings
    val folder = s.folderPath
    val uuid = UUID.randomUUID().toString()
    val src = joinPath(folder, "$OWNED_PREFIX$uuid.src")
    runOwned(env, b) {
        val w = writeOwnedFile(env, share, src, data, renameTo = null)
        b.put("sorgenteByte", data.bytes.size)
        b.put("sorgenteScritturaMs", r1(w.writeMs))

        val within = runCopy(
            env, share, src, share, joinPath(folder, "$OWNED_PREFIX$uuid.cp1"), data,
            method = "File.remoteCopyTo di smbj (FSCTL_SRV_COPYCHUNK_WRITE)", acrossShares = false,
        )
        b.put("stessaShare", within)
        (within["copiaMs"] as? Double)?.let { b.put("rapportoCopiaSuScrittura", r2(it / w.writeMs)) }

        if (s.share2.isBlank()) {
            b.put("traShare", "saltato: seconda share non indicata")
        } else {
            try {
                val (share2, openMs) = timed { conn.openShare2(s.share2.trim()) }
                val across = runCopy(
                    env, share, src, share2, "$OWNED_PREFIX$uuid.cp2", data,
                    method = "FSCTL_SRV_COPYCHUNK_WRITE manuale (smbj non lo espone tra share)", acrossShares = true,
                )
                across["connessioneShare2Ms"] = r1(openMs)
                b.put("traShare", across)
                (across["copiaMs"] as? Double)?.let { b.put("rapportoCopiaTraShareSuScrittura", r2(it / w.writeMs)) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                b.put("traShare", mapOf("supportata" to false, "errore" to describeError(t, s.sensitiveTokens)))
            }
        }
        null
    }
}
