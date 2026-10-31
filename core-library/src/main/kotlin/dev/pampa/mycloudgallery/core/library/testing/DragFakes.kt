package dev.pampa.mycloudgallery.core.library.testing

import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.contract.DragRequest
import dev.pampa.mycloudgallery.core.library.contract.MediaDragHandler
import kotlinx.coroutines.delay

/**
 * Il trascinamento finto (Fase 10, P0, §5.2): risponde con [answer] dopo [delayMs] (preparazioni pronte, rifiutate, lente). Un `Ready` vuole un
 * `ClipData` vero: lo costruisce il test (Robolectric) dentro [answer]. Registra le richieste. Il vero (`DocumentDragPayloads`, P6) passa la stessa
 * suite con il provider in memoria della Fase 5.
 */
class FakeMediaDragHandler(
  var delayMs: Long = 0,
  var answer: (DragRequest) -> DragPrepared = { DragPrepared.Refused(DragRefusal.UNAVAILABLE) },
) : MediaDragHandler {
  val requests: MutableList<DragRequest> = mutableListOf()

  override suspend fun prepare(request: DragRequest): DragPrepared {
    requests += request
    if (delayMs > 0) delay(delayMs)
    return answer(request)
  }
}
