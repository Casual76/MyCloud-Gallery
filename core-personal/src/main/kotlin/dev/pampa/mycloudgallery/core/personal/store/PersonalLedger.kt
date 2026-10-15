package dev.pampa.mycloudgallery.core.personal.store

import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalPublication
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Il registro locale dei dati personali: tutto cio' che lo store deve sapere dopo la morte del processo (§2.9 "Modifiche offline": una
// modifica fatta senza ora del NAS resta "da datare" anche se l'app si chiude). Un'istantanea sola, scritta per intero in modo
// atomico: le voci applicate e la testa da cui vengono arrivano insieme (un crash non lascia mai una testa "gia' letta" senza le sue
// voci), e la proiezione sul catalogo si puo' rifare da qui.

/** Una modifica dell'utente senza ora del NAS: il valore c'e', l'ora no. Non si pubblica finche' non e' datata. */
data class PendingChange(val value: PersonalValue, val fingerprint: PersonalFingerprint?)

/** Lo stato persistente. Le mappe sono copie: chi riceve un'istantanea non cambia lo stato di chi l'ha data. */
class LedgerSnapshot(
  /** Lo stato unito: per ogni chiave la voce che vince. */
  val entries: Map<PersonalKey, PersonalEntry> = emptyMap(),
  val pending: Map<PersonalKey, PendingChange> = emptyMap(),
  /** Le voci applicate allo stato ma non (ancora) al catalogo: si riprovano. */
  val unprojected: Set<PersonalKey> = emptySet(),
  /** L'ultima testa accettata per dispositivo (anti-rollback). */
  val marks: Map<String, HeadMark> = emptyMap(),
  val published: PersonalPublication? = null,
) {
  companion object {
    val EMPTY = LedgerSnapshot()
  }
}

/** Dove sta l'istantanea. `:app` passa [FilePersonalLedger] su un file in `noBackupFilesDir` (escluso dal backup, tolto dall'azzeramento). */
interface PersonalLedger {
  /** L'ultima istantanea salvata, o [LedgerSnapshot.EMPTY] se non c'e' o non si legge (mai un'eccezione per un file rovinato). */
  suspend fun load(): LedgerSnapshot

  suspend fun save(snapshot: LedgerSnapshot)
}

/**
 * Su file: un'istantanea binaria con intestazione e CRC-32, scritta in un temporaneo e rinominata sopra la precedente (mai un file a
 * meta'). Un file mancante, troncato, rovinato o di una versione che non conosciamo vale "vuoto": al giro dopo lo stato torna dai
 * dispositivi (teste riletta da zero) e dal catalogo (`PersonalSyncStore.adoptLocal`), e si perdono solo le modifiche "da datare".
 */
class FilePersonalLedger(private val file: File) : PersonalLedger {

  override suspend fun load(): LedgerSnapshot = withContext(Dispatchers.IO) {
    try {
      if (!file.isFile || file.length() > MAX_FILE_BYTES) return@withContext LedgerSnapshot.EMPTY
      decode(file.readBytes())
    } catch (e: IOException) {
      LedgerSnapshot.EMPTY
    } catch (e: RuntimeException) {
      LedgerSnapshot.EMPTY
    }
  }

