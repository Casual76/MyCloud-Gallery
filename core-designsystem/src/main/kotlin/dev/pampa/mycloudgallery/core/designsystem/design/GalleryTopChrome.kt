package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import dev.pampa.mycloudgallery.core.designsystem.R
import kotlin.math.abs

// La chrome in alto delle pagine della galleria (G2, docs/grafica-progetto.md §12): la direzione C, "vetro adattivo". Nata per la
// Libreria e le griglie delle Raccolte (`GridTopChrome` di feature-library), sta qui dalla Fase 4B (P16) perche' la usano anche le
// pagine della 4B (feature-nas) senza una copia. Una struttura sola:
//
//  - UNA riga sotto la barra di stato, senza barra: (indietro) | etichetta | capsula dei comandi. Dietro, la sfumatura del colore
//    della pagina (lo "scroll edge effect"), dentro il fondale: il vetro la vede;
//  - ogni pezzo sceglie da se' pellicola e inchiostro dal contenuto che gli passa sotto ([GalleryChromeContent]: i ThumbHash delle
//    celle di una griglia, mai i pixel), con l'opacita' minima che tiene 4,5:1 sul caso peggiore; senza contenuto noto vale il caso
//    peggiore assoluto (nero e bianco pieni).

/**
 * Il contenuto che passa sotto la chrome adattiva (una griglia di miniature). Tutto in pixel del contenuto, che parte da
 * [chromeOriginInWindow] nella finestra.
 */
interface GalleryChromeContent {
  /** Una chiave delle righe sotto [bounds]: cambia solo quando sotto entra o esce una riga (lo stile si ricalcola solo allora). */
  fun chromeRowsUnder(bounds: Rect): Long

  /** I colori del contenuto sotto [bounds] (per una griglia: i ThumbHash delle celle sulla pagina [page], col testo [text]). */
  fun chromeContentUnder(bounds: Rect, page: Color, text: Color): ContentRange

  /** Dove sta l'origine del contenuto nella finestra. */
  val chromeOriginInWindow: Offset

  /** Cambia quando cambia cio' che il contenuto mostra (la vista della griglia): la scelta si rifa'. Letto in composizione. */
  val chromeContentVersion: Any?
}

/**
 * Lo stile adattivo di un pezzo di chrome che sta su [bounds] (pixel del contenuto): si ricalcola solo quando sotto entra o esce una
 * riga, dai colori del [content] composti con il velo [veil] (la sfumatura di bordo alla quota del bordo basso del pezzo). Senza
 * contenuto ([content] nullo: una pagina senza miniature con ThumbHash) vale il caso peggiore.
 *
 * **Nessuna coppia mostrata per strada scende sotto la soglia** (GS-5, review della beta): la scelta nuova vale dalla composizione
 * che la calcola. L'inchiostro e la polarita' cambiano in un fotogramma, e una pellicola che deve farsi piu' densa lo fa subito; solo
 * una pellicola che puo' **alleggerirsi** lo fa con la molla standard ([adaptiveFilmAlpha]). Prima inchiostro e pellicola si animavano
 * separatamente: fra le due polarita', a meta' strada, il testo stava a 2,56:1.
 */
@Composable
fun rememberAdaptiveChromeStyle(content: GalleryChromeContent?, bounds: Rect?, veil: Color?): GalleryChromeStyle {
  val palette = GalleryChromeStyles.adaptivePalette()
  val page = MaterialTheme.colorScheme.background
  val text = MaterialTheme.colorScheme.onSurface
  val darkTheme = GlassDefaults.isDarkSurface()
  val key by remember(content, bounds) { derivedStateOf { if (content != null && bounds != null) content.chromeRowsUnder(bounds) else null } }
  val previous = remember { mutableStateOf<Boolean?>(null) }
  val version = content?.chromeContentVersion
  val k = key
  val memo = remember(palette) { AdaptiveMemo(palette) }
  val choice = remember(k, version, page, text, veil, palette) {
    val raw = if (content == null || bounds == null) palette.unknown else content.chromeContentUnder(bounds, page, text)
    val range = if (veil == null) raw else ContentRange(raw.samples.map { veil.compositeOver(it) })
    memo.adapt(range, previousDark = previous.value ?: !darkTheme)
  }
  SideEffect { previous.value = choice.darkInk }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val motion = remember { AdaptiveFilmMotion(choice) }
  LaunchedEffect(choice.darkInk, choice.film.alpha, reduced) { motion.follow(choice, reduced) }
  return GalleryChromeStyles.adaptive(choice.copy(film = choice.film.copy(alpha = motion.shownAlpha(choice))))
}

