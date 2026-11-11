package dev.pampa.mycloudgallery.feature.library.collections

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Le porte della scheda Raccolte verso il resto dell'app (Fase 7, P11). Sono opzionali per Dagger, come quelle di Cerca
// (`SearchPortsModule`): finche' `:app` (P13) non le lega la scheda funziona con l'ordine predefinito e le scelte che durano quanto il
// processo, e il grafo di `:app` compila lo stesso.

/**
 * Dove l'utente tiene l'ordine e la visibilita' delle sezioni (D33). `:app` lo lega a `SyncedCollectionsSettings` di core-personal
 * (voci `collections_order` e `collections_hidden:<sezione>`, sincronizzate fra i dispositivi dell'account, valide subito anche senza l'ora
 * del NAS): `feature-library` non vede core-personal, quindi la porta ripete la forma e basta.
 */
interface CollectionsLayoutStore {
  /** L'ordine salvato: i codici delle sezioni, anche di quelle che oggi non ci sono. Vuoto = mai scelto, vale quello predefinito. */
  val order: StateFlow<List<String>>

  /** I codici delle sezioni nascoste dall'utente. */
  val hidden: StateFlow<Set<String>>

  /** Salva l'ordine; senza codici validi torna al predefinito. Puo' sollevare (disco pieno): la scheda lo dice. */
  suspend fun setOrder(codes: List<String>)

  /** Nasconde o mostra la sezione [code]. */
  suspend fun setHidden(code: String, hidden: Boolean)
}

/**
 * Il deposito che c'e' finche' `:app` non lega quello vero: le scelte durano quanto il processo e non viaggiano. Falliscono nel verso
 * sicuro (nessuna sezione nascosta, l'ordine predefinito); serve anche ai test e agli screenshot.
 */
class MemoryCollectionsLayoutStore(
  order: List<String> = emptyList(),
  hidden: Set<String> = emptySet(),
) : CollectionsLayoutStore {
  private val orderFlow = MutableStateFlow(order)
  private val hiddenFlow = MutableStateFlow(hidden)

  override val order: StateFlow<List<String>> = orderFlow.asStateFlow()
  override val hidden: StateFlow<Set<String>> = hiddenFlow.asStateFlow()

  override suspend fun setOrder(codes: List<String>) {
    orderFlow.value = codes.filter(CollectionSections::isValidCode).distinct().take(CollectionSections.MAX_SECTIONS)
  }

  override suspend fun setHidden(code: String, hidden: Boolean) {
    if (!CollectionSections.isValidCode(code) || code in CollectionSections.neverHidden) return
    hiddenFlow.update { if (hidden) it + code else it - code }
  }
}

/** Le porte della scheda, opzionali per Dagger: `:app` lega il deposito e i fornitori delle sezioni (`@IntoSet`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class CollectionsPortsModule {

  @BindsOptionalOf
  abstract fun collectionsLayoutStore(): CollectionsLayoutStore

  /** CS-1: i ritagli dei volti per le righe delle persone (feature-people, legati da `:app`); senza, la piastrella della famiglia. */
  @BindsOptionalOf
  abstract fun collectionFaceCrops(): CollectionFaceCrops

  /** I1: la mappa in anteprima dei Luoghi (feature-map, legata da `:app`); senza, il mosaico delle foto dei luoghi. */
  @BindsOptionalOf
  abstract fun collectionsMapPreview(): CollectionsMapPreview

  /** I fornitori delle sezioni: l'insieme esiste anche vuoto (nessun pacchetto ne ha ancora registrato uno). */
  @Multibinds
  abstract fun collectionsSectionProviders(): Set<CollectionsSectionProvider>
}
