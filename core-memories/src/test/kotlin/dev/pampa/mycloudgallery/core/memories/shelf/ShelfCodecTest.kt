package dev.pampa.mycloudgallery.core.memories.shelf

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import dev.pampa.mycloudgallery.core.memories.contract.ShelfStoreContract
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.util.Random
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il formato del file dello scaffale (D10; P6): tutto cio' che si scrive si rilegge uguale, byte uguali a ogni scrittura, e **qualunque** file
 * che non torna e' [ShelfRead.Corrupt] senza mai lanciare: troncato a ogni lunghezza, un bit cambiato in ogni posizione, byte cambiati con il CRC
 * rifatto (valori fuori intervallo, conti sbagliati), spazzatura.
 */
class ShelfCodecTest {

  private val rich = richShelf()
  private val bytes = ShelfCodec.encode(rich)

  /** Il file con il CRC rifatto (per cambiare il corpo e mettere alla prova i controlli di forma, non solo il CRC). */
  private fun withFixedCrc(data: ByteArray): ByteArray {
    val out = data.copyOf()
    val crc = CRC32().also { it.update(out, 0, out.size - 4) }.value
    for (k in 0 until 4) out[out.size - 4 + k] = (crc shr (24 - 8 * k)).toByte()
    return out
  }

  @Test
  fun ognTipoDiRicordoSiRilegge() {
    assertThat(ShelfCodec.decode(bytes)).isEqualTo(ShelfRead.Ready(rich))
    assertThat(ShelfCodec.decode(ShelfCodec.encode(ShelfFile.EMPTY))).isEqualTo(ShelfRead.Ready(ShelfFile.EMPTY))
    val kinds = (ShelfCodec.decode(bytes) as ShelfRead.Ready).file.memories.map { it.kind }.toSet()
    assertThat(kinds).containsExactlyElementsIn(MemoryKind.entries)
  }

  @Test
  fun ilFileHaIlFormatoDichiarato() {
    assertThat(bytes.copyOfRange(0, 4)).isEqualTo("MCGS".toByteArray())
    assertThat(((bytes[4].toInt() and 0xff) shl 8) or (bytes[5].toInt() and 0xff)).isEqualTo(ShelfFile.VERSION)
    val length = ((bytes[8].toInt() and 0xff) shl 24) or ((bytes[9].toInt() and 0xff) shl 16) or
      ((bytes[10].toInt() and 0xff) shl 8) or (bytes[11].toInt() and 0xff)
    assertThat(length).isEqualTo(bytes.size - 16)
  }

  /** Gli stessi dati, gli stessi byte: l'ordine delle mappe non conta. */
  @Test
  fun glistessiDatiDannoGliStessiByte() {
    assertThat(ShelfCodec.encode(rich)).isEqualTo(bytes)
    val shuffled = rich.copy(lastPublished = rich.lastPublished.entries.reversed().associate { it.key to it.value })
    assertThat(ShelfCodec.encode(shuffled)).isEqualTo(bytes)
  }

  /** 60 ricordi da 40 elementi con l'etichetta piu' lunga: sotto il tetto di lettura, e si rilegge. */
  @Test
  fun loScaffalePienoStaNelTetto() {
    val label = "x".repeat(ShelfCodec.MAX_LABEL_CHARS)
    val memories = List(60) { n ->
      val key = MemoryKey.OnThisDay(LocalDate.of(2020, 1, 1).plusDays(n.toLong()))
      val ids = List(40) { MediaId(UUID(n.toLong(), it.toLong()).toString()) }
      val title = MemoryTitle(1, DayRange.of(LocalDate.of(2020, 1, 1)), listOf(1, 2, 3), label, null, null)
      Memory(MemoryIds.of(key), key, title, ids, ids[0], 20261027)
    }
    val full = ShelfFile(memories, lastRunDay = 20261027)
    val encoded = ShelfCodec.encode(full)
    println("P6 misure: scaffale pieno (60 ricordi da 40 elementi, etichette da 120 caratteri) = ${encoded.size} byte")
    assertThat(encoded.size).isLessThan(ShelfCodec.MAX_BYTES / 2)
    assertThat(ShelfCodec.decode(encoded)).isEqualTo(ShelfRead.Ready(full))
  }

  /** Ogni troncamento (ogni lunghezza da 0 a n-1) e' un file rovinato, mai un'eccezione. */
  @Test
  fun ognTroncamentoERovinato() {
    for (length in bytes.indices) {
      assertWithMessage("lunghezza $length").that(ShelfCodec.decode(bytes.copyOf(length))).isEqualTo(ShelfRead.Corrupt)
    }
    assertThat(ShelfCodec.decode(bytes + byteArrayOf(0))).isEqualTo(ShelfRead.Corrupt)
  }

  /** Il CRC-32 vede ogni bit cambiato, in ogni posizione. */
  @Test
  fun ognBitCambiatoERovinato() {
    for (position in bytes.indices) for (bit in 0 until 8) {
      val copy = bytes.copyOf()
      copy[position] = (copy[position].toInt() xor (1 shl bit)).toByte()
      assertWithMessage("byte $position bit $bit").that(ShelfCodec.decode(copy)).isEqualTo(ShelfRead.Corrupt)
    }
  }

