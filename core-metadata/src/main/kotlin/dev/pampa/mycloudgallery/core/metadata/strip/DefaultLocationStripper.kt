package dev.pampa.mycloudgallery.core.metadata.strip

import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.format.Sniffer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

// Fase 5B, P11 (docs/fase5-progetto.md §1.2 "Posizione", D26, E-X5, L41; contratto `LocationStripper` di P0-B): togliere la posizione da una
// COPIA da condividere. JPEG, PNG e WebP con `ExifInterface` e la pulizia dell'XMP; MP4 e MOV rinominando in `free` le scatole della posizione
// (stessa dimensione, nessuna ricodifica); HEIC convertito in JPEG. Ogni altro formato non si tratta (L41): la condivisione senza posizione si
// ferma con il motivo.
//
// **Si fallisce chiusi.** Una destinazione vale solo se tutti i lettori del ricontrollo la leggono e dicono "nessuna posizione". Se uno trova
// una posizione la destinazione si toglie (`StillHasLocation`); se uno non riesce a leggerla si toglie (`Failed`); se i lettori che si applicano
// a questo tipo sono meno di due si toglie (`Unsupported(NOT_AVAILABLE)`: un legame sbagliato non produce mai un successo). Il sorgente non si
// tocca mai; la destinazione si crea in esclusiva (una che c'e' gia' non si sostituisce) e, se qualcosa va storto, non resta.

/**
 * Lo stripper vero.
 *
 * @param rechecks i lettori del ricontrollo (di serie `LocationRechecks.standard`): per ogni tipo ne servono almeno due che si applichino. In
 *   coda a questi gira sempre [RawLocationRecheck], che guarda i byte.
 * @param heic il convertitore HEIC → JPEG; senza (o su un telefono che non decodifica HEVC) un HEIC non si condivide senza posizione.
 */