/**
 * L'opacita' della pellicola da mostrare mentre il vetro adattivo va verso la scelta [to] (GS-5): [animated] e' l'opacita' che la molla
 * sta portando, valida per la polarita' [animatedDark].
 *  - se la polarita' e' cambiata, quella di [to]: la pellicola di prima e' dell'altro colore e sotto l'inchiostro nuovo non regge;
 *  - se no, **mai meno** di quella di [to], che e' il minimo che tiene la soglia sul contenuto nuovo: una pellicola che deve crescere
 *    arriva subito, una che puo' calare cala con la molla (e un rimbalzo della molla sotto il minimo non si mostra).
 * Funzione pura: il percorso si verifica campionato, senza comporre (`GalleryTopChromeTest`).
 */
internal fun adaptiveFilmAlpha(animated: Float, animatedDark: Boolean, to: AdaptiveInk): Float =
  if (animatedDark != to.darkInk) to.film.alpha else maxOf(animated, to.film.alpha)

/** La molla dell'opacita' della pellicola di un pezzo di chrome, con la polarita' per cui vale. */
private class AdaptiveFilmMotion(initial: AdaptiveInk) {
  private val alpha = Animatable(initial.film.alpha, visibilityThreshold = AlphaThreshold)
  private var dark = initial.darkInk

  /** Letta in composizione: cio' che si mostra adesso, gia' sopra la soglia per [choice]. */
  fun shownAlpha(choice: AdaptiveInk): Float = adaptiveFilmAlpha(alpha.value, dark, choice)

  /** Porta la molla verso [choice]: con la molla solo se la pellicola si alleggerisce nella stessa polarita'. */
  suspend fun follow(choice: AdaptiveInk, reduced: Boolean) {
    val target = choice.film.alpha
    if (reduced || dark != choice.darkInk || alpha.value <= target) {
      alpha.snapTo(target)
      dark = choice.darkInk
    } else {
      alpha.animateTo(target, FluidMotion.standard())
    }
  }
}

/** La molla dell'opacita' si ferma a un millesimo dal valore: un passo che non si vede. */
private const val AlphaThreshold = 0.001f

/**
 * Le ultime scelte di [GalleryLegibility.adapt] di un pezzo di chrome, per contenuto: lo stesso contenuto sotto (lo stesso
 * livello allo stesso punto, tornando indietro con un pinch o uno scorrimento) da' la stessa scelta senza rifare le due
 * ricerche. E' la stessa funzione pura, ricordata: il risultato non cambia.
 */
private class AdaptiveMemo(private val palette: AdaptivePalette) {
  private val choices = object : LinkedHashMap<Pair<ContentRange, Boolean>, AdaptiveInk>(MemoEntries, MemoLoadFactor, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<ContentRange, Boolean>, AdaptiveInk>?): Boolean = size > MemoEntries
  }

  fun adapt(range: ContentRange, previousDark: Boolean): AdaptiveInk =
    choices.getOrPut(range to previousDark) { GalleryLegibility.adapt(range, palette, previousDark = previousDark) }
}

private const val MemoEntries = 16
private const val MemoLoadFactor = 0.75f

