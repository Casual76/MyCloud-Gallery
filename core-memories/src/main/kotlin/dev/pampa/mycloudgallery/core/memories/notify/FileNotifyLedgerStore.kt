package dev.pampa.mycloudgallery.core.memories.notify

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Come [FileNotifyLedgerStore] tocca il disco: le prove sostituiscono **solo** questo (la morte del processo a meta' scrittura). Quello di serie e' [StateFilesLedgerIo]. */
internal interface LedgerIo {
  /** I byte del file, o null se non c'e'. Lancia `IOException` se c'e' ma non si legge. */
  fun read(file: File): ByteArray?

  /** Scrive il file in modo atomico: chi legge dopo vede quello di prima intero o quello nuovo intero. */
  fun write(file: File, bytes: ByteArray)

  /** Toglie il file e il suo temporaneo; falso se ne resta uno. */
  fun delete(file: File): Boolean
}

internal object StateFilesLedgerIo : LedgerIo {
  override fun read(file: File): ByteArray? = StateFiles.readBytes(file, LABEL, maxBytes = NotifyLedgerCodec.MAX_BYTES.toLong() + 1)

  override fun write(file: File, bytes: ByteArray) = StateFiles.writeBytes(file, bytes, LABEL)

  override fun delete(file: File): Boolean = StateFiles.delete(file, LABEL)

  private const val LABEL = "notified"
}

/**
 * Il formato del registro locale del giorno (D15): testo ASCII, **la prima riga e' il marcatore, l'ultima e' il CRC-32 di tutto cio' che la
 * precede**; in mezzo una riga per giorno, `aaaammgg` e `1` se era un anniversario (`0` se no). Mai un id di ricordo, un titolo o un'ora: solo
 * "in che giorno e' partita una notifica". Un file che non torna (CRC, righe, giorni, troppo grande) vale "rovinato".
 */
internal object NotifyLedgerCodec {
  const val MAX_BYTES: Int = 4_096
  private const val MAGIC = "MCGN1"
  private const val CRC_PREFIX = "crc "

  fun encode(ledger: NotifyLedger): ByteArray {
    val body = StringBuilder().append(MAGIC).append('\n')
    for (mark in ledger.marks.takeLast(NotifyRules.LEDGER_DAYS)) body.append(mark.day).append(' ').append(if (mark.special) '1' else '0').append('\n')
    val crc = CRC32().apply { update(body.toString().toByteArray(Charsets.US_ASCII)) }.value
    return body.append(CRC_PREFIX).append(String.format(Locale.ROOT, "%08x", crc)).append('\n').toString().toByteArray(Charsets.US_ASCII)
  }

  /** Il registro, o null se il file e' rovinato. */
  fun decode(bytes: ByteArray): NotifyLedger? {
    if (bytes.size > MAX_BYTES || bytes.any { it < 0 || it > 126 }) return null
    val text = bytes.toString(Charsets.US_ASCII)
    if (!text.endsWith("\n")) return null
    val lines = text.dropLast(1).split('\n')
    if (lines.size < 2 || lines.first() != MAGIC) return null
    val crcLine = lines.last()
    if (!crcLine.startsWith(CRC_PREFIX)) return null
    val crcText = crcLine.removePrefix(CRC_PREFIX)
    if (crcText.length != 8 || crcText.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val expected = crcText.toLong(16)
    val body = text.substring(0, text.length - 1 - crcLine.length)
    if (CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value != expected) return null
    val marks = ArrayList<NotifyMark>()
    for (line in lines.subList(1, lines.size - 1)) {
      val parts = line.split(' ')
      if (parts.size != 2) return null
      val day = parts[0].takeIf { it.length == 8 }?.toIntOrNull() ?: return null
      if (!isDay(day)) return null
      val special = when (parts[1]) {
        "1" -> true
        "0" -> false
        else -> return null
      }
      marks += NotifyMark(day, special)
    }
    if (marks.size > NotifyRules.LEDGER_DAYS || marks.map { it.day }.distinct().size != marks.size) return null
    return NotifyLedger(marks.sortedBy { it.day })
  }

  private fun isDay(day: Int): Boolean = MemoryDays.dateOf(day) != null
}

/**
 * Il registro locale del giorno su file (docs/fase9-progetto.md D15, E-NT5; modello-dati.md §16): `noBackupFilesDir/memories/notified`, mai sul
 * NAS, mai nei backup, tolto dall'azzeramento (D37). **Scritto in modo atomico** (`StateFiles`: temporaneo accanto al file, `fsync`, rinomina): la
 * morte del processo a meta' scrittura lascia il registro di prima, intero. Si scrive **prima** di pubblicare la notifica: se il processo muore
 * fra le due cose quel giorno resta senza notifica, mai con due.
 *
 * - [read] di un file rovinato **lancia** `IOException` (non risponde "vuoto": un registro vuoto lascerebbe partire una seconda notifica nello
 *   stesso giorno); [clear] lo rimette a posto. Un file che non c'e' e' un registro vuoto.
 * - [record] su un file rovinato riparte da un registro vuoto e lo riscrive.
 * - Le tre operazioni lavorano su [dispatcher] (di serie `Dispatchers.IO`) e una alla volta.
 *
 * Nel log, via `StateFiles`, solo il nome fisso del file e il tipo dell'errore: mai un percorso, mai un giorno.
 */
class FileNotifyLedgerStore internal constructor(
  private val directory: File,
  private val io: LedgerIo,
  private val dispatcher: CoroutineDispatcher,
) : NotifyLedgerStore {

  /** [directory] e' `noBackupFilesDir/memories`: il file e' `notified`. */
  constructor(directory: File, dispatcher: CoroutineDispatcher = Dispatchers.IO) : this(directory, StateFilesLedgerIo, dispatcher)

  private val lock = Mutex()
  private val target: File get() = File(directory, FILE_NAME)

  override suspend fun read(): NotifyLedger = withContext(dispatcher) { lock.withLock { readLocked() } }

  override suspend fun record(mark: NotifyMark, today: LocalDate) {
    withContext(dispatcher) {
      lock.withLock {
        val current = try {
          readLocked()
        } catch (_: CorruptLedgerException) {
          NotifyLedger()
        }
        io.write(target, NotifyLedgerCodec.encode(current.with(mark, today)))
      }
    }
  }

  /** Toglie il registro; se non ci riesce lo dice (l'azzeramento deve saperlo): `IOException`. */
  override suspend fun clear() {
    val gone = withContext(dispatcher) { lock.withLock { io.delete(target) } }
    if (!gone) throw IOException("registro delle notifiche non tolto")
  }

  private fun readLocked(): NotifyLedger {
    val file = target
    if (file.isFile && file.length() > NotifyLedgerCodec.MAX_BYTES) throw CorruptLedgerException()
    val bytes = io.read(file) ?: return NotifyLedger()
    return NotifyLedgerCodec.decode(bytes) ?: throw CorruptLedgerException()
  }

  companion object {
    const val FILE_NAME: String = "notified"
  }
}

/** Il file del registro c'e' ma non torna: e' un `IOException` (chi legge non puo' provare "nessuna notifica oggi"). */
class CorruptLedgerException : IOException("registro delle notifiche rovinato")
