package dev.pampa.mycloudgallery.feature.viewer

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryViewerTransitionState
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.gesture.FlightPlan
import dev.pampa.mycloudgallery.feature.viewer.gesture.GestureContext
import dev.pampa.mycloudgallery.feature.viewer.gesture.InfoIntent
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerDismissState
import dev.pampa.mycloudgallery.feature.viewer.info.InfoGeometry
import dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutController
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutHost
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerSubjectPress
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextController
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextHost
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionDirector
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionHost
import dev.pampa.mycloudgallery.feature.viewer.motion.PowerSaving
import dev.pampa.mycloudgallery.feature.viewer.page.fitRect
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** I fogli di conferma che il viewer apre: sono azioni dell'utente, mai automatiche. */
internal enum class ViewerSheet { Delete, Maps }

/**
 * La regia del viewer: tutto cio' che si decide a un gesto o a un tocco. Il composable la costruisce una volta e le
 * dice, a ogni ricomposizione, quanto e' grande lo schermo e quali sono la vista e i servizi di adesso; lei guida
 * l'espansione, la chiusura, il pannello, il lettore e le azioni.
 *
 * Non disegna niente e non sa nulla di Compose oltre agli stati osservabili: cosi' la logica delle azioni (che cosa
 * succede se l'utente tocca "Preferito" senza azioni locali, o "Elimina" su Android 10) si legge in un posto solo.
 */