/**
 * Lo stile del vetro adattivo per un contenuto **qualunque** (nero e bianco pieni), nella polarita' del tema: per i pezzi che corrono
 * su tutta la griglia (lo scrubber) e per le pagine senza ThumbHash. E' la pellicola piu' densa che il vetro adattivo puo' chiedere:
 * regge 4,5:1 su tutto, per costruzione.
 */
@Composable
fun rememberAnyContentChromeStyle(): GalleryChromeStyle {
  val palette = GalleryChromeStyles.adaptivePalette()
  val darkTheme = GlassDefaults.isDarkSurface()
  val choice = remember(palette, darkTheme) { GalleryLegibility.adapt(palette.unknown, palette, previousDark = !darkTheme) }
  return GalleryChromeStyles.adaptive(choice)
}

/**
 * L'altezza della chrome in alto, barra di stato compresa: e' la linea sotto cui comincia il contenuto e su cui si legge la sezione.
 * Cresce con il testo grande, ma non dipende da cio' che l'etichetta dice ([chromeRowHeight]): non si misura, quindi non puo' fare
 * l'altalena con la sezione che legge.
 */
@Composable
fun galleryTopChromeHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + chromeRowHeight()

/**
 * La riga di chrome in alto: sotto la barra di stato, [onBack] (una capsula con la freccia, se c'e'), l'etichetta con [title] e
 * [subtitle] e la capsula dei comandi ([commands], se [showCommands]). Ogni pezzo e' vetro adattivo sul [content] che gli passa
 * sotto (null = caso peggiore). [topChrome] e' [galleryTopChromeHeight]. [shorterTitles]: le forme piu' corte di [title], per
 * quando la riga e' stretta ([GalleryChromeLabel]). [shorterSubtitles]: se [subtitle] e' un riepilogo da mostrare intero o niente (GF-22),
 * le sue forme piu' corte; null = il sottotitolo si tronca con i puntini (il luogo).
 *
 * Le icone della barra di stato seguono il tema: la sfumatura di bordo ([GalleryTopEdgeScrim]) e' densa proprio li'.
 */
@Composable
fun GalleryTopChrome(
  content: GalleryChromeContent?,
  backdrop: GlassBackdropState,
  title: String,
  subtitle: String?,
  topChrome: Dp,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  backDescription: String = stringResource(R.string.topbar_back),
  showCommands: Boolean = true,
  shorterTitles: List<String> = emptyList(),
  shorterSubtitles: List<String>? = null,
  commands: @Composable RowScope.() -> Unit,
) {
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val page = MaterialTheme.colorScheme.background
  val density = LocalDensity.current
  var backBounds by remember { mutableStateOf<Rect?>(null) }
  // L'etichetta e la capsula cambiano larghezza con una molla (B1 della beta): i loro limiti, che scelgono lo stile adattivo, si
  // aggiornano solo quando si spostano di almeno [BoundsSlack] (o cambiano quota), non a ogni fotogramma della molla. Lo stile si
  // ricalcola comunque solo quando sotto entra o esce una riga di celle, larghe piu' di quella tolleranza.
  val labelBounds = remember { mutableStateOf<Rect?>(null) }
  val capsuleBounds = remember { mutableStateOf<Rect?>(null) }
  val slack = with(density) { BoundsSlack.toPx() }
  val stops = galleryTopEdgeStops(statusBar, topChrome)
  // Il velo sotto un pezzo di chrome: la sfumatura della pagina alla sua opacita' piu' bassa (il bordo basso del pezzo).
  fun veilAt(bounds: Rect?): Color? = bounds?.let { page.copy(alpha = galleryEdgeAlphaAt(stops, with(density) { it.bottom.toDp() })) }
  val labelStyle = rememberAdaptiveChromeStyle(content, labelBounds.value, veilAt(labelBounds.value))
  val capsuleStyle = rememberAdaptiveChromeStyle(content, capsuleBounds.value, veilAt(capsuleBounds.value))
  val backStyle = if (onBack != null) rememberAdaptiveChromeStyle(content, backBounds, veilAt(backBounds)) else null
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
        modifier = Modifier.onGloballyPositioned { backBounds = it.inChromeContent(content) }.galleryChromeTag("indietro"),
      ) {
        GalleryChromeIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = backDescription, onClick = onBack)
      }
      Spacer(Modifier.width(GalleryChromeDefaults.Gap))
    }
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      GalleryChromeLabel(
        title = title,
        subtitle = subtitle,
        style = labelStyle,
        backdrop = backdrop,
        modifier = Modifier.onGloballyPositioned { labelBounds.settle(it.inChromeContent(content), slack) }.galleryChromeTag("titolo"),
        shorterTitles = shorterTitles,
        shorterSubtitles = shorterSubtitles,
      )
    }
    if (showCommands) {
      Spacer(Modifier.width(GalleryChromeDefaults.Gap))
      GalleryChromeCapsule(
        style = capsuleStyle,
        backdrop = backdrop,
        modifier = Modifier.onGloballyPositioned { capsuleBounds.settle(it.inChromeContent(content), slack) }.galleryChromeTag("comandi"),
        content = commands,
      )
    }
  }
}

