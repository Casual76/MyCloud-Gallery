package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidSkeleton
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGrabber
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterEntry
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoHeader
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoRow
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoSection
import dev.pampa.mycloudgallery.feature.viewer.R
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Una sezione in piu' del pannello informazioni (la mappa, le persone, le copie simili): un'intestazione e le sue righe, o un [content] proprio
 * (i volti, la mini-mappa) sotto la stessa intestazione.
 */
internal class InfoSectionModel(
  val header: String?,
  val rows: List<GalleryInfoRow>,
  val content: (@Composable () -> Unit)? = null,
  /** CU-8: il segno accanto al titolo, come la fotocamera: niente intestazioni in maiuscolo. */
  val icon: ImageVector? = null,
)

/**
 * La posizione del pannello informazioni (H2): [progress] va da 0 (chiuso) a 1 (aperto, il bordo a meta' schermo) e [offset] e' quanto si e'
 * scorso un pannello lungo oltre la meta' (prima il pannello cresce e porta con se' il contenuto, [lift]; poi le informazioni scorrono dentro,
 * [inner]). Il dito lo guida da qualunque parte parta (la foto, il pannello stesso) con [drag]; al rilascio [endDrag] decide (apre, chiude o
 * lascia scorrere).
 *
 * **Un solo orologio**, come l'espansione: il pannello, il contenuto che sale con lui e il chrome che si toglie leggono tutti questi stati, letti
 * nel disegno: un fotogramma di gesto non ricompone niente.
 */
@Stable
internal class InfoPanelState(initialOpen: Boolean) {

  val progress: Animatable<Float, AnimationVector1D> = Animatable(if (initialOpen) 1f else 0f)

  /** Lo scorrimento di un pannello lungo (0 = in cima). */
  val offset: Animatable<Float, AnimationVector1D> = Animatable(0f)

  /** Un dito sta guidando il pannello. */
  var dragging: Boolean by mutableStateOf(false)
    private set

  /** Quanto si puo' scorrere: le informazioni oltre meta' schermo. Lo scrive il pannello quando si misura. */
  var maxOffset: Float by mutableFloatStateOf(0f)
    private set

  /** Quanto il pannello puo' crescere oltre la meta' prima che le informazioni scorrano dentro (fino alla barra in alto). */
  var maxLift: Float by mutableFloatStateOf(0f)
    private set

  private var startedOpen: Boolean = initialOpen

  /**
   * Lo scorrimento **valido** adesso: quello di [offset] dentro i limiti delle informazioni di adesso (CG-1). Le informazioni cambiano altezza
   * (un altro elemento, l'EXIF che arriva dopo): uno scorrimento rimasto da informazioni piu' lunghe non sposta mai quelle nuove fuori dal
   * pannello.
   */
  val scroll: Float get() = offset.value.coerceIn(0f, maxOffset)

  /** Di quanto il pannello e' cresciuto oltre meta' schermo. */
  val lift: Float get() = InfoScroll.lift(scroll, maxLift)

  /** Quanto scorrono le informazioni dentro il pannello. */
  val inner: Float get() = InfoScroll.inner(scroll, maxLift)

  /** Il bordo alto del pannello nella scena alta [stageHeight]. */
  fun panelTop(stageHeight: Float): Float = InfoGeometry.panelTop(stageHeight, progress.value, lift)

  /**
   * Il pannello si e' misurato: le informazioni sono alte [contentHeight], meta' schermo e' [travel], e fra meta' schermo e il limite in alto
   * ci sono [liftRoom] pixel.
   */
  fun measure(contentHeight: Float, travel: Float, liftRoom: Float) {
    maxOffset = (contentHeight - travel).coerceAtLeast(0f)
    maxLift = min(maxOffset, liftRoom.coerceAtLeast(0f))
    // I limiti dell'inerzia seguono le informazioni di adesso: quelli di un pannello di prima (piu' corto) taglierebbero lo scorrimento di uno
    // piu' lungo, quelli di uno piu' lungo lo lascerebbero andare oltre la fine.
    offset.updateBounds(0f, maxOffset)
  }

