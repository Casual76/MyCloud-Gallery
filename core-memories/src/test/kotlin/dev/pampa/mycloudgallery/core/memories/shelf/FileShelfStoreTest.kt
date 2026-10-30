package dev.pampa.mycloudgallery.core.memories.shelf

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import java.io.File
import java.io.IOException
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lo scaffale su file (D10, §4.3 "Stabilita'"; P6): la **morte del processo a ogni salvataggio**, i resti di un temporaneo, i file rovinati
 * sul disco vero, la lettura in un colpo, l'azzeramento.
 */
class FileShelfStoreTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun directory(): File = File(temp.newFolder(), "memories")

  private fun shelfFile(directory: File) = File(directory, FileShelfStore.FILE_NAME)

  /** Una serie di scaffali diversi (piu' grandi, piu' piccoli, vuoti) da salvare uno dopo l'altro. */
  private val saves = List(10) { n -> if (n % 4 == 3) richShelf(n).copy(memories = emptyList()) else richShelf(n, lastRunDay = 20261001 + n) }

  /**
   * §4.3: la morte del processo **a ogni salvataggio**, in ognuno dei tre punti (temporaneo a meta', temporaneo intero, dopo la rinomina):
   * un nuovo processo trova lo scaffale di prima, intero, o quello nuovo, intero; mai uno a meta' e mai un file che manca; poi salva ancora e
   * non resta nessun temporaneo.
   */
  @Test
  fun laMorteDelProcessoAOgniSalvataggioLasciaUnoScaffaleIntero() = runTest {
    for (point in DeathPoint.entries) for (k in saves.indices) {
      val dir = directory()
      val io = FaultyIo(StateFilesIo)
      val store = FileShelfStore(dir, io, Dispatchers.Unconfined)
      for (i in 0 until k) store.write(saves[i])
      io.dieAt = point
      assertThat(runCatching { store.write(saves[k]) }.exceptionOrNull()).isInstanceOf(CrashException::class.java)
      // Un nuovo processo: uno store nuovo sulla stessa cartella.
      val reborn = FileShelfStore(dir, Dispatchers.Unconfined)
      val expected = when {
        point == DeathPoint.AFTER_RENAME -> ShelfRead.Ready(saves[k])
        k == 0 -> ShelfRead.Missing
        else -> ShelfRead.Ready(saves[k - 1])
      }
      assertWithMessage("$point, salvataggio $k").that(reborn.read()).isEqualTo(expected)
      val next = saves[(k + 1) % saves.size]
      reborn.write(next)
      assertThat(reborn.read()).isEqualTo(ShelfRead.Ready(next))
      assertThat(dir.list()!!.toList()).containsExactly(FileShelfStore.FILE_NAME)
    }
  }

  /** Un temporaneo lasciato a meta' accanto a un file buono non si legge mai: il file buono resta lo scaffale. */
  @Test
  fun unTemporaneoAMetaNonSiLegge() = runTest {
    val dir = directory()
    val store = FileShelfStore(dir, Dispatchers.Unconfined)
    store.write(saves[0])
    File(dir, FileShelfStore.FILE_NAME + ".tmp").writeBytes(ShelfCodec.encode(saves[1]).copyOf(40))
    assertThat(store.read()).isEqualTo(ShelfRead.Ready(saves[0]))
  }

  /** Il file rovinato in ogni modo sul disco vero: troncato (a lunghezze casuali), un byte cambiato, vuoto, enorme, senza il CRC. */
  @Test
  fun unFileRovinatoSulDiscoESempreRovinatoMaiUnCrollo() = runTest {
    val dir = directory()
    val store = FileShelfStore(dir, Dispatchers.Unconfined)
    store.write(saves[0])
    val good = shelfFile(dir).readBytes()
    val random = Random(11)
    fun put(bytes: ByteArray) = shelfFile(dir).writeBytes(bytes)
    repeat(200) {
      put(good.copyOf(random.nextInt(good.size)))
      assertThat(store.read()).isEqualTo(ShelfRead.Corrupt)
    }
    repeat(200) {
      val copy = good.copyOf()
      val at = random.nextInt(copy.size)
      copy[at] = (copy[at].toInt() xor (1 shl random.nextInt(8))).toByte()
      put(copy)
      assertThat(store.read()).isEqualTo(ShelfRead.Corrupt)
    }
    put(ByteArray(0))
    assertThat(store.read()).isEqualTo(ShelfRead.Corrupt)
    put(ByteArray(ShelfCodec.MAX_BYTES + 100))
    assertThat(store.read()).isEqualTo(ShelfRead.Corrupt)
    put(good.copyOf(good.size - 4))
    assertThat(store.read()).isEqualTo(ShelfRead.Corrupt)
    // Una scrittura lo sostituisce e lo scaffale torna intero.
    store.write(saves[2])
    assertThat(store.read()).isEqualTo(ShelfRead.Ready(saves[2]))
  }

  /** Un errore del disco (il file c'e' ma non si legge) non e' "file rovinato": lancia, e lo scaffale buono non si butta. */
  @Test
  fun unErroreDelDiscoLanciaESiRiprova() = runTest {
    val dir = directory()
    val io = FaultyIo(StateFilesIo)
    val store = FileShelfStore(dir, io, Dispatchers.Unconfined)
    store.write(saves[0])
    io.failReads = true
    assertThat(runCatching { store.read() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    io.failReads = false
    assertThat(store.read()).isEqualTo(ShelfRead.Ready(saves[0]))
  }

  /** D37: l'azzeramento toglie lo scaffale e il suo temporaneo; se non ci riesce lo dice. */
  @Test
  fun clearToglieIlFileEIlTemporaneo() = runTest {
    val dir = directory()
    val store = FileShelfStore(dir, Dispatchers.Unconfined)
    store.clear()
    store.write(saves[0])
    File(dir, FileShelfStore.FILE_NAME + ".tmp").writeBytes(byteArrayOf(1, 2, 3))
    store.clear()
    assertThat(dir.list()!!.toList()).isEmpty()
    assertThat(store.read()).isEqualTo(ShelfRead.Missing)
    val refusing = object : ShelfIo by StateFilesIo {
      override fun delete(file: File): Boolean = false
    }
    val failing = FileShelfStore(dir, refusing, Dispatchers.Unconfined)
    assertThat(runCatching { failing.clear() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  /** Un contenuto che non puo' stare nel file non lo sporca: la scrittura lancia **prima** di toccare il disco, e il file di prima resta. */
  @Test
  fun unContenutoNonScrivibileNonToccaIlFile() = runTest {
    val dir = directory()
    val store = FileShelfStore(dir, Dispatchers.Unconfined)
    store.write(saves[0])
    val before = shelfFile(dir).readBytes()
    val label = "z".repeat(ShelfCodec.MAX_LABEL_BYTES + 1)
    val bad = saves[1].copy(memories = saves[1].memories.map { it.copy(title = it.title.copy(tripLabel = label)) })
    assertThat(runCatching { store.write(bad) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(shelfFile(dir).readBytes()).isEqualTo(before)
    assertThat(dir.list()!!.toList()).containsExactly(FileShelfStore.FILE_NAME)
  }

  /** Lo scaffale si legge in un colpo e con lo scaffale pieno e' una lettura da pochi millisecondi (nessuna scansione, un solo file). */
  @Test
  fun unaSolaLetturaDiUnSoloFile() = runTest {
    val dir = directory()
    var reads = 0
    val counting = object : ShelfIo by StateFilesIo {
      override fun read(file: File): ByteArray? = StateFilesIo.read(file).also { reads++ }
    }
    val store = FileShelfStore(dir, counting, Dispatchers.Unconfined)
    store.write(saves[0])
    assertThat(store.read()).isEqualTo(ShelfRead.Ready(saves[0]))
    assertThat(reads).isEqualTo(1)
  }

  /** Lo scaffale pieno (60 ricordi da 40 elementi) si legge da file e si decodifica in pochi millisecondi: Raccolte -> Ricordi si disegna subito. */
  @Test
  fun lLetturaDelloScaffalePienoEVeloce() = runTest {
    val dir = directory()
    val store = FileShelfStore(dir, Dispatchers.Unconfined)
    val memories = List(60) { n ->
      val key = dev.pampa.mycloudgallery.core.memories.contract.MemoryKey.OnThisDay(java.time.LocalDate.of(2020, 1, 1).plusDays(n.toLong()))
      val ids = List(40) { dev.pampa.mycloudgallery.core.model.MediaId(java.util.UUID(n.toLong(), it.toLong()).toString()) }
      val title = dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle(1, dev.pampa.mycloudgallery.core.memories.contract.DayRange.of(java.time.LocalDate.of(2020, 1, 1)), listOf(1, 2, 3), "x".repeat(ShelfCodec.MAX_LABEL_CHARS), null, null)
      dev.pampa.mycloudgallery.core.memories.contract.Memory(dev.pampa.mycloudgallery.core.memories.contract.MemoryIds.of(key), key, title, ids, ids[0], 20261027)
    }
    val full = dev.pampa.mycloudgallery.core.memories.contract.ShelfFile(memories, lastRunDay = 20261027)
    store.write(full)
    store.read()
    val runs = 50
    val start = System.nanoTime()
    repeat(runs) { store.read() }
    val ms = (System.nanoTime() - start) / 1_000_000.0 / runs
    assertThat(store.read()).isEqualTo(ShelfRead.Ready(full))
    println("P6 misure: lettura da file e decodifica dello scaffale pieno = %.2f ms".format(ms))
    assertWithMessage("lettura dello scaffale pieno: %.2f ms".format(ms)).that(ms).isLessThan(50.0)
  }
}
