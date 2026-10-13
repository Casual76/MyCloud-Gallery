package dev.pampa.mycloudgallery.feature.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
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
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryDismissSpec
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryViewerTransitionState
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.gesture.VerticalIntent
import dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState
import dev.pampa.mycloudgallery.feature.viewer.page.fitRect
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
  var sheetPx: Float = 1f
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

  /** Cosa mostrare del messaggio che ha causato la prossima sparizione dalla vista (nascosto, ripristinato). */
  var pendingMessage: ViewerMessage? = null

  private var motionJob: Job? = null

  /** Gli id per cui la parte video di una motion photo non e' stata riconosciuta (E-M16). */
  val motionUnplayable: MutableSet<MediaId> = HashSet()
  var motionUnplayableVersion: Int by mutableIntStateOf(0)
    private set

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
    if (state.restored) transition.progress.snapTo(1f) else transition.open(reducedMotion)
  }

  /** Chiude (tasto, Indietro, swipe giu' gia' deciso): la foto torna alla cella di cio' che si sta guardando. */
  fun close() {
    if (closing) return
    closing = true
    scope.launch { closeNow() }
  }

  private suspend fun closeNow() {
    motionJob?.cancel()
    playback.stopMotion()
    sheet = null
    // Il pannello e lo zoom tornano a riposo prima: l'espansione alla rovescia parte dalla foto intera.
    if (state.zoomed) state.zoomReset?.invoke()
    if (info.progress.value > INFO_REST) {
      state.infoOpen = false
      info.settle(false, reducedMotion)
    }
    val id = state.currentId
    // Un elemento sparito dalla vista (E-V6) non ha piu' una cella da portare in vista: si chiude sul rettangolo di ripiego.
    if (view.viewIndexOf(id) >= 0 && request.anchor.boundsOf(id) == null) request.anchor.reveal(id)
    origin = originFor(id)
    playback.clear()
    transition.close(reducedMotion)
    onClosed()
  }

  /** Il rilascio di un trascinamento verso il basso: chiude oppure torna a posto, con la stessa soglia dell'espansione. */
  suspend fun releaseDismiss(velocityY: Float) {
    val dragProgress = GalleryDismissSpec.dragProgress(transition.drag.value.y, stage.height)
    if (GalleryDismissSpec.shouldDismiss(dragProgress, velocityY, density)) {
      closing = true
      closeNow()
    } else {
      transition.release(velocityY, density, stage.height, reducedMotion)
    }
  }

  // --- Gesti verticali -----------------------------------------------------------------------------------------------

  fun intentFor(dy: Float): VerticalIntent? = when {
    closing -> null
    state.infoOpen -> if (dy > 0f) VerticalIntent.CloseInfo else null
    dy > 0f -> VerticalIntent.Dismiss
    !sidePanel -> VerticalIntent.OpenInfo
    else -> null
  }

  fun onVerticalDrag(intent: VerticalIntent, delta: Offset) {
    scope.launch {
      when (intent) {
        VerticalIntent.Dismiss -> transition.dragBy(delta, stage.height)
        VerticalIntent.OpenInfo, VerticalIntent.CloseInfo -> info.dragBy(-delta.y / sheetPx)
      }
    }
  }

  fun onVerticalEnd(intent: VerticalIntent, velocityY: Float) {
    scope.launch {
      when (intent) {
        VerticalIntent.Dismiss -> releaseDismiss(velocityY)
        VerticalIntent.OpenInfo -> state.infoOpen = info.release(-velocityY / sheetPx, reducedMotion, InfoPanelState.OpenBias)
        VerticalIntent.CloseInfo -> state.infoOpen = info.release(-velocityY / sheetPx, reducedMotion, InfoPanelState.CloseBias)
      }
    }
  }

  fun onVerticalCancel(intent: VerticalIntent) {
    scope.launch {
      when (intent) {
        VerticalIntent.Dismiss -> transition.release(0f, density, Float.MAX_VALUE, reducedMotion)
        VerticalIntent.OpenInfo, VerticalIntent.CloseInfo -> info.settle(state.infoOpen, reducedMotion)
      }
    }
  }

  // --- Barre, informazioni ------------------------------------------------------------------------------------------

  fun toggleChrome() {
    if (!closing) state.chromeVisible = !state.chromeVisible
  }

  fun toggleInfo() {
    if (!closing) state.infoOpen = !state.infoOpen
  }

  // --- Motion photo -------------------------------------------------------------------------------------------------

  /** Pressione lunga su una motion photo: cerca la parte video e la riproduce in silenzio finche' il dito sta giu'. */
  fun onMotionHold() {
    val id = state.currentId
    val ref = refOf(id) ?: return
    val phone = ref as? MediaRef.Phone ?: return
    val uri = services.intents?.uriOf(phone)
    motionJob?.cancel()
    motionJob = scope.launch {
      when (val found = services.motion.locate(ref)) {
        is MotionVideo.Found -> {
          val range = found.range
          if (uri != null) playback.playMotion(id, PlaybackSource.Range(uri, range.start, range.length))
        }
        MotionVideo.Unrecognized -> {
          if (motionUnplayable.add(id)) motionUnplayableVersion++
          state.message = ViewerMessage.MotionUnplayable
        }
      }
    }
  }

  fun onMotionRelease() {
    motionJob?.cancel()
    playback.stopMotion()
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
    scope.launch { actions.setFavorite(setOf(id), on) }
  }

  /** Nascondi (Libreria) o Mostra (Nascosti). L'elemento sparisce dalla vista: il viewer passa al vicino con un avviso. */
  fun toggleHidden() {
    val actions = services.actions ?: return
    val id = state.currentId
    val hide = !request.secure
    pendingMessage = if (hide) ViewerMessage.Hidden else ViewerMessage.Unhidden
    scope.launch { actions.setHidden(setOf(id), hide) }
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

  /** Ripristina la posizione di un trascinamento senza chiudere, dopo un Indietro predittivo annullato. */
  suspend fun cancelBack() = coroutineScope {
    transition.release(0f, density, Float.MAX_VALUE, reducedMotion)
  }

  private companion object {
    const val FallbackFraction = 0.3f
    const val MILLI = 1000f
    const val INFO_REST = 0.01f
  }
}