/**
 * La sfumatura del colore della pagina in alto (lo "scroll edge effect", scelta 2 dell'utente: la cima segue il tema), da posare
 * **dentro** il fondale della pagina perche' il vetro sopra la veda.
 */
@Composable
fun GalleryTopEdgeScrim(topChrome: Dp, modifier: Modifier = Modifier) {
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val stops = galleryTopEdgeStops(statusBar, topChrome)
  GalleryEdgeScrim(color = MaterialTheme.colorScheme.background, stops = stops, modifier = modifier.fillMaxWidth().height(stops.last().first))
}

/** Le opacita' della sfumatura della pagina in alto: piena sotto la barra di stato, leggera sotto i pezzi di chrome. */
fun galleryTopEdgeStops(statusBar: Dp, topChrome: Dp): List<Pair<Dp, Float>> =
  listOf(0.dp to EdgeTop, statusBar to EdgeStatus, topChrome to EdgeRow, topChrome + EdgeFade to 0f)

/** L'opacita' di una sfumatura a [y], interpolando fra le tappe. */
fun galleryEdgeAlphaAt(stops: List<Pair<Dp, Float>>, y: Dp): Float {
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

/**
 * Aggiorna i limiti di un pezzo di chrome solo se sono cambiati davvero: un bordo laterale spostato di piu' di [slackPx] (la larghezza
 * che anima), o la quota. Il primo valore passa sempre.
 */
internal fun MutableState<Rect?>.settle(next: Rect, slackPx: Float) {
  val now = value
  if (now == null || abs(now.left - next.left) > slackPx || abs(now.right - next.right) > slackPx || now.top != next.top || now.bottom != next.bottom) {
    value = next
  }
}

/** I limiti di un nodo nei pixel del contenuto (che parte da [GalleryChromeContent.chromeOriginInWindow]); senza, della finestra. */
fun LayoutCoordinates.inChromeContent(content: GalleryChromeContent?): Rect =
  boundsInWindow().let { b -> if (content == null) b else b.translate(-content.chromeOriginInWindow) }

// La sfumatura della pagina in alto. Densa solo sotto la barra di stato (le icone di sistema seguono il tema e non si adattano),
// leggera sotto i pezzi di chrome: il loro fondo lo garantisce la loro pellicola adattiva, e un velo forte farebbe seguire sempre il
// tema alla polarita' (misurato in G1 con il velo a 0,82-0,50: non cambiava mai).
private const val EdgeTop = 0.85f
private const val EdgeStatus = 0.55f
private const val EdgeRow = 0.15f
private val EdgeFade = 44.dp

/** La tolleranza dei limiti di un pezzo che cambia larghezza: meno di un settimo della cella piu' piccola della griglia (58 dp). */
private val BoundsSlack = 8.dp
