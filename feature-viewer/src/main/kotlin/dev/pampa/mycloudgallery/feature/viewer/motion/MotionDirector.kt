package dev.pampa.mycloudgallery.feature.viewer.motion

import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.video.MotionMode
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import dev.pampa.mycloudgallery.feature.viewer.video.NasMotionResult
import dev.pampa.mycloudgallery.feature.viewer.video.NasMotionSource
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoGate
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Il pager com'e' adesso: la pagina corrente e se si sta muovendo (scorrimento, espansione, trascinamento verso il basso). */
internal data class PageSettle(val id: MediaId, val moving: Boolean)

/** Cio' che il direttore chiede al viewer: tutto in funzioni, cosi' si prova senza Compose. */
internal class MotionHost(
  val services: () -> ViewerServices,
  val refOf: (MediaId) -> MediaRef?,
  val kindOf: (MediaId) -> MediaKind?,
  /** Larghezza/altezza della foto, gia' orientata; 0 se non nota. */
  val photoAspect: (MediaId) -> Float,
  val reducedMotion: () -> Boolean,
  val powerSaving: () -> Boolean,
  /** E-M16: il formato di [MediaId] non e' riconosciuto: niente badge, la nota nelle informazioni. */
  val onUnrecognized: (MediaId) -> Unit,
  /** Le pagine vicine (precarico ±1): per loro si preparano solo i byte. */
  val neighborsOf: (MediaId) -> List<MediaId>,
)

/**
 * Chi decide quando una foto in movimento si muove (D50, D51, §2.11): l'**animazione d'apertura**, muta, quando la pagina si
 * ferma, e il **video intero** dal badge, con l'audio. Non disegna niente e non decodifica niente: dice a [ViewerPlayback] (un
 * lettore solo) cosa riprodurre e a [MotionState] cosa mostrare.
 *
 * **L'animazione** parte se, dopo [settleMs] senza movimento del pager, la pagina e' una foto in movimento il cui video si trova, e
 * nessuna di queste condizioni la vieta: interruttore spento, "Rimuovi animazioni", risparmio energetico, proporzioni del video diverse
 * oltre il 3%. Una pagina attraversata da uno scorrimento non parte mai (la richiesta in corso si annulla con il movimento). Per i
 * solo-NAS solo con `NasMotionSource` e rete aperta, se la parte video e' pronta entro [readyWithinMs]; altrimenti niente, e senza
 * attese: la lettura continua in cache per il prossimo arrivo.
 *
 * **Precarico**: per le pagine vicine solo i byte (intervallo e istante; per i solo-NAS la coda, solo sul Wi-Fi), mai un decoder.
 */
