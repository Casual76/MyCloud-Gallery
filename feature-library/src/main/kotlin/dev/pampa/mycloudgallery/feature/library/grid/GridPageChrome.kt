package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeActionBar
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCapsule
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeLabel
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyle
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyles
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryEdgeScrim
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.design.chromeRowHeight
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.feature.library.R

// La chrome delle pagine a griglia (G2, docs/grafica-progetto.md §12): la direzione C, "vetro adattivo", per la Libreria e
// per le griglie delle Raccolte. Una struttura sola:
//
//  - in alto UNA riga sotto la barra di stato, senza barra: (indietro) | etichetta | capsula dei comandi. L'etichetta
//    mostra la sezione che sta sotto la riga (e' l'intestazione agganciata: nessuna seconda fascia di vetro). Dietro, la
//    sfumatura del colore della pagina (lo "scroll edge effect"), dentro il fondale: il vetro la vede;
//  - ogni pezzo sceglie da se' pellicola e inchiostro dal contenuto che gli passa sotto (i ThumbHash delle celle, mai i
//    pixel), con l'opacita' minima che tiene 4,5:1 sul caso peggiore;
//  - in basso, in selezione, la barra delle azioni nel posto della pillola, alla stessa altezza; la griglia non si sposta;
//  - un fondale di vetro solo, quello della pagina: lo scrubber e' disegnato fuori, la griglia non ne registra un secondo.

/**
 * Lo stile adattivo di un pezzo di chrome che sta su [bounds] (pixel della griglia): si ricalcola solo quando sotto entra
 * o esce una riga ([rowsUnder]), dai ThumbHash delle celle ([contentUnder]) composti con il velo [veil] (la sfumatura di
 * bordo alla quota del bordo basso del pezzo); inchiostro e pellicola cambiano con la molla standard (subito, con le
 * animazioni ridotte). Senza griglia ([grid] nullo: una pagina senza miniature con ThumbHash) vale il caso peggiore.
 */
@Composable
internal fun rememberAdaptiveChromeStyle(grid: MediaGridState?, bounds: Rect?, veil: Color?): GalleryChromeStyle {
  val palette = GalleryChromeStyles.adaptivePalette()
  val page = MaterialTheme.colorScheme.background
  val text = MaterialTheme.colorScheme.onSurface
  val darkTheme = GlassDefaults.isDarkSurface()
  val key by remember(grid, bounds) { derivedStateOf { if (grid != null && bounds != null) grid.rowsUnder(bounds) else null } }
  val previous = remember { mutableStateOf<Boolean?>(null) }
  val view = grid?.view
  val k = key
  val choice = remember(k, view, page, text, veil, palette) {
    val raw = if (grid == null || bounds == null) palette.unknown else grid.contentUnder(bounds, page, text)
    val range = if (veil == null) raw else ContentRange(raw.samples.map { veil.compositeOver(it) })
    GalleryLegibility.adapt(range, palette, previousDark = previous.value ?: !darkTheme)
  }
  SideEffect { previous.value = choice.darkInk }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val ink by animateColorAsState(choice.ink, if (reduced) snap() else FluidMotion.standard(), label = "inchiostro adattivo")
  val film by animateColorAsState(choice.film, if (reduced) snap() else FluidMotion.standard(), label = "pellicola adattiva")
  return GalleryChromeStyles.adaptive(choice.copy(ink = ink, film = film))
}

/**
 * Lo stile del vetro adattivo per un contenuto **qualunque** (nero e bianco pieni), nella polarita' del tema: per i pezzi
 * che corrono su tutta la griglia (lo scrubber) e per le pagine senza ThumbHash. E' la pellicola piu' densa che il vetro
 * adattivo puo' chiedere: regge 4,5:1 su tutto, per costruzione.
 */