  /**
   * Un altro elemento (CG-1): lo scorrimento e la crescita sono suoi, quindi si riparte dalla testa. L'inerzia di prima si ferma; il pannello
   * torna a meta' schermo con la molla delle superfici (subito con il movimento ridotto). L'apertura non cambia.
   */
  suspend fun resetScroll(reducedMotion: Boolean) {
    offset.stop()
    if (offset.value == 0f) return
    if (reducedMotion || dragging) offset.snapTo(0f) else offset.animateTo(0f, GalleryMotion.settleSpec())
  }

  /** Il dito si sposta: [fraction] e' la frazione della corsa (positiva = si apre). Solo l'apertura, senza scorrimento. */
  suspend fun dragBy(fraction: Float) {
    dragging = true
    progress.snapTo((progress.value + fraction).coerceIn(0f, 1f))
  }

  /**
   * Un passo del dito di [dy] pixel (positivo = verso il basso) con la corsa [travel] (meta' schermo): verso l'alto prima si apre, poi scorre
   * (il pannello cresce e porta il contenuto, poi le informazioni scorrono); verso il basso prima torna in cima, poi si chiude ([InfoScroll]).
   */
  suspend fun drag(dy: Float, travel: Float) {
    if (!dragging) {
      dragging = true
      startedOpen = progress.value >= OpenAt
      offset.stop()
    }
    val step = InfoScroll.step(progress.value, scroll, dy, travel, maxOffset)
    progress.snapTo(step.progress)
    offset.snapTo(step.offset)
  }

  /**
   * Il dito si alza con [velocityY] pixel al secondo (positiva = verso il basso). A meta' fra chiuso e aperto decide come [release] (un lancio
   * deciso vince, altrimenti un terzo di corsa); aperto, un pannello lungo continua a scorrere con la velocita' del dito ([decay]). Restituisce
   * se e' aperto.
   *
   * [onDecided] riceve la decisione **subito**, prima della molla: chi guarda lo stato (l'arbitro dei gesti, il tocco sulla foto) sa gia' se il
   * pannello e' aperto mentre ci sta arrivando, e un gesto fatto in quei 300 ms va al pannello e non al viewer.
   */
  suspend fun endDrag(
    velocityY: Float,
    travel: Float,
    reducedMotion: Boolean,
    decay: DecayAnimationSpec<Float>,
    onDecided: (Boolean) -> Unit = {},
  ): Boolean {
    dragging = false
    if (progress.value < OpenAt) {
      val bias = if (startedOpen) CloseBias else OpenBias
      return release(if (travel > 0f) -velocityY / travel else 0f, reducedMotion, bias, onDecided)
    }
    onDecided(true)
    if (maxOffset > 0f && scroll > 0f && !reducedMotion && abs(velocityY) > 0f) {
      offset.updateBounds(0f, maxOffset)
      offset.animateDecay(-velocityY, decay)
    }
    return true
  }

  /**
   * Il dito si alza. [velocityFraction] e' la velocita' in frazioni della corsa al secondo (positiva = si apre). Un lancio deciso vince sulla
   * posizione; altrimenti si apre se il pannello e' oltre [openBias].
   *
   * Il punto di svolta non e' la meta' per tutti: chi **apre** dal basso con uno swipe in su ha gia' deciso e il pannello si apre dopo un terzo
   * di corsa ([OpenBias]); chi **chiude** da aperto lo chiude dopo un terzo di corsa ([CloseBias]). Restituisce se e' aperto; [onDecided] lo
   * sa prima della molla.
   */
  suspend fun release(velocityFraction: Float, reducedMotion: Boolean, openBias: Float = HalfWay, onDecided: (Boolean) -> Unit = {}): Boolean {
    val open = when {
      abs(velocityFraction) > FlingFractionPerSecond -> velocityFraction > 0f
      else -> progress.value > openBias
    }
    onDecided(open)
    settle(open, reducedMotion)
    return open
  }

  /** Porta il pannello aperto o chiuso con la molla delle superfici grandi; chiudendo torna anche in cima. */
  suspend fun settle(open: Boolean, reducedMotion: Boolean) {
    dragging = false
    val target = if (open) 1f else 0f
    if (reducedMotion) {
      progress.snapTo(target)
      if (!open) offset.snapTo(0f)
      return
    }
    coroutineScope {
      if (!open && offset.value > 0f) launch { offset.animateTo(0f, GalleryMotion.settleSpec()) }
      progress.animateTo(target, GalleryMotion.settleSpec())
    }
  }

