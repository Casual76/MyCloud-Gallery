package dev.pampa.mycloudgallery.feature.library.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.attempt
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cio' che serve a comporre lo stato della scheda: le sezioni che la scheda e i fornitori hanno adesso (senza ordine), le scelte
 * dell'utente e la modalita'. I gruppi di `:app` ([CollectionEntryGroup] passati come `extraGroups`) si aggiungono nella composizione,
 * con [CollectionsLayout.state]: cosi' non c'e' nessun fotogramma in cui mancano.
 */
internal data class CollectionsInputs(
  val present: List<CollectionsSection>,
  val saved: List<String>,
  val hidden: Set<String>,
  val editing: Boolean,
  val saveFailed: Boolean,
)

/** Una sezione di un fornitore: [section] null = il fornitore ha consegnato un gruppo senza voci. */
private data class Provided(val code: String, val section: CollectionsSection?)

/**
 * Le sezioni della scheda Raccolte (D33, §2.7) e le scelte dell'utente su di loro: da dove arrivano, in che ordine, quali si vedono, e
 * il salvataggio. Senza Android a parte [CollectionsLayoutViewModel], cosi' lo prova la JVM.
 *
 * - **Sezioni come dati.** La scheda fa da sola Da tenere al sicuro e Nascosti (le raccolte che c'erano gia'); tutto il resto
 *   arriva da un [CollectionsSectionProvider] registrato dal pacchetto che lo possiede (e dai gruppi che `:app` passa ancora). Un
 *   fornitore con il codice di una sezione della scheda **la sostituisce**; di due fornitori con lo stesso codice vale il primo; uno con
 *   un codice che non si puo' scrivere nelle impostazioni e' ignorato.
 * - **Ordine e visibilita'** si salvano nel [store] (voci `collections_*` sincronizzate). La scelta si vede **subito**: una copia locale
 *   si aggiorna prima del salvataggio, e un salvataggio che fallisce la riporta com'era e lo dice ([CollectionsLayoutState.saveFailed]).
 * - **Una sezione nuova va in coda** (E-L14) e non rompe l'ordine salvato: [CollectionsLayout].
 */
