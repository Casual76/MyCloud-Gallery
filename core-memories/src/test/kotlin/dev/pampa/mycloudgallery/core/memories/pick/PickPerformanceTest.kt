package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.LookAlike
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import java.util.Random
import org.junit.Test

/**
 * Le prestazioni (§4.4, riga P7): meno di 50 ms per un ricordo da 200 candidati sulla JVM. Si misura la scelta (non la costruzione degli
 * ingressi) dopo un riscaldamento, sul caso peggiore: vettori da 768 numeri su tutti i candidati (un modello vero), pochi concetti (molti
 * doppioni da cercare), misure dei pixel, album e persone. La mediana di molte corse deve stare sotto soglia; il massimo si stampa.
 *
 * Il tempo si legge con `System.nanoTime` (monotono: l'orologio del telefono non si usa mai).
 */
class PickPerformanceTest {

  private class Case(val candidates: List<CandidateCell>, val signals: PickSignals)

  private fun build(seed: Long, size: Int, dim: Int, concepts: Int): Case {
    val random = Random(seed)
    val cells = Cells(tag = seed)
    val base = List(concepts) { SyntheticVectors.concept(seed * 97 + it, dim) }
    val vectors = HashMap<MediaId, FloatArray>()
    val pixels = HashMap<MediaId, PixelProbe>()
    val albums = HashSet<MediaId>()
    val people = HashMap<MediaId, Set<PersonId>>()
    val list = List(size) { i ->
      val flags = (if (random.nextInt(10) == 0) CellFlags.FAVORITE else 0) or (if (random.nextInt(10) == 0) CellFlags.EDITED else 0)
      val source = if (random.nextInt(8) == 0) MediaSource.WHATSAPP else MediaSource.CAMERA
      val cell = cells.cell(
        minute = random.nextInt(14 * 60),
        on = cells.day.plusDays(random.nextInt(5).toLong()),
        source = source,
        flags = flags,
      )
      vectors[cell.id] = SyntheticVectors.withCosine(base[random.nextInt(concepts)], 0.7 + random.nextDouble() * 0.29, seed * 1_000 + i)
      pixels[cell.id] = PixelProbe(random.nextFloat() * 400f, random.nextFloat())
      if (random.nextInt(5) == 0) albums += cell.id
      if (random.nextInt(4) == 0) people[cell.id] = setOf(person(1 + random.nextInt(3)))
      cell
    }
    return Case(list, PickSignals(vectors = vectors, pixels = pixels, albums = albums, people = people, lookAlike = LookAlike { _, _ -> null }))
  }

  private fun medianAndMax(kind: MemoryKind, case: Case): Pair<Double, Double> {
    val picker = MemoryPhotoPicker()
    val spec = MemoryRules.spec(kind)
    repeat(WARMUP) { picker.pick(case.candidates, spec, case.signals) }
    val millis = DoubleArray(RUNS) {
      val start = System.nanoTime()
      picker.pick(case.candidates, spec, case.signals)
      (System.nanoTime() - start) / 1e6
    }
    millis.sort()
    return millis[RUNS / 2] to millis.last()
  }

  @Test
  fun duecentoCandidatiSottoCinquantaMillisecondi() {
    val lines = ArrayList<String>()
    for (kind in listOf(MemoryKind.ON_THIS_DAY, MemoryKind.TRIP, MemoryKind.PERSON, MemoryKind.MONTH)) {
      val case = build(seed = 7, size = 200, dim = 768, concepts = 6)
      val (median, max) = medianAndMax(kind, case)
      lines += "pick ${kind.code}: 200 candidati, vettori da 768: mediana %.2f ms, massimo %.2f ms".format(median, max)
      assertThat(median).isLessThan(LIMIT_MS)
    }
    // Il numero resta nel resoconto del test (build/test-results), non nel codice.
    println(lines.joinToString("\n"))
  }

  /** Anche con tanti concetti diversi (pochi doppioni, il confronto va fino in fondo) e con vettori piu' corti. */
  @Test
  fun ilCasoSenzaDoppioniEPiuLento() {
    val case = build(seed = 11, size = 200, dim = 768, concepts = 200)
    val (median, max) = medianAndMax(MemoryKind.TRIP, case)
    println("pick trip, 200 concetti diversi, vettori da 768: mediana %.2f ms, massimo %.2f ms".format(median, max))
    assertThat(median).isLessThan(LIMIT_MS)
  }

  @Test
  fun senzaVettoriEUnBattitoDiCiglia() {
    val case = build(seed = 13, size = 200, dim = 768, concepts = 3)
    val bare = Case(case.candidates, PickSignals())
    val (median, max) = medianAndMax(MemoryKind.TRIP, bare)
    println("pick trip, 200 candidati senza vettori: mediana %.2f ms, massimo %.2f ms".format(median, max))
    assertThat(median).isLessThan(LIMIT_MS)
  }

  /** Un mese pesante (1.500 candidati) non e' il caso del "Fatto quando", ma non deve diventare un secondo: tetto largo, misura stampata. */
  @Test
  fun unMesePesanteRestaSottoUnSecondo() {
    val case = build(seed = 17, size = 1_500, dim = 256, concepts = 40)
    val (median, max) = medianAndMax(MemoryKind.MONTH, case)
    println("pick month, 1500 candidati, vettori da 256: mediana %.2f ms, massimo %.2f ms".format(median, max))
    assertThat(median).isLessThan(1_000.0)
  }

  private companion object {
    const val WARMUP = 25
    const val RUNS = 21
    const val LIMIT_MS = 50.0
  }
}
