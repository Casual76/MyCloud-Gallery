package dev.pampa.mycloudgallery.integration

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagCodes
import dev.pampa.mycloudgallery.core.mediastore.diagnostics.PhoneLibraryDiagCodes
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.gallery.LibraryDiagCodes
import org.junit.Test

/**
 * P9 (nota di P8 in docs/decisioni.md): i contributori di rete e di fiducia che `:app` aggiunge alla Diagnostica
 * (`GraphModule`) usano solo codici del catalogo, cosi' ogni loro voce ha il suo titolo, il "cosa fare" e l'azione.
 *
 * P11: lo stesso per la galleria del telefono (`GalleryModule`): i codici di `core-mediastore` e quelli di `:app`. Il
 * catalogo li scrive come letterali (feature-diagnostics non vede core-mediastore): qui si controlla che id **ed edge
 * case** coincidano.
 */
class DiagnosticsWiringTest {

  @Test
  fun iCodiciDeiContributoriCollegatiSonoTuttiNelCatalogo() {
    val all = NetworkDiagCodes.ALL + TrustDiagCodes.ALL + PhoneLibraryDiagCodes.ALL + LibraryDiagCodes.ALL
    val fuori = all.filterNot { it in DiagCatalog }.map { it.id }
    assertWithMessage("codici dei contributori fuori dal catalogo: $fuori").that(fuori).isEmpty()
  }

  @Test
  fun iCodiciDellaGalleriaHannoGliStessiEdgeCaseDelCatalogo() {
    for (code in PhoneLibraryDiagCodes.ALL + LibraryDiagCodes.ALL) {
      val entry = DiagCatalog.entry(code)
      assertWithMessage(code.id).that(entry).isNotNull()
      assertWithMessage(code.id).that(entry!!.code.edgeCases).isEqualTo(code.edgeCases)
    }
  }
}
