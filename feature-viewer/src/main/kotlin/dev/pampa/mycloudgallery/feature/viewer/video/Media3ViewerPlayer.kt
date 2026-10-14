package dev.pampa.mycloudgallery.feature.viewer.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Il lettore vero, su Media3 ExoPlayer (D7).
 *
 * **Nessuna sorgente di default.** Le sorgenti sono `ContentDataSource` (l'URI `content://` del MediaStore, tutto o un
 * intervallo di byte) e, dalla Fase 4, [NasVideoDataSource] per i video del NAS (D19): mai la sorgente "di default" di
 * Media3, che apre anche `https://` (D24, vietata da un test sui sorgenti e dal test dei `DataSource` creati). Per questo il
 * costruttore dell'ExoPlayer riceve una [MediaSource.Factory] nostra invece di costruirne una da solo.
 *
 * **Un solo lettore, una superficie `SurfaceView`.** La superficie e' quella dell'HDR: HDR10 e HLG arrivano allo
 * schermo senza passare da una texture (che li appiattirebbe). Il focus audio lo prende solo un video con audio
 * acceso: una motion photo che si guarda in silenzio non deve zittire la musica di chi ascolta.
 *
 * **Scorrere.** [seekTo] imposta `CLOSEST_SYNC` o `EXACT` a ogni salto: mentre il dito trascina si va al fotogramma
 * chiave piu' vicino (nessuna decodifica dei fotogrammi in mezzo), al rilascio a quello esatto.
 */
@OptIn(UnstableApi::class)
class Media3ViewerPlayer(context: Context, private val nasVideo: NasVideoSource? = null) : ViewerPlayer {

  private val appContext = context.applicationContext
  private val handler = Handler(Looper.getMainLooper())
  private val player: ExoPlayer = ExoPlayer.Builder(appContext, ProgressiveMediaSource.Factory(contentOnly(appContext)))
    .setHandleAudioBecomingNoisy(true)
    .build()
  private val mutableState = MutableStateFlow(PlayerUiState())
  private var released = false

  override val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

  private val ticker = object : Runnable {
    override fun run() {
      if (released) return
      publish()
      if (player.isPlaying) handler.postDelayed(this, PositionPollMs)
    }
  }