@Stable
internal class ViewerController(
  val request: ViewerRequest,
  val state: ViewerState,
  val transition: GalleryViewerTransitionState,
  val info: InfoPanelState,
  val pager: PagerState,
  val playback: ViewerPlayback,
  private val scope: CoroutineScope,
  private val context: Context,
  private val onClosed: () -> Unit,
) {

  // --- Cio' che il composable aggiorna a ogni ricomposizione ---------------------------------------------------------
  var services: ViewerServices = ViewerServices()
  var view: TimelineView = request.items.view.value
  var stage: Size = Size.Zero
  var windowOffset: Offset = Offset.Zero
  var reducedMotion: Boolean = false
  var density: Float = 1f
  var sidePanel: Boolean = false
  var launchSender: (IntentSender) -> Unit = {}

  /** Il viewer e' stato posizionato nella finestra: da qui [windowOffset] e' quello vero. */
  var positioned: Boolean by mutableStateOf(false)

  /** Da dove parte e dove torna l'espansione, nelle coordinate del viewer. Null finche' non si apre. */
  var origin: Rect? by mutableStateOf(null)

  /** Il viewer si sta chiudendo: i gesti e i tasti non fanno piu' niente. */
  var closing: Boolean by mutableStateOf(false)
    private set

  /** Il foglio di conferma aperto, se c'e'. */
  var sheet: ViewerSheet? by mutableStateOf(null)

  /**
   * Cosa mostrare del messaggio che ha causato la prossima sparizione dalla vista (nascosto, ripristinato). Si imposta
   * **solo a scrittura riuscita**: un "Nascosto" che precede l'esito direbbe una cosa non vera (R8).
   */
  var pendingMessage: ViewerMessage? = null

  /**
   * Quanti "Nascondi"/"Mostra" sono in corso. Finche' sono in corso l'elemento che sparisce dalla vista non e' ancora una
   * notizia: l'avviso lo da' l'esito (riuscito: "Nascosto"; fallito: "Non e' stato possibile nascondere").
   */
  var changingVisibility: Int = 0

  /** Gli id per cui la parte video di una motion photo non e' stata riconosciuta (E-M16). */
  val motionUnplayable: MutableSet<MediaId> = HashSet()
  var motionUnplayableVersion: Int by mutableIntStateOf(0)
    private set

  private val systemPowerSaving: PowerSaving by lazy(LazyThreadSafetyMode.NONE) { PowerSaving.system(context) }

  /**
   * Le foto in movimento (D50, D51): l'animazione d'apertura quando la pagina si ferma e il video intero dal badge. Dice al lettore
   * condiviso cosa riprodurre; non ne crea altri.
   */
  val motion: MotionDirector = MotionDirector(
    playback = playback,
    scope = scope,
    host = MotionHost(
      services = { services },
      refOf = ::refOf,
      kindOf = ::kindOf,
      photoAspect = { id ->
        val index = view.snapshot.indexOf(id)
        if (index >= 0) view.snapshot.aspectMilliAt(index) / MILLI else 0f
      },
      reducedMotion = { reducedMotion },
      powerSaving = { (services.powerSaving ?: systemPowerSaving).isOn() },
      onUnrecognized = { id -> if (motionUnplayable.add(id)) motionUnplayableVersion++ },
      zoomed = { state.zoomed },
      neighborsOf = { id ->
        val page = view.viewIndexOf(id)
        listOf(page - 1, page + 1).filter { it in 0 until view.size && page >= 0 }.map { view.snapshot.idAt(view.snapshotIndex(it)) }
      },
    ),
  )

  /**
   * Il ritaglio del soggetto (Fase 6, P13, D44, D45, D46, D47): il soggetto che si stacca, la barra "Copia · Condividi · Salva come nuova
   * foto", l'interruttore "Contorno". Lo avvia l'arbitro della pressione prolungata ([liveText]), la voce ⋮ e l'azione di TalkBack.
   */
  val cutout: CutoutController = CutoutController(
    scope = scope,
    host = CutoutHost(
      services = { services },
      refOf = ::refOf,
      kindOf = ::kindOf,
      currentId = { state.currentId },
      announce = { state.message = it },
      hidden = { request.secure },
      work = { services.dispatchers.default },
    ),
  )

  /** La pressione prolungata fuori dal testo: il ritaglio, e la porta dei servizi se qualcuno ne ha messa una (le prove dell'arbitro). */
  private val subjectPress = ViewerSubjectPress { id, x, y ->
    services.subjectPress?.onPress(id, x, y)
    if (cutout.available) cutout.onPress(id, x, y)
  }

  /**
   * Il testo dal vivo (Fase 6, P12, D43, D45, D46): selezione con le maniglie, foglio "Testo nella foto", lettura al volo. L'arbitro della
   * pressione prolungata gli e' dentro: cio' che non e' testo lo passa al ritaglio ([cutout], P13).
   */
  val liveText: LiveTextController = LiveTextController(
    scope = scope,
    context = context,
    host = LiveTextHost(
      services = { services },
      refOf = ::refOf,
      kindOf = ::kindOf,
      currentId = { state.currentId },
      announce = { state.message = it },
      subject = { if (services.subjectPress != null || cutout.available) subjectPress else null },
    ),
  )

  // --- Geometria -----------------------------------------------------------------------------------------------------

  /** La cella di [id] nelle coordinate del viewer; se non e' sullo schermo, un rettangolo al centro. */
  fun originFor(id: MediaId): Rect {
    val bounds: PixelRect? = request.anchor.boundsOf(id)
    if (bounds != null && !bounds.isEmpty) {
      return Rect(bounds.left - windowOffset.x, bounds.top - windowOffset.y, bounds.right - windowOffset.x, bounds.bottom - windowOffset.y)
    }
    return centeredFallback(id)
  }

  private fun centeredFallback(id: MediaId): Rect {
    val side = minOf(stage.width, stage.height) * FallbackFraction
    val index = view.snapshot.indexOf(id)
    val aspect = if (index >= 0) view.snapshot.aspectMilliAt(index) / MILLI else 1f
    val width = if (aspect >= 1f) side else side * aspect
    val height = if (aspect >= 1f) side / aspect else side
    return Rect(
      left = (stage.width - width) / 2f,
      top = (stage.height - height) / 2f,
      right = (stage.width + width) / 2f,
      bottom = (stage.height + height) / 2f,
    )
  }

  /** Il rettangolo della foto di [id] intera nello schermo (dove arriva l'espansione). */
  fun photoFit(id: MediaId): Rect {
    val index = view.snapshot.indexOf(id)
    val aspect = if (index >= 0) view.snapshot.aspectMilliAt(index) / MILLI else 0f
    return fitRect(stage, aspect)
  }

  // --- Apertura e chiusura -------------------------------------------------------------------------------------------

  /** Apre: la miniatura diventa il viewer. */
  suspend fun open() {
    // Le coordinate della cella sono della finestra: senza sapere dove sta il viewer il punto di partenza sarebbe sbagliato.
    snapshotFlow { positioned }.first { it }
    origin = originFor(state.currentId)
    // Dopo una ricreazione (rotazione, split screen) il viewer si ripresenta aperto: l'espansione l'ha gia' fatta.
    if (state.restored) {
      transition.progress.snapTo(1f)
    } else {
      // CU-1: la foto esce dalla sua cella, che resta vuota finche' l'espansione non e' finita.
      hideCell(state.currentId)
      transition.open(reducedMotion)
      if (!closing) hideCell(null)
    }
  }

  /** La cella lasciata vuota nella griglia sotto (CU-1), o null. */
  private var hiddenCell: MediaId? = null

  /**
   * Svuota la cella di [id] nella griglia sotto (null = nessuna): mentre la foto ne esce all'apertura e mentre ci rientra (il trascinamento,
   * il pizzico, l'Indietro predittivo, il volo), come in Foto di Apple. La griglia la legge nel disegno ([ViewerAnchor.hide]).
   */
  private fun hideCell(id: MediaId?) {
    if (hiddenCell == id) return
    hiddenCell = id
    request.anchor.hide(id)
  }

  /** Il viewer esce dalla composizione: nessuna cella resta vuota, comunque sia andata. */
  fun releaseCell() = hideCell(null)

  /** L'espansione e' finita: il pager e' quello che si vede (prima si vedeva la foto che cresce dalla cella). */
  val opened: Boolean get() = transition.progress.value >= FULLY_OPEN && !transition.progress.isRunning

  /**
   * La chiusura (H1): trascinamento verso il basso, pizzico in chiusura, Indietro predittivo e volo alla cella, in un solo stato. Il
   * composable lo legge solo nel disegno ([ViewerDismissState.layer], [ViewerDismissState.backdropAlpha]).
   */
  val dismiss: ViewerDismissState = ViewerDismissState()

  /** Il ritorno al suo posto del contenuto, in corso: un dito che lo riprende lo ferma. */
  private var returnJob: Job? = null

  /**
   * Un video che si chiude **sfumando** (senza cella, [FlightPlan.Away]): la `SurfaceView` non sfuma con il layer, quindi al via del volo
   * il lettore si ferma e la pagina mostra subito la sua miniatura (senza dissolvenza), che sfuma con il resto. Verso la cella invece il
   * fotogramma del video vola com'e'.
   */
  var exitingVideo: Boolean by mutableStateOf(false)
    private set

  /**
   * Chiude (tasto, Indietro, swipe o pizzico gia' decisi): il contenuto vola alla cella di cio' che si sta guardando. [then] (CS-22) e' quello che si fa
   * quando il viewer se n'e' andato (aprire l'album toccato nel pannello): dopo il volo, mai al suo posto.
   */
  fun close(velocity: Offset = Offset.Zero, then: (() -> Unit)? = null) {
    if (closing) return
    closing = true
    returnJob?.cancel()
    returnJob = null
    scope.launch {
      var exited = false
      try {
        closeNow(velocity)
        // La foto e' nella cella: la cella torna a disegnarsi nello stesso fotogramma in cui il viewer se ne va.
        hideCell(null)
        exited = true
        onClosed()
        then?.invoke()
      } finally {
        // CG-6: comunque finisca questa coroutine, la chiusura non resta appesa. Se il viewer non e' uscito (un errore, la coroutine cancellata),
        // la cella torna visibile, il contenuto torna al suo posto e il viewer e' di nuovo usabile (una chiusura nuova puo' partire).
        if (!exited) {
          hideCell(null)
          dismiss.reset()
          closing = false
        }
      }
    }
  }

  /**
   * CG-6: esegue [animation], un'animazione che la chiusura aspetta. Se un altro mutatore dello stesso valore la interrompe, l'eccezione di
   * cancellazione arriva qui ma questa coroutine e' viva: la chiusura va avanti (false). Se e' questa coroutine a essere cancellata (il viewer
   * esce dalla composizione), la cancellazione passa.
   */
  private suspend fun completes(animation: suspend () -> Unit): Boolean = try {
    animation()
    true
  } catch (interrupted: CancellationException) {
    currentCoroutineContext().ensureActive()
    false
  }

  /** La chiusura fino all'arrivo nella cella (o all'uscita sfumando): il pannello giu', la cella in vista, il volo. Il resto lo fa [close]. */
  private suspend fun closeNow(velocity: Offset) {
    // CU-1: la foto vola nella sua cella, che resta vuota fino all'arrivo.
    hideCell(state.currentId)
    motion.stopAll()
    sheet = null
    // Il pannello e lo zoom tornano a riposo prima: il volo parte dalla foto intera.
    if (state.zoomed) state.zoomReset?.invoke()
    if (info.progress.value > INFO_REST) {
      state.infoOpen = false
      // CG-6: durante la chiusura il pannello non prende gesti, ma se qualcosa interrompe la sua discesa la chiusura non si ferma: va giu' di colpo.
      if (!completes { info.settle(false, reducedMotion) }) completes { info.snapShut() }
    }
    val id = state.currentId
    // La Libreria segue gia' il viewer (la cella di cio' che si guarda e' in vista); se non lo e', la si porta in vista. Un elemento sparito
    // dalla vista (E-V6), o una pagina senza griglia sotto (album, ricerca, mappa, ricordi), non ha una cella: il contenuto si allontana sfumando.
    if (view.viewIndexOf(id) >= 0 && request.anchor.boundsOf(id) == null) completes { request.anchor.reveal(id) }
    val content = contentRect(id)
    val plan = ViewerDismissState.planFor(from = dismiss.screenRect(content), content = content, cell = cellOf(id), stage = stage)
    if (plan is FlightPlan.Away && playback.videoFor == id) {
      exitingVideo = true
      playback.clear()
    }
    completes { dismiss.fly(plan, ViewerDismissState.velocityToward(plan, velocity), reducedMotion) }
    playback.clear()
  }

  /** La cella di [id] nelle coordinate del viewer, o null se non e' sullo schermo o non c'e' una griglia sotto. */
  fun cellOf(id: MediaId): Rect? {
    val bounds: PixelRect = request.anchor.boundsOf(id) ?: return null
    if (bounds.isEmpty) return null
    return Rect(bounds.left - windowOffset.x, bounds.top - windowOffset.y, bounds.right - windowOffset.x, bounds.bottom - windowOffset.y)
  }

  /** Dove sta il contenuto di [id] a riposo (coordinate locali): la foto intera nella scena; per il video che va, con le sue proporzioni. */
  fun contentRect(id: MediaId): Rect {
    if (playback.videoFor == id) {
      playback.player?.state?.value?.aspect?.let { return fitRect(stage, it) }
    }
    return photoFit(id)
  }

  // --- Gesti ---------------------------------------------------------------------------------------------------------

  /** Quello che l'arbitro dei gesti deve sapere adesso (vedi `ViewerGestureArbiter`). */
  fun gestureContext(): GestureContext {
    val phase = dismiss.phase
    return GestureContext(
      enabled = opened && !closing && !liveText.active && !cutout.active &&
        (phase == ViewerDismissState.Phase.Idle || phase == ViewerDismissState.Phase.Returning),
      zoomed = state.zoomed,
      infoOpen = state.infoOpen,
      sidePanel = sidePanel,
      returning = phase == ViewerDismissState.Phase.Returning,
    )
  }

  /** Un dito (o due) prende il contenuto per chiudere: se stava tornando al suo posto, lo si ferma dov'e'. */
  fun onDismissStart() {
    returnJob?.cancel()
    returnJob = null
    hideCell(state.currentId)
    dismiss.begin()
  }

  fun onDismissMove(pan: Offset, zoom: Float, centroid: Offset) {
    dismiss.move(pan, zoom, centroid, stage.height)
  }

  /** Le dita si alzano: chiude (volo alla cella) o torna al suo posto, con la soglia della chiusura ([ViewerDismissSpec.shouldDismiss]). */
  fun onDismissEnd(velocity: Offset, pinchRate: Float) {
    val velocityYDp = if (density > 0f) velocity.y / density else velocity.y
    if (dismiss.releaseDismisses(velocityYDp, pinchRate, stage.height)) close(velocity) else settleDismiss()
  }

  fun onDismissCancel() = settleDismiss()

  private fun settleDismiss() {
    if (closing) return
    returnJob?.cancel()
    returnJob = scope.launch {
      dismiss.settleBack(reducedMotion)
      // Tornata al suo posto: la cella si ridisegna. Un dito che la riprende mentre torna cancella questo giro e la lascia vuota.
      hideCell(null)
    }
  }

  /** La corsa del pannello informazioni del telefono: meta' scena (H2, [InfoGeometry]). */
  private val infoTravel: Float get() = InfoGeometry.travel(stage.height)

  /**
   * Un passo del dito sul pannello informazioni, da qualunque parte arrivi (la foto, il pannello): un gesto solo che apre, fa scorrere un
   * pannello lungo, torna in cima e chiude ([InfoPanelState.drag]).
   */
  fun onInfoDrag(@Suppress("UNUSED_PARAMETER") intent: InfoIntent, dy: Float) = onPanelDrag(dy)

  fun onInfoEnd(@Suppress("UNUSED_PARAMETER") intent: InfoIntent, velocityY: Float) = onPanelDragEnd(velocityY)

  // CG-6: durante la chiusura il pannello e' della chiusura. Un dito sul pannello (anche un gesto cominciato prima della X) non lo muove piu':
  // il suo `snapTo` cancellerebbe la discesa che la chiusura sta aspettando.
  fun onInfoCancel(@Suppress("UNUSED_PARAMETER") intent: InfoIntent) {
    if (closing) return
    scope.launch { info.settle(state.infoOpen, reducedMotion) }
  }

  /** Il dito sul pannello stesso (o sulla foto): [dy] pixel, positivo = verso il basso. */
  fun onPanelDrag(dy: Float) {
    if (closing) return
    scope.launch { info.drag(dy, infoTravel) }
  }

  /** Il dito si alza dal pannello con [velocityY] pixel al secondo: aperto, chiuso o lasciato scorrere. */
  fun onPanelDragEnd(velocityY: Float) {
    if (closing) return
    // Lo stato dice subito dove va il pannello: un gesto durante la molla (un tocco, un pizzico, uno swipe in giu') va al pannello giusto.
    scope.launch { info.endDrag(velocityY, infoTravel, reducedMotion, panelDecay) { open -> state.infoOpen = open } }
  }

  /** H2: due dita a pannello aperto chiudono il pannello. */
  fun onInfoPinch() {
    if (!closing) state.infoOpen = false
  }

  /** Lo scorrimento di un pannello lungo che continua dopo il dito: la stessa decelerazione delle liste. */
  private val panelDecay by lazy(LazyThreadSafetyMode.NONE) { splineBasedDecay<Float>(Density(density)) }

  // --- Indietro predittivo -------------------------------------------------------------------------------------------

  /** Il gesto Indietro di sistema a [progress] (0..1) dal bordo [fromLeft]: lo stesso stato del dito ([ViewerDismissState.back]). */
  fun onBackProgress(progress: Float, fromLeft: Boolean) {
    if (closing) return
    returnJob?.cancel()
    returnJob = null
    hideCell(state.currentId)
    dismiss.back(progress, fromLeft, stage)
  }

  /** Indietro annullato: il contenuto torna al suo posto. */
  fun onBackCancelled() = settleDismiss()

  // --- Barre, informazioni ------------------------------------------------------------------------------------------

  /**
   * Il tocco sulla foto: mostra o nasconde le barre. H2: con il pannello informazioni del telefono aperto, il tocco sulla meta' alta lo chiude
   * (la colonna laterale resta: la foto accanto si guarda e si tocca come sempre).
   */
  fun toggleChrome() {
    if (closing) return
    if (state.infoOpen && !sidePanel) {
      state.infoOpen = false
      return
    }
    state.chromeVisible = !state.chromeVisible
  }

  fun toggleInfo() {
    if (!closing) state.infoOpen = !state.infoOpen
  }

  // --- Motion photo -------------------------------------------------------------------------------------------------

  /**
   * Il badge "Movimento", la voce ⋮ "Riproduci il movimento" e l'azione di TalkBack (D51): il video intero, una volta, con l'audio al
   * volume dei contenuti multimediali; poi torna la foto. Un altro tocco lo ferma. **La pressione lunga non c'entra piu'** (D45): resta
   * libera per il testo dal vivo e il ritaglio.
   */
  fun playMotion() {
    if (!closing) motion.playFull(state.currentId)
  }

  /** Un tocco, un pizzico, un doppio tocco o un trascinamento sulla pagina: l'animazione d'apertura si ferma subito (D50). */
  fun onMotionTouch() = motion.interruptOpening()

  /** L'utente accetta i dati mobili per la parte video di [id] (E-V15, D51): `:app` registra la conferma e il video parte. */
  fun confirmMotionMobileData(id: MediaId) {
    val ref = refOf(id) ?: return
    services.onConfirmMobileData?.invoke(ref)
    motion.state.notice = null
    motion.playFull(id)
  }

  /** Il badge si vede: una foto in movimento il cui video si trova; per un media del NAS solo con la lettura a intervalli collegata (D13). */
  fun motionAvailable(id: MediaId): Boolean {
    if (motionUnplayableVersion < 0 || kindOf(id) != MediaKind.MOTION_PHOTO || id in motionUnplayable) return false
    return when (refOf(id)) {
      is MediaRef.Phone -> true
      is MediaRef.Nas -> services.nasMotion != null
      else -> false
    }
  }

  // --- Media del NAS (Fase 4) ----------------------------------------------------------------------------------------

  /** "Riprova" dalla nota: la pagina si ricarica e, per un video, riparte. L'originale chiesto con lo zoom si richiede. */
  fun retry() {
    val id = state.currentId
    state.originals.remove(id)
    // Il video che era in errore va rifatto da capo: il lettore libera la sorgente e la pagina lo riavvia.
    if (playback.videoFor == id) playback.clear()
    state.retries[id] = (state.retries[id] ?: 0) + 1
  }

  /**
   * D-15 (prove sul campo): la rete verso il NAS e' cambiata ([network] e' quella di adesso). Quello che una pagina dice per colpa della rete ("Servono
   * i dati mobili", "NAS non raggiungibile fuori casa", "Nessuna rete", "Il NAS non risponde") non e' piu' vero com'era: si rifa il caricamento delle
   * pagine in quello stato, come dopo un "Riprova" (un video riparte da solo, una foto riprende la sua sorgente), cosi' lo stato segue la rete di adesso
   * invece di restare quello di quando e' stato calcolato (sul campo: dopo il ritorno del Wi-Fi il viewer restava su "Servono i dati mobili").
   *
   * Una pagina che funziona (un video che va, una foto nitida) non si tocca: un cambio di rete non la rilancia. Un video fermo da un errore di rete
   * riparte solo se adesso il NAS si puo' provare. Un download in attesa dei dati mobili si scarta: la prossima richiesta lo rivaluta con la rete di adesso.
   */
  fun networkChanged(network: NasNetwork) {
    if (closing) return
    val stale = LinkedHashSet<MediaId>()
    for ((id, original) in state.originals) if (original is OriginalProgress.Blocked && original.reason in NETWORK_REASONS) stale += id
    for ((id, image) in state.images) if (image.unavailable?.reason in NETWORK_REASONS) stale += id
    motion.networkChanged()
    val id = state.currentId
    val reachable = network == NasNetwork.OPEN || network == NasNetwork.METERED
    if (reachable && playback.videoFor == id && playback.player?.state?.value?.problem == PlaybackProblem.NAS_UNREACHABLE) stale += id
    for (page in stale) {
      state.originals.remove(page)
      // Il video che era in errore va rifatto da capo: il lettore libera la sorgente e la pagina lo riavvia.
      if (playback.videoFor == page) playback.clear()
      state.retries[page] = (state.retries[page] ?: 0) + 1
    }
    services.download?.let { downloads ->
      for ((locationId, download) in downloads.states.value) if (download is DownloadState.NeedsMobileData) downloads.acknowledge(locationId)
    }
  }

  /**
   * L'utente accetta i dati mobili per [ref] (E-V2): :app registra la conferma (`onConfirmMobileData`) e la pagina riparte da
   * sola, come dopo un "Riprova". Senza il collegamento di :app la nota non propone il tasto.
   */
  fun confirmMobileData(ref: MediaRef) {
    services.onConfirmMobileData?.invoke(ref)
    retry()
  }

  /** Come [confirmMobileData], per "Scarica sul telefono": dopo la conferma il download parte. */
  fun confirmMobileDataForDownload(ref: MediaRef.Nas) {
    services.onConfirmMobileData?.invoke(ref)
    services.download?.start(ref)
  }

  /** "Scarica sul telefono" dal menu: il download va in coda e continua anche se il viewer si chiude (D20). */
  fun download() {
    if (closing) return
    val ref = refOf(state.currentId) as? MediaRef.Nas ?: return
    services.download?.start(ref)
  }

  fun cancelDownload() {
    val ref = refOf(state.currentId) as? MediaRef.Nas ?: return
    services.download?.cancel(ref)
  }

  // --- Azioni --------------------------------------------------------------------------------------------------------

  /** Dove stanno i byte di [id], o null se adesso non c'e' una copia leggibile o l'elemento e' sparito del tutto. */
  fun refOf(id: MediaId): MediaRef? = request.items.refOrNull(id)

  fun isFavorite(id: MediaId): Boolean {
    state.favoriteOverride[id]?.let { return it }
    val index = view.snapshot.indexOf(id)
    return index >= 0 && CellFlags.has(view.snapshot.flagsAt(index), CellFlags.FAVORITE)
  }

  fun toggleFavorite() {
    val actions = services.actions ?: return
    val id = state.currentId
    val on = !isFavorite(id)
    state.favoriteOverride[id] = on
    scope.launch {
      if (!attempt { actions.setFavorite(setOf(id), on) }) {
        // Non salvato: la stella torna com'era e lo si dice (R8).
        state.favoriteOverride.remove(id)
        state.message = ViewerMessage.FavoriteFailed
      }
    }
  }

  /**
   * Nascondi (Libreria) o Mostra (Nascosti). L'elemento sparisce dalla vista: il viewer passa al vicino con un avviso, ma
   * solo se la scrittura e' riuscita; se non riesce l'avviso dice che non e' stato possibile e l'elemento resta com'era (R8).
   */
  fun toggleHidden() {
    val actions = services.actions ?: return
    val id = state.currentId
    val hide = !request.secure
    changingVisibility++
    scope.launch {
      var familySees = false
      val done = try {
        val written = attempt { actions.setHidden(setOf(id), hide) }
        // E-H2: la domanda sta dentro la finestra "sto cambiando la visibilita'", altrimenti l'elemento che esce dalla vista si annuncerebbe
        // come sparito (ItemGone) prima che il messaggio giusto sia pronto.
        if (written && hide) familySees = familyStillSees(actions, id)
        written
      } finally {
        changingVisibility--
      }
      val message = when {
        done && hide && familySees -> ViewerMessage.HiddenFamilySees
        done && hide -> ViewerMessage.Hidden
        done -> ViewerMessage.Unhidden
        hide -> ViewerMessage.HideFailed
        else -> ViewerMessage.UnhideFailed
      }
      when {
        !done -> state.message = message
        // E' gia' sparito dalla vista (il viewer e' passato al vicino senza dire niente): l'avviso adesso.
        request.items.view.value.viewIndexOf(id) < 0 -> state.message = message
        // Sparira' fra un attimo: lo dira' la vista che cambia.
        else -> pendingMessage = message
      }
    }
  }

  fun share() {
    val phone = refOf(state.currentId) as? MediaRef.Phone ?: return
    val intent = services.intents?.share(listOf(phone))
    if (intent == null) {
      state.message = ViewerMessage.ActionFailed
      return
    }
    val chooser = Intent.createChooser(intent, null).apply { if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
      context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
      state.message = ViewerMessage.ActionFailed
    }
  }

  fun copy(label: String) {
    val phone = refOf(state.currentId) as? MediaRef.Phone ?: return
    val uri = services.intents?.uriOf(phone) ?: return
    state.message = if (ViewerSystem.copyUri(context, uri, label, sensitive = request.secure)) ViewerMessage.Copied else ViewerMessage.ActionFailed
  }

  /** Il foglio di conferma di "Elimina dal telefono" (D14). Niente parte finche' l'utente non lo conferma. */
  fun askDelete() {
    if (!closing) sheet = ViewerSheet.Delete
  }

  /** Confermato: il dialogo di sistema del cestino (30 giorni, ripristinabile). L'app non cancella mai da sola. */
  fun confirmDelete() {
    sheet = null
    val phone = refOf(state.currentId) as? MediaRef.Phone ?: return
    val sender = services.intents?.trashRequest(listOf(phone), trash = true)
    if (sender == null) state.message = ViewerMessage.ActionFailed else launchSender(sender)
  }

  fun askMaps() {
    if (!closing) sheet = ViewerSheet.Maps
  }

  fun confirmMaps(point: GeoPoint?) {
    sheet = null
    if (point == null) return
    val intent = ViewerSystem.mapsIntent(point).apply { if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      state.message = ViewerMessage.ActionFailed
    }
  }

  /** Il tipo di cio' che si sta guardando, dall'indice. */
  fun kindOf(id: MediaId): MediaKind? {
    val index = view.snapshot.indexOf(id)
    return if (index >= 0) view.snapshot.kindAt(index) else null
  }

  internal companion object {
    /** Sopra questo avanzamento l'espansione e' finita (il pager sostituisce la foto che cresce). */
    const val FULLY_OPEN = 0.999f
    const val FallbackFraction = 0.3f
    const val MILLI = 1000f
    const val INFO_REST = 0.01f

    /** D-15: i motivi per cui una pagina non mostra l'originale che dipendono dalla rete di quel momento (non il volume tolto). */
    val NETWORK_REASONS: Set<UnavailableReason> = setOf(
      UnavailableReason.NAS_UNREACHABLE,
      UnavailableReason.NOT_ALLOWED_HERE,
      UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION,
      UnavailableReason.VPN_OFF_AWAY,
      UnavailableReason.OFFLINE,
    )
  }
}
