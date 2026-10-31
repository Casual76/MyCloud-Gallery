package dev.pampa.mycloudgallery.feature.memories.testing

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MusicSource
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MusicEvent
import dev.pampa.mycloudgallery.feature.memories.contract.MusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerImages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

// I FAKE DELLE PORTE DELLA PRESENTAZIONE E DELLE IMPOSTAZIONI (Fase 9, P9, §5.2): per le prove di P9 e di P13. Fedeli alla regola di ogni porta (mai
// un'attesa, mai un nome salvato); non fanno rete e non toccano il disco.

/** Un lettore audio finto: ricorda cosa gli e' stato chiesto e fa arrivare gli eventi del focus a comando ([emit]). */
class FakeMusicPlayback : MusicPlayback {
  private val shared = MutableSharedFlow<MusicEvent>(extraBufferCapacity = 16)
  override val events: Flow<MusicEvent> = shared.asSharedFlow()

  /** Cosa e' successo, nell'ordine: "start", "pause", "resume", "stop" (mai il brano). */
  val calls: MutableList<String> = ArrayList()

  /** Vero tra uno "start" riuscito e uno "stop". */
  var started: Boolean = false
    private set

  /** Come va il prossimo "start": vero = un lettore e' partito; falso = focus negato o file che non si apre (MU-2). */
  var startResult: Boolean = true

  /** Se non e' nullo, "start" aspetta questo (un fornitore lento: MU-1); si completa dal test. */
  var startGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

  override suspend fun start(source: MusicSource): Boolean {
    calls += "start"
    startGate?.await()
    started = startResult
    return startResult
  }

  override fun pause() {
    calls += "pause"
  }

  override fun resume() {
    calls += "resume"
  }

  override fun stop() {
    calls += "stop"
    started = false
  }

  /** Un fatto dal sistema (la chiamata, il file che non c'e' piu'). */
  fun emit(event: MusicEvent) {
    check(shared.tryEmit(event)) { "evento perso" }
  }
}

/** Le immagini della presentazione finte: ricordano cosa e' stato chiesto (mai attese), nessuna anteprima. */
class RecordingPlayerImages : PlayerImages {
  /** Ogni richiesta: l'elemento di adesso e i successivi. */
  val wants: MutableList<Pair<MediaId, List<MediaId>>> = ArrayList()
  var cleared: Int = 0
    private set

  override fun want(snapshot: TimelineSnapshot, current: MediaId, next: List<MediaId>) {
    wants += current to next
  }

  override fun previewOf(media: MediaId): ImageBitmap? = null

  override fun clear() {
    cleared++
  }
}

/** Il pacchetto di musica finto: i brani e il loro stato a comando; [open] da' il file solo per i brani pronti e per la canzone propria che c'e'. */
class FakeMusicLibrary(initial: Map<String, MusicTrackState> = emptyMap()) : MusicLibrary {
  private val state = MutableStateFlow(initial)
  override val tracks: StateFlow<Map<String, MusicTrackState>> = state.asStateFlow()

  /** Le canzoni proprie che si aprono ancora (i riferimenti scelti): le altre sono "sparite". */
  val availableSongs: MutableSet<String> = HashSet()

  var installs: Int = 0
    private set

  fun set(trackId: String, trackState: MusicTrackState) {
    state.value = state.value + (trackId to trackState)
  }

  override suspend fun install(trackId: String): Boolean {
    installs++
    set(trackId, MusicTrackState.Installing(0))
    return true
  }

  override suspend fun open(choice: MusicChoice): MusicSource? = when (choice) {
    MusicChoice.None -> null
    is MusicChoice.OwnSong -> if (choice.uriToken in availableSongs) MusicSource(choice.uriToken) else null
    is MusicChoice.FreeTrack -> if (state.value[choice.trackId] == MusicTrackState.Ready) MusicSource(choice.trackId) else null
  }
}

/** Il permesso delle notifiche finto: lo stato a comando. */
class FakeNotificationAccess(initial: NotifyPermission = NotifyPermission.GRANTED) : NotificationAccess {
  private val current = MutableStateFlow(initial)
  override val state: StateFlow<NotifyPermission> = current.asStateFlow()

  var refreshes: Int = 0
    private set

  fun set(permission: NotifyPermission) {
    current.value = permission
  }

  override fun refresh() {
    refreshes++
  }
}
