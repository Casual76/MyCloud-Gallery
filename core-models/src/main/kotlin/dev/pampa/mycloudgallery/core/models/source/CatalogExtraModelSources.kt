package dev.pampa.mycloudgallery.core.models.source

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ExtraModelSources
import dev.pampa.mycloudgallery.core.models.contract.ExtraModelUrls
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.PinnedUrl

/**
 * Gli indirizzi aggiuntivi del manifest firmato, **filtrati con il catalogo dell'app** (Fase 10, P1; D29, E-REL7, E-S16; C440, C441).
 * Un indirizzo passa solo se:
 * 1. il modello [ModelId] e' nel [catalog] **con la stessa impronta** [Sha256] (il manifest non aggiunge modelli e non cambia impronte);
 * 2. il modello si scarica (un modello dentro l'APK non ha sorgenti HTTPS: nessun indirizzo);
 * 3. diventa un [PinnedUrl] con `PinnedUrl.of` (solo `https`, solo host di `ModelHosts`, niente porta, utente, parametri, frammento, IP);
 * 4. non e' gia' fra gli indirizzi fissati (che vengono prima: NAS, fissati, aggiuntivi).
 * Al piu' [MAX_EXTRA] indirizzi, nell'ordine del manifest. Un errore della porta vale "nessun indirizzo" (fallire chiusi). L'impronta del
 * catalogo decide comunque: un indirizzo verso un file diverso produce un file rifiutato, mai accettato.
 */
class CatalogExtraModelSources(
  private val offered: ExtraModelUrls,
  private val catalog: List<ModelSpec> = ModelCatalog.WITH_FACES,
) : ExtraModelSources {

  override fun urls(id: ModelId, sha256: Sha256): List<PinnedUrl> {
    val spec = catalog.firstOrNull { it.id == id } ?: return emptyList()
    if (spec.sha256 != sha256 || spec.bundledAsset != null) return emptyList()
    val raw = try {
      offered.urls(id.value, sha256.hex)
    } catch (e: RuntimeException) {
      return emptyList()
    }
    return raw.asSequence()
      .take(MAX_OFFERED)
      .mapNotNull { PinnedUrl.of(it) }
      .filter { it !in spec.https }
      .distinct()
      .take(MAX_EXTRA)
      .toList()
  }

  override fun toString(): String = "CatalogExtraModelSources"

  companion object {
    /** Quanti indirizzi aggiuntivi al piu' per modello (come i tetti del manifest: 4 per modello). */
    const val MAX_EXTRA: Int = 4

    /** Quanti indirizzi grezzi si guardano al piu' (16 voci da 4 indirizzi nel manifest: una porta sbagliata non fa lavorare di piu'). */
    private const val MAX_OFFERED: Int = 64
  }
}
