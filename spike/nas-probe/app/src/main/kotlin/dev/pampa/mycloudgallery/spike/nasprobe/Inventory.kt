package dev.pampa.mycloudgallery.spike.nasprobe

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

// =================================================================================================
// Inventario in SOLA LETTURA: solo statistiche aggregate. Nessun nome di file o di cartella viene
// conservato, registrato o mostrato; nessun file viene aperto, creato, scritto o cancellato (si
// elencano solo le cartelle, con diritti di lettura).
// =================================================================================================

/** Una voce dell'elenco indicato dall'utente: `share` oppure `share/cartella`. */
class InventoryEntry(val share: String, val folder: String)

fun parseInventory(text: String): List<InventoryEntry> =
    text.split(',')
        .map { it.trim().replace('/', '\\').trim('\\') }
        .filter { it.isNotEmpty() }
        .map { raw ->
            val i = raw.indexOf('\\')
            if (i < 0) InventoryEntry(raw, "") else InventoryEntry(raw.substring(0, i), raw.substring(i + 1).trim('\\'))
        }

private const val MAX_INVENTORY_DEPTH = 64
private const val PROGRESS_EVERY = 1000
private const val MAX_CONSECUTIVE_ERRORS = 25
private const val ATTR_HIDDEN = 0x2L
private const val ATTR_SYSTEM = 0x4L
private const val ATTR_DIRECTORY = 0x10L
private const val ATTR_REPARSE = 0x400L
private val EXT_REGEX = Regex("[a-z0-9]{1,8}")

/** Stati NTSTATUS dopo i quali non ha senso continuare (sessione scaduta o credenziali respinte). */
private val FATAL_STATUSES = setOf(
    "STATUS_LOGON_FAILURE", "STATUS_USER_SESSION_DELETED", "STATUS_NETWORK_SESSION_EXPIRED",
    "STATUS_ACCOUNT_LOCKED_OUT", "STATUS_ACCOUNT_DISABLED", "STATUS_PASSWORD_EXPIRED",
    "STATUS_WRONG_PASSWORD", "STATUS_NETWORK_NAME_DELETED", "STATUS_CONNECTION_DISCONNECTED",
)

/** Estensione sanificata: solo 1-8 caratteri [a-z0-9], altrimenti "(altra)"; mai pezzi di nome. */
private fun inventoryExt(name: String): String {
    val i = name.lastIndexOf('.')
    if (i <= 0 || i == name.length - 1) return "(nessuna)"
    val ext = name.substring(i + 1).lowercase(Locale.ROOT)
    return if (EXT_REGEX.matches(ext)) ext else "(altra)"
}

/** Lista di Long senza boxing, per calcolare la mediana delle dimensioni. */
private class LongList {
    private var a = LongArray(16)
    var n = 0
        private set

    fun add(v: Long) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }

    fun median(): Long {
        if (n == 0) return 0
        java.util.Arrays.sort(a, 0, n)
        return (a[(n - 1) / 2] + a[n / 2]) / 2
    }
}

private class ExtStats {
    var count = 0L
    var bytes = 0L
    var max = 0L
    val sizes = LongList()

    fun add(size: Long) {
        count++
        bytes += size
        if (size > max) max = size
        sizes.add(size)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "file" to count,
        "byteTotali" to bytes,
        "medianaByte" to sizes.median(),
        "massimoByte" to max,
    )
}

private val HIST_LABELS = listOf("<1MB", "1-5MB", "5-20MB", "20-100MB", "100MB-1GB", ">1GB")

private fun histIndex(size: Long): Int = when {
    size < 1_000_000L -> 0
    size < 5_000_000L -> 1
    size < 20_000_000L -> 2
    size < 100_000_000L -> 3
    size <= 1_000_000_000L -> 4
    else -> 5
}

/** Contatori di una share. Contiene solo numeri e stringhe anno-mese. */
class InventoryAcc(private val index: Int, private val shareName: String, private val hasFolder: Boolean) {
    var files = 0L
    var dirs = 0L
    var totalBytes = 0L
    var maxDepth = 0
    private var maxFilesInDir = 0
    private var dirsListed = 0L
    private var emptyFiles = 0L
    private var hiddenFiles = 0L
    private var systemFiles = 0L
    private var hiddenOrSystemFiles = 0L
    private var hiddenOrSystemDirs = 0L
    private var dotFiles = 0L
    private var dotDirs = 0L
    private var atDirs = 0L
    private var filesInSpecialDirs = 0L
    private var reparseNotFollowed = 0L
    private var depthLimitHits = 0L
    private var deniedDirs = 0L
    private var oldest = Long.MAX_VALUE
    private var newest = Long.MIN_VALUE
    private var invalidDates = 0L
    private val hist = LongArray(HIST_LABELS.size)
    private val ext = HashMap<String, ExtStats>()
    private val otherErrors = HashMap<String, Int>()
    private val startNs = System.nanoTime()

    /** Impostati dal percorso se la share non si e' potuta percorrere. */
    var error: String? = null

