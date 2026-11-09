package dev.pampa.mycloudgallery.core.rules.platform.crash

import android.content.Context
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.File
import java.io.IOException

/**
 * Il **registro locale dei crash** (C472, OA-2 / OB-7): gli ultimi crash e ANR di questo telefono, in `noBackupFilesDir/crash/crashes.log` (non viaggia in nessun
 * backup, non esce dal telefono). Lo legge la Diagnostica, che lo mostra e lo mette nell'esportazione manuale, con l'anteprima come il resto: nessuna telemetria.
 *
 * - **Cosa c'e' dentro:** per ogni crash l'ora, la famiglia del thread, la versione dell'app e la catena delle eccezioni, di ognuna la classe e le prime cornici nostre come
 *   `classe.metodo(File.kt:riga)`. **Mai un messaggio** ([CrashDigest]) e mai un percorso. Il file e' un dato (qualcuno potrebbe averlo toccato): ogni riga si rilegge
 *   con la stessa grammatica con cui e' stata scritta, e una riga che non la rispetta fa scartare il suo crash.
 * - **Quanti:** gli ultimi [MAX_RECORDS], con un tetto di dimensione ([MAX_BYTES]): il piu' vecchio esce per primo, il nuovo c'e' sempre.
 * - **Scrittura atomica** (`StateFiles`): chi legge vede il file di prima intero o quello nuovo intero. Chi chiama ([CrashLogHandler]) non deve poter cadere a sua volta.
 *
 * Formato (testo, una riga per campo, pensato per essere letto anche a occhio):
 * ```
 * mycloudgallery-crash 1
 * R CRASH <ora ms> <versionCode> <FAMIGLIA> <main|other>
 * C <classe dell'eccezione>
 * F <classe.metodo(File.kt:riga)>
 * C <classe della causa>
 * F ...
 * ```
 */
class CrashLogStore(private val file: File) {

  /** Aggiunge [record] (il piu' recente), tiene gli ultimi [MAX_RECORDS] dentro [MAX_BYTES]. Lancia `IOException` se non riesce a scrivere: il file di prima e' intatto. */
  @Synchronized
  fun record(record: CrashRecord) {
    val kept = readUnlocked()
    val all = ArrayList<CrashRecord>(kept.size + 1)
    all += record
    all += kept
    var records = all.take(MAX_RECORDS)
    var text = CrashLogFormat.encode(records)
    // Il tetto di dimensione: si toglie il piu' vecchio finche' ci si sta; il nuovo (il primo) resta sempre.
    while (records.size > 1 && text.length > MAX_BYTES) {
      records = records.dropLast(1)
      text = CrashLogFormat.encode(records)
    }
    StateFiles.writeText(file, text, Charsets.UTF_8, label = FILE_LABEL)
  }

  /** I crash registrati, dal piu' recente. Mai un'eccezione: un file che non c'e', rovinato o troppo grande vale "niente registrato". */
  @Synchronized
  fun read(): List<CrashRecord> = readUnlocked()

  /** Toglie il registro (azzeramento dell'app, o l'utente che lo vuole vuotare). Vero se non c'e' piu'. */
  @Synchronized
  fun clear(): Boolean = StateFiles.delete(file, label = FILE_LABEL)

  private fun readUnlocked(): List<CrashRecord> = try {
    StateFiles.readText(file, Charsets.UTF_8, label = FILE_LABEL, maxBytes = READ_LIMIT_BYTES)?.let { CrashLogFormat.decode(it) }.orEmpty()
  } catch (_: IOException) {
    emptyList()
  }

  companion object {
    /** Quanti crash si ricordano. */
    const val MAX_RECORDS = 10

    /** Il tetto di dimensione del file scritto (96 KiB): dieci crash con le loro catene ci stanno largamente, un caso patologico no. */
    const val MAX_BYTES = 96 * 1024

    /** Un file piu' grande di cosi' non e' il nostro (lo avremmo tenuto sotto [MAX_BYTES]): non si legge. */
    private const val READ_LIMIT_BYTES = 4L * MAX_BYTES

    private const val FILE_LABEL = "crashes.log"

    /** Il file dell'app: `noBackupFilesDir/crash/crashes.log`. */
    fun inNoBackupDir(context: Context): CrashLogStore = CrashLogStore(File(context.noBackupFilesDir, "crash/crashes.log"))
  }
}

