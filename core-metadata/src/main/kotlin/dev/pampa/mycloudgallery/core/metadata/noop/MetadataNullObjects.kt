package dev.pampa.mycloudgallery.core.metadata.noop

import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe

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
