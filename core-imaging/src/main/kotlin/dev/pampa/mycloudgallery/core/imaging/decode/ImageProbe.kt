package dev.pampa.mycloudgallery.core.imaging.decode

import android.graphics.ImageDecoder
import androidx.exifinterface.media.ExifInterface
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Un'immagine che non si legge, con il codice stabile che finisce nello stato e nella Diagnostica. */
class ImageFailureException(val code: ImageFailure, cause: Throwable? = null) : Exception(code.name, cause)

/**
 * Cosa serve sapere di un'immagine **prima** di decodificarla, letto dalla testa del file (mai dall'intero): il
 * formato vero, le dimensioni come le vede il decodificatore, l'orientamento che dobbiamo applicare noi, e se si anima.
 */
class ImageFacts(
  val format: ImageFormat,
  /** Larghezza della bitmap che `ImageDecoder` produrrebbe (gia' orientata se il decodificatore lo fa da solo). */
  val decoderWidth: Int,
  val decoderHeight: Int,
  /** L'orientamento che applichiamo noi dopo la decodifica; [ExifOrientation.NORMAL] se lo fa il sistema. */
  val manual: ExifOrientation,
  val animated: AnimatedSpec?,
  val sizeBytes: Long?,
) {
  /** Le dimensioni dell'immagine come la vede l'utente: dritta (E-V3). */
  val uprightWidth: Int get() = manual.uprightSize(decoderWidth, decoderHeight).first
  val uprightHeight: Int get() = manual.uprightSize(decoderWidth, decoderHeight).second
}

/**
 * Chi applica l'orientamento EXIF, per formato (E-V3, E-M15: gli otto orientamenti, sempre dritti, mai due volte).
 *
 * | Formato | Orientamento |
 * |---|---|
 * | JPEG, PNG, WebP, AVIF, GIF, BMP | lo applica `ImageDecoder`: non si fa niente |
 * | HEIC con `irot`/`imir` | il decodificatore HEIF applica la rotazione del contenitore: non si fa niente |
 * | HEIC senza `irot`/`imir` | si legge il tag EXIF e lo applichiamo noi |
 * | DNG e altri RAW (TIFF) | `ImageDecoder` non lo applica: lo leggiamo (tag 274) e lo applichiamo noi |
 *
 * **Da verificare sul campo (T-26):** il comportamento di `ImageDecoder` su HEIC e DNG veri dell'S25. Se una
 * versione di One UI applicasse da sola l'EXIF di un DNG, il risultato sarebbe ruotato due volte: la politica sta
 * in un posto solo per correggerla in una riga.
 */
object OrientationPolicy {

  /** Vero se per questo formato serve leggere il tag EXIF (negli altri lo applica il sistema). */
  fun needsExif(format: ImageFormat, hasContainerTransform: Boolean): Boolean =
    format == ImageFormat.TIFF_RAW || (format == ImageFormat.HEIF && !hasContainerTransform)

  /** L'orientamento da applicare dopo la decodifica, dato quello letto dal file. */
  fun manual(format: ImageFormat, exif: ExifOrientation, hasContainerTransform: Boolean): ExifOrientation =
    if (needsExif(format, hasContainerTransform)) exif else ExifOrientation.NORMAL
}

/** La lettura della testa di un'immagine (funzioni bloccanti: girano su un thread di lavoro). */
object ImageProbe {

  private const val SNIFF_BYTES = 64
  private val NEEDS_LONG_HEAD = setOf(ImageFormat.HEIF, ImageFormat.AVIF, ImageFormat.GIF, ImageFormat.WEBP)

  /**
   * Legge la testa di [bytes]: formato, dimensioni del decodificatore, orientamento manuale, animazione.
   *
   * @throws ImageFailureException `UNSUPPORTED_FORMAT` per un formato che non si riconosce, `DECODE_FAILED` per un
   *   file vuoto, troncato nella testa o corrotto (E-M10, E-M12), `NOT_FOUND` se il media non c'e' piu'.
   */
  fun probe(bytes: ImageBytes): ImageFacts {
    val head = try {
      bytes.openStream().use { stream ->
        // Pochi byte bastano a riconoscere il formato; solo HEIC, GIF e WebP hanno bisogno di una testa piu' lunga
        // (la casella `meta` con `irot`, il blocco di animazione). Un JPEG da 60 MB ne legge 64.
        val first = readHead(stream, SNIFF_BYTES)
        if (ImageSniffer.sniff(first) in NEEDS_LONG_HEAD) first + readHead(stream, ImageSniffer.HEAD_BYTES - first.size) else first
      }
    } catch (e: FileNotFoundException) {
      throw ImageFailureException(ImageFailure.NOT_FOUND, e)
    } catch (e: IOException) {
      throw ImageFailureException(ImageFailure.DECODE_FAILED, e)
    }
    if (head.isEmpty()) throw ImageFailureException(ImageFailure.DECODE_FAILED)
    val format = ImageSniffer.sniff(head)
    if (format == ImageFormat.UNKNOWN) throw ImageFailureException(ImageFailure.UNSUPPORTED_FORMAT)

    val (width, height) = readDecoderSize(bytes)
    val container = format == ImageFormat.HEIF && ImageSniffer.hasContainerTransform(head)
    val exif = if (OrientationPolicy.needsExif(format, container)) readExifOrientation(bytes) else ExifOrientation.NORMAL
    return ImageFacts(
      format = format,
      decoderWidth = width,
      decoderHeight = height,
      manual = OrientationPolicy.manual(format, exif, container),
      animated = ImageSniffer.animatedSpec(head),
      sizeBytes = bytes.sizeBytes,
    )
  }

  /** Il tag EXIF `Orientation`; qualunque problema di lettura vale "nessuna rotazione". */
  fun readExifOrientation(bytes: ImageBytes): ExifOrientation = try {
    bytes.openStream().use { stream ->
      ExifOrientation.fromExif(ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    }
  } catch (_: IOException) {
    ExifOrientation.NORMAL
  } catch (_: RuntimeException) {
    ExifOrientation.NORMAL
  }

  private class HeaderRead(val width: Int, val height: Int) : RuntimeException() {
    // Un'eccezione di servizio per uscire dall'ascoltatore: niente traccia dello stack.
    override fun fillInStackTrace(): Throwable = this
  }

  /** Le dimensioni di `ImageDecoder`, fermandosi alla testa: l'ascoltatore esce prima di decodificare un pixel. */
  private fun readDecoderSize(bytes: ImageBytes): Pair<Int, Int> {
    try {
      ImageDecoder.decodeBitmap(bytes.decoderSource()) { _, info, _ ->
        throw HeaderRead(info.size.width, info.size.height)
      }
    } catch (header: HeaderRead) {
      if (header.width <= 0 || header.height <= 0) throw ImageFailureException(ImageFailure.DECODE_FAILED)
      return header.width to header.height
    } catch (e: FileNotFoundException) {
      throw ImageFailureException(ImageFailure.NOT_FOUND, e)
    } catch (e: IOException) {
      // Include ImageDecoder.DecodeException: testa illeggibile o formato che il sistema non sa leggere.
      throw ImageFailureException(ImageFailure.DECODE_FAILED, e)
    } catch (e: RuntimeException) {
      throw ImageFailureException(ImageFailure.DECODE_FAILED, e)
    }
    throw ImageFailureException(ImageFailure.DECODE_FAILED)
  }

  private fun readHead(stream: InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var read = 0
    while (read < limit) {
      val n = stream.read(buffer, read, limit - read)
      if (n < 0) break
      read += n
    }
    return buffer.copyOf(read)
  }
}
