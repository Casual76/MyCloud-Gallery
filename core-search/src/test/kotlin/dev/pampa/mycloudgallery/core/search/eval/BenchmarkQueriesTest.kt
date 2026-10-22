package dev.pampa.mycloudgallery.core.search.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.io.File
import java.time.LocalDate
import org.junit.Test

/**
 * Le 50 query pubbliche di `SearchBenchmark` (modulo `:benchmark`, che non ha test sulla JVM) sono quelle che dicono di essere: 30 per contenuto, 10 di soli
 * filtri e 10 di testo, tutte diverse, senza nomi di persona. Il test legge i sorgenti del benchmark e le passa all'analizzatore vero (P7).
 */
class BenchmarkQueriesTest {

  private val source: String = File(repositoryRoot(), "benchmark/src/main/kotlin/dev/pampa/mycloudgallery/benchmark/SearchBenchSupport.kt").readText()

  private fun list(name: String): List<String> {
    val start = source.indexOf("val $name: List<String> = listOf(")
    require(start >= 0) { "Elenco $name non trovato." }
    val end = source.indexOf("\n  )", start)
    return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(source.substring(start, end)).map { it.groupValues[1] }.toList()
  }

  private val places = PlaceDictionary(
    listOf(
      PlaceEntry("firenze", PlaceOption(setOf(101), "Firenze", PlaceLevel.PLACE), 10),
      PlaceEntry("roma", PlaceOption(setOf(103), "Roma", PlaceLevel.PLACE), 10),
    ),
  )
  private val today = LocalDate.of(2026, 10, 21)
  private val parser = ItalianQueryParser()

  @Test
  fun sonoCinquantaTutteDiverse() {
    val all = list("CONTENT") + list("FILTERS") + list("TEXT")
    assertThat(list("CONTENT")).hasSize(30)
    assertThat(list("FILTERS")).hasSize(10)
    assertThat(list("TEXT")).hasSize(10)
    assertThat(all.distinct()).hasSize(50)
    assertThat(all.all { it.length in 3..60 }).isTrue()
  }

  @Test
  fun leQueryPerContenutoHannoUnTestoDaCercareNelModello() {
    for (q in list("CONTENT")) assertThat(parser.parse(q, today, places).semantic).isNotNull()
  }

  @Test
  fun leQueryDiSoliFiltriNonHannoUnaParteSemantica() {
    for (q in list("FILTERS")) {
      val parsed = parser.parse(q, today, places)
      assertThat(parsed.semantic).isNull()
      assertThat(parsed.words).isEmpty()
      assertThat(parsed.chips.any { it.isFilter }).isTrue()
    }
  }

  @Test
  fun leQueryDiTestoHannoParoleDaCercareNelTestoLetto() {
    for (q in list("TEXT")) {
      val parsed = parser.parse(q, today, places)
      assertThat(parsed.words).isNotEmpty()
      assertThat(parsed.chips.none { it.isFilter }).isTrue()
    }
  }

  private fun repositoryRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle").isFile) return dir
      dir = dir.parentFile
    }
    error("Radice del repository non trovata.")
  }
}
