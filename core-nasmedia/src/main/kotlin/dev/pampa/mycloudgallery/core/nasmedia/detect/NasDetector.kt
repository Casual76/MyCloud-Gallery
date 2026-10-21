package dev.pampa.mycloudgallery.core.nasmedia.detect

import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetaResult
import dev.pampa.mycloudgallery.core.nasmedia.exclude.MediaTypes
import java.io.IOException
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

// Il RILEVAMENTO di un file del NAS (docs/fase4-progetto.md §2.3, "Altrimenti | rilevamento"; D17, E-M12): un'apertura,
// testa e coda da 64 KiB per l'impronta rapida `qh1`, i metadati con l'estrattore della Fase 3 sugli stessi byte, e la
// miniatura incorporata dell'EXIF come miniatura provvisoria.

/** L'esito di un rilevamento. [bytesRead] sono i byte letti dal NAS (anche se il file poi non e' un media). */
sealed interface Detection {
  val bytesRead: Long

  /**
   * Un media: [fingerprint] con l'`qh1` **letto qui** (D8: solo cosi' puo' servire a unire con un media del telefono),
   * [stamp] il timbro visto all'apertura (quello coerente con i byte letti), [meta] cio' che si e' saputo dei metadati
   * (`null` = non letti per il tetto di lettura del file: la preparazione li completera') e [provisionalJpeg] la
   * miniatura EXIF pronta da mostrare (con il suo orientamento), se c'e' ed e' attendibile.
   */
  class Media(
    val stamp: NasStamp,
    val fingerprint: Fingerprint,
    val meta: NasMetaResult?,
    val provisionalJpeg: ByteArray?,
    override val bytesRead: Long,
  ) : Detection {
    override fun toString(): String = "Detection.Media(size=${fingerprint.size}, ${provisionalJpeg?.size ?: 0} B miniatura)"
  }

  /** Zero byte o non e' un media (E-M12): contato, mai in Libreria. */
  class NotMedia(val code: ExtractCode, override val bytesRead: Long) : Detection

  /**
   * Non si e' riuscito a leggere adesso: file occupato dal PC (E-N13), cambiato o accorciato mentre lo leggevamo, errore
   * del NAS ([error]). Mai "corrotto": si riprova al giro dopo.
   */
  class Unreadable(val error: NasError?, override val bytesRead: Long) : Detection

  /** Il tetto di lettura del giro e' finito (D22): il file resta da rilevare. */
  class CapReached(override val bytesRead: Long) : Detection
}

/**
 * Rileva un file del NAS gia' aperto (`NasMediaReader.open`). Legge:
 * - la **testa** (i primi 64 KiB) e la **coda** (gli ultimi 64 KiB, piu' il resto della pagina che le contiene) per l'impronta
 *   `qh1`: due letture, anche su un video da 9 GB;
 * - quanto serve all'[extractor] oltre a quelle: per una foto qualche pagina da 64 KiB, per un video l'indice del
 *   contenitore (fino a 4 MiB). Ogni file ha un tetto ([IMAGE_LIMIT], [VIDEO_LIMIT]) e il giro un tetto di byte
 *   ([ReadBudget]).
 *
 * **L'estrattore per il NAS va costruito con `maxFullDecodeBytes = 0`** (`DefaultMetadataExtractor`): il ripiego di
 * decodifica completa per il segnaposto sfocato rileggerebbe l'intera immagine dal NAS. Se lo fa lo stesso, il tetto per
 * file lo ferma e il media viene registrato con i metadati "da completare" ([Detection.Media.meta] `null`) invece che
 * come rovinato.
 *
 * @param zone il fuso del telefono alla lettura (per le date senza fuso, E-P5).
 */
