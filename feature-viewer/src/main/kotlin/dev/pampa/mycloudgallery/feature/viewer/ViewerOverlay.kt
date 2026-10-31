package dev.pampa.mycloudgallery.feature.viewer

import java.time.ZoneId
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.produceState
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
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPaneRules
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberGalleryWindow
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
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
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
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerKeyCallbacks
import dev.pampa.mycloudgallery.feature.viewer.gesture.viewerKeyboard
import dev.pampa.mycloudgallery.feature.viewer.menu.AlbumMenuResult
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupActionsController
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuHandlers
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupNoticeView
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareNotice
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareRefusedSheet
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareUi
import dev.pampa.mycloudgallery.feature.viewer.menu.EverywhereSummary
import dev.pampa.mycloudgallery.feature.viewer.menu.albumMenuEntries
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionCapabilities
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionHandlers
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionSheets
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionsController
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionsState
import dev.pampa.mycloudgallery.feature.viewer.menu.backupMenuEntries
import dev.pampa.mycloudgallery.feature.viewer.menu.similarMenuEntries
import dev.pampa.mycloudgallery.feature.viewer.menu.everywhereLocations
import dev.pampa.mycloudgallery.feature.viewer.menu.nasMenuEntries
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.gesture.viewerVerticalDrag
import dev.pampa.mycloudgallery.feature.viewer.info.InfoInputs
import dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSectionModel
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSheet
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextReadingNotice
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextSheet
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextState
import dev.pampa.mycloudgallery.feature.viewer.livetext.TextSelections
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSidePanel
import dev.pampa.mycloudgallery.feature.viewer.info.buildInfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.rememberBackupState
import dev.pampa.mycloudgallery.feature.viewer.info.rememberPeoplePanel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberSimilarCopiesPanel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberPlaceMapPanel
import dev.pampa.mycloudgallery.feature.viewer.info.sectionsWithPeople
import dev.pampa.mycloudgallery.feature.viewer.info.sectionsWithPlaceMap
import dev.pampa.mycloudgallery.feature.viewer.info.sheetHeight
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionBadge
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionNotice
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionNoticeView
import dev.pampa.mycloudgallery.feature.viewer.motion.PageSettle
import dev.pampa.mycloudgallery.feature.viewer.page.PageSpec
import dev.pampa.mycloudgallery.feature.viewer.page.ViewerPage
import dev.pampa.mycloudgallery.feature.viewer.video.Media3ViewerPlayer
import dev.pampa.mycloudgallery.feature.viewer.video.MotionMode
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoGate
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayerFactory
import dev.pampa.mycloudgallery.feature.viewer.video.collectPlayerState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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
 * solo lettore; la motion photo si anima quando la pagina si ferma e col badge "Movimento" (D50, D51); GIF e WebP si animano. Un tocco nasconde le barre (e quelle
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
  val window = rememberGalleryWindow()
  val dock = LocalViewerDock.current
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val scope = rememberCoroutineScope()
  val viewModel: ViewerViewModel = viewModel()
  val state = rememberSaveable(saver = ViewerState.Saver) { ViewerState(request.start) }
  // Gli esiti di scaricamenti conclusi prima che il viewer si aprisse non sono una notizia: si scartano in silenzio (Fase 4).
  // (`also`: una volta per servizio, prima della prima lettura degli esiti; `remember` non deve restituire Unit.)
  // D-15: nemmeno un "servono i dati mobili" rimasto da prima, se la rete di adesso non e' a consumo: non e' piu' vero.
  remember(services.download) {
    services.download?.also { downloads ->
      val network = services.link?.network()
      downloads.states.value
        .filterValues { it.isTerminal || (it is DownloadState.NeedsMobileData && network != null && network != NasNetwork.METERED) }
        .keys.forEach(downloads::acknowledge)
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
  // D-15 (prove sul campo): lo stato delle pagine segue la rete di adesso. Il primo valore e' la rete di quando si apre (niente da rifare); da li' in
  // poi ogni cambiamento rifa le pagine ferme per colpa della rete (dati mobili, VPN spenta, nessuna rete): non restano su un avviso non piu' vero.
  val link = services.link
  LaunchedEffect(link) {
    link?.changes?.drop(1)?.collect { network -> controller.networkChanged(network) }
  }

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
  // Fase 10 (P5, D13 (5)): affiancato alla Libreria, un tocco su una cella della griglia a lato cambia la foto, senza animazione d'apertura; la griglia resta dov'e'.
  val jump = dock?.jump
  LaunchedEffect(jump) {
    if (jump == null) return@LaunchedEffect
    val page = ViewerJumps.pageOf(latestView, jump.media)
    if (page != null && !controller.closing && page != pager.currentPage) pager.scrollToPage(page)
    dock?.jumpHandled(jump)
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
    controller.motion.state.notice = null
    // Cambio pagina: il video di prima si ferma e libera il decoder.
    if (playback.videoFor != null && playback.videoFor != currentId) playback.clear()
    if (playback.motionFor != null && playback.motionFor != currentId) playback.stopMotion()
  }
  // Foto in movimento (D50): l'animazione d'apertura parte quando il pager sta fermo da ~150 ms; ogni movimento (scorrimento, espansione,
  // trascinamento verso il basso, chiusura) la ferma. Il flusso emette solo i cambiamenti: chiudere le info o tornare dallo zoom non la rifa.
  LaunchedEffect(controller) {
    controller.motion.watch(
      snapshotFlow {
        PageSettle(state.currentId, moving = !atRest || controller.closing || pager.isScrollInProgress || pager.currentPage != pager.settledPage)
      },
    )
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

  // --- Tastiera (Fase 10, P4, D15, E-T10) --------------------------------------------------------------------------------

  // Le frecce sinistra e destra spostano di una foto, solo a viewer a riposo e senza un foglio, una selezione del testo o un soggetto staccato aperti (come il pager, che in quei
  // casi non scorre: E-V13). Esc e' il gesto Indietro, che `MainActivity` manda al gestore qui sopra; la rotella e' dello zoom (`ZoomableMedia`).
  fun stepPage(delta: Int): Boolean {
    if (!atRest || controller.closing || controller.sheet != null || controller.liveText.active || controller.cutout.active) return false
    val target = pager.currentPage + delta
    if (target !in 0 until pager.pageCount) return false
    scope.launch { pager.animateScrollToPage(target) }
    return true
  }
  val keyboard = Modifier.viewerKeyboard(ViewerKeyCallbacks(previous = { stepPage(-1) }, next = { stepPage(1) }))

  // --- Disegno ---------------------------------------------------------------------------------------------------------

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .then(keyboard)
      .onGloballyPositioned {
        controller.windowOffset = it.positionInWindow()
        controller.positioned = true
      },
  ) {
    val stagePx = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
    // Fase 10 (P5, D12): le informazioni in colonna da finestra estesa (840 dp, la soglia di sempre) secondo la finestra comune, che dentro un pannello e' quella del pannello.
    val sidePanel = GalleryPaneRules.infoBeside(window)
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
        // Con una selezione del testo o un soggetto staccato aperti il trascinamento verso il basso e' fermo (D45, E-V13).
        enabled = { atRest() && !state.zoomed && !controller.closing && !controller.liveText.active && !controller.cutout.active },
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
      // ... e con una selezione o un soggetto staccato aperti il pager non scorre: si chiude prima (E-V13).
      userScrollEnabled = atRest() && !controller.closing && !controller.liveText.active && !controller.cutout.active,
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
            // Fase 10 (P7, D22): la frase la scrive il descrittore unico; se il giorno e' rovinato resta quella di prima (tipo, giorno, luogo).
            description = MediaDescribers.describeCell(MediaDescribers.shared(services.places), snapshot, index, ZoneId.systemDefault())
              ?: format.pageDescription(kind, snapshot.localDayAt(index), placeName),
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
          onMotionTouch = controller::onMotionTouch,
          onPlayMotion = if (controller.motionAvailable(id)) controller::playMotion else null,
          liveText = controller.liveText,
          cutout = controller.cutout,
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
  // Fase 5B (P12): le voci del backup (Versioni, Ripristina originale, Correggi, Ruota, Modifica con…), "Condividi" con "Rimuovi la posizione" e
  // "Elimina solo dal telefono" con la sua verifica. Il controller non scrive niente: ogni voce e' una richiesta a `:app`, che apre il foglio sopra il viewer.
  val backupController = services.backupActions?.let { actions -> remember(actions) { BackupActionsController(actions, scope) } }
  if (backupController != null) LaunchedEffect(backupController, currentId, currentKind) { backupController.bind(currentId, currentKind == MediaKind.VIDEO) }
  val backupTarget = backupController?.target?.collectAsState()?.value
  val shareWithoutLocation = backupController?.removeLocation?.collectAsState()?.value == true
  val backupShare = backupController?.shareState?.collectAsState()?.value ?: BackupShareUi.Idle
  val backupNotice = backupController?.notice?.collectAsState()?.value
  val baseCapabilities = currentRef?.let { ViewerCapabilities.of(services, it) } ?: ViewerCapabilities(false, false, false, false, false)
  // E-D1 (piano §2): un media sul telefono e sul NAS ha un solo "Elimina…", con "Solo dal telefono" / "Ovunque" (il foglio del NAS).
  val phoneAndNas = nasTarget?.viaPhoneCopy == true
  // Correzioni 5B (MC-2): con "Rimuovi la posizione" acceso "Copia" non c'e': metterebbe negli appunti l'indirizzo dell'originale, posizione compresa.
  val capabilities = baseCapabilities.copy(trash = baseCapabilities.trash && !phoneAndNas, copy = baseCapabilities.copy && !shareWithoutLocation)
  val isFavorite = controller.isFavorite(currentId)
  val copyLabel = stringResource(R.string.viewer_copy_label)
  // Il testo dal vivo (Fase 6, P12, D42, D43): il testo letto dell'indice, da qualunque dispositivo, anche con la lettura spenta qui.
  val liveText = controller.liveText
  LaunchedEffect(currentId, services.textSource) {
    liveText.bind(currentId)
    // VT-2: il testo che arriva, cambia o si ritira a foto aperta si vede subito (righe e pulsante "Testo").
    services.textSource?.changes?.collect { liveText.refresh(currentId) }
  }
  val hasLiveText = currentKind != MediaKind.VIDEO && liveText.hasText(currentId)
  // Il ritaglio del soggetto (Fase 6, P13, D44, D46): la voce ⋮ e l'azione di TalkBack; il ritaglio di un media si chiude cambiando media (E-H1).
  val cutout = controller.cutout
  LaunchedEffect(currentId) { cutout.bind(currentId) }
  val canCutout = currentKind != MediaKind.VIDEO && cutout.available
  val handlers = ViewerActionHandlers(
    onToggleFavorite = controller::toggleFavorite,
    onToggleHidden = controller::toggleHidden,
    onShare = { if (backupController != null && backupController.capabilities.share) backupController.share() else controller.share() },
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
  val backupEntries = if (backupController != null) {
    backupMenuEntries(resources, backupController.capabilities, backupTarget, BackupMenuHandlers(backupController::perform))
  } else {
    emptyList()
  }
  val menu = viewerMenuEntries(
    resources,
    capabilities,
    isFavorite,
    request.secure,
    handlers,
    nasActions = nasEntries?.actions.orEmpty(),
    nasDelete = nasEntries?.delete.orEmpty(),
    backupActions = backupEntries,
    shareWithoutLocation = shareWithoutLocation,
    onPlayMotion = if (controller.motionAvailable(currentId)) controller::playMotion else null,
    onText = if (hasLiveText) ({ liveText.openSheet(currentId) }) else null,
    onCutout = if (canCutout) ({ cutout.cutLargest(currentId) }) else null,
    similarActions = similarMenuEntries(
      resources,
      services.similarSearch.availability(currentId),
      onFind = { services.similarSearch.findSimilar(currentId) },
      onExplain = { state.message = ViewerMessage.SimilarUnavailable },
    ),
    dock = LocalViewerDock.current.entry(secure = request.secure),
    albumActions = albumMenuEntries(
      resources,
      services.albums.menu(currentId),
      onAdd = { services.albums.addToAlbum(currentId) },
      onCover = { scope.launch { state.message = albumMessage(services.albums.setAsCover(currentId), ViewerMessage.AlbumCoverSet) } },
      onRemove = { scope.launch { state.message = albumMessage(services.albums.removeFromAlbum(currentId), ViewerMessage.AlbumRemoved) } },
    ),
  )
  val barActions = viewerBarActions(resources, baseCapabilities, isFavorite, handlers, onText = if (hasLiveText) ({ liveText.toggleHighlight(currentId) }) else null)
  val nasNotice = nasController?.notice?.collectAsState()?.value
  if (nasController != null && nasNotice != null) {
    LaunchedEffect(nasNotice) {
      delay(MessageMillis)
      nasController.dismissNotice()
    }
  }
  // Gli avvisi del backup si spengono da soli come gli altri: la copia pronta e la frase dopo "Modifica con…". Correzioni 5B (MC-1): spegnere
  // l'avviso "Pronta" e' solo lo stato; la copia e il permesso restano finche' l'altra app non ha finito (li rilascia `:app`).
  if (backupController != null && backupShare is BackupShareUi.Ready) {
    LaunchedEffect(backupShare) {
      delay(MessageMillis)
      backupController.hideShareNotice()
    }
  }
  if (backupController != null && backupNotice != null) {
    LaunchedEffect(backupNotice) {
      delay(MessageMillis)
      backupController.dismissNotice()
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

  // Con il soggetto staccato i controlli sono i suoi (la barra del ritaglio): niente chrome sotto, niente controlli uno sopra l'altro.
  val chromeShown = atRest && !controller.closing && !controller.cutout.active && (state.chromeVisible || touchExploration)
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
      // Il badge "Movimento" (D51): visibile con le barre, accanto al menu ⋮. Funziona sempre, anche con l'interruttore spento.
      if (controller.motionAvailable(currentId)) {
        MotionBadge(
          playing = playback.motionFor == currentId && playback.motionMode == MotionMode.FULL,
          unavailable = controller.motion.state.isUnavailable(currentId),
          onClick = controller::playMotion,
          backdrop = backdrop,
          loading = controller.motion.state.isLoading(currentId),
        )
      }
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
    // Foto in movimento del NAS (E-V15, D51): i dati mobili da confermare, o "non disponibile ora".
    val motionNotice = controller.motion.state.notice
    if (motionNotice != null && atRest) {
      val noticeId = when (motionNotice) {
        is MotionNotice.NeedsMobileData -> motionNotice.id
        is MotionNotice.Unavailable -> motionNotice.id
      }
      LaunchedEffect(motionNotice) {
        delay(MessageMillis)
        if (controller.motion.state.notice == motionNotice) controller.motion.state.notice = null
      }
      Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = NoticeBottomInset)) {
        MotionNoticeView(
          notice = motionNotice,
          backdrop = backdrop,
          onConfirmMobileData = if (services.onConfirmMobileData != null) ({ controller.confirmMotionMobileData(noticeId) }) else null,
        )
      }
    }
    // Un avviso breve (E-V6, nascosto, copiato) in alto, sotto la barra.
    val message = state.message
    if (message == null && liveText.state is LiveTextState.ReadingNow && liveText.owner == currentId) {
      Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = MessageTopInset)) {
        LiveTextReadingNotice(backdrop = backdrop)
      }
    }
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
    } else if (backupController != null && (backupShare is BackupShareUi.Preparing || backupShare is BackupShareUi.Ready)) {
      Box(
        Modifier
          .align(Alignment.TopCenter)
          .statusBarsPadding()
          .padding(top = MessageTopInset),
      ) {
        BackupShareNotice(share = backupShare, backdrop = backdrop, onCancel = backupController::dismissShare)
      }
    } else if (backupNotice != null) {
      Box(
        Modifier
          .align(Alignment.TopCenter)
          .statusBarsPadding()
          .padding(top = MessageTopInset),
      ) {
        BackupNoticeView(notice = backupNotice, backdrop = backdrop)
      }
    }
  }

  // Le informazioni: foglio a meta' sul telefono, colonna laterale da 840 dp.
  val infoVisible by remember { derivedStateOf { info.progress.value > 0f || state.infoOpen } }
  // Fase 5A (D16): lo stato del backup del media, seguito solo mentre il pannello e' aperto.
  val backupState by rememberBackupState(services.backup, currentId, active = infoVisible)
  // Fase 5A (P7, D36): "N copie" nel pannello. Una copia che sta nell'indice si raggiunge scorrendo il pager; una fuori indice (le copie unite
  // in una cella hanno nell'indice solo la copertina) si guarda a parte, sopra il viewer, e "Indietro" torna alla foto di prima.
  var outsideCopy by remember { mutableStateOf<MediaId?>(null) }
  val similar = rememberSimilarCopiesPanel(
    source = services.similarCopies,
    id = currentId,
    active = infoVisible,
    refreshKey = view.snapshot.seq,
    onView = { copyId ->
      val page = view.viewIndexOf(copyId)
      if (page >= 0) scope.launch { controller.pager.animateScrollToPage(page) } else if (services.outsideCopy != null) outsideCopy = copyId
    },
  )
  // Fase 8 (P9, D25): la riga "Persone" (volti, nomi, "Chi e'?"), solo con la porta legata e il pannello aperto.
  val peoplePanel = rememberPeoplePanel(services.people, currentId, active = infoVisible)
  // Fase 9 (P4, D30): la riga "Mostra sulla mappa" e la mini-mappa (solo offline), mai per un media aperto dai Nascosti.
  val placeMapPanel = rememberPlaceMapPanel(services.placeMap, currentId, active = infoVisible, secure = request.secure)
  if (infoVisible && currentKind != null) {
    val kind = currentKind
    val baseSections = remember(current, kind, controller.motionUnplayableVersion, placeName, currentId, backupState) {
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
    val placeHeader = stringResource(R.string.viewer_info_h_place)
    val sections = remember(baseSections, similar.sections, peoplePanel.sections, placeMapPanel.sections, placeHeader) {
      sectionsWithPlaceMap(sectionsWithSimilar(sectionsWithPeople(baseSections, peoplePanel.sections), similar.sections, format), placeMapPanel.sections, placeHeader)
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

  // Il testo dal vivo: Indietro chiude la selezione prima del viewer; il foglio "Testo nella foto" (D46).
  BackHandler(enabled = liveText.canDismiss && !controller.closing && controller.sheet == null) { liveText.dismiss() }
  // ... e il ritaglio: Indietro chiude il soggetto staccato prima del viewer (D45).
  BackHandler(enabled = cutout.canDismiss && !controller.closing && controller.sheet == null) { cutout.dismiss() }
  val sheetLayout = (liveText.state as? LiveTextState.Sheet)?.layout
  val sheetText = remember { mutableStateOf("") }
  if (sheetLayout != null) sheetText.value = remember(sheetLayout) { TextSelections.allText(sheetLayout) }
  val clipLabel = stringResource(R.string.livetext_clip_label)
  LiveTextSheet(
    visible = sheetLayout != null && liveText.owner == currentId,
    text = sheetText.value,
    onCopyAll = { liveText.copyAll(clipLabel) },
    onClose = liveText::closeSheet,
  )
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
  // Fase 5A (D36): il foglio "Separare questa copia?", accanto agli altri fogli.
  similar.confirmSheet()
  // Fase 8 (P9): i fogli della riga "Persone" ("Chi e'?", "Non e' …", "Ignora questo volto").
  peoplePanel.sheets()
  // I fogli delle voci del NAS (P14): "Solo dal telefono" usa il cestino di sistema del viewer.
  if (nasController != null) {
    // Fase 5B (E-D15, D22): con "Ovunque" il foglio elenca anche le versioni precedenti; "Solo dal telefono" passa dalla verifica del backup.
    val deleteOpen = nasController.state.collectAsState().value is NasActionsState.Delete
    val everywhere by produceState<EverywhereSummary?>(initialValue = null, backupController, currentId, deleteOpen) {
      value = if (deleteOpen) backupController?.everywhere() else null
    }
    NasActionSheets(
      controller = nasController,
      onDeleteFromPhone = controller::confirmDelete,
      extraLocations = everywhereLocations(everywhere),
      onPhoneOnlyDelete = backupController?.let { bc -> { bc.deleteFromPhone(controller::confirmDelete) } },
      // Correzioni 5B (LS-14): "Ovunque" toglie dal telefono anche le altre copie del media.
      onEverywherePhone = backupController?.let { bc -> { bc.deleteEverywhereFromPhone(controller::confirmDelete) } },
    )
  }
  // Fase 5B (D26, L41): una condivisione ferma ha il suo foglio, con "Condividi con la posizione" solo come scelta esplicita.
  if (backupController != null) {
    BackupShareRefusedSheet(
      state = backupShare as? BackupShareUi.Refused,
      onShareWithLocation = backupController::shareWithLocation,
      onDismiss = backupController::dismissShare,
    )
  }
  // L'altra copia di un gruppo che non sta nell'indice: per ultima, sopra tutto il resto.
  outsideCopy?.let { copyId -> OutsideCopyLayer(id = copyId, services = services, playback = playback, onClose = { outsideCopy = null }) }
}

/** La sezione "N copie" va subito dopo quella del file (prima di "Telefono"); se il file non c'e', in fondo. */
private fun sectionsWithSimilar(base: List<InfoSectionModel>, similar: List<InfoSectionModel>, format: ViewerFormat): List<InfoSectionModel> {
  if (similar.isEmpty()) return base
  val fileHeader = format.resources.getString(R.string.viewer_info_h_file)
  val at = base.indexOfFirst { it.header == fileHeader }
  return if (at < 0) base + similar else base.take(at + 1) + similar + base.drop(at + 1)
}

/** Restringe di [end] pixel il lato destro: il chrome sta nella parte della foto, accanto alla colonna informazioni. */
private fun Modifier.shrinkEnd(end: () -> Int): Modifier = layout { measurable, constraints ->
  val width = (constraints.maxWidth - end()).coerceAtLeast(0)
  val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
  layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
}

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
/** L'avviso breve di una voce degli album (Fase 7, P10): quello del successo, o perche' la tabella dei permessi non lo permette, o che non e' riuscito. */
private fun albumMessage(result: AlbumMenuResult, done: ViewerMessage): ViewerMessage = when (result) {
  AlbumMenuResult.DONE -> done
  AlbumMenuResult.NOT_ALLOWED -> ViewerMessage.AlbumDenied
  AlbumMenuResult.FAILED -> ViewerMessage.AlbumFailed
}

private fun messageOf(state: DownloadState): ViewerMessage? = when (state) {
  is DownloadState.Done -> ViewerMessage.Downloaded
  DownloadState.Cancelled -> ViewerMessage.DownloadCancelled
  is DownloadState.Failed -> when (state.reason) {
    DownloadFailure.NAS_UNREACHABLE, DownloadFailure.NOT_ALLOWED_HERE -> ViewerMessage.DownloadNasUnreachable
    DownloadFailure.VPN_OFF_AWAY -> ViewerMessage.DownloadVpnOff
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
    // D-15: da questa rete il NAS non si raggiunge (VPN spenta fuori casa, nessuna rete): la nota lo dice, senza proporre i dati mobili, e la pagina
    // riparte da sola quando la rete cambia ([ViewerController.networkChanged]).
    is NasVideoGate.Unreachable -> state.originals[id] = OriginalProgress.Blocked(gate.reason, null)
    NasVideoGate.Missing -> state.message = ViewerMessage.ItemGone
  }
}
