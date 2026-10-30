package dev.pampa.mycloudgallery.core.memories.notify

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.memories.notify.NotifyFixtures.dayInt
import dev.pampa.mycloudgallery.core.memories.testing.ProcessDied
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il registro locale del giorno su file (docs/fase9-progetto.md D15, E-NT5, §4.3 "Stabilita'": scrittura atomica con la prova della morte del
 * processo a ogni salvataggio): formato, un file rovinato che non passa mai per "vuoto", i quattordici giorni, la morte a ogni scrittura, i
 * lavori in contemporanea.
 */
class FileNotifyLedgerStoreTest {

  private val dirs = ArrayList<File>()
  private val today = LocalDate.of(2026, 11, 3)

  @After
  fun togliTutto() {
    dirs.forEach { it.deleteRecursively() }
  }

  private fun dir(): File = Files.createTempDirectory("notified").toFile().also { dirs += it }

  private fun store(dir: File) = FileNotifyLedgerStore(dir, Dispatchers.Unconfined)

  private fun mark(daysAgo: Long, special: Boolean = false) = NotifyMark(dayInt(today.minusDays(daysAgo)), special)

  @Test
  fun senzaFileIlRegistroEVuoto() = runTest {
    assertThat(store(dir()).read()).isEqualTo(NotifyLedger())
  }

  /** Scrive, riapre con un oggetto nuovo (come dopo la morte del processo) e legge lo stesso registro. */
  @Test
  fun siScriveESiRilegge() = runTest {
    val dir = dir()
    store(dir).record(mark(2), today)
    store(dir).record(mark(0, special = true), today)
    val read = store(dir).read()
    assertThat(read.marks).containsExactly(mark(2), mark(0, special = true)).inOrder()
    assertThat(read.notifiedOn(today)).isTrue()
    assertThat(read.notifiedOn(today.minusDays(1))).isFalse()
    // Scrivere due volte lo stesso giorno non lo raddoppia (E-NT5).
    store(dir).record(mark(0, special = true), today)
    assertThat(store(dir).read().marks).hasSize(2)
  }

  /** Il registro tiene solo gli ultimi 14 giorni (basta alla frequenza di Q4): il file non cresce. */
  @Test
  fun tieneQuattordiciGiorni() = runTest {
    val dir = dir()
    val s = store(dir)
    for (n in 40L downTo 0L) s.record(mark(n), today.minusDays(n))
    val read = s.read()
    assertThat(read.marks).hasSize(NotifyRules.LEDGER_DAYS)
    assertThat(read.marks.first().day).isEqualTo(dayInt(today.minusDays(NotifyRules.LEDGER_DAYS - 1L)))
    assertThat(File(dir, FileNotifyLedgerStore.FILE_NAME).length()).isLessThan(NotifyLedgerCodec.MAX_BYTES.toLong())
  }

  /** Il file non dice mai altro che giorni: nessun id, titolo, luogo; e il registro non mostra i giorni nel `toString()`. */
  @Test
  fun ilFileEIlToStringNonDiconoNiente() = runTest {
    val dir = dir()
    store(dir).record(mark(0), today)
    val text = File(dir, FileNotifyLedgerStore.FILE_NAME).readText(Charsets.US_ASCII)
    assertThat(text).startsWith("MCGN1\n")
    assertThat(text.lines().filter { it.isNotEmpty() }).hasSize(3)
    assertThat(store(dir).read().toString()).doesNotContain("2026")
  }

  /** Un file rovinato (ogni troncamento, ogni bit) **non** vale "vuoto": lancia, cosi' non parte una seconda notifica nello stesso giorno. */
  @Test
  fun unFileRovinatoNonPassaMaiPerVuoto() = runTest {
    val good = NotifyLedgerCodec.encode(NotifyLedger(listOf(mark(3), mark(1, special = true), mark(0))))
    assertThat(NotifyLedgerCodec.decode(good)).isNotNull()
    for (n in 0 until good.size) assertWithMessage("troncato a $n byte").that(NotifyLedgerCodec.decode(good.copyOf(n))).isNull()
    for (byte in good.indices) {
      for (bit in 0 until 8) {
        val flipped = good.copyOf().also { it[byte] = (it[byte].toInt() xor (1 shl bit)).toByte() }
        assertWithMessage("byte $byte, bit $bit").that(NotifyLedgerCodec.decode(flipped)).isNull()
      }
    }
    val dir = dir()
    File(dir, FileNotifyLedgerStore.FILE_NAME).writeBytes(good.copyOf(good.size - 3))
    assertThrows(CorruptLedgerException::class.java) { runBlocking { store(dir).read() } }
  }

