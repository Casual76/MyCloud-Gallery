package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoHeader
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoRow
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoSection
import kotlin.math.abs

/**
 * Una sezione del pannello informazioni: un'intestazione e le sue righe. Una sezione con un [content] proprio (la riga "Persone" dei volti, Fase
 * 8, P9) disegna quello al posto delle righe, sotto la stessa intestazione.
 */
internal class InfoSectionModel(val header: String?, val rows: List<GalleryInfoRow>, val content: (@Composable () -> Unit)? = null)

/**
 * La posizione del pannello informazioni: `progress` va da 0 (chiuso) a 1 (aperto). Sul telefono il dito lo guida
 * (swipe su dalla foto, trascinamento della maniglia) e al rilascio una molla lo porta dove deve stare; sul tablet lo
 * apre il tasto.
 *
 * **Un solo orologio**, come l'espansione: il foglio, lo spostamento della foto che gli fa posto e il chrome che si
 * toglie leggono tutti [progress], cosi' niente si muove per conto suo.
 */
@Stable
internal class InfoPanelState(initialOpen: Boolean) {

  val progress: Animatable<Float, *> = Animatable(if (initialOpen) 1f else 0f)

  /** Un dito sta guidando il pannello. */
  var dragging: Boolean by mutableStateOf(false)
    private set

  /** Il dito si sposta: [fraction] e' la frazione dell'altezza del foglio (positiva = si apre). */
  suspend fun dragBy(fraction: Float) {
    dragging = true
    progress.snapTo((progress.value + fraction).coerceIn(0f, 1f))
  }

  /**
   * Il dito si alza. [velocityFraction] e' la velocita' in frazioni del foglio al secondo (positiva = si apre). Un lancio
   * deciso vince sulla posizione; altrimenti si apre se il foglio e' oltre [openBias].
   *
   * Il punto di svolta non e' la meta' per tutti: chi **apre** dal basso con uno swipe in su ha gia' deciso e il foglio
   * si apre dopo un terzo di corsa ([OpenBias]); chi **chiude** da aperto lo chiude dopo un terzo di corsa
   * ([CloseBias]). Un dito che si ferma e si alza, senza velocita' (come fa il gesto di una prova con `adb input`),
   * non annulla un gesto chiaro. Restituisce se e' aperto.
   */
  suspend fun release(velocityFraction: Float, reducedMotion: Boolean, openBias: Float = HalfWay): Boolean {
    val open = when {
      abs(velocityFraction) > FlingFractionPerSecond -> velocityFraction > 0f
      else -> progress.value > openBias
    }
    settle(open, reducedMotion)
    return open
  }

  /** Porta il pannello aperto o chiuso con la molla delle superfici grandi. */
  suspend fun settle(open: Boolean, reducedMotion: Boolean) {
    dragging = false
    val target = if (open) 1f else 0f
    if (reducedMotion) progress.snapTo(target) else progress.animateTo(target, GalleryMotion.settleSpec())
  }

  companion object {
    private const val HalfWay = 0.5f

    /** Oltre questa frazione di corsa, uno swipe in su apre il foglio anche senza lancio. */
    const val OpenBias = 0.28f

    /** Sotto questa frazione, uno swipe in giu' dal foglio aperto lo chiude anche senza lancio. */
    const val CloseBias = 0.72f

    /** Quanto veloce deve essere un lancio (frazioni del foglio al secondo, circa 800 px/s sul telefono) per decidere da solo. */
    private const val FlingFractionPerSecond = 0.6f
  }
}

/** Le sezioni come colonna: scorre se il testo e' grande o le righe sono molte. */
@Composable
internal fun InfoSections(sections: List<InfoSectionModel>, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(SectionSpacing),
  ) {
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

/**
 * Il foglio informazioni del telefono: sale dal basso fino a [height] (circa meta' schermo) e la foto gli fa posto
 * spostandosi in alto. La maniglia si trascina; il contenuto scorre.
 *
 * Opaco: la gerarchia di una lista raggruppata di testi non regge un vetro trasparente su una foto qualunque.
 */
@Composable
internal fun BoxScope.InfoSheet(
  progress: () -> Float,
  height: Dp,
  title: String,
  onClose: () -> Unit,
  onHeaderDrag: (deltaPx: Float) -> Unit,
  onHeaderDragEnd: (velocityPx: Float) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  val heightPx = with(density) { height.toPx() }
  val shape = ContinuousCornerShape(topStart = FluidRadius.Sheet, topEnd = FluidRadius.Sheet)
  Column(
    modifier = modifier
      .align(Alignment.BottomCenter)
      .fillMaxWidth()
      .height(height)
      .graphicsLayer {
        val p = progress()
        translationY = (1f - p) * heightPx
        // A pannello chiuso non si disegna: nessun layer, nessun tocco.
        alpha = if (p <= InvisibleBelow) 0f else 1f
      }
      .background(MaterialTheme.colorScheme.surface, shape)
      .semantics { paneTitle = title },
  ) {
    GalleryInfoHeader(
      title = title,
      onClose = onClose,
      modifier = Modifier.draggable(
        state = rememberDraggableState { onHeaderDrag(it) },
        orientation = Orientation.Vertical,
        onDragStopped = { onHeaderDragEnd(it) },
      ),
    )
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = GalleryDimens.Gutter)
        .padding(top = ContentTop, bottom = ContentBottom)
        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
      content = content,
    )
  }
}

/**
 * La colonna informazioni del tablet (da 840 dp): a destra, a tutta altezza, con la foto che le fa posto. Non si
 * trascina: si apre con il tasto e si chiude con il suo.
 */
@Composable
internal fun BoxScope.InfoSidePanel(
  progress: () -> Float,
  width: Dp,
  title: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val density = androidx.compose.ui.platform.LocalDensity.current
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
      .background(MaterialTheme.colorScheme.surface, shape)
      .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
      .semantics { paneTitle = title },
  ) {
    GalleryInfoHeader(title = title, onClose = onClose, showGrabber = false, modifier = Modifier.padding(top = PanelHeaderTop))
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = GalleryDimens.Gutter)
        .padding(top = ContentTop, bottom = ContentBottom)
        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
      content = content,
    )
  }
}

/** Il foglio e' alto circa meta' schermo, ma mai meno di quanto serve a due righe e mai piu' di una colonna comoda. */
internal fun sheetHeight(stageHeight: Dp): Dp = (stageHeight * SheetFraction).coerceIn(SheetMin, SheetMax)

private val SectionSpacing = 18.dp
private val CustomSectionGap = 8.dp
private val ContentTop = 4.dp
private val ContentBottom = 24.dp
private val PanelHeaderTop = 12.dp
private val SheetMin = 320.dp
private val SheetMax = 560.dp
private const val SheetFraction = 0.58f
private const val InvisibleBelow = 0.001f
