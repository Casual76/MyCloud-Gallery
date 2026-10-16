package dev.pampa.mycloudgallery.feature.viewer

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidCornerRadii
import dev.antigravity.fluidengine.ui.fluidphysics.FluidForm
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsClip
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidPhysicsState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryDismissSpec
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryExpandSpec
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryViewerTransitionState
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryVideoBar
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerChrome
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerChromeDefaults
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadFailure
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionCapabilities
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionHandlers
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionSheets
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionsController
import dev.pampa.mycloudgallery.feature.viewer.menu.nasMenuEntries
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.gesture.viewerVerticalDrag
import dev.pampa.mycloudgallery.feature.viewer.info.InfoInputs
import dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSheet
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSidePanel
import dev.pampa.mycloudgallery.feature.viewer.info.buildInfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.rememberBackupState
import dev.pampa.mycloudgallery.feature.viewer.info.sheetHeight
import dev.pampa.mycloudgallery.feature.viewer.page.PageSpec
import dev.pampa.mycloudgallery.feature.viewer.page.ViewerPage
import dev.pampa.mycloudgallery.feature.viewer.video.Media3ViewerPlayer
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoGate
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayerFactory
import dev.pampa.mycloudgallery.feature.viewer.video.collectPlayerState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** I nomi con cui i test trovano i pezzi del viewer. */
internal object ViewerTags {
  const val Pager = "viewer_pager"
  const val InfoPanel = "viewer_info"
}

/**
 * Il viewer: una **sovrapposizione nella stessa schermata**, non una rotta (D12). `:app` lo ospita sopra la pillola e
 * lo mostra quando [request] c'e'; [onClosed] lo toglie. La miniatura *diventa* il viewer.
 *
 * **Apertura e chiusura.** La sagoma passa dalla cella allo schermo con Fluid-physics (`fluidPhysicsClip`), opaca dal
 * primo fotogramma: niente sfuma. La foto dentro cresce dalla cella (`Crop`) alla foto intera (`Fit`) partendo dalla
 * stessa bitmap della miniatura. Si chiude con il tasto, con uno swipe verso il basso (la foto segue il dito, torna
 * alla cella di cio' che si stava guardando, anche se e' un'altra) o con il gesto Indietro predittivo.
 *
 * **Dentro.** Un pager orizzontale sulla stessa vista della Libreria, con precarico di una pagina per lato; la foto si
 * ingrandisce con tasselli fino a 200 MP e passa al pager dal bordo; i video partono quando la pagina si ferma, con un
 * solo lettore; la motion photo si anima tenendo premuto; GIF e WebP si animano. Un tocco nasconde le barre (e quelle
 * di sistema), uno swipe verso l'alto apre le informazioni (foglio a meta' sul telefono, pannello laterale da 840 dp).
 *
 * @param images da dove arrivano i pixel, per chi non passa [services] (compatibilita' con P0).
 * @param services tutto cio' che arriva da fuori: dettagli, azioni, lettore, miniature. I default sono i null-object,
 *   e le capacita' assenti non compaiono (D13).
 * @param onCurrentChanged l'elemento che si sta guardando e' cambiato: chi ospita lo salva per la ricreazione (E-V8).
 */
@Composable
fun ViewerOverlay(
  request: ViewerRequest?,
  onClosed: () -> Unit,
  modifier: Modifier = Modifier,
  images: ViewerImageSource = NoViewerImageSource,
  services: ViewerServices = ViewerServices(images = images),
  onCurrentChanged: (MediaId) -> Unit = {},
) {
  if (request == null) return
  ViewerHost(request = request, onClosed = onClosed, modifier = modifier, services = services, onCurrentChanged = onCurrentChanged)
}

private const val FullyOpen = 0.999f

