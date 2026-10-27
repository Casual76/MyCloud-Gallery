package dev.pampa.mycloudgallery.core.metadata.extract

import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.metadata.contract.CaptureTimeInputs
import dev.pampa.mycloudgallery.core.metadata.contract.CaptureTimeResolver
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.FileNameDates
import dev.pampa.mycloudgallery.core.metadata.contract.GroupHint
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.contract.MotionFormat
import dev.pampa.mycloudgallery.core.metadata.contract.MotionPart
import dev.pampa.mycloudgallery.core.metadata.contract.NeighborOffsets
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.format.ExifFacts
import dev.pampa.mycloudgallery.core.metadata.format.GifScanner
import dev.pampa.mycloudgallery.core.metadata.format.ArrayAccess
import dev.pampa.mycloudgallery.core.metadata.format.BmpScanner
import dev.pampa.mycloudgallery.core.metadata.format.ImageScan
import dev.pampa.mycloudgallery.core.metadata.format.IsoBmff
import dev.pampa.mycloudgallery.core.metadata.format.JpegScanner
import dev.pampa.mycloudgallery.core.metadata.format.MotionCandidate
import dev.pampa.mycloudgallery.core.metadata.format.MotionCandidates
import dev.pampa.mycloudgallery.core.metadata.format.NameParts
import dev.pampa.mycloudgallery.core.metadata.format.PagedAccess
import dev.pampa.mycloudgallery.core.metadata.format.PngScanner
import dev.pampa.mycloudgallery.core.metadata.format.RandomAccess
import dev.pampa.mycloudgallery.core.metadata.format.Sniffed
import dev.pampa.mycloudgallery.core.metadata.format.Sniffer
import dev.pampa.mycloudgallery.core.metadata.format.TiffFile
import dev.pampa.mycloudgallery.core.metadata.format.TiffParser
import dev.pampa.mycloudgallery.core.metadata.format.WebpScanner
import dev.pampa.mycloudgallery.core.metadata.format.XmpFacts
import dev.pampa.mycloudgallery.core.metadata.format.tail
import dev.pampa.mycloudgallery.core.metadata.format.u32le
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.contract.TimeZoneLookup
import java.time.LocalTime
import kotlin.math.abs
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chi sa quali file del telefono sono "vicini" a uno che non ha il fuso: stessa cartella, stesso modello di
 * fotocamera, entro 6 ore, con fuso esatto (docs/fase3-progetto.md §2.2). Lo implementa chi ha il catalogo (P2); senza,
 * un'immagine senza fuso e senza GPS prende il fuso del telefono alla lettura.
 *
 * @param approxUtcMs un istante approssimato dello scatto, per cercare i vicini nel tempo.
 */
fun interface NeighborOffsetsProvider {
  suspend fun offsetsFor(relativePath: String, cameraModel: String?, approxUtcMs: Long): NeighborOffsets?
}

