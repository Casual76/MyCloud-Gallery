package dev.pampa.mycloudgallery.feature.library.hidden

import dev.pampa.mycloudgallery.core.library.contract.ViewerItems

/**
 * Da dove si leggono i media nascosti. **Il solo punto dell'app che li legge** (E-H1): l'indice della timeline, la sua
 * istantanea su file, le raccolte, il viewer della Libreria, i conteggi non li contengono mai. Si leggono dal catalogo
 * (`TimelineIndexDao.hiddenRows()`, i media con `hidden = 1` ancora presenti in qualche posto) e solo qui.
 *
 * `:app` (P11) lo lega al catalogo vero; i test usano `FakeCollectionsWorld.hiddenLibrary` (package `collections.testing`), che conta le letture.
 */
fun interface HiddenLibrary {

  /**
   * Legge i nascosti dal database e li restituisce come elementi da scorrere: la vista (nell'ordine della timeline, il
   * piu' recente per primo) e come arrivare ai byte di ognuno.
   *
   * **Va chiamata solo dopo lo sblocco**: [HiddenController] la chiama soltanto con [HiddenAccessGate.isUnlocked], e
   * dimentica il risultato appena la schermata esce. Una lettura fa un'istantanea: dopo "Mostra" si legge di nuovo.
   */
  suspend fun read(): ViewerItems
}
