package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import org.junit.Test

/**
 * I codici della Diagnostica della Fase 6 (§2.6) sono nel catalogo (riga P11 del §4.4): ogni codice dei contratti (`AiDiag`, `ModelDiag`,
 * `SearchDiag`, `AiIndexDiag`) ha la sua voce con lo **stesso id e gli stessi edge case**, nella sezione "Ricerca", con un titolo e, se e'
 * un avviso o un errore, il "cosa fare". Un codice nuovo in un contratto senza voce fa fallire il test.
 */
class Phase6DiagCatalogTest {

  @Test
  fun ogniCodiceDeiContrattiHaLaSuaVoce() {
    for (code in AiDiagnostics.CONTRACT_CODES) {
      val entry = DiagCatalog.entry(code)
      assertWithMessage("${code.id} nel catalogo").that(entry).isNotNull()
      entry!!
      assertWithMessage("${code.id}: stessi edge case").that(entry.code.edgeCases).isEqualTo(code.edgeCases)
      assertWithMessage("${code.id}: sezione").that(entry.section).isEqualTo(DiagSection.SEARCH)
      assertWithMessage("${code.id}: titolo").that(entry.titleRes).isNotEqualTo(0)
      if (entry.severity == DiagSeverity.WARNING || entry.severity == DiagSeverity.ERROR) {
        assertWithMessage("${code.id}: cosa fare").that(entry.guideRes).isNotEqualTo(0)
      }
    }
  }

  @Test
  fun iCodiciDellaRicercaNelCatalogoVengonoDaiContratti() {
    val contract = AiDiagnostics.CONTRACT_CODES.map { it.id }.toSet()
    val search = DiagCatalog.entriesOf(DiagSection.SEARCH).map { it.code.id }.toSet() - "SEARCH_STATE"
    assertWithMessage("voci della sezione Ricerca senza un codice nei contratti").that(search - contract).isEmpty()
  }
}
