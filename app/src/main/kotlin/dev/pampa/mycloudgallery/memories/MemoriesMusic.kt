package dev.pampa.mycloudgallery.memories

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MusicPackCatalog
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.memories.contract.MusicSource
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.feature.memories.contract.MusicEvent
import dev.pampa.mycloudgallery.feature.memories.contract.MusicPlayback
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// LA MUSICA DELLA PRESENTAZIONE (Fase 9, P13; docs/fase9-progetto.md D13, §11 Q3; "per P13" di P9). Di serie nessuna musica (Q3); il pacchetto di musica
// libera e' vuoto finche' l'utente non approva fonti e brani (`MusicPackCatalog.TRACKS`); la canzone propria e' un file del telefono scelto con il selettore
// di sistema (`ACTION_OPEN_DOCUMENT`, permesso persistito), **mai copiato** e mai sul NAS. Si suona con il `MediaPlayer` di Android (nessuna dipendenza:
// Media3 resta solo in feature-viewer, D7) e con il **focus audio**. Nei log solo nomi di classi.

/**
 * La scelta della musica **solo su questo dispositivo** (`noBackupFilesDir/memories/music`, l'azzeramento di `memories/` la toglie): una riga col tipo e,
 * per la canzone propria, il riferimento del documento (un `content://` del selettore, mai un percorso), per il brano libero il suo id. Un file rovinato o
 * sconosciuto vale "nessuna musica". La scrittura e' atomica; il flusso cambia solo a scrittura riuscita.
 */
class FileMusicSettings(directory: File) : MusicSettings {
  private val file = File(directory, FILE)
  private val state = MutableStateFlow(read())

  override val choice: StateFlow<MusicChoice> = state.asStateFlow()

  override suspend fun set(choice: MusicChoice) {
    withContext(Dispatchers.IO) { StateFiles.writeText(file, encode(choice)) }
    state.value = choice
  }

  private fun read(): MusicChoice = try {
    StateFiles.readText(file)?.let(::decode) ?: MusicChoice.None
  } catch (e: IOException) {
    MusicChoice.None
  }

  companion object {
    const val FILE: String = "music"

    fun encode(choice: MusicChoice): String = when (choice) {
      MusicChoice.None -> "none"
      is MusicChoice.OwnSong -> "own\n${choice.uriToken}"
      is MusicChoice.FreeTrack -> "free\n${choice.trackId}"
    }

    fun decode(text: String): MusicChoice {
      val lines = text.split('\n')
      return try {
        when (lines.firstOrNull()) {
          "own" -> lines.getOrNull(1)?.takeIf { it.startsWith("content://") }?.let { MusicChoice.OwnSong(it) } ?: MusicChoice.None
          "free" -> lines.getOrNull(1)?.takeIf { MusicPackCatalog.byId(it) != null }?.let { MusicChoice.FreeTrack(it) } ?: MusicChoice.None
          else -> MusicChoice.None
        }
      } catch (e: IllegalArgumentException) {
        MusicChoice.None
      }
    }
  }
}

/**
 * I brani della presentazione (`MusicLibrary`): il pacchetto libero e' vuoto (Q3: nessun brano senza l'approvazione dell'utente), quindi niente da
 * installare; la canzone propria si apre solo se il permesso persistito c'e' ancora ([persisted]), altrimenti null (la presentazione resta muta e dice
 * "il file non c'e' piu'", E-MEM18).
 */
class AppMusicLibrary(private val persisted: (Uri) -> Boolean) : MusicLibrary {
  override val tracks: StateFlow<Map<String, MusicTrackState>> =
    MutableStateFlow(MusicPackCatalog.TRACKS.associate { it.id to MusicTrackState.NotInstalled as MusicTrackState }).asStateFlow()

  override suspend fun install(trackId: String): Boolean = false

  override suspend fun open(choice: MusicChoice): MusicSource? = when (choice) {
    is MusicChoice.OwnSong -> Uri.parse(choice.uriToken).takeIf(persisted)?.let { MusicSource(choice.uriToken) }
    MusicChoice.None, is MusicChoice.FreeTrack -> null
  }

  companion object {
    /** Il permesso di lettura persistito del documento c'e' ancora (il file e' stato scelto con il selettore e non e' stato revocato). */
    fun persistedOf(context: Context): (Uri) -> Boolean = { uri ->
      context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }
  }
}

/**
 * Il lettore della musica (`MusicPlayback`, Q3, D13): un brano solo, in ripetizione, a volume moderato, con il **focus audio** (una chiamata o un messaggio
 * vocale fermano la musica e la presentazione; il focus perso per sempre la ferma). Un file che non si apre emette [MusicEvent.MISSING].
 */
class AndroidMusicPlayback(context: Context) : MusicPlayback {
  private val app = context.applicationContext
  private val audio = app.getSystemService(AudioManager::class.java)
  private val eventsFlow = MutableSharedFlow<MusicEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private var player: MediaPlayer? = null
  private var focus: AudioFocusRequest? = null

  override val events: Flow<MusicEvent> = eventsFlow.asSharedFlow()

  private val listener = AudioManager.OnAudioFocusChangeListener { change ->
    when (change) {
      AudioManager.AUDIOFOCUS_LOSS -> {
        stopPlayer()
        eventsFlow.tryEmit(MusicEvent.FOCUS_LOST_FOR_GOOD)
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        player?.takeIf { it.isPlaying }?.pause()
        eventsFlow.tryEmit(MusicEvent.FOCUS_LOST)
      }
      AudioManager.AUDIOFOCUS_GAIN -> eventsFlow.tryEmit(MusicEvent.FOCUS_GAINED)
    }
  }

  override fun start(source: MusicSource) {
    stop()
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener).build()
    if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      eventsFlow.tryEmit(MusicEvent.FOCUS_LOST)
      return
    }
    focus = request
    try {
      player = MediaPlayer().apply {
        setAudioAttributes(attributes)
        setDataSource(app, Uri.parse(source.token))
        isLooping = true
        setVolume(VOLUME, VOLUME)
        prepare()
        start()
      }
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "musica non aperta: ${e.javaClass.simpleName}" }
      stop()
      eventsFlow.tryEmit(MusicEvent.MISSING)
    }
  }

  override fun pause() {
    player?.takeIf { it.isPlaying }?.pause()
  }

  override fun resume() {
    player?.takeIf { !it.isPlaying }?.start()
  }

  override fun stop() {
    stopPlayer()
    focus?.let { audio?.abandonAudioFocusRequest(it) }
    focus = null
  }

  private fun stopPlayer() {
    val current = player ?: return
    player = null
    runCatching { current.stop() }
    current.release()
  }

  private companion object {
    const val TAG = "MemoriesMusic"

    /** Volume moderato (Q3): la musica accompagna, non copre. */
    const val VOLUME = 0.6f
  }
}