  override suspend fun save(snapshot: LedgerSnapshot): Unit = withContext(Dispatchers.IO) {
    val bytes = encode(snapshot)
    val dir = file.absoluteFile.parentFile
    if (dir != null && !dir.isDirectory) dir.mkdirs()
    val temp = File(dir, file.name + ".tmp")
    FileOutputStream(temp).use { out ->
      out.write(bytes)
      out.fd.sync()
    }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  internal companion object {
    const val MAX_FILE_BYTES: Long = 256L * 1024 * 1024
    private const val MAX_ITEMS = 4_000_000
    private const val VERSION = 1
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte())

    fun encode(snapshot: LedgerSnapshot): ByteArray {
      val body = ByteArrayOutputStream()
      DataOutputStream(body).use { out ->
        out.writeInt(snapshot.entries.size)
        for (entry in snapshot.entries.values) {
          writeKey(out, entry.key)
          out.writeLong(entry.atNas)
          out.writeUTF(entry.author)
          writeValue(out, entry.value)
          writeFingerprint(out, entry.fingerprint)
        }
        out.writeInt(snapshot.pending.size)
        for ((key, change) in snapshot.pending) {
          writeKey(out, key)
          writeValue(out, change.value)
          writeFingerprint(out, change.fingerprint)
        }
        out.writeInt(snapshot.unprojected.size)
        snapshot.unprojected.forEach { writeKey(out, it) }
        out.writeInt(snapshot.marks.size)
        for ((device, mark) in snapshot.marks) {
          out.writeUTF(device)
          writeMark(out, mark)
        }
        val published = snapshot.published
        out.writeBoolean(published != null)
        if (published != null) {
          out.writeLong(published.version)
          writeMark(out, published.mark)
          out.writeLong(published.publishedAtNas)
          out.writeInt(published.entries)
          out.writeInt(published.segments.size)
          published.segments.forEach(out::writeUTF)
        }
      }
      val payload = body.toByteArray()
      val crc = CRC32().apply { update(payload) }.value
      val out = ByteArrayOutputStream(MAGIC.size + 1 + payload.size + 8)
      DataOutputStream(out).use {
        it.write(MAGIC)
        it.writeByte(VERSION)
        it.write(payload)
        it.writeLong(crc)
      }
      return out.toByteArray()
    }

    fun decode(bytes: ByteArray): LedgerSnapshot {
      val header = MAGIC.size + 1
      if (bytes.size < header + 8 || !MAGIC.indices.all { bytes[it] == MAGIC[it] } || bytes[MAGIC.size].toInt() != VERSION) return LedgerSnapshot.EMPTY
      val payload = bytes.copyOfRange(header, bytes.size - 8)
      val crc = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 8, 8)).readLong()
      if (CRC32().apply { update(payload) }.value != crc) return LedgerSnapshot.EMPTY
      val input = DataInputStream(ByteArrayInputStream(payload))
      val entries = LinkedHashMap<PersonalKey, PersonalEntry>()
      repeat(count(input)) {
        val key = readKey(input)
        val at = input.readLong()
        val author = input.readUTF()
        val value = readValue(input)
        val fingerprint = readFingerprint(input)
        entries[key] = PersonalEntry(key, at, author, value, fingerprint)
      }
      val pending = LinkedHashMap<PersonalKey, PendingChange>()
      repeat(count(input)) {
        val key = readKey(input)
        pending[key] = PendingChange(readValue(input), readFingerprint(input))
      }
      val unprojected = LinkedHashSet<PersonalKey>()
      repeat(count(input)) { unprojected += readKey(input) }
      val marks = LinkedHashMap<String, HeadMark>()
      repeat(count(input)) {
        val device = input.readUTF()
        marks[device] = readMark(input)
      }
      val published = if (input.readBoolean()) {
        val version = input.readLong()
        val mark = readMark(input)
        val at = input.readLong()
        val entryCount = input.readInt()
        val segments = LinkedHashSet<String>()
        repeat(count(input)) { segments += input.readUTF() }
        PersonalPublication(version, mark, at, segments, entryCount)
      } else {
        null
      }
      if (input.available() != 0) return LedgerSnapshot.EMPTY
      return LedgerSnapshot(entries, pending, unprojected, marks, published)
    }

    private fun count(input: DataInputStream): Int {
      val n = input.readInt()
      if (n !in 0..MAX_ITEMS) throw IOException("conteggio fuori misura")
      return n
    }

    private fun writeKey(out: DataOutputStream, key: PersonalKey) {
      out.writeUTF(key.kind.code)
      out.writeUTF(key.subject)
      out.writeUTF(key.field)
    }

    private fun readKey(input: DataInputStream): PersonalKey {
      val code = input.readUTF()
      val kind = PersonalKind.entries.firstOrNull { it.code == code } ?: throw IOException("tipo sconosciuto")
      return PersonalKey(kind, input.readUTF(), input.readUTF())
    }

    private fun writeValue(out: DataOutputStream, value: PersonalValue) {
      when (value) {
        is PersonalValue.Flag -> out.writeByte(if (value.on) TAG_TRUE else TAG_FALSE)
        is PersonalValue.Number -> {
          out.writeByte(TAG_NUMBER)
          out.writeLong(value.value)
        }
        is PersonalValue.Text -> {
          out.writeByte(TAG_TEXT)
          out.writeUTF(value.text)
        }
        PersonalValue.Removed -> out.writeByte(TAG_REMOVED)
      }
    }

    private fun readValue(input: DataInputStream): PersonalValue = when (input.readUnsignedByte()) {
      TAG_FALSE -> PersonalValue.Flag(false)
      TAG_TRUE -> PersonalValue.Flag(true)
      TAG_NUMBER -> PersonalValue.Number(input.readLong())
      TAG_TEXT -> PersonalValue.Text(input.readUTF())
      TAG_REMOVED -> PersonalValue.Removed
      else -> throw IOException("valore sconosciuto")
    }

    private fun writeFingerprint(out: DataOutputStream, fingerprint: PersonalFingerprint?) {
      out.writeBoolean(fingerprint != null)
      if (fingerprint != null) {
        out.writeLong(fingerprint.size)
        out.writeUTF(fingerprint.quickHash.hex)
      }
    }

    private fun readFingerprint(input: DataInputStream): PersonalFingerprint? =
      if (input.readBoolean()) PersonalFingerprint(input.readLong(), QuickHash(input.readUTF())) else null

    private fun writeMark(out: DataOutputStream, mark: HeadMark) {
      out.writeLong(mark.version)
      out.writeUTF(mark.contentHash)
    }

    private fun readMark(input: DataInputStream): HeadMark = HeadMark(input.readLong(), input.readUTF())

    private const val TAG_FALSE = 0
    private const val TAG_TRUE = 1
    private const val TAG_NUMBER = 2
    private const val TAG_TEXT = 3
    private const val TAG_REMOVED = 4
  }
}
