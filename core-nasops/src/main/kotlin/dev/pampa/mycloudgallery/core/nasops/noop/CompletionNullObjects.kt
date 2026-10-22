package dev.pampa.mycloudgallery.core.nasops.noop

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.OpId

/**
 * Fase 7 (P0, D29): nessun catalogo da avvisare (test, banco, NAS senza catalogo). Non fa niente e non fallisce: il motore si comporta come
 * oggi (la posizione nuova la scopre la scansione). E' il default del costruttore di `NasOpsEngine` quando P4 ci mette la chiamata.
 */
object NoMoveCompletionSink : MoveCompletionSink {
  override suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256) = Unit
}
