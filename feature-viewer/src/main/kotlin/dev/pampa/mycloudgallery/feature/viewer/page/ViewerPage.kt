package dev.pampa.mycloudgallery.feature.viewer.page

import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ZoomSpec
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginalFetch
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.feature.viewer.OriginalProgress
import dev.pampa.mycloudgallery.feature.viewer.PageImage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.ViewerState
import dev.pampa.mycloudgallery.feature.viewer.gesture.holdToPlay
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayer
import dev.pampa.mycloudgallery.feature.viewer.video.collectPlayerState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState

/** Cio' che serve a disegnare una pagina del pager e che non cambia mentre la si guarda. */
@Immutable
internal class PageSpec(
  val id: MediaId,
  /** Dove stanno i byte, o null se adesso non c'e' una copia leggibile (E-P22): la pagina mostra il segnaposto. */
  val ref: MediaRef?,
  val kind: MediaKind,
  /** Larghezza/altezza dall'indice, gia' orientata; 0 se non nota. */
  val aspect: Float,
  val description: String,
  val thumbHash: ThumbHash?,
  val thumbKey: ThumbKey,
)

/**
 * Una pagina del viewer: la foto, il video, la motion photo o l'immagine animata di un elemento.
 *
 * **Come arriva l'immagine, senza salti** (E-V1): la bitmap della miniatura (`peek`, la stessa della cella) o il
 * segnaposto sfocato sono gia' li' al primo fotogramma; poi l'anteprima della sorgente; poi la versione nitida alla
 * taglia dello schermo; poi, per la sola pagina corrente e solo dove i tasselli sono possibili, i tasselli
 * (telephoto) che ricevono la nitida come anteprima. Tutte e quattro occupano **lo stesso rettangolo** (la proporzione
 * e' una sola): chi sostituisce chi non sposta niente.
 *
 * **Carica solo se serve** ([loadActive]): la pagina corrente e le due vicine. Una pagina che scorre via in un fling
 * non avvia nessuna decodifica, e una che esce dalla finestra annulla la sua (E-V5).
 *
 * @param isCurrent la pagina assestata: l'unica con i gesti dello zoom, i tasselli, il video e la pressione lunga.
 */
