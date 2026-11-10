package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlotVisibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.TakeGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeActionBar
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeContent
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyle
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryTopChrome
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryTopEdgeScrim
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.core.designsystem.design.galleryEdgeAlphaAt
import dev.pampa.mycloudgallery.core.designsystem.design.galleryTopChromeHeight
import dev.pampa.mycloudgallery.core.designsystem.design.galleryTopEdgeStops
import dev.pampa.mycloudgallery.core.designsystem.design.inChromeContent
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.core.designsystem.design.rememberAdaptiveChromeStyle as designAdaptiveChromeStyle
import dev.pampa.mycloudgallery.core.designsystem.design.rememberAnyContentChromeStyle as designAnyContentChromeStyle

// La chrome delle pagine a griglia (G2, docs/grafica-progetto.md §12): la direzione C, "vetro adattivo", per la Libreria e
// per le griglie delle Raccolte. Una struttura sola:
//
//  - in alto UNA riga sotto la barra di stato, senza barra: (indietro) | etichetta | capsula dei comandi. L'etichetta
//    mostra la sezione che sta sotto la riga (e' l'intestazione agganciata: nessuna seconda fascia di vetro). Dietro, la
//    sfumatura del colore della pagina (lo "scroll edge effect"), dentro il fondale: il vetro la vede;
//  - ogni pezzo sceglie da se' pellicola e inchiostro dal contenuto che gli passa sotto (i ThumbHash delle celle, mai i
//    pixel), con l'opacita' minima che tiene 4,5:1 sul caso peggiore;
//  - in basso, in selezione, la barra delle azioni nel posto della pillola, alla stessa altezza (G2: la pillola esce); la griglia non si sposta;
//  - un fondale di vetro solo, quello della pagina: lo scrubber e' disegnato fuori, la griglia non ne registra un secondo.
//
// Fase 4B (P16): la riga in alto, la sfumatura e lo stile adattivo stanno in core-designsystem (`GalleryTopChrome`), cosi' le pagine
// della 4B (feature-nas) usano la stessa chrome invece di una copia. Qui restano gli adattatori per la griglia, con gli stessi nomi:
// l'aspetto non cambia.

/** La griglia come contenuto che passa sotto la chrome: righe e ThumbHash delle celle (`rowsUnder`, `contentUnder`). */
private class GridChromeContent(private val grid: MediaGridState) : GalleryChromeContent {
  override fun chromeRowsUnder(bounds: Rect): Long = grid.rowsUnder(bounds)

  override fun chromeContentUnder(bounds: Rect, page: Color, text: Color): ContentRange = grid.contentUnder(bounds, page, text)

  override val chromeOriginInWindow: Offset get() = grid.originInWindow

  override val chromeContentVersion: Any? get() = grid.view
}

/** L'adattatore della griglia, uno per griglia (le chiavi dello stile adattivo devono restare stabili). */
@Composable
private fun rememberGridChromeContent(grid: MediaGridState?): GalleryChromeContent? = remember(grid) { grid?.let(::GridChromeContent) }

/**
 * Lo stile adattivo di un pezzo di chrome che sta su [bounds] (pixel della griglia): vedi `rememberAdaptiveChromeStyle` di
 * core-designsystem. Senza griglia ([grid] nullo: una pagina senza miniature con ThumbHash) vale il caso peggiore.
 */
@Composable
internal fun rememberAdaptiveChromeStyle(grid: MediaGridState?, bounds: Rect?, veil: Color?): GalleryChromeStyle =
  designAdaptiveChromeStyle(rememberGridChromeContent(grid), bounds, veil)

/**
 * Lo stile del vetro adattivo per un contenuto **qualunque** (nero e bianco pieni), nella polarita' del tema: per i pezzi
 * che corrono su tutta la griglia (lo scrubber) e per le pagine senza ThumbHash.
 */
@Composable
internal fun rememberAnyContentChromeStyle(): GalleryChromeStyle = designAnyContentChromeStyle()

