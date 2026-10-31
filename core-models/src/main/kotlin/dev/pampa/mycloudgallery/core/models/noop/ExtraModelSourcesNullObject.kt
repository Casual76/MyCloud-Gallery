package dev.pampa.mycloudgallery.core.models.noop

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ExtraModelSources
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.PinnedUrl

/** Fase 10 (P0, D29): nessun manifest, nessun indirizzo in piu'; restano NAS e indirizzi fissati del catalogo. */
object NoExtraModelSources : ExtraModelSources {
  override fun urls(id: ModelId, sha256: Sha256): List<PinnedUrl> = emptyList()
}