  /** Con il CRC rifatto il corpo e' ancora controllato: valori fuori intervallo, conti sbagliati, dati in piu', tipi sconosciuti. */
  @Test
  fun ilCorpoCambiatoConIlCrcRifattoNonCrolla() {
    val random = Random(20261030)
    var accepted = 0
    repeat(20_000) {
      val copy = bytes.copyOf()
      repeat(1 + random.nextInt(3)) { copy[12 + random.nextInt(copy.size - 16)] = random.nextInt(256).toByte() }
      when (val read = ShelfCodec.decode(withFixedCrc(copy))) {
        ShelfRead.Corrupt -> Unit
        ShelfRead.Missing -> error("il codec non risponde Missing")
        is ShelfRead.Ready -> {
          accepted++
          // Cio' che si accetta e' uno scaffale intero e coerente: si riscrive e si rilegge uguale.
          assertThat(ShelfCodec.decode(ShelfCodec.encode(read.file))).isEqualTo(read)
        }
      }
    }
    // Molti byte sono id di media: cambiarli da' un altro scaffale valido (e il CRC rifatto lo copre). Il resto e' rifiutato.
    assertWithMessage("qualcosa e' stato rifiutato").that(accepted).isLessThan(20_000)
  }

  /** Dove sta il conto dei ricordi nel corpo: dopo i due giorni e le pubblicazioni per tipo. */
  private fun memoryCountOffset(): Int =
    12 + 4 + 4 + 1 + rich.lastPublished.keys.sumOf { 1 + it.code.length + 4 }

  /** I conti sbagliati con il CRC rifatto: ricordi in meno (dati in piu'), ricordi in piu' (dati che mancano), oltre il tetto. */
  @Test
  fun iContiSbagliatiConIlCrcRifattoSonoRifiutati() {
    val at = memoryCountOffset()
    assertThat(bytes[at].toInt()).isEqualTo(rich.memories.size)
    for (wrong in listOf(0, 1, rich.memories.size - 1, rich.memories.size + 1, 60, 61, 255)) {
      val copy = bytes.copyOf().also { it[at] = wrong.toByte() }
      assertWithMessage("conto $wrong").that(ShelfCodec.decode(withFixedCrc(copy))).isEqualTo(ShelfRead.Corrupt)
    }
    val lastRunDayBad = bytes.copyOf().also { it[12] = 0x7f }
    assertThat(ShelfCodec.decode(withFixedCrc(lastRunDayBad))).isEqualTo(ShelfRead.Corrupt)
  }

  @Test
  fun laSpazzaturaERovinata() {
    val random = Random(7)
    repeat(2_000) {
      val junk = ByteArray(random.nextInt(300)).also { random.nextBytes(it) }
      assertThat(ShelfCodec.decode(junk)).isEqualTo(ShelfRead.Corrupt)
    }
    assertThat(ShelfCodec.decode(ByteArray(ShelfCodec.MAX_BYTES + 1))).isEqualTo(ShelfRead.Corrupt)
  }

  @Test
  fun unaVersioneDiversaERovinata() {
    val newer = bytes.copyOf().also { it[5] = (ShelfFile.VERSION + 1).toByte() }
    assertThat(ShelfCodec.decode(withFixedCrc(newer))).isEqualTo(ShelfRead.Corrupt)
    val reserved = bytes.copyOf().also { it[7] = 1 }
    assertThat(ShelfCodec.decode(withFixedCrc(reserved))).isEqualTo(ShelfRead.Corrupt)
    val wrongMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
    assertThat(ShelfCodec.decode(withFixedCrc(wrongMagic))).isEqualTo(ShelfRead.Corrupt)
  }

  /** Il codec scrive solo cio' che rilegge: un'etichetta troppo lunga o un giorno non valido si rifiutano **senza** dati nel messaggio. */
  @Test
  fun cioCheNonPuoStareNelFileSiRifiuta() {
    val longLabel = "Etichetta-segreta-" + "y".repeat(ShelfCodec.MAX_LABEL_BYTES)
    val sample = ShelfStoreContract.sampleMemory()
    val bad = sample.copy(title = sample.title.copy(tripLabel = longLabel))
    val failure = assertThrows(IllegalArgumentException::class.java) { ShelfCodec.encode(ShelfFile(listOf(bad))) }
    assertThat(failure.message.orEmpty()).doesNotContain("segreta")
    assertThrows(IllegalArgumentException::class.java) { ShelfCodec.encode(ShelfFile(listOf(sample), lastRunDay = 20261399)) }
    assertThrows(IllegalArgumentException::class.java) { ShelfCodec.encode(ShelfFile(listOf(sample.copy(publishedDay = 0)))) }
  }

  @Test
  fun capLabelNonSpezzaUnaCoppiaDiSurrogati() {
    val emoji = "😀"
    val label = "a".repeat(ShelfCodec.MAX_LABEL_CHARS - 1) + emoji
    val capped = ShelfCodec.capLabel(label)
    assertThat(capped.length).isAtMost(ShelfCodec.MAX_LABEL_CHARS)
    assertThat(capped.last()).isEqualTo('a')
    assertThat(ShelfCodec.capLabel("breve")).isEqualTo("breve")
    // Un'etichetta di soli caratteri a tre byte, tagliata a 120, sta nel limite in byte del file.
    assertThat(ShelfCodec.capLabel("€".repeat(500)).toByteArray().size).isAtMost(ShelfCodec.MAX_LABEL_BYTES)
  }

  /** Nel file non ci sono nomi: del titolo di una persona c'e' l'id, mai il nome; `toString` senza date. */
  @Test
  fun nelFileNonCiSonoNomiDiPersone() {
    val text = String(bytes, Charsets.ISO_8859_1)
    for (name in listOf("Anna", "Marco", "Nome")) assertThat(text).doesNotContain(name)
    assertThat(rich.toString()).doesNotContain("2024")
    assertThat(bytes.size).isLessThan(4_000)
  }
}
