package dev.pampa.mycloudgallery.feature.viewer.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chi sta usando il lettore, per tutto il viewer: **un solo [ViewerPlayer]**, creato la prima volta che serve e riusato
 * da un video all'altro e dalle motion photo. Un decoder acceso per ogni pagina vicina al pager (precarico ±1) non e'
 * un'opzione: i decoder video sono poche istanze per telefono, e due colonne sonore sono un difetto, non una funzione.
 *
 * Il lettore e' di una pagina alla volta: [videoFor] e' il video che si sta guardando (parte da solo quando la pagina
 * si ferma, D19) e [motionFor] la motion photo che si muove, in uno dei due modi di [MotionMode]: il tratto d'apertura, muto
 * (D50), o il video intero dal badge "Movimento", con l'audio (D51). Cambiare pagina, chiudere o uscire dall'app liberano
 * il decoder.
 */
@Stable
internal class ViewerPlayback(private val factory: ViewerPlayerFactory) {

  /** Il lettore, o null finche' nessuno ne ha avuto bisogno. */
  var player: ViewerPlayer? by mutableStateOf(null)
    private set

  /** Il video in riproduzione (pagina assestata). */
  var videoFor: MediaId? by mutableStateOf(null)
    private set

  /** La motion photo che si sta riproducendo (animazione d'apertura o video dal badge). */
  var motionFor: MediaId? by mutableStateOf(null)
    private set

  /** Come si sta riproducendo [motionFor]; null se non si riproduce. */
  var motionMode: MotionMode? by mutableStateOf(null)
    private set

  /** La riproduzione di [motionFor] e' finita e la foto sta tornando con la dissolvenza: il video resta sotto fino al suo termine. */
  var motionFading: Boolean by mutableStateOf(false)
    private set

  /** Numero di lettori creati in tutta la vita di questo oggetto: deve restare 1 (un solo player). */
  var created: Int = 0
    private set

  /** Carica e fa partire il video di [id]. Con l'audio, e prendendo il focus audio. */
  fun playVideo(id: MediaId, source: PlaybackSource, startPositionMs: Long = 0L) {
    obtain().load(source, autoplay = true, muted = false, startPositionMs = startPositionMs)
    videoFor = id
    clearMotion()
  }

  /**
   * Riproduce la parte video della motion photo [id]. [MotionMode.OPENING]: il tratto [clip] (tagliato), **muto**, senza
   * prendere il focus audio. [MotionMode.FULL]: il video intero, **con l'audio** al volume dei contenuti multimediali.
   */
  fun playMotion(id: MediaId, source: PlaybackSource, mode: MotionMode, clip: PlaybackClip? = null) {
    require((mode == MotionMode.OPENING) == (clip != null)) { "Il tratto tagliato e' solo dell'animazione d'apertura." }
    obtain().load(source, autoplay = true, muted = mode == MotionMode.OPENING, clip = clip)
    motionFor = id
    motionMode = mode
    motionFading = false
    videoFor = null
  }

  /** La riproduzione e' arrivata alla fine: la foto torna con la dissolvenza, il decoder si libera con [stopMotion]. */
  fun fadeMotion() {
    if (motionFor != null) motionFading = true
  }

  /** La motion photo non si muove piu': si ferma e la foto torna ferma. */
  fun stopMotion() {
    if (motionFor == null) return
    player?.stop()
    clearMotion()
  }

  /** Cambio pagina o uscita: ferma tutto e libera il decoder, tenendo il lettore. */
  fun clear() {
    if (videoFor == null && motionFor == null) return
    player?.stop()
    videoFor = null
    clearMotion()
  }

  fun pause() {
    player?.pause()
  }

  /** Libera il lettore. Dopo questo se ne ricrea uno al bisogno. */
  fun release() {
    player?.release()
    player = null
    videoFor = null
    clearMotion()
  }

  private fun clearMotion() {
    motionFor = null
    motionMode = null
    motionFading = false
  }

  private fun obtain(): ViewerPlayer {
    player?.let { return it }
    return factory.create().also {
      player = it
      created++
    }
  }
}

/** Come si muove una foto in movimento (D50, D51). */
internal enum class MotionMode {
  /** L'animazione d'apertura: il tratto che precede la foto, muto. */
  OPENING,

  /** Il video intero dal badge "Movimento": una volta, con l'audio. */
  FULL,
}

private val Idle: StateFlow<PlayerUiState> = MutableStateFlow(PlayerUiState())

/** Lo stato del lettore come stato di Compose; fermo se il lettore non c'e' ancora. */
@Composable
internal fun ViewerPlayback.collectPlayerState(): PlayerUiState {
  val flow = remember(player) { player?.state ?: Idle }
  val state by flow.collectAsState()
  return state
}