@Composable
internal fun rememberAnyContentChromeStyle(): GalleryChromeStyle {
  val palette = GalleryChromeStyles.adaptivePalette()
  val darkTheme = GlassDefaults.isDarkSurface()
  val choice = remember(palette, darkTheme) { GalleryLegibility.adapt(palette.unknown, palette, previousDark = !darkTheme) }
  return GalleryChromeStyles.adaptive(choice)
}

/** Che cosa dice l'etichetta in alto: il titolo e, sotto, il luogo o il riepilogo. */
internal data class ChromeLabelText(val title: String, val subtitle: String?)

/**
 * L'altezza della chrome in alto di una pagina a griglia, barra di stato compresa: e' la linea sotto cui comincia la
 * griglia e su cui si legge la sezione. Cresce con il testo grande, ma non dipende da cio' che l'etichetta dice
 * (`chromeRowHeight`): non si misura, quindi non puo' fare l'altalena con la sezione che legge.
 */
@Composable
internal fun gridTopChromeHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + chromeRowHeight()

/**
 * La riga di chrome in alto di una pagina a griglia: sotto la barra di stato, [onBack] (una capsula con la freccia, se
 * c'e'), l'etichetta con [label] e la capsula dei comandi ([commands], se [showCommands]). Ogni pezzo e' vetro adattivo.
 * [topChrome] e' [gridTopChromeHeight].
 *
 * Le icone della barra di stato seguono il tema: la sfumatura di bordo ([GridTopEdgeScrim]) e' densa proprio li'.
 */
@Composable
internal fun GridTopChrome(
  grid: MediaGridState?,
  backdrop: GlassBackdropState,
  label: ChromeLabelText,
  topChrome: Dp,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  showCommands: Boolean = true,
  commands: @Composable RowScope.() -> Unit,
) {
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val page = MaterialTheme.colorScheme.background
  val density = LocalDensity.current
  var backBounds by remember { mutableStateOf<Rect?>(null) }
  var labelBounds by remember { mutableStateOf<Rect?>(null) }
  var capsuleBounds by remember { mutableStateOf<Rect?>(null) }
  val stops = topEdgeStops(statusBar, topChrome)
  // Il velo sotto un pezzo di chrome: la sfumatura della pagina alla sua opacita' piu' bassa (il bordo basso del pezzo).
  fun veilAt(bounds: Rect?): Color? = bounds?.let { page.copy(alpha = edgeAlphaAt(stops, with(density) { it.bottom.toDp() })) }
  val labelStyle = rememberAdaptiveChromeStyle(grid, labelBounds, veilAt(labelBounds))
  val capsuleStyle = rememberAdaptiveChromeStyle(grid, capsuleBounds, veilAt(capsuleBounds))
  val backStyle = if (onBack != null) rememberAdaptiveChromeStyle(grid, backBounds, veilAt(backBounds)) else null
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = statusBar)
      .height(chromeRowHeight())
      .padding(horizontal = GalleryChromeDefaults.EdgeMargin),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null && backStyle != null) {
      GalleryChromeCapsule(
        style = backStyle,
        backdrop = backdrop,
        modifier = Modifier.onGloballyPositioned { backBounds = it.inGrid(grid) }.galleryChromeTag("indietro"),
      ) {
        GalleryChromeIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = stringResource(R.string.grid_back), onClick = onBack)
      }
      Spacer(Modifier.width(GalleryChromeDefaults.Gap))
    }
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      GalleryChromeLabel(
        title = label.title,
        subtitle = label.subtitle,
        style = labelStyle,
        backdrop = backdrop,
        modifier = Modifier.onGloballyPositioned { labelBounds = it.inGrid(grid) }.galleryChromeTag("titolo"),
      )
    }
    if (showCommands) {
      Spacer(Modifier.width(GalleryChromeDefaults.Gap))
      GalleryChromeCapsule(
        style = capsuleStyle,
        backdrop = backdrop,
        modifier = Modifier.onGloballyPositioned { capsuleBounds = it.inGrid(grid) }.galleryChromeTag("comandi"),
        content = commands,
      )
    }
  }
}

