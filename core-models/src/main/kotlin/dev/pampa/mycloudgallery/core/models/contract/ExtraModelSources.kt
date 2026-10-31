package dev.pampa.mycloudgallery.core.models.contract

import dev.pampa.mycloudgallery.core.model.Sha256

// GLI INDIRIZZI AGGIUNTIVI DEI MODELLI (Fase 10, P0; docs/fase10-progetto.md §1.2 "core-models", D29, E-REL7, E-S16). Firma di P0; l'uso in
// `RoutingModelFetcher` (ordine: NAS, poi gli indirizzi fissati del catalogo, poi questi) e' di P1. :app la lega a `ReleaseState.extraUrls` del
// manifest firmato: core-models non vede core-release e core-release non vede core-models (i due si parlano con testo validato qui).

/**
 * Gli indirizzi `https` **in piu'** che il manifest di rilascio da' per un modello **gia' nel catalogo** (D29): solo per un [id] e uno [sha256]
 * uguali a una voce di [ModelCatalog], solo come [PinnedUrl] (l'unico costruttore e' `PinnedUrl.of`: host di [ModelHosts], niente porta,
 * utente, parametri ne' frammento). Non aggiunge un modello, non cambia un'impronta, non aggiunge un host, non salta la verifica: l'impronta
 * fissata decide sempre. Un manifest scaduto non da' indirizzi (E-REL4). Il null-object non ne da' mai.
 */
fun interface ExtraModelSources {
  fun urls(id: ModelId, sha256: Sha256): List<PinnedUrl>
}
