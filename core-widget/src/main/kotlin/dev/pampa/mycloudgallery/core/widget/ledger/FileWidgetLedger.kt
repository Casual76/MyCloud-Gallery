package dev.pampa.mycloudgallery.core.widget.ledger

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCellRef
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.CRC32
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dove sta il registro: i byte di un solo file. Il vero e' [FileLedgerStorage] (scrittura atomica); una prova ci mette un deposito che
 * muore a meta' scrittura.
 */
interface WidgetLedgerStorage {
  /** I byte del file, o null se non c'e'. Lancia `IOException` se non si legge. */
  fun read(): ByteArray?

  /** Sostituisce il file con [bytes], tutto o niente. Lancia `IOException` se non riesce: il file di prima resta com'era. */
  fun write(bytes: ByteArray)

  /** Toglie il file (e il suo temporaneo). Vero se non c'e' piu'. */
  fun delete(): Boolean
}

/** Il file `widget/ledger.mcgw` in `noBackupFilesDir`, con [StateFiles]: temporaneo, `fsync`, rinomina atomica. */
class FileLedgerStorage(private val file: File) : WidgetLedgerStorage {
  override fun read(): ByteArray? = StateFiles.readBytes(file, label = FILE_NAME, maxBytes = LedgerCodec.MAX_FILE_BYTES)

  override fun write(bytes: ByteArray) = StateFiles.writeBytes(file, bytes, label = FILE_NAME)

  override fun delete(): Boolean = StateFiles.delete(file, label = FILE_NAME)

  companion object {
    const val FILE_NAME: String = "ledger.mcgw"
  }
}

/**
 * Il registro del widget su file (D9, D5, D42; §4.3 "Stabilita'"): per ogni widget l'epoca dell'ultimo piano e le sue celle (il media e, per la
 * copertina di un ricordo, il ricordo). Sta in `noBackupFilesDir/widget/`: non si copia in nessun backup e sparisce con i dati dell'app.
 *
 * - **Scrittura atomica**: temporaneo accanto al file, `fsync`, rinomina con `ATOMIC_MOVE` ([StateFiles]). Chi muore **prima** della rinomina
 *   lascia il registro di prima, intero (un temporaneo a meta' non si legge mai); chi muore dopo, quello nuovo.
 * - **Un file rovinato e' un registro vuoto**, mai un crollo: formato sbagliato, versione che non conosco, troncato, un byte cambiato (CRC),
 *   codici non validi, numeri fuori dai limiti, byte in piu'. Il widget si ridisegna e il registro si rifa' con il piano nuovo. **Vuoto non vuol dire "nessuna foto sulla
 *   Home"** (R4): chi svuota per un nascosto tratta ogni widget che il registro non conosce come da svuotare (`WidgetEngine.affectedBy`, `WidgetGraph.watch`).
 * - **Tutto o niente anche in memoria**: lo stato in memoria cambia solo se la scrittura e' riuscita; una scrittura fallita lancia
 *   `IOException` e chi chiama lo sa (P3: il disegno non si consegna senza registro, §2.1 passo 7).
 * - Un lucchetto solo (`Mutex`): le chiamate si accodano; la lettura e la scrittura girano su [io].
 *
 * Il file contiene id di media: nei log mai (nessun `toString()` li mostra, e gli errori portano solo il tipo).
 *
 * Formato (big endian): `"MCGW"` | versione (2 byte) | lunghezza del contenuto (4) | contenuto | CRC-32 di tutto il precedente (4). Contenuto: numero di
 * widget (2), poi per ognuno `appWidgetId` (4), epoca (8), numero di celle (1) e per ogni cella i 16 byte del media, un byte "ha un
 * ricordo" e, se 1, le 26 lettere dell'id del ricordo.
 */
class FileWidgetLedger(
  private val storage: WidgetLedgerStorage,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : WidgetLedger {

  /** [dir] e' `noBackupFilesDir/widget/`: il file e' [FileLedgerStorage.FILE_NAME]. */
  constructor(dir: File, io: CoroutineDispatcher = Dispatchers.IO) : this(FileLedgerStorage(File(dir, FileLedgerStorage.FILE_NAME)), io)

  private val mutex = Mutex()
  private var loaded: Map<WidgetSlot, LedgerRow>? = null

  private suspend fun <T> locked(block: suspend (Map<WidgetSlot, LedgerRow>) -> T): T = mutex.withLock {
    val current = loaded ?: load().also { loaded = it }
    block(current)
  }

  private suspend fun load(): Map<WidgetSlot, LedgerRow> = withContext(io) {
    val bytes = try {
      storage.read()
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "Registro del widget non leggibile: riparte vuoto." }
      return@withContext emptyMap()
    } ?: return@withContext emptyMap()
    LedgerCodec.decode(bytes) ?: run {
      GalleryLog.w(TAG) { "Registro del widget rovinato: riparte vuoto." }
      emptyMap()
    }
  }

  /** Scrive [next] e solo dopo lo rende lo stato in memoria. */
  private suspend fun commit(next: Map<WidgetSlot, LedgerRow>) {
    withContext(io) { storage.write(LedgerCodec.encode(next)) }
    loaded = next
  }

  override suspend fun record(plan: WidgetPlan) {
    locked { rows ->
      val current = rows[plan.slot]
      if (current != null && plan.epoch < current.epoch) return@locked
      val memory = (plan.mode as? WidgetMode.Memory)?.id
      val cells = plan.cells.map { WidgetCellRef(it.media, if (it.index == 0) memory else null) }
      if (plan.slot !in rows && rows.size >= LedgerCodec.MAX_SLOTS) throw IOException("troppi widget nel registro")
      commit(rows + (plan.slot to LedgerRow(plan.epoch, cells)))
    }
  }

  override suspend fun cell(slot: WidgetSlot, index: Int): WidgetCellRef? = locked { it[slot]?.cells?.getOrNull(index) }

  override suspend fun showing(ids: Set<MediaId>): Set<WidgetSlot> =
    locked { rows -> rows.filterValues { row -> row.cells.any { it.media in ids } }.keys.toSet() }

  override suspend fun showingMemory(id: MemoryId): Set<WidgetSlot> =
    locked { rows -> rows.filterValues { row -> row.cells.any { it.memory == id } }.keys.toSet() }

  override suspend fun slots(): Set<WidgetSlot> = locked { it.keys.toSet() }

  override suspend fun epochOf(slot: WidgetSlot): Long = locked { it[slot]?.epoch ?: 0L }

  override suspend fun forget(slot: WidgetSlot) {
    locked { rows -> if (slot in rows) commit(rows - slot) }
  }

  /** Azzeramento (D42): il file sparisce. Se non si riesce a toglierlo si riscrive vuoto; se nemmeno questo, `IOException`. */
  override suspend fun clear() {
    locked {
      withContext(io) {
        if (!storage.delete()) storage.write(LedgerCodec.encode(emptyMap()))
      }
      loaded = emptyMap()
    }
  }

  private companion object {
    const val TAG = "WidgetLedger"
  }
}