  /** Righe che non tornano (giorni doppi, date che non esistono, valori strani, troppo grande) sono rovinate anche con il CRC giusto. */
  @Test
  fun leRigheStraneSonoRovinateAncheConIlCrcGiusto() {
    fun withCrc(body: String): ByteArray {
      val crc = java.util.zip.CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value
      return (body + "crc " + String.format("%08x", crc) + "\n").toByteArray(Charsets.US_ASCII)
    }
    assertThat(NotifyLedgerCodec.decode(withCrc("MCGN1\n20261103 1\n"))).isNotNull()
    assertThat(NotifyLedgerCodec.decode(withCrc("MCGN1\n20261103 1\n20261103 0\n"))).isNull()
    assertThat(NotifyLedgerCodec.decode(withCrc("MCGN1\n20261303 1\n"))).isNull()
    assertThat(NotifyLedgerCodec.decode(withCrc("MCGN1\n20261103 2\n"))).isNull()
    assertThat(NotifyLedgerCodec.decode(withCrc("MCGN1\n2026110 1\n"))).isNull()
    assertThat(NotifyLedgerCodec.decode(withCrc("MCGN2\n20261103 1\n"))).isNull()
    assertThat(NotifyLedgerCodec.decode(ByteArray(NotifyLedgerCodec.MAX_BYTES + 1) { 65 })).isNull()
    assertThat(NotifyLedgerCodec.decode(byteArrayOf(-1, 77))).isNull()
    // Piu' di 14 giorni: non e' il nostro file.
    val many = (1..15).joinToString("") { "202611%02d 0\n".format(it) }
    assertThat(NotifyLedgerCodec.decode(withCrc("MCGN1\n$many"))).isNull()
  }

  /** Scrivere sopra un file rovinato riparte da vuoto; `clear` lo toglie; il registro dopo `clear` e' vuoto. */
  @Test
  fun siRimetteAPostoEIlClearTogliTutto() = runTest {
    val dir = dir()
    val file = File(dir, FileNotifyLedgerStore.FILE_NAME)
    file.writeText("spazzatura")
    store(dir).record(mark(0), today)
    assertThat(store(dir).read().marks).containsExactly(mark(0))
    store(dir).clear()
    assertThat(file.exists()).isFalse()
    assertThat(store(dir).read()).isEqualTo(NotifyLedger())
    // Togliere un registro che non c'e' non e' un errore.
    store(dir).clear()
  }

  /**
   * La morte del processo a **ogni** salvataggio (§4.3): in ognuno dei punti di una storia di 30 giorni il disco muore prima o dopo la scrittura;
   * dopo la riapertura il registro e' **intero**: quello di prima o quello nuovo, mai un'altra cosa, mai rovinato, e ripetendo il passo si arriva
   * allo stesso registro finale.
   */
  @Test
  fun laMorteDelProcessoAOgniSalvataggio() = runTest {
    val story = (0 until 30).map { today.plusDays(it.toLong()) }
    val expected = run {
      var ledger = NotifyLedger()
      story.forEach { day -> ledger = ledger.with(NotifyMark(dayInt(day), special = day.dayOfMonth % 5 == 0), day) }
      ledger
    }
    for (killAt in story.indices) {
      for (before in listOf(true, false)) {
        val dir = dir()
        val dying = DyingIo(killAtWrite = killAt, beforeWrite = before)
        val victim = FileNotifyLedgerStore(dir, dying, Dispatchers.Unconfined)
        var lastDone = -1
        try {
          story.forEachIndexed { n, day ->
            victim.record(NotifyMark(dayInt(day), special = day.dayOfMonth % 5 == 0), day)
            lastDone = n
          }
          throw AssertionError("la morte simulata non e' arrivata")
        } catch (_: ProcessDied) {
          // Il processo e' morto qui.
        }
        // Riavvio: oggetto nuovo, disco vero.
        val reopened = store(dir)
        val after = reopened.read()
        val sofar = story.take(lastDone + 1)
        val withKilled = story.take(killAt + 1)
        val candidates = listOf(sofar, withKilled).map { days ->
          var ledger = NotifyLedger()
          days.forEach { day -> ledger = ledger.with(NotifyMark(dayInt(day), special = day.dayOfMonth % 5 == 0), day) }
          ledger
        }
        assertWithMessage("morte alla scrittura $killAt (prima=$before)").that(after).isIn(candidates)
        // Si riprende dal giorno in cui si era: lo stesso registro finale.
        story.drop(lastDone + 1).forEach { day -> reopened.record(NotifyMark(dayInt(day), special = day.dayOfMonth % 5 == 0), day) }
        assertThat(reopened.read()).isEqualTo(expected)
      }
    }
  }

