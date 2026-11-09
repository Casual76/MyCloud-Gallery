package dev.pampa.mycloudgallery.core.common.diagnostics

import android.content.Context
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.File
import java.io.IOException

/**
 * Un esito o un errore da ricordare per la sezione "Ultimi esiti ed errori" della Diagnostica.
 *
 * Fatto solo di [DiagCode], [DiagSeverity] e [ExportValue]: per costruzione non puo' contenere testo libero,
 * quindi l'anello si puo' esportare cosi' com'e'.
 *
 * @property at l'ora del dispositivo, in millisecondi dall'epoca (e' un registro locale, non un dato firmato).
 */
class DiagEvent(
  val at: Long,
  val code: DiagCode,
  val severity: DiagSeverity,
  val values: Map<String, ExportValue> = emptyMap(),
) {
  init {
    require(values.keys.all { DiagItem.EXPORT_KEY.matches(it) }) { "chiave non valida" }
  }

  override fun toString(): String = "DiagEvent($code, $severity, ${values.size} valori)"
}

/**
 * Il registro degli ultimi eventi della Diagnostica: un **anello** di [DEFAULT_CAPACITY] eventi (piano 2.7).
 * Il piu' vecchio esce quando ne entra uno nuovo; niente cresce senza limite.
 *
 * [record] e' veloce e si puo' chiamare da qualunque thread; la persistenza (se c'e') scrive una riga corta,
 * quindi chi la usa da un lavoro di rete e' gia' sul dispatcher di I/O come vuole la regola (§4.2).
 */
interface DiagnosticsLog {

  fun record(event: DiagEvent)

  /** Gli eventi, dal piu' vecchio al piu' recente. */
  fun snapshot(): List<DiagEvent>

  fun clear()

  companion object {
    const val DEFAULT_CAPACITY: Int = 500
  }
}

/** Dove l'anello si conserva tra un avvio e l'altro. */
interface DiagnosticsLogStore {
  fun load(): List<DiagEvent>

  fun append(event: DiagEvent)

  /** Riscrive tutto (compattazione, o dopo [DiagnosticsLog.clear]). */
  fun rewrite(events: List<DiagEvent>)

  /** Nessuna persistenza: l'anello vive solo in memoria. */
  object None : DiagnosticsLogStore {
    override fun load(): List<DiagEvent> = emptyList()

    override fun append(event: DiagEvent) = Unit

    override fun rewrite(events: List<DiagEvent>) = Unit
  }
}

/**
 * L'anello vero, in memoria, con una persistenza facoltativa ([store]). Alla prima lettura o scrittura
 * ricarica gli eventi salvati (al piu' [capacity]).
 */
class RingDiagnosticsLog(
  private val capacity: Int = DiagnosticsLog.DEFAULT_CAPACITY,
  private val store: DiagnosticsLogStore = DiagnosticsLogStore.None,
) : DiagnosticsLog {

  init {
    require(capacity > 0) { "capacita' non valida" }
  }

  private val events = ArrayDeque<DiagEvent>(capacity)
  private var loaded = false

  @Synchronized
  override fun record(event: DiagEvent) {
    ensureLoaded()
    if (events.size == capacity) events.removeFirst()
    events.addLast(event)
    try {
      store.append(event)
    } catch (_: IOException) {
      // La Diagnostica non deve mai far fallire chi la usa: l'evento resta almeno in memoria.
    }
  }

  @Synchronized
  override fun snapshot(): List<DiagEvent> {
    ensureLoaded()
    return events.toList()
  }

  @Synchronized
  override fun clear() {
    loaded = true
    events.clear()
    try {
      store.rewrite(emptyList())
    } catch (_: IOException) {
      // Come sopra.
    }
  }

  private fun ensureLoaded() {
    if (loaded) return
    loaded = true
    val saved = try {
      store.load()
    } catch (_: IOException) {
      emptyList()
    }
    saved.takeLast(capacity).forEach { events.addLast(it) }
  }
}

/**
 * L'anello su file, una riga per evento, in `noBackupFilesDir` (nessun backup lo copia). Il formato e'
 * fatto solo di identificatori e numeri, e una riga che non si riconosce si scarta: un file manomesso non
 * puo' far entrare testo libero nell'esportazione.
 *
 * Il file cresce fino a [compactAt] righe, poi si riscrive con le ultime [keep]: le scritture restano
 * appendi-e-basta (veloci) e il file non cresce senza limite.
 */
