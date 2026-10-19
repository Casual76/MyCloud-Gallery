package dev.pampa.mycloudgallery.core.metadata.noop

import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataWriter
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe
import dev.pampa.mycloudgallery.core.metadata.contract.WriteOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteUnsupported
import java.io.File

// I null-object di core-metadata: cio' che :app lega finche' P3 non porta l'estrattore vero.
//
// Non stanno nel package `testing` di proposito: vanno nella release, mentre i fake no (tools/check.ps1 lo
// verifica). Falliscono chiusi: nessun metadato inventato. Un file "non supportato" resta in Libreria senza
// data precisa, luogo ne' segnaposto; non viene mai scartato.

/** Non legge niente: ogni file e' "formato non supportato" (esito sicuro, E-M10). */
object NoMetadataExtractor : MetadataExtractor {
  override suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction =
    Extraction.Unsupported(ExtractCode.UNSUPPORTED_FORMAT)
}

/** Nessun dettaglio EXIF: il pannello info mostra solo cio' che sa dal catalogo. */
object NoExifDetailsReader : ExifDetailsReader {
  override suspend fun exifDetails(bytes: MediaBytes): ExifDetails = ExifDetails()
}

/** Nessuna sonda video: durata e fuso li dara' il MediaStore. */
object NoVideoProbe : VideoProbe {
  override fun probe(bytes: MediaBytes): VideoFacts? = null
}

// --- Fase 5B (P0-B): scrittura dei metadati e rimozione della posizione --------------------------------------------------------

/**
 * Nessuno scrittore legato: ogni correzione e' "non disponibile" e **niente si crea**. Finche' P8 non porta quello vero la correzione di
 * data e luogo resta nell'app e nell'indice (come per i formati che non si scrivono, L40).
 */
object NoMetadataWriter : MetadataWriter {
  override fun canWrite(mime: String): Boolean = false

  override fun apply(src: File, dst: File, change: MetadataChange): WriteOutcome = WriteOutcome.Unsupported(WriteUnsupported.NOT_AVAILABLE)
}

/**
 * Nessun togli-posizione legato: ogni richiesta e' "non disponibile" e **niente si crea**. Fallisce chiuso: la condivisione senza posizione
 * si ferma con il motivo invece di consegnare un file con le coordinate (D26: si dimostra, non si presume).
 */
object NoLocationStripper : LocationStripper {
  override fun strip(src: File, dst: File, mime: String): StripOutcome = StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE)
}