/**
 * L'estrattore vero (fase META di docs/fase3-progetto.md §2.1 e §2.2). Legge **solo testa, intestazioni e coda** del
 * file a intervalli (circa 160 KiB per una foto); solo quando manca una miniatura utilizzabile, un'immagine fino a
 * [MAX_FULL_DECODE_BYTES] si legge intera, **in streaming**, per il segnaposto sfocato; riconosce il formato dai byte; ricava dimensioni, orientamento (gli 8 valori EXIF o `irot`/`imir`),
 * data (tabella del §2.2), luogo, tratti, tipo definitivo, parte video di una motion photo e indizi di gruppo.
 *
 * **Mai un'eccezione per un file rovinato** (E-M10, E-M12): zero byte e falsi media sono `NotMedia`; un file troncato o
 * illeggibile e' `Corrupt`; un formato che non sappiamo leggere e' `Unsupported`. Solo l'annullamento della coroutine passa.
 *
 * Il lavoro bloccante (decodifica dell'anteprima, sonda del video) gira su [blocking]; il chiamante deve comunque stare
 * fuori dal thread principale.
 *
 * **Segnaposto sfocato (ThumbHash, al massimo 100 px): l'ordine delle fonti, dal meno costoso.**
 * 1. *Miniatura incorporata*, se c'e' ed e' decodificabile: la miniatura JPEG dell'IFD1 dell'EXIF (gia' in memoria con
 *    l'EXIF: nessuna lettura in piu'), oppure l'anteprima JPEG di un DNG (striscia unica di un IFD ridotto, fino a 1 MiB).
 *    Si usa **solo se la sua proporzione coincide con quella delle dimensioni vere** (entro il 4%, o un pixel a 100 px): le
 *    miniature EXIF sono spesso 4:3 anche per foto 16:9 o ritagliate, e un segnaposto con la forma sbagliata e' peggio di
 *    nessuno. Una miniatura che non si decodifica o non coincide passa al ripiego. Con le dimensioni vere ignote la
 *    miniatura si accetta (non c'e' prova contraria). Un ritaglio con la stessa proporzione non si riconosce.
 *    Un HEIC non offre questa strada: l'elemento `thmb` e' un flusso HEVC grezzo, non un file che BitmapFactory apra senza
 *    riscriverlo in un contenitore (se il suo EXIF ha una miniatura IFD1, vale come per il JPEG).
 * 2. *Ripiego*: decodifica completa dell'immagine **in streaming** ([PreviewDecoder.decode] su [MediaBytes]: `decodeStream`
 *    con `inSampleSize`, mai un `ByteArray` dell'intero file), solo se il file pesa al massimo [maxFullDecodeBytes]. E' la strada di
 *    PNG, WebP, GIF, BMP, HEIC e dei JPEG senza miniatura valida.
 * 3. Altrimenti nessun segnaposto (il campo e' `null` e la cella usa la miniatura di sistema).
 *
 * Conseguenza per E-M10: un JPEG, PNG, GIF, WebP o BMP che nessun decodificatore apre diventa `Corrupt(DECODE_FAILED)` **solo se
 * si arriva al ripiego**; con una miniatura valida la decodifica dell'immagine vera non avviene qui e un danno nei dati compressi
 * non si vede (i tagli si riconoscono comunque dalla struttura). Costo stimato per 10 mila foto: vedi docs/fase3-campo.md §11.
 *
 * @param previewDecoder per il segnaposto sfocato; null = nessun segnaposto.
 * @param neighbors i fusi dei file vicini; null = nessun vicino.
 * @param clock l'orologio di riferimento per le date assurde (E-M9, E-P9).
 * @param maxFullDecodeBytes il peso massimo di un'immagine per il ripiego di decodifica completa (vedi sopra); oltre, o con 0, il
 *   segnaposto viene solo da una miniatura incorporata valida.
 */
