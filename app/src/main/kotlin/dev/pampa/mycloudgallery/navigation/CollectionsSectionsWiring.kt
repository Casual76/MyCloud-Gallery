package dev.pampa.mycloudgallery.navigation

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.state.PhoneTrash
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.settings.SyncedCollectionsSettings
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumsSectionSources
import dev.pampa.mycloudgallery.feature.albums.sections.SectionData
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsLayoutStore
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.library.collections.EntryShape
import dev.pampa.mycloudgallery.feature.library.collections.MemoryCollectionsLayoutStore
import dev.pampa.mycloudgallery.feature.library.utility.utilityCountsFlow
import dev.pampa.mycloudgallery.feature.library.utility.utilityEntryGroup
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import dev.pampa.mycloudgallery.feature.albums.R as AlbumsR
import dev.pampa.mycloudgallery.feature.nas.R as NasR

// LE SEZIONI DELLE RACCOLTE COME FORNITORI (Fase 7, P13; D33, §2.7; P11: "un fornitore per sezione", "il ponte extraGroups tolto"). Ogni sezione e' un
// `CollectionsSectionProvider` legato con `@IntoSet` (`CollectionsSectionsModule`): Album, Album smart e Viaggi dai dati di feature-albums
// (`AlbumsSectionSources`, P10, con `toGroup()`), Cartelle NAS (prima "Sul NAS", passato come `extraGroups`), Utility con "Libera spazio" (P8 e Fase 5) e
// "Da tenere al sicuro" senza voci: gli Eliminati di recente stanno ora nell'Utility (D24, D26), una volta sola. I codici delle voci sono stabili e non
// portano mai un dato dell'utente; li riconoscono `onOpenEntry` ed `entryDetail` in `AppNavHost`.

/** La trasformazione campo per campo dei dati di feature-albums nel gruppo delle Raccolte (i due moduli non si conoscono, P10-7). */
internal fun SectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
  title = title,
  entries = entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing, cover = it.cover) },
  code = code,
  hideable = hideable,
  hint = hint,
)

/**
 * I1 (fase I): la sezione Viaggi nella galleria delle Raccolte. Un viaggio o un evento e' una scheda larga con la copertina e, sotto il titolo (che ha gia'
 * il posto e il mese), una riga piccola sola: "Viaggio" o "Evento" ([tripKind]); "Tutti i viaggi" e' il "tutti" della sezione; la riga "nessun viaggio con
 * la posizione" un invito. La frase intera ("Viaggio · 640 elementi") resta per TalkBack.
 */
internal fun SectionData.toTripsGroup(tripKind: (CollectionEntry) -> String?): CollectionEntryGroup {
  val base = toGroup()
  val hasTrips = base.entries.any { AlbumEntryTarget.parse(it.code).let { t -> t is AlbumEntryTarget.Trip && t.id != null } }
  return base.copy(
    entries = base.entries.map { entry ->
      when (val target = AlbumEntryTarget.parse(entry.code)) {
        is AlbumEntryTarget.Trip -> if (target.id != null) {
          entry.copy(shape = EntryShape.Card, caption = tripKind(entry))
        } else {
          // "Tutti i viaggi" con dei viaggi da mostrare e' il "tutti"; da sola (nessun viaggio con la posizione) e' l'invito.
          entry.copy(shape = if (hasTrips) EntryShape.More else EntryShape.Notice)
        }
        else -> entry.copy(shape = EntryShape.Notice)
      }
    },
  )
}

/** Un fornitore da un codice e da un flusso. */
internal class FlowSection(override val code: String, override val group: StateFlow<CollectionEntryGroup?>) : CollectionsSectionProvider

/**
 * Le sezioni di `:app`. [nasConfigured]: il NAS e' collegato (senza, Cartelle NAS non c'e'); [operations]: le operazioni sui file in corso (il numero
 * sulla voce "Operazioni in corso"); [freeSpace]: c'e' "Libera spazio" (solo con il backup); [canTrash]: c'e' un cestino (di sistema o del NAS).
 */
