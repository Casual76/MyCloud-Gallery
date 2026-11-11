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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidCornerRadii
import dev.antigravity.fluidengine.ui.fluidphysics.FluidForm
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsClip
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidPhysicsState
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberGalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryViewerMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryViewerMenuHost
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryViewerMenuState
import dev.pampa.mycloudgallery.core.designsystem.chrome.rememberGalleryViewerMenuState
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryExpandSpec
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGlassGrowth
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGlassMotion
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
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerGestureCallbacks
import dev.pampa.mycloudgallery.feature.viewer.gesture.viewerGestures
import dev.pampa.mycloudgallery.feature.viewer.page.fitRect
import dev.pampa.mycloudgallery.feature.viewer.page.viewerContentLayer
import dev.pampa.mycloudgallery.feature.viewer.info.InfoInputs
import dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState
import dev.pampa.mycloudgallery.feature.viewer.info.InfoContent
import dev.pampa.mycloudgallery.feature.viewer.info.InfoGeometry
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSheet
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextReadingNotice
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextSheet
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextState
import dev.pampa.mycloudgallery.feature.viewer.livetext.TextSelections
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSidePanel
import dev.pampa.mycloudgallery.feature.viewer.info.averageColor
import dev.pampa.mycloudgallery.feature.viewer.info.backupNote
import dev.pampa.mycloudgallery.feature.viewer.info.buildInfoModel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberAlbumsPanel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberBackupState
import dev.pampa.mycloudgallery.feature.viewer.info.rememberPeoplePanel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberSimilarCopiesPanel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberPlaceMapPanel
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

