package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Una riga della tabella pubblica dei titoli: il titolo, "oggi" e le cinque colonne attese. */
data class TitleRow(val file: String, val line: Int, val today: LocalDate, val title: String, val expected: List<String>) {
  override fun toString(): String = "$file:$line «${title.take(60)}»"
}

/**
 * La TABELLA PUBBLICA dei titoli degli album smart (P6, §2.2 passi 1-3, D13): i file `resources/smart/titoli-*.txt`, eseguiti da [TitleTableTest]
 * una riga per test. Il formato sta in `resources/smart/LEGGIMI.txt`. La Libreria e' [TitleLibrary]: luoghi pubblici e viaggi inventati.
 */
internal object TitleTable {
  val FILES = listOf(
    "titoli-anni.txt", "titoli-stagioni.txt", "titoli-feste.txt", "titoli-cavallo-danno.txt", "titoli-luoghi.txt", "titoli-omonimi.txt",
    "titoli-senza-data.txt", "titoli-senza-luogo.txt", "titoli-tipi.txt", "titoli-relativi.txt", "titoli-limiti.txt",
  )

  val DEFAULT_TODAY: LocalDate = LocalDate.of(2026, 12, 20)

  fun load(): List<TitleRow> = FILES.flatMap { load(it) }

  fun load(name: String): List<TitleRow> {
    val stream = TitleTable::class.java.getResourceAsStream("/smart/$name") ?: error("Manca la tabella smart/$name")
    var today = DEFAULT_TODAY
    val rows = ArrayList<TitleRow>()
    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
      lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        when {
          line.isEmpty() || line.startsWith("#") -> Unit
          line.startsWith("@oggi ") -> today = LocalDate.parse(line.removePrefix("@oggi ").trim())
          else -> {
            val parts = line.split(" => ").map { it.trim() }
            val title = when (parts[0]) {
              "<vuoto>" -> ""
              "<spazi>" -> "   "
              else -> parts[0]
            }
            rows += TitleRow(name, index + 1, today, title, (1..5).map { parts.getOrNull(it) ?: "-" })
          }
        }
      }
    }
    return rows
  }
}

/** Come una proposta si scrive in una riga della tabella (stessa forma di `LEGGIMI.txt`). */
internal object TitleRenderer {

  fun columns(proposal: RuleProposal): List<String> {
    val rules = proposal.rules
    return listOf(period(rules.period), place(rules.place, proposal), rules.text ?: "-", types(rules.types), notes(proposal))
  }

  private fun period(period: SmartPeriod?): String = when (period) {
    null -> "-"
    is SmartPeriod.Ranges -> "R " + period.ranges.joinToString(" ; ") { "${it.from}..${it.to}" }
    is SmartPeriod.EveryYear -> "Y %02d-%02d..%02d-%02d".format(period.from.monthValue, period.from.dayOfMonth, period.to.monthValue, period.to.dayOfMonth)
    is SmartPeriod.Days -> period.days.sorted().let { "E #${it.size} ${it.first()}..${it.last()}" }
  }

  private fun place(rule: PlaceRule?, proposal: RuleProposal): String {
    rule ?: return "-"
    val alternatives = if (proposal.alternatives.isEmpty()) "" else " alt=" + proposal.alternatives.joinToString(",") { "${it.rule.label}#${ids(it.rule)}:${it.mediaCount}" }
    return "${rule.scope.code} ${rule.label} r${rule.radiusKm} #${ids(rule)}$alternatives"
  }

  private fun ids(rule: PlaceRule): String = rule.placeIds.joinToString("+")

  private fun types(types: Set<SmartMediaType>): String = if (types == SmartMediaType.ALL) "-" else types.joinToString("+") { it.name }

  private fun notes(proposal: RuleProposal): String {
    val tokens = proposal.notes.map { it.name }.toMutableList()
    if (proposal.periodSeries.isNotEmpty()) tokens += "serie=${proposal.periodSeries.size}"
    return tokens.sorted().joinToString(",").ifEmpty { "-" }
  }
}

/** P6: la tabella pubblica dei titoli, una riga per test (`resources/smart/titoli-*.txt`, formato in `LEGGIMI.txt`). */
@RunWith(Parameterized::class)
class TitleTableTest(private val row: TitleRow) {

  @Test
  fun laRigaDellaTabella() = runTest {
    val world = TitleLibrary.world()
    val interpreter = RuleSmartNameInterpreter(ItalianQueryParser(), world, world, world.candidates(), world.placeLookup)
    val proposal = interpreter.propose(row.title, row.today)
    val actual = TitleRenderer.columns(proposal)
    val names = listOf("periodo", "luogo", "descrizione", "tipi", "note")
    for (k in actual.indices) {
      val want = row.expected[k]
      if (want.contains("r*")) {
        val pattern = want.split("r*").joinToString("r\\d+") { Regex.escape(it) }
        assertWithMessage("${names[k]} di $row").that(actual[k]).matches(pattern)
      } else {
        assertWithMessage("${names[k]} di $row").that(actual[k]).isEqualTo(want)
      }
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun rows(): List<Array<Any>> = TitleTable.load().map { arrayOf(it) }
  }
}

/** La tabella e' davvero grande e copre cio' che promette (P6, riga del §4.4: almeno 150 titoli). */
class TitleTableShapeTest {
  @Test
  fun cisonoAlmenoCentoCinquantaTitoliDiversi() {
    val rows = TitleTable.load()
    assertThat(rows.size).isAtLeast(150)
    assertThat(rows.map { it.title }.toSet().size).isAtLeast(150)
  }

  @Test
  fun laTabellaCopreOgniCategoria() {
    val rows = TitleTable.load()
    fun count(file: String) = rows.count { it.file == file }
    for (file in TitleTable.FILES) assertWithMessage("righe di $file").that(count(file)).isAtLeast(5)
    val columns = rows.map { it.expected }
    assertThat(columns.any { it[0].startsWith("Y ") }).isTrue()
    assertThat(columns.any { it[0].startsWith("E ") }).isTrue()
    assertThat(columns.any { it[0].contains(" ; ") }).isTrue()
    assertThat(columns.any { it[1].startsWith("region ") }).isTrue()
    assertThat(columns.any { it[1].startsWith("country ") }).isTrue()
    assertThat(columns.any { it[1].contains(" alt=") }).isTrue()
    assertThat(columns.any { it[1] == "-" && it[2] != "-" }).isTrue()
    assertThat(columns.any { it[0] == "-" && it[1] == "-" }).isTrue()
  }

  @Test
  fun unaRigaSolaNonHaSeriePiuLunghe() = runTest {
    // La tabella dei titoli e' sulla Libreria sintetica: nessuna foto e' nascosta e nessun luogo e' fuori dall'elenco.
    val world = TitleLibrary.world()
    assertThat(world.items().size).isGreaterThan(300)
    assertThat(world.hidden).isEmpty()
  }
}
