package dev.pampa.mycloudgallery.feature.viewer.testing

import android.view.SurfaceView
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetailsSource
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerBytes
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlayerUiState
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// I fake del viewer (D23): in src/main, cosi' :app (solo debug) e i test li usano senza cicli. Mai nella release:
// tools/check.ps1 fallisce se una classe di un package `testing` compare nel dex dell'APK di release. Nessun codice di
// produzione di questo modulo li referenzia.

/** Una chiamata registrata dal lettore finto, nell'ordine in cui e' arrivata. */
sealed interface PlayerCall {
  data class Load(val source: PlaybackSource, val autoplay: Boolean, val muted: Boolean, val startMs: Long) : PlayerCall

  data class Seek(val positionMs: Long, val exact: Boolean) : PlayerCall

  data object Play : PlayerCall

  data object Pause : PlayerCall

  data object Stop : PlayerCall

  data class Mute(val muted: Boolean) : PlayerCall

  data object Release : PlayerCall
}

/**
 * Un lettore che non decodifica niente: registra le chiamate e lascia al test lo stato ([emit]). Serve a provare che il
 * viewer ne usa **uno solo**, che parte quando la pagina si ferma, che scorre con `CLOSEST_SYNC` e rilascia con
 * `EXACT`, e a disegnare la barra del video negli screenshot.
 */
class FakeViewerPlayer : ViewerPlayer {

  private val mutable = MutableStateFlow(PlayerUiState())
  override val state: StateFlow<PlayerUiState> = mutable.asStateFlow()

  val calls: MutableList<PlayerCall> = mutableListOf()
  var boundSurface: SurfaceView? = null
    private set
  var released: Boolean = false
    private set

  val loads: List<PlayerCall.Load> get() = calls.filterIsInstance<PlayerCall.Load>()
  val seeks: List<PlayerCall.Seek> get() = calls.filterIsInstance<PlayerCall.Seek>()

  /** Cambia lo stato come farebbe il decoder (posizione, primo fotogramma, errore...). */
  fun emit(change: (PlayerUiState) -> PlayerUiState) {
    mutable.value = change(mutable.value)
  }

  /** Il decoder non regge il formato (E-M7, E-M13). */
  fun failWith(problem: PlaybackProblem) = emit { it.copy(playing = false, problem = problem) }

  override fun bind(surface: SurfaceView?) {
    boundSurface = surface
  }

  override fun load(source: PlaybackSource, autoplay: Boolean, muted: Boolean, startPositionMs: Long) {
    calls += PlayerCall.Load(source, autoplay, muted, startPositionMs)
    mutable.value = PlayerUiState(source = source, playing = autoplay, positionMs = startPositionMs, muted = muted, durationMs = DEFAULT_DURATION_MS)
  }

  override fun play() {
    calls += PlayerCall.Play
    emit { it.copy(playing = true, ended = false) }
  }

  override fun pause() {
    calls += PlayerCall.Pause
    emit { it.copy(playing = false) }
  }

  override fun stop() {
    calls += PlayerCall.Stop
    mutable.value = PlayerUiState()
  }

  override fun seekTo(positionMs: Long, exact: Boolean) {
    calls += PlayerCall.Seek(positionMs, exact)
    emit { it.copy(positionMs = positionMs) }
  }

  override fun setMuted(muted: Boolean) {
    calls += PlayerCall.Mute(muted)
    emit { it.copy(muted = muted) }
  }

  override fun release() {
    calls += PlayerCall.Release
    released = true
  }

  companion object {
    const val DEFAULT_DURATION_MS: Long = 134_000L
  }
}

/** I dettagli scelti dal test; chi non c'e' nella mappa non e' nel catalogo. */
class FakeMediaDetailsSource(private val details: Map<MediaId, MediaDetails> = emptyMap()) : MediaDetailsSource {

  /** Quante volte e' stato chiesto ogni id (la cache del ViewModel non deve rileggere). */
  val requests: MutableMap<MediaId, Int> = HashMap()

  override suspend fun details(id: MediaId): MediaDetails? {
    requests[id] = (requests[id] ?: 0) + 1
    return details[id]
  }
}

/** I byte di un file in memoria, per l'EXIF e le motion photo di prova. */
class FakeViewerBytes(private val files: Map<MediaRef, MediaBytes> = emptyMap()) : ViewerBytes {
  override suspend fun open(ref: MediaRef): MediaBytes? = files[ref]
}

/** Un localizzatore di motion photo scelto dal test: l'intervallo di un id, o il formato non riconosciuto (E-M16). */
class FakeMotionVideoLocator(private val ranges: Map<MediaRef, ByteRange> = emptyMap()) : MotionVideoLocator {
  val requests: MutableList<MediaRef> = mutableListOf()

  override suspend fun locate(ref: MediaRef): MotionVideo {
    requests += ref
    val range = ranges[ref] ?: return MotionVideo.Unrecognized
    return MotionVideo.Found(range)
  }
}
