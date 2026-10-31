package dev.pampa.mycloudgallery.core.library.noop

import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.contract.DragRequest
import dev.pampa.mycloudgallery.core.library.contract.MediaDragHandler

// Fase 10 (P0, D20): il trascinamento non legato. Ogni richiesta e' rifiutata: nessun URI esce dall'app finche' P6 e P13 non legano la porta.

/** Niente trascinamento. */
object NoMediaDragHandler : MediaDragHandler {
  override suspend fun prepare(request: DragRequest): DragPrepared = DragPrepared.Refused(DragRefusal.UNAVAILABLE)
}
