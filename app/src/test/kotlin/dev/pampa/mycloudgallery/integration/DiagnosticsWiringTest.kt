package dev.pampa.mycloudgallery.integration

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagCodes
import dev.pampa.mycloudgallery.core.mediastore.diagnostics.PhoneLibraryDiagCodes
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.gallery.LibraryDiagCodes
import dev.pampa.mycloudgallery.core.index.contract.IndexDiag
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMediaDiag
import dev.pampa.mycloudgallery.core.prepare.contract.PrepDiag
import dev.pampa.mycloudgallery.nas.NasLibraryDiagCodes
import dev.pampa.mycloudgallery.nas.NasOpsDiagCodes
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

  /** P11: i codici della 4A dei moduli (indice, scansione, preparazione) e di :app (stato, emergenza, aree). */
  private val NAS_4A = IndexDiag.ALL + NasMediaDiag.ALL + PrepDiag.ALL + NasLibraryDiagCodes.ALL

  /** P16: i codici della 4B di :app (operazioni, cestino, manutenzione, dati personali). */
  private val NAS_4B = NasOpsDiagCodes.ALL

  @Test
  fun iCodiciDeiContributoriCollegatiSonoTuttiNelCatalogo() {
    val all = NetworkDiagCodes.ALL + TrustDiagCodes.ALL + PhoneLibraryDiagCodes.ALL + LibraryDiagCodes.ALL + NAS_4A + NAS_4B
    val fuori = all.filterNot { it in DiagCatalog }.map { it.id }
    assertWithMessage("codici dei contributori fuori dal catalogo: $fuori").that(fuori).isEmpty()
  }

  @Test
  fun iCodiciDellaGalleriaHannoGliStessiEdgeCaseDelCatalogo() {
    for (code in PhoneLibraryDiagCodes.ALL + LibraryDiagCodes.ALL + NAS_4A + NAS_4B) {
      val entry = DiagCatalog.entry(code)
      assertWithMessage(code.id).that(entry).isNotNull()
      assertWithMessage(code.id).that(entry!!.code.edgeCases).isEqualTo(code.edgeCases)
    }
  }
}
