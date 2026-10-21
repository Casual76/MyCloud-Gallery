package dev.pampa.mycloudgallery.feature.viewer.video

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.flow.StateFlow

// Il lettore video del viewer, dietro un'interfaccia (docs/fase3-progetto.md D7): la UI parla con [ViewerPlayer]; la
// realizzazione vera sta su Media3 ExoPlayer ([Media3ViewerPlayer]) e nei test e negli screenshot c'e' un fake. Un solo
// lettore per tutto il viewer, riusato da una pagina all'altra: due decoder video insieme sono la ricetta per finire
// la memoria su un telefono, e per sentire due colonne sonore.

/**
 * Cosa riprodurre. Mai una rete qualunque (D24: niente `DefaultDataSource`, che aprirebbe anche `https://`): un URI
 * `content://` del MediaStore, tutto o un intervallo di byte (la parte video di una motion photo), oppure, dalla Fase 4, un
 * video del NAS letto da `NasVideoDataSource` (D19) sopra i contratti di `core-nas`.
 */
sealed interface PlaybackSource {

  val uri: Uri

  /** Il file intero. */
  data class Whole(override val uri: Uri) : PlaybackSource

  /** Solo i byte `[start, start + length)` del file, come se fossero un file a se' (motion photo). */
  data class Range(override val uri: Uri, val start: Long, val length: Long) : PlaybackSource {
    init {
      require(start >= 0 && length > 0) { "Intervallo di byte non valido." }
    }
  }

  /**
   * Un video del NAS (Fase 4, D19), in streaming a intervalli. [uri] e' un'etichetta senza percorsi (`nasmedia://v/<id>.mp4`,
   * l'estensione aiuta l'estrattore): i byte non si cercano a quell'indirizzo ma si leggono dal media [ref], risolto dal
   * catalogo solo all'apertura. Nessun altro indirizzo si apre mai con questa sorgente.
   */
  data class Nas(val ref: MediaRef.Nas, override val uri: Uri) : PlaybackSource {
    override fun toString(): String = "PlaybackSource.Nas"
  }
}

/**
 * Il tratto di una sorgente da riprodurre, in millisecondi: la sorgente si **taglia** a `[startMs, endMs]` e il lettore finisce
 * a [endMs] (stato `ended`). Lo usa l'animazione d'apertura delle foto in movimento (D50): il tratto che precede l'istante della foto.
 */
data class PlaybackClip(val startMs: Long, val endMs: Long) {
  init {
    require(startMs >= 0 && endMs > startMs) { "Tratto non valido." }
  }

  val durationMs: Long get() = endMs - startMs
}

/** Perche' il video non parte. */
enum class PlaybackProblem {
  /** Il decoder non c'e' o non regge il formato (8K, APV, un profilo HDR): "Questo dispositivo non riesce a riprodurre il video". */
  UNSUPPORTED,

  /** Il file non si legge o e' rovinato. */
  FAILED,

  /** Il NAS non risponde o la rete non e' ammessa (Fase 4, E-L4): il video riparte con "Riprova". */
  NAS_UNREACHABLE,
}

/** Lo stato del lettore, tutto quello che la UI ha bisogno di disegnare. */
@Immutable
data class PlayerUiState(
  val source: PlaybackSource? = null,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val playing: Boolean = false,
  val buffering: Boolean = false,
  val ended: Boolean = false,
  /** Vero dal primo fotogramma disegnato: fino ad allora si vede la miniatura. */
  val firstFrame: Boolean = false,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val hasAudio: Boolean = false,
  val muted: Boolean = false,
  val problem: PlaybackProblem? = null,
) {
  /** La proporzione del video che si vede, o null se ancora non e' nota. */
  val aspect: Float? get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else null
}

/**
 * Il lettore. Tutti i metodi si chiamano dal thread principale. [state] riflette sempre l'ultimo stato noto.
 *
 * Fino al primo fotogramma ([PlayerUiState.firstFrame]) la superficie e' vuota: chi la disegna tiene la miniatura sopra.
 */
interface ViewerPlayer {

  val state: StateFlow<PlayerUiState>

  /** Collega (o scollega, con null) la superficie su cui disegnare: una `SurfaceView`, per l'HDR. */
  fun bind(surface: SurfaceView?)

  /**
   * Prepara [source] e, con [autoplay], parte. Sostituisce cio' che c'era. [muted] spegne l'audio (l'animazione d'apertura di
   * una motion photo e' muta); un video normale, e il video intero dal badge "Movimento", partono con l'audio al volume dei
   * contenuti multimediali e prendono il focus audio.
   *
   * Con [clip] la sorgente e' **tagliata** a `[clip.startMs, clip.endMs]` (D50): la riproduzione comincia dal fotogramma chiave
   * che precede `clip.startMs` se ce n'e' uno entro 2 secondi, altrimenti dal fotogramma esatto, e finisce a `clip.endMs`.
   * Le posizioni dello stato restano quelle della sorgente intera.
   */
  fun load(source: PlaybackSource, autoplay: Boolean, muted: Boolean, startPositionMs: Long = 0L, clip: PlaybackClip? = null)

  fun play()

  fun pause()

  /** Ferma e dimentica la sorgente: libera il decoder ma tiene il lettore, pronto per la prossima. */
  fun stop()

  /**
   * Va a [positionMs]. Con [exact] falso cerca il **fotogramma chiave piu' vicino** (`CLOSEST_SYNC`: rapido, lo usa
   * lo scorrimento mentre il dito trascina); con [exact] vero il fotogramma esatto (al rilascio).
   */
  fun seekTo(positionMs: Long, exact: Boolean)

  fun setMuted(muted: Boolean)

  /** Libera tutto. Dopo questo il lettore non si usa piu'. */
  fun release()
}

/** Costruisce il lettore la prima volta che serve (un viewer su sole foto non ne crea nessuno). */
fun interface ViewerPlayerFactory {
  fun create(): ViewerPlayer
}
