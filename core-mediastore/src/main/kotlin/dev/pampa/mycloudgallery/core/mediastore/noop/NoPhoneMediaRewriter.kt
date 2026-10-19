package dev.pampa.mycloudgallery.core.mediastore.noop

import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File

/**
 * Fase 5B (P0-B): nessuna riscrittura sul telefono finche' `:app` non lega quella vera (P14). Fallisce chiuso: "nessun permesso" e **non apre
 * ne' legge** ne' la riga ne' il file. Cosi' una correzione o un "Ripristina originale" senza il legame vero lascia il telefono com'e'.
 */
object NoPhoneMediaRewriter : PhoneMediaRewriter {
  override suspend fun replace(ref: MediaRef.Phone, file: File, expectedSha256: Sha256): RewriteResult = RewriteResult.NotGranted
}