@Composable
internal fun ViewerPage(
  spec: PageSpec,
  screen: IntSize,
  loadActive: Boolean,
  isCurrent: Boolean,
  state: ViewerState,
  services: ViewerServices,
  playback: ViewerPlayback,
  onTap: () -> Unit,
  onMotionHold: () -> Unit,
  onMotionRelease: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val id = spec.id
  val image = state.images[id] ?: PageImage()
  // Fase 4: "Riprova" e l'arrivo dell'originale del NAS rifanno il caricamento (la nitida dall'originale, HDR compreso).
  val retry = state.retries[id] ?: 0
  val originalReady = state.originalReady[id] ?: 0

  LaunchedEffect(id, spec.ref, screen, loadActive, retry, originalReady) {
    val ref = spec.ref
    if (ref == null || !loadActive || screen.width <= 0 || screen.height <= 0) return@LaunchedEffect
    state.loadGate.withPermit {
      services.images.state(ref, screen).collect { next ->
        state.images[id] = (state.images[id] ?: PageImage()).reduce(next)
      }
    }
  }

  // Un media del NAS la cui nitida viene dall'HQ della replica: se l'utente ingrandisce si legge l'originale, con
  // l'avanzamento nella nota (D19). Se e' gia' sul telefono (o la nitida viene gia' da lui) non si legge niente.
  val originals = services.originals
  val nasRef = spec.ref as? MediaRef.Nas
  val wantsOriginal = originals != null && nasRef != null && isCurrent && image.sharp != null && spec.kind != MediaKind.VIDEO
  val peeked = remember(spec.thumbKey, services.thumbnails) { services.thumbnails.peek(spec.thumbKey) }
  val placeholder = remember(spec.thumbHash, services.placeholders) { spec.thumbHash?.let { services.placeholders.image(it) } }
  val best = image.best ?: peeked
  val facts = rememberPageFacts(spec, services, nasKey = if (nasRef != null) (if (image.sharp != null) 1 else 0) + 2 * originalReady else 0)
  val zoomSpec = facts?.zoom
  val animated = facts?.animated

  // La dimensione dell'originale decide i limiti dello zoom; la proporzione e' una sola per tutte le bitmap della pagina.
  val contentSize = when {
    zoomSpec != null -> Size(zoomSpec.width.toFloat(), zoomSpec.height.toFloat())
    best != null -> Size(best.width.toFloat(), best.height.toFloat())
    spec.aspect > 0f -> Size(spec.aspect * REFERENCE_SIDE, REFERENCE_SIDE)
    else -> Size.Zero
  }
  val drawAspect = when {
    contentSize.width > 0f && contentSize.height > 0f -> contentSize.width / contentSize.height
    else -> spec.aspect
  }
  val zoom = rememberZoomableMediaState(contentSize, screen)

  // Solo per un media del NAS con la nitida gia' arrivata: le pagine del telefono non pagano nemmeno questo effetto.
  // La chiave non comprende `originalReady`: a originale arrivato l'effetto finisce, non si rilancia (se il file sparisse dal
  // disco, un nuovo "Riprova" o un nuovo ingresso nella pagina lo chiedono di nuovo; mai un giro continuo di letture).
  if (wantsOriginal && originals != null && nasRef != null) LaunchedEffect(nasRef, retry) {
    if (withContext(services.dispatchers.io) { originals.localFile(nasRef) } != null) return@LaunchedEffect
    snapshotFlow { zoom.isZoomed }.first { it }
    originals.fetch(nasRef).collect { change ->
      when (change) {
        is OriginalFetch.Reading -> state.originals[id] = OriginalProgress.Reading(change.progress)
        is OriginalFetch.Ready -> {
          state.originals.remove(id)
          state.originalReady[id] = (state.originalReady[id] ?: 0) + 1
        }
        is OriginalFetch.Blocked -> state.originals[id] = OriginalProgress.Blocked(change.reason, change.sizeBytes)
        OriginalFetch.Missing -> state.originals[id] = OriginalProgress.Failed(ImageFailure.NOT_FOUND)
        is OriginalFetch.Failed -> state.originals[id] = OriginalProgress.Failed(change.code)
      }
    }
  }

  // Una pagina che smette di essere la corrente torna interamente nello schermo, senza che si veda.
  LaunchedEffect(isCurrent) {
    if (isCurrent) {
      state.zoomReset = { zoom.reset(animated = true) }
      snapshotFlow { zoom.isZoomed }.collect { state.zoomed = it }
    } else {
      zoom.reset(animated = false)
    }
  }
  DisposableEffect(isCurrent) {
    onDispose {
      if (isCurrent) {
        state.zoomReset = null
        state.zoomed = false
      }
    }
  }

  // La motion photo tenuta premuta: il video sta SOTTO la foto, che si toglie solo al primo fotogramma (niente lampo nero).
  val motionPlayer = playback.player
  val motionActive = spec.kind == MediaKind.MOTION_PHOTO && playback.motionFor == spec.id && motionPlayer != null
  val motionUi = playback.collectPlayerState()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val imageAlpha by animateFloatAsState(
    targetValue = if (motionActive && motionUi.firstFrame) 0f else 1f,
    animationSpec = if (reducedMotion || !motionActive) androidx.compose.animation.core.snap() else FluidMotion.instant(),
    label = "motion photo",
  )

  val motionEnabled = isCurrent && spec.kind == MediaKind.MOTION_PHOTO
  Box(
    modifier = modifier
      .fillMaxSize()
      .semantics { contentDescription = spec.description }
      .holdToPlay(
        enabled = { motionEnabled && !zoom.isZoomed },
        onHold = onMotionHold,
        onRelease = onMotionRelease,
      ),
  ) {
    if (motionActive && motionPlayer != null) {
      VideoSurface(player = motionPlayer, aspect = motionUi.aspect ?: drawAspect)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha }) {
    when {
      spec.kind == MediaKind.VIDEO -> VideoContent(
        spec = spec,
        poster = best ?: placeholder,
        drawAspect = drawAspect,
        isCurrent = isCurrent,
        playback = playback,
        onTap = onTap,
      )
      animated != null && spec.ref != null -> ZoomableMedia(
        state = zoom,
        contentSize = Size(animated.width.toFloat(), animated.height.toFloat()),
        gesturesEnabled = isCurrent,
        onTap = onTap,
      ) {
        AnimatedMedia(
          ref = spec.ref!!,
          loader = services.animated,
          fallback = best,
          aspect = animated.width.toFloat() / animated.height,
          active = isCurrent,
        )
      }
      best != null || placeholder != null -> {
        // I tasselli leggono dal MediaStore (telefono) o, per un media del NAS, dal temporaneo dell'originale (D19).
        val phoneUri = remember(spec.ref, services.intents, facts?.originalUri) {
          (spec.ref as? MediaRef.Phone)?.let { services.intents?.uriOf(it) } ?: facts?.originalUri
        }
        val sharp = image.sharp
        // Se i tasselli non si leggono (file rovinato, formato che BitmapRegionDecoder non regge) resta la nitida.
        var tilesFailed by remember(spec.ref) { mutableStateOf(false) }
        val useTiles = isCurrent && zoomSpec?.tiled == true && phoneUri != null && sharp != null && !tilesFailed
        ZoomableMedia(
          state = zoom,
          contentSize = contentSize,
          gesturesEnabled = isCurrent,
          onTap = onTap,
          locationKey = useTiles,
        ) {
          if (useTiles && phoneUri != null && sharp != null) {
            TiledMedia(uri = phoneUri, preview = sharp, zoom = zoom, onFailed = { tilesFailed = true })
          } else {
            FitBitmap(
              bitmap = best ?: placeholder!!,
              aspect = drawAspect,
              quality = if (best != null) FilterQuality.High else FilterQuality.Low,
            )
          }
        }
      }
      else -> Box(Modifier.fillMaxSize())
    }
    }
  }
}