@Composable
private fun ViewerHost(
  request: ViewerRequest,
  onClosed: () -> Unit,
  modifier: Modifier,
  services: ViewerServices,
  onCurrentChanged: (MediaId) -> Unit,
) {
  // Nascosti: screenshot bloccati e recenti oscurati per tutto il viewer (E-S5).
  SecureScreen(enabled = request.secure)

  val view by request.items.view.collectAsState()
  if (view.size == 0) {
    // E-V6: la lista e' vuota (cancellata da Galleria o da un altro dispositivo): il viewer si chiude.
    LaunchedEffect(Unit) { onClosed() }
    return
  }

  val context = LocalContext.current
  val density = LocalDensity.current
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val scope = rememberCoroutineScope()
  val viewModel: ViewerViewModel = viewModel()
  val state = rememberSaveable(saver = ViewerState.Saver) { ViewerState(request.start) }
  // Gli esiti di scaricamenti conclusi prima che il viewer si aprisse non sono una notizia: si scartano in silenzio (Fase 4).
  // (`also`: una volta per servizio, prima della prima lettura degli esiti; `remember` non deve restituire Unit.)
  remember(services.download) {
    services.download?.also { downloads ->
      downloads.states.value.filterValues { it.isTerminal }.keys.forEach(downloads::acknowledge)
    }
  }
  val transition = remember { GalleryViewerTransitionState() }
  val info = remember { InfoPanelState(initialOpen = state.infoOpen) }
  val startPage = remember {
    view.viewIndexOf(state.currentId).takeIf { it >= 0 } ?: view.viewIndexOf(request.start).coerceAtLeast(0)
  }
  val pager = rememberPagerState(initialPage = startPage) { view.size }
  val playback = remember(services.player, services.nasVideo) {
    ViewerPlayback(services.player ?: ViewerPlayerFactory { Media3ViewerPlayer(context, services.nasVideo) })
  }
  DisposableEffect(playback) { onDispose { playback.release() } }
  val latestOnClosed by rememberUpdatedState(onClosed)
  val controller = remember {
    ViewerController(request, state, transition, info, pager, playback, scope, context) { latestOnClosed() }
  }
  controller.services = services
  controller.view = view
  controller.reducedMotion = reducedMotion
  controller.density = density.density

  val format = remember(context) { ViewerFormat(context) }
  val backdrop = rememberGlassBackdrop()
  val scheme = MaterialTheme.colorScheme
  val touchExploration = remember(context) {
    context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
  }

  // --- Cio' che segue dalla vista ---------------------------------------------------------------------------------

  // Dopo una ricreazione l'id salvato potrebbe non essere piu' nella vista: si riparte da dove si e' aperto.
  LaunchedEffect(Unit) {
    if (view.viewIndexOf(state.currentId) < 0) state.currentId = view.snapshot.idAt(view.snapshotIndex(startPage))
  }

  // La pagina assestata del pager e' l'elemento che si sta guardando.
  val latestView by rememberUpdatedState(view)
  LaunchedEffect(pager) {
    snapshotFlow { pager.settledPage }.collect { page ->
      val current = latestView
      if (page in 0 until current.size) {
        val id = current.snapshot.idAt(current.snapshotIndex(page))
        if (id != state.currentId) state.currentId = id
      }
    }
  }
  LaunchedEffect(state.currentId) { onCurrentChanged(state.currentId) }

  // E-V6: l'elemento che si guarda sparisce dalla vista (cancellato da Galleria o da un altro dispositivo, nascosto,
  // tolto dai preferiti con il filtro Preferiti): si passa al vicino con un breve avviso.
  LaunchedEffect(request) {
    var previous = request.items.view.value
    request.items.view.collect { next ->
      if (next !== previous && next.size > 0 && next.viewIndexOf(state.currentId) < 0) {
        val oldIndex = previous.viewIndexOf(state.currentId).coerceAtLeast(0)
        val target = oldIndex.coerceAtMost(next.size - 1)
        state.currentId = next.snapshot.idAt(next.snapshotIndex(target))
        // Con un Nascondi/Mostra in corso l'avviso lo da' l'esito, non la sparizione (R8).
        if (controller.changingVisibility == 0) state.message = controller.pendingMessage ?: ViewerMessage.ItemGone
        controller.pendingMessage = null
      }
      previous = next
    }
  }
  // La pagina segue l'elemento corrente quando la vista cambia sotto (inserimenti prima di lui, vicino dopo una sparizione).
  LaunchedEffect(view) {
    val index = view.viewIndexOf(state.currentId)
    if (index >= 0 && index != pager.currentPage && !pager.isScrollInProgress) pager.scrollToPage(index)
  }
  // I preferiti toccati restano "in sospeso" finche' l'indice non conferma.
  LaunchedEffect(view.snapshot.seq) {
    state.favoriteOverride.keys.toList().forEach { id ->
      val index = view.snapshot.indexOf(id)
      if (index < 0 || view.snapshot.hasFlag(index, CellFlags.FAVORITE) == state.favoriteOverride[id]) state.favoriteOverride.remove(id)
    }
  }
  // La Libreria segue il viewer: la cella dell'elemento corrente e' sempre sullo schermo, pronta per la chiusura.
  LaunchedEffect(state.currentId) {
    if (state.currentId != request.start && !controller.closing) request.anchor.reveal(state.currentId)
  }
  // Un avviso breve si spegne da solo.
  LaunchedEffect(state.message) {
    if (state.message != null) {
      delay(MessageMillis)
      state.message = null
    }
  }
  // Il pannello informazioni segue lo stato (il tasto, il menu); il dito lo guida da solo e poi lo scrive qui.
  LaunchedEffect(state.infoOpen) {
    if (!info.dragging) info.settle(state.infoOpen, reducedMotion)
  }

  // --- Il lettore ------------------------------------------------------------------------------------------------------

  val currentId = state.currentId
  val currentKind = controller.kindOf(currentId)
  val atRest by remember { derivedStateOf { transition.progress.value >= FullyOpen && !transition.dragging && !transition.progress.isRunning } }
  var resumeAfterRest by remember { mutableStateOf(false) }
  // Il fondale di vetro delle barre: dal primo riposo in poi (vedi il disegno qui sotto).
  var chromeBackdrop by remember { mutableStateOf(state.restored) }
  LaunchedEffect(Unit) {
    if (!chromeBackdrop) {
      snapshotFlow { atRest }.first { it }
      chromeBackdrop = true
    }
  }
  LaunchedEffect(currentId) {
    // Cambio pagina: il video di prima si ferma e libera il decoder.
    if (playback.videoFor != null && playback.videoFor != currentId) playback.clear()
    if (playback.motionFor != null && playback.motionFor != currentId) playback.stopMotion()
  }
  LaunchedEffect(currentId, atRest, currentKind, state.retries[currentId]) {
    if (currentKind != MediaKind.VIDEO) return@LaunchedEffect
    if (atRest) {
      if (playback.videoFor == currentId) {
        if (resumeAfterRest) {
          playback.player?.play()
          resumeAfterRest = false
        }
      } else {
        // D19: il video parte da solo quando la pagina si ferma, con l'audio.
        when (val ref = controller.refOf(currentId)) {
          is MediaRef.Phone -> {
            val uri = services.intents?.uriOf(ref)
            if (uri != null) playback.playVideo(currentId, PlaybackSource.Whole(uri))
          }
          // Un video del NAS: prima si guarda se puo' partire (dati mobili), poi parte in streaming a intervalli (D19).
          is MediaRef.Nas -> startNasVideo(currentId, ref, services, state, playback)
          else -> Unit
        }
      }
    } else if (playback.videoFor == currentId) {
      // Un trascinamento verso il basso ferma il video; se il dito torna indietro, riparte da dove era.
      resumeAfterRest = playback.player?.state?.value?.playing == true
      playback.pause()
    }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }

  // --- Barre di sistema, HDR -------------------------------------------------------------------------------------------

  val immersive = !state.chromeVisible && !touchExploration
  ImmersiveSystemBars(immersive = immersive && atRest)
  HdrWindowMode(hdr = state.images[currentId]?.hdr == true && atRest)

  // --- Indietro --------------------------------------------------------------------------------------------------------

  // Il foglio informazioni si chiude prima del viewer (dichiarato dopo: ha la precedenza quando e' attivo).
  PredictiveBackHandler(enabled = !controller.closing && controller.sheet == null) { events ->
    try {
      transition.dragBy(Offset.Zero, controller.stage.height)
      events.collect { event ->
        val p = event.progress
        transition.progress.snapTo(lerp(1f, GalleryDismissSpec.MinViewerProgress, p))
        val edge = if (event.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1f else -1f
        transition.drag.snapTo(Offset(edge * p * controller.stage.width * BackShiftFraction, p * controller.stage.height * BackLiftFraction))
      }
      controller.close()
    } catch (cancelled: CancellationException) {
      controller.cancelBack()
      throw cancelled
    }
  }

  // --- Disegno ---------------------------------------------------------------------------------------------------------

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .onGloballyPositioned {
        controller.windowOffset = it.positionInWindow()
        controller.positioned = true
      },
  ) {
    val stagePx = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
    val sidePanel = maxWidth >= SidePanelMinWidth
    val sheetHeightDp = sheetHeight(maxHeight)
    val sheetPx = with(density) { sheetHeightDp.toPx() }
    val panelPx = with(density) { SidePanelWidth.toPx() }
    controller.stage = stagePx
    controller.sidePanel = sidePanel
    controller.sheetPx = sheetPx

    LaunchedEffect(Unit) { controller.open() }

    val shown by remember { derivedStateOf { transition.isShown } }
    val originRect = controller.origin
    if (shown && originRect != null) {
      val thumbRadius = with(density) { FluidRadius.Small.toPx() }
      val thumbForm = remember(originRect, thumbRadius) { FluidForm.Slab(originRect, FluidCornerRadii.all(thumbRadius)) }
      val fullForm = remember(stagePx) { FluidForm.Slab(Rect(Offset.Zero, stagePx)) }
      val physics = rememberFluidPhysicsState(thumbForm)
      SideEffect { physics.driveExternally(from = thumbForm, to = fullForm, progress = { transition.progress.value }) }

      val background by animateColorAsState(
        targetValue = if (state.chromeVisible || touchExploration) scheme.background else scheme.scrim,
        animationSpec = if (reducedMotion) snap() else FluidMotion.color(),
        label = "viewer background",
      )

      // 1. La foto: la sagoma che cresce dalla cella, con dentro il pager (a riposo) o la foto che si espande.
      Box(
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            translationX = transition.drag.value.x
            translationY = transition.drag.value.y
          },
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .fluidPhysicsClip(physics) { transition.progress.value < FullyOpen || transition.dragging },
        ) {
          Box(Modifier.fillMaxSize().background(background))
          // Il fondale di vetro delle barre entra quando le barre possono comparire (la prima volta a riposo) e poi resta,
          // anche in chiusura, mentre le barre sfumano. Durante l'espansione era un altro layer a schermo intero da
          // ridisegnare a ogni fotogramma, in un RenderThread gia' oltre il budget di memoria (sull'S25 ogni fotogramma
          // dell'espansione riallocava i layer e ricaricava le miniature: 25-37 ms).
          Box(Modifier.fillMaxSize().then(if (chromeBackdrop) Modifier.glassBackdropSource(backdrop) else Modifier)) {
            PagerLayer(
              controller = controller,
              view = view,
              services = services,
              screen = screen,
              panelPx = panelPx,
              atRest = { atRest },
              format = format,
            )
            if (!atRest) {
              TransitionPhoto(controller = controller, services = services, origin = originRect)
            }
          }
        }
      }

      // 2. Il resto sta sopra e non si sposta con il dito: barre, avvisi, informazioni.
      ViewerOverlays(
        controller = controller,
        view = view,
        services = services,
        viewModel = viewModel,
        format = format,
        backdrop = backdrop,
        atRest = atRest,
        touchExploration = touchExploration,
        sidePanel = sidePanel,
        sheetHeight = sheetHeightDp,
        panelPx = panelPx,
      )
    }
  }
}