class DefaultLocationStripper(
  private val rechecks: List<LocationRecheck>,
  private val heic: HeicToJpeg = HeicToJpeg.NotAvailable,
  private val maxImageBytes: Long = MAX_IMAGE_BYTES,
) : LocationStripper {

  private val raw = RawLocationRecheck()

  override fun strip(src: File, dst: File, mime: String): StripOutcome {
    val sniffed = try {
      if (!src.isFile) return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
      val head = RandomAccessFile(src, "r").use { RawFile(it).read(0, HEAD_BYTES) }
      if (head.isEmpty()) return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
      Sniffer.sniff(head, mime)
    } catch (_: IOException) {
      return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    } catch (_: SecurityException) {
      return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    }
    return when (sniffed.type) {
      ContainerType.JPEG, ContainerType.PNG, ContainerType.WEBP, ContainerType.MP4 -> copyAndStrip(src, dst, sniffed.type, sniffed.mime ?: mime)
      ContainerType.HEIF -> convertHeif(src, dst)
      // GIF, BMP, TIFF/RAW, Matroska, AVI e cio' che non e' un media: non si sa togliere la posizione, quindi non si condivide senza (L41).
      else -> StripOutcome.Unsupported(StripUnsupported.FORMAT)
    }
  }

  // --- JPEG, PNG, WebP, MP4, MOV -------------------------------------------------------------------------------------------------

  private fun copyAndStrip(src: File, dst: File, type: ContainerType, outMime: String): StripOutcome {
    if (type != ContainerType.MP4 && src.length() > maxImageBytes) return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    if (!applicable(outMime)) return StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
    val created = createExclusive(dst) ?: return StripOutcome.Failed(WriteFailure.IO)
    if (!created) return StripOutcome.Failed(WriteFailure.DESTINATION_EXISTS)
    var keep = false
    try {
      try {
        src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) } }
      } catch (_: IOException) {
        return StripOutcome.Failed(WriteFailure.IO)
      }
      val stripped = when (type) {
        ContainerType.MP4 -> stripVideo(dst)
        else -> when (ImageLocationStrip.run(dst, type)) {
          ImageLocationStrip.Result.DONE -> StripStep.DONE
          ImageLocationStrip.Result.UNSUPPORTED -> StripStep.STILL
          ImageLocationStrip.Result.IO -> StripStep.IO
        }
      }
      when (stripped) {
        StripStep.IO -> return StripOutcome.Failed(WriteFailure.IO)
        StripStep.STILL -> return StripOutcome.StillHasLocation
        StripStep.DONE -> Unit
      }
      val outcome = verdictOf(dst, outMime)
      keep = outcome is StripOutcome.Stripped
      return outcome
    } finally {
      if (!keep) dst.delete()
    }
  }

  private enum class StripStep { DONE, STILL, IO }

  /** Un video: il piano dei riquadri di posizione e, se non c'e' niente che blocchi, la patch sul posto. */
  private fun stripVideo(dst: File): StripStep = try {
    RandomAccessFile(dst, "rw").use { raf ->
      val file = RawFile(raf)
      val plan = IsoLocation.plan(file, 0, file.length)
      if (plan.blockers.isNotEmpty()) {
        StripStep.STILL
      } else {
        IsoLocation.apply(file, plan)
        StripStep.DONE
      }
    }
  } catch (_: IOException) {
    StripStep.IO
  } catch (_: SecurityException) {
    StripStep.IO
  }

  // --- HEIC → JPEG ---------------------------------------------------------------------------------------------------------------

  private fun convertHeif(src: File, dst: File): StripOutcome {
    if (src.length() > maxImageBytes) return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    if (!applicable(JPEG)) return StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
    val created = createExclusive(dst) ?: return StripOutcome.Failed(WriteFailure.IO)
    if (!created) return StripOutcome.Failed(WriteFailure.DESTINATION_EXISTS)
    var keep = false
    try {
      when (val converted = heic.convert(src, dst)) {
        HeicConversion.Done -> Unit
        HeicConversion.NotAvailable -> return StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
        is HeicConversion.Failed -> return StripOutcome.Failed(converted.reason)
      }
      // Anche un JPEG nato dai pixel si ricontrolla: un convertitore sbagliato non deve poter far passare una posizione.
      val outcome = verdictOf(dst, JPEG)
      keep = outcome is StripOutcome.Stripped
      return outcome
    } finally {
      if (!keep) dst.delete()
    }
  }

  // --- Il ricontrollo -------------------------------------------------------------------------------------------------------------

  /** Almeno due lettori iniettati si applicano a questo tipo. */
  private fun applicable(mime: String): Boolean = rechecks.count { it.appliesTo(mime) } >= 2

  /**
   * Tutti i lettori che si applicano rileggono [dst]: nessuna posizione per tutti → [StripOutcome.Stripped]; una posizione per anche uno solo →
   * [StripOutcome.StillHasLocation]; uno che non legge → `Failed`.
   */
  private fun verdictOf(dst: File, mime: String): StripOutcome {
    val readers = listOf(raw) + rechecks.filter { it.appliesTo(mime) }
    var unreadable = false
    for (reader in readers) {
      when (reader.check(dst, mime)) {
        LocationVerdict.HAS_LOCATION -> return StripOutcome.StillHasLocation
        LocationVerdict.UNREADABLE -> unreadable = true
        LocationVerdict.CLEAR -> Unit
      }
    }
    return if (unreadable) StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE) else StripOutcome.Stripped(mime)
  }

  /** Crea [dst] in esclusiva: true = creato, false = c'era gia', null = non si puo' creare. */
  private fun createExclusive(dst: File): Boolean? = try {
    dst.createNewFile()
  } catch (_: IOException) {
    null
  } catch (_: SecurityException) {
    null
  }

  companion object {
    /** Una foto da condividere sta ben sotto questo tetto (un JPEG da 200 MP pesa ~60 MB). */
    const val MAX_IMAGE_BYTES: Long = 256L shl 20

    private const val HEAD_BYTES = 256
    private const val COPY_BUFFER = 1 shl 20
    private const val JPEG = "image/jpeg"
  }
}
