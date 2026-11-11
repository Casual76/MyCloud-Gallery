package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import org.junit.Test

/**
 * Le voci della griglia dei risultati (H4): **solo per pertinenza** e **senza intestazioni di data**; prima i sicuri (il primo e' il piu' adatto: la
 * griglia lo mette subito sopra la barra di ricerca), poi il separatore leggero e i meno probabili; il viewer scorre i risultati nell'ordine mostrato.
 */
class SearchGridEntriesTest {

  private val lib = library(count = 40)

  private fun results(confident: IntArray, likely: IntArray = intArrayOf()): SearchScreenState.Results =
    SearchScreenState.Results(emptyList(), lib.view(*confident), lib.view(*likely), SearchCoverage.Complete, emptyList())

  @Test
  fun iSicuriNellOrdineDelMotoreSenzaDateNeSeparatoriSeNonCiSonoMenoProbabili() {
    val entries = SearchGridEntries.build(results(intArrayOf(7, 2, 30, 11)))
    assertThat(entries.map { (it as GridEntry.Cell).media }).containsExactly(lib.id(7), lib.id(2), lib.id(30), lib.id(11)).inOrder()
    assertThat(entries.filterIsInstance<GridEntry.LikelyDivider>()).isEmpty()
  }

  @Test
  fun iMenoProbabiliInCodaDopoIlSeparatoreCheDiceQuantiSono() {
    val entries = SearchGridEntries.build(results(intArrayOf(3, 1), intArrayOf(20, 21, 22)))
    assertThat(entries).hasSize(6)
    assertThat(entries[2]).isEqualTo(GridEntry.LikelyDivider(3))
    assertThat((entries[0] as GridEntry.Cell).section).isEqualTo(ResultSection.CONFIDENT)
    assertThat((entries[3] as GridEntry.Cell).section).isEqualTo(ResultSection.LIKELY)
    assertThat((entries.last() as GridEntry.Cell).media).isEqualTo(lib.id(22))
  }

  @Test
  fun soloMenoProbabiliIlSeparatoreStaInTesta() {
    val entries = SearchGridEntries.build(results(intArrayOf(), intArrayOf(5, 6)))
    assertThat(entries.first()).isEqualTo(GridEntry.LikelyDivider(2))
  }

  @Test
  fun leChiaviSonoUnicheEStabili() {
    val a = SearchGridEntries.build(results(intArrayOf(3, 1), intArrayOf(20, 21)))
    val b = SearchGridEntries.build(results(intArrayOf(3, 1), intArrayOf(20, 21)))
    assertThat(a.map { it.key }).containsNoDuplicates()
    assertThat(a.map { it.key }).isEqualTo(b.map { it.key })
  }

  @Test
  fun ilViewerScorreSicuriEPoiMenoProbabiliNellOrdineMostrato() {
    val view = SearchGridEntries.openingView(results(intArrayOf(9, 4), intArrayOf(15, 12)))
    val ids = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    assertThat(ids).containsExactly(lib.id(9), lib.id(4), lib.id(15), lib.id(12)).inOrder()
  }
}