/** Il pager orizzontale, con il gesto verticale (chiudi / informazioni) che lo avvolge. */
@Composable
private fun PagerLayer(
  controller: ViewerController,
  view: TimelineView,
  services: ViewerServices,
  screen: IntSize,
  panelPx: Float,
  atRest: () -> Boolean,
  format: ViewerFormat,
) {
  val state = controller.state
  val pager = controller.pager
  val request = controller.request
  val info = controller.info
  val stage = controller.stage
  val positionText = stringResource(R.string.viewer_position, GalleryNumbers.format(pager.currentPage + 1), GalleryNumbers.format(view.size))
  val zoomedText = stringResource(R.string.viewer_zoomed)

  // Le pagine vicine entrano a meta' dell'espansione, non nel suo primo fotogramma: durante l'espansione il pager e'
  // trasparente e non scorre (si vede la foto che cresce), e comporre tre pagine insieme nel fotogramma del tocco era
  // gran parte dello scatto all'apertura (S25, 13/10). Una volta entrate restano, anche in chiusura.
  var neighbors by remember { mutableStateOf(controller.state.restored) }
  LaunchedEffect(Unit) {
    if (!neighbors) {
      snapshotFlow { atRest() || controller.transition.progress.value >= NeighborsAtProgress }.first { it }
      neighbors = true
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .viewerVerticalDrag(
        enabled = { atRest() && !state.zoomed && !controller.closing },
        intentFor = controller::intentFor,
        onStart = {},
        onDrag = controller::onVerticalDrag,
        onEnd = controller::onVerticalEnd,
        onCancel = controller::onVerticalCancel,
      )
      .graphicsLayer {
        // Il pannello aperto fa posto alla foto: si rimpicciolisce e si sposta, tutto dall'orologio del pannello.
        val progress = info.progress.value
        alpha = if (atRest()) 1f else 0f
        if (progress > 0f) {
          if (controller.sidePanel) {
            val scale = lerp(1f, ((stage.width - panelPx) / stage.width).coerceAtLeast(MIN_SHIFT_SCALE), progress)
            scaleX = scale
            scaleY = scale
            translationX = -(panelPx / 2f) * progress
          } else {
            val scale = lerp(1f, ((stage.height - controller.sheetPx) / stage.height).coerceAtLeast(MIN_SHIFT_SCALE), progress)
            scaleX = scale
            scaleY = scale
            translationY = -(controller.sheetPx / 2f) * progress
          }
        }
      }
      .semantics {
        contentDescription = positionText
        if (state.zoomed) stateDescription = zoomedText
      },
  ) {
    HorizontalPager(
      state = pager,
      modifier = Modifier.fillMaxSize().testTag(ViewerTags.Pager),
      pageSpacing = PageSpacing,
      beyondViewportPageCount = if (neighbors) 1 else 0,
      userScrollEnabled = atRest() && !controller.closing,
      flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = FluidMotion.smooth()),
      key = { index -> if (index < view.size) view.snapshot.idAt(view.snapshotIndex(index)).value else "gone-$index" },
    ) { page ->
      if (page < view.size) {
        val snapshot = view.snapshot
        val index = view.snapshotIndex(page)
        val id = snapshot.idAt(index)
        val kind = snapshot.kindAt(index)
        val placeId = snapshot.placeAt(index)
        val placeName = if (placeId > 0) services.places.labelOfId(placeId) else null
        val ref = request.items.refOrNull(id)
        val spec = remember(id, index, snapshot.seq, placeName, ref) {
          PageSpec(
            id = id,
            ref = ref,
            kind = kind,
            aspect = snapshot.aspectMilliAt(index) / MILLI,
            description = format.pageDescription(kind, snapshot.localDayAt(index), placeName),
            thumbHash = snapshot.thumbHashAt(index),
            thumbKey = request.anchor.thumbKey(id),
          )
        }
        ViewerPage(
          spec = spec,
          screen = screen,
          // Solo la pagina corrente e le vicine decodificano: una che scorre via in un fling non parte (E-V5).
          loadActive = abs(page - pager.targetPage) <= 1,
          isCurrent = atRest() && page == pager.settledPage,
          state = state,
          services = services,
          playback = controller.playback,
          onTap = controller::toggleChrome,
          onMotionHold = controller::onMotionHold,
          onMotionRelease = controller::onMotionRelease,
        )
      }
    }
  }
}

