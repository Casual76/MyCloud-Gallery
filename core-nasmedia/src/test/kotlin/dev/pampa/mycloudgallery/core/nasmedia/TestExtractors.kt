package dev.pampa.mycloudgallery.core.nasmedia

import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor

/** Un estrattore scritto sul momento dal test. */
internal fun scripted(block: suspend (MediaBytes, ExtractHints) -> Extraction): MetadataExtractor = object : MetadataExtractor {
  override suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction = block(bytes, hints)
}
