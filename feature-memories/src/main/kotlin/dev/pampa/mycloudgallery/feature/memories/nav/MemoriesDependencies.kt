package dev.pampa.mycloudgallery.feature.memories.nav

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MusicLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryClips
import dev.pampa.mycloudgallery.core.memories.noop.NoMusicLibrary
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.MusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NoMusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.memories.contract.UnboundNotificationAccess
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import java.util.Optional
import javax.inject.Inject

// LE PORTE DELLE SCHERMATE DEI RICORDI PER DAGGER (Fase 9, P9; D7 della Fase 2: @HiltViewModel; docs/fase9-progetto.md §1.4). Tutte **opzionali**:
// `:app` (P13, `MemoriesGraph`) lega i contratti veri (il motore dei ricordi, le persone, i nomi dei luoghi, i Nascosti, le preferenze, il lettore
// audio); finche' non lo fa le schermate partono lo stesso con i null-object, che **non fanno niente di pericoloso** (nessun ricordo, nessuna
// persona, nessun audio) e il grafo di `:app` compila. Le preferenze (notifiche e musica) senza legame stanno in memoria: valgono finche' vive il
// processo, e P13 le sostituisce con quelle vere su DataStore.

/** I contratti con cui parlano le schermate dei ricordi, gia' risolti sui null-object quando `:app` non li ha legati. */
class MemoriesDependencies @Inject constructor(
  memories: Optional<Memories>,
  people: Optional<MemoryPeople>,
  places: Optional<MemoryPlaceNames>,
  mask: Optional<HiddenMask>,
  notify: Optional<NotifySettings>,
  access: Optional<NotificationAccess>,
  musicSettings: Optional<MusicSettings>,
  musicLibrary: Optional<MusicLibrary>,
  playback: Optional<MusicPlayback>,
  clips: Optional<MemoryClips>,
) {
  val memories: Memories = memories.orElse(NoMemories)
  val people: MemoryPeople? = people.orElse(null)
  val names: MemoryNames = MemoryNames(this.people, places.orElse(NoMemoryPlaceNames))

  /** Il filtro finale dei Nascosti: senza la maschera (`:app` non l'ha legata) vale il solo filtro del servizio. */
  val filter: FinalFilter = FinalFilter(mask.orElse(null))
  val notify: NotifySettings = notify.orElse(InMemoryNotifySettings())
  val access: NotificationAccess = access.orElse(UnboundNotificationAccess)
  val musicSettings: MusicSettings = musicSettings.orElse(InMemoryMusicSettings())
  val musicLibrary: MusicLibrary = musicLibrary.orElse(NoMusicLibrary)
  val playback: MusicPlayback = playback.orElse(NoMusicPlayback)
  val clips: MemoryClips = clips.orElse(NoMemoryClips)
}

/** Le porte dei ricordi, opzionali per Dagger: `:app` le lega ai contratti di core-memories. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MemoriesPortsModule {

  @BindsOptionalOf
  abstract fun memories(): Memories

  @BindsOptionalOf
  abstract fun memoryPeople(): MemoryPeople

  @BindsOptionalOf
  abstract fun placeNames(): MemoryPlaceNames

  @BindsOptionalOf
  abstract fun hiddenMask(): HiddenMask

  @BindsOptionalOf
  abstract fun notifySettings(): NotifySettings

  @BindsOptionalOf
  abstract fun notificationAccess(): NotificationAccess

  @BindsOptionalOf
  abstract fun musicSettings(): MusicSettings

  @BindsOptionalOf
  abstract fun musicLibrary(): MusicLibrary

  @BindsOptionalOf
  abstract fun musicPlayback(): MusicPlayback

  @BindsOptionalOf
  abstract fun memoryClips(): MemoryClips
}
