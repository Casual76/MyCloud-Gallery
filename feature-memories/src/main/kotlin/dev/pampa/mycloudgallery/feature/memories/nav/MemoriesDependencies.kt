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
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.MusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NoMusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.memories.contract.UnboundNotificationAccess
import dev.pampa.mycloudgallery.feature.memories.noop.SessionMusicSettings
import dev.pampa.mycloudgallery.feature.memories.noop.SessionNotifySettings
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import java.util.Optional
import javax.inject.Inject

// LE PORTE DELLE SCHERMATE DEI RICORDI PER DAGGER (Fase 9, P9; D7 della Fase 2: @HiltViewModel; docs/fase9-progetto.md §1.4). Tutte **opzionali**, tranne la maschera
// dei Nascosti (R3, 31/10: il filtro finale non puo' tornare muto in silenzio, quindi la chiede senza `Optional` e Hilt la deve avere):
// `:app` (P13, `MemoriesGraph`) lega i contratti veri (il motore dei ricordi, le persone, i nomi dei luoghi, i Nascosti, le preferenze, il lettore
// audio); finche' non lo fa le schermate partono lo stesso con i null-object, che **non fanno niente di pericoloso** (nessun ricordo, nessuna
// persona, nessun audio) e il grafo di `:app` compila. Le preferenze (notifiche e musica) senza legame stanno in memoria (`noop/SessionSettings.kt`, mai i
// fake di `testing/`: P13 li ha tolti perche' entravano nel dex di release): valgono finche' vive il processo, e P13 lega quelle vere su file.

/** I contratti con cui parlano le schermate dei ricordi, gia' risolti sui null-object quando `:app` non li ha legati. */
class MemoriesDependencies @Inject constructor(
  memories: Optional<Memories>,
  people: Optional<MemoryPeople>,
  places: Optional<MemoryPlaceNames>,
  /** I Nascosti: **non opzionale** (R3, 31/10). La maschera la lega sempre `:app` (`GalleryModule`); senza, `:app` non compila. */
  mask: HiddenMask,
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

  /** Il filtro finale dei Nascosti: la maschera c'e' sempre (un legame che manca e' un errore di compilazione, non un filtro muto). */
  val filter: FinalFilter = FinalFilter(mask)
  val notify: NotifySettings = notify.orElseGet { SessionNotifySettings() }
  val access: NotificationAccess = access.orElse(UnboundNotificationAccess)
  val musicSettings: MusicSettings = musicSettings.orElseGet { SessionMusicSettings() }
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