/** Fotogrammi fra il riposo e il fondale di vetro, fra il fondale e la barra in alto, fra questa e quella in basso (l'arrivo a riposo a passi). */
private const val BackdropDelayFrames = 1
private const val ChromeDelayFrames = 1
private const val BottomBarDelayFrames = 3

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
  // Il menu ⋮ (K1, GS-1): il suo stato sta qui perche' lo leggono le frecce della tastiera (qui sotto) e la barra (in `ViewerOverlays`).
  val menuState = rememberGalleryViewerMenuState()
  val startPage = remember {
    view.viewIndexOf(state.currentId).takeIf { it >= 0 } ?: view.viewIndexOf(request.start).coerceAtLeast(0)
  }
  val pager = rememberPagerState(initialPage = startPage) { view.size }
  // B5 (G3): le foto piu' vecchie a sinistra di quella aperta. Si decide una volta, all'apertura, dall'ordine della vista (vedi `ViewerPaging`): l'indice, gli id salvati, il ritorno alla
  // cella e il precarico non cambiano, cambia da che parte sta ogni pagina; le frecce della tastiera vanno dove si vede, le azioni di TalkBack seguono la numerazione.
  val layoutRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val pagerOrder = remember(request) { ViewerPaging.orderOf(view) }
  val reversePager = ViewerPaging.reverseLayout(pagerOrder, layoutRtl)
  val leftDelta = ViewerPaging.leftPageDelta(reversePager, layoutRtl)
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
  // CU-1: comunque esca il viewer, nessuna cella della griglia resta vuota.
  DisposableEffect(controller) { onDispose { controller.releaseCell() } }
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
  // Aperto: l'espansione e' finita (si vede il pager). A riposo: aperto e fermo, senza una chiusura in corso (H1: trascinamento, pizzico, Indietro,
  // ritorno o volo). Durante la chiusura il contenuto resta quello vero (la foto con i suoi tasselli, il fotogramma del video), ma le barre sfumano
  // via, il video si mette in pausa e il pager non scorre.
  val opened by remember { derivedStateOf { controller.opened } }
  val atRest by remember { derivedStateOf { controller.opened && !controller.dismiss.active } }
  var resumeAfterRest by remember { mutableStateOf(false) }
  // Il fondale di vetro delle barre: dal primo riposo in poi (vedi il disegno qui sotto). **A passi**, non tutto nel fotogramma del riposo: il fondale (un layer a schermo
  // intero), la barra in alto e quella in basso compaiono a un fotogramma di distanza l'uno dall'altro (la barra in basso a tre). Sul Tab S9 (03/11) l'arrivo a riposo
  // era il fotogramma piu' lungo dell'apertura, 34 ms con le sue 47 passate di vetro in piu': a passi, 28 ms (il vetro delle barre cambia di un fotogramma, non d'aspetto).
  var chromeBackdrop by remember { mutableStateOf(state.restored) }
  var chromeAllowed by remember { mutableStateOf(state.restored) }
  var bottomAllowed by remember { mutableStateOf(state.restored) }
  LaunchedEffect(Unit) {
    if (!chromeBackdrop) {
      snapshotFlow { atRest }.first { it }
      repeat(BackdropDelayFrames) { withFrameNanos { } }
      chromeBackdrop = true
      repeat(ChromeDelayFrames) { withFrameNanos { } }
      chromeAllowed = true
      repeat(BottomBarDelayFrames) { withFrameNanos { } }
      bottomAllowed = true
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
  // H1: l'Indietro predittivo guida lo stesso stato della chiusura col dito (il contenuto si rimpicciolisce, lo sfondo sfuma a meta') e al rilascio
  // prende la stessa uscita: il volo alla cella.
  PredictiveBackHandler(enabled = !controller.closing && controller.sheet == null) { events ->
    try {
      events.collect { event ->
        controller.onBackProgress(event.progress, fromLeft = event.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT)
      }
      controller.close()
    } catch (cancelled: CancellationException) {
      controller.onBackCancelled()
      throw cancelled
    }
  }

  // --- Tastiera (Fase 10, P4, D15, E-T10) --------------------------------------------------------------------------------

  // Le frecce sinistra e destra spostano di una foto (la sinistra va alla pagina che sta a sinistra, quale sia: B5), solo a viewer a riposo e senza un foglio, una selezione del testo o un soggetto staccato aperti (come il pager, che in quei
  // casi non scorre: E-V13). Esc e' il gesto Indietro, che `MainActivity` manda al gestore qui sopra; la rotella e' dello zoom (`ZoomableMedia`).
  // K1 (GS-1): nemmeno con il menu ⋮ aperto. Le sue voci agiscono sulla foto per cui si sono composte: una freccia che cambiasse foto sotto il menu aperto
  // farebbe agire "Aggiungi ad album" o "Trova simili" sulla foto di prima. Il pop-up prende comunque il fuoco (i tasti vanno a lui); questa e' la seconda
  // difesa, e vale anche per la goccia, che il fuoco non lo prende. Chiuso il menu, il viewer si riprende il fuoco.
  fun stepPage(delta: Int): Boolean {
    if (!atRest || controller.closing || controller.sheet != null || controller.liveText.active || controller.cutout.active || menuState.isOpen) return false
    val target = pager.currentPage + delta
    if (target !in 0 until pager.pageCount) return false
    scope.launch { pager.animateScrollToPage(target) }
    return true
  }
  val keyboard = Modifier.viewerKeyboard(
    ViewerKeyCallbacks(previous = { stepPage(leftDelta) }, next = { stepPage(-leftDelta) }),
    holdsFocus = { !menuState.isOpen },
  )

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
    // H2: anche nelle finestre basse e larghe (il telefono in orizzontale), dove meta' altezza sarebbe una striscia inservibile.
    val sidePanel = InfoGeometry.side(window)
    val panelPx = with(density) { SidePanelWidth.toPx() }
    controller.stage = stagePx
    controller.sidePanel = sidePanel

    LaunchedEffect(Unit) { controller.open() }

    val shown by remember { derivedStateOf { transition.isShown } }
    val originRect = controller.origin
    if (shown && originRect != null) {
      // La cella della parete e' squadrata (G2: `GalleryWall.CornerScale` = 0): la sagoma parte con i suoi angoli, gli stessi su cui la chiusura
      // si posa (H1).
      val thumbForm = remember(originRect) { FluidForm.Slab(originRect, FluidCornerRadii.all(0f)) }
      val fullForm = remember(stagePx) { FluidForm.Slab(Rect(Offset.Zero, stagePx)) }
      val physics = rememberFluidPhysicsState(thumbForm)
      SideEffect { physics.driveExternally(from = thumbForm, to = fullForm, progress = { transition.progress.value }) }

      val background by animateColorAsState(
        targetValue = if (state.chromeVisible || touchExploration) scheme.background else scheme.scrim,
        animationSpec = if (reducedMotion) snap() else FluidMotion.color(),
        label = "viewer background",
      )

      // 1. La foto: la sagoma che cresce dalla cella, con dentro il pager (aperto) o la foto che si espande. Nella chiusura (H1) la sagoma resta
      // ferma a tutto schermo: si muove solo il contenuto (`PagerLayer`) e lo sfondo sfuma, letto nel disegno, lasciando vedere la pagina sotto.
      val dismiss = controller.dismiss
      Box(modifier = Modifier.fillMaxSize()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .fluidPhysicsClip(physics) { transition.progress.value < ViewerController.FULLY_OPEN },
        ) {
          Box(Modifier.fillMaxSize().drawBehind { drawRect(background, alpha = dismiss.backdropAlpha) })
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
              opened = { opened },
              format = format,
              reverseLayout = reversePager,
            )
            if (!opened) {
              TransitionPhoto(controller = controller, services = services, origin = originRect)
            }
            // Durante il volo (H1) nessun tocco arriva al contenuto che si allontana: ne' allo zoom ne' al pager.
            if (controller.closing) ClosingTouchShield()
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
        chromeBackdropReady = chromeAllowed,
        bottomReady = bottomAllowed,
        touchExploration = touchExploration,
        sidePanel = sidePanel,
        panelPx = panelPx,
        menuState = menuState,
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
  opened: () -> Boolean,
  format: ViewerFormat,
  reverseLayout: Boolean,
) {
  val state = controller.state
  val pager = controller.pager
  val request = controller.request
  val info = controller.info
  val stage = controller.stage
  val positionText = stringResource(R.string.viewer_position, GalleryNumbers.format(pager.currentPage + 1), GalleryNumbers.format(view.size))
  val zoomedText = stringResource(R.string.viewer_zoomed)
  val nextText = stringResource(R.string.viewer_action_next)
  val previousText = stringResource(R.string.viewer_action_previous)
  val infoText = stringResource(R.string.viewer_action_info)
  val favoriteText = stringResource(R.string.viewer_action_favorite)
  val unfavoriteText = stringResource(R.string.viewer_action_unfavorite)
  val scope = rememberCoroutineScope()
  val gestures = remember(controller) {
    ViewerGestureCallbacks(
      context = controller::gestureContext,
      onDismissStart = controller::onDismissStart,
      onDismissMove = controller::onDismissMove,
      onDismissEnd = controller::onDismissEnd,
      onDismissCancel = controller::onDismissCancel,
      onInfoDrag = controller::onInfoDrag,
      onInfoEnd = controller::onInfoEnd,
      onInfoCancel = controller::onInfoCancel,
      onInfoPinch = controller::onInfoPinch,
    )
  }
  // H2: con il pannello aperto ogni pagina si ritaglia ai suoi bordi: una foto orizzontale ingrandita per riempire la meta' alta non sconfina
  // nella vicina mentre si scorre.
  val infoShown by remember { derivedStateOf { info.progress.value > 0f } }

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
      // H1: l'arbitro dei gesti (`ViewerGestureArbiter`) prima del layer che sposta il contenuto: le dita si leggono nelle coordinate dello schermo.
      // Con una selezione del testo o un soggetto staccato aperti nessuna chiusura ne' pannello (D45, E-V13): lo dice `gestureContext`.
      .viewerGestures(gestures)
      // La chiusura (H1) sposta tutto il pager (`ViewerDismissState`: trascinamento, pizzico, Indietro, volo alla cella); il pannello informazioni
      // (H2) sposta ogni pagina con le sue proporzioni (`infoPageLayer`, qui sotto). Letti nel disegno: un fotogramma di gesto non ricompone niente.
      .viewerContentLayer(visible = opened) { controller.dismiss.layer() }
      .semantics {
        contentDescription = positionText
        if (state.zoomed) stateDescription = zoomedText
        // Fase 10 P8 (D23, E-AC7, E-AC9): si va avanti e indietro, si apre Informazioni e si sceglie il preferito senza gesti
        // (con lo scorrimento a due dita o con queste azioni; il pizzico non serve). Condividi ed Elimina (con il foglio) sono i
        // tasti della barra, che i lettori di schermo raggiungono.
        customActions = buildList {
          val page = pager.currentPage
          // B5: "successiva" e "precedente" seguono la numerazione ("5 di 50" -> "6 di 50") e lo scorrimento in avanti di TalkBack, non la direzione sullo schermo: chi non vede
          // le pagine sentirebbe "foto successiva" portare al numero piu' basso. La freccia sinistra della tastiera, invece, va a sinistra (sopra).
          if (page + 1 < view.size) add(CustomAccessibilityAction(nextText) { scope.launch { pager.animateScrollToPage(page + 1) }; true })
          if (page > 0) add(CustomAccessibilityAction(previousText) { scope.launch { pager.animateScrollToPage(page - 1) }; true })
          add(CustomAccessibilityAction(infoText) { controller.toggleInfo(); true })
          if (services.actions != null) {
            val label = if (controller.isFavorite(state.currentId)) unfavoriteText else favoriteText
            add(CustomAccessibilityAction(label) { controller.toggleFavorite(); true })
          }
        }
      },
  ) {
    HorizontalPager(
      state = pager,
      modifier = Modifier.fillMaxSize().testTag(ViewerTags.Pager),
      pageSpacing = PageSpacing,
      reverseLayout = reverseLayout,
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
          // Aperto, non "a riposo": durante la chiusura la pagina resta quella vera (i tasselli, il fotogramma del video), ed e' lei che vola.
          isCurrent = opened() && page == pager.settledPage,
          exiting = controller.exitingVideo,
          state = state,
          services = services,
          playback = controller.playback,
          onTap = controller::toggleChrome,
          onMotionTouch = controller::onMotionTouch,
          onPlayMotion = if (controller.motionAvailable(id)) controller::playMotion else null,
          liveText = controller.liveText,
          cutout = controller.cutout,
          // Fase 10 (P6, D20, E-T18): la foto si trascina verso un'altra app, mai dai Nascosti.
          dragEnabled = !request.secure,
          sidePanel = controller.sidePanel,
          modifier = Modifier.infoPageLayer(controller, remember(spec.aspect, stage) { fitRect(stage, spec.aspect) }, panelPx, clip = infoShown),
        )
      }
    }
  }
}

