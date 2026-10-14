package dev.pampa.mycloudgallery.core.mediastore.noop

import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.model.Sha256

/** Fase 4 (P0): nessun salvataggio sul telefono finche' :app non lega quello vero (P8). Non legge la sorgente. */
object NoPhoneMediaWriter : PhoneMediaWriter {
  override suspend fun saveToPictures(
    request: SaveRequest,
    source: PhoneSaveSource,
    expectedSha256: Sha256?,
    progress: (Long) -> Unit,
    beforePublish: suspend (PendingCopy) -> Unit,
  ): SaveResult = SaveResult.Failed("NOT_AVAILABLE")
}