  companion object {
    private const val HalfWay = 0.5f

    /** Da qui in su il pannello e' aperto (un pannello lungo scorre invece di chiudersi). */
    private const val OpenAt = 0.999f

    /** Oltre questa frazione di corsa, uno swipe in su apre il pannello anche senza lancio. */
    const val OpenBias = 0.28f

    /** Sotto questa frazione, uno swipe in giu' dal pannello aperto lo chiude anche senza lancio. */
    const val CloseBias = 0.72f

    /** Quanto veloce deve essere un lancio (frazioni della corsa al secondo, circa 700 px/s sul telefono) per decidere da solo. */
    private const val FlingFractionPerSecond = 0.6f
  }
}

/**
 * Il pannello informazioni del telefono (H2): sale dal basso e, aperto, e' alto **esattamente meta' schermo** ([InfoGeometry]). Si trascina
 * dovunque (anche dalla foto: vedi `ViewerGestureArbiter`); se le informazioni sono di piu', scorrendo cresce oltre la meta' fino a [topLimit]
 * e poi le informazioni scorrono dentro.
 *
 * Il pannello ha **la sua misura piena** (dalla barra in alto al fondo dello schermo) e **si trasla**: niente cambia misura durante un gesto,
 * nemmeno il vetro (lezione P1/P2: un nodo di vetro che cambia misura rialloca i suoi layer a ogni fotogramma). Il materiale e' quello dei fogli
 * dell'engine (`GlassDefaults.panelTint`, `panelOptics`, ruolo `Modal`, angoli `FluidRadius.Sheet`, la maniglia `FluidGrabber`): aperto sta
 * sopra lo sfondo del viewer, non sopra la foto (la foto sale e lascia il posto), quindi e' un piano su cui si legge; mentre sale lascia
 * intravedere la foto che scorre sotto.
 */
@Composable
internal fun BoxScope.InfoSheet(
  state: InfoPanelState,
  stageHeight: Float,
  topLimit: Float,
  title: String,
  backdrop: GlassBackdropState?,
  onDrag: (dy: Float) -> Unit,
  onDragEnd: (velocityY: Float) -> Unit,
  onClose: () -> Unit,
  contentAlpha: () -> Float,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val density = LocalDensity.current
  val travel = InfoGeometry.travel(stageHeight)
  val fullHeight = with(density) { (stageHeight - topLimit).coerceAtLeast(0f).toDp() }
  val shape = ContinuousCornerShape(topStart = FluidRadius.Sheet, topEnd = FluidRadius.Sheet)
  val closeLabel = stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.info_close)
  val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  Box(
    modifier = modifier
      .align(Alignment.TopStart)
      .fillMaxWidth()
      .height(fullHeight)
      .graphicsLayer {
        // Il pannello sta sotto la barra in alto ([topLimit]) e si sposta di quanto serve: chiuso e' fuori dallo schermo.
        translationY = state.panelTop(stageHeight)
        alpha = if (state.progress.value <= InvisibleBelow) 0f else 1f
      }
      .then(sheetMaterial(backdrop, shape))
      .clip(shape)
      .draggable(
        state = rememberDraggableState { onDrag(it) },
        orientation = Orientation.Vertical,
        onDragStopped = { onDragEnd(it) },
      )
      .semantics {
        paneTitle = title
        customActions = listOf(CustomAccessibilityAction(closeLabel) { onClose(); true })
      },
  ) {
    // CU-8: la maniglia sta ferma in cima; le informazioni scorrono sotto di lei, ritagliate e sfumate per [ScrollFade] (la sfumatura c'e' solo
    // quando sono scorse: a riposo nessun layer in piu').
    val grabber = remember { IntArray(1) }
    val fade = MaterialTheme.colorScheme.surface
    Column(modifier = Modifier.fillMaxSize()) {
      FluidGrabber(modifier = Modifier.onSizeChanged { grabber[0] = it.height })
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .clipToBounds()
          .graphicsLayer { compositingStrategy = if (state.inner > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
          .drawWithContent {
            drawContent()
            if (state.inner > 0f) {
              val edge = (ScrollFade.toPx() / size.height).coerceIn(0f, 1f)
              drawRect(
                brush = Brush.verticalGradient(0f to fade.copy(alpha = 0f), edge to fade, 1f to fade),
                blendMode = BlendMode.DstIn,
              )
            }
          },
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .onSizeChanged { state.measure((it.height + grabber[0]).toFloat(), travel, travel - topLimit) }
            .graphicsLayer {
              translationY = -state.inner
              alpha = contentAlpha()
            }
            .padding(horizontal = GalleryDimens.Gutter)
            .padding(top = ContentTop, bottom = ContentBottom + navigationBottom),
          content = content,
        )
      }
    }
  }
}