  private val listener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      publish()
      handler.removeCallbacks(ticker)
      if (isPlaying) handler.post(ticker)
    }

    override fun onPlaybackStateChanged(playbackState: Int) = publish()

    override fun onRenderedFirstFrame() {
      mutableState.value = mutableState.value.copy(firstFrame = true)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
      mutableState.value = mutableState.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
    }

    override fun onTracksChanged(tracks: Tracks) {
      mutableState.value = mutableState.value.copy(hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO))
    }

    override fun onPlayerError(error: PlaybackException) {
      mutableState.value = mutableState.value.copy(playing = false, problem = playbackProblemOf(error.errorCode))
    }
  }

  init {
    player.addListener(listener)
  }

  override fun bind(surface: SurfaceView?) {
    if (released) return
    if (surface == null) player.clearVideoSurface() else player.setVideoSurfaceView(surface)
  }

  override fun load(source: PlaybackSource, autoplay: Boolean, muted: Boolean, startPositionMs: Long) {
    if (released) return
    val factory = dataSourceFactoryFor(source)
    if (factory == null) {
      // Un video del NAS senza il collegamento al NAS: niente da riprodurre, e lo si dice invece di restare muti.
      mutableState.value = PlayerUiState(source = source, muted = muted, problem = PlaybackProblem.NAS_UNREACHABLE)
      return
    }
    val mediaSource = ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory())
      .createMediaSource(MediaItem.fromUri(source.uri))
    player.setAudioAttributes(
      AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
      /* handleAudioFocus = */ !muted,
    )
    player.volume = if (muted) 0f else 1f
    mutableState.value = PlayerUiState(source = source, muted = muted)
    player.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = autoplay
  }

  override fun play() {
    if (released) return
    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
    player.play()
  }

  override fun pause() {
    if (!released) player.pause()
  }

  override fun stop() {
    if (released) return
    handler.removeCallbacks(ticker)
    player.stop()
    player.clearMediaItems()
    mutableState.value = PlayerUiState()
  }

  override fun seekTo(positionMs: Long, exact: Boolean) {
    if (released) return
    player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
    player.seekTo(positionMs.coerceAtLeast(0L))
    publish()
  }

  override fun setMuted(muted: Boolean) {
    if (released) return
    player.volume = if (muted) 0f else 1f
    mutableState.value = mutableState.value.copy(muted = muted)
  }

  override fun release() {
    if (released) return
    released = true
    handler.removeCallbacksAndMessages(null)
    player.removeListener(listener)
    player.release()
    mutableState.value = PlayerUiState()
  }

  /** La sola fabbrica di `DataSource` per [source]: contenuto locale, intervallo locale o video del NAS. Mai altro. */
  internal fun dataSourceFactoryFor(source: PlaybackSource): DataSource.Factory? = when (source) {
    is PlaybackSource.Whole -> contentOnly(appContext)
    is PlaybackSource.Range -> DataSource.Factory { RangeDataSource(ContentDataSource(appContext), source.start, source.length) }
    is PlaybackSource.Nas -> nasVideo?.let { nas -> DataSource.Factory { nas.newDataSource(source) } }
  }

  private fun publish() {
    if (released) return
    val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0L
    mutableState.value = mutableState.value.copy(
      positionMs = player.currentPosition.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE),
      durationMs = duration,
      playing = player.isPlaying,
      buffering = player.playbackState == Player.STATE_BUFFERING,
      ended = player.playbackState == Player.STATE_ENDED,
    )
  }

  private companion object {
    const val PositionPollMs = 250L

    fun contentOnly(context: Context): DataSource.Factory = DataSource.Factory { ContentDataSource(context) }
  }
}

/**
 * Una sorgente che espone solo i byte `[start, start + length)` della sorgente a monte, come se fossero un file a se':
 * e' la parte video di una motion photo, dentro il JPEG. L'estrattore MP4 legge dal suo "inizio" (la posizione 0 e'
 * il byte [start] del file vero) e non vede mai i byte della foto.
 */
@OptIn(UnstableApi::class)
internal class RangeDataSource(
  private val upstream: DataSource,
  private val start: Long,
  private val length: Long,
) : DataSource {

  override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

  override fun open(dataSpec: DataSpec): Long {
    val remaining = (length - dataSpec.position).coerceAtLeast(0L)
    val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else minOf(dataSpec.length, remaining)
    val shifted = dataSpec.buildUpon().setPosition(start + dataSpec.position).setLength(requested).build()
    upstream.open(shifted)
    return requested
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

  override fun getUri() = upstream.uri

  override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

  override fun close() = upstream.close()
}

/**
 * I codici per cui il dispositivo "non riesce a riprodurre": decoder assente o che non si avvia, formato fuori dalle sue
 * capacita' (8K, un profilo HDR, APV), contenitore non supportato. Poi, dalla Fase 4, la rete verso il NAS che non c'e'.
 * Tutto il resto e' un file che non si legge o e' rovinato. E' la differenza fra "Questo dispositivo non riesce a riprodurre il video" (E-M7, E-M13) e "Non riesco a
 * riprodurre il video".
 */
internal fun playbackProblemOf(errorCode: Int): PlaybackProblem = when (errorCode) {
  PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
  PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
  PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
  PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
  PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
  -> PlaybackProblem.UNSUPPORTED
  // Fase 4: il NAS non risponde o la rete non c'e' (E-L4). Dal telefono questi codici non arrivano mai.
  PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
  PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
  -> PlaybackProblem.NAS_UNREACHABLE
  else -> PlaybackProblem.FAILED
}