/** La foto che si espande dalla cella (e ci torna): la stessa bitmap, nel rettangolo di questo istante. */
@Composable
private fun TransitionPhoto(controller: ViewerController, services: ViewerServices, origin: Rect) {
  val state = controller.state
  val id = state.currentId
  val bitmap: ImageBitmap? = state.images[id]?.best
    ?: remember(id, services.thumbnails) { services.thumbnails.peek(controller.request.anchor.thumbKey(id)) }
  val placeholder = remember(id, services.placeholders) {
    val index = controller.view.snapshot.indexOf(id)
    if (index >= 0) controller.view.snapshot.thumbHashAt(index)?.let { services.placeholders.image(it) } else null
  }
  val fit = remember(id, controller.stage) { controller.photoFit(id) }
  val transition = controller.transition
  Box(
    modifier = Modifier
      .fillMaxSize()
      // Durante l'espansione i tocchi non passano alla griglia ne' al pager nascosto.
      .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
  ) {
    Box(
      modifier = Modifier.layout { measurable, constraints ->
        val frame = GalleryExpandSpec.frame(transition.progress.value.coerceIn(0f, 1f), origin, fit)
        val placeable = measurable.measure(
          Constraints.fixed(
            width = frame.width.roundToInt().coerceAtLeast(1),
            height = frame.height.roundToInt().coerceAtLeast(1),
          ),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
          placeable.place(frame.left.roundToInt(), frame.top.roundToInt())
        }
      },
    ) {
      val shown = bitmap ?: placeholder
      if (shown != null) {
        Image(
          bitmap = shown,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
          filterQuality = if (bitmap != null) FilterQuality.Medium else FilterQuality.Low,
        )
      } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
      }
    }
  }
}