class DefaultMetadataExtractor(
  private val geocoder: ReverseGeocoder,
  private val zones: TimeZoneLookup,
  private val videoProbe: VideoProbe,
  private val previewDecoder: PreviewDecoder? = null,
  private val neighbors: NeighborOffsetsProvider? = null,
  private val clock: () -> Long = System::currentTimeMillis,
  private val blocking: CoroutineDispatcher = Dispatchers.Default,
  private val maxFullDecodeBytes: Long = MAX_FULL_DECODE_BYTES,
) : MetadataExtractor {

  override suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction {
    if (bytes.size <= 0L) return Extraction.NotMedia(ExtractCode.ZERO_BYTES)
    return try {
      read(bytes, hints)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Errori di I/O, byte che non tornano, un nostro errore su un file strano: per il catalogo e' un file rovinato.
      Extraction.Corrupt(ExtractCode.DECODE_FAILED)
    }
  }

  private suspend fun read(bytes: MediaBytes, hints: ExtractHints): Extraction {
    val ra = PagedAccess(bytes)
    val head = ra.read(0, HEAD_BYTES)
    val sniffed = Sniffer.sniff(head, hints.mime)
    return when (sniffed.type) {
      ContainerType.UNKNOWN -> unrecognized(hints)
      ContainerType.MP4, ContainerType.MATROSKA, ContainerType.AVI -> readVideo(bytes, ra, sniffed, hints)
      else -> readImage(bytes, ra, head, sniffed, hints)
    }
  }

  /** Byte iniziali che non sono di un media: un falso `.jpg` e' `NotMedia`, un formato multimediale che non conosciamo e' `Unsupported`. */
  private fun unrecognized(hints: ExtractHints): Extraction {
    val mime = hints.mime?.lowercase()
    val declaresMedia = mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))
    return if (declaresMedia && mime !in Sniffer.SNIFFABLE_MIMES) {
      Extraction.Unsupported(ExtractCode.UNSUPPORTED_FORMAT)
    } else {
      Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE)
    }
  }

  // ---- immagini ------------------------------------------------------------------------------------------------

  private suspend fun readImage(bytes: MediaBytes, ra: RandomAccess, head: ByteArray, sniffed: Sniffed, hints: ExtractHints): Extraction {
    var tiffFile: TiffFile? = null
    val scan: ImageScan = when (sniffed.type) {
      ContainerType.JPEG -> JpegScanner.scan(ra)
      ContainerType.PNG -> PngScanner.scan(ra)
      ContainerType.WEBP -> WebpScanner.scan(ra)
      ContainerType.GIF -> GifScanner.scan(head)
      ContainerType.BMP -> BmpScanner.scan(head, ra.size)
      ContainerType.HEIF -> IsoBmff.scanHeif(ra)
      ContainerType.TIFF -> {
        tiffFile = TiffParser.parse(ra) ?: return Extraction.Corrupt(ExtractCode.DECODE_FAILED)
        ImageScan(truncated = tiffFile.declaredDataEnd() > ra.size)
      }
      else -> return Extraction.Corrupt(ExtractCode.DECODE_FAILED)
    }
    if (scan.truncated) return Extraction.Corrupt(ExtractCode.TRUNCATED)
    if (scan.malformed) return Extraction.Corrupt(ExtractCode.DECODE_FAILED)

    val xmp = XmpFacts.parse(scan.xmp)
    if (sniffed.type == ContainerType.JPEG && !scan.jpegEndFound && !scan.trailerEvidence && !xmp.declaresMotion) {
      // Un JPEG che non finisce con l'EOI e non ha nulla di aggiunto in coda: tagliato.
      return Extraction.Corrupt(ExtractCode.TRUNCATED)
    }

    if (tiffFile == null && scan.exifTiff != null) tiffFile = TiffParser.parse(ArrayAccess(scan.exifTiff))
    val exif = tiffFile?.let { ExifFacts.read(it, withThumbnail = previewDecoder != null) } ?: ExifFacts.EMPTY

    val width = scan.width ?: exif.declaredSize?.first ?: hints.width?.takeIf { it > 0 }
    val height = scan.height ?: exif.declaredSize?.second ?: hints.height?.takeIf { it > 0 }
    // HEIF/AVIF: le proprieta' `irot` e `imir` sono l'unica fonte (i decodificatori le applicano e ignorano l'Orientation dell'EXIF,
    // che le fotocamere scrivono in piu' e a volte contraddice: contarlo due volte ruoterebbe due volte). Per gli altri formati
    // vale l'EXIF e, in mancanza, la rotazione del MediaStore.
    val orientation = if (sniffed.type == ContainerType.HEIF) {
      scan.heifOrientation ?: ExifOrientation.NORMAL
    } else {
      exif.orientation?.let { ExifOrientation.fromExif(it) }
        ?: hints.orientation?.let { rotationOnly(it) }
        ?: ExifOrientation.NORMAL
    }

    val isRawFile = sniffed.type == ContainerType.TIFF && (exif.isDng || hasRawHint(hints))
    val mime = when {
      sniffed.type == ContainerType.TIFF && exif.isDng -> "image/x-adobe-dng"
      else -> sniffed.mime ?: hints.mime
    }

    // Motion photo: dichiarata da XMP o dal trailer SEF, poi cercata e verificata.
    var motion: ByteRange? = null
    var isMotionPhoto = false
    if (sniffed.type == ContainerType.JPEG || sniffed.type == ContainerType.HEIF) {
      val candidates = MotionCandidates.of(xmp, ra.tail(TAIL_BYTES), ra.size)
      isMotionPhoto = xmp.declaresMotion || candidates.any { it.part.format == MotionFormat.SAMSUNG_SEF }
      for (candidate in candidates) {
        val confirmed = confirm(ra, candidate)
        if (confirmed != null) {
          motion = confirmed.videoRange
          isMotionPhoto = true
          break
        }
      }
    }

    val kind = when {
      isRawFile -> MediaKind.RAW_PHOTO
      sniffed.type == ContainerType.GIF || scan.animated -> MediaKind.ANIMATED_IMAGE
      isMotionPhoto -> MediaKind.MOTION_PHOTO
      else -> MediaKind.PHOTO
    }

    // Tratti.
    val traits = LinkedHashSet<MediaTrait>()
    traits += SourceClassifier.classify("", hints.relativePath, hints.displayName, null, hints.mime).traits
    val sphere = xmp.isFullSphere(width, height)
    if (sphere) traits += MediaTrait.SPHERE
    val shownRatio = if (width != null && height != null) {
      if (orientation.swapsSides) height.toDouble() / width else width.toDouble() / height
    } else {
      null
    }
    if (!sphere && (xmp.hasGPano || (shownRatio != null && shownRatio >= PANORAMA_RATIO))) traits += MediaTrait.PANORAMA
    if (xmp.gainMap) traits += MediaTrait.ULTRA_HDR

    // Segnaposto sfocato: miniatura incorporata se valida, altrimenti decodifica completa in streaming (vedi il KDoc della classe).
    var thumbHash: ThumbHash? = null
    val decoder = previewDecoder
    if (decoder != null) {
      var preview: Preview? = null
      val embedded = exif.thumbnail
      if (embedded != null && embedded.isNotEmpty()) {
        val candidate = withContext(blocking) { decoder.decode(embedded, PREVIEW_SIDE) }
        if (candidate != null && aspectMatches(candidate, width, height)) preview = candidate
      }
      if (preview == null && ra.size <= maxFullDecodeBytes) {
        preview = withContext(blocking) { decoder.decode(bytes, PREVIEW_SIDE) }
        // Un JPEG, PNG, GIF, WebP o BMP intero che nessun decodificatore apre e' rovinato (E-M10); per HEIC e DNG la mancanza di
        // un codec non e' colpa del file.
        val decodable = sniffed.type != ContainerType.TIFF && sniffed.type != ContainerType.HEIF
        if (preview == null && decodable) return Extraction.Corrupt(ExtractCode.DECODE_FAILED)
      }
      if (preview != null) {
        val shown = preview.oriented(orientation)
        thumbHash = ThumbHashCodec.encode(shown.width, shown.height, shown.rgba)
      }
    }

    val dateInputs = CaptureTimeInputs(
      exifLocal = exif.local,
      exifOffsetMinutes = exif.offsetMinutes,
      exifSubSecondMs = exif.subSecondMs,
      gpsUtc = exif.gpsUtc,
      gps = exif.gps,
      fileName = FileNameDates.parse(hints.displayName),
      mediaStoreDateTakenMs = hints.dateTakenMs,
      fileModifiedMs = hints.dateModifiedMs,
      scanZone = hints.scanZone,
      nowMs = clock(),
    )
    val captureTime = CaptureTimeResolver.resolve(dateInputs, zones, neighborsFor(dateInputs, hints, exif.model))

    val metadata = MediaMetadata(
      mimeType = mime,
      width = width,
      height = height,
      orientationDegrees = orientation.degrees,
      durationMillis = null,
      captureTime = captureTime,
      location = exif.gps,
      mirrored = orientation.mirrored,
      traits = traits,
      place = placeOf(exif.gps),
      thumbHash = thumbHash,
    )
    val parts = NameParts.of(hints.displayName)
    val groupHint = GroupHint(
      baseName = parts.base,
      burstIndex = parts.burstIndex,
      isCover = parts.isCover,
      subSecondMs = exif.subSecondMs,
      cameraModel = exif.model,
    )
    return Extraction.Read(metadata, kind, motion, groupHint)
  }

  /**
   * La proporzione dell'anteprima coincide con quella dell'immagine vera? L'altezza attesa a quella larghezza non deve scostarsi di
   * piu' del 4% (o di un pixel: a 100 px l'arrotondamento pesa). Dimensioni ignote: si accetta.
   */
  private fun aspectMatches(preview: Preview, width: Int?, height: Int?): Boolean {
    if (width == null || height == null || width <= 0 || height <= 0) return true
    val expectedHeight = preview.width.toDouble() * height / width
    return abs(preview.height - expectedHeight) <= maxOf(1.0, ASPECT_TOLERANCE * expectedHeight)
  }

  private fun hasRawHint(hints: ExtractHints): Boolean {
    val ext = hints.displayName.substringAfterLast('.', "").lowercase()
    return ext in RAW_EXTENSIONS || hints.mime?.lowercase() in RAW_MIMES
  }

  /** Il video e' dove l'XMP o il trailer dicono? Si guardano i primi byte: un MP4 ha `ftyp` (o `moov`, `mdat`...) al secondo campo. */
  private suspend fun confirm(ra: RandomAccess, candidate: MotionCandidate): MotionPart? {
    val range = candidate.part.videoRange
    if (looksLikeVideo(ra, range.start)) return candidate.part
    val block = candidate.sefBlockStart ?: return null
    // SEF: il nome del blocco puo' non essere quello presunto; si legge la lunghezza vera.
    val header = ra.read(block, 8)
    if (header.size < 8) return null
    val nameLength = header.u32le(4)
    if (nameLength < 0 || nameLength > MAX_SEF_NAME) return null
    val start = block + 8 + nameLength
    if (start >= range.endExclusive || !looksLikeVideo(ra, start)) return null
    return MotionPart(ByteRange(start, range.endExclusive), candidate.part.format, candidate.part.stillPresentationUs)
  }

  private suspend fun looksLikeVideo(ra: RandomAccess, start: Long): Boolean {
    val first = ra.read(start, 16)
    return first.size >= 12 && Sniffer.sniff(first, null).type == ContainerType.MP4
  }

  // ---- video ---------------------------------------------------------------------------------------------------

  private suspend fun readVideo(bytes: MediaBytes, ra: RandomAccess, sniffed: Sniffed, hints: ExtractHints): Extraction {
    if (sniffed.type == ContainerType.MP4) {
      val top = IsoBmff.walkTopLevel(ra)
      // Una registrazione interrotta non ha `moov`: il file non si riproduce (E-M10).
      if (top.truncated) return Extraction.Corrupt(ExtractCode.TRUNCATED)
      if (!IsoBmff.videoLooksComplete(top)) return Extraction.Corrupt(ExtractCode.TRUNCATED)
    }
    val facts: VideoFacts? = try {
      withContext(blocking) { videoProbe.probe(bytes) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }

    val rotation = facts?.rotation ?: hints.orientation?.let { rotationOnly(it).degrees } ?: 0
    val orientation = ExifOrientation.of(((rotation % 360) + 360) % 360 / 90 * 90, false)
    val traits = LinkedHashSet<MediaTrait>()
    traits += SourceClassifier.classify("", hints.relativePath, hints.displayName, null, hints.mime).traits
    if (facts?.hdr == true) traits += MediaTrait.HDR_VIDEO

    val dateInputs = CaptureTimeInputs(
      videoUtcMs = facts?.utcMs,
      videoGps = facts?.iso6709,
      fileName = FileNameDates.parse(hints.displayName),
      mediaStoreDateTakenMs = hints.dateTakenMs,
      fileModifiedMs = hints.dateModifiedMs,
      scanZone = hints.scanZone,
      nowMs = clock(),
    )
    val captureTime = CaptureTimeResolver.resolve(dateInputs, zones, neighborsFor(dateInputs, hints, null))
    val duration = facts?.durationMs?.takeIf { it > 0 } ?: hints.durationMs?.takeIf { it >= 0 }
    val metadata = MediaMetadata(
      mimeType = sniffed.mime ?: hints.mime,
      width = hints.width?.takeIf { it > 0 },
      height = hints.height?.takeIf { it > 0 },
      orientationDegrees = orientation.degrees,
      durationMillis = duration,
      captureTime = captureTime,
      location = facts?.iso6709,
      mirrored = false,
      traits = traits,
      place = placeOf(facts?.iso6709),
      thumbHash = null,
    )
    return Extraction.Read(metadata, MediaKind.VIDEO, motion = null, groupHint = null)
  }

  // ---- comuni --------------------------------------------------------------------------------------------------

  /** I fusi dei vicini servono solo se il file non ha un fuso proprio (EXIF, GPS, luogo del video). */
  private suspend fun neighborsFor(inputs: CaptureTimeInputs, hints: ExtractHints, model: String?): NeighborOffsets? {
    val provider = neighbors ?: return null
    val gps = inputs.gps
    val videoGps = inputs.videoGps
    val hasOwnZone = if (inputs.exifLocal != null) {
      inputs.exifOffsetMinutes != null || inputs.gpsUtc != null || (gps != null && zones.zoneAt(gps) != null)
    } else {
      inputs.videoUtcMs != null && videoGps != null && zones.zoneAt(videoGps) != null
    }
    if (hasOwnZone) return null
    val approx = approximateUtc(inputs, hints) ?: return null
    return try {
      provider.offsetsFor(hints.relativePath, model, approx)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  private fun approximateUtc(inputs: CaptureTimeInputs, hints: ExtractHints): Long? {
    inputs.exifLocal?.let { return ZonedDateTime.of(it, hints.scanZone).toInstant().toEpochMilli() }
    inputs.videoUtcMs?.let { return it }
    inputs.fileName?.let { fn ->
      val local = fn.date.atTime(fn.time ?: LocalTime.NOON)
      return if (fn.isUtc) local.toInstant(ZoneOffset.UTC).toEpochMilli() else ZonedDateTime.of(local, hints.scanZone).toInstant().toEpochMilli()
    }
    return hints.dateTakenMs ?: hints.dateModifiedMs
  }

  /** Il luogo, con la regola di [PlaceChoice] (la stessa del ricalcolo a un dataset nuovo). */
  private fun placeOf(point: GeoPoint?): PlaceRef? = PlaceChoice.of(geocoder, point)

  /** Solo la rotazione in gradi (il MediaStore non conosce gli specchi); un valore non valido vale "dritta". */
  private fun rotationOnly(degrees: Int): ExifOrientation = when (((degrees % 360) + 360) % 360) {
    90 -> ExifOrientation.ROTATE_90
    180 -> ExifOrientation.ROTATE_180
    270 -> ExifOrientation.ROTATE_270
    else -> ExifOrientation.NORMAL
  }

  companion object {
    /** Le immagini fino a questa dimensione si leggono una volta per l'anteprima sfocata; oltre, solo la miniatura dell'EXIF. */
    const val MAX_FULL_DECODE_BYTES: Long = 16L * 1024 * 1024

    /** Il lato lungo dell'anteprima da cui si calcola il ThumbHash (il formato ne accetta al massimo 100). */
    const val PREVIEW_SIDE: Int = 100

    /** Scarto massimo di proporzione fra una miniatura incorporata e l'immagine vera (4%). */
    const val ASPECT_TOLERANCE: Double = 0.04

    private const val HEAD_BYTES = 64
    private const val TAIL_BYTES = 64 * 1024
    private const val MAX_SEF_NAME = 256L
    private const val PANORAMA_RATIO = 2.5

    private val RAW_EXTENSIONS = setOf("dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "pef", "srw", "raf")
    private val RAW_MIMES = setOf(
      "image/x-adobe-dng", "image/dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-sony-arw", "image/x-olympus-orf",
      "image/x-panasonic-rw2", "image/x-pentax-pef", "image/x-samsung-srw",
    )
  }
}
