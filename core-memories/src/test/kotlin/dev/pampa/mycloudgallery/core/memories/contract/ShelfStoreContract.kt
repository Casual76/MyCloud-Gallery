package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La suite di contratto del deposito dello scaffale (Fase 9, P0; `docs/fase9-progetto.md` D9, D10, §2.3, §4.3 "Stabilita'", §5.2). Gira sul
 * riferimento in memoria e, da P6, su `FileShelfStore` (cartella temporanea, file troncati e rovinati, morte del processo a ogni salvataggio).
 */
abstract class ShelfStoreContract {

  interface Rig {
    val store: MemoryShelfStore

    /** La prossima scrittura muore fra il temporaneo e la rinomina (per `FileShelfStore`: il processo muore a meta'). */
    fun dieDuringNextWrite()

    /** Il file si rovina (byte cambiati, troncato, CRC sbagliato). */
    fun corrupt()
  }

  protected abstract fun rig(): Rig

  companion object {
    private var n = 0L

    /** Un ricordo valido per le prove (id deterministico dalla chiave, elementi casuali). */
    fun sampleMemory(
      key: MemoryKey = MemoryKey.OnThisDay(LocalDate.of(2025, 10, 27).minusDays(n)),
      members: Int = 6,
      publishedDay: Int = 20261027,
    ): Memory {
      n++
      val ids = List(members) { MediaId(UUID(n, it.toLong()).toString()) }
      val title = MemoryTitle(1, DayRange.of(LocalDate.of(2025, 10, 27)), listOf(3_100_000), null, null, null)
      return Memory(MemoryIds.of(key), key, title, ids, ids[minOf(2, members - 1)], publishedDay)
    }
  }

  private fun richFile(): ShelfFile {
    val trip = MemoryKey.Trip(DayRange(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 6)))
    val person = MemoryKey.Person(PersonId.fromBytes(ByteArray(16) { 4 }), PersonWindow.Year(2024))
    val month = MemoryKey.Month(YearMonth.of(2026, 9))
    val withAlbum = sampleMemory(trip).let { it.copy(origin = MemoryOrigin.TripAlbum(AlbumId.random())) }
    return ShelfFile(
      listOf(sampleMemory(), withAlbum, sampleMemory(person), sampleMemory(month, members = 40)),
      lastPublished = mapOf(MemoryKind.TRIP to 20261025, MemoryKind.PERSON to 20261020),
      lastRediscovery = 20261001,
      lastRunDay = 20261027,
    )
  }

  @Test
  fun senzaFileNienteScaffale() = runTest {
    assertThat(rig().store.read()).isEqualTo(ShelfRead.Missing)
  }

  @Test
  fun quelloCheSiScriveSiRilleggeUguale() = runTest {
    val rig = rig()
    val file = richFile()
    rig.store.write(file)
    assertThat(rig.store.read()).isEqualTo(ShelfRead.Ready(file))
    val second = file.copy(memories = file.memories.drop(1), lastRunDay = 20261028)
    rig.store.write(second)
    assertThat(rig.store.read()).isEqualTo(ShelfRead.Ready(second))
  }

  /** §4.3: la morte del processo a meta' scrittura lascia il file di prima, intero. */
  @Test
  fun laMorteAMetaScritturaLasciaIlFileDiPrima() = runTest {
    val rig = rig()
    val before = richFile()
    rig.store.write(before)
    rig.dieDuringNextWrite()
    val failure = runCatching { rig.store.write(before.copy(memories = emptyList())) }.exceptionOrNull()
    assertThat(failure).isNotNull()
    assertThat(rig.store.read()).isEqualTo(ShelfRead.Ready(before))
    rig.store.write(before.copy(lastRunDay = 20261029))
    assertThat((rig.store.read() as ShelfRead.Ready).file.lastRunDay).isEqualTo(20261029)
  }

  /** D10: un file rovinato vale "scaffale vuoto" (lo dice la lettura, mai un crollo) e una scrittura lo sostituisce. */
  @Test
  fun unFileRovinatoSiRiconosceESiSostituisce() = runTest {
    val rig = rig()
    rig.store.write(richFile())
    rig.corrupt()
    assertThat(rig.store.read()).isEqualTo(ShelfRead.Corrupt)
    rig.store.write(ShelfFile.EMPTY)
    assertThat(rig.store.read()).isEqualTo(ShelfRead.Ready(ShelfFile.EMPTY))
  }

  /** D37: l'azzeramento toglie lo scaffale. */
  @Test
  fun clearToglieTutto() = runTest {
    val rig = rig()
    rig.store.write(richFile())
    rig.store.clear()
    assertThat(rig.store.read()).isEqualTo(ShelfRead.Missing)
  }

  /** D10: al piu' 60 ricordi, ognuno una volta sola; un ricordo da 3 a 40 elementi con la copertina fra gli elementi. */
  @Test
  fun iTettiStannoNelTipo() {
    assertThrows(IllegalArgumentException::class.java) { ShelfFile(List(61) { sampleMemory() }) }
    val one = sampleMemory()
    assertThrows(IllegalArgumentException::class.java) { ShelfFile(listOf(one, one)) }
    assertThrows(IllegalArgumentException::class.java) { sampleMemory(members = 2) }
    assertThrows(IllegalArgumentException::class.java) { sampleMemory(members = 41) }
    assertThrows(IllegalArgumentException::class.java) { one.copy(cover = MediaId.random()) }
  }
}

/** La suite sul riferimento (§5.2). */
class InMemoryShelfStoreContractTest : ShelfStoreContract() {
  override fun rig(): Rig = object : Rig {
    private val memory = InMemoryShelfStore()
    override val store: MemoryShelfStore = memory

    override fun dieDuringNextWrite() = memory.dieDuringNextWrite()

    override fun corrupt() = memory.corrupt()
  }
}