/** Le barre, gli avvisi, il pannello informazioni e i fogli di conferma: sopra la foto, fermi. */
@Composable
private fun ViewerOverlays(
  controller: ViewerController,
  view: TimelineView,
  services: ViewerServices,
  viewModel: ViewerViewModel,
  format: ViewerFormat,
  backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
  atRest: Boolean,
  touchExploration: Boolean,
  sidePanel: Boolean,
  sheetHeight: Dp,
  panelPx: Float,
) {
  val state = controller.state
  val info = controller.info
  val request = controller.request
  val playback = controller.playback
  val context = LocalContext.current
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val currentId = state.currentId
  val snapshot = view.snapshot
  val currentIndex = snapshot.indexOf(currentId)
  val currentKind = controller.kindOf(currentId)
  val currentRef = controller.refOf(currentId)

  // "Scarica sul telefono" (Fase 4, D20): lo stato di ogni download e, a download concluso, l'avviso con l'esito.
  // Senza `PhoneDownload` (il viewer di sola lettura) non si osserva niente: nessun costo nell'apertura.
  val downloads: Map<Long, DownloadState> = services.download?.states?.collectAsState()?.value ?: NoDownloads
  if (services.download != null) {
    LaunchedEffect(downloads) {
      val finished = downloads.entries.firstOrNull { it.value.isTerminal } ?: return@LaunchedEffect
      messageOf(finished.value)?.let { state.message = it }
      services.download?.acknowledge(finished.key)
    }
  }

  // Il launcher del dialogo di sistema del cestino (D14).
  val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
  controller.launchSender = { sender -> trashLauncher.launch(IntentSenderRequest.Builder(sender).build()) }

  // I dettagli del catalogo; l'EXIF solo con il pannello aperto.
  var loaded by remember { mutableStateOf<Pair<MediaId, LoadedInfo>?>(null) }
  LaunchedEffect(currentId, snapshot.seq, state.infoOpen) {
    val ref = controller.refOf(currentId) ?: return@LaunchedEffect
    val wantExif = state.infoOpen && controller.kindOf(currentId) != MediaKind.VIDEO
    loaded = currentId to viewModel.load(services, currentId, ref, snapshot.seq, wantExif)
  }
  val current = loaded?.takeIf { it.first == currentId }?.second
  val details = current?.details

  val placeId = if (currentIndex >= 0) snapshot.placeAt(currentIndex) else 0
  val placeName = details?.place?.let { services.places.label(it) } ?: if (placeId > 0) services.places.labelOfId(placeId) else null
  val capture = details?.captureTime
  val title = capture?.let { format.dateTitle(it) }
    ?: (if (currentIndex >= 0) format.dateTitle(snapshot.localDayAt(currentIndex)) else null).orEmpty()
  val subtitle = format.subtitle(capture, placeName)

  // Le azioni: solo quelle che funzionano (D13).
  // Fase 4B (P14, P16): "Sposta", "Rinomina", "Elimina…" per il media del NAS di adesso. Il controller vive quanto il viewer: se si
  // chiude a meta' di un foglio non resta niente di avviato (un'operazione parte solo dal tocco di conferma del foglio).
  val nasController = services.nasActions?.let { actions ->
    remember(actions) {
      // R10: prima di rinominare, spostare o cestinare il file che sta guardando, il viewer chiude il suo lettore.
      NasActionsController(actions, scope) {
        val playing = playback.videoFor != null || playback.motionFor != null
        playback.clear()
        playing
      }
    }
  }
  if (nasController != null) LaunchedEffect(nasController, currentRef, currentId) { nasController.bind(currentRef, currentId) }
  val nasTarget = nasController?.target?.collectAsState()?.value
  val baseCapabilities = currentRef?.let { ViewerCapabilities.of(services, it) } ?: ViewerCapabilities(false, false, false, false, false)
  // E-D1 (piano §2): un media sul telefono e sul NAS ha un solo "Elimina…", con "Solo dal telefono" / "Ovunque" (il foglio del NAS).
  val phoneAndNas = nasTarget?.viaPhoneCopy == true
  val capabilities = if (phoneAndNas) baseCapabilities.copy(trash = false) else baseCapabilities
  val isFavorite = controller.isFavorite(currentId)
  val copyLabel = stringResource(R.string.viewer_copy_label)
  val handlers = ViewerActionHandlers(
    onToggleFavorite = controller::toggleFavorite,
    onToggleHidden = controller::toggleHidden,
    onShare = controller::share,
    onCopy = { controller.copy(copyLabel) },
    onDelete = { if (phoneAndNas) nasController?.requestDelete() else controller.askDelete() },
    onInfo = controller::toggleInfo,
    onDownload = controller::download,
  )
  val nasEntries = if (nasController != null) {
    nasMenuEntries(
      resources,
      NasActionCapabilities.of(nasTarget),
      NasActionHandlers(onMove = nasController::requestMove, onRename = nasController::requestRename, onDelete = nasController::requestDelete),
    )
  } else {
    null
  }
  val menu = viewerMenuEntries(
    resources,
    capabilities,
    isFavorite,
    request.secure,
    handlers,
    nasActions = nasEntries?.actions.orEmpty(),
    nasDelete = nasEntries?.delete.orEmpty(),
  )
  val barActions = viewerBarActions(resources, baseCapabilities, isFavorite, handlers)
  val nasNotice = nasController?.notice?.collectAsState()?.value
  if (nasController != null && nasNotice != null) {
    LaunchedEffect(nasNotice) {
      delay(MessageMillis)
      nasController.dismissNotice()
    }
  }

  // Il video: la barra con play, tempo e scorrimento.
  val playerUi = playback.collectPlayerState()
  val isVideo = currentKind == MediaKind.VIDEO
  var scrubWasPlaying by remember { mutableStateOf<Boolean?>(null) }
  val videoBar: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = if (isVideo && playback.videoFor == currentId && playerUi.problem == null) {
    {
      val player = playback.player
      GalleryVideoBar(
        positionMs = playerUi.positionMs,
        durationMs = playerUi.durationMs,
        playing = playerUi.playing,
        ended = playerUi.ended,
        backdrop = backdrop,
        onPlayPause = { if (playerUi.playing) player?.pause() else player?.play() },
        onScrub = { position ->
          if (scrubWasPlaying == null) {
            scrubWasPlaying = playerUi.playing
            if (playerUi.playing) player?.pause()
          }
          player?.seekTo(position, exact = false)
        },
        onScrubFinished = { position ->
          player?.seekTo(position, exact = true)
          if (scrubWasPlaying == true) player?.play()
          scrubWasPlaying = null
        },
        muted = if (playerUi.hasAudio) playerUi.muted else null,
        onToggleMute = { player?.setMuted(!playerUi.muted) },
      )
    }
  } else {
    null
  }

  val chromeShown = atRest && !controller.closing && (state.chromeVisible || touchExploration)
  val infoClosed by remember { derivedStateOf { info.progress.value < INFO_CLOSED } }
  val chromeModifier = if (sidePanel) Modifier.shrinkEnd { (panelPx * info.progress.value).roundToInt() } else Modifier

  GalleryViewerChrome(
    visible = chromeShown,
    title = title,
    subtitle = subtitle,
    backdrop = backdrop,
    onClose = controller::close,
    bottomVisible = chromeShown && (sidePanel || infoClosed),
    topActions = {
      if (menu.isNotEmpty()) GalleryMenuButton(entries = menu, backdrop = backdrop)
    },
    bottomActions = barActions,
    bottomContent = videoBar,
    modifier = chromeModifier,
  )

  Box(Modifier.fillMaxSize().then(chromeModifier)) {
    // Perche' la pagina non mostra l'originale (o il video non parte): la nota sta sopra la barra azioni.
    val image = state.images[currentId]
    val videoProblem = if (isVideo && playback.videoFor == currentId) playerUi.problem else null
    val nasRef = currentRef as? MediaRef.Nas
    val downloadState = nasRef?.let { downloads[it.locationId] }
    val pageNotice = PageNoticeKind.of(image, videoProblem, isVideo, state.originals[currentId])
    val noticeKind: PageNoticeKind? = if (!atRest) {
      null
    } else {
      when {
        pageNotice is PageNoticeKind.VideoProblem -> pageNotice
        downloadState is DownloadState.Queued -> PageNoticeKind.Downloading(progress = null, queued = true)
        downloadState is DownloadState.Running -> PageNoticeKind.Downloading(downloadState.progress)
        downloadState is DownloadState.NeedsMobileData -> PageNoticeKind.MobileData(downloadState.sizeBytes, MobileDataFor.DOWNLOAD)
        // Fino al primo fotogramma di un video del NAS si dice che si sta leggendo (Fase 4, "Loading fino al primo fotogramma").
        isVideo && nasRef != null && playback.videoFor == currentId && !playerUi.firstFrame && pageNotice == null -> PageNoticeKind.VideoLoading
        else -> pageNotice
      }
    }
    if (noticeKind != null) {
      val confirmMobileData: (() -> Unit)? = when {
        services.onConfirmMobileData == null || currentRef == null -> null
        downloadState is DownloadState.NeedsMobileData && nasRef != null -> ({ controller.confirmMobileDataForDownload(nasRef) })
        else -> ({ controller.confirmMobileData(currentRef) })
      }
      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = if (videoBar != null) NoticeBottomInset + VideoBarExtra else NoticeBottomInset),
      ) {
        PageNotice(
          kind = noticeKind,
          backdrop = backdrop,
          onConfirmMobileData = confirmMobileData,
          onRetry = if (nasRef != null) controller::retry else null,
          onCancelDownload = if (downloadState is DownloadState.Queued || downloadState is DownloadState.Running) controller::cancelDownload else null,
        )
      }
    }
    // Un avviso breve (E-V6, nascosto, copiato) in alto, sotto la barra.
    val message = state.message
    if (message != null) {
      Box(
        Modifier
          .align(Alignment.TopCenter)
          .statusBarsPadding()
          .padding(top = MessageTopInset),
      ) {
        MessageNotice(message = message, backdrop = backdrop)
      }
    } else if (nasNotice != null) {
      Box(
        Modifier
          .align(Alignment.TopCenter)
          .statusBarsPadding()
          .padding(top = MessageTopInset),
      ) {
        NasNoticeView(notice = nasNotice, backdrop = backdrop)
      }
    }
  }

  // Le informazioni: foglio a meta' sul telefono, colonna laterale da 840 dp.
  val infoVisible by remember { derivedStateOf { info.progress.value > 0f || state.infoOpen } }
  // Fase 5A (D16): lo stato del backup del media, seguito solo mentre il pannello e' aperto.
  val backupState by rememberBackupState(services.backup, currentId, active = infoVisible)
  if (infoVisible && currentKind != null) {
    val kind = currentKind
    val sections = remember(current, kind, controller.motionUnplayableVersion, placeName, currentId, backupState) {
      buildInfoSections(
        format = format,
        inputs = InfoInputs(
          kind = kind,
          details = details,
          exif = current?.exif,
          place = current?.place,
          placeName = placeName,
          motionUnplayable = currentId in controller.motionUnplayable,
          backup = backupState,
        ),
        onOpenMaps = if (details?.location != null) controller::askMaps else null,
      )
    }
    val infoTitle = stringResource(R.string.viewer_info_title)
    Box(Modifier.fillMaxSize().testTag(ViewerTags.InfoPanel)) {
      if (sidePanel) {
        InfoSidePanel(
          progress = { info.progress.value },
          width = SidePanelWidth,
          title = infoTitle,
          onClose = { state.infoOpen = false },
        ) { InfoSections(sections) }
      } else {
        InfoSheet(
          progress = { info.progress.value },
          height = sheetHeight,
          title = infoTitle,
          onClose = { state.infoOpen = false },
          onHeaderDrag = { dy -> scope.launch { info.dragBy(-dy / controller.sheetPx) } },
          onHeaderDragEnd = { velocity -> scope.launch { state.infoOpen = info.release(-velocity / controller.sheetPx, reducedMotion, InfoPanelState.CloseBias) } },
        ) { InfoSections(sections) }
      }
    }
  }

  // Il pannello aperto si chiude con Indietro prima del viewer.
  PredictiveBackHandler(enabled = state.infoOpen && !controller.closing && controller.sheet == null) { events ->
    try {
      events.collect { event -> info.progress.snapTo(lerp(1f, INFO_BACK_END, event.progress)) }
      state.infoOpen = false
    } catch (cancelled: CancellationException) {
      info.settle(true, reducedMotion)
      throw cancelled
    }
  }

  DeleteSheet(
    visible = controller.sheet == ViewerSheet.Delete,
    isVideo = isVideo,
    onConfirm = controller::confirmDelete,
    onCancel = { controller.sheet = null },
  )
  MapsSheet(
    visible = controller.sheet == ViewerSheet.Maps,
    onConfirm = { controller.confirmMaps(details?.location) },
    onCancel = { controller.sheet = null },
  )
  // I fogli delle voci del NAS (P14): "Solo dal telefono" usa il cestino di sistema del viewer.
  if (nasController != null) NasActionSheets(controller = nasController, onDeleteFromPhone = controller::confirmDelete)
}