/** Cio' che si sa dell'originale di una pagina: le misure per lo zoom (e i tasselli) e, per GIF e WebP, l'animazione. */
@Immutable
private class PageFacts(val zoom: ZoomSpec?, val animated: AnimatedSpec?, val originalUri: android.net.Uri? = null)

/**
 * Le misure dell'originale, lette **fuori dal thread principale**: `zoomSpec` e `animated` della sorgente aprono il file
 * e ne leggono la testa (un `openFileDescriptor` del provider e un `ImageDecoder` sull'intestazione) quando non le hanno
 * gia' in cache. Lette nella composizione, come prima, costavano dai 5 ai 10 ms per pagina nel primo fotogramma
 * dell'espansione, con tre pagine composte insieme (la corrente e le vicine): il fotogramma da 48 ms che sull'S25 si
 * vedeva come uno scatto all'apertura (13/10).
 *
 * Fino a che non arrivano la pagina usa la proporzione dell'indice e la bitmap della miniatura, che hanno la stessa
 * forma: niente si sposta quando arrivano (cambiano solo il limite dello zoom e la scelta dei tasselli).
 */
@Composable
private fun rememberPageFacts(spec: PageSpec, services: ViewerServices, nasKey: Int = 0): PageFacts? {
  val ref = spec.ref ?: return null
  var facts by remember(ref, spec.kind, services.images) { mutableStateOf<PageFacts?>(null) }
  // [nasKey] cambia per un media del NAS quando arriva la nitida (la posizione e' risolta: ora si sanno le misure del
  // catalogo) e quando arriva l'originale (misure vere, tasselli): le misure si rifanno. Per il telefono e' sempre 0.
  LaunchedEffect(ref, spec.kind, services.images, nasKey) {
    facts = withContext(services.dispatchers.io) {
      PageFacts(
        zoom = readOrNull { services.images.zoomSpec(ref) },
        animated = if (spec.kind != MediaKind.VIDEO) readOrNull { services.images.animated(ref) } else null,
        originalUri = if (ref is MediaRef.Nas) readOrNull { services.originals?.localFile(ref)?.let(android.net.Uri::fromFile) } else null,
      )
    }
  }
  return facts
}

