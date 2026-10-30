package dev.pampa.mycloudgallery.core.memories.shelf

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.YearMonth
import java.util.UUID
import java.util.zip.CRC32

/**
 * Il formato del file dello scaffale (`noBackupFilesDir/memories/shelf.bin`; docs/fase9-progetto.md D9, D10, §2.3; modello-dati.md §16).
 * Un file solo, letto in un colpo (Raccolte -> Ricordi si disegna subito dall'ultimo scaffale) e scritto per intero in modo atomico da
 * [FileShelfStore] (temporaneo, `fsync`, rinomina).
 *
 *     0   "MCGS"                              4 byte
 *     4   versione                            u16    ([ShelfFile.VERSION])
 *     6   riservato                           u16    (0)
 *     8   lunghezza del corpo                 u32
 *     12  corpo                               lunghezza byte
 *     ..  CRC-32 di tutto cio' che precede    u32
 *
 * Il corpo: giorno dell'ultimo giro, ultima riscoperta (0 = mai), ultima pubblicazione per tipo, poi i ricordi. Un ricordo porta la chiave (da
 * cui si ricalcola l'id: un id non si salva mai due volte, quindi non puo' contraddire la chiave), il titolo come dati, gli elementi (16 byte
 * ciascuno), la copertina come posizione fra gli elementi, il giorno di pubblicazione e l'origine. **Mai un nome**: del titolo si salva l'id
 * della persona e non il suo nome; l'etichetta di un viaggio e' al piu' [MAX_LABEL_CHARS] caratteri.
 *
 * Gli interi sono big-endian. [decode] non lancia mai: un file che non torna (troncato, byte cambiati, versione diversa, valori fuori
 * intervallo, dati in piu') e' [ShelfRead.Corrupt], e il servizio rigenera lo scaffale (`MEMORY_SHELF_RESET`). [encode] scrive solo cio' che
 * [decode] rilegge uguale, e lancia `IllegalArgumentException` (senza dati nel messaggio) per un contenuto che non puo' stare nel file: i tetti
 * sono quelli del tipo ([Memory], [ShelfFile]) piu' l'etichetta.
 */
object ShelfCodec {

  const val MAX_LABEL_CHARS: Int = 120
  const val MAX_LABEL_BYTES: Int = 512

  /** Il file piu' grande che si legge (60 ricordi da 40 elementi con un'etichetta lunga stanno sotto i 150 KB): oltre, e' rovinato. */
  const val MAX_BYTES: Int = 256 * 1024

