package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
import org.junit.Test

/**
 * La riga sopra la barra (H4): una sola, la cosa piu' importante da dire adesso; niente a riposo; «Niente per…» senza risultati, mai una pagina; il primo
 * avviso del motore o della copertura; per "Trova simili", da dove vengono i risultati.
 */
class SearchLinesTest {

  private val lib = library(count = 20)

  private fun results(notices: List<SearchNotice> = emptyList(), coverage: SearchCoverage = SearchCoverage.Complete, similar: Int? = null) =
    SearchScreenState.Results(emptyList(), lib.view(0, 1, 2), lib.view(), coverage, notices, similar)

  @Test
  fun aRiposoNienteRiga() {
    assertThat(SearchLines.of(SearchScreenState.Idle, SearchReadiness.Ready, null)).isNull()
    assertThat(SearchLines.of(results(), SearchReadiness.Ready, null)).isNull()
  }

  @Test
  fun senzaRisultatiUnaRigaColTestoCercato() {
    assertThat(SearchLines.of(SearchScreenState.NoResults("zebre", emptyList()), SearchReadiness.Ready, null)).isEqualTo(SearchLine.NoResults("zebre"))
    assertThat(SearchLines.of(SearchScreenState.NoResults(null, emptyList(), similar = 1), SearchReadiness.Ready, null)).isEqualTo(SearchLine.NoResults(null))
  }

  @Test
  fun ilPrimoAvvisoVinceELaRigaSiToccaSeHaUnAzione() {
    val line = SearchLines.of(results(listOf(SearchNotice.TypoCorrected("sardegna", "sardegan"), SearchNotice.TextReadingOff)), SearchReadiness.Ready, null)
    assertThat(line).isEqualTo(SearchLine.Notice(NoticeItem.Typo("sardegna", "sardegan")))
    assertThat(line!!.action).isNull()
    val coverage = SearchLines.of(results(coverage = SearchCoverage.Partial(12_300, 15_000, emptyMap())), SearchReadiness.Ready, null)
    assertThat(coverage).isEqualTo(SearchLine.Notice(NoticeItem.CoveragePartial(12_300, 15_000)))
    assertThat(coverage!!.action).isEqualTo(NoticeAction.WHY_COVERAGE)
  }

  @Test
  fun ilModelloCheMancaSiDiceUnaVoltaSolaEPoiLoScaricamento() {
    val missing = SearchLines.of(results(listOf(SearchNotice.OnlyFiltersAndText)), SearchReadiness.FiltersAndTextOnly(modelMissing = true), null)
    assertThat((missing as SearchLine.Notice).item).isInstanceOf(NoticeItem.ModelMissing::class.java)
    assertThat(missing.action).isEqualTo(NoticeAction.DOWNLOAD_MODEL)
    val ready = SearchLines.of(results(listOf(SearchNotice.OnlyFiltersAndText)), SearchReadiness.Ready, ModelState.Ready(100, dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind.NAS, 0))
    assertThat(ready).isNull()
  }

  @Test
  fun trovaSimiliDiceDaDoveVengonoIRisultati() {
    assertThat(SearchLines.of(results(similar = 3), SearchReadiness.Ready, null)).isEqualTo(SearchLine.Similar(3))
    // Un avviso conta di piu': i media della scelta non analizzati.
    assertThat(SearchLines.of(results(listOf(SearchNotice.SimilarWithoutVectors(1)), similar = 3), SearchReadiness.Ready, null))
      .isEqualTo(SearchLine.Notice(NoticeItem.SimilarWithoutVectors(1)))
  }

  @Test
  fun quandoNonSiPuoCercareLaRigaHaLaSuaAzione() {
    val empty = SearchLines.of(SearchScreenState.Unavailable(SearchUnavailableReason.EMPTY_LIBRARY), SearchReadiness.Ready, null)
    assertThat(empty!!.action).isEqualTo(NoticeAction.ALLOW_ACCESS)
    val error = SearchLines.of(SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR), SearchReadiness.Ready, null)
    assertThat(error!!.action).isEqualTo(NoticeAction.RETRY)
  }

  @Test
  fun aRiposoITreSuggerimentiSonoSenzaDoppioni() {
    val idle = dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions(
      listOf(recent(1, "Roma"), recent(2, "mare")),
      listOf(dev.pampa.mycloudgallery.core.search.contract.PlaceOption(setOf(1), "roma", dev.pampa.mycloudgallery.core.search.contract.PlaceLevel.PLACE)),
      listOf(dev.pampa.mycloudgallery.core.search.contract.SearchMediaType.VIDEO),
    )
    assertThat(SearchStates.idleTop(idle).map { it.label }).containsExactly("Roma", "mare", "Video").inOrder()
  }
}
