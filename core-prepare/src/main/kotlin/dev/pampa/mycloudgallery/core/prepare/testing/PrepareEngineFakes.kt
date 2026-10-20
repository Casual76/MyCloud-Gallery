package dev.pampa.mycloudgallery.core.prepare.testing

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.contract.PrepAttempts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepClock
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepRecordSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepVerdict
import dev.pampa.mycloudgallery.core.prepare.video.VideoFrameGrabber
import dev.pampa.mycloudgallery.core.prepare.video.VideoFrameGrabberFactory
import dev.pampa.mycloudgallery.core.prepare.video.VideoInput
import dev.pampa.mycloudgallery.core.prepare.video.VideoStreamInfo
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// I fake della preparazione vera (P6): in src/main per i test di P11 e della Fase 5. Mai nella release (tools/check.ps1).

/** I decoder di un dispositivo finto: l'elenco dei tipi MIME che sa decodificare. */
class FakeCodecs(vararg mimes: String) : CodecAvailability {
  private val available = mimes.map { it.lowercase() }.toSet()

  override fun hasDecoder(mime: String): Boolean = mime.lowercase() in available

  companion object {
    /** Un dispositivo che sa decodificare tutto cio' che serve ai test (HEVC, AVC, AV1, VP9). */
    fun everything(): FakeCodecs = FakeCodecs("video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9", "video/mp4v-es", "video/3gpp")

    /** Un dispositivo senza nessun decoder video. */
    fun none(): FakeCodecs = FakeCodecs()
  }
}

/** Contatore di tentativi falliti in memoria (E-M10): cinque e poi ci si arrende. */
class InMemoryPrepAttempts : PrepAttempts {
  private val counts = ConcurrentHashMap<String, Int>()

  override suspend fun failed(share: String, item: WorkItem, code: PrepFailCode): Int =
    counts.merge("$share/${item.contentKey.hex}", 1) { a, b -> a + b } ?: 1

  fun countOf(share: String, item: WorkItem): Int = counts["$share/${item.contentKey.hex}"] ?: 0
}

/** Le regole del giro, a copione: i verdetti si consumano uno per volta, l'ultimo si ripete. */
class ScriptedPrepControl(vararg verdicts: PrepVerdict) : PrepControl {
  private val queue = CopyOnWriteArrayList(verdicts.toList().ifEmpty { listOf(PrepVerdict.Continue) })
  val asked = java.util.concurrent.atomic.AtomicInteger()

  override suspend fun verdict(): PrepVerdict {
    asked.incrementAndGet()
    synchronized(queue) {
      return if (queue.size > 1) queue.removeAt(0) else queue[0]
    }
  }
}

/** Un orologio monotono mosso a mano (o dalle pause del test). */
class ManualPrepClock(startNanos: Long = 0) : PrepClock {
  private val now = AtomicLong(startNanos)

  override fun nanos(): Long = now.get()

  fun advanceMillis(ms: Long) {
    now.addAndGet(ms * 1_000_000)
  }
}

/** I record base da una mappa (la chiave e' la chiave di contenuto): il catalogo finto. Letto da piu' media insieme, quindi una mappa concorrente. */
class MapPrepRecords(val records: MutableMap<String, MetaFile> = ConcurrentHashMap()) : PrepRecordSource {
  override suspend fun baseFor(share: String, item: WorkItem): MetaFile? = records[item.contentKey.hex]
}

/**
 * Un lettore di fotogrammi finto: un video descritto da [info] e fotogrammi prodotti da [frame] (null = non decodificabile).
 * Registra gli istanti chiesti. Serve a provare la logica dei fotogrammi senza un decoder; il vero `MediaMetadataRetriever`
 * passa da `MediaMetadataFrameGrabbers`.
 */
class FakeVideoFrameGrabbers(
  var info: VideoStreamInfo,
  var frame: (timeMs: Long, maxSide: Int) -> Bitmap?,
) : VideoFrameGrabberFactory {
  /** Se non e' null, aprire il video lancia questa eccezione (un contenitore che non si apre). */
  var openFailure: RuntimeException? = null

  val opened = CopyOnWriteArrayList<VideoInput>()
  val requested = CopyOnWriteArrayList<Long>()

  override fun open(input: VideoInput): VideoFrameGrabber {
    opened += input
    openFailure?.let { throw it }
    return object : VideoFrameGrabber {
      override val info: VideoStreamInfo get() = this@FakeVideoFrameGrabbers.info

      override fun frameAt(timeMs: Long, maxSide: Int): Bitmap? {
        requested += timeMs
        return frame(timeMs, maxSide)
      }

      override fun close() = Unit
    }
  }
}
