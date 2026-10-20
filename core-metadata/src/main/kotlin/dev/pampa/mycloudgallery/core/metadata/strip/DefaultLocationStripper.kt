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

// Fase 5B, P11 (docs/fase5-progetto.md §1.2 "Posizione", D26, E-X5, L41; contratto `LocationStripper` di P0-B), riscritto con le correzioni 5B (R1,
// R2, docs/review/5b-fase5b.md): togliere la posizione da una COPIA da condividere **per elenco di cio' che si tiene, non di cio' che si toglie**.
//  - JPEG, PNG e WebP si ricostruiscono dal sorgente con i soli pezzi che servono a vedere l'immagine, il profilo colore e un EXIF nuovo con
//    orientamento e data (`ImageRebuild`, `MinimalExif`); una motion photo riattacca il suo video solo dopo averlo ricostruito come un video;
//  - MP4 e MOV tengono solo le tracce audio e video e i riquadri che servono a riprodurle; il resto diventa `free` azzerato, e in `mdat` restano
//    solo i campioni delle tracce tenute (`IsoClean`): stessa dimensione, nessuna ricodifica;
//  - HEIC si converte in JPEG dai pixel (`HeicToJpeg`) e il JPEG si ricostruisce come gli altri.
// Ogni altro formato non si tratta (L41), e un file che non si ricostruisce cosi' nemmeno: la condivisione senza posizione si ferma con il motivo.
//
// **Si fallisce chiusi.** Una destinazione vale solo se la legge per primo il rilevatore indipendente (`LocationTraces`: scritto da zero, non
// riusa niente di questo stripper, non si puo' togliere) e poi tutti i lettori iniettati che si applicano, e tutti dicono "nessuna posizione". Se
// uno trova una posizione la destinazione si toglie (`StillHasLocation`); se uno non riesce a leggerla si toglie (`Failed`); se i lettori
// iniettati che si applicano a questo tipo sono meno di due si toglie (`Unsupported(NOT_AVAILABLE)`: un legame sbagliato non produce mai un
// successo). Il sorgente non si tocca mai; la destinazione si crea in esclusiva (una che c'e' gia' non si sostituisce) e, se qualcosa va storto,
// non resta.

/**
 * Lo stripper vero.
 *
 * @param rechecks i lettori del ricontrollo (di serie `LocationRechecks.standard`: l'estrattore dell'app, `ExifInterface`, `MediaMetadataRetriever`):
 *   per ogni tipo ne servono almeno due che si applichino. Davanti a questi gira sempre il rilevatore indipendente [LocationTraceRecheck].
 * @param heic il convertitore HEIC → JPEG; senza (o su un telefono che non decodifica HEVC) un HEIC non si condivide senza posizione.
 */