/** Restringe di [end] pixel il lato destro: il chrome sta nella parte della foto, accanto alla colonna informazioni. */
private fun Modifier.shrinkEnd(end: () -> Int): Modifier = layout { measurable, constraints ->
  val width = (constraints.maxWidth - end()).coerceAtLeast(0)
  val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
  layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
}

private val SidePanelMinWidth: Dp = 840.dp
private val SidePanelWidth: Dp = 380.dp
private val PageSpacing: Dp = 16.dp
private val NoticeBottomInset: Dp = GalleryViewerChromeDefaults.BottomMargin + 48.dp + 28.dp

/** La barra del video (una capsula da 56 dp) e il suo spazio dalla barra azioni: la nota sale sopra anche quella. */
private val VideoBarExtra: Dp = 56.dp + GalleryViewerChromeDefaults.BarGap
private val MessageTopInset: Dp = 64.dp
private const val MessageMillis = 3200L
private const val MILLI = 1000f
private const val BackShiftFraction = 0.04f
private const val BackLiftFraction = 0.02f
private const val INFO_CLOSED = 0.02f
private const val INFO_BACK_END = 0.55f
private const val MIN_SHIFT_SCALE = 0.4f

/** A che punto dell'espansione entrano le pagine vicine (vedi `PagerLayer`): lontano dal primo fotogramma, prima della fine. */
private const val NeighborsAtProgress = 0.5f