class AppCollectionsSections(
  private val resources: Resources,
  albums: Albums,
  trips: Trips,
  people: AlbumPeople,
  clock: AlbumScreensClock,
  items: CollectionItemsSource,
  nasConfigured: Flow<Boolean>,
  operations: Flow<Int>,
  private val freeSpace: Boolean,
  private val canTrash: Boolean,
  /** D-3: il cestino di questo telefono (su Samsung quello di Galleria), per la frase della voce "Eliminati di recente". */
  private val phoneTrash: PhoneTrash,
  scope: CoroutineScope,
) {
  private val sources = AlbumsSectionSources(resources, albums, trips, people, clock, scope)

  val albums: CollectionsSectionProvider = FlowSection(CollectionSections.ALBUMS, sources.albums.mapState(scope) { it?.toGroup() })

  val smart: CollectionsSectionProvider = FlowSection(CollectionSections.SMART, sources.smart.mapState(scope) { it?.toGroup() })

  val trips: CollectionsSectionProvider = FlowSection(CollectionSections.TRIPS, sources.trips.mapState(scope) { it?.toTripsGroup(::tripKind) })

  /** I1: "Viaggio" o "Evento", dall'icona della voce (quella che feature-albums da' ai due tipi). */
  private fun tripKind(entry: CollectionEntry): String? = when (entry.icon) {
    Icons.Rounded.Flight -> resources.getString(AlbumsR.string.trips_kind_trip)
    Icons.Rounded.Event -> resources.getString(AlbumsR.string.trips_kind_event)
    else -> null
  }

  /** Cartelle NAS (D33: "oggi Sul NAS"): le quattro pagine della 4B, solo con il NAS collegato. */
  val nas: CollectionsSectionProvider = FlowSection(
    CollectionSections.NAS,
    combine(nasConfigured, operations) { configured, active -> if (configured) nasGroup(active) else null }.stateIn(scope, SharingStarted.Eagerly, null),
  )

  /** Utility (D24): Screenshot, Video, Modificate, WhatsApp, Doppioni, Eliminati di recente, e "Libera spazio" della Fase 5 nello stesso gruppo. */
  val utility: CollectionsSectionProvider = FlowSection(
    CollectionSections.UTILITY,
    items.utilityCountsFlow().map { counts -> utilityEntryGroup(resources, counts, canTrash, freeSpaceEntries(), phoneTrash) }.stateIn(scope, SharingStarted.Eagerly, null),
  )

  /** "Da tenere al sicuro" senza voci: gli Eliminati di recente si aprono dall'Utility (D26), una volta sola nelle Raccolte. */
  val safe: CollectionsSectionProvider = FlowSection(CollectionSections.SAFE, MutableStateFlow(CollectionEntryGroup("", emptyList(), code = CollectionSections.SAFE)))

  val all: List<CollectionsSectionProvider> get() = listOf(albums, smart, trips, nas, utility, safe)

  private fun freeSpaceEntries(): List<CollectionEntry> = if (!freeSpace) emptyList() else listOf(
    CollectionEntry(
      BackupScreen.FREE_SPACE.route, resources.getString(R.string.fiveb_free_space_title), resources.getString(R.string.fiveb_free_space_subtitle),
      Icons.Rounded.CleaningServices, FluidTone.Primary,
    ),
  )

  private fun nasGroup(active: Int): CollectionEntryGroup = CollectionEntryGroup(
    title = resources.getString(NasR.string.nas_hub_group),
    entries = listOf(
      CollectionEntry(NasScreen.FOLDERS.route, resources.getString(NasR.string.nas_hub_folders_title), resources.getString(NasR.string.nas_hub_folders_subtitle), Icons.Rounded.Folder, FluidTone.Primary),
      CollectionEntry(NasScreen.SPACE.route, resources.getString(NasR.string.nas_hub_space_title), resources.getString(NasR.string.nas_hub_space_subtitle), Icons.Rounded.Storage, FluidTone.Info),
      // Giro 1 (CU-22): «Cestino del NAS» con la cartella barrata, cosi' non e' un secondo «Eliminati di recente» uguale a quello del telefono (Utility).
      CollectionEntry(
        NasScreen.TRASH.route, resources.getString(R.string.collections_nas_trash), resources.getString(NasR.string.nas_hub_trash_subtitle),
        Icons.Rounded.FolderDelete, FluidTone.Danger,
      ),
      CollectionEntry(
        NasScreen.OPERATIONS.route,
        resources.getString(NasR.string.nas_hub_ops_title),
        if (active > 0) resources.getQuantityString(NasR.plurals.nas_hub_ops_subtitle_active, active, GalleryNumbers.format(active)) else resources.getString(NasR.string.nas_hub_ops_subtitle_none),
        Icons.Rounded.Sync,
        FluidTone.Success,
        badge = active,
      ),
    ),
    code = CollectionSections.NAS,
  )

  private fun <T, R> StateFlow<T>.mapState(scope: CoroutineScope, transform: (T) -> R): StateFlow<R> =
    map(transform).stateIn(scope, SharingStarted.Eagerly, transform(value))

  internal companion object {
    /** Nessun NAS: Cartelle NAS non c'e'. */
    val NO_OPERATIONS: Flow<Int> = flowOf(0)
  }
}

