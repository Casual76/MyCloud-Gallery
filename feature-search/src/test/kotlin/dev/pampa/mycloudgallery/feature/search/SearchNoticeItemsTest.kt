package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import org.junit.Test

/**
 * Ogni avviso del contratto (`SearchNotice`, `SearchCoverage`, `SearchReadiness`) ha il suo messaggio e, dove la tabella del §2.6 ne ha una, la sua azione
 * (Fase 6, P8, E-Q6, E-Q11, E-I12). Il test percorre **tutti i casi** dei tipi sigillati: un caso nuovo senza messaggio non compila.
 */
class SearchNoticeItemsTest {

  private fun items(
    notices: List<SearchNotice> = emptyList(),
    coverage: SearchCoverage = SearchCoverage.Complete,
    readiness: SearchReadiness = SearchReadiness.Ready,
    model: ModelState? = null,
  ) = SearchNoticeItems.of(notices, coverage, readiness, model)

  @Test
  fun ognuCasoDelContrattoHaIlSuoMessaggioELaSuaAzione() {
    val samples = listOf(
      SearchNotice.PreparingContentSearch,
      SearchNotice.OnlyFiltersAndText,
      SearchNotice.NoIndexerForArea(commonArea = true),
      SearchNotice.NoIndexerForArea(commonArea = false),
      SearchNotice.TypoCorrected("screenshot", "screeshot"),
      SearchNotice.TextReadingOff,
      SearchNotice.PeopleRecognizing(60),
    )
    for (notice in samples) {
      val (item, action) = expected(notice)
      val shown = items(notices = listOf(notice)).single()
      assertThat(shown).isEqualTo(item)
      assertThat(shown.action).isEqualTo(action)
    }
  }

  /** Il messaggio e l'azione attesi per ogni caso: un `when` esaustivo, quindi un caso nuovo del contratto non compila finche' non ha il suo. */
  private fun expected(notice: SearchNotice): Pair<NoticeItem, NoticeAction?> = when (notice) {
    SearchNotice.PreparingContentSearch -> NoticeItem.Preparing to null
    SearchNotice.OnlyFiltersAndText -> NoticeItem.ModelMissing(ModelCatalog.QUERY_270M.sizeBytes, null) to NoticeAction.DOWNLOAD_MODEL
    is SearchNotice.NoIndexerForArea -> NoticeItem.NoIndexer(notice.commonArea) to NoticeAction.ENABLE_INDEXER
    is SearchNotice.TypoCorrected -> NoticeItem.Typo(notice.from, notice.to) to null
    SearchNotice.TextReadingOff -> NoticeItem.TextReadingOff to NoticeAction.LEARN_TEXT_READING
    is SearchNotice.PeopleRecognizing -> NoticeItem.PeopleProgress(notice.percent) to null
  }

  @Test
  fun laCoperturaSottoIl98ConPercheEQuellaSopraNonDiceNiente_E_Q6() {
    val partial = SearchCoverage.Partial(covered = 12_300, total = 15_000, reasons = mapOf(CoverageReason.PREPARATION to 2_700))
    assertThat(items(coverage = partial)).containsExactly(NoticeItem.CoveragePartial(12_300, 15_000))
    assertThat(NoticeItem.CoveragePartial(1, 2).action).isEqualTo(NoticeAction.WHY_COVERAGE)
    // 98% o piu': nessun avviso insistente.
    assertThat(items(coverage = SearchCoverage.Partial(14_700, 15_000, emptyMap()))).isEmpty()
    assertThat(items(coverage = SearchCoverage.Complete)).isEmpty()
  }

  @Test
  fun unAggiornamentoInCorsoDiceLaPercentualeSenzaAzione() {
    val shown = items(coverage = SearchCoverage.Updating(40)).single()
    assertThat(shown).isEqualTo(NoticeItem.CoverageUpdating(40))
    assertThat(shown.action).isNull()
  }

  @Test
  fun ilModelloAssenteSiDiceUnaVoltaSola_QualunqueSiaLaStrada_E_Q11() {
    val size = 123_456_789L
    val shown = items(
      notices = listOf(SearchNotice.OnlyFiltersAndText),
      coverage = SearchCoverage.ModelMissing(size),
      readiness = SearchReadiness.FiltersAndTextOnly(modelMissing = true),
    )
    assertThat(shown).containsExactly(NoticeItem.ModelMissing(size, null))
    assertThat(items(coverage = SearchCoverage.ModelMissing(size)).single()).isEqualTo(NoticeItem.ModelMissing(size, null))
    assertThat(items(readiness = SearchReadiness.FiltersAndTextOnly(modelMissing = true)).single())
      .isEqualTo(NoticeItem.ModelMissing(ModelCatalog.QUERY_270M.sizeBytes, null))
    // Motore guasto, modello presente: niente "Scarica".
    assertThat(items(readiness = SearchReadiness.FiltersAndTextOnly(modelMissing = false))).isEmpty()
  }

  @Test
  fun mentreIlModelloArrivaNonSiProponeDiScaricarlo() {
    val fetching = ModelState.Fetching(ModelSourceKind.HTTPS, 40_000_000, 164_626_432)
    val shown = items(notices = listOf(SearchNotice.OnlyFiltersAndText), model = fetching).single() as NoticeItem.ModelMissing
    assertThat(shown.fetching).isEqualTo(40_000_000L to 164_626_432L)
    assertThat(shown.action).isNull()
  }

  @Test
  fun ilMotoreCheSiCaricaDiceStoPreparando_E_Q11() {
    assertThat(items(readiness = SearchReadiness.PreparingContent)).containsExactly(NoticeItem.Preparing)
    // Detto dal motore e dallo stato insieme: una volta sola.
    assertThat(items(notices = listOf(SearchNotice.PreparingContentSearch), readiness = SearchReadiness.PreparingContent)).containsExactly(NoticeItem.Preparing)
  }

  @Test
  fun ilRefustoCorrettoDiceCosaSiEraScrittoENonHaAzioni() {
    val typo = items(notices = listOf(SearchNotice.TypoCorrected("esselunga", "eselunga"))).single() as NoticeItem.Typo
    assertThat(typo.searched).isEqualTo("esselunga")
    assertThat(typo.typed).isEqualTo("eselunga")
  }

  /**
   * CE-8: con il modello delle query pronto "serve il modello" non e' piu' vero, anche se i risultati in vista (o lo stato del motore) sono di prima che
   * finisse lo scaricamento. Prima l'avviso con "Scarica" restava finche' non si faceva un'altra ricerca.
   */
  @Test
  fun conIlModelloProntoLAvvisoServeIlModelloSiToglie_CE8() {
    val ready = ModelState.Ready(ModelCatalog.QUERY_270M.sizeBytes, ModelSourceKind.HTTPS, 1)
    assertThat(items(notices = listOf(SearchNotice.OnlyFiltersAndText), model = ready)).isEmpty()
    assertThat(items(coverage = SearchCoverage.ModelMissing(ModelCatalog.QUERY_270M.sizeBytes), model = ready)).isEmpty()
    assertThat(items(readiness = SearchReadiness.FiltersAndTextOnly(modelMissing = true), model = ready)).isEmpty()
    // Senza il modello pronto l'avviso c'e' come prima.
    assertThat(items(notices = listOf(SearchNotice.OnlyFiltersAndText), model = ModelState.Absent)).hasSize(1)
  }
}
