package dev.pampa.mycloudgallery.core.models.verify

import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.core.models.store.ModelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * L'unico modo di avere un modello da caricare (D16, D33, E-A11): ad **ogni** apertura rilegge il file per intero, controlla la
 * dimensione e lo SHA-256 contro il catalogo e, solo se tornano, consegna il modello con il descrittore su cui ha verificato. Un file
 * che non torna (alterato dopo la verifica, memoria rovinata, ripristino da un backup di sistema) e' tolto subito e il modello torna in
 * coda: il prossimo `ensure` lo riprende dalle sorgenti. Null = assente, rifiutato o illeggibile.
 *
 * Per 157 MiB sono circa un secondo, per 370 MiB un paio: e' il prezzo della garanzia "nessun modello non verificato viene caricato".
 */
internal class LocalModelFiles(
  private val store: ModelStore,
  private val verifier: ModelVerifier,
  private val io: CoroutineDispatcher,
) : ModelFiles {

  override suspend fun open(id: ModelId): VerifiedModel? {
    val spec = store.spec(id) ?: return null
    return store.lock.withLock {
      withContext(io) {
        when (val opened = verifier.openVerified(spec)) {
          is ModelVerifier.Opened.Verified -> opened.model
          else -> null
        }
      }
    }
  }
}
