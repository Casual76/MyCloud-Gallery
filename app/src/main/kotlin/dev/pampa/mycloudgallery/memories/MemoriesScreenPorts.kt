package dev.pampa.mycloudgallery.memories

import android.content.res.Resources
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPeople
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.library.collections.EntryShape
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionSources
import dev.pampa.mycloudgallery.feature.memories.section.SectionData
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryState
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryStatus
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntrySummary
import dev.pampa.mycloudgallery.navigation.FlowSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// LE PORTE DELLE SCHERMATE DEI RICORDI (Fase 9, P13; "per P13" di P9): la sezione delle Raccolte, le persone per i titoli, la voce di Impostazioni. Il file
// nomina `HiddenMask` (il filtro finale della sezione): sta nell'elenco `wiring` del registro `HiddenSurfaces`; la superficie e' `memories-section`.

/** La trasformazione campo per campo dei dati di feature-memories nel gruppo delle Raccolte (i due moduli non si conoscono; `MemoriesSectionBindingTest`). */
internal fun SectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
  title = title,
  // I1 (fase I): un ricordo e' una scheda larga con la copertina e il mese, "Tutti i ricordi" il "tutti" della sezione, le righe di stato un invito.
  entries = entries.map {
    val shape = when (MemoriesEntryTarget.parse(it.code)) {
      is MemoriesEntryTarget.Memory -> EntryShape.Card
      MemoriesEntryTarget.All -> EntryShape.More
      else -> EntryShape.Notice
    }
    CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing, cover = it.cover, shape = shape, caption = it.caption)
  },
  code = code,
  hideable = hideable,
  hint = hint,
)

/**
 * La sezione Ricordi delle Raccolte come fornitore (codice `memories`, P9): si disegna **dall'ultimo scaffale** (la facciata dei ricordi, che `restore()` ha
 * letto da file al montaggio) e un istante dopo il filtro finale dei Nascosti rifa' copertine e conti. Montata o no c'e' sempre una riga ("sto preparando",
 * "arrivano quando ci sono abbastanza foto"). **Una per processo** (`@Singleton`): i collettori vivono quanto [scope].
 */
fun memoriesSectionProvider(
  resources: Resources,
  memories: Memories,
  people: MemoryPeople,
  places: MemoryPlaceNames,
  mask: HiddenMask,
  scope: CoroutineScope,
): CollectionsSectionProvider {
  val sources = MemoriesSectionSources(resources, memories, MemoryNames(people, places), FinalFilter(mask), scope)
  return FlowSection(MemoriesSectionSources.CODE, sources.section.map { it.toGroup() }.stateIn(scope, SharingStarted.Eagerly, sources.section.value.toGroup()))
}

/** Le persone per le schermate dei ricordi (i nomi nei titoli, "con Anna"): quelle del motore montato, prima nessuna. Mai salvati. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryPeopleFacade(private val graph: MemoriesGraph) : MemoryPeople {
  private val current: MemoryPeople get() = graph.live.value?.people ?: NoMemoryPeople

  override val available: StateFlow<Boolean> = graph.live.flatMapLatest { it?.people?.available ?: MutableStateFlow(false) }
    .stateIn(graph.facadeScope, SharingStarted.Eagerly, false)

  override suspend fun named(): List<PersonDigest> = current.named()

  override suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>> = current.peopleIn(ids)

  override suspend fun resolve(person: PersonId): PersonId? = current.resolve(person)

  override suspend fun label(person: PersonId): String? = current.label(person)

  override fun toString(): String = "MemoryPeopleFacade"
}

/**
 * La voce "Ricordi e notifiche" di Impostazioni (P9, `MemoriesEntryStatus`): la frequenza scelta (o spente) e un pallino se le notifiche sono accese ma il
 * permesso o il canale di Android le bloccano (D18: la voce lo dice, non chiede niente). Il permesso si legge solo se le notifiche sono accese. Finche' le preferenze non
 * sono lette il valore e' `null` (IM-1): mai quello di serie.
 */
class AppMemoriesEntryStatus(settings: () -> NotifySettings, access: NotificationAccess, scope: CoroutineScope) : MemoriesEntryStatus {
  // Pigra: preferenze e permesso si leggono alla prima riga che guarda (Impostazioni aperte), fuori dal thread principale; mai all'avvio.
  // IM-1 (31/10): **nessun valore di serie**. Prima partiva da `NotifyPrefs()` con il permesso concesso, e per qualche fotogramma la riga diceva "2-3 a settimana" e niente
  // pallino anche a chi aveva spento le notifiche o negato il permesso, per poi correggersi. Ora e' `null` ("non lo so ancora": la riga dice a cosa serve) finche' le
  // preferenze non sono lette; poi l'ultimo valore letto resta in memoria quanto il processo (lo `StateFlow` e' dell'app), e le aperture dopo la prima partono da quello.
  override val summary: StateFlow<MemoriesEntrySummary?> = flow {
    emitAll(combine(settings().prefs, access.state) { prefs, permission -> summaryOf(prefs, permission) })
  }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Lazily, null)

  companion object {
    fun summaryOf(prefs: NotifyPrefs, permission: NotifyPermission): MemoriesEntrySummary {
      val state = if (!prefs.enabled) {
        MemoriesEntryState.OFF
      } else {
        when (prefs.frequency) {
          NotifyFrequency.FEW_PER_WEEK -> MemoriesEntryState.FEW_PER_WEEK
          NotifyFrequency.DAILY -> MemoriesEntryState.DAILY
          NotifyFrequency.ANNIVERSARIES -> MemoriesEntryState.ANNIVERSARIES
        }
      }
      return MemoriesEntrySummary(state, needsAttention = state != MemoriesEntryState.OFF && permission != NotifyPermission.GRANTED)
    }
  }
}
