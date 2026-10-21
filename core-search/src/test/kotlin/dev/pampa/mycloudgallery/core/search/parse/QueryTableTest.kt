package dev.pampa.mycloudgallery.core.search.parse

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** P7: la tabella pubblica di query italiane, una riga per test (`resources/parse/query-*.txt`, formato in `LEGGIMI.txt`). */
@RunWith(Parameterized::class)
class QueryTableTest(private val row: QueryRow) {

  @Test
  fun laRigaDellaTabella() {
    val parsed = ItalianQueryParser().parse(row.query, row.today, TestPlaces.DICTIONARY)
    val actual = QueryRenderer.columns(parsed)
    val names = listOf("chip", "contenuto", "parole", "testo esatto", "correzioni")
    for (k in actual.indices) {
      assertWithMessage("${names[k]} di $row").that(actual[k]).isEqualTo(row.expected[k])
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun rows(): List<Array<Any>> = QueryTable.load().map { arrayOf(it) }
  }
}
