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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidGrabber
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
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
internal class InfoSectionModel(val header: String?, val rows: List<GalleryInfoRow>, val content: (@Composable () -> Unit)? = null)

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

  /** Di quanto il pannello e' cresciuto oltre meta' schermo. */
  val lift: Float get() = InfoScroll.lift(offset.value, maxLift)

  /** Quanto scorrono le informazioni dentro il pannello. */
  val inner: Float get() = InfoScroll.inner(offset.value, maxLift)

  /** Il bordo alto del pannello nella scena alta [stageHeight]. */
  fun panelTop(stageHeight: Float): Float = InfoGeometry.panelTop(stageHeight, progress.value, lift)

  /**
   * Il pannello si e' misurato: le informazioni sono alte [contentHeight], meta' schermo e' [travel], e fra meta' schermo e il limite in alto
   * ci sono [liftRoom] pixel.
   */
  fun measure(contentHeight: Float, travel: Float, liftRoom: Float) {
    maxOffset = (contentHeight - travel).coerceAtLeast(0f)
    maxLift = min(maxOffset, liftRoom.coerceAtLeast(0f))
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
    val step = InfoScroll.step(progress.value, offset.value, dy, travel, maxOffset)
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
    if (maxOffset > 0f && offset.value > 0f && !reducedMotion && abs(velocityY) > 0f) {
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
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight(align = Alignment.Top, unbounded = true)
        .onSizeChanged { state.measure(it.height.toFloat(), travel, travel - topLimit) }
        .graphicsLayer {
          translationY = -state.inner
          alpha = contentAlpha()
        },
    ) {
      FluidGrabber()
      Column(
        modifier = Modifier
          .padding(horizontal = GalleryDimens.Gutter)
          .padding(top = ContentTop, bottom = ContentBottom + navigationBottom),
        content = content,
      )
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

/** Il materiale dei fogli dell'engine; senza un fondale (i test, le anteprime) la superficie opaca. */
@Composable
private fun sheetMaterial(backdrop: GlassBackdropState?, shape: androidx.compose.ui.graphics.Shape): Modifier =
  if (backdrop != null) {
    Modifier.glassSurface(
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
 * Le informazioni di un media, compatte (H2): la data grande in testa con l'ora e il luogo sotto; il backup in una capsula; la fotocamera e i
 * dati di scatto in pillole; la mappa, le persone, le copie ([extras]); in fondo, piccoli, il nome del file e la cartella.
 *
 * @param onOpenMaps la posizione in un'altra app ("Apri in Mappe", con la sua conferma); null = niente posizione o nessuna app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InfoContent(
  model: InfoModel,
  backup: GalleryFooterNote?,
  extras: List<InfoSectionModel>,
  onOpenMaps: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BlockGap)) {
    // 1. La testa: quando e dove.
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PillGap)) {
        model.date?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
        }
        if (model.estimated) FluidStatusBadge(label = stringResource(R.string.viewer_estimated_pill), tone = FluidTone.Warning)
      }
      model.timeLine?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
      model.place?.let { place ->
        Row(
          modifier = Modifier.padding(top = PlaceTop),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(IconGap),
        ) {
          Icon(imageVector = Icons.Rounded.Place, contentDescription = null, modifier = Modifier.size(LineIcon), tint = scheme.primary)
          Text(text = place, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
      if (onOpenMaps != null) {
        FluidChip(
          label = stringResource(R.string.viewer_place_open_maps),
          selected = false,
          onClick = onOpenMaps,
          modifier = Modifier.padding(top = ChipTop),
          leading = { Icon(imageVector = Icons.Rounded.Map, contentDescription = null) },
        )
      }
    }

    // 2. Il backup: un'icona e due parole, il perche' solo quando serve.
    if (backup != null) GalleryFooterEntry(note = backup, onClick = null, alignment = Alignment.Start)

    // 3. Lo scatto e il file, in pillole.
    if (model.hasShot || model.file.isNotEmpty() || model.edited) {
      Column(verticalArrangement = Arrangement.spacedBy(PillRowGap)) {
        model.camera?.let { camera ->
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IconGap)) {
            Icon(imageVector = Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(LineIcon), tint = scheme.onSurfaceVariant)
            Text(text = camera, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        if (model.shot.isNotEmpty()) Pills(model.shot)
        if (model.file.isNotEmpty() || model.edited) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(PillGap), verticalArrangement = Arrangement.spacedBy(PillGap)) {
            model.file.forEach { FluidStatusBadge(label = it, tone = FluidTone.Neutral) }
            if (model.edited) FluidStatusBadge(label = stringResource(R.string.viewer_edited_pill), tone = FluidTone.Info)
          }
        }
      }
    }

    // 4. La mappa, le persone, le copie.
    if (extras.isNotEmpty()) InfoSections(extras)

    // 5. Il file, piccolo, in fondo.
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

/** Le sezioni in piu' (la mappa, le persone, le copie) in colonna: un'intestazione discreta e il loro contenuto. */
@Composable
internal fun InfoSections(sections: List<InfoSectionModel>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BlockGap)) {
    sections.forEach { section ->
      val custom = section.content
      if (custom == null) {
        GalleryInfoSection(rows = section.rows, header = section.header)
      } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CustomSectionGap)) {
          section.header?.let { GallerySectionHeader(title = it) }
          custom()
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(pills: List<String>) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(PillGap), verticalArrangement = Arrangement.spacedBy(PillGap)) {
    pills.forEach { FluidStatusBadge(label = it, tone = FluidTone.Neutral) }
  }
}

private val BlockGap = 20.dp
private val LineGap = 2.dp
private val PlaceTop = 6.dp
private val ChipTop = 8.dp
private val IconGap = 6.dp
private val LineIcon = 18.dp
private val PillGap = 6.dp
private val PillRowGap = 10.dp
private val CustomSectionGap = 8.dp
private val ContentTop = 4.dp
private val ContentBottom = 24.dp
private val PanelHeaderTop = 12.dp
private const val InvisibleBelow = 0.001f
