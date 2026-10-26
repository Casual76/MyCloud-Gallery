package dev.pampa.mycloudgallery.core.backup.noop

import dev.pampa.mycloudgallery.core.backup.contract.SafeCopyRequests
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Fase 7 (P0, D28): nessun guardiano (backup non legato, test). Rifiuta apertamente: la richiesta **non** e' registrata, cosi' chi chiama
 * (l'orchestratore della condivisione, P4) non segna "copia privata in arrivo" su un media che nessuno proteggera'.
 */
object NoSafeCopyRequests : SafeCopyRequests {
  override suspend fun requestNow(media: List<MediaId>) {
    if (media.isNotEmpty()) throw IllegalStateException("Nessun guardiano: la richiesta della copia privata non e' registrata.")
  }
}
