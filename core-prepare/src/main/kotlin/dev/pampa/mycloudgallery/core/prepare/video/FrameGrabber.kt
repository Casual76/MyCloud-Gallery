package dev.pampa.mycloudgallery.core.prepare.video

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import dev.pampa.mycloudgallery.core.metadata.extract.QuietMediaDataSource
import dev.pampa.mycloudgallery.core.metadata.extract.VideoMetadataParsing
import java.io.File
import kotlin.math.max
import kotlin.math.min

// I FOTOGRAMMI di un video (§2.4): `MediaMetadataRetriever` su una `MediaDataSource` (sopra la cache a blocchi) o su un file
// locale. Il sistema decide da solo quali byte leggere: un fotogramma chiave per istante, mai il file intero.

/** Da dove il sistema legge il video. */
sealed interface VideoInput {
  /** Una sorgente a intervalli (di solito la cache a blocchi sopra `MediaBytes`). */
  class Source(val dataSource: MediaDataSource) : VideoInput

  /** Un file locale (un temporaneo, o il file del telefono nella Fase 5). */
  class LocalFile(val file: File) : VideoInput
}

/**
 * Cio' che il contenitore dice del flusso, come lo legge il sistema. Larghezza e altezza sono quelle **memorizzate** (prima
 * della rotazione), come `METADATA_KEY_VIDEO_WIDTH` e `..._HEIGHT`; [rotation] sono i gradi orari da applicare per mostrarlo dritto.
 */
class VideoStreamInfo(val durationMs: Long?, val width: Int?, val height: Int?, val rotation: Int)

/** Un video aperto: i suoi dati e i suoi fotogrammi. Bloccante, da un thread di lavoro. */
interface VideoFrameGrabber : AutoCloseable {
  val info: VideoStreamInfo

  /**
   * Il fotogramma chiave piu' vicino a [timeMs], ridotto per stare in un quadrato di [maxSide] px (la proporzione resta quella
   * del video). Null se il sistema non lo sa decodificare. Il fotogramma puo' arrivare **non ruotato** (dipende dal decoder
   * e dalla versione): la rotazione si corregge in [FrameOrientation].
   */
  fun frameAt(timeMs: Long, maxSide: Int): Bitmap?
}

/** Come si apre un video. Lancia `RuntimeException` se il contenitore non si apre. */
fun interface VideoFrameGrabberFactory {
  fun open(input: VideoInput): VideoFrameGrabber
}

/** L'adattatore vero: `MediaMetadataRetriever` con `OPTION_CLOSEST_SYNC` (il fotogramma chiave, senza decodificare i successivi). */
object MediaMetadataFrameGrabbers : VideoFrameGrabberFactory {
  override fun open(input: VideoInput): VideoFrameGrabber {
    val retriever = MediaMetadataRetriever()
    try {
      when (input) {
        // Mai un'eccezione verso il codice nativo (stack trace su System.err in release): la sorgente non lancia.
        is VideoInput.Source -> retriever.setDataSource(QuietMediaDataSource(input.dataSource))
        is VideoInput.LocalFile -> retriever.setDataSource(input.file.path)
      }
      return RetrieverGrabber(retriever)
    } catch (e: Throwable) {
      runCatching { retriever.release() }
      throw e
    }
  }

  private class RetrieverGrabber(private val retriever: MediaMetadataRetriever) : VideoFrameGrabber {
    override val info: VideoStreamInfo = VideoStreamInfo(
      durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 },
      width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 },
      height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 },
      rotation = VideoMetadataParsing.normalizeRotation(
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
      ),
    )

    override fun frameAt(timeMs: Long, maxSide: Int): Bitmap? {
      val timeUs = timeMs.coerceAtLeast(0) * 1000
      // Mai oltre la taglia del video: un'anteprima piu' grande dell'originale e' solo piu' lenta.
      val storedLong = max(info.width ?: 0, info.height ?: 0)
      val box = if (storedLong > 0) min(maxSide, storedLong) else maxSide
      val scaled = try {
        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box)
      } catch (_: RuntimeException) {
        null
      }
      if (scaled != null) return scaled
      // Alcuni decoder non sanno scalare: si chiede il fotogramma intero e lo si riduce dopo.
      return try {
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
      } catch (_: RuntimeException) {
        null
      }
    }

    override fun close() {
      runCatching { retriever.release() }
    }
  }
}
