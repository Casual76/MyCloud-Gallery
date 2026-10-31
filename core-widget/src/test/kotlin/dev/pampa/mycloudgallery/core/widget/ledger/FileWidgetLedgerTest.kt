package dev.pampa.mycloudgallery.core.widget.ledger

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCellRef
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.YearMonth
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il registro su file oltre la suite di contratto (D5, D9, D42; §4.3 "Stabilita'"): formato fissato, file troncato a ogni lunghezza e rovinato a
 * ogni byte = registro vuoto, un file con CRC giusto ma contenuto impossibile, il temporaneo che resta dopo la morte, l'azzeramento, le scritture
 * insieme, i tetti. La morte **vera** del processo (un processo figlio ucciso a meta' scrittura) e' in `FileWidgetLedgerKillTest`.
 */
class FileWidgetLedgerTest {

  @get:Rule
  val folder = TemporaryFolder()

  private fun id(n: Int) = MediaId(UUID(40L, n.toLong()).toString())

  private val memory = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 9)))

  private fun photos(slot: Int, epoch: Long, count: Int = 3) = WidgetPlan(
    WidgetSlot(slot), WidgetSize.LARGE, WidgetMode.Photos, List(count) { WidgetCell(it, id(slot * 10 + it), MediaKind.PHOTO, 20261031) }, epoch,
  )

  private fun memoryPlan(slot: Int, epoch: Long) = WidgetPlan(
    WidgetSlot(slot), WidgetSize.WIDE, WidgetMode.Memory(memory, WidgetTitle(1, listOf(3_100_000), null, null)),
    List(4) { WidgetCell(it, id(slot * 10 + it), MediaKind.VIDEO, 20250930) }, epoch,
  )

  private fun newDir() = folder.newFolder()

  private fun file(dir: File) = File(dir, FileLedgerStorage.FILE_NAME)

  /** Un file con un registro di tre widget, per le prove di rovina. */
  private fun sampleBytes(): ByteArray = LedgerCodec.encode(
    mapOf(
      WidgetSlot(1) to LedgerRow(4, listOf(WidgetCellRef(id(1), null), WidgetCellRef(id(2), null))),
      WidgetSlot(2) to LedgerRow(9, listOf(WidgetCellRef(id(3), memory), WidgetCellRef(id(4), null))),
      WidgetSlot(7) to LedgerRow(1, emptyList()),
    ),
  )

  @Test
  fun ilFormatoEFissato() {
    // Un widget (id 5), epoca 2, una cella senza ricordo: ogni byte e' quello del formato scritto nel KDoc.
    val rows = mapOf(WidgetSlot(5) to LedgerRow(2, listOf(WidgetCellRef(id(1), null))))
    val content = ByteArrayOutputStream().also {
      DataOutputStream(it).apply {
        writeShort(1)
        writeInt(5)
        writeLong(2)
        writeByte(1)
        writeLong(40L)
        writeLong(1L)
        writeByte(0)
      }
    }.toByteArray()
    assertThat(LedgerCodec.encode(rows)).isEqualTo(seal(content))
    val decoded = LedgerCodec.decode(seal(content))
    assertThat(decoded).isEqualTo(rows)
    assertThat(String(seal(content).copyOf(4), Charsets.US_ASCII)).isEqualTo("MCGW")
  }

  /** Mette l'intestazione e il CRC attorno a [content], come il codec. */
  private fun seal(content: ByteArray, version: Int = 1): ByteArray {
    val head = ByteArrayOutputStream().also {
      DataOutputStream(it).apply {
        write("MCGW".toByteArray(Charsets.US_ASCII))
        writeShort(version)
        writeInt(content.size)
        write(content)
      }
    }.toByteArray()
    val crc = CRC32().apply { update(head) }.value.toInt()
    return head + ByteArrayOutputStream().also { DataOutputStream(it).writeInt(crc) }.toByteArray()
  }

  private fun content(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

  @Test
  fun ilCodecFaAndataERitorno() {
    val bytes = sampleBytes()
    val back = LedgerCodec.decode(bytes)!!
    assertThat(back.keys).containsExactly(WidgetSlot(1), WidgetSlot(2), WidgetSlot(7))
    assertThat(back[WidgetSlot(2)]).isEqualTo(LedgerRow(9, listOf(WidgetCellRef(id(3), memory), WidgetCellRef(id(4), null))))
    assertThat(LedgerCodec.encode(back)).isEqualTo(bytes)
  }

  @Test
  fun unFileTroncatoAOgniLunghezzaEUnRegistroVuoto() = runTest {
    val dir = newDir()
    val whole = sampleBytes()
    for (length in 0 until whole.size) {
      file(dir).writeBytes(whole.copyOf(length))
      val ledger = FileWidgetLedger(dir)
      assertWithMessage("troncato a $length byte").that(ledger.slots()).isEmpty()
    }
    file(dir).writeBytes(whole)
    assertThat(FileWidgetLedger(dir).slots()).hasSize(3)
  }

  @Test
  fun unByteCambiatoOvunqueEUnRegistroVuoto() = runTest {
    val dir = newDir()
    val whole = sampleBytes()
    for (at in whole.indices) {
      for (mask in intArrayOf(0x01, 0x80, 0xFF)) {
        val damaged = whole.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }
        file(dir).writeBytes(damaged)
        assertWithMessage("byte $at, maschera $mask").that(FileWidgetLedger(dir).slots()).isEmpty()
      }
    }
  }

  @Test
  fun byteInPiuOInMenoNonSonoUnRegistro() = runTest {
    val dir = newDir()
    val whole = sampleBytes()
    file(dir).writeBytes(whole + byteArrayOf(0))
    assertThat(FileWidgetLedger(dir).slots()).isEmpty()
    file(dir).writeBytes(byteArrayOf(0) + whole)
    assertThat(FileWidgetLedger(dir).slots()).isEmpty()
  }

  @Test
  fun conIlCrcGiustoMaIlContenutoImpossibileEUnRegistroVuoto() = runTest {
    val dir = newDir()
    val validCell = content { writeLong(40L); writeLong(1L); writeByte(0) }
    val cases = mapOf(
      "versione sconosciuta" to seal(content { writeShort(0) }, version = 2),
      "versione zero" to seal(content { writeShort(0) }, version = 0),
      "id di widget zero" to seal(content { writeShort(1); writeInt(0); writeLong(1); writeByte(0) }),
      "id di widget negativo" to seal(content { writeShort(1); writeInt(-4); writeLong(1); writeByte(0) }),
      "epoca negativa" to seal(content { writeShort(1); writeInt(3); writeLong(-1); writeByte(0) }),
      "otto celle" to seal(content { writeShort(1); writeInt(3); writeLong(1); writeByte(8) }),
      "flag del ricordo impossibile" to seal(content { writeShort(1); writeInt(3); writeLong(1); writeByte(1); writeLong(40L); writeLong(1L); writeByte(2) }),
      "ricordo non canonico" to seal(
        content {
          writeShort(1); writeInt(3); writeLong(1); writeByte(1); writeLong(40L); writeLong(1L); writeByte(1)
          write("ZZZZZZZZZZZZZZZZZZZZZZZZZZ".toByteArray(Charsets.US_ASCII))
        },
      ),
      "ricordo troppo corto" to seal(content { writeShort(1); writeInt(3); writeLong(1); writeByte(1); writeLong(40L); writeLong(1L); writeByte(1); writeBytes("abc") }),
      "widget ripetuto" to seal(
        content {
          writeShort(2)
          repeat(2) { writeInt(3); writeLong(1); writeByte(1); write(validCell) }
        },
      ),
      "piu' widget del dichiarato" to seal(content { writeShort(1); writeInt(3); writeLong(1); writeByte(0); writeInt(9) }),
      "meno widget del dichiarato" to seal(content { writeShort(2); writeInt(3); writeLong(1); writeByte(0) }),
      "troppi widget" to seal(content { writeShort(300) }),
      "vuoto senza il numero dei widget" to seal(ByteArray(0)),
    )
    for ((what, bytes) in cases) {
      file(dir).writeBytes(bytes)
      assertWithMessage(what).that(LedgerCodec.decode(bytes)).isNull()
      assertWithMessage(what).that(FileWidgetLedger(dir).slots()).isEmpty()
    }
    // E il caso "buono" fatto con gli stessi mattoni lo legge.
    val good = seal(content { writeShort(1); writeInt(3); writeLong(1); writeByte(1); write(validCell) })
    assertThat(LedgerCodec.decode(good)).isNotNull()
  }

  @Test
  fun unFileRovinatoNonImpedisceDiScrivereIlPianoNuovo() = runTest {
    val dir = newDir()
    file(dir).writeBytes(ByteArray(200) { 0x7F })
    val ledger = FileWidgetLedger(dir)
    assertThat(ledger.slots()).isEmpty()
    ledger.record(photos(1, 1))
    assertThat(FileWidgetLedger(dir).cell(WidgetSlot(1), 1)?.media).isEqualTo(id(11))
  }

  @Test
  fun unFileEnormeOIllegibileNonEUnCrollo() = runTest {
    val dir = newDir()
    file(dir).writeBytes(ByteArray(LedgerCodec.MAX_FILE_BYTES.toInt() + 1))
    assertThat(FileWidgetLedger(dir).slots()).isEmpty()
    val unreadable = object : WidgetLedgerStorage {
      override fun read(): ByteArray? = throw IOException("disco")

      override fun write(bytes: ByteArray) = Unit

      override fun delete(): Boolean = true
    }
    assertThat(FileWidgetLedger(unreadable).slots()).isEmpty()
  }

  @Test
  fun ilTemporaneoRimastoDopoLaMorteNonSiLeggeESiRimpiazza() = runTest {
    val dir = newDir()
    val ledger = FileWidgetLedger(dir)
    ledger.record(photos(1, 1))
    val temp = File(file(dir).path + ".tmp")
    temp.writeBytes(ByteArray(50) { 1 })
    val reopened = FileWidgetLedger(dir)
    assertThat(reopened.cell(WidgetSlot(1), 0)?.media).isEqualTo(id(10))
    reopened.record(photos(1, 2))
    assertWithMessage("il temporaneo e' stato rimpiazzato dalla rinomina").that(temp.exists()).isFalse()
    assertThat(FileWidgetLedger(dir).epochOf(WidgetSlot(1))).isEqualTo(2)
  }

  @Test
  fun unaScritturaCheMuoreLasciaIlFileDiPrimaIntero() = runTest {
    val dir = newDir()
    FileWidgetLedger(dir).record(photos(1, 1))
    val before = file(dir).readBytes()
    // Muore a meta' del corpo, dentro la scrittura atomica: il file vero non cambia e il temporaneo non resta.
    val failure = runCatching {
      StateFiles.write(file(dir)) {
        it.write(ByteArray(10))
        throw IOException("morte")
      }
    }
    assertThat(failure.exceptionOrNull()).isInstanceOf(IOException::class.java)
    assertThat(file(dir).readBytes()).isEqualTo(before)
    assertThat(File(file(dir).path + ".tmp").exists()).isFalse()
  }

  @Test
  fun unaScritturaFallitaNonCambiaLoStatoInMemoria() = runTest {
    val dir = newDir()
    val storage = DyingStorage(FileLedgerStorage(file(dir)), file(dir))
    val ledger = FileWidgetLedger(storage)
    ledger.record(photos(1, 1))
    storage.dieOnNextWrite = true
    assertThat(runCatching { ledger.record(photos(1, 2)) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    // Anche nello stesso processo: il registro dice cio' che e' su disco, non il piano che non e' stato scritto.
    assertThat(ledger.epochOf(WidgetSlot(1))).isEqualTo(1)
    assertThat(ledger.cell(WidgetSlot(1), 0)?.media).isEqualTo(id(10))
    ledger.record(photos(1, 3))
    assertThat(FileWidgetLedger(dir).epochOf(WidgetSlot(1))).isEqualTo(3)
  }

  @Test
  fun ilRicordoELEpocaSopravvivonoAllaRiapertura() = runTest {
    val dir = newDir()
    FileWidgetLedger(dir).apply {
      record(memoryPlan(2, 8))
      record(photos(1, 3))
    }
    val reopened = FileWidgetLedger(dir)
    assertThat(reopened.cell(WidgetSlot(2), 0)).isEqualTo(WidgetCellRef(id(20), memory))
    assertThat(reopened.cell(WidgetSlot(2), 3)).isEqualTo(WidgetCellRef(id(23), null))
    assertThat(reopened.showingMemory(memory)).containsExactly(WidgetSlot(2))
    assertThat(reopened.showing(setOf(id(11)))).containsExactly(WidgetSlot(1))
    assertThat(reopened.epochOf(WidgetSlot(2))).isEqualTo(8)
    assertThat(reopened.epochOf(WidgetSlot(99))).isEqualTo(0)
    // Un piano piu' vecchio non rimpiazza (nemmeno dopo la riapertura).
    reopened.record(photos(2, 5))
    assertThat(reopened.cell(WidgetSlot(2), 0)?.memory).isEqualTo(memory)
  }

  @Test
  fun unMessaggioSostituisceLeFotoESvuotaLoSvuotamento() = runTest {
    val dir = newDir()
    val ledger = FileWidgetLedger(dir)
    ledger.record(photos(1, 1))
    assertThat(ledger.showing(setOf(id(10)))).containsExactly(WidgetSlot(1))
    ledger.record(WidgetPlan(WidgetSlot(1), WidgetSize.LARGE, WidgetMode.Message(WidgetEmpty.NOT_CONFIGURED), emptyList(), 2))
    assertThat(FileWidgetLedger(dir).showing(setOf(id(10)))).isEmpty()
    assertThat(FileWidgetLedger(dir).slots()).containsExactly(WidgetSlot(1))
  }

  @Test
  fun azzerareTogliIlFileEIlTemporaneo() = runTest {
    val dir = newDir()
    val ledger = FileWidgetLedger(dir)
    ledger.record(photos(1, 1))
    File(file(dir).path + ".tmp").writeBytes(byteArrayOf(1, 2, 3))
    ledger.clear()
    assertThat(file(dir).exists()).isFalse()
    assertThat(File(file(dir).path + ".tmp").exists()).isFalse()
    assertThat(ledger.slots()).isEmpty()
    assertThat(FileWidgetLedger(dir).slots()).isEmpty()
    // Un registro che non si riesce a cancellare si riscrive vuoto: i dati non restano.
    val stubborn = object : WidgetLedgerStorage {
      var bytes: ByteArray? = sampleBytes()

      override fun read(): ByteArray? = bytes

      override fun write(bytes: ByteArray) {
        this.bytes = bytes
      }

      override fun delete(): Boolean = false
    }
    val other = FileWidgetLedger(stubborn)
    assertThat(other.slots()).hasSize(3)
    other.clear()
    assertThat(FileWidgetLedger(stubborn).slots()).isEmpty()
  }

  @Test
  fun dimenticareUnWidgetNonTocchiGliAltri() = runTest {
    val dir = newDir()
    val ledger = FileWidgetLedger(dir)
    ledger.record(photos(1, 1))
    ledger.record(photos(2, 1))
    ledger.forget(WidgetSlot(1))
    ledger.forget(WidgetSlot(55))
    assertThat(FileWidgetLedger(dir).slots()).containsExactly(WidgetSlot(2))
  }

  @Test
  fun scrittureInsiemeNonSiPerdonoNeRovinanoIlFile() = runTest {
    val dir = newDir()
    val ledger = FileWidgetLedger(dir)
    (1..60).map { slot -> async { ledger.record(photos(slot, 1, count = 1 + slot % 7)) } }.awaitAll()
    val reopened = FileWidgetLedger(dir)
    assertThat(reopened.slots()).hasSize(60)
    for (slot in 1..60) assertThat(reopened.cell(WidgetSlot(slot), slot % 7)?.media).isEqualTo(id(slot * 10 + slot % 7))
  }

  @Test
  fun ilTettoDeiWidgetEUnErroreNonUnFileInleggibile() = runTest {
    // In memoria (256 `fsync` su un disco vero non aggiungono niente a questa prova).
    val ram = object : WidgetLedgerStorage {
      var bytes: ByteArray? = null

      override fun read(): ByteArray? = bytes

      override fun write(bytes: ByteArray) {
        this.bytes = bytes
      }

      override fun delete(): Boolean = true
    }
    val ledger = FileWidgetLedger(ram)
    for (slot in 1..LedgerCodec.MAX_SLOTS) ledger.record(photos(slot, 1, count = 1))
    assertThat(runCatching { ledger.record(photos(LedgerCodec.MAX_SLOTS + 1, 1, count = 1)) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    assertThat(FileWidgetLedger(ram).slots()).hasSize(LedgerCodec.MAX_SLOTS)
    // Il file pieno sta nel tetto di lettura (sette celle con ricordo, per ogni widget).
    val full = LedgerCodec.encode(
      (1..LedgerCodec.MAX_SLOTS).associate { WidgetSlot(it) to LedgerRow(1, List(7) { c -> WidgetCellRef(id(it * 10 + c), memory) }) },
    )
    assertThat(full.size.toLong()).isLessThan(LedgerCodec.MAX_FILE_BYTES)
    assertThat(LedgerCodec.decode(full)).hasSize(LedgerCodec.MAX_SLOTS)
  }
}
