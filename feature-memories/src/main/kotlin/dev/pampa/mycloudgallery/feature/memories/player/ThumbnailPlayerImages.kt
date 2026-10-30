package dev.pampa.mycloudgallery.feature.memories.player

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerImages
import dev.pampa.mycloudgallery.feature.memories.page.thumbRequestOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Le immagini della presentazione sopra le miniature della Libreria e l'anteprima locale (docs/fase9-progetto.md §2.4, D13, E-MEM14, E-MEM15):
 * **mai un'attesa**. [want] fa tre cose senza sospendere chi chiama: dice al caricatore delle miniature che l'elemento di adesso e' in vista e i
 * due successivi stanno per entrare (cosi' si decodificano **prima**, la miniatura grande `L`); lancia, per ognuno dei tre, la richiesta
 * dell'anteprima ad alta qualita' **locale** ([MemoriesThumbnails.preview]: se non c'e' adesso, null, e si resta sulla miniatura; mai il NAS, mai
 * la rete); toglie dalla memoria cio' che non serve piu'. La schermata disegna la migliore che c'e' (anteprima, poi miniatura grande, poi il
 * segnaposto `ThumbHash`) e **non aspetta nessuna delle tre**: una sorgente lenta o spenta lascia la presentazione al suo ritmo.
 *
 * **In memoria al piu' tre bitmap** a schermo intero (quella di adesso e le due successive: ~60 MB [stima] sul tablet). Ogni richiesta lenta si
 * annulla dopo [PREVIEW_WAIT_MS] o appena esce dalla finestra. Niente nei log.
 */
class ThumbnailPlayerImages(
  private val thumbnails: MemoriesThumbnails,
  private val scope: CoroutineScope,
  private val sidePx: Int = DEFAULT_SIDE_PX,
) : PlayerImages {

  private val previews = mutableStateMapOf<MediaId, ImageBitmap>()
  private val jobs = HashMap<MediaId, Job>()

  override fun want(snapshot: TimelineSnapshot, current: MediaId, next: List<MediaId>) {
    val window = listOf(current) + next.take(MAX_AHEAD)
    // 1. Il caricatore: l'elemento di adesso in vista, i successivi in arrivo.
    val visible = listOfNotNull(request(snapshot, current))
    val ahead = next.take(MAX_AHEAD).mapNotNull { request(snapshot, it) }
    thumbnails.loader.window(visible, ahead)
    // 2. L'anteprima locale ad alta qualita': in volo, mai attesa.
    val source = thumbnails.preview
    if (source != null) {
      for (media in window) {
        if (media in previews || jobs.containsKey(media)) continue
        val ref = thumbnails.refs.refAt(snapshot, snapshot.indexOf(media).takeIf { it >= 0 } ?: continue) ?: continue
        jobs[media] = scope.launch {
          val bitmap = try {
            withTimeoutOrNull(PREVIEW_WAIT_MS) { source.preview(ref, sidePx) }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            null
          }
          if (bitmap != null) previews[media] = bitmap
        }
      }
    }
    // 3. Fuori dalla finestra non si tiene niente.
    val keep = window.toSet()
    for (media in previews.keys.toList()) if (media !in keep) previews.remove(media)
    for ((media, job) in jobs.entries.toList()) {
      if (media !in keep) {
        job.cancel()
        jobs.remove(media)
      }
    }
  }

  override fun previewOf(media: MediaId): ImageBitmap? = previews[media]

  override fun clear() {
    for (job in jobs.values) job.cancel()
    jobs.clear()
    previews.clear()
    thumbnails.loader.window(emptyList(), emptyList())
  }

  private fun request(snapshot: TimelineSnapshot, media: MediaId) =
    snapshot.indexOf(media).takeIf { it >= 0 }?.let { thumbRequestOf(thumbnails, snapshot, it, ThumbBucket.L) }

  companion object {
    /** Quante immagini successive si preparano in anticipo (D13). */
    const val MAX_AHEAD: Int = 2

    /** Il lato lungo dell'anteprima chiesta alla sorgente locale. */
    const val DEFAULT_SIDE_PX: Int = 1_920

    /** Dopo questo tempo una anteprima che non arriva si lascia perdere (la miniatura resta). */
    const val PREVIEW_WAIT_MS: Long = 8_000
  }
}