/**
 * La colonna informazioni (da 840 dp e nelle finestre basse e larghe, [InfoGeometry.side]): a destra, a tutta altezza, con la foto che le fa
 * posto ([InfoGeometry.sideLayer]). Lo stesso materiale e lo stesso contenuto del pannello del telefono; si apre e si chiude con il tasto
 * Informazioni o con la sua X. Il tocco sulla foto accanto resta delle barre (la foto e' intera e si guarda come sempre).
 */
@Composable
internal fun BoxScope.InfoSidePanel(
  progress: () -> Float,
  width: Dp,
  title: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  backdrop: GlassBackdropState? = null,
  contentAlpha: () -> Float = { 1f },
  content: @Composable ColumnScope.() -> Unit,
) {
  val density = LocalDensity.current
  val widthPx = with(density) { width.toPx() }
  val shape = ContinuousCornerShape(topStart = FluidRadius.Sheet, bottomStart = FluidRadius.Sheet)
  Column(
    modifier = modifier
      .align(Alignment.CenterEnd)
      .fillMaxHeight()
      .width(width)
      .graphicsLayer {
        val p = progress()
        translationX = (1f - p) * widthPx
        alpha = if (p <= InvisibleBelow) 0f else 1f
      }
      .then(sheetMaterial(backdrop, shape))
      .clip(shape)
      .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
      .semantics { paneTitle = title },
  ) {
    GalleryInfoHeader(title = title, onClose = onClose, showGrabber = false, modifier = Modifier.padding(top = PanelHeaderTop))
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .graphicsLayer { alpha = contentAlpha() }
        .padding(horizontal = GalleryDimens.Gutter)
        .padding(top = ContentTop, bottom = ContentBottom)
        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
      content = content,
    )
  }
}

/** Il materiale dei fogli dell'engine, dal vetro della galleria (superficie piena se il sistema la chiede); senza un fondale (i test, le anteprime) la superficie opaca. */
@Composable
private fun sheetMaterial(backdrop: GlassBackdropState?, shape: androidx.compose.ui.graphics.Shape): Modifier =
  if (backdrop != null) {
    Modifier.galleryGlass(
      state = backdrop,
      tint = GlassDefaults.panelTint(),
      shape = shape,
      role = GlassRole.Modal,
      optics = GlassDefaults.panelOptics(),
    )
  } else {
    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
  }

/**
 * Le informazioni di un media (H2; giro di correzione 1: un pannello **disegnato**, non una pila di pillole). In testa **la data, unico
 * titolo**, l'ora e il luogo; il backup in una capsula (l'unico segno colorato forte); le facce delle persone ([leading]); **una sola scheda
 * dello scatto** con la striscia dei valori, tinta appena del colore della foto ([accent]); la mini-mappa, gli album, le copie ([extras]); in
 * fondo, piccoli, il nome del file e la cartella.
 *
 * @param onOpenMaps la posizione in un'altra app ("Apri in Mappe", con la sua conferma), in coda al luogo; null = niente posizione, o c'e' la
 *   mini-mappa (che la porta lei).
 * @param accent il colore medio della foto (dal suo segnaposto, gia' in memoria): tinge la scheda dello scatto; null = il tema.
 * @param backupPending la fonte del backup c'e' ma non ha ancora risposto: al posto della capsula un segnaposto della stessa misura.
 */
