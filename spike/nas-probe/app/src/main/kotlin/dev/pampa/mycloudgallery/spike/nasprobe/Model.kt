package dev.pampa.mycloudgallery.spike.nasprobe

import com.hierynomus.mssmb2.SMBApiException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/** Impostazioni inserite dall'utente. La password vive solo in memoria. */
data class ProbeSettings(
    val host: String,
    val share: String,
    val folder: String,
    val user: String,
    val password: String,
    val share2: String,
    val mac: String,
    val requireEncryption: Boolean,
    val bindToWifi: Boolean,
    /** Modalita' inventario: elenco di `share` o `share/cartella` separati da virgola. */
    val inventory: String = "",
) {
    /** Voci dell'inventario gia' separate in share e cartella. */
    val inventoryEntries: List<InventoryEntry>
        get() = parseInventory(inventory)

    /** Cartella normalizzata col separatore SMB, senza barre iniziali o finali. */
    val folderPath: String
        get() = folder.trim().replace('/', '\\').trim('\\')

    /** Valori che non devono mai comparire in risultati o log. */
    val sensitiveTokens: List<String>
        get() = (listOf(host, share, folder.trim(), user, share2, mac) +
            inventoryEntries.flatMap { listOf(it.share) + it.folder.split('\\') })
            .map { it.trim() }
            .filter { it.length >= 2 }
}

enum class Status { OK, FAIL, SKIPPED }

/** Esito di un singolo test. Mai nomi di file o percorsi: solo conteggi, dimensioni, estensioni. */
data class TestResult(
    val id: String,
    val title: String,
    val status: Status,
    val elapsedMs: Long,
    val data: Map<String, Any?> = emptyMap(),
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("status", status.name)
        put("elapsedMs", elapsedMs)
        if (error != null) put("error", error)
        put("data", toJsonValue(data))
    }
}

/** Costruttore di risultati: raccoglie i dati e misura il tempo totale del test. */
class ResultBuilder(private val id: String, private val title: String) {
    val data = linkedMapOf<String, Any?>()
    private val startNs = System.nanoTime()

    fun put(key: String, value: Any?) {
        data[key] = value
    }

    fun elapsedMs(): Long = (System.nanoTime() - startNs) / 1_000_000

    fun ok(): TestResult = TestResult(id, title, Status.OK, elapsedMs(), data)

    fun fail(error: String): TestResult = TestResult(id, title, Status.FAIL, elapsedMs(), data, error)

    fun fail(t: Throwable, sensitive: List<String>): TestResult = fail(describeError(t, sensitive))

    fun skipped(reason: String): TestResult {
        data["motivo"] = reason
        return TestResult(id, title, Status.SKIPPED, elapsedMs(), data)
    }
}

fun skippedResult(id: String, title: String, reason: String) =
    TestResult(id, title, Status.SKIPPED, 0, mapOf("motivo" to reason))

/** Cronometra un blocco e restituisce (valore, millisecondi con decimali). */
inline fun <T> timed(block: () -> T): Pair<T, Double> {
    val t0 = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - t0) / 1_000_000.0
}

fun r1(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else (x * 10).roundToLong() / 10.0
fun r2(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else (x * 100).roundToLong() / 100.0

/** MB/s con 1 MB = 1.000.000 byte. */
fun mbps(bytes: Long, ms: Double): Double = if (ms <= 0.0) 0.0 else r2(bytes / 1_000_000.0 / (ms / 1000.0))

/** Statistiche su una serie di tempi in ms. */
fun statsOf(values: List<Double>): Map<String, Any?> {
    if (values.isEmpty()) return mapOf("n" to 0)
    val s = values.sorted()
    fun pct(p: Double): Double = s[((s.size - 1) * p).roundToLong().toInt()]
    return linkedMapOf(
        "n" to s.size,
        "minMs" to r1(s.first()),
        "medianaMs" to r1(pct(0.5)),
        "p95Ms" to r1(pct(0.95)),
        "maxMs" to r1(s.last()),
        "mediaMs" to r1(s.average()),
    )
}

/** Converte Map/List/valori in tipi org.json (NaN e infiniti esclusi). */
fun toJsonValue(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray -> v
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJsonValue(x)) } }
    is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJsonValue(it)) } }
    is Array<*> -> JSONArray().also { a -> v.forEach { a.put(toJsonValue(it)) } }
    is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
    is Float -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v.toDouble()
    is Number, is Boolean, is String -> v
    is Enum<*> -> v.name
    else -> v.toString()
}

/** JSON indentato; `org.json` scrive `\/` al posto di `/`, che e' valido ma illeggibile. */
fun JSONObject.pretty(): String = toString(2).replace("\\/", "/")

/**
 * Descrizione sintetica di un errore, adatta a schermo e JSON: per gli errori SMB solo lo stato NTSTATUS
 * (il messaggio della libreria puo' contenere percorsi); per gli altri il tipo e un messaggio ripulito
 * dai valori inseriti dall'utente.
 */
fun describeError(t: Throwable, sensitive: List<String>): String {
    var root: Throwable = t
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val api = generateSequence(t) { it.cause }.filterIsInstance<SMBApiException>().firstOrNull()
    if (api != null) {
        val code = String.format(Locale.ROOT, "0x%08X", api.statusCode)
        return "SMBApiException ${api.status} ($code) durante ${api.failedCommand}"
    }
    var msg = root.message ?: t.message ?: ""
    sensitive.forEach { token -> msg = msg.replace(token, "<x>", ignoreCase = true) }
    // Un messaggio con barre inverse puo' contenere un percorso o un nome di file: meglio ometterlo del tutto.
    if (msg.contains('\\')) msg = "(messaggio omesso: contiene percorsi)"
    msg = msg.replace('\n', ' ').take(160)
    val head = t.javaClass.simpleName
    val rootName = root.javaClass.simpleName
    return if (root === t) "$head: $msg".trim().trimEnd(':') else "$head (causa: $rootName): $msg".trim().trimEnd(':')
}

/** Estensione normalizzata di un nome file; mai il nome stesso. */
fun extensionOf(name: String): String {
    val i = name.lastIndexOf('.')
    if (i <= 0 || i == name.length - 1) return "(nessuna)"
    val ext = name.substring(i + 1).lowercase(Locale.ROOT)
    return if (ext.length > 8) "(lunga)" else ext
}

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng", "avif", "tif", "tiff")
val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "mkv", "avi", "3gp", "webm", "mts", "m2ts", "mpg", "mpeg", "wmv")
