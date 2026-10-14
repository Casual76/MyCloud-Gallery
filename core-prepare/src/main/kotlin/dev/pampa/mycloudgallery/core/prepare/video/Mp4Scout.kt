package dev.pampa.mycloudgallery.core.prepare.video

import dev.pampa.mycloudgallery.core.metadata.extract.VideoMetadataParsing

/**
 * Cio' che l'indice di un contenitore ISO (MP4, MOV, 3GP) dice del suo flusso video, letto **senza scaricare il file**.
 *
 * @property isoBmff il file ha la forma di un contenitore ISO (primo riquadro noto).
 * @property hasMoov l'indice c'e' ed e' intero. `isoBmff && !hasMoov` e' una registrazione interrotta o un file troncato
 *   (E-M10): non si apre nemmeno.
 * @property sampleEntry il marchio del primo flusso video (`avc1`, `hvc1`, `apv1`...): dice quale decoder serve.
 * @property width altezza e larghezza **memorizzate** (prima della rotazione del `tkhd`).
 * @property rotation 0, 90, 180, 270 gradi in senso orario da applicare per mostrarlo dritto.
 */
internal class ContainerFacts(
  val isoBmff: Boolean,
  val hasMoov: Boolean,
  val sampleEntry: String?,
  val width: Int?,
  val height: Int?,
  val rotation: Int,
  val durationMs: Long?,
) {
  companion object {
    val NOT_ISO = ContainerFacts(isoBmff = false, hasMoov = false, sampleEntry = null, width = null, height = null, rotation = 0, durationMs = null)
  }
}

/**
 * L'esploratore dell'indice (§2.4, E-M7): cammina i riquadri di primo livello saltando i `mdat` (poche letture da 16 byte,
 * anche in un file da 9 GB), trova `moov` (in coda per le registrazioni di Android) e lo legge **intero** una volta,
 * attraverso la stessa cache a blocchi del sistema: l'indice che serve a capire il codec e' lo stesso che poi
 * `MediaMetadataRetriever` chiede, e non si scarica due volte.
 *
 * Non decodifica niente e non si fida di niente: ogni lunghezza e' controllata prima di leggere, i riquadri malformati
 * danno `hasMoov = false`, mai un'eccezione (solo gli errori di lettura dell'origine arrivano al chiamante).
 */
internal object Mp4Scout {
  private val TOP_LEVEL = setOf("ftyp", "moov", "mdat", "free", "wide", "skip", "pnot", "styp", "uuid")
  private const val MAX_TOP_LEVEL_BOXES = 256

  suspend fun scout(cache: BlockCache, maxIndexBytes: Long): ContainerFacts {
    val size = cache.size
    if (size < 16) return ContainerFacts.NOT_ISO
    val first = cache.read(0, 16)
    if (first.size < 16 || fourcc(first, 4) !in TOP_LEVEL) return ContainerFacts.NOT_ISO

    var position = 0L
    var moovOffset = -1L
    var moovSize = 0L
    var moovHeader = 8
    var steps = 0
    // `ended`: la camminata e' arrivata a fine file o a un riquadro che non torna. Se invece si e' fermata per il tetto di
    // riquadri non si sa se l'indice c'e': lo decide il sistema, non lo dichiariamo mancante.
    var ended = false
    while (true) {
      if (position + 8 > size) {
        ended = true
        break
      }
      if (steps++ >= MAX_TOP_LEVEL_BOXES) break
      val header = cache.read(position, 16)
      if (header.size < 8) {
        ended = true
        break
      }
      var boxSize = u32(header, 0)
      val type = fourcc(header, 4)
      var headerSize = 8
      if (boxSize == 1L) {
        if (header.size < 16) {
          ended = true
          break
        }
        boxSize = u64(header, 8)
        headerSize = 16
      } else if (boxSize == 0L) {
        boxSize = size - position
      }
      if (boxSize < headerSize || boxSize < 0) {
        ended = true
        break
      }
      if (type == "moov") {
        moovOffset = position
        moovSize = boxSize
        moovHeader = headerSize
        break
      }
      position += boxSize
    }
    if (moovOffset < 0) return if (ended) noIndex() else UNKNOWN_INDEX
    // Un indice che finisce oltre il file e' un file troncato.
    if (moovOffset + moovSize > size) return noIndex()
    // Un indice enorme (ore di video a 60 fps) lo guarda il sistema; qui non si legge per intero.
    if (moovSize > maxIndexBytes) return UNKNOWN_INDEX
    val body = cache.read(moovOffset + moovHeader, (moovSize - moovHeader).toInt())
    if (body.size.toLong() != moovSize - moovHeader) return noIndex()
    return try {
      parseMoov(body)
    } catch (_: IndexOutOfBoundsException) {
      noIndex()
    }
  }

  /** C'e' un indice ma non lo leggiamo (troppo grande, o troppi riquadri per cercarlo): niente da dire, decide il sistema. */
  private val UNKNOWN_INDEX = ContainerFacts(isoBmff = true, hasMoov = true, sampleEntry = null, width = null, height = null, rotation = 0, durationMs = null)

