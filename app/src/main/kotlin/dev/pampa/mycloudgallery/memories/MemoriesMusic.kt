package dev.pampa.mycloudgallery.memories

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CancellationException
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
 * vocale fermano la musica e la presentazione; il focus perso per sempre la ferma).
 *
 * **Il brano si apre fuori dal thread principale** (MU-1, 31/10): `setDataSource` e `prepare` su un documento remoto o lento (Drive, una scheda SD) bloccano chi li chiama,
 * e prima giravano sul thread principale: la presentazione si fermava al titolo e il tocco non rispondeva (con un fornitore che non risponde, un ANR). Ora [start] e' una
 * `suspend` che apre su `Dispatchers.IO`, si annulla con l'uscita dalla presentazione e, se nel frattempo e' arrivato uno [stop] o un altro [start], rilascia quello che
 * aveva preso e non fa partire niente (`generation`).
 *
 * **Un focus negato non e' un lettore** (MU-2): [start] torna falso e chi chiama dice "musica mancante" (muta, con l'avviso); prima emetteva `FOCUS_LOST`, la presentazione si
 * fermava e un tocco la faceva andare avanti muta con lo stato "in riproduzione".
 */
class AndroidMusicPlayback(context: Context) : MusicPlayback {
  private val app = context.applicationContext
  private val audio = app.getSystemService(AudioManager::class.java)
  private val eventsFlow = MutableSharedFlow<MusicEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val lock = Any()

  /** Una partenza per volta: ogni [start] e ogni [stop] la cambiano, cosi' un'apertura lenta che finisce dopo un'uscita non fa partire niente. */
  private var generation = 0
  private var player: MediaPlayer? = null
  private var focus: AudioFocusRequest? = null

  override val events: Flow<MusicEvent> = eventsFlow.asSharedFlow()

  private val listener = AudioManager.OnAudioFocusChangeListener { change ->
    when (change) {
      AudioManager.AUDIOFOCUS_LOSS -> {
        synchronized(lock) { releasePlayerLocked() }
        eventsFlow.tryEmit(MusicEvent.FOCUS_LOST_FOR_GOOD)
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        synchronized(lock) { player?.takeIf { it.isPlaying }?.pause() }
        eventsFlow.tryEmit(MusicEvent.FOCUS_LOST)
      }
      AudioManager.AUDIOFOCUS_GAIN -> eventsFlow.tryEmit(MusicEvent.FOCUS_GAINED)
    }
  }

  override suspend fun start(source: MusicSource): Boolean {
    val mine = synchronized(lock) {
      stopLocked()
      ++generation
    }
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener).build()
    // MU-2: il sistema nega l'audio (una chiamata in corso): niente lettore, e chi chiama lo sa.
    if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
    val created = MediaPlayer()
    val usable = synchronized(lock) {
      if (generation == mine) {
        focus = request
        player = created
        true
      } else {
        false
      }
    }
    if (!usable) {
      runCatching { audio?.abandonAudioFocusRequest(request) }
      created.release()
      return false
    }
    return try {
      created.setOnErrorListener { _, _, _ ->
        eventsFlow.tryEmit(MusicEvent.MISSING)
        true
      }
      // MU-1: l'apertura del documento e `prepare` (che aspettano il fornitore) fuori dal thread principale.
      withContext(Dispatchers.IO) {
        created.setAudioAttributes(attributes)
        created.setDataSource(app, Uri.parse(source.token))
        created.isLooping = true
        created.setVolume(VOLUME, VOLUME)
        created.prepare()
      }
      synchronized(lock) {
        if (generation == mine) {
          created.start()
          true
        } else {
          false
        }
      }
    } catch (e: CancellationException) {
      synchronized(lock) { if (generation == mine) stopLocked() }
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "musica non aperta: ${e.javaClass.simpleName}" }
      synchronized(lock) { if (generation == mine) stopLocked() }
      false
    }
  }

  override fun pause() {
    synchronized(lock) { player?.takeIf { it.isPlaying }?.pause() }
  }

  override fun resume() {
    synchronized(lock) { player?.takeIf { !it.isPlaying }?.start() }
  }

  override fun stop() {
    synchronized(lock) { stopLocked() }
  }

  private fun stopLocked() {
    generation++
    releasePlayerLocked()
    focus?.let { runCatching { audio?.abandonAudioFocusRequest(it) } }
    focus = null
  }

  private fun releasePlayerLocked() {
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

/**
 * I permessi di lettura persistiti delle canzoni scelte con il selettore di sistema (I4 della revisione di sicurezza, 31/10). Il permesso lo prende la pagina alla scelta
 * (`takePersistableUriPermission`); prima non lo rilasciava nessuno, e l'app teneva il diritto di leggere ogni file audio mai scelto (il sistema ne ricorda gli URI anche
 * dopo "dispositivo azzerato"). Ora si rilascia quello della canzone che si lascia ([PermissionReleasingMusicSettings]) e, all'azzeramento, **tutti** quelli dell'app.
 */
object MusicPermissions {

  /** Rilascia il permesso di lettura persistito del documento [token] (un `content://`); un permesso che non c'e' (piu') non e' un errore. */
  fun release(context: Context, token: String) {
    try {
      context.contentResolver.releasePersistableUriPermission(Uri.parse(token), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
      // Non c'era (piu'): niente da rilasciare.
    } catch (e: IllegalArgumentException) {
      GalleryLog.w("MemoriesMusic") { "permesso della canzone non rilasciato: ${e.javaClass.simpleName}" }
    }
  }

  /** Rilascia **tutti** i permessi persistiti dell'app (l'azzeramento): quanti ne ha rilasciati. L'app non ne prende altri che quelli delle canzoni dei ricordi. */
  fun releaseAll(context: Context): Int {
    val resolver = context.contentResolver
    var released = 0
    // Una copia: rilasciare toglie dall'elenco del sistema, e un elenco vivo scorrerebbe saltando i permessi (come fanno le prove con Robolectric).
    for (permission in resolver.persistedUriPermissions.toList()) {
      val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
        (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
      try {
        resolver.releasePersistableUriPermission(permission.uri, flags)
        released++
      } catch (e: SecurityException) {
        // Gia' tolto da altri: va bene cosi'.
      }
    }
    return released
  }
}

/**
 * La scelta della musica che a ogni cambio di canzone **rilascia il permesso della vecchia** (I4, 31/10): scegliere un'altra canzone, "Nessuna" o un brano del pacchetto
 * toglie il diritto di leggere il file che si lascia. Se la scelta nuova e' la stessa canzone (stesso documento) il permesso resta. [release] riceve il riferimento della
 * canzone lasciata; si chiama solo a scrittura riuscita.
 */
class PermissionReleasingMusicSettings(private val inner: MusicSettings, private val release: (String) -> Unit) : MusicSettings {
  override val choice: StateFlow<MusicChoice> get() = inner.choice

  override suspend fun set(choice: MusicChoice) {
    val old = inner.choice.value
    inner.set(choice)
    if (old is MusicChoice.OwnSong && old != choice) release(old.uriToken)
  }
}