class NasDetector(
  private val extractor: MetadataExtractor,
  private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

  /**
   * @param name il nome del file; @param folder la cartella che lo contiene, relativa alla share (`null` = radice);
   * @param modifiedAt la data di modifica dall'elenco (ora del NAS), ultimo ripiego per la data di scatto (E-M8).
   */
  suspend fun detect(
    file: NasRandomFile,
    name: String,
    folder: String?,
    modifiedAt: Long,
    budget: ReadBudget? = null,
  ): Detection {
    val size = file.size
    if (size <= 0L) return Detection.NotMedia(ExtractCode.ZERO_BYTES, 0)

    val kind = MediaTypes.kindOf(name) ?: MediaKind.PHOTO
    val bytes = NasMediaBytes(file, budget, if (kind == MediaKind.VIDEO) VIDEO_LIMIT else IMAGE_LIMIT)
    val regions = QuickHashAlgorithm.regions(size)

    val ends: Ends? = try {
      readEnds(bytes, size, regions)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      return failed(bytes, e)
    }
    // Il file e' piu' corto di quanto dicesse l'apertura: e' cambiato mentre lo leggevamo, si riprova al giro dopo.
    if (ends == null) return Detection.Unreadable(null, bytes.bytesRead)
    val head = ends.head
    val tail = ends.tail

    val quick = QuickHashAlgorithm.compute(size, head, tail)
    val fingerprint = Fingerprint(size, quick)

    val hints = ExtractHints(
      mime = MediaTypes.mimeOf(name),
      displayName = name,
      relativePath = folder?.let { "$it/" } ?: "",
      msDateTakenMs = null,
      msWidth = null,
      msHeight = null,
      msOrientation = null,
      msDurationMs = null,
      scanZone = zone(),
      msDateModifiedMs = modifiedAt,
    )
    val extraction = try {
      extractor.extract(bytes, hints)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      null
    }

    // Prima di credere a "rovinato": un errore del NAS, il tetto del giro o il tetto del file non sono colpa del file.
    bytes.failure?.let { return Detection.Unreadable(it, bytes.bytesRead) }
    if (bytes.capHit) return Detection.CapReached(bytes.bytesRead)
    val metaMissing = bytes.limitHit || extraction == null

    return when (extraction) {
      is Extraction.NotMedia -> Detection.NotMedia(extraction.code, bytes.bytesRead)
      is Extraction.Read -> {
        val meta = NasMetaResult.Read(extraction.metadata, extraction.kind)
        Detection.Media(file.stamp, fingerprint, meta, provisional(head, extraction), bytes.bytesRead)
      }
      is Extraction.Unsupported -> Detection.Media(file.stamp, fingerprint, NasMetaResult.Unsupported, null, bytes.bytesRead)
      is Extraction.Corrupt ->
        Detection.Media(file.stamp, fingerprint, if (metaMissing) null else NasMetaResult.Failed(extraction.code.name), null, bytes.bytesRead)
      null -> Detection.Media(file.stamp, fingerprint, null, null, bytes.bytesRead)
    }
  }

  private class Ends(val head: ByteArray, val tail: ByteArray)

  /** Testa e coda per l'impronta (e tenute in memoria per l'estrattore); `null` se il file e' piu' corto del previsto. */
  private suspend fun readEnds(bytes: NasMediaBytes, size: Long, regions: List<LongRange>): Ends? {
    val headRange = regions[0]
    val head = bytes.pin(headRange)
    if (head.size.toLong() != headRange.last - headRange.first + 1) return null
    if (regions.size == 1) return Ends(head, EMPTY)
    // Coda: gli ultimi 64 KiB per l'impronta, e il resto della pagina da 64 KiB che li contiene, che l'estrattore
    // chiedera' comunque (le sue letture sono a pagine intere): una lettura sola per tutta la coda.
    val tailRange = regions[1]
    val pageStart = ((tailRange.first / NasMediaBytes.PAGE) * NasMediaBytes.PAGE).coerceAtLeast(headRange.last + 1)
    val read = bytes.pin(pageStart until size)
    if (read.size.toLong() != size - pageStart) return null
    return Ends(head, read.copyOfRange((tailRange.first - pageStart).toInt(), read.size))
  }

  private fun failed(bytes: NasMediaBytes, e: IOException): Detection = when {
    bytes.capHit -> Detection.CapReached(bytes.bytesRead)
    e is NasIoException -> Detection.Unreadable(e.error, bytes.bytesRead)
    else -> Detection.Unreadable(null, bytes.bytesRead)
  }

  /**
   * La miniatura EXIF come miniatura provvisoria (D17): solo di un JPEG, solo se la sua proporzione e' quella della foto
   * (entro il 4%, come per il ThumbHash: una miniatura 4:3 di una foto 16:9 e' peggio di nessuna) e con il suo orientamento.
   */
  private fun provisional(head: ByteArray, read: Extraction.Read): ByteArray? {
    if (read.kind != MediaKind.PHOTO && read.kind != MediaKind.MOTION_PHOTO) return null
    val thumb = ExifThumbnail.find(head) ?: return null
    val (tw, th) = ExifThumbnail.jpegSize(thumb) ?: return null
    val w = read.metadata.width
    val h = read.metadata.height
    if (w != null && h != null && !sameShape(tw, th, w, h)) return null
    val orientation = read.metadata.orientation
    return if (orientation == ExifOrientation.NORMAL) thumb else ExifThumbnail.withOrientation(thumb, orientation.exif)
  }

  private fun sameShape(tw: Int, th: Int, w: Int, h: Int): Boolean {
    val expectedHeight = tw.toDouble() * h / w
    return kotlin.math.abs(expectedHeight - th) <= maxOf(1.0, th * ASPECT_TOLERANCE)
  }

  companion object {
    /**
     * La "generazione" del riconoscitore, per chi ricorda i suoi esiti fra un processo e l'altro (D-16, `FileNotMediaMemory`): da alzare quando
     * cambia cio' che il rilevamento riconosce come media (un formato in piu', un controllo diverso). Con un'altra generazione i file gia'
     * riconosciuti "non media" si rileggono una volta.
     */
    const val CLASSIFIER_REVISION: Int = 1

    /** Tetto di byte per un rilevamento di immagine: testa, coda e le pagine che l'EXIF, l'XMP e i riquadri HEIC chiedono. */
    const val IMAGE_LIMIT: Long = 2L * 1024 * 1024

    /** Per un video: l'indice del contenitore (fino a 4 MiB) piu' testa e coda (§2.3). */
    const val VIDEO_LIMIT: Long = 5L * 1024 * 1024

    /** Come `DefaultMetadataExtractor.ASPECT_TOLERANCE`. */
    private const val ASPECT_TOLERANCE = 0.04

    private val EMPTY = ByteArray(0)
  }
}