/** Nessun download: la mappa vuota di un viewer senza `PhoneDownload`. */
private val NoDownloads: Map<Long, DownloadState> = emptyMap()

/** L'avviso breve per l'esito di un download concluso, o null se non c'e' niente da dire (stati non terminali). */
private fun messageOf(state: DownloadState): ViewerMessage? = when (state) {
  is DownloadState.Done -> ViewerMessage.Downloaded
  DownloadState.Cancelled -> ViewerMessage.DownloadCancelled
  is DownloadState.Failed -> when (state.reason) {
    DownloadFailure.NAS_UNREACHABLE, DownloadFailure.NOT_ALLOWED_HERE -> ViewerMessage.DownloadNasUnreachable
    DownloadFailure.NOT_FOUND -> ViewerMessage.ItemGone
    DownloadFailure.PHONE_FULL -> ViewerMessage.DownloadPhoneFull
    DownloadFailure.INTEGRITY -> ViewerMessage.DownloadIntegrity
    DownloadFailure.OTHER -> ViewerMessage.DownloadFailed
  }
  else -> null
}

/**
 * Fa partire il video del NAS [ref] nella pagina [id] (D19): guarda se si puo' (dati mobili da confermare, media sparito),
 * poi lo consegna al lettore come sorgente del NAS. Il perche' di un no sta in [ViewerState.originals], che la nota legge.
 * Un catalogo che lancia vale "il NAS non risponde": non fa cadere il viewer.
 */
private suspend fun startNasVideo(
  id: MediaId,
  ref: MediaRef.Nas,
  services: ViewerServices,
  state: ViewerState,
  playback: ViewerPlayback,
) {
  val nas = services.nasVideo
  if (nas == null) {
    state.originals[id] = OriginalProgress.Blocked(UnavailableReason.NAS_UNREACHABLE, null)
    return
  }
  val gate = try {
    // La posizione si risolve dal catalogo: fuori dal thread principale.
    withContext(services.dispatchers.io) { nas.check(ref) }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    state.originals[id] = OriginalProgress.Blocked(UnavailableReason.NAS_UNREACHABLE, null)
    return
  }
  when (gate) {
    is NasVideoGate.Ready -> {
      state.originals.remove(id)
      playback.playVideo(id, nas.playbackSource(ref))
    }
    is NasVideoGate.NeedsMobileData ->
      state.originals[id] = OriginalProgress.Blocked(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, gate.sizeBytes)
    NasVideoGate.Missing -> state.message = ViewerMessage.ItemGone
  }
}