/** Una lettura della testa del file che non riesce vale "non si sa": la pagina resta sulla bitmap, senza zoom a tasselli. */
private inline fun <T> readOrNull(read: () -> T?): T? = try {
  read()
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (_: Exception) {
  null
}

/** I tasselli di telephoto sopra la bitmap nitida, che fa da anteprima finche' i tasselli non arrivano. */
@Composable
private fun TiledMedia(
  uri: android.net.Uri,
  preview: ImageBitmap,
  zoom: ZoomableMediaState,
  onFailed: () -> Unit,
) {
  val source = remember(uri, preview) { SubSamplingImageSource.contentUri(uri, preview) }
  DisposableEffect(source) { onDispose { source.close() } }
  val latestOnFailed by rememberUpdatedState(onFailed)
  val reporter = remember {
    object : SubSamplingImageErrorReporter {
      override fun onImageLoadingFailed(e: java.io.IOException, imageSource: SubSamplingImageSource) {
        latestOnFailed()
      }
    }
  }
  val tiles = rememberSubSamplingImageState(imageSource = source, zoomableState = zoom.zoomable, errorReporter = reporter)
  SubSamplingImage(state = tiles, contentDescription = null, modifier = Modifier.fillMaxSize())
}

/**
 * Un GIF o un WebP animato (E-M11): il `Drawable` di `ImageDecoder` si anima da solo e si disegna nel rettangolo della
 * foto. Nella griglia restano fermi; qui partono quando la pagina e' la corrente, e si fermano quando non lo e' piu'.
 * Fino a che il decoder non ha finito si vede la bitmap [fallback] (la miniatura): niente pagina vuota.
 */
@Composable
private fun AnimatedMedia(
  ref: MediaRef,
  loader: dev.pampa.mycloudgallery.feature.viewer.AnimatedDrawableLoader?,
  fallback: ImageBitmap?,
  aspect: Float,
  active: Boolean,
) {
  var drawable by remember(ref) { mutableStateOf<Drawable?>(null) }
  LaunchedEffect(ref, loader, active) {
    if (active && loader != null && drawable == null) drawable = loader.load(ref)
  }
  val current = drawable
  if (current == null) {
    if (fallback != null) FitBitmap(bitmap = fallback, aspect = aspect)
    return
  }
  val tick = remember(current) { mutableIntStateOf(0) }
  DisposableEffect(current, active) {
    val handler = Handler(Looper.getMainLooper())
    val callback = object : Drawable.Callback {
      override fun invalidateDrawable(who: Drawable) {
        tick.intValue++
      }

      override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        handler.postAtTime(what, who, `when`)
      }

      override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        handler.removeCallbacks(what, who)
      }
    }
    current.callback = callback
    if (current is AnimatedImageDrawable) current.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
    val animatable = current as? Animatable
    if (active) animatable?.start() else animatable?.stop()
    onDispose {
      animatable?.stop()
      current.callback = null
      handler.removeCallbacksAndMessages(null)
    }
  }
  Canvas(modifier = Modifier.fillMaxSize()) {
    tick.intValue // si ridisegna a ogni fotogramma dell'animazione
    val target = fitRect(size, aspect)
    current.setBounds(0, 0, target.width.toInt().coerceAtLeast(1), target.height.toInt().coerceAtLeast(1))
    drawIntoCanvas { canvas ->
      val native = canvas.nativeCanvas
      native.save()
      native.translate(target.left, target.top)
      current.draw(native)
      native.restore()
    }
  }
}

/** Il video della pagina corrente: la superficie e il suo primo fotogramma, con la miniatura sopra finche' non arriva. */
@Composable
private fun VideoContent(
  spec: PageSpec,
  poster: ImageBitmap?,
  drawAspect: Float,
  isCurrent: Boolean,
  playback: ViewerPlayback,
  onTap: () -> Unit,
) {
  val ui = playback.collectPlayerState()
  val player = playback.player
  val showSurface = isCurrent && playback.videoFor == spec.id && player != null
  val frameShown = showSurface && ui.firstFrame
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val posterAlpha by animateFloatAsState(
    targetValue = if (frameShown) 0f else 1f,
    animationSpec = if (reducedMotion) androidx.compose.animation.core.snap() else FluidMotion.fadeOut(),
    label = "video poster",
  )
  Box(
    modifier = Modifier
      .fillMaxSize()
      .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) },
  ) {
    if (showSurface && player != null) {
      VideoSurface(player = player, aspect = ui.aspect ?: drawAspect)
    }
    if (poster != null && posterAlpha > 0f) {
      Box(Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha }) {
        FitBitmap(bitmap = poster, aspect = drawAspect)
      }
    }
    if (!frameShown) PlayBadge(modifier = Modifier.align(Alignment.Center))
  }
}

/** La superficie del video: una `SurfaceView` (D7, per l'HDR), nel rettangolo del video, al centro. */
@Composable
internal fun VideoSurface(player: ViewerPlayer, aspect: Float, modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    AndroidView(
      factory = { context -> SurfaceView(context) },
      update = { player.bind(it) },
      onRelease = { player.bind(null) },
      modifier = Modifier.aspectRatio(if (aspect > 0f) aspect else DEFAULT_VIDEO_ASPECT),
    )
  }
}

/** Il segno "e' un video": un cerchio scuro con il triangolo, al centro. Non e' un tasto: il video parte da solo. */
@Composable
private fun PlayBadge(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .size(BadgeSize)
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Rounded.PlayArrow,
      contentDescription = null,
      modifier = Modifier.size(BadgeGlyph),
      tint = GalleryOnPhoto.Content,
    )
  }
}

private val BadgeSize: Dp = 64.dp
private val BadgeGlyph: Dp = 36.dp
private const val REFERENCE_SIDE = 1000f
private const val DEFAULT_VIDEO_ASPECT = 16f / 9f
