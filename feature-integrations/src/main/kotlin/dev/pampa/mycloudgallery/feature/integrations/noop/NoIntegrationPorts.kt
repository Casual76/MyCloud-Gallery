package dev.pampa.mycloudgallery.feature.integrations.noop

import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentRoot
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryGate
import dev.pampa.mycloudgallery.feature.integrations.contract.MediaDocuments

// Fase 5B (P0-B): i null-object delle porte delle integrazioni, release-safe (fuori da `testing`). Quello che le porte valgono finche' `:app`
// non installa le vere (P14): falliscono chiusi, un'altra app che chiede una foto non vede niente e nessuna azione parte.

/** Nessun ingresso: l'app non e' pronta, le activity d'ingresso non mostrano niente. */
object NoEntryGate : EntryGate {
  override fun decide(): EntryDecision = EntryDecision.NOT_READY
}

/** Nessun documento: nessuna radice, niente da elencare, niente da aprire. */
object NoMediaDocuments : MediaDocuments {
  override suspend fun roots(): List<DocumentRoot> = emptyList()

  override suspend fun children(parent: DocumentId, limit: Int): List<DocumentEntry> = emptyList()

  override suspend fun entry(id: DocumentId): DocumentEntry? = null

  override suspend fun open(id: DocumentId): DocumentReader? = null
}