/** Una riga del registro: l'epoca e le celle del piano (vuote per un messaggio). */
internal data class LedgerRow(val epoch: Long, val cells: List<WidgetCellRef>)

/** Il formato del file del registro (vedi [FileWidgetLedger]). Puro: nessun file qui, solo byte. */
internal object LedgerCodec {
  private val MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte())
  private const val VERSION: Int = 1
  private const val HEADER_BYTES = 4 + 2 + 4
  private const val CRC_BYTES = 4

  /** Quanti widget al piu' (Android ne da' pochi: il tetto serve a non leggere un file ostile come fosse vero). */
  const val MAX_SLOTS: Int = 256

  /** Il file piu' grande possibile: 256 widget da 7 celle di 43 byte, con un po' d'aria. Oltre non si legge. */
  const val MAX_FILE_BYTES: Long = 128L * 1024

  fun encode(rows: Map<WidgetSlot, LedgerRow>): ByteArray {
    val body = ByteArrayOutputStream()
    DataOutputStream(body).use { out ->
      out.writeShort(rows.size)
      for ((slot, row) in rows.toSortedMap(compareBy<WidgetSlot> { it.appWidgetId })) {
        out.writeInt(slot.appWidgetId)
        out.writeLong(row.epoch)
        out.writeByte(row.cells.size)
        for (cell in row.cells) {
          val uuid = UUID.fromString(cell.media.value)
          out.writeLong(uuid.mostSignificantBits)
          out.writeLong(uuid.leastSignificantBits)
          val memory = cell.memory
          if (memory == null) {
            out.writeByte(0)
          } else {
            out.writeByte(1)
            out.write(memory.value.toByteArray(Charsets.US_ASCII))
          }
        }
      }
    }
    val content = body.toByteArray()
    val whole = ByteArrayOutputStream(HEADER_BYTES + content.size + CRC_BYTES)
    DataOutputStream(whole).use { out ->
      out.write(MAGIC)
      out.writeShort(VERSION)
      out.writeInt(content.size)
      out.write(content)
    }
    val head = whole.toByteArray()
    val crc = CRC32().apply { update(head) }.value.toInt()
    return head + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
  }

  /** Le righe, o null se il file non e' un registro valido (rovinato, troncato, di un'altra versione, con byte in piu'). Non lancia. */
  fun decode(bytes: ByteArray): Map<WidgetSlot, LedgerRow>? {
    try {
      if (bytes.size < HEADER_BYTES + CRC_BYTES || bytes.size > MAX_FILE_BYTES) return null
      for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
      val input = DataInputStream(ByteArrayInputStream(bytes))
      input.skipBytes(MAGIC.size)
      if (input.readUnsignedShort() != VERSION) return null
      val length = input.readInt()
      if (length < 0 || bytes.size.toLong() != HEADER_BYTES.toLong() + length + CRC_BYTES) return null
      val stored = DataInputStream(ByteArrayInputStream(bytes, HEADER_BYTES + length, CRC_BYTES)).readInt()
      val crc = CRC32().apply { update(bytes, 0, HEADER_BYTES + length) }.value.toInt()
      if (stored != crc) return null
      return readRows(DataInputStream(ByteArrayInputStream(bytes, HEADER_BYTES, length)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Fine del file inattesa, id non validi, numeri fuori dai limiti: e' un file rovinato, non un errore.
      return null
    }
  }

  private fun readRows(input: DataInputStream): Map<WidgetSlot, LedgerRow>? {
    val count = input.readUnsignedShort()
    if (count > MAX_SLOTS) return null
    val rows = LinkedHashMap<WidgetSlot, LedgerRow>(count)
    repeat(count) {
      val slot = WidgetSlot(input.readInt())
      val epoch = input.readLong()
      if (epoch < 0) return null
      val cellCount = input.readUnsignedByte()
      if (cellCount > WidgetRules.MAX_IMAGES) return null
      val cells = ArrayList<WidgetCellRef>(cellCount)
      repeat(cellCount) {
        val media = MediaId(UUID(input.readLong(), input.readLong()).toString())
        val memory = when (input.readUnsignedByte()) {
          0 -> null
          1 -> MemoryId(String(ByteArray(26).also { input.readFully(it) }, Charsets.US_ASCII))
          else -> return null
        }
        cells += WidgetCellRef(media, memory)
      }
      if (rows.put(slot, LedgerRow(epoch, cells)) != null) return null
    }
    if (input.available() != 0) return null
    return rows
  }
}