  private fun noIndex() = ContainerFacts(isoBmff = true, hasMoov = false, sampleEntry = null, width = null, height = null, rotation = 0, durationMs = null)

  // --- moov ------------------------------------------------------------------------------------------------------

  private class Box(val type: String, val start: Int, val end: Int)

  private fun children(buf: ByteArray, from: Int, to: Int): List<Box> {
    val out = ArrayList<Box>()
    var p = from
    while (p + 8 <= to) {
      var size = u32(buf, p)
      val type = fourcc(buf, p + 4)
      var header = 8
      if (size == 1L) {
        if (p + 16 > to) break
        size = u64(buf, p + 8)
        header = 16
      } else if (size == 0L) {
        size = (to - p).toLong()
      }
      if (size < header || p + size > to) break
      out += Box(type, p + header, (p + size).toInt())
      p = (p + size).toInt()
    }
    return out
  }

  private fun parseMoov(body: ByteArray): ContainerFacts {
    val top = children(body, 0, body.size)
    var movieDurationMs: Long? = null
    top.firstOrNull { it.type == "mvhd" }?.let { movieDurationMs = timescaleDuration(body, it) }

    for (trak in top.filter { it.type == "trak" }) {
      val parts = children(body, trak.start, trak.end)
      val mdia = parts.firstOrNull { it.type == "mdia" } ?: continue
      val mdiaParts = children(body, mdia.start, mdia.end)
      val hdlr = mdiaParts.firstOrNull { it.type == "hdlr" } ?: continue
      if (hdlr.end - hdlr.start < 12 || fourcc(body, hdlr.start + 8) != "vide") continue

      var entry: String? = null
      mdiaParts.firstOrNull { it.type == "minf" }?.let { minf ->
        val stbl = children(body, minf.start, minf.end).firstOrNull { it.type == "stbl" }
        val stsd = stbl?.let { children(body, it.start, it.end).firstOrNull { b -> b.type == "stsd" } }
        // stsd: versione e flag (4), numero di voci (4), poi la prima voce: lunghezza (4) e marchio (4).
        if (stsd != null && stsd.end - stsd.start >= 16) entry = fourcc(body, stsd.start + 12)
      }

      var width: Int? = null
      var height: Int? = null
      var rotation = 0
      parts.firstOrNull { it.type == "tkhd" }?.let { tkhd ->
        val version = body[tkhd.start].toInt() and 0xFF
        val matrix = tkhd.start + if (version == 1) 52 else 40
        if (tkhd.end - tkhd.start >= matrix - tkhd.start + 44) {
          rotation = rotationOf(i32(body, matrix), i32(body, matrix + 4), i32(body, matrix + 12), i32(body, matrix + 16))
          val w = (u32(body, matrix + 36) shr 16).toInt()
          val h = (u32(body, matrix + 40) shr 16).toInt()
          if (w > 0 && h > 0) {
            width = w
            height = h
          }
        }
      }
      val trackDuration = mdiaParts.firstOrNull { it.type == "mdhd" }?.let { timescaleDuration(body, it) }
      return ContainerFacts(
        isoBmff = true,
        hasMoov = true,
        sampleEntry = entry,
        width = width,
        height = height,
        rotation = VideoMetadataParsing.normalizeRotation(rotation),
        durationMs = (movieDurationMs?.takeIf { it > 0 } ?: trackDuration),
      )
    }
    // Un indice senza flusso video: e' un file audio, o uno scheletro. Non e' un video che si possa preparare.
    return ContainerFacts(isoBmff = true, hasMoov = true, sampleEntry = null, width = null, height = null, rotation = 0, durationMs = movieDurationMs)
  }

  /** `mvhd` e `mdhd` hanno lo stesso schema per scala dei tempi e durata (v0: 32 bit, v1: 64 bit). */
  private fun timescaleDuration(buf: ByteArray, box: Box): Long? {
    val version = buf[box.start].toInt() and 0xFF
    val timescaleAt = box.start + if (version == 1) 20 else 12
    val durationAt = timescaleAt + 4
    val need = if (version == 1) 12 else 8
    if (timescaleAt + need > box.end) return null
    val timescale = u32(buf, timescaleAt)
    val duration = if (version == 1) u64(buf, durationAt) else u32(buf, durationAt)
    if (timescale <= 0 || duration < 0 || duration == 0xFFFFFFFFL) return null
    return duration * 1000 / timescale
  }

  /** La rotazione dalla matrice del `tkhd` (a, b, c, d in 16.16): quella che Android legge per `METADATA_KEY_VIDEO_ROTATION`. */
  fun rotationOf(a: Int, b: Int, c: Int, d: Int): Int {
    val one = 0x00010000
    return when {
      a == one && d == one -> 0
      b == one && c == -one -> 90
      a == -one && d == -one -> 180
      b == -one && c == one -> 270
      else -> 0
    }
  }

  // --- byte ------------------------------------------------------------------------------------------------------

  private fun u32(b: ByteArray, i: Int): Long =
    ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

  private fun i32(b: ByteArray, i: Int): Int = u32(b, i).toInt()

  private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)

  private fun fourcc(b: ByteArray, i: Int): String = String(b, i, 4, Charsets.ISO_8859_1)
}