class CollectionsLayoutController(
  private val canTrash: Boolean,
  providers: Collection<CollectionsSectionProvider>,
  private val store: CollectionsLayoutStore,
  private val scope: CoroutineScope,
  sharing: SharingStarted = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
) {

  // G2: i fornitori delle sezioni passate alla scheda Album (album, smart) non si ascoltano: le Raccolte non le mostrano.
  private val providers: List<CollectionsSectionProvider> =
    providers.filter { CollectionSections.isValidCode(it.code) && it.code !in CollectionSections.inAlbumsTab }.distinctBy { it.code }

  private val editing = MutableStateFlow(false)
  private val failed = MutableStateFlow(false)

  // Una copia locale delle scelte: la modifica si vede prima che il deposito la salvi, e le modifiche rapide (due "Sposta su" di
  // TalkBack di fila) partono ognuna da quella precedente. Il deposito la riallinea a ogni suo cambiamento (un altro dispositivo).
  private val savedOrder = MutableStateFlow(store.order.value)
  private val hiddenCodes = MutableStateFlow(store.hidden.value)

  init {
    scope.launch { store.order.collect { savedOrder.value = it } }
    scope.launch { store.hidden.collect { hiddenCodes.value = it } }
  }

  private val presentFlow: Flow<List<CollectionsSection>> = providedSections().map { provided -> merged(collectionsUiState(canTrash), provided) }

  private val choices: Flow<Triple<List<String>, Set<String>, Pair<Boolean, Boolean>>> =
    combine(savedOrder, hiddenCodes, editing, failed) { order, hidden, edit, fail -> Triple(order, hidden, edit to fail) }

  internal val inputs: StateFlow<CollectionsInputs> = combine(presentFlow, choices) { present, (order, hidden, mode) ->
    CollectionsInputs(present, order, hidden, editing = mode.first, saveFailed = mode.second)
  }.stateIn(scope, sharing, initialInputs())

  /** Cio' che dicono i fornitori: per ognuno il suo codice e la sezione, o null se dice "e' mia e non ha voci"; chi non fornisce niente (null) non c'e'. */
  private fun providedSections(): Flow<List<Provided>> {
    if (providers.isEmpty()) return flowOf(emptyList())
    return combine(providers.map { p -> p.group.map { group -> provided(p.code, group) } }) { all -> all.filterNotNull() }
  }

  private fun provided(code: String, group: CollectionEntryGroup?): Provided? =
    if (group == null) null else Provided(code, CollectionsLayout.sectionOf(code, group))

  /**
   * Le sezioni della scheda per prime, poi quelle dei fornitori: a pari codice vince il fornitore (la chiave resta al suo posto). Un
   * fornitore che consegna un gruppo **senza voci** dice che la sezione e' sua e oggi e' vuota: toglie anche quella della scheda (P8 che
   * porta gli Eliminati di recente in Utility); uno che non consegna niente (null) lascia stare quella della scheda.
   */
  private fun merged(state: CollectionsUiState, provided: List<Provided>): List<CollectionsSection> {
    val byCode = LinkedHashMap<String, CollectionsSection>()
    builtinSections(state).forEach { byCode[it.code] = it }
    for (p in provided) {
      if (p.section == null) byCode.remove(p.code) else byCode[p.code] = p.section
    }
    // G2: le sezioni passate alla scheda Album (Album, Album smart, Telefono) non sono delle Raccolte: ne' nell'elenco ne' in "Modifica Raccolte".
    // I loro codici restano negli ordini salvati (`CollectionSections.inAlbumsTab`).
    return byCode.values.filter { it.code !in CollectionSections.inAlbumsTab }
  }

  private fun initialInputs(): CollectionsInputs {
    val provided = providers.mapNotNull { p -> provided(p.code, p.group.value) }
    return CollectionsInputs(
      merged(collectionsUiState(canTrash), provided), savedOrder.value, hiddenCodes.value, editing = false, saveFailed = false,
    )
  }

  /** "Modifica Raccolte". */
  fun startEditing() {
    editing.value = true
  }

  /** "Fine". */
  fun stopEditing() {
    editing.value = false
    failed.value = false
  }

  /**
   * L'utente ha scelto questo ordine per le sezioni che ci sono adesso (un rilascio dopo il trascinamento, o "Sposta su" e "Sposta giu'"
   * di TalkBack). Si salva l'ordine intero, tenendo il posto delle sezioni che oggi non ci sono ([CollectionsLayout.toSave]).
   */
  fun reorder(newOrder: List<String>) {
    val clean = newOrder.filter(CollectionSections::isValidCode).distinct()
    if (clean.isEmpty()) return
    val toSave = CollectionsLayout.toSave(clean, savedOrder.value)
    if (toSave == savedOrder.value) return
    val before = savedOrder.value
    savedOrder.value = toSave
    scope.launch {
      if (attempt { store.setOrder(toSave) }) {
        failed.value = false
      } else {
        savedOrder.compareAndSet(toSave, before)
        failed.value = true
      }
    }
  }

  /** Mostra o nasconde la sezione [code]. Album (e un codice che non si puo' scrivere) non si nasconde: non succede niente. */
  fun setShown(code: String, shown: Boolean) {
    if (!CollectionSections.isValidCode(code) || code in CollectionSections.neverHidden) return
    val hidden = !shown
    val before = hiddenCodes.value
    if ((code in before) == hidden) return
    hiddenCodes.update { if (hidden) it + code else it - code }
    scope.launch {
      if (attempt { store.setHidden(code, hidden) }) {
        failed.value = false
      } else {
        hiddenCodes.value = before
        failed.value = true
      }
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** Le sezioni che la scheda fa da sola: Da tenere al sicuro (il cestino) e Nascosti. CS-17: non piu' il Telefono, che sta nella scheda Album dalla G2. */
internal fun builtinSections(state: CollectionsUiState): List<CollectionsSection> = listOfNotNull(
  CollectionsSection(
    code = CollectionSections.SAFE,
    title = SectionText.Res(R.string.collections_section_protected),
    rows = state.protectedRows.filter { it.kind == CollectionKind.RECENTLY_DELETED }.map { SectionRow.Kind(it) },
  ).takeIf { state.protectedRows.any { it.kind == CollectionKind.RECENTLY_DELETED } },
  CollectionsSection(
    code = CollectionSections.HIDDEN,
    title = SectionText.Res(R.string.collections_section_hidden),
    rows = state.protectedRows.filter { it.kind == CollectionKind.HIDDEN }.map { SectionRow.Kind(it) },
    hint = SectionText.Res(R.string.collections_hidden_hint),
  ).takeIf { state.protectedRows.any { it.kind == CollectionKind.HIDDEN } },
)

/**
 * Il ViewModel della scheda Raccolte: un [CollectionsLayoutController] sopra le porte che `:app` lega. Tutte le porte sono OPZIONALI per
 * Dagger ([CollectionsPortsModule]): senza il deposito le scelte durano quanto il processo, senza fornitori ci sono le sezioni di
 * sempre. Il ViewModel delle griglie filtrate resta [CollectionsViewModel].
 */
@HiltViewModel
class CollectionsLayoutViewModel private constructor(
  source: CollectionItemsSource,
  intents: PhoneMediaIntents,
  providers: Set<CollectionsSectionProvider>,
  store: Optional<CollectionsLayoutStore>,
  loader: Optional<ThumbnailLoader>,
  faces: Optional<CollectionFaceCrops>,
) : ViewModel() {

  /** H5: con il caricatore delle miniature le voci delle Raccolte mostrano le loro copertine ([CollectionCovers]). */
  @Inject constructor(
    source: CollectionItemsSource,
    intents: PhoneMediaIntents,
    providers: @JvmSuppressWildcards Set<CollectionsSectionProvider>,
    store: Optional<CollectionsLayoutStore>,
    loader: ThumbnailLoader,
    faces: Optional<CollectionFaceCrops>,
  ) : this(source, intents, providers, store, Optional.of(loader), faces)

  /** Senza caricatore (le prove di `:app` che costruiscono la scheda a mano): solo icone. */
  constructor(
    source: CollectionItemsSource,
    intents: PhoneMediaIntents,
    providers: Set<CollectionsSectionProvider>,
    store: Optional<CollectionsLayoutStore>,
  ) : this(source, intents, providers, store, Optional.empty(), Optional.empty())

  /** Le copertine delle voci: la stessa cache delle miniature e la Libreria visibile (mai un nascosto). Null = solo icone. */
  internal val covers: CollectionCovers? = loader.map { CollectionCovers(it, source.items(LibraryFilter.ALL), faces.orElse(null)) }.orElse(null)

  internal val controller: CollectionsLayoutController = CollectionsLayoutController(
    canTrash = intents.canTrash,
    providers = providers,
    store = store.orElseGet { MemoryCollectionsLayoutStore() },
    scope = viewModelScope,
  )

  internal val inputs: StateFlow<CollectionsInputs> get() = controller.inputs

  fun startEditing() = controller.startEditing()

  fun stopEditing() = controller.stopEditing()

  fun reorder(newOrder: List<String>) = controller.reorder(newOrder)

  fun setShown(code: String, shown: Boolean) = controller.setShown(code, shown)
}
