package dev.pampa.mycloudgallery.integration

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagCodes
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import org.junit.Test

/**
 * P9 (nota di P8 in docs/decisioni.md): i contributori di rete e di fiducia che `:app` aggiunge alla Diagnostica
 * (`GraphModule`) usano solo codici del catalogo, cosi' ogni loro voce ha il suo titolo, il "cosa fare" e l'azione.
 */
class DiagnosticsWiringTest {

  @Test
  fun iCodiciDeiContributoriCollegatiSonoTuttiNelCatalogo() {
    val fuori = (NetworkDiagCodes.ALL + TrustDiagCodes.ALL).filterNot { it in DiagCatalog }.map { it.id }
    assertWithMessage("codici dei contributori fuori dal catalogo: $fuori").that(fuori).isEmpty()
  }
}
