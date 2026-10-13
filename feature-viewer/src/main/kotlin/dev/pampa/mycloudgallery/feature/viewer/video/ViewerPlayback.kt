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
 * si ferma, D19) e [motionFor] la motion photo tenuta premuta (in silenzio). Cambiare pagina, chiudere o uscire
 * dall'app liberano il decoder.
 */
@Stable
internal class ViewerPlayback(private val factory: ViewerPlayerFactory) {

  /** Il lettore, o null finche' nessuno ne ha avuto bisogno. */
  var player: ViewerPlayer? by mutableStateOf(null)
    private set

  /** Il video in riproduzione (pagina assestata). */
  var videoFor: MediaId? by mutableStateOf(null)
    private set

  /** La motion photo che si sta riproducendo (dito premuto). */
  var motionFor: MediaId? by mutableStateOf(null)
    private set

  /** Numero di lettori creati in tutta la vita di questo oggetto: deve restare 1 (un solo player). */
  var created: Int = 0
    private set

  /** Carica e fa partire il video di [id]. Con l'audio, e prendendo il focus audio. */
  fun playVideo(id: MediaId, source: PlaybackSource, startPositionMs: Long = 0L) {
    obtain().load(source, autoplay = true, muted = false, startPositionMs = startPositionMs)
    videoFor = id
    motionFor = null
  }

  /** Riproduce la parte video della motion photo [id], in silenzio. */
  fun playMotion(id: MediaId, source: PlaybackSource) {
    obtain().load(source, autoplay = true, muted = true)
    motionFor = id
    videoFor = null
  }

  /** La motion photo non e' piu' premuta: si ferma e la foto torna ferma. */
  fun stopMotion() {
    if (motionFor == null) return
    player?.stop()
    motionFor = null
  }

  /** Cambio pagina o uscita: ferma tutto e libera il decoder, tenendo il lettore. */
  fun clear() {
    if (videoFor == null && motionFor == null) return
    player?.stop()
    videoFor = null
    motionFor = null
  }

  fun pause() {
    player?.pause()
  }

  /** Libera il lettore. Dopo questo se ne ricrea uno al bisogno. */
  fun release() {
    player?.release()
    player = null
    videoFor = null
    motionFor = null
  }

  private fun obtain(): ViewerPlayer {
    player?.let { return it }
    return factory.create().also {
      player = it
      created++
    }
  }
}

private val Idle: StateFlow<PlayerUiState> = MutableStateFlow(PlayerUiState())

/** Lo stato del lettore come stato di Compose; fermo se il lettore non c'e' ancora. */
@Composable
internal fun ViewerPlayback.collectPlayerState(): PlayerUiState {
  val flow = remember(player) { player?.state ?: Idle }
  val state by flow.collectAsState()
  return state
}