/**
 * L'ordine e la visibilita' delle sezioni (P11) sopra `SyncedCollectionsSettings` di core-personal (`collections_order`, `collections_hidden:<codice>`,
 * sincronizzati fra i dispositivi dell'account), dal registro dei dati personali che c'e' solo con il NAS agganciato ([stores]). **Prima** del registro le
 * scelte valgono subito in memoria e, appena il registro c'e', vi entrano (come `PersonalSettingsHub` per `search_order`): niente si perde e niente
 * aspetta la sincronizzazione.
 *
 * **RC-2** (revisione di fine Fase 7): finche' una scelta non e' nel registro **vale quella dell'utente**, non quella del registro. Prima, appena il registro
 * compariva, `order` passava a quello del registro (ancora vuoto) e la scelta tornava per un attimo l'ordine predefinito; e una consegna fallita (disco
 * pieno) non si ritentava fino alla scelta dopo. Adesso la scelta non consegnata prevale, e la consegna si ritenta a intervalli.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCollectionsLayoutStore(
  stores: StateFlow<PersonalStore?>,
  private val scope: CoroutineScope,
  /** Ogni quanto si ritenta una consegna fallita (la prova lo accorcia). */
  private val retryMs: Long = RETRY_MS,
) : CollectionsLayoutStore {
  private val synced: StateFlow<SyncedCollectionsSettings?> = stores
    .map { store -> store?.let { SyncedCollectionsSettings(it, scope) } }
    .stateIn(scope, SharingStarted.Eagerly, null)
  private val local = MemoryCollectionsLayoutStore()
  private val pending = Mutex()
  private var pendingOrder: List<String>? = null
  private val pendingHidden = LinkedHashMap<String, Boolean>()

  /** Le scelte non ancora nel registro, come flussi: prevalgono su quello che il registro dice. */
  private val orderUndelivered = MutableStateFlow(false)
  private val hiddenUndelivered = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  override val order: StateFlow<List<String>> =
    combine(synced.flatMapLatest { s -> s?.order ?: local.order }, local.order, orderUndelivered) { shared, mine, undelivered -> if (undelivered) mine else shared }
      .stateIn(scope, SharingStarted.Eagerly, emptyList())

  override val hidden: StateFlow<Set<String>> =
    combine(synced.flatMapLatest { s -> s?.hidden ?: local.hidden }, hiddenUndelivered) { shared, undelivered ->
      if (undelivered.isEmpty()) shared else shared.filterTo(HashSet()) { it !in undelivered } + undelivered.filterValues { it }.keys
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

  init {
    // Le scelte fatte prima del registro vi entrano appena c'e' (una volta: poi si scrive direttamente li'); una consegna che non riesce si ritenta.
    scope.launch { synced.filterNotNull().collect { deliverUntilDone(it) } }
  }

  override suspend fun setOrder(codes: List<String>) {
    synced.value?.let { settings ->
      settings.setOrder(codes)
      // Una scelta nuova nel registro scavalca quella vecchia che non era riuscita a entrarci.
      pending.withLock {
        pendingOrder = null
        orderUndelivered.value = false
      }
      return
    }
    pending.withLock {
      local.setOrder(codes)
      pendingOrder = local.order.value
      orderUndelivered.value = true
    }
    synced.value?.let { deliverUntilDone(it) }
  }

  override suspend fun setHidden(code: String, hidden: Boolean) {
    // Come il deposito in memoria e il registro: un codice sconosciuto e le sezioni che non si nascondono mai (Album, D33) non entrano da nessuna parte.
    if (!CollectionSections.isValidCode(code) || code in CollectionSections.neverHidden) return
    synced.value?.let { settings ->
      settings.setHidden(code, hidden)
      pending.withLock {
        pendingHidden.remove(code)
        hiddenUndelivered.value = pendingHidden.toMap()
      }
      return
    }
    pending.withLock {
      local.setHidden(code, hidden)
      pendingHidden[code] = hidden
      hiddenUndelivered.value = pendingHidden.toMap()
    }
    synced.value?.let { deliverUntilDone(it) }
  }

  /** Consegna finche' riesce (al piu' [MAX_ATTEMPTS] volte, ogni [RETRY_MS]): una scelta che non entra nel registro resta quella dell'utente. */
  private suspend fun deliverUntilDone(settings: SyncedCollectionsSettings) {
    var attempt = 0
    while (attempt++ < MAX_ATTEMPTS) {
      if (deliver(settings)) return
      delay(retryMs)
    }
  }

  /** Consegna le scelte in attesa; vero se non resta niente (quelle che il registro non ha scritto restano in attesa per il tentativo dopo). */
  private suspend fun deliver(settings: SyncedCollectionsSettings): Boolean {
    val (order, hidden) = pending.withLock { pendingOrder to pendingHidden.toMap() }
    try {
      if (order != null) {
        settings.setOrder(order)
        // La scelta dell'utente cede il posto al registro solo quando il registro l'ha riletta: se no per un attimo si vedrebbe il valore di prima.
        withTimeoutOrNull(SETTLE_MS) { settings.order.first { it == order } }
        pending.withLock {
          if (pendingOrder === order) {
            pendingOrder = null
            orderUndelivered.value = false
          }
        }
      }
      for ((code, value) in hidden) {
        settings.setHidden(code, value)
        withTimeoutOrNull(SETTLE_MS) { settings.hidden.first { (code in it) == value } }
        pending.withLock {
          if (pendingHidden[code] == value) {
            pendingHidden.remove(code)
            hiddenUndelivered.value = pendingHidden.toMap()
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "scelte delle Raccolte non ancora nel registro: ${e.javaClass.simpleName}" }
      return false
    }
    return pending.withLock { pendingOrder == null && pendingHidden.isEmpty() }
  }

  private companion object {
    const val TAG = "CollectionsLayout"
    const val MAX_ATTEMPTS = 20
    const val RETRY_MS = 15_000L

    /** Quanto si aspetta che il registro rilegga una scelta appena scritta, prima di lasciare il posto a lui. */
    const val SETTLE_MS = 3_000L
  }
}