/**
 * **Dove sta una pagina con il pannello informazioni** (H2): il punto che sposta il contenuto per il pannello, per ogni pagina con le sue
 * proporzioni ([rest], la foto intera nella scena). Sul telefono il contenuto resta attaccato al bordo del pannello e una foto orizzontale si
 * ingrandisce a riempire la meta' alta ([InfoGeometry.contentLayer]); con la colonna laterale si fa piu' piccolo nello spazio che resta
 * ([InfoGeometry.sideLayer]). Il ritaglio ai bordi della pagina sta fuori dalla trasformazione ([clip], solo a pannello visibile).
 */
private fun Modifier.infoPageLayer(controller: ViewerController, rest: Rect, panelPx: Float, clip: Boolean): Modifier =
  (if (clip) clipToBounds() else this).viewerContentLayer(visible = { true }) {
    val stage = controller.stage
    val info = controller.info
    if (controller.sidePanel) {
      InfoGeometry.sideLayer(stage, rest, info.progress.value, panelPx)
    } else {
      InfoGeometry.contentLayer(stage, rest, info.panelTop(stage.height))
    }
  }

/** Durante il volo di chiusura (H1) i tocchi si fermano qui: niente zoom, pager o barre su un contenuto che se ne sta andando. */
@Composable
private fun ClosingTouchShield() {
  Box(
    Modifier
      .fillMaxSize()
      .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
  )
}

