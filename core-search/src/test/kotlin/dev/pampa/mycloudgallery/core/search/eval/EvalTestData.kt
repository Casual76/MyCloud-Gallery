package dev.pampa.mycloudgallery.core.search.eval

import java.util.Random

/**
 * Tabelle dei punteggi **sintetiche** per provare la calibrazione: nessun dato personale, nessuna query vera. Ogni tabella ha attese con z alto,
 * "negativi difficili" appena sopra la soglia di partenza e rumore. Deterministiche (seme).
 */
internal object EvalTestData {

  fun categoryOf(i: Int): EvalCategory = EvalCategory.entries[i % EvalCategory.entries.size]

  /**
   * Una tabella: [expected] attese (z attorno a [expectedZ], coseno [expectedS]), [hard] negativi con z attorno a [hardZ] e coseno [hardS], e
   * [noise] celle di rumore con z fra -2 e [noiseMaxZ]. Eta' in giorni scelte da [ageOf].
   */
  fun table(
    index: Int,
    expected: Int = 12,
    expectedZ: Double = 6.5,
    expectedZSd: Double = 1.2,
    expectedS: Double = 0.33,
    hard: Int = 6,
    hardZ: Double = 4.4,
    hardZSd: Double = 0.25,
    hardS: Double = 0.22,
    noise: Int = 300,
    noiseMaxZ: Double = 3.2,
    seed: Long = 11,
    expectedAge: (Random) -> Int = { it.nextInt(2000) },
    otherAge: (Random) -> Int = { it.nextInt(2000) },
  ): ScoreTable {
    val random = Random(seed * 1_000 + index)
    val cells = ArrayList<ScoredCell>()
    val keys = HashSet<String>()
    repeat(expected) {
      val key = "q$index-e$it"
      keys += key
      cells += ScoredCell(key, (expectedZ + random.nextGaussian() * expectedZSd).toFloat(), (expectedS + random.nextGaussian() * 0.02).toFloat(), 0f, expectedAge(random))
    }
    repeat(hard) {
      cells += ScoredCell("q$index-h$it", (hardZ + random.nextGaussian() * hardZSd).toFloat(), (hardS + random.nextGaussian() * 0.02).toFloat(), 0f, otherAge(random))
    }
    repeat(noise) {
      val z = (-2.0 + random.nextDouble() * (noiseMaxZ + 2.0)).toFloat()
      cells += ScoredCell("q$index-n$it", z, (0.05 + random.nextDouble() * 0.12).toFloat(), 0f, otherAge(random))
    }
    return ScoreTable.of("q$index", categoryOf(index), keys, cells)
  }

  fun tables(count: Int = 20, build: (Int) -> ScoreTable = { table(it) }): List<ScoreTable> = (0 until count).map(build)
}