internal class MotionDirector(
  private val playback: ViewerPlayback,
  private val scope: CoroutineScope,
  private val host: MotionHost,
  val state: MotionState = MotionState(),
  private val settleMs: Long = SETTLE_MS,
  private val readyWithinMs: Long = READY_WITHIN_MS,
) {

  private var fullJob: Job? = null
  private val located = LinkedHashMap<MediaId, Pair<MediaRef, MotionVideo>>()
  private val inflight = HashMap<Pair<MediaId, Boolean>, Deferred<NasMotionResult>>()

  /**
   * Segue il pager: a ogni pagina che si ferma per [settleMs] cerca di far partire l'animazione; a ogni movimento ferma tutto. Va
   * lanciato una volta per viewer. Il flusso emette solo i cambiamenti (la stessa pagina ferma non riparte chiudendo le info o
   * tornando dallo zoom: E-V14).
   */
  suspend fun watch(pages: Flow<PageSettle>) {
    pages.collectLatest { page ->
      if (page.moving || (playback.motionFor != null && playback.motionFor != page.id)) stopAll()
      if (page.moving) return@collectLatest
      delay(settleMs)
      arrive(page.id)
    }
  }

  /** Un tocco, un pizzico, un doppio tocco, un trascinamento: l'animazione si ferma subito e si vede la foto (D50). Il video dal badge no. */
  fun interruptOpening() {
    if (playback.motionFor != null && playback.motionMode == MotionMode.OPENING) playback.stopMotion()
  }

  /** Il viewer si chiude, la pagina cambia o l'app esce: ogni movimento si ferma, il decoder si libera. */
  fun stopAll() {
    fullJob?.cancel()
    fullJob = null
    playback.stopMotion()
    state.notice = null
  }

  /** La rete e' cambiata (D-15): "non disponibile ora" e la richiesta dei dati mobili non valgono piu' com'erano. */
  fun networkChanged() = state.networkChanged()

  /** Il badge "Movimento", la voce ⋮ e l'azione di TalkBack: il video intero, una volta, con l'audio; un altro tocco lo ferma. */
  fun playFull(id: MediaId) {
    if (playback.motionFor == id && playback.motionMode == MotionMode.FULL) {
      stopAll()
      return
    }
    fullJob?.cancel()
    fullJob = scope.launch { playFullNow(id) }
  }

  // --- Animazione d'apertura ---------------------------------------------------------------------------------------

  private suspend fun arrive(id: MediaId) {
    if (host.kindOf(id) != MediaKind.MOTION_PHOTO) return
    val ref = host.refOf(id) ?: return
    val services = host.services()
    val skip = MotionConditions.skip(services.motionAnimation.isOn(), host.reducedMotion(), host.powerSaving())
    val source: PlaybackSource
    val found: MotionVideo.Found
    when (ref) {
      is MediaRef.Phone -> {
        found = locate(id, ref) as? MotionVideo.Found ?: return
        if (skip != null) return
        preloadNeighbors(id)
        source = phoneSource(ref, found) ?: return
      }
      is MediaRef.Nas -> {
        if (skip != null) return
        val nas = services.nasMotion ?: return
        // Dati mobili, VPN spenta, nessuna rete: nessuna lettura (D51).
        if (nas.network() != NasNetwork.OPEN) return
        preloadNeighbors(id)
        val ready = withTimeoutOrNull(readyWithinMs) { shared(id, ref, nas, automatic = true).await() } ?: return
        when (ready) {
          is NasMotionResult.Ready -> {
            found = ready.clip.found
            source = ready.clip.source
          }
          NasMotionResult.Unrecognized -> {
            host.onUnrecognized(id)
            return
          }
          else -> return
        }
      }
      else -> return
    }
    val clip = found.openingClip ?: return
    playback.playMotion(id, source, MotionMode.OPENING, clip)
    follow(id, MotionMode.OPENING)
  }

  // --- Video intero dal badge -----------------------------------------------------------------------------------------

  private suspend fun playFullNow(id: MediaId) {
    val ref = host.refOf(id) ?: return
    val services = host.services()
    state.notice = null
    when (ref) {
      is MediaRef.Phone -> {
        val found = locate(id, ref) as? MotionVideo.Found ?: return
        val source = phoneSource(ref, found) ?: return
        playback.playMotion(id, source, MotionMode.FULL)
        follow(id, MotionMode.FULL)
      }
      is MediaRef.Nas -> {
        val nas = services.nasMotion ?: return
        when (val result = nas.fetch(ref, maxVideoBytes = Long.MAX_VALUE, automatic = false)) {
          is NasMotionResult.Ready -> {
            state.clearUnavailable(id)
            playback.playMotion(id, result.clip.source, MotionMode.FULL)
            follow(id, MotionMode.FULL)
          }
          is NasMotionResult.Blocked -> when (val gate = result.gate) {
            is NasVideoGate.NeedsMobileData -> state.notice = MotionNotice.NeedsMobileData(id, gate.sizeBytes)
            else -> unavailable(id)
          }
          NasMotionResult.Unrecognized -> host.onUnrecognized(id)
          is NasMotionResult.TooBig, NasMotionResult.Failed -> unavailable(id)
        }
      }
      else -> Unit
    }
  }

  // --- Comune ----------------------------------------------------------------------------------------------------------------

  /**
   * Segue la riproduzione appena partita: per l'animazione controlla le proporzioni (oltre il 3% niente, E-V17) appena il lettore le
   * conosce, poi aspetta la fine e fa tornare la foto con la dissolvenza. Un errore del decoder la ferma, senza dire niente.
   */
  private suspend fun follow(id: MediaId, mode: MotionMode) {
    val player = playback.player ?: return
    if (mode == MotionMode.OPENING) {
      val ui = player.state.first { it.aspect != null || it.problem != null }
      val compatible = ui.problem == null && MotionConditions.aspectCompatible(ui.aspect ?: 0f, host.photoAspect(id))
      if (!compatible) {
        if (playback.motionFor == id && playback.motionMode == mode) playback.stopMotion()
        return
      }
    }
    val end = player.state.first { it.ended || it.problem != null }
    // Nel frattempo e' partito altro (il badge durante l'animazione) o e' stato fermato: non e' piu' cosa di questa riproduzione.
    if (playback.motionFor != id || playback.motionMode != mode) return
    if (end.problem != null) {
      playback.stopMotion()
      if (mode == MotionMode.FULL) unavailable(id)
      return
    }
    playback.fadeMotion()
    delay(FADE_MS)
    if (playback.motionFor == id && playback.motionMode == mode) playback.stopMotion()
  }

  private fun unavailable(id: MediaId) {
    state.markUnavailable(id)
    state.notice = MotionNotice.Unavailable(id)
  }

  private fun phoneSource(ref: MediaRef.Phone, found: MotionVideo.Found): PlaybackSource? {
    val uri = host.services().intents?.uriOf(ref) ?: return null
    return PlaybackSource.Range(uri, found.range.start, found.range.length)
  }

  /** Intervallo e istante di un media del telefono; il risultato si ricorda (la stessa pagina non rilegge testa e coda). */
  private suspend fun locate(id: MediaId, ref: MediaRef.Phone): MotionVideo {
    located[id]?.takeIf { it.first == ref }?.let { return it.second }
    val video = host.services().motion.locate(ref)
    located[id] = ref to video
    while (located.size > LOCATED_MAX) located.remove(located.keys.first())
    if (video is MotionVideo.Unrecognized) host.onUnrecognized(id)
    return video
  }

  /** La parte video del NAS di [id]: una lettura sola per volta e per richiesta; chi arriva dopo aspetta la stessa. */
  private fun shared(id: MediaId, ref: MediaRef.Nas, nas: NasMotionSource, automatic: Boolean): Deferred<NasMotionResult> {
    val key = id to automatic
    inflight[key]?.takeIf { it.isActive }?.let { return it }
    val request = scope.async(start = CoroutineStart.LAZY) {
      nas.fetch(ref, maxVideoBytes = if (automatic) NasMotionSource.AUTOMATIC_MAX_BYTES else Long.MAX_VALUE, automatic = automatic)
    }
    inflight[key] = request
    request.start()
    return request
  }

  /** Il precarico ±1: solo byte (mai un decoder, `ViewerPlayback.created` resta 1). Per i solo-NAS solo con la rete aperta. */
  private fun preloadNeighbors(id: MediaId) {
    for (neighbor in host.neighborsOf(id)) {
      if (host.kindOf(neighbor) != MediaKind.MOTION_PHOTO) continue
      when (val ref = host.refOf(neighbor)) {
        is MediaRef.Phone -> scope.launch { locate(neighbor, ref) }
        is MediaRef.Nas -> {
          val nas = host.services().nasMotion ?: continue
          if (nas.network() == NasNetwork.OPEN) shared(neighbor, ref, nas, automatic = true)
        }
        else -> Unit
      }
    }
  }

  companion object {
    /** Quanto deve stare fermo il pager perche' la pagina sia "ferma" (D50, E-V14). */
    const val SETTLE_MS: Long = 150L

    /** Un solo-NAS anima solo se la parte video e' pronta entro 1,5 s dalla pagina ferma (D51). */
    const val READY_WITHIN_MS: Long = 1_500L

    /** La dissolvenza del ritorno alla foto (D50): circa 200 ms, poi il decoder si libera. */
    const val FADE_MS: Long = 200L

    private const val LOCATED_MAX = 32
  }
}