/** La foto che si espande dalla cella all'apertura: la stessa bitmap, nel rettangolo di questo istante. */
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
  chromeBackdropReady: Boolean,
  bottomReady: Boolean,
  touchExploration: Boolean,
  sidePanel: Boolean,
  panelPx: Float,
  menuState: GalleryViewerMenuState,
) {
  val state = controller.state
  val info = controller.info
  val request = controller.request
  val playback = controller.playback
  val context = LocalContext.current
  val resources = LocalResources.current
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val currentId = state.currentId
  // K1 (GS-1): le voci del menu ⋮ sono della foto per cui si sono composte. Se la foto cambia con il menu aperto (un'eliminazione da un altro dispositivo, un
  // tocco sulla griglia affiancata, qualunque altra via), il menu si chiude invece di restare sopra la foto nuova.
  LaunchedEffect(currentId) { menuState.close() }
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
  val chromeShown = atRest && chromeBackdropReady && !controller.closing && !controller.cutout.active && (state.chromeVisible || touchExploration)
  val infoClosed by remember { derivedStateOf { info.progress.value < INFO_CLOSED } }
  // CU-5: la barra in alto riassume l'elemento a pannello chiuso; da meta' corsa in su il pannello dice data, ora e luogo per esteso, e la
  // barra tiene solo i suoi tasti (una cosa si legge una volta sola). Cambia una volta per apertura, non a ogni fotogramma.
  val infoSpeaks by remember { derivedStateOf { info.progress.value >= INFO_SPEAKS } }
  val chromeModifier = if (sidePanel) Modifier.shrinkEnd { (panelPx * info.progress.value).roundToInt() } else Modifier

  GalleryViewerChrome(
    visible = chromeShown,
    title = if (infoSpeaks) "" else title,
    subtitle = if (infoSpeaks) null else subtitle,
    backdrop = backdrop,
    onClose = controller::close,
    bottomVisible = chromeShown && bottomReady && (sidePanel || infoClosed),
    topActions = {
      // Il badge "Movimento" (D51): visibile con le barre, accanto al menu ⋮. Funziona sempre, anche con l'interruttore spento. Senza un fondale suo: prende quello della barra, come
      // il tasto di chiusura e il ⋮ (B4). Compare e sparisce con i token di FluidMotion al cambio di foto, invece di farsi posto di colpo.
      AnimatedVisibility(
        visible = controller.motionAvailable(currentId),
        enter = GalleryGlassMotion.enter(GalleryGlassGrowth.Width),
        exit = GalleryGlassMotion.exit(GalleryGlassGrowth.Width),
      ) {
        MotionBadge(
          playing = playback.motionFor == currentId && playback.motionMode == MotionMode.FULL,
          unavailable = controller.motion.state.isUnavailable(currentId),
          onClick = controller::playMotion,
          loading = controller.motion.state.isLoading(currentId),
        )
      }
      // B4: lo stesso vetro del tasto di chiusura (il fondale lo da' la barra). K1: il menu e' il pop-up con il velo (la goccia e' spenta, vedi `GalleryViewerMenuButton`).
      if (menu.isNotEmpty()) GalleryViewerMenuButton(entries = menu, state = menuState)
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
    val confirmMobileData: (() -> Unit)? = when {
      services.onConfirmMobileData == null || currentRef == null -> null
      downloadState is DownloadState.NeedsMobileData && nasRef != null -> ({ controller.confirmMobileDataForDownload(nasRef) })
      else -> ({ controller.confirmMobileData(currentRef) })
    }
    // Le note, come gli avvisi qui sotto, entrano e escono sfumando con i token di FluidMotion (B4): prima comparivano di colpo a ogni cambio di foto o di rete.
    ViewerGlassPresence(
      value = noticeKind,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = if (videoBar != null) NoticeBottomInset + VideoBarExtra else NoticeBottomInset),
    ) { kind ->
      PageNotice(
        kind = kind,
        backdrop = backdrop,
        onConfirmMobileData = confirmMobileData,
        onRetry = if (nasRef != null) controller::retry else null,
        onCancelDownload = if (downloadState is DownloadState.Queued || downloadState is DownloadState.Running) controller::cancelDownload else null,
      )
    }
    // Foto in movimento del NAS (E-V15, D51): i dati mobili da confermare, o "non disponibile ora".
    val motionNotice = controller.motion.state.notice
    if (motionNotice != null && atRest) {
      LaunchedEffect(motionNotice) {
        delay(MessageMillis)
        if (controller.motion.state.notice == motionNotice) controller.motion.state.notice = null
      }
    }
    ViewerGlassPresence(
      value = motionNotice.takeIf { atRest },
      modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = NoticeBottomInset),
    ) { notice ->
      val noticeId = when (notice) {
        is MotionNotice.NeedsMobileData -> notice.id
        is MotionNotice.Unavailable -> notice.id
      }
      MotionNoticeView(
        notice = notice,
        backdrop = backdrop,
        onConfirmMobileData = if (services.onConfirmMobileData != null) ({ controller.confirmMotionMobileData(noticeId) }) else null,
      )
    }
    // Un avviso breve (E-V6, nascosto, copiato) in alto, sotto la barra: uno alla volta (l'avviso, poi la lettura, poi quelli del NAS e del backup), che sfuma in entrata e in uscita (B4).
    val message = state.message
    val topNotice: TopNotice? = when {
      message != null -> TopNotice(key = message) { MessageNotice(message = message, backdrop = backdrop) }
      liveText.state is LiveTextState.ReadingNow && liveText.owner == currentId -> TopNotice(key = ReadingKey) { LiveTextReadingNotice(backdrop = backdrop) }
      nasNotice != null -> TopNotice(key = nasNotice) { NasNoticeView(notice = nasNotice, backdrop = backdrop) }
      backupController != null && (backupShare is BackupShareUi.Preparing || backupShare is BackupShareUi.Ready) ->
        TopNotice(key = backupShare) { BackupShareNotice(share = backupShare, backdrop = backdrop, onCancel = backupController::dismissShare) }
      backupNotice != null -> TopNotice(key = backupNotice) { BackupNoticeView(notice = backupNotice, backdrop = backdrop) }
      else -> null
    }
    ViewerGlassPresence(
      value = topNotice,
      modifier = Modifier
        .align(Alignment.TopCenter)
        .statusBarsPadding()
        .padding(top = MessageTopInset),
    ) { notice -> notice.content() }
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
  // CU-6: "Apri in Mappe" (un'altra app) sta dentro la mini-mappa quando c'e', altrimenti in coda al luogo.
  val onOpenMaps: (() -> Unit)? = if (details?.location != null) controller::askMaps else null
  val placeMapPanel = rememberPlaceMapPanel(services.placeMap, currentId, active = infoVisible, secure = request.secure, onOpenMaps = onOpenMaps)
  val placeOpenMaps = if (placeMapPanel.sections.isEmpty()) onOpenMaps else null
  // Gli album dell'elemento (giro di correzione 1): letti solo a pannello aperto, mai dai Nascosti.
  // CS-22: la capsula chiude il viewer con la sua chiusura (il volo alla cella, o l'allontanarsi dove la cella non c'e') e solo dopo apre l'album.
  val albumsPanel = rememberAlbumsPanel(services.mediaAlbums, currentId, active = infoVisible && !request.secure) { album ->
    val source = services.mediaAlbums
    controller.close(then = { source?.open(album) })
  }
  // H2: cambiando elemento a pannello aperto le informazioni nuove sostituiscono le vecchie con una dissolvenza breve, senza lampi.
  val infoFade = remember { Animatable(1f) }
  LaunchedEffect(currentId) {
    // CG-1: lo scorrimento e la crescita sono dell'elemento: quello nuovo riparte dalla testa (in parallelo alla dissolvenza).
    launch { info.resetScroll(reducedMotion) }
    if (info.progress.value > 0f && !reducedMotion) {
      infoFade.snapTo(0f)
      infoFade.animateTo(1f, FluidMotion.crossFade())
    } else {
      infoFade.snapTo(1f)
    }
  }
  if (infoVisible && currentKind != null) {
    val kind = currentKind
    val onPhone = currentRef is MediaRef.Phone || details?.fileName != null
    val localDay = if (currentIndex >= 0) snapshot.localDayAt(currentIndex) else 0
    val model = remember(current, kind, controller.motionUnplayableVersion, placeName, currentId, localDay, onPhone) {
      buildInfoModel(
        format = format,
        inputs = InfoInputs(
          kind = kind,
          details = details,
          exif = current?.exif,
          place = current?.place,
          placeName = placeName,
          motionUnplayable = currentId in controller.motionUnplayable,
          onPhone = onPhone,
          localDay = localDay,
          exifPending = current?.exif == null,
        ),
      )
    }
    val backupNote = remember(backupState, onPhone, format) { backupState?.let { backupNote(format, it, onPhone) } }
    // La mappa sotto il luogo e i dati, poi le persone, poi le copie (H2).
    // Le facce subito sotto la testa; la mappa, gli album e le copie dopo la scheda dello scatto (giro di correzione 1).
    val extras = remember(placeMapPanel.sections, albumsPanel.sections, similar.sections) {
      placeMapPanel.sections + albumsPanel.sections + similar.sections
    }
    // La tinta della scheda dello scatto: il colore medio del segnaposto della foto, gia' decodificato per la pagina (nessuna lettura in piu').
    val hash = if (currentIndex >= 0) snapshot.thumbHashAt(currentIndex) else null
    val accent = remember(hash, services.placeholders) { hash?.let { averageColor(services.placeholders.image(it)) } }
    val backupPending = services.backup != null && backupState == null
    val infoTitle = stringResource(R.string.viewer_info_title)
    val topLimit = with(density) { WindowInsets.statusBars.getTop(density) + PanelTopReserve.toPx() }
    Box(Modifier.fillMaxSize().testTag(ViewerTags.InfoPanel)) {
      if (sidePanel) {
        InfoSidePanel(
          progress = { info.progress.value },
          width = SidePanelWidth,
          title = infoTitle,
          backdrop = backdrop,
          onClose = { state.infoOpen = false },
          contentAlpha = { infoFade.value },
        ) {
          InfoContent(
            model = model,
            backup = backupNote,
            extras = extras,
            onOpenMaps = placeOpenMaps,
            leading = peoplePanel.sections,
            accent = accent,
            backupPending = backupPending,
          )
        }
      } else {
        InfoSheet(
          state = info,
          stageHeight = controller.stage.height,
          topLimit = topLimit,
          title = infoTitle,
          backdrop = backdrop,
          onDrag = controller::onPanelDrag,
          onDragEnd = controller::onPanelDragEnd,
          onClose = { state.infoOpen = false },
          contentAlpha = { infoFade.value },
        ) {
          InfoContent(
            model = model,
            backup = backupNote,
            extras = extras,
            onOpenMaps = placeOpenMaps,
            leading = peoplePanel.sections,
            accent = accent,
            backupPending = backupPending,
          )
        }
        // Il video continua ad andare e i suoi comandi stanno sopra il bordo del pannello, e salgono con lui (H2).
        val barShown = videoBar != null && atRest && chromeBackdropReady && !controller.cutout.active && (state.chromeVisible || touchExploration)
        if (barShown && videoBar != null) {
          val stageHeight = controller.stage.height
          val gap = with(density) { GalleryViewerChromeDefaults.BarGap.toPx() }
          Column(
            modifier = Modifier
              .align(Alignment.TopCenter)
              .graphicsLayer {
                translationY = info.panelTop(stageHeight) - size.height - gap
                alpha = ((info.progress.value - VideoBarFrom) / (1f - VideoBarFrom)).coerceIn(0f, 1f)
              },
            horizontalAlignment = Alignment.CenterHorizontally,
            content = videoBar,
          )
        }
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
  // L'altra copia di un gruppo che non sta nell'indice: sopra tutto il resto.
  outsideCopy?.let { copyId -> OutsideCopyLayer(id = copyId, services = services, playback = playback, onClose = { outsideCopy = null }) }

  // Il menu ⋮ a goccia (B4): il suo host, per ultimo (sopra tutto il resto, e il suo Indietro vince su quello del pannello informazioni). Con la goccia spenta (K1) non compone
  // niente: il pop-up vive nel `FluidGlassModalHost` dell'app, che ha il suo Indietro.
  GalleryViewerMenuHost(state = menuState, backdrop = backdrop)
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
private const val INFO_CLOSED = 0.02f
private const val INFO_BACK_END = 0.55f
/** Il pannello informazioni del telefono cresce fino a qui sotto la barra di stato: la barra in alto resta raggiungibile (H2). */
private val PanelTopReserve: Dp = 64.dp

/** La barra del video sopra il pannello compare nell'ultima meta' della sua corsa (H2). */
private const val VideoBarFrom = 0.5f

/** Da qui il pannello informazioni e' la fonte di data e luogo e la barra in alto non li ripete (CU-5). */
private const val INFO_SPEAKS = 0.5f

/** A che punto dell'espansione entrano le pagine vicine (vedi `PagerLayer`): lontano dal primo fotogramma, prima della fine. */
private const val NeighborsAtProgress = 0.5f

/** Un avviso in alto: [key] dice quale (cambia l'avviso, non il suo disegno), [content] lo disegna. */
private class TopNotice(val key: Any, val content: @Composable () -> Unit) {
  override fun equals(other: Any?): Boolean = other is TopNotice && other.key == key

  override fun hashCode(): Int = key.hashCode()
}

private val ReadingKey = Any()

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
