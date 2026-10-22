package dev.pampa.mycloudgallery.debugtools.eval

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SOLO debug (Fase 6, P9; DB-1): le miniature **locali** dello strumento di valutazione, dal caricatore della Libreria. Prima `thumbnail()` rispondeva sempre
 * null e la griglia era una distesa di riquadri grigi con il giorno: senza le foto non si possono segnare le attese delle 50 query di F54.
 *
 * Il caricatore (D5) lavora a **finestre**: chi chiede una miniatura la mette nell'insieme delle richieste volute e il caricatore carica tutto l'insieme; chi
 * smette di volerla (la cella esce dalla composizione, o la miniatura e' arrivata) la toglie. Cosi' le celle in vista non si annullano a vicenda (una finestra
 * da una richiesta sola le cancellerebbe tutte tranne l'ultima). Come in Cerca, un media solo sul NAS e' una richiesta "in arrivo": la sorgente del NAS legge
 * l'originale solo per il contenuto visibile e qui non lo e' mai (mai il NAS, D33); risponde con la replica locale o la miniatura provvisoria.
 *
 * @param refAt la sorgente della miniatura di una cella (`thumbRefAt`: il telefono se c'e', altrimenti il NAS).
 */
internal class EvalThumbnails(
  private val loader: ThumbnailLoader,
  private val snapshot: () -> TimelineSnapshot?,
  private val refAt: (TimelineSnapshot, Int) -> MediaRef?,
  private val waitMs: Long = WAIT_MS,
) {
  private val lock = Any()
  private val wanted = LinkedHashMap<ThumbKey, ThumbRequest>()

  /** La miniatura locale di [id], o null se il media non c'e', non ha una sorgente o non arriva in [waitMs]. */
  suspend fun of(id: MediaId): ImageBitmap? {
    val snapshot = snapshot() ?: return null
    val index = snapshot.indexOf(id)
    if (index < 0) return null
    val ref = refAt(snapshot, index) ?: return null
    val version = (snapshot as? ColumnarTimelineSnapshot)?.versionKeyAt(index) ?: 1L
    val request = ThumbRequest(ThumbKey(id, version, ThumbBucket.M), ref)
    loader.peek(request.key)?.let { return it }
    want(request)
    try {
      val state = loader.image(request.key, request.ref)
      // Si legge lo stato a brevi intervalli: fuori dalla composizione una scrittura di stato non sveglia nessun osservatore.
      return withTimeoutOrNull(waitMs) {
        var image = state.value
        while (image == null) {
          delay(POLL_MS)
          image = state.value
        }
        image
      }
    } finally {
      withContext(NonCancellable) { unwant(request) }
    }
  }

  private fun want(request: ThumbRequest) {
    synchronized(lock) {
      wanted[request.key] = request
      publish()
    }
  }

  private fun unwant(request: ThumbRequest) {
    synchronized(lock) {
      wanted.remove(request.key)
      publish()
    }
  }

  /** Le richieste del telefono sono "visibili", quelle solo-NAS "in arrivo" (replica locale, mai l'originale). Le ultime [MAX_WANTED] contano. */
  private fun publish() {
    val all = wanted.values.toList().takeLast(MAX_WANTED)
    val (local, replicaOnly) = all.partition { it.ref !is MediaRef.Nas }
    loader.window(local, replicaOnly)
  }

  private companion object {
    /** Quanto si aspetta una miniatura: la griglia mostra intanto il riquadro con il giorno. */
    const val WAIT_MS: Long = 6_000

    const val POLL_MS: Long = 40

    /** Una griglia a tre colonne compone qualche decina di celle: oltre, le piu' vecchie escono dalla finestra. */
    const val MAX_WANTED: Int = 90
  }
}