@Composable
internal fun InfoContent(
  model: InfoModel,
  backup: GalleryFooterNote?,
  extras: List<InfoSectionModel>,
  onOpenMaps: (() -> Unit)?,
  modifier: Modifier = Modifier,
  leading: List<InfoSectionModel> = emptyList(),
  accent: Color? = null,
  backupPending: Boolean = false,
) {
  val scheme = MaterialTheme.colorScheme
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BlockGap)) {
    // 1. La testa: quando e dove. La data e' l'unico titolo del pannello.
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PillGap)) {
        model.date?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
          )
        }
        if (model.estimated) FluidStatusBadge(label = stringResource(R.string.viewer_estimated_pill), tone = FluidTone.Warning)
      }
      model.timeLine?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant) }
      model.place?.let { place ->
        Row(
          modifier = Modifier.padding(top = PlaceTop),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(IconGap),
        ) {
          Icon(imageVector = Icons.Rounded.Place, contentDescription = null, modifier = Modifier.size(LineIcon), tint = scheme.primary)
          Text(
            text = place,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          // CU-6: senza la mini-mappa (che e' l'ingresso alla mappa e porta lei questo tasto) l'altra app si apre da qui, in coda al luogo.
          if (onOpenMaps != null) OpenInMapsButton(onOpenMaps)
        }
      }
    }

    // 2. Il backup: un'icona e due parole, il perche' solo quando serve. Finche' la fonte non risponde, il suo posto (niente salti).
    when {
      backup != null -> GalleryFooterEntry(note = backup, onClick = null, alignment = Alignment.Start)
      backupPending -> FluidSkeleton(modifier = Modifier.width(CapsuleSkeletonWidth), height = CapsuleSkeletonHeight, shape = FluidCapsuleShape)
    }

    // 3. Le persone: le facce, subito sotto la testa.
    if (leading.isNotEmpty()) InfoSections(leading)

    // 4. Lo scatto: una scheda sola.
    if (model.hasCard) ShotCard(model, accent)

    // 5. La mappa, gli album, le copie.
    if (extras.isNotEmpty()) InfoSections(extras)

    // 6. Il file, piccolo, in fondo.
    val fileLine = when {
      model.fileName != null && model.folder != null -> stringResource(R.string.viewer_info_file_line, model.fileName, model.folder)
      else -> model.fileName ?: model.folder
    }
    if (fileLine != null || model.note != null) {
      Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        model.note?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
        fileLine?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
      }
    }
  }
}

/**
 * La scheda dello scatto: in testa la fotocamera (o il tipo di media) con il formato; sotto l'obiettivo e, in una riga, le misure e il peso; i
 * tratti come capsule; in fondo, dopo un filetto, **la striscia dei valori** (ISO · focale · apertura · tempo), cifre tabellari divise da
 * filetti verticali, come la fanno le gallerie di riferimento. La superficie e' quella delle schede dell'engine, tinta di una parte del
 * colore della foto ([accent]): ogni foto ha la sua scheda, e il testo resta sul suo contrasto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShotCard(model: InfoModel, accent: Color?) {
  val scheme = MaterialTheme.colorScheme
  val base = scheme.surfaceContainerLow
  val surface = if (accent != null) lerp(base, accent, AccentShare) else base
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(surface)
      .padding(horizontal = CardPadding, vertical = CardPaddingVertical),
    verticalArrangement = Arrangement.spacedBy(CardLineGap),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IconGap)) {
      Icon(
        imageVector = if (model.video) Icons.Rounded.Videocam else Icons.Rounded.CameraAlt,
        contentDescription = null,
        modifier = Modifier.size(LineIcon),
        tint = scheme.primary,
      )
      Box(modifier = Modifier.weight(1f)) {
        when {
          model.camera != null -> Text(
            text = model.camera,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          model.shotPending -> FluidSkeleton(modifier = Modifier.fillMaxWidth(CameraSkeletonFraction), height = TitleSkeletonHeight)
          else -> Text(
            text = stringResource(if (model.video) R.string.viewer_info_kind_video else R.string.viewer_info_kind_photo),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
          )
        }
      }
      model.format?.let { FluidStatusBadge(label = it, tone = FluidTone.Neutral) }
    }
    model.lens?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (model.specs.isNotEmpty()) {
      Text(
        text = model.specs.joinToString(SpecSeparator),
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TabularFigures),
        color = scheme.onSurfaceVariant,
      )
    }
    if (model.traits.isNotEmpty() || model.edited) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(PillGap), verticalArrangement = Arrangement.spacedBy(PillGap)) {
        model.traits.forEach { FluidStatusBadge(label = it, tone = FluidTone.Neutral) }
        if (model.edited) FluidStatusBadge(label = stringResource(R.string.viewer_edited_pill), tone = FluidTone.Info)
      }
    }
    if (model.strip.isNotEmpty() || model.shotPending) {
      Box(modifier = Modifier.fillMaxWidth().padding(top = StripTop).height(Hairline).background(scheme.outlineVariant))
      ValueStrip(values = model.strip, pending = model.shotPending)
    }
  }
}

/** La striscia dei valori dello scatto: celle uguali, cifre tabellari, filetti verticali in mezzo. In attesa, segnaposto della stessa misura. */
@Composable
private fun ValueStrip(values: List<String>, pending: Boolean) {
  val scheme = MaterialTheme.colorScheme
  val cells: List<String?> = if (pending) List(PendingCells) { null } else values
  val spoken = values.joinToString(", ")
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(IntrinsicSize.Min)
      .semantics(mergeDescendants = true) { if (!pending) contentDescription = spoken },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    cells.forEachIndexed { i, value ->
      if (i > 0) Box(modifier = Modifier.width(Hairline).fillMaxHeight().padding(vertical = RuleInset).background(scheme.outlineVariant))
      Box(modifier = Modifier.weight(1f).padding(vertical = StripCellPadding), contentAlignment = Alignment.Center) {
        if (value != null) {
          Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TabularFigures),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        } else {
          FluidSkeleton(modifier = Modifier.width(StripSkeletonWidth))
        }
      }
    }
  }
}