class FileDiagnosticsLogStore(
  private val file: File,
  private val keep: Int = DiagnosticsLog.DEFAULT_CAPACITY,
  private val compactAt: Int = keep * 2,
) : DiagnosticsLogStore {

  private var lines = -1

  @Synchronized
  override fun load(): List<DiagEvent> {
    val text = StateFiles.readLines(file, Charsets.UTF_8)
    if (text == null) {
      lines = 0
      return emptyList()
    }
    lines = text.size
    return text.mapNotNull(DiagEventCodec::decode)
  }

  @Synchronized
  override fun append(event: DiagEvent) {
    if (lines < 0) load()
    if (lines + 1 > compactAt) {
      val current = load().takeLast(keep - 1) + event
      rewrite(current)
      return
    }
    StateFiles.append(file, DiagEventCodec.encode(event) + "\n", Charsets.UTF_8)
    lines++
  }

  @Synchronized
  override fun rewrite(events: List<DiagEvent>) {
    StateFiles.writeText(file, events.joinToString(separator = "") { DiagEventCodec.encode(it) + "\n" }, Charsets.UTF_8)
    lines = events.size
  }

  companion object {
    /** Il file dell'app: `noBackupFilesDir/diagnostics/events.log`. */
    fun inNoBackupDir(context: Context): FileDiagnosticsLogStore =
      FileDiagnosticsLogStore(File(context.noBackupFilesDir, "diagnostics/events.log"))
  }
}

/**
 * Una riga: `at|CODICE|GRAVITA|chiave=valore;chiave=valore`. Valori: `n123`, `f1`/`f0`, `d250`,
 * `eTipo.COSTANTE`, `h0123456789ab`, `tClasse,Classe.metodo(File.kt:1)`. Le chiavi e i nomi sono identificatori validati, quindi i separatori
 * non possono comparire dentro un campo.
 */
internal object DiagEventCodec {

  fun encode(event: DiagEvent): String {
    val values = event.values.entries.joinToString(";") { (key, value) -> key + "=" + encodeValue(value) }
    return "${event.at}|${event.code.id}|${event.severity.name}|$values"
  }

  fun decode(line: String): DiagEvent? {
    val parts = line.split('|')
    if (parts.size != 4) return null
    val at = parts[0].toLongOrNull() ?: return null
    if (!DiagCode.ID.matches(parts[1])) return null
    val severity = DiagSeverity.entries.firstOrNull { it.name == parts[2] } ?: return null
    val values = LinkedHashMap<String, ExportValue>()
    if (parts[3].isNotEmpty()) {
      for (pair in parts[3].split(';')) {
        val key = pair.substringBefore('=', missingDelimiterValue = "")
        val raw = pair.substringAfter('=', missingDelimiterValue = "")
        if (!DiagItem.EXPORT_KEY.matches(key)) return null
        values[key] = decodeValue(raw) ?: return null
      }
    }
    return DiagEvent(at, DiagCode(parts[1]), severity, values)
  }

  private fun encodeValue(value: ExportValue): String = when (value) {
    is ExportValue.Num -> "n${value.value}"
    is ExportValue.Flag -> if (value.value) "f1" else "f0"
    is ExportValue.Dur -> "d${value.millis}"
    is ExportValue.Enum -> "e${value.type}.${value.constant}"
    is ExportValue.Hashed -> "h${value.hex}"
    // Gli identificativi del codice hanno solo lettere, cifre e . $ < > - ( ) : e mai una virgola: la virgola li separa.
    is ExportValue.Trace -> "t" + value.entries.joinToString(",")
  }

  private fun decodeValue(raw: String): ExportValue? {
    if (raw.isEmpty()) return null
    val body = raw.substring(1)
    return when (raw[0]) {
      'n' -> body.toLongOrNull()?.let { ExportValue.Num(it) }
      'f' -> when (body) {
        "1" -> ExportValue.Flag(true)
        "0" -> ExportValue.Flag(false)
        else -> null
      }
      'd' -> body.toLongOrNull()?.let { ExportValue.Dur(it) }
      'e' -> ExportValue.Enum.restore(body.substringBefore('.'), body.substringAfter('.', missingDelimiterValue = ""))
      'h' -> if (ExportValue.Hashed.HEX.matches(body)) ExportValue.Hashed(body) else null
      't' -> ExportValue.Trace.of(if (body.isEmpty()) emptyList() else body.split(','))
      else -> null
    }
  }
}

/** Il null-object: non ricorda niente (prima che :app leghi l'anello vero, o nei test che non lo guardano). */
object NoDiagnosticsLog : DiagnosticsLog {
  override fun record(event: DiagEvent) = Unit

  override fun snapshot(): List<DiagEvent> = emptyList()

  override fun clear() = Unit
}