/** La scrittura e la lettura del registro, con la grammatica (niente testo libero). */
internal object CrashLogFormat {

  const val HEADER = "mycloudgallery-crash 1"

  fun encode(records: List<CrashRecord>): String = buildString {
    append(HEADER).append('\n')
    for (record in records) {
      append("R ").append(record.kind.name).append(' ').append(record.atMillis).append(' ').append(record.versionCode).append(' ')
        .append(record.thread.name).append(' ').append(if (record.mainProcess) "main" else "other").append('\n')
      for (cause in record.chain) {
        append("C ").append(cause.className).append('\n')
        for (frame in cause.frames) append("F ").append(frame).append('\n')
      }
    }
  }

  /** I crash del testo, dal piu' recente (l'ordine del file). Un crash con una riga fuori grammatica si scarta intero; il resto si salva. */
  fun decode(text: String): List<CrashRecord> {
    val lines = text.lineSequence().iterator()
    if (!lines.hasNext() || lines.next() != HEADER) return emptyList()
    val records = ArrayList<CrashRecord>()
    var current: Draft? = null

    fun finish() {
      current?.build()?.let { records += it }
      current = null
    }

    while (lines.hasNext()) {
      val line = lines.next()
      if (line.isEmpty()) continue
      when {
        line.startsWith("R ") -> {
          finish()
          current = Draft.parse(line.substring(2))
        }
        line.startsWith("C ") -> current?.cause(line.substring(2))
        line.startsWith("F ") -> current?.frame(line.substring(2))
        else -> current?.spoil()
      }
    }
    finish()
    return records.take(CrashLogStore.MAX_RECORDS)
  }

  /** Un crash che si sta rileggendo: se una riga non rispetta la grammatica resta "rovinato" e [build] non lo restituisce. */
  private class Draft private constructor(
    val kind: CrashKind,
    val atMillis: Long,
    val versionCode: Long,
    val thread: ThreadKind,
    val mainProcess: Boolean,
  ) {
    private var spoiled = false
    private val causes = ArrayList<Pair<String, MutableList<String>>>()

    fun spoil() {
      spoiled = true
    }

    fun cause(className: String) {
      if (!ExportValue.Trace.isEntry(className) || causes.size >= CrashDigest.MAX_CAUSES) return spoil()
      causes += className to ArrayList()
    }

    fun frame(frame: String) {
      val frames = causes.lastOrNull()?.second ?: return spoil()
      if (!ExportValue.Trace.isEntry(frame) || frames.size >= CrashDigest.MAX_FRAMES) return spoil()
      frames += frame
    }

    fun build(): CrashRecord? {
      if (spoiled || causes.isEmpty()) return null
      return CrashRecord(kind, atMillis, versionCode, thread, mainProcess, causes.map { (name, frames) -> CrashCause(name, frames.toList()) })
    }

    companion object {
      /** Un campo che manca o non e' valido da' un crash rovinato (null non serve: si registra "rovinato" e si scarta a [build]). */
      fun parse(fields: String): Draft {
        val parts = fields.split(' ')
        val kind = parts.getOrNull(0)?.let { name -> CrashKind.entries.firstOrNull { it.name == name } }
        val at = parts.getOrNull(1)?.toLongOrNull()
        val version = parts.getOrNull(2)?.toLongOrNull()
        val thread = parts.getOrNull(3)?.let { name -> ThreadKind.entries.firstOrNull { it.name == name } }
        val process = parts.getOrNull(4)
        val valid = parts.size == 5 && kind != null && at != null && version != null && thread != null && (process == "main" || process == "other")
        return Draft(
          kind = kind ?: CrashKind.CRASH,
          atMillis = at ?: 0L,
          versionCode = version ?: 0L,
          thread = thread ?: ThreadKind.OTHER,
          mainProcess = process == "main",
        ).also { if (!valid) it.spoil() }
      }
    }
  }
}