/**
 * La sfumatura del colore della pagina in alto (lo "scroll edge effect", scelta 2 dell'utente: la cima segue il tema),
 * da posare **dentro** il fondale della pagina perche' il vetro sopra la veda.
 */
@Composable
internal fun GridTopEdgeScrim(topChrome: Dp, modifier: Modifier = Modifier) {
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val stops = topEdgeStops(statusBar, topChrome)
  GalleryEdgeScrim(color = MaterialTheme.colorScheme.background, stops = stops, modifier = modifier.fillMaxWidth().height(stops.last().first))
}

/**
 * La barra delle azioni della selezione, adattiva come la chrome in alto, nel posto della pillola: compare e scompare
 * sfumando (e' chrome, non una pagina). [veilAlpha] e' la sfumatura della pagina che le sta sotto (quella della pillola
 * nella Libreria; zero dove sotto c'e' solo la griglia). [actions] si legge solo mentre la barra si vede.
 */
@Composable
internal fun GridSelectionBar(
  visible: Boolean,
  grid: MediaGridState?,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  veilAlpha: Float = 0f,
  actions: @Composable () -> List<GalleryBarAction>,
) {
  AnimatedVisibility(visible = visible, enter = fadeIn(FluidMotion.fadeIn()), exit = fadeOut(FluidMotion.fadeOut()), modifier = modifier) {
    val page = MaterialTheme.colorScheme.background
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val style = rememberAdaptiveChromeStyle(grid, bounds, bounds?.let { page.copy(alpha = veilAlpha) })
    GalleryChromeActionBar(
      actions = actions(),
      style = style,
      backdrop = backdrop,
      modifier = Modifier.onGloballyPositioned { bounds = it.inGrid(grid) }.galleryChromeTag("azioni"),
    )
  }
}

/** Lo scrubber della griglia nello stile della chrome: maniglia e bolla di vetro adattivo per un contenuto qualunque. */
@Composable
internal fun GridScrubber(grid: MediaGridState, backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  val style = rememberAnyContentChromeStyle()
  MediaGridScrubber(state = grid, backdrop = backdrop, modifier = modifier.galleryChromeTag("scrubber"), tint = style.tint, ink = style.ink)
}

/** Le opacita' della sfumatura della pagina in alto: piena sotto la barra di stato, leggera sotto i pezzi di chrome. */
internal fun topEdgeStops(statusBar: Dp, topChrome: Dp): List<Pair<Dp, Float>> =
  listOf(0.dp to EdgeTop, statusBar to EdgeStatus, topChrome to EdgeRow, topChrome + EdgeFade to 0f)

/** L'opacita' di una sfumatura a [y], interpolando fra le tappe. */
internal fun edgeAlphaAt(stops: List<Pair<Dp, Float>>, y: Dp): Float {
  if (y <= stops.first().first) return stops.first().second
  for (i in 1 until stops.size) {
    val (y1, a1) = stops[i]
    if (y <= y1) {
      val (y0, a0) = stops[i - 1]
      val t = if (y1 > y0) (y - y0) / (y1 - y0) else 1f
      return a0 + (a1 - a0) * t
    }
  }
  return stops.last().second
}

/** I limiti di un nodo nei pixel della griglia (che parte dall'origine della sua finestra); senza griglia, della finestra. */
internal fun LayoutCoordinates.inGrid(grid: MediaGridState?): Rect = boundsInWindow().let { b -> if (grid == null) b else b.translate(-grid.originInWindow) }

// La sfumatura della pagina in alto. Densa solo sotto la barra di stato (le icone di sistema seguono il tema e non si
// adattano), leggera sotto i pezzi di chrome: il loro fondo lo garantisce la loro pellicola adattiva, e un velo forte
// farebbe seguire sempre il tema alla polarita' (misurato in G1 con il velo a 0,82-0,50: non cambiava mai).
private const val EdgeTop = 0.85f
private const val EdgeStatus = 0.55f
private const val EdgeRow = 0.15f
private val EdgeFade = 44.dp