  /** Un temporaneo lasciato a meta' (la morte durante la scrittura vera) non si legge mai e si sovrascrive alla scrittura dopo. */
  @Test
  fun unTemporaneoARmetaNonSiLegge() = runTest {
    val dir = dir()
    store(dir).record(mark(1), today)
    File(dir, "${FileNotifyLedgerStore.FILE_NAME}.tmp").writeText("MCGN1\n2026110")
    assertThat(store(dir).read().marks).containsExactly(mark(1))
    store(dir).record(mark(0), today)
    assertThat(store(dir).read().marks).containsExactly(mark(1), mark(0)).inOrder()
  }

  /** Tante scritture insieme (due lavori, la revoca): nessuna si perde. */
  @Test
  fun scrittureInContemporaneaNonSiPerdono() = runBlocking<Unit> {
    val dir = dir()
    val s = FileNotifyLedgerStore(dir, Dispatchers.IO)
    val days = (0 until 12).map { today.minusDays(it.toLong()) }
    days.map { day -> async(Dispatchers.Default) { s.record(NotifyMark(dayInt(day), false), today) } }.awaitAll()
    assertThat(s.read().marks.map { it.day }).containsExactlyElementsIn(days.map { dayInt(it) })
  }

  /** Il file segue il modello in memoria passo per passo: giorni che avanzano a caso (anche piu' notifiche nello stesso giorno), riletto da un oggetto nuovo. */
  @Test
  fun ilFileSeguiIlModello() = runTest {
    val random = Random(3)
    val dir = dir()
    var model = NotifyLedger()
    var offset = 0L
    repeat(200) {
      offset += random.nextInt(4)
      val day = today.plusDays(offset)
      val m = NotifyMark(dayInt(day), random.nextBoolean())
      store(dir).record(m, day)
      model = model.with(m, day)
      assertThat(store(dir).read()).isEqualTo(model)
    }
  }

  /** Un disco che non scrive: l'errore esce (la corsa non pubblica) e il file di prima resta. */
  @Test
  fun unErroreDelDiscoEsceEIlFileDiPrimaResta() = runTest {
    val dir = dir()
    store(dir).record(mark(1), today)
    val failing = FileNotifyLedgerStore(dir, DyingIo(killAtWrite = 0, beforeWrite = true, error = IOException("disco pieno")), Dispatchers.Unconfined)
    assertThrows(IOException::class.java) { runBlocking { failing.record(mark(0), today) } }
    assertThat(store(dir).read().marks).containsExactly(mark(1))
  }

  /** Un disco che muore alla scrittura numero [killAtWrite] (da 0): prima di toccare il file, o subito dopo averlo scritto intero. */
  private class DyingIo(private val killAtWrite: Int, private val beforeWrite: Boolean, private val error: Throwable = ProcessDied()) : LedgerIo {
    private var writes = 0

    override fun read(file: File): ByteArray? = StateFilesLedgerIo.read(file)

    override fun write(file: File, bytes: ByteArray) {
      val mine = writes++
      if (mine == killAtWrite && beforeWrite) throw error
      StateFilesLedgerIo.write(file, bytes)
      if (mine == killAtWrite) throw error
    }

    override fun delete(file: File): Boolean = StateFilesLedgerIo.delete(file)
  }
}