    /** Vero se un errore di sessione/autenticazione impone di fermare tutto l'inventario. */
    var fatal = false

    val entries: Long get() = files + dirs

    fun elapsedMs(): Long = (System.nanoTime() - startNs) / 1_000_000

    fun recordDir(depth: Int, name: String, attrs: Long): Boolean {
        dirs++
        if (name.startsWith(".")) dotDirs++
        if (name.startsWith("@")) atDirs++
        if (attrs and (ATTR_HIDDEN or ATTR_SYSTEM) != 0L) hiddenOrSystemDirs++
        if (attrs and ATTR_REPARSE != 0L) {
            reparseNotFollowed++
            return false
        }
        if (depth + 1 > MAX_INVENTORY_DEPTH) {
            depthLimitHits++
            return false
        }
        return true
    }

    fun recordFile(name: String, attrs: Long, size: Long, writeTimeMs: Long?, special: Boolean) {
        files++
        totalBytes += size
        if (size == 0L) emptyFiles++
        hist[histIndex(size)]++
        ext.getOrPut(inventoryExt(name)) { ExtStats() }.add(size)
        val hidden = attrs and ATTR_HIDDEN != 0L
        val system = attrs and ATTR_SYSTEM != 0L
        if (hidden) hiddenFiles++
        if (system) systemFiles++
        if (hidden || system) hiddenOrSystemFiles++
        if (name.startsWith(".")) dotFiles++
        if (special) filesInSpecialDirs++
        if (writeTimeMs != null && writeTimeMs > 0) {
            if (writeTimeMs < oldest) oldest = writeTimeMs
            if (writeTimeMs > newest) newest = writeTimeMs
        } else {
            invalidDates++
        }
    }

    fun recordDirListed(depth: Int, filesHere: Int) {
        dirsListed++
        if (depth > maxDepth) maxDepth = depth
        if (filesHere > maxFilesInDir) maxFilesInDir = filesHere
    }

    fun recordDenied() {
        deniedDirs++
    }

    fun recordOtherError(description: String) {
        otherErrors.merge(description, 1) { a, b -> a + b }
    }

    private fun yearMonth(ms: Long): String =
        YearMonth.from(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)).toString()

    fun result(interrupted: Boolean): TestResult {
        val ms = elapsedMs()
        val d = linkedMapOf<String, Any?>()
        d["share"] = shareName
        d["cartellaIndicata"] = hasFolder
        d["completo"] = !interrupted && error == null
        if (interrupted) d["interrotto"] = true
        d["file"] = files
        d["cartelle"] = dirs
        d["voci"] = entries
        d["byteTotali"] = totalBytes
        d["profonditaMax"] = maxDepth
        d["cartellaConPiuFile_numeroFile"] = maxFilesInDir // solo il conteggio, mai il nome
        d["cartelleLette"] = dirsListed
        d["fileVuoti"] = emptyFiles
        d["istogrammaDimensioni"] = HIST_LABELS.indices.associate { HIST_LABELS[it] to hist[it] }
        d["istogrammaNota"] = "1 MB = 1.000.000 byte; 100MB-1GB include 1 GB esatto"
        d["nascostiESistema"] = linkedMapOf(
            "fileNascosti" to hiddenFiles,
            "fileDiSistema" to systemFiles,
            "fileNascostiODiSistema" to hiddenOrSystemFiles,
            "cartelleNascosteODiSistema" to hiddenOrSystemDirs,
            "fileConNomeCheIniziaConPunto" to dotFiles,
            "cartelleCheIniziaConPunto" to dotDirs,
            "cartelleCheIniziaConChiocciola" to atDirs,
            "fileDentroCartellePuntoOChiocciola" to filesInSpecialDirs,
        )
        d["date"] = linkedMapOf(
            "fileConDataValida" to (files - invalidDates),
            "piuVecchio" to (if (oldest != Long.MAX_VALUE) yearMonth(oldest) else null),
            "piuRecente" to (if (newest != Long.MIN_VALUE) yearMonth(newest) else null),
            "fileSenzaDataValida" to invalidDates,
        )
        d["saltate"] = linkedMapOf(
            "vociSaltateAccessoNegato" to deniedDirs,
            "nota" to "sono cartelle il cui contenuto non si e' potuto elencare per accesso negato",
            "altriErroriCartelle" to otherErrors.toMap(),
            "cartelleNonSeguite" to linkedMapOf(
                "puntiDiRipresa" to reparseNotFollowed,
                "oltreProfondita${MAX_INVENTORY_DEPTH}" to depthLimitHits,
            ),
        )
        val sorted = ext.entries.sortedByDescending { it.value.count }
        d["estensioniDistinte"] = sorted.size
        d["estensioni"] = sorted.take(100).associate { it.key to it.value.toMap() }
        if (sorted.size > 100) {
            val rest = sorted.drop(100)
            d["estensioniAltre"] = linkedMapOf(
                "estensioni" to rest.size,
                "file" to rest.sumOf { it.value.count },
                "byteTotali" to rest.sumOf { it.value.bytes },
            )
        }
        d["tempoMs"] = ms
        d["vociAlSecondo"] = r1(entries * 1000.0 / ms.coerceAtLeast(1))
        val title = "Inventario $index: $shareName"
        return if (error != null) {
            TestResult("INV-$index", title, Status.FAIL, ms, d, error)
        } else {
            TestResult("INV-$index", title, Status.OK, ms, d)
        }
    }

    /** Righe di totale: servono al riepilogo finale. */
    fun totals(): Triple<Long, Long, Long> = Triple(files, dirs, totalBytes)
}

