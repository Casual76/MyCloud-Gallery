package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.ContentKey
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Lavoro doppio ≤ 1% in 1.000 giri casuali con due e tre dispositivi simulati" (riga P3 del §4.4; D13, E-I1, E-I2).
 *
 * Ogni dispositivo ha unita' di lavoro vere (prenotazioni firmate sul NAS finto, teste rilette a ogni unita'),
 * pubblicatore vero e il proprio catalogo. A ogni giro uno a caso fa un passo: prende un'unita' o prepara da 1 a 10 media
 * (20 s di ora del NAS ciascuno), rinnova ogni 20 minuti, pubblica alla fine dell'unita' (e ogni 5 minuti nel mezzo).
 * Guasti casuali: morte del processo (1% dei giri: niente rilascio, si riparte con un processo nuovo) e fine di tappa
 * con il dispositivo che sparisce da 10 minuti a 3 ore (0,5%). Lavoro doppio = preparazioni di un media gia' preparato
 * da un altro dispositivo.
 */
class DoubleWorkSimulationTest {

  private val share = "test-comune"

  private class Result(val preparations: Int, val distinct: Int, val duplicates: Int)

  private fun simulate(devices: Int, seed: Int, rounds: Int = 1000): Result {
    var result: Result? = null
    val started = System.nanoTime()
    runTest(timeout = kotlin.time.Duration.parse("10m")) {
      val random = Random(seed)
      val world = TestWorld()
      val months = listOf("2023-11", "2023-12", "2024-01", "2024-02", "2024-03", "2024-04")
      val media = simMedia(world, share, months, perMonth = 350, random)
      val workers = (1..devices).map { SimWorker(world, world.device("D$it"), media) }
      val preparedBy = HashMap<ContentKey, MutableList<Int>>()

      repeat(rounds) {
        world.clock.advance(random.nextLong(5_000, 30_000))
        val index = random.nextInt(devices)
        val worker = workers[index]
        if (worker.offlineUntil > world.clock.nowMillis()) return@repeat
        if (worker.offlineUntil != Long.MIN_VALUE) {
          worker.offlineUntil = Long.MIN_VALUE
          worker.restart()
        }
        val roll = random.nextDouble()
        when {
          roll < 0.01 -> worker.restart() // morte del processo: niente finish, la prenotazione scade da sola
          roll < 0.015 -> worker.stop(random.nextLong(10L * 60_000, 180L * 60_000))
          else -> worker.step(random.nextInt(1, 11)).forEach { preparedBy.getOrPut(it) { ArrayList() } += index }
        }
      }
      val preparations = preparedBy.values.sumOf { it.size }
      result = Result(preparations, preparedBy.size, preparedBy.values.sumOf { it.size - 1 })
    }
    println("P3 simulazione: $devices dispositivi, seme $seed, ${(System.nanoTime() - started) / 1_000_000} ms")
    return result!!
  }

  private fun check(devices: Int, seeds: IntRange) {
    var preparations = 0
    var duplicates = 0
    for (seed in seeds) {
      val r = simulate(devices, seed)
      preparations += r.preparations
      duplicates += r.duplicates
      assertWithMessage("seme $seed: lavoro fatto").that(r.distinct).isGreaterThan(1000)
    }
    val ratio = duplicates.toDouble() / preparations
    println("P3 lavoro doppio, $devices dispositivi: $duplicates su $preparations preparazioni (${"%.3f".format(ratio * 100)}%)")
    assertWithMessage("lavoro doppio con $devices dispositivi").that(ratio).isAtMost(0.01)
  }

  @Test
  fun dueDispositivi_lavoroDoppioAlPiuUnoPerCento() = check(devices = 2, seeds = 1..3)

  @Test
  fun treDispositivi_lavoroDoppioAlPiuUnoPerCento() = check(devices = 3, seeds = 11..13)
}
