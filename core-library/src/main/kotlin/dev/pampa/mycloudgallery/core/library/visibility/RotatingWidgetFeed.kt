package dev.pampa.mycloudgallery.core.library.visibility

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La sorgente del widget (D22, [WidgetFeed]): gira sulle celle dell'istantanea di [visibility] (che i nascosti non li ha, E-H1), dalla piu'
 * recente, e a ogni chiamata da' i prossimi [WidgetFeed.next] media da dove era arrivata. **Mai un nascosto**: prima di restituire ogni blocco
 * chiede a [visibility] quali sono ancora visibili e a [mask] quali sono nascosti **adesso** (un media nascosto un attimo fa non deve comparire
 * in un widget che si ridisegna), e tiene solo chi passa tutti e due. Il filtro e' l'ultimo passo prima della risposta.
 *
 * Una cella che il filtro scarta non occupa un posto: si guarda la successiva, fino a riempire la richiesta o a fare un giro intero. Un errore
 * di lettura da' la lista vuota: nel dubbio il widget non mostra niente (fallire chiusi). Sicura per piu' chiamate insieme (il widget puo'
 * avere piu' istanze): una alla volta.
 *
 * Il widget di prova della Fase 7 (solo debug) e quello vero della Fase 10 leggono solo da qui.
 */
class RotatingWidgetFeed(
  private val visibility: VisibleMedia,
  private val mask: HiddenMask,
) : WidgetFeed {

  private val lock = Mutex()
  private var cursor = 0

  override suspend fun next(size: Int): List<MediaId> {
    if (size <= 0) return emptyList()
    return lock.withLock {
      try {
        pick(size.coerceAtMost(WidgetFeed.MAX_SIZE))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG, e) { "Sorgente del widget non riuscita: niente da mostrare." }
        emptyList()
      }
    }
  }

  private suspend fun pick(want: Int): List<MediaId> {
    val snapshot = visibility.snapshot.value ?: return emptyList()
    val total = snapshot.size
    if (total == 0) return emptyList()
    val goal = want.coerceAtMost(total)
    val start = cursor % total
    val picked = ArrayList<MediaId>(goal)
    var consumed = 0
    while (picked.size < goal && consumed < total) {
      val batch = (goal - picked.size).coerceAtLeast(MIN_BATCH).coerceAtMost(total - consumed)
      val candidates = List(batch) { snapshot.idAt((start + consumed + it) % total) }
      val hiddenNow = mask.hiddenAmong(candidates)
      val visibleNow = visibility.visible(candidates)
      for (id in candidates) {
        if (picked.size == goal) break
        consumed++
        if (id !in hiddenNow && id in visibleNow) picked += id
      }
    }
    cursor = (start + consumed) % total
    return picked
  }

  private companion object {
    const val TAG = "WidgetFeed"

    /** Almeno questo numero di celle per interrogazione: poche domande al catalogo anche quando molte celle vengono scartate. */
    const val MIN_BATCH = 8
  }
}
