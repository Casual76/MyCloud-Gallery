package dev.pampa.mycloudgallery.core.metadata

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.NeighborOffsetsProvider
import dev.pampa.mycloudgallery.core.metadata.extract.Preview
import dev.pampa.mycloudgallery.core.metadata.extract.PreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.fixtures.Fixture
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers

/** Il fuso del telefono nelle fixture: Roma (docs/fase3-campo.md, "Condizioni di riferimento"). */
internal val ROME: ZoneId = ZoneId.of("Europe/Rome")

internal val PLACES = FakePlaces()

/**
 * Un decodificatore di anteprime finto: restituisce sempre la stessa immagine ([previewWidth] x [previewHeight], gradiente) o
 * fallisce. Distingue le due strade dell'estrattore: [decode] su byte (una miniatura incorporata) e [decode] su [MediaBytes]
 * (il ripiego in streaming).
 */
internal class FakePreviewDecoder(
  var fail: Boolean = false,
  /** Fallisce solo la strada dei byte (una miniatura che non si decodifica). */
  var failBytes: Boolean = false,
  var previewWidth: Int = 8,
  var previewHeight: Int = 6,
) : PreviewDecoder {
  var byteCalls = 0
    private set
  var streamCalls = 0
    private set
  val calls: Int get() = byteCalls + streamCalls
  var lastSize = 0
    private set
  var lastStreamSize = 0L
    private set

  override fun decode(data: ByteArray, maxSide: Int): Preview? {
    byteCalls++
    lastSize = data.size
    return if (fail || failBytes) null else preview()
  }

  override fun decode(bytes: MediaBytes, maxSide: Int): Preview? {
    streamCalls++
    lastStreamSize = bytes.size
    return if (fail) null else preview()
  }

  private fun preview(): Preview {
    val w = previewWidth
    val h = previewHeight
    val rgba = ByteArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
      val i = (y * w + x) * 4
      rgba[i] = (x * 30).toByte()
      rgba[i + 1] = (y * 40).toByte()
      rgba[i + 2] = 128.toByte()
      rgba[i + 3] = 255.toByte()
    }
    return Preview(w, h, rgba)
  }
}

internal fun testExtractor(
  probe: VideoProbe = FakeVideoProbe(null),
  preview: PreviewDecoder? = FakePreviewDecoder(),
  neighbors: NeighborOffsetsProvider? = null,
  nowMs: Long = FixtureCatalog.NOW_MS,
): DefaultMetadataExtractor = DefaultMetadataExtractor(
  geocoder = PLACES,
  zones = PLACES,
  videoProbe = probe,
  previewDecoder = preview,
  neighbors = neighbors,
  clock = { nowMs },
  blocking = Dispatchers.Unconfined,
)

internal fun Fixture.hints(): ExtractHints = ExtractHints(
  mime = mime,
  displayName = name,
  relativePath = folder,
  msDateTakenMs = msDateTakenMs,
  msWidth = null,
  msHeight = null,
  msOrientation = null,
  msDurationMs = null,
  scanZone = ROME,
  msDateModifiedMs = msDateModifiedMs,
)

internal suspend fun Fixture.extract(extractor: DefaultMetadataExtractor = testExtractor()): Extraction =
  extractor.extract(ByteArrayMediaBytes(bytes()), hints())

private val LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME

/** Le differenze fra cio' che il catalogo si aspetta e cio' che l'estrattore ha letto: vuoto = tutto corrisponde. */
internal fun Fixture.mismatches(actual: Extraction): List<String> {
  val out = ArrayList<String>()
  val e = expect
  val actualResult = when (actual) {
    is Extraction.Read -> "read"
    is Extraction.Corrupt -> "corrupt:${actual.code}"
    is Extraction.NotMedia -> "notmedia:${actual.code}"
    is Extraction.Unsupported -> "unsupported:${actual.code}"
  }
  if (actualResult != e.result) {
    out += "esito: atteso ${e.result}, letto $actualResult"
    return out
  }
  if (actual !is Extraction.Read) return out
  val m = actual.metadata
  fun <T> check(what: String, expected: T?, got: T?) {
    if (expected != null && expected != got) out += "$what: atteso $expected, letto $got"
  }
  check("tipo", e.kind, actual.kind.name)
  check("larghezza", e.width, m.width)
  check("altezza", e.height, m.height)
  check("orientamento EXIF", e.orientation, m.orientation.exif)
  val capture = m.captureTime
  if (capture == null) {
    out += "data: manca"
  } else {
    check("ora locale", e.local, LOCAL.format(capture.localDateTime))
    check("fuso (minuti)", e.offsetMinutes, capture.offsetMinutes)
    check("affidabilita'", e.confidence, capture.confidence.name)
    check("fonte della data", e.dateSource, capture.source.name)
  }
  if (e.place != null) {
    val got = m.place?.let { PLACES.label(it) } ?: "-"
    check("luogo", e.place, got)
  }
  if (e.traits != null) check("tratti", e.traits, m.traits.map { it.name }.sorted().joinToString(","))
  check("proporzione", e.aspectMilli, TimelineRules.aspectMilli(m))
  e.source?.let {
    check("sorgente", it, SourceClassifier.classify("external_primary", folder, name, null, mime).source.name)
  }
  when (e.motion) {
    null -> Unit
    "none" -> if (actual.motion != null || actual.kind.name == "MOTION_PHOTO") out += "motion: attesa nessuna"
    "declared" -> if (actual.motion != null || actual.kind.name != "MOTION_PHOTO") out += "motion: attesa dichiarata ma non trovata, letta ${actual.motion}"
    else -> {
      val range = actual.motion
      if (range == null) {
        out += "motion: attesa ${e.motion}, nessuna parte video"
      } else {
        check("lunghezza del video", e.motionLength, range.length)
        val data = bytes()
        if (e.motion == "GOOGLE_V1" || e.motion == "MICRO_VIDEO") check("fine del video", data.size.toLong(), range.endExclusive)
        val head = data.copyOfRange(range.start.toInt() + 4, range.start.toInt() + 8)
        if (String(head, Charsets.ISO_8859_1) != "ftyp") out += "motion: il video non comincia con ftyp"
      }
    }
  }
  return out
}

/**
 * Un file "enorme" che non esiste: i suoi primi byte sono [head] (un JPEG vero, con EXIF), gli ultimi due `FF D9`, il resto zeri.
 * Conta quanto si legge: la prova che l'estrattore non guarda mai tutto il file (200 MP = circa 60 MB, E-P4).
 */
internal class SparseJpegBytes(override val size: Long, private val head: ByteArray) : MediaBytes {
  var bytesRead = 0L
    private set
  var reads = 0
    private set
  var maxSingleRead = 0
    private set

  override suspend fun read(range: LongRange): ByteArray {
    val first = maxOf(0L, range.first)
    val last = minOf(size - 1, range.last)
    if (last < first) return ByteArray(0)
    val out = ByteArray((last - first + 1).toInt())
    for (i in out.indices) {
      val pos = first + i
      out[i] = when {
        pos < head.size -> head[pos.toInt()]
        pos == size - 2 -> 0xFF.toByte()
        pos == size - 1 -> 0xD9.toByte()
        else -> 0
      }
    }
    reads++
    bytesRead += out.size
    maxSingleRead = maxOf(maxSingleRead, out.size)
    return out
  }

  override fun dataSource(): MediaDataSource = error("non serve")
}