class DefaultLocationStripper(
  private val rechecks: List<LocationRecheck>,
  private val heic: HeicToJpeg = HeicToJpeg.NotAvailable,
  private val maxImageBytes: Long = MAX_IMAGE_BYTES,
) : LocationStripper {

  private val traces = LocationTraceRecheck()

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
      ContainerType.JPEG, ContainerType.PNG, ContainerType.WEBP -> image(src, dst, sniffed.type, sniffed.mime ?: mime)
      ContainerType.MP4 -> video(src, dst, sniffed.mime ?: mime)
      ContainerType.HEIF -> convertHeif(src, dst)
      // GIF, BMP, TIFF/RAW, Matroska, AVI e cio' che non e' un media: non si sa togliere la posizione, quindi non si condivide senza (L41).
      else -> StripOutcome.Unsupported(StripUnsupported.FORMAT)
    }
  }

  // --- JPEG, PNG, WebP -----------------------------------------------------------------------------------------------------------

  private fun image(src: File, dst: File, type: ContainerType, outMime: String): StripOutcome {
    if (src.length() > maxImageBytes) return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    if (!applicable(outMime)) return StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
    return intoNew(dst) {
      when (ImageRebuild.run(src, dst, type, MinimalExif.of(src))) {
        ImageRebuild.Result.DONE -> verdictOf(dst, outMime)
        ImageRebuild.Result.UNSUPPORTED -> StripOutcome.Unsupported(StripUnsupported.FORMAT)
        ImageRebuild.Result.IO -> StripOutcome.Failed(WriteFailure.IO)
      }
    }
  }

  // --- MP4, MOV ------------------------------------------------------------------------------------------------------------------

  /** Un video: la copia intera, poi la ricostruzione sul posto (`IsoClean`); se non si puo', la condivisione si ferma. */
  private fun video(src: File, dst: File, outMime: String): StripOutcome {
    if (!applicable(outMime)) return StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
    return intoNew(dst) {
      try {
        src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) } }
      } catch (_: IOException) {
        return@intoNew StripOutcome.Failed(WriteFailure.IO)
      }
      val stop = try {
        RandomAccessFile(dst, "rw").use { raf -> RawFile(raf).let { IsoClean.clean(it, 0, it.length) } }
      } catch (_: IOException) {
        return@intoNew StripOutcome.Failed(WriteFailure.IO)
      } catch (_: SecurityException) {
        return@intoNew StripOutcome.Failed(WriteFailure.IO)
      }
      if (stop != null) StripOutcome.Unsupported(StripUnsupported.FORMAT) else verdictOf(dst, outMime)
    }
  }

  // --- HEIC → JPEG ---------------------------------------------------------------------------------------------------------------

  /** I pixel diventano un JPEG in un file di lavoro accanto alla destinazione; il JPEG si ricostruisce come ogni altro nella destinazione. */
  private fun convertHeif(src: File, dst: File): StripOutcome {
    if (src.length() > maxImageBytes) return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    if (!applicable(JPEG)) return StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
    return intoNew(dst) {
      val work = File(dst.parentFile, dst.name + WORK_SUFFIX)
      val created = createExclusive(work) ?: return@intoNew StripOutcome.Failed(WriteFailure.IO)
      if (!created) return@intoNew StripOutcome.Failed(WriteFailure.DESTINATION_EXISTS)
      try {
        when (val converted = heic.convert(src, work)) {
          HeicConversion.Done -> Unit
          HeicConversion.NotAvailable -> return@intoNew StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
          is HeicConversion.Failed -> return@intoNew StripOutcome.Failed(converted.reason)
        }
        // Anche un JPEG nato dai pixel si ricostruisce e si ricontrolla: un convertitore che si porta dietro dei metadati non li fa passare.
        // L'orientamento e' gia' nei pixel: niente EXIF.
        when (ImageRebuild.run(work, dst, ContainerType.JPEG, null)) {
          ImageRebuild.Result.DONE -> verdictOf(dst, JPEG)
          ImageRebuild.Result.UNSUPPORTED -> StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
          ImageRebuild.Result.IO -> StripOutcome.Failed(WriteFailure.IO)
        }
      } finally {
        work.delete()
      }
    }
  }

  // --- Il ricontrollo -------------------------------------------------------------------------------------------------------------

  /** Almeno due lettori iniettati si applicano a questo tipo (oltre al rilevatore indipendente, che c'e' sempre). */
  private fun applicable(mime: String): Boolean = rechecks.count { it.appliesTo(mime) } >= 2

  /**
   * Il rilevatore indipendente e poi tutti i lettori che si applicano rileggono [dst]: nessuna posizione per tutti → [StripOutcome.Stripped];
   * una posizione per anche uno solo → [StripOutcome.StillHasLocation]; uno che non legge → `Failed`.
   */
  private fun verdictOf(dst: File, mime: String): StripOutcome {
    val readers = listOf(traces) + rechecks.filter { it.appliesTo(mime) }
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

  /** Crea [dst] in esclusiva, fa [work] e toglie [dst] se l'esito non e' [StripOutcome.Stripped]. */
  private inline fun intoNew(dst: File, work: () -> StripOutcome): StripOutcome {
    val created = createExclusive(dst) ?: return StripOutcome.Failed(WriteFailure.IO)
    if (!created) return StripOutcome.Failed(WriteFailure.DESTINATION_EXISTS)
    var keep = false
    try {
      val outcome = work()
      keep = outcome is StripOutcome.Stripped
      return outcome
    } finally {
      if (!keep) dst.delete()
    }
  }

  /** Crea [file] in esclusiva: true = creato, false = c'era gia', null = non si puo' creare. */
  private fun createExclusive(file: File): Boolean? = try {
    file.createNewFile()
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
    private const val WORK_SUFFIX = ".heic.jpg"
  }
}