  private val MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte())
  private const val HEADER = 12
  private const val CRC = 4
  private const val MAX_KIND_CODE = 16
  private val YEARS = 1900..2999

  /** L'etichetta di un viaggio come sta nello scaffale: al piu' [MAX_LABEL_CHARS] caratteri, senza spezzare una coppia di surrogati. */
  fun capLabel(label: String): String {
    if (label.length <= MAX_LABEL_CHARS) return label
    val cut = label.substring(0, MAX_LABEL_CHARS)
    return if (Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
  }

  // --- Scrittura --------------------------------------------------------------------------------------------------------------------

  fun encode(file: ShelfFile): ByteArray {
    val body = ByteArrayOutputStream()
    val out = DataOutputStream(body)
    writeDay(out, file.lastRunDay, allowZero = true)
    require(file.lastRediscovery != 0) { "Giorno non valido." }
    writeDay(out, file.lastRediscovery ?: 0, allowZero = true)
    val published = file.lastPublished.entries.sortedBy { it.key.ordinal }
    out.writeByte(published.size)
    for ((kind, day) in published) {
      writeKind(out, kind)
      writeDay(out, day, allowZero = false)
    }
    out.writeByte(file.memories.size)
    for (memory in file.memories) writeMemory(out, memory)
    val bytes = body.toByteArray()
    val whole = ByteArrayOutputStream(HEADER + bytes.size + CRC)
    val head = DataOutputStream(whole)
    head.write(MAGIC)
    head.writeShort(ShelfFile.VERSION)
    head.writeShort(0)
    head.writeInt(bytes.size)
    head.write(bytes)
    head.writeInt(crcOf(whole.toByteArray(), whole.size()))
    return whole.toByteArray()
  }

  private fun writeMemory(out: DataOutputStream, memory: Memory) {
    writeKey(out, memory.key)
    val title = memory.title
    val yearsAgo = title.yearsAgo
    require(yearsAgo == null || yearsAgo in 1..Short.MAX_VALUE) { "Anni fa fuori intervallo." }
    out.writeShort(yearsAgo ?: 0)
    writeDay(out, MemoryDays.dayOf(title.days.from), allowZero = false)
    writeDay(out, MemoryDays.dayOf(title.days.to), allowZero = false)
    out.writeByte(title.placeIds.size)
    for (place in title.placeIds) out.writeInt(place)
    val label = title.tripLabel
    out.writeByte(if (label == null) 0 else 1)
    if (label != null) {
      val raw = label.toByteArray(Charsets.UTF_8)
      require(raw.size <= MAX_LABEL_BYTES) { "Etichetta troppo lunga." }
      out.writeShort(raw.size)
      out.write(raw)
    }
    val person = title.person
    out.writeByte(if (person == null) 0 else 1)
    if (person != null) out.write(person.bytes())
    val month = title.month
    out.writeByte(if (month == null) 0 else 1)
    if (month != null) out.writeInt(monthCode(month))
    out.writeByte(memory.members.size)
    for (id in memory.members) writeUuid(out, UUID.fromString(id.value))
    out.writeByte(memory.members.indexOf(memory.cover))
    writeDay(out, memory.publishedDay, allowZero = false)
    when (val origin = memory.origin) {
      null -> out.writeByte(0)
      is MemoryOrigin.TripAlbum -> {
        out.writeByte(1)
        writeUuid(out, UUID.fromString(origin.album.value))
      }
    }
  }

  private fun writeKey(out: DataOutputStream, key: MemoryKey) {
    writeKind(out, key.kind)
    when (key) {
      is MemoryKey.OnThisDay -> writeDay(out, MemoryDays.dayOf(key.day), allowZero = false)
      is MemoryKey.Trip -> {
        writeDay(out, MemoryDays.dayOf(key.days.from), allowZero = false)
        writeDay(out, MemoryDays.dayOf(key.days.to), allowZero = false)
      }
      is MemoryKey.Event -> {
        writeDay(out, MemoryDays.dayOf(key.day), allowZero = false)
        out.writeByte(key.startQuarter)
      }
      is MemoryKey.Person -> {
        out.write(key.person.bytes())
        val window = key.window
        out.writeInt(
          when (window) {
            is PersonWindow.Year -> window.year.also { require(it in YEARS) { "Anno fuori intervallo." } }
            PersonWindow.Recent -> 0
          },
        )
      }
      is MemoryKey.Month -> out.writeInt(monthCode(key.month))
    }
  }

  private fun monthCode(month: YearMonth): Int {
    require(month.year in YEARS) { "Anno fuori intervallo." }
    return month.year * 100 + month.monthValue
  }

  private fun writeKind(out: DataOutputStream, kind: MemoryKind) {
    val raw = kind.code.toByteArray(Charsets.US_ASCII)
    out.writeByte(raw.size)
    out.write(raw)
  }

  private fun writeUuid(out: DataOutputStream, uuid: UUID) {
    out.writeLong(uuid.mostSignificantBits)
    out.writeLong(uuid.leastSignificantBits)
  }

  private fun writeDay(out: DataOutputStream, day: Int, allowZero: Boolean) {
    require((allowZero && day == 0) || MemoryDays.dateOf(day) != null) { "Giorno non valido." }
    out.writeInt(day)
  }

  private fun crcOf(bytes: ByteArray, length: Int): Int = CRC32().also { it.update(bytes, 0, length) }.value.toInt()

  // --- Lettura ----------------------------------------------------------------------------------------------------------------------

  /** Lo scaffale scritto in [bytes], o [ShelfRead.Corrupt] per qualunque cosa non torni. Non lancia mai. */
  fun decode(bytes: ByteArray): ShelfRead {
    if (bytes.size < HEADER + CRC || bytes.size > MAX_BYTES) return ShelfRead.Corrupt
    return try {
      ShelfRead.Ready(parse(bytes))
    } catch (_: RuntimeException) {
      // Un file che non si legge e' un file rovinato, qualunque sia il modo (valori fuori intervallo, tipi che rifiutano un dato): mai un crollo.
      ShelfRead.Corrupt
    }
  }

  private fun parse(bytes: ByteArray): ShelfFile {
    val head = Reader(bytes, 0, bytes.size)
    for (b in MAGIC) check(head.byte() == b.toInt() and 0xff)
    check(head.short() == ShelfFile.VERSION)
    check(head.short() == 0)
    val length = head.int()
    check(length >= 0 && length == bytes.size - HEADER - CRC)
    val stored = Reader(bytes, HEADER + length, bytes.size).int()
    check(stored == crcOf(bytes, HEADER + length))
    val r = Reader(bytes, HEADER, HEADER + length)
    val lastRunDay = r.day(allowZero = true)
    val lastRediscovery = r.day(allowZero = true).takeIf { it != 0 }
    val published = LinkedHashMap<MemoryKind, Int>()
    repeat(r.byte()) { check(published.put(r.kind(), r.day(allowZero = false)) == null) }
    val memories = ArrayList<Memory>()
    repeat(r.byte()) { memories += readMemory(r) }
    check(r.atEnd())
    return ShelfFile(memories, published, lastRediscovery, lastRunDay)
  }

  private fun readMemory(r: Reader): Memory {
    val key = readKey(r)
    val yearsAgo = r.short().takeIf { it != 0 }?.also { check(it <= Short.MAX_VALUE) }
    val from = r.date()
    val to = r.date()
    val places = List(r.byte()) { r.int() }
    val label = if (r.flag()) r.string() else null
    val person = if (r.flag()) PersonId.fromBytes(r.bytes(UUID_LENGTH)) else null
    val month = if (r.flag()) r.month() else null
    val members = List(r.byte()) { MediaId(r.uuid()) }
    val coverIndex = r.byte()
    check(coverIndex < members.size)
    val publishedDay = r.day(allowZero = false)
    val origin = when (r.byte()) {
      0 -> null
      1 -> MemoryOrigin.TripAlbum(AlbumId(r.uuid()))
      else -> error("origine sconosciuta")
    }
    val title = MemoryTitle(yearsAgo, DayRange(from, to), places, label, person, month)
    return Memory(MemoryIds.of(key), key, title, members, members[coverIndex], publishedDay, origin)
  }

  private fun readKey(r: Reader): MemoryKey = when (r.kind()) {
    MemoryKind.ON_THIS_DAY -> MemoryKey.OnThisDay(r.date())
    MemoryKind.TRIP -> MemoryKey.Trip(DayRange(r.date(), r.date()))
    MemoryKind.EVENT -> MemoryKey.Event(r.date(), r.byte())
    MemoryKind.PERSON -> {
      val person = PersonId.fromBytes(r.bytes(UUID_LENGTH))
      val window = r.int()
      MemoryKey.Person(person, if (window == 0) PersonWindow.Recent else PersonWindow.Year(window.also { check(it in YEARS) }))
    }
    MemoryKind.MONTH -> MemoryKey.Month(r.month())
  }

  private const val UUID_LENGTH = 16

  /** Un lettore con i limiti: ogni lettura oltre la fine, o ogni valore che non e' valido, e' un'eccezione (presa da [decode]). */
  private class Reader(private val data: ByteArray, private var pos: Int, private val end: Int) {

    fun atEnd(): Boolean = pos == end

    fun byte(): Int {
      check(pos < end)
      return data[pos++].toInt() and 0xff
    }

    fun short(): Int = (byte() shl 8) or byte()

    fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()

    fun flag(): Boolean = when (byte()) {
      0 -> false
      1 -> true
      else -> error("flag")
    }

    fun bytes(count: Int): ByteArray {
      check(count >= 0 && end - pos >= count)
      return data.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xffffffffL)

    fun uuid(): String = UUID(long(), long()).toString()

    fun string(): String {
      val length = short()
      check(length <= MAX_LABEL_BYTES)
      // UTF-8 rigoroso: un byte che non e' UTF-8 non diventa un carattere di sostituzione (che riscritto sarebbe piu' lungo), rovina il file.
      return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes(length))).toString()
      } catch (_: CharacterCodingException) {
        error("etichetta non valida")
      }
    }

    fun kind(): MemoryKind {
      val length = byte()
      check(length in 1..MAX_KIND_CODE)
      return checkNotNull(MemoryKind.ofCode(String(bytes(length), Charsets.US_ASCII)))
    }

    fun day(allowZero: Boolean): Int {
      val day = int()
      check((allowZero && day == 0) || MemoryDays.dateOf(day) != null)
      return day
    }

    fun date() = checkNotNull(MemoryDays.dateOf(int()))

    fun month(): YearMonth {
      val code = int()
      check(code / 100 in YEARS)
      return YearMonth.of(code / 100, code % 100)
    }
  }
}