/**
 * Che cosa dice l'etichetta in alto: il titolo e, sotto, il luogo o il riepilogo. [shorterTitles]: le forme piu' corte del titolo (una
 * data), dalla piu' lunga alla piu' stretta; l'etichetta mostra la prima che entra invece dei puntini (GF-2). [shorterSubtitles]: se il sottotitolo e' un
 * riepilogo (un conteggio) le sue forme piu' corte: si mostra la prima che entra, e se nessuna entra la seconda riga manca (GF-22); null = si tronca con i puntini.
 */
internal data class ChromeLabelText(
  val title: String,
  val subtitle: String?,
  val shorterTitles: List<String> = emptyList(),
  val shorterSubtitles: List<String>? = null,
)

/** L'altezza della chrome in alto di una pagina a griglia, barra di stato compresa (`galleryTopChromeHeight`). */
@Composable
internal fun gridTopChromeHeight(): Dp = galleryTopChromeHeight()

/**
 * La riga di chrome in alto di una pagina a griglia (`GalleryTopChrome` di core-designsystem) sulla griglia [grid]: indietro,
 * etichetta con [label], capsula dei comandi.
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
  // P2: mentre la griglia si muove per un gesto l'etichetta non anima la larghezza (vedi `MediaGridState.inMotion`).
  val contentMoving = remember(grid) { { grid?.inMotion == true } }
  GalleryTopChrome(
    content = rememberGridChromeContent(grid),
    backdrop = backdrop,
    title = label.title,
    subtitle = label.subtitle,
    topChrome = topChrome,
    modifier = modifier,
    onBack = onBack,
    backDescription = stringResource(R.string.grid_back),
    showCommands = showCommands,
    shorterTitles = label.shorterTitles,
    shorterSubtitles = label.shorterSubtitles,
    contentMoving = contentMoving,
    commands = commands,
  )
}

/** La sfumatura del colore della pagina in alto (`GalleryTopEdgeScrim`), da posare **dentro** il fondale della pagina. */
@Composable
internal fun GridTopEdgeScrim(topChrome: Dp, modifier: Modifier = Modifier) = GalleryTopEdgeScrim(topChrome, modifier)

/**
 * La barra delle azioni della selezione, adattiva come la chrome in alto, **nel posto della pillola** (G2, B3): entra ed esce come la
 * capsula `ReplacingTabBar` dell'engine (sale da sotto il bordo con la molla del chrome, scende piu' svelta) e, dentro una scheda, prende
 * il posto della pillola finche' si vede ([TakeGalleryTabBarSlot]): la pillola e il cerchio di Cerca escono, niente si posa sopra di loro.
 * I margini (barra di sistema, lati, fondo) li mette lei: il chiamante la allinea in basso e basta. [veilAlpha] e' la sfumatura della pagina
 * che le sta sotto (quella della pillola nella Libreria; zero dove sotto c'e' solo la griglia). [actions] si legge solo mentre la barra si vede.
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
  TakeGalleryTabBarSlot(active = visible)
  GalleryTabBarSlotVisibility(visible = visible, modifier = modifier) {
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

/** Le opacita' della sfumatura della pagina in alto (`galleryTopEdgeStops`). */
internal fun topEdgeStops(statusBar: Dp, topChrome: Dp): List<Pair<Dp, Float>> = galleryTopEdgeStops(statusBar, topChrome)

/** L'opacita' di una sfumatura a [y], interpolando fra le tappe (`galleryEdgeAlphaAt`). */
internal fun edgeAlphaAt(stops: List<Pair<Dp, Float>>, y: Dp): Float = galleryEdgeAlphaAt(stops, y)

/** I limiti di un nodo nei pixel della griglia (che parte dall'origine della sua finestra); senza griglia, della finestra. */
internal fun LayoutCoordinates.inGrid(grid: MediaGridState?): Rect = inChromeContent(grid?.let(::GridChromeContent))