private fun listDirectory(share: DiskShare, dir: String) = try {
    share.list(dir)
} catch (e: Exception) {
    if (dir.isEmpty()) share.list(".") else throw e
}

private fun apiException(t: Throwable): SMBApiException? =
    generateSequence(t) { it.cause }.filterIsInstance<SMBApiException>().firstOrNull()

/**
 * Percorre ricorsivamente (in profondita', con una pila: memoria limitata) la share indicata, in sola
 * lettura. `onProgress` riceve un testo ogni 1000 voci. Si interrompe con l'annullamento della coroutine.
 */
suspend fun inventoryShare(
    conn: SmbConn,
    entry: InventoryEntry,
    acc: InventoryAcc,
    sensitive: List<String>,
    label: String,
    onProgress: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    val share = try {
        conn.session.connectShare(entry.share) as DiskShare
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        acc.error = "share non accessibile: ${describeError(t, sensitive)}"
        return@withContext
    }
    try {
        // Pila di (percorso, profondita', dentro una cartella che inizia con "." o "@").
        val stack = ArrayDeque<Triple<String, Int, Boolean>>()
        stack.addLast(Triple(entry.folder, 0, false))
        var first = true
        var consecutiveErrors = 0
        var nextProgress = PROGRESS_EVERY.toLong()

        while (stack.isNotEmpty()) {
            ensureActive()
            val (dir, depth, special) = stack.removeLast()
            val listed = try {
                listDirectory(share, dir).also { consecutiveErrors = 0 }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val api = apiException(t)
                val statusName = api?.status?.name
                when {
                    first -> {
                        acc.error = "cartella di partenza non leggibile: ${describeError(t, sensitive)}"
                        return@withContext
                    }
                    statusName == "STATUS_ACCESS_DENIED" -> acc.recordDenied()
                    statusName != null && statusName in FATAL_STATUSES -> {
                        acc.error = "errore di sessione, inventario fermato: ${describeError(t, sensitive)}"
                        acc.fatal = true
                        return@withContext
                    }
                    else -> {
                        acc.recordOtherError(describeError(t, sensitive))
                        if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            acc.error = "troppi errori consecutivi, share abbandonata (ultimo: ${describeError(t, sensitive)})"
                            return@withContext
                        }
                    }
                }
                null
            } finally {
                first = false
            }
            if (listed == null) continue

            var filesHere = 0
            for (info in listed) {
                val name = info.fileName
                if (name == "." || name == "..") continue
                val attrs = info.fileAttributes
                if (attrs and ATTR_DIRECTORY != 0L) {
                    val follow = acc.recordDir(depth, name, attrs)
                    if (follow) {
                        val childSpecial = special || name.startsWith(".") || name.startsWith("@")
                        stack.addLast(Triple(if (dir.isEmpty()) name else "$dir\\$name", depth + 1, childSpecial))
                    }
                } else {
                    filesHere++
                    acc.recordFile(name, attrs, info.endOfFile, info.lastWriteTime?.toEpochMillis(), special)
                }
                if (acc.entries >= nextProgress) {
                    nextProgress += PROGRESS_EVERY
                    val ms = acc.elapsedMs().coerceAtLeast(1)
                    onProgress(
                        "$label: ${acc.entries} voci " +
                            "(${acc.dirs} cartelle), ${r1(acc.entries * 1000.0 / ms)} voci/s",
                    )
                    ensureActive()
                }
            }
            acc.recordDirListed(depth, filesHere)
        }
    } finally {
        try {
            share.close()
        } catch (_: Throwable) {
        }
    }
}

/** Riepilogo finale su tutte le share percorse. */
fun inventoryTotals(accs: List<InventoryAcc>, interrupted: Boolean): TestResult {
    val b = ResultBuilder("INV-TOT", "Inventario: totali")
    var files = 0L
    var dirs = 0L
    var bytes = 0L
    var ms = 0L
    for (a in accs) {
        val (f, d, by) = a.totals()
        files += f
        dirs += d
        bytes += by
        ms += a.elapsedMs()
    }
    b.put("shareLette", accs.size)
    b.put("file", files)
    b.put("cartelle", dirs)
    b.put("voci", files + dirs)
    b.put("byteTotali", bytes)
    b.put("tempoMs", ms)
    b.put("vociAlSecondo", r1((files + dirs) * 1000.0 / ms.coerceAtLeast(1)))
    b.put("completo", !interrupted && accs.none { it.error != null })
    return b.ok()
}
