package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I codici della Diagnostica della Fase 7 (§2.8, P13) sono nel catalogo: ogni codice del contratto (`AlbumDiag`) ha la sua voce con lo **stesso id e gli
 * stessi edge case**, nella sezione "Album", con un titolo e il "cosa fare". Un codice nuovo nel contratto senza voce fa fallire il test; una voce
 * della sezione che non viene dal contratto (a parte lo stato) anche.
 */
class Phase7DiagCatalogTest {

  @Test
  fun ogniCodiceDelContrattoHaLaSuaVoce() {
    for (code in AlbumsDiagnostics.CONTRACT_CODES) {
      val entry = DiagCatalog.entry(code)
      assertWithMessage("${code.id} nel catalogo").that(entry).isNotNull()
      entry!!
      assertWithMessage("${code.id}: stessi edge case").that(entry.code.edgeCases).isEqualTo(code.edgeCases)
      assertWithMessage("${code.id}: sezione").that(entry.section).isEqualTo(DiagSection.ALBUMS)
      assertWithMessage("${code.id}: titolo").that(entry.titleRes).isNotEqualTo(0)
      assertWithMessage("${code.id}: cosa fare").that(entry.guideRes).isNotEqualTo(0)
    }
  }

  @Test
  fun iCodiciDegliAlbumNelCatalogoVengonoDalContratto() {
    val contract = AlbumsDiagnostics.CONTRACT_CODES.map { it.id }.toSet()
    val albums = DiagCatalog.entriesOf(DiagSection.ALBUMS).map { it.code.id }.toSet() - "ALBUMS_STATE"
    assertWithMessage("voci della sezione Album senza un codice nel contratto").that(albums - contract).isEmpty()
  }

  @Test
  fun primaCheGliAlbumSianoMontatiCeSoloLoStato() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val graph = AlbumsGraph(scope, { null }, MutableStateFlow(false))
    val items = AlbumsDiagnostics(graph).collect()
    assertThat(items.map { it.code.id }).containsExactly("ALBUMS_STATE")
    assertThat(items.single().severity).isEqualTo(DiagSeverity.INFO)
    scope.cancel()
  }

  @Test
  fun leRegoleLargheSonoQuelleDelleAlbumSmart() {
    assertThat(AlbumsDiagnostics.tooBroad(3_001, 100_000)).isTrue()
    assertThat(AlbumsDiagnostics.tooBroad(250, 1_000)).isTrue()
    assertThat(AlbumsDiagnostics.tooBroad(150, 1_000)).isFalse()
    assertThat(AlbumsDiagnostics.tooBroad(10, 0)).isFalse()
  }
}
