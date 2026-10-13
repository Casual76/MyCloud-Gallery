package dev.pampa.mycloudgallery.core.metadata.extract

import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.format.ArrayAccess
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.format.ExifFacts
import dev.pampa.mycloudgallery.core.metadata.format.IsoBmff
import dev.pampa.mycloudgallery.core.metadata.format.JpegScanner
import dev.pampa.mycloudgallery.core.metadata.format.PagedAccess
import dev.pampa.mycloudgallery.core.metadata.format.PngScanner
import dev.pampa.mycloudgallery.core.metadata.format.Sniffer
import dev.pampa.mycloudgallery.core.metadata.format.TiffFile
import dev.pampa.mycloudgallery.core.metadata.format.TiffParser
import dev.pampa.mycloudgallery.core.metadata.format.WebpScanner
import kotlin.coroutines.cancellation.CancellationException

/**
 * I dettagli EXIF per il pannello info (marca, modello, obiettivo, apertura, posa, ISO, focale, flash), **su richiesta**:
 * legge lo stesso blocco EXIF dell'estrattore (JPEG, PNG, WebP, HEIC, TIFF/DNG) e non salva niente. Un file senza EXIF,
 * rovinato o di un formato sconosciuto da' un [ExifDetails] vuoto, mai un errore.
 */
class DefaultExifDetailsReader : ExifDetailsReader {

  override suspend fun exifDetails(bytes: MediaBytes): ExifDetails {
    if (bytes.size <= 0L) return ExifDetails()
    return try {
      val ra = PagedAccess(bytes)
      val sniffed = Sniffer.sniff(ra.read(0, 64), null)
      val tiff: TiffFile? = when (sniffed.type) {
        ContainerType.JPEG -> JpegScanner.scan(ra).exifTiff
        ContainerType.PNG -> PngScanner.scan(ra).exifTiff
        ContainerType.WEBP -> WebpScanner.scan(ra).exifTiff
        ContainerType.HEIF -> IsoBmff.scanHeif(ra).exifTiff
        ContainerType.TIFF -> return details(TiffParser.parse(ra))
        else -> null
      }?.let { TiffParser.parse(ArrayAccess(it)) }
      details(tiff)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      ExifDetails()
    }
  }

  private suspend fun details(tiff: TiffFile?): ExifDetails {
    if (tiff == null) return ExifDetails()
    val f = ExifFacts.read(tiff)
    return ExifDetails(
      make = f.make,
      model = f.model,
      lens = f.lens,
      fNumber = f.fNumber,
      exposureTime = f.exposureTime,
      iso = f.iso,
      focalLengthMm = f.focalLengthMm,
      flashFired = f.flashFired,
    )
  }
}