/** Le sezioni in piu' (la mappa, le persone, le copie) in colonna: un'intestazione discreta e il loro contenuto. */
@Composable
internal fun InfoSections(sections: List<InfoSectionModel>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BlockGap)) {
    sections.forEach { section ->
      val custom = section.content
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CustomSectionGap)) {
        section.header?.let { SectionTitle(icon = section.icon, title = it) }
        if (custom == null) GalleryInfoSection(rows = section.rows, header = null) else custom()
      }
    }
  }
}

/** CU-8: il titolo di una sezione del pannello, come la riga della fotocamera: il segno nell'accento e il nome, senza maiuscolo. */
@Composable
private fun SectionTitle(icon: ImageVector?, title: String) {
  Row(
    modifier = Modifier.semantics(mergeDescendants = true) { heading() },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(IconGap),
  ) {
    if (icon != null) Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(LineIcon), tint = MaterialTheme.colorScheme.primary)
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
  }
}

/** "Apri in Mappe" (un'altra app, con la sua conferma): un tasto piccolo con il segno di "fuori dall'app", bersaglio da 48 dp. */
@Composable
private fun OpenInMapsButton(onClick: () -> Unit) {
  val label = stringResource(R.string.viewer_place_open_maps)
  Box(
    modifier = Modifier
      .size(GalleryDimens.MinTouchTarget)
      .fluidPressable(onClick = onClick, role = Role.Button)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(LineIcon),
    )
  }
}

private val BlockGap = 20.dp
private val LineGap = 2.dp
private val PlaceTop = 6.dp
private val IconGap = 6.dp
private val LineIcon = 18.dp
private val PillGap = 6.dp
private val CustomSectionGap = 8.dp
private val ContentTop = 4.dp
private val ContentBottom = 24.dp
private val PanelHeaderTop = 12.dp
private const val InvisibleBelow = 0.001f

/** CU-8: quanto sfumano le informazioni che scorrono sotto la maniglia. */
private val ScrollFade = 12.dp

// La scheda dello scatto (giro di correzione 1 di H2).
/** Quanto del colore della foto entra nella superficie della scheda: abbastanza per sentirlo, poco per non toccare il contrasto. */
private const val AccentShare = 0.14f
private val CardPadding = 16.dp
private val CardPaddingVertical = 14.dp
private val CardLineGap = 4.dp
private val StripTop = 8.dp
private val Hairline = 1.dp
private val RuleInset = 6.dp
private val StripCellPadding = 10.dp
private val StripSkeletonWidth = 40.dp
private const val PendingCells = 4
private const val CameraSkeletonFraction = 0.55f
private val TitleSkeletonHeight = 18.dp
private val CapsuleSkeletonWidth = 140.dp
private val CapsuleSkeletonHeight = 36.dp
private const val SpecSeparator = " · "
private const val TabularFigures = "tnum"
