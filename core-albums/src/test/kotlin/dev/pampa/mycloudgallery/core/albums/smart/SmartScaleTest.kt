package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import java.time.LocalDate
import java.time.MonthDay
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * LE PRESTAZIONI (riga P6 del §4.4, §2.10, F91): **meno di 2 secondi per album su 100 mila elementi**, sulla JVM, per la valutazione intera di
 * un album (carica, classifica, differenza con le righe, scrittura) in quattro forme: luogo e serie di giorni, solo il periodo di un anno, luogo senza
 * periodo (il caso peggiore: legge tutta la Libreria) e luogo con "ogni anno". Il tempo e' il migliore di tre giri dopo uno di riscaldamento (il PC e'
 * condiviso); il ricalcolo incrementale di un lotto di arrivi costa una frazione di quello intero. Il telefono si misura a F91.
 */
class SmartScaleTest {
  private val base = LocalDate.of(2025, 1, 1)

  /** Cento mila media su due anni (come `RandomWorlds`: GPS dentro, vicino, lontano e senza, dispositivi, familiari, screenshot). */
  private fun bigWorld(count: Int = 100_000): MemoryWorld {
    val rnd = Random(2026)
    val world = MemoryWorld()
    val items = ArrayList<SmartCandidate>(count)
    for (i in 1..count) items += RandomWorlds.candidate(i, rnd, base.plusDays(rnd.nextLong(0, 730)))
    world.put(*items.toTypedArray())
    return world
  }

  private suspend fun best(runs: Int = 3, block: suspend () -> Unit): Long {
    block()
    var best = Long.MAX_VALUE
    repeat(runs) {
      val start = System.nanoTime()
      block()
      best = minOf(best, (System.nanoTime() - start) / 1_000_000)
    }
    return best
  }

  private suspend fun timeAlbum(world: MemoryWorld, rules: SmartRules): Pair<Long, SmartOutcome> {
    val id = albumId(1)
    var outcome: SmartOutcome = SmartOutcome.NotFound
    val ms = best {
      val rows = InMemorySmartRows()
      rows.create(id, rules)
      val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, FakeAlbumClocks())
      outcome = engine.evaluate(id, Duration.INFINITE)
    }
    return ms to outcome
  }

  @Test
  fun menoDiDueSecondiPerAlbumSuCentoMilaElementi() = runTest {
    val world = bigWorld()
    assertThat(world.items()).hasSize(100_000)
    val year = SmartPeriod.Ranges(listOf(LocalDayRange(day(2025, 1, 1), day(2025, 12, 31))))
    val shapes = listOf(
      "luogo e periodo" to SmartRules(year, around(Spots.OSLO, 25), null),
      "solo il periodo" to SmartRules(year, null, null),
      "luogo senza periodo" to SmartRules(null, around(Spots.OSLO, 25), null),
      "luogo ogni anno" to SmartRules(SmartPeriod.EveryYear(MonthDay.of(3, 1), MonthDay.of(9, 30)), around(Spots.OSLO, 100), null),
    )
    for ((name, rules) in shapes) {
      val (ms, outcome) = timeAlbum(world, rules)
      println("PRESTAZIONI $name: $ms ms su 100000 elementi ($outcome)")
      assertWithMessage("evaluate di $name").that(ms).isLessThan(2_000)
      assertThat(outcome).isInstanceOf(SmartOutcome.Done::class.java)
    }
  }

  @Test
  fun ilRicalcoloIncrementaleDiUnLottoDiArriviEUnaFrazioneDiQuelloIntero() = runTest {
    val world = bigWorld()
    val rules = SmartRules(SmartPeriod.Ranges(listOf(LocalDayRange(day(2025, 1, 1), day(2025, 12, 31)))), around(Spots.OSLO, 25), null)
    val rows = InMemorySmartRows()
    val id = albumId(1)
    rows.create(id, rules)
    val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, FakeAlbumClocks())
    val startFull = System.nanoTime()
    engine.evaluate(id, Duration.INFINITE)
    val full = (System.nanoTime() - startFull) / 1_000_000
    // Arriva un lotto di 300 media su cinque giorni.
    val rnd = Random(7)
    val days = (0 until 5).map { base.plusDays(40L + it) }
    val arrived = (0 until 300).map { RandomWorlds.candidate(200_000 + it, rnd, days[it % 5]) }
    world.put(*arrived.toTypedArray())
    val start = System.nanoTime()
    engine.reevaluate(id, days)
    val incremental = (System.nanoTime() - start) / 1_000_000
    println("PRESTAZIONI incrementale di 300 arrivi in 5 giorni: $incremental ms (intera: $full ms)")
    assertThat(incremental).isLessThan(maxOf(full / 2, 200))
  }

  @Test
  fun unPeriodoBreveNonRileggeLaLibreria() = runTest {
    val world = bigWorld(20_000)
    val rows = InMemorySmartRows()
    val id = albumId(1)
    rows.create(id, SmartRules(SmartPeriod.Ranges(listOf(LocalDayRange(day(2025, 3, 1), day(2025, 3, 7)))), around(Spots.OSLO, 25), null))
    val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, FakeAlbumClocks())
    val before = world.candidates().pages
    engine.evaluate(id, Duration.INFINITE)
    assertThat(world.candidates().pages - before).isAtMost(2)
  }
}
