package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.feature.search.testing.RecordingSearchActions
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import org.junit.Test

/** Fase 6 (P0): gli stati di esempio coprono i casi della pagina Ricerca e modelli (§1.5) e restano coerenti col catalogo. */
class SearchSamplesTest {
  @Test
  fun lEsempioDellaPaginaECoerente() {
    val state = SearchSamples.settingsIndexer
    assertThat(state.models.models.map { it.id }).containsExactlyElementsIn(ModelCatalog.ALL.map { it.id })
    assertThat(state.models.models.single { !it.removable }.id).isEqualTo(ModelCatalog.CUTOUT_MAGICTOUCH.id)
    // 12.300 su 15.000 = 82%: sotto il 98% l'avviso c'e' (D31).
    assertThat(state.coverage.warns).isTrue()
    assertThat(SearchCoverage.Partial(9_900, 10_000, emptyMap()).warns).isFalse()
    assertThat(state.textReading.needsConsent).isTrue()
  }

  @Test
  fun leAzioniSiRegistrano() {
    val actions = RecordingSearchActions()
    actions.openTextReadingActivation()
    actions.openSearchSettings()
    assertThat(actions.calls).containsExactly("activation", "settings").inOrder()
  }
}