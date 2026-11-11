package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.BlurMaskFilter
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import kotlin.math.ceil
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleAction
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingCapsuleDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidSearchField
import dev.antigravity.fluidengine.ui.fluid.FluidSearchFieldDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidSearchLabels
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.holdsTouches
import dev.pampa.mycloudgallery.core.designsystem.design.rememberAnyContentChromeStyle
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import kotlin.math.max

// LA BARRA DI RICERCA DELLA LIBRERIA (H4 della fase H, 11/11: «stile ricerca in app Apple»). Il cerchio di Cerca accanto alla pillola **si allarga di lato**
// fino a diventare il campo, mentre la pillola esce nello stesso movimento; poi il campo **sale con la tastiera**, fotogramma per fotogramma, con il suo
// vetro sempre calcolato su cio' che ha dietro. Il campo e' quello dell'engine (`FluidSearchField`, che nasce proprio come cerchio che diventa campo):
// qui c'e' solo dove sta, da dove parte e la riga sopra di lui.

/** Le misure della barra di ricerca. */
object GallerySearchBarDefaults {

  /** Sul tablet e in orizzontale la barra non diventa larga un metro: al piu' come una capsula dell'engine, centrata. */
  val MaxWidth: Dp = FluidFloatingCapsuleDefaults.MaxWidth

  /** Fra la barra e la tastiera aperta. */
  val KeyboardGap: Dp = 8.dp

  /**
   * Il campo (52 dp) sta al centro dell'altezza della pillola (60 dp): il suo bordo basso e' tanto sopra quello della pillola. Cosi' il cerchio del
   * campo nasce concentrico al cerchio della pillola.
   */
  val RestInset: Dp = (FluidFloatingTabBarDefaults.OpenHeight - FluidSearchFieldDefaults.Height) / 2

  /** L'altezza visibile della riga sopra la barra (il bersaglio del tocco resta di 48 dp). */
  val LineHeight: Dp = 36.dp

  /** Fra la riga e la barra. */
  val LineGap: Dp = 8.dp

  /** Lo spazio che la barra occupa sopra il bordo basso (barra di sistema esclusa), senza tastiera: margine della pillola, rientro, campo. */
  val ContentInset: Dp = FluidFloatingTabBarDefaults.BottomMargin + RestInset + FluidSearchFieldDefaults.Height

  /** Lo spazio in piu' quando c'e' la riga sopra la barra. */
  val LineInset: Dp = GalleryDimens.MinTouchTarget + LineGap - (GalleryDimens.MinTouchTarget - LineHeight) / 2
}

/**
 * Quanto salgono la barra di ricerca e i risultati sopra la tastiera, in pixel: zero a tastiera chiusa, poi quanto serve perche' la barra stia
 * [GallerySearchBarDefaults.KeyboardGap] sopra la tastiera, **a ogni fotogramma** dell'animazione della tastiera (`WindowInsets.ime`).
 *
 * [px] legge gli inset: va chiamata **nel layout o nel disegno** (`Modifier.offset { }`, `graphicsLayer { }`), mai in composizione, cosi' la salita
 * non ricompone niente. La barra e i risultati la leggono nello stesso fotogramma: salgono insieme.
 */
@Stable
class GallerySearchLift internal constructor(
  private val ime: WindowInsets,
  private val navigation: WindowInsets,
  private val density: Density,
) {
  fun px(): Int = with(density) {
    val rest = navigation.getBottom(this) + (FluidFloatingTabBarDefaults.BottomMargin + GallerySearchBarDefaults.RestInset).roundToPx()
    max(0, ime.getBottom(this) + GallerySearchBarDefaults.KeyboardGap.roundToPx() - rest)
  }
}

@Composable
fun rememberGallerySearchLift(): GallerySearchLift {
  val ime = WindowInsets.ime
  val navigation = WindowInsets.navigationBars
  val density = LocalDensity.current
  return remember(ime, navigation, density) { GallerySearchLift(ime, navigation, density) }
}

/**
 * Lo stato della barra di ricerca che deve sopravvivere alla barra stessa (H4): se e' aperta e da dove e' partita. Va ricordato da chi posa la barra, fuori
 * da cio' che la toglie (la selezione le prende il posto e la toglie; quando torna e' gia' aperta).
 */
@Stable
class GallerySearchBarState {
  internal var expanded: Boolean by mutableStateOf(false)
  internal var start: Float? by mutableStateOf(null)
}

@Composable
fun rememberGallerySearchBarState(): GallerySearchBarState = remember { GallerySearchBarState() }

/**
 * Dove sta il cerchio di Cerca della pillola (il suo centro, nella finestra): la barra di ricerca ci nasce sopra e ci torna. Lo scrive la pillola
 * ([GalleryTabBar]) quando si posa; si legge solo all'apertura e alla chiusura, mai per fotogramma.
 */
class GallerySearchCircle {
  internal var coordinates: LayoutCoordinates? = null
  private var last: Float? = null

  /**
   * Il centro del cerchio in pixel della finestra; se la pillola adesso non c'e' (la ricerca e' aperta e l'ha tolta), l'ultimo visto: la barra torna
   * dove la pillola ricomparira'. Null se la pillola non si e' mai posata.
   */
  fun centerXInWindow(): Float? {
    val now = coordinates?.takeIf { it.isAttached }?.let { it.positionInWindow().x + it.size.width / 2f }
    if (now != null) last = now
    return now ?: last
  }
}

/**
 * La barra di ricerca nel posto della pillola (H4): va posata dove sta la pillola, dentro [GalleryTabBarSlotVisibility] (che mette i margini ai lati e in
 * basso e la fa uscire quando la barra delle azioni di una selezione prende il posto). Va composta finche' e' aperta **e** mentre si richiude: [open] e' lo
 * stato voluto, e quando la barra e' tornata cerchio chiama [onCollapsed] (chi la ospita la toglie e la pillola e' gia' tornata al suo posto).
 *
 * - **Apertura**: compare come cerchio sopra il cerchio della pillola ([circle]) e si allarga verso sinistra fino alla sua larghezza (tutta la riga sul
 *   telefono, al piu' [GallerySearchBarDefaults.MaxWidth] centrata sul tablet), con la molla di `FluidSearchField` (`FluidMotion.standard`); intanto scorre
 *   dal cerchio alla sua posizione. Il vetro ha la misura finale dal primo fotogramma: si allarga la parte che si vede (CG-2). Con [focus] prende il fuoco
 *   e la tastiera sale; [onFocused] consuma la richiesta (tornando dal viewer non risale).
 * - **Tastiera**: sale con lei ([lift], letto nel layout).
 * - **Chiusura**: **una X sola** (CU-12): con del testo la crocetta lo svuota, a campo vuoto la X chiama [onClose]; «indietro» la chiude sempre (lo
 *   scaffold). Il campo torna cerchio verso la pillola e la tastiera scende subito.
 * - [message]: la riga sopra la barra (una sola, piccola); [onMessageClick] la rende un tasto, detto da [messageActionLabel].
 */
@Composable
fun GallerySearchBar(
  open: Boolean,
  query: String,
  onQueryChange: (String) -> Unit,
  onClose: () -> Unit,
  onSearch: () -> Unit,
  onCollapsed: () -> Unit,
  lift: GallerySearchLift,
  circle: GallerySearchCircle,
  state: GallerySearchBarState,
  modifier: Modifier = Modifier,
  backdrop: GlassBackdropState? = null,
  placeholder: String? = null,
  focus: Boolean = false,
  onFocused: () -> Unit = {},
  onFocusChanged: (Boolean) -> Unit = {},
  message: String? = null,
  onMessageClick: (() -> Unit)? = null,
  messageActionLabel: String? = null,
) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  // La barra nasce chiusa (un cerchio) e si apre al fotogramma dopo. Lo stato sta fuori ([state]): la barra che rientra dopo una selezione (la barra
  // delle azioni le aveva preso il posto) e' gia' aperta, non rinasce dal cerchio.
  var expanded by state::expanded
  LaunchedEffect(open) {
    if (open && !expanded) state.start = circle.centerXInWindow()
    // Chiudendo il fuoco e la tastiera vanno via subito, non restano a coprire mentre la barra torna cerchio.
    if (!open && expanded) {
      focusManager.clearFocus()
      keyboard?.hide()
    }
    expanded = open
  }
  val currentOnCollapsed by rememberUpdatedState(onCollapsed)
  // La molla dell'apertura (quella di `FluidSearchField`, `FluidMotion.standard`): lo scorrimento dal cerchio e la rivelazione vanno insieme.
  val progress = animateFloatAsState(
    targetValue = if (expanded) 1f else 0f,
    animationSpec = if (reduced) snap() else FluidMotion.standard(),
    label = "gallery search bar",
    finishedListener = { if (it == 0f && !open) currentOnCollapsed() },
  )
  // Chiusa a meta' apertura (o mai aperta): il cerchio torna e la barra si toglie anche senza animazione.
  LaunchedEffect(open, expanded) { if (!open && !expanded && progress.value == 0f) currentOnCollapsed() }
  // Dove nasce e dove torna: il centro del cerchio della pillola, letto all'apertura (la pillola sta ferma).
  val start = state.start
  var rightEdge by remember { mutableStateOf<Float?>(null) }
  val labels = FluidSearchLabels(
    search = stringResource(R.string.search_bar_search),
    clear = stringResource(R.string.search_bar_clear),
    close = stringResource(R.string.search_bar_close),
  )

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(bottom = GallerySearchBarDefaults.RestInset)
      .offset { IntOffset(0, -lift.px()) },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Column(
      modifier = Modifier.widthIn(max = GallerySearchBarDefaults.MaxWidth).fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(GallerySearchBarDefaults.LineGap - (GalleryDimens.MinTouchTarget - GallerySearchBarDefaults.LineHeight) / 2),
    ) {
      GallerySearchLine(message = message, onClick = onMessageClick, actionLabel = messageActionLabel, backdrop = backdrop, visible = { progress.value > 0.5f })
      // CG-2 (giro 1): il vetro del campo ha la sua misura finale **dal primo fotogramma**. Il campo dell'engine richiudibile si allargava rimisurando
      // il nodo di vetro a ogni fotogramma (una cattura dello sfondo di misura nuova per fotogramma, la stessa classe di costo del pinch al 3,4-4%):
      // qui il campo e' sempre aperto, largo quanto la riga, e l'apertura e' una traslazione piu' un ritaglio letti nel disegno ([searchReveal]).
      BoxWithConstraints(modifier = Modifier.fillMaxWidth().onPlaced { rightEdge = it.positionInWindow().x + it.size.width }) {
        val field = backdrop ?: currentGlassBackdrop()
        val fitted = fittingPlaceholder(placeholder ?: labels.search, fallback = labels.search, fieldWidth = maxWidth)
        val hint = fitted.text
        // Il campo e la X si spostano insieme, della stessa quantita' (calcolata sulla larghezza del campo, non su quella di chi la legge).
        val fieldWidth = constraints.maxWidth.toFloat()
        // Da quando il segnaposto della rivelazione non e' piu' tagliato dal capo destro (CG-2 del giro 2).
        val density = LocalDensity.current
        val placeholderStart = remember(fieldWidth, fitted, density) {
          with(density) { revealPlaceholderStart(fieldWidth, FluidSearchFieldDefaults.Height.toPx(), SearchFieldTextStart.toPx() + fitted.width) }
        }
        // L'ombra del capo destro mentre la barra cresce: quella del vetro dell'engine, dal ruolo scrim (nero) del tema.
        val capShadow = MaterialTheme.colorScheme.scrim.copy(alpha = if (GlassDefaults.isDarkSurface()) SearchCapShadowDark else SearchCapShadowLight)
        // Durante la molla il segnaposto e' il nostro, in dissolvenza; a barra aperta quello del campo (una ricomposizione all'arrivo e alla partenza).
        val revealed by remember { derivedStateOf { progress.value >= 1f } }
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
              val p = progress.value.coerceIn(0f, 1f)
              // Il cerchio del campo sostituisce quello della pillola nel primo quinto dell'apertura (e se ne va per ultimo chiudendo).
              alpha = (p / CircleCrossfade).coerceIn(0f, 1f)
              val from = start
              val edge = rightEdge
              translationX = if (from != null && edge != null) lerp(from - (edge - FluidSearchFieldDefaults.Height.toPx() / 2f), 0f, p) else 0f
            }
            .searchReveal(progress = { progress.value }, shadow = capShadow),
        ) {
          val slide = Modifier.graphicsLayer { translationX = searchRevealHidden(fieldWidth, FluidSearchFieldDefaults.Height.toPx(), progress.value) }
          FluidSearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier
              .fillMaxWidth()
              .then(slide)
              .onFocusChanged { onFocusChanged(it.hasFocus) },
            backdrop = field,
            placeholder = if (revealed) hint else "",
            labels = labels,
            onSearch = {
              onSearch()
              keyboard?.hide()
            },
            autoFocus = focus,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          )
          if (!revealed && query.isEmpty()) {
            RevealPlaceholder(text = hint, alpha = { revealPlaceholderAlpha(progress.value, placeholderStart) }, modifier = Modifier.matchParentSize().then(slide))
          }
          // CU-12 (giro 1): **una X sola**. Con del testo c'e' la crocetta del campo (svuota); a campo vuoto, nello stesso punto, la X che chiude.
          SearchCloseButton(
            visible = query.isEmpty() && open,
            label = labels.close,
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterEnd).then(slide),
          )
          // Mentre torna cerchio la barra non si tocca: un tocco non deve riaprire la tastiera su un campo che se ne sta andando.
          if (!open) Box(Modifier.matchParentSize().holdsTouches())
        }
      }
    }
  }
  // La richiesta del fuoco e' di una volta: servita dal campo (che la legge quando si apre), si consuma al fotogramma dopo.
  LaunchedEffect(expanded, focus) {
    if (expanded && focus) {
      withFrameNanos { }
      onFocused()
    }
  }
}

/**
 * La riga sopra la barra: una frase sola, piccola, su un pezzo di vetro della chrome (pellicola e inchiostro che reggono 4,5:1 su qualunque foto). Se si
 * tocca e' un tasto da 48 dp (il vetro visibile e' piu' basso). Entra ed esce in dissolvenza, mai cambiando misura mentre si anima.
 */
@Composable
private fun GallerySearchLine(
  message: String?,
  onClick: (() -> Unit)?,
  actionLabel: String?,
  backdrop: GlassBackdropState?,
  visible: () -> Boolean,
) {
  val shown = remember { mutableStateOf(message) }
  if (message != null && message != shown.value) shown.value = message
  AnimatedVisibility(
    visible = message != null,
    enter = fadeIn(FluidMotion.fadeIn()),
    exit = fadeOut(FluidMotion.fadeOut()),
  ) {
    val text = shown.value ?: return@AnimatedVisibility
    val style = rememberAnyContentChromeStyle()
    val glass = backdrop ?: currentGlassBackdrop()
    Box(
      modifier = Modifier
        .heightIn(min = GalleryDimens.MinTouchTarget)
        .graphicsLayer { alpha = if (visible()) 1f else 0f }
        .then(
          if (onClick != null) {
            Modifier
              .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape)
              .semantics { if (actionLabel != null) onClick(label = actionLabel) { onClick(); true } }
          } else {
            // Come ogni pezzo della chrome (fase H, D2): un tocco sulla riga non apre il risultato dietro.
            Modifier.holdsTouches().semantics { liveRegion = LiveRegionMode.Polite }
          },
        ),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier = Modifier
          .heightIn(min = GallerySearchBarDefaults.LineHeight)
          .galleryGlass(state = glass, tint = style.tint, shape = FluidCapsuleShape, role = GlassRole.Floating)
          .padding(horizontal = GalleryChromeDefaults.TextButtonPadding),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = text,
          style = MaterialTheme.typography.labelLarge,
          color = style.ink,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * Di quanto il campo e' spostato a destra durante l'apertura ([progress] da 0 a 1), in pixel: il suo capo sinistro (la lente, il bordo e l'ombra veri del
 * vetro) sta sempre sul bordo sinistro della parte visibile, che va dal cerchio ([height]) a tutta la larghezza ([width]). A barra aperta zero.
 */
internal fun searchRevealHidden(width: Float, height: Float, progress: Float): Float = (width - height).coerceAtLeast(0f) * (1f - progress.coerceIn(0f, 1f))

/**
 * La rivelazione della barra (CG-2): il campo, largo quanto la riga fin dal primo fotogramma e spostato a destra di [searchRevealHidden], si vede solo fino al
 * bordo destro della riga, con il capo destro arrotondato come il vetro. A sinistra c'e' tutto: il capo sinistro e' quello vero, con il suo bordo e la
 * sua ombra. Il capo destro e' un ritaglio, e la sua ombra ([shadow], quella dei vetri sospesi dell'engine: sfocata di 20 dp, un sesto piu' in giu') si
 * disegna qui, cosi' la barra che cresce ha l'ombra tutt'intorno come un vetro intero (CG-2 del giro 2).
 *
 * Il ritaglio e l'ombra dipendono solo dalla misura: si fanno una volta per misura (`drawWithCache`), mai a ogni fotogramma della molla, e senza
 * operazioni di `Path` (due contorni nello stesso verso, l'unione la fa il riempimento). Nel disegno si legge solo [progress]. A barra aperta il ritaglio
 * e l'ombra disegnata qui non ci sono: c'e' il vetro intero con la sua ombra.
 */
private fun Modifier.searchReveal(progress: () -> Float, shadow: Color): Modifier = drawWithCache {
  val radius = size.height / 2f
  val capX = size.width - radius
  val blur = SearchCapShadowBlur.toPx()
  val drop = SearchCapShadowBlur.toPx() / SearchCapShadowDropFraction
  val reach = size.height * 4f + blur * 4f
  // Tutto cio' che sta a sinistra del centro del capo destro, piu' il capo destro.
  val clip = Path().apply {
    addRect(Rect(-size.width - reach, -reach, capX, size.height + reach))
    addOval(Rect(center = Offset(capX, radius), radius = radius))
  }
  // L'ombra del capo destro, registrata una volta: un cerchio sfocato un po' in giu', senza l'interno del capo (come il vetro) e senza la meta' sinistra
  // (li' c'e' quella vera del campo).
  val pad = blur * 2f
  val shadowSize = IntSize(ceil(radius * 2f + pad * 2f).toInt(), ceil(size.height + pad * 2f + drop).toInt())
  val center = Offset(pad + radius, pad + radius)
  val capShadow = obtainGraphicsLayer().apply {
    compositingStrategy = CompositingStrategy.Offscreen
    alpha = SearchCapShadowAlpha
    record(size = shadowSize) {
      drawIntoCanvas { canvas ->
        val paint = Paint().apply {
          color = shadow
          asFrameworkPaint().maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(center + Offset(0f, drop), radius, paint)
      }
      drawCircle(shadow, radius, center, blendMode = BlendMode.Clear)
      drawRect(shadow, topLeft = Offset.Zero, size = Size(center.x, shadowSize.height.toFloat()), blendMode = BlendMode.Clear)
    }
  }
  onDrawWithContent {
    if (progress() >= 1f) {
      drawContent()
      return@onDrawWithContent
    }
    clipPath(clip) { this@onDrawWithContent.drawContent() }
    translate(capX - radius - pad, -pad) { drawLayer(capShadow) }
  }
}

/**
 * Il segnaposto mentre la barra si apre o si chiude (CG-2 del giro 2): lo stesso testo, nello stesso punto e con lo stesso stile di quello del campo, che
 * compare in dissolvenza ([alpha], letta nel disegno) quando la barra e' quasi aperta, cosi' non si vede mai tagliato dal capo destro. A barra aperta lo
 * sostituisce il segnaposto del campo (gli stessi pixel), che TalkBack legge dentro il campo: questo per TalkBack non c'e'.
 */
@Composable
private fun RevealPlaceholder(text: String, alpha: () -> Float, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .graphicsLayer { this.alpha = alpha() }
      .padding(start = SearchFieldTextStart, end = PlaceholderEndInset)
      .clearAndSetSemantics { },
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Da quale frazione dell'apertura il segnaposto, che finisce a [textEnd] pixel dal bordo sinistro del campo largo [width] (il cerchio e' [circle]), non e'
 * piu' tagliato dal capo destro: la parte nascosta ([searchRevealHidden]) e' diventata piu' piccola dello spazio a destra del testo. Mai prima di
 * [PlaceholderFadeMin]: anche un segnaposto corto aspetta che la barra sia cresciuta.
 */
internal fun revealPlaceholderStart(width: Float, circle: Float, textEnd: Float): Float {
  val travel = (width - circle).coerceAtLeast(1f)
  val uncut = 1f - (width - textEnd) / travel
  return uncut.coerceIn(PlaceholderFadeMin, PlaceholderFadeEnd)
}

/** Il segnaposto durante la rivelazione: nascosto finche' sarebbe tagliato ([start]), poi in dissolvenza fino a barra quasi aperta. */
internal fun revealPlaceholderAlpha(progress: Float, start: Float): Float =
  if (start >= PlaceholderFadeEnd) {
    if (progress >= 1f) 1f else 0f
  } else {
    ((progress - start) / (PlaceholderFadeEnd - start)).coerceIn(0f, 1f)
  }

/** Il segnaposto scelto e quanto e' largo (in pixel), per sapere da quando la rivelazione non lo taglia piu'. */
private class FittedPlaceholder(val text: String, val width: Float)

/**
 * Il segnaposto del campo se entra intero fra la lente e la X (a campo vuoto la X sta sopra la fine del campo); altrimenti, col testo grande, [fallback].
 */
@Composable
private fun fittingPlaceholder(text: String, fallback: String, fieldWidth: Dp): FittedPlaceholder {
  val measurer = rememberTextMeasurer()
  val style = MaterialTheme.typography.bodyLarge
  val density = LocalDensity.current
  return remember(text, fallback, fieldWidth, style, density) {
    val available = with(density) { (fieldWidth - SearchFieldTextStart - PlaceholderEndInset).toPx() }
    val width = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toFloat()
    if (width <= available || text == fallback) {
      FittedPlaceholder(text, width)
    } else {
      FittedPlaceholder(fallback, measurer.measure(fallback, style, maxLines = 1, softWrap = false).size.width.toFloat())
    }
  }
}

/** La X che chiude la ricerca, a campo vuoto (CU-12): nello stesso punto della crocetta del campo, che c'e' solo con del testo. */
@Composable
private fun SearchCloseButton(visible: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = fadeIn(FluidMotion.fadeIn(SwapInMillis)) + scaleIn(FluidMotion.snappy(), initialScale = SwapScale),
    exit = fadeOut(FluidMotion.fadeOut(SwapOutMillis)) + scaleOut(FluidMotion.fadeOut(SwapOutMillis), targetScale = SwapScale),
  ) {
    FluidCapsuleAction(
      icon = Icons.Rounded.Close,
      contentDescription = label,
      onClick = onClick,
      tint = MaterialTheme.colorScheme.onSurface,
      size = FluidSearchFieldDefaults.ActionSize,
    )
  }
}

/** Dove comincia il testo del campo: il margine della lente, la lente e lo spazio dopo (come li mette `FluidSearchField`). */
private val SearchFieldTextStart: Dp = FluidSearchFieldDefaults.IconInset + FluidSearchFieldDefaults.IconSize + 10.dp

/** Fra la fine del segnaposto e la X. */
private val SearchFieldTextGap: Dp = 4.dp

/** L'ombra dei vetri sospesi dell'engine (`GlassRole.Floating`): sfocata di 20 dp, spostata in giu' di un sesto, al 70%. */
private val SearchCapShadowBlur: Dp = 20.dp
private const val SearchCapShadowDropFraction = 6f
private const val SearchCapShadowAlpha = 0.7f

/** Il colore dell'ombra dei vetri dell'engine: nero al 16% su una pagina chiara, al 10% su una scura (`glassShadowColor`). */
private const val SearchCapShadowLight = 0.16f
private const val SearchCapShadowDark = 0.10f

/** Il segnaposto della rivelazione non compare prima di questa frazione dell'apertura, e e' del tutto visibile a [PlaceholderFadeEnd]. */
private const val PlaceholderFadeMin = 0.6f
private const val PlaceholderFadeEnd = 0.99f

/** Lo spazio a destra del segnaposto, dove non arriva mai (la X, e lo stacco prima di lei: [fittingPlaceholder]). */
internal val PlaceholderEndInset: Dp get() = FluidSearchFieldDefaults.ActionSize + SearchFieldTextGap

/** Lo scambio fra la crocetta del campo e la X: come la crocetta di `FluidSearchField`. */
private const val SwapInMillis = 140
private const val SwapOutMillis = 120
private const val SwapScale = 0.6f

/** La parte dell'apertura in cui il cerchio del campo prende il posto di quello della pillola. */
private const val CircleCrossfade = 0.2f

/** La pillola mentre la barra si apre ([progress] da 0 a 1): sparisce entro meta' apertura. */
fun gallerySearchPillAlpha(progress: Float): Float = (1f - progress / PillFadeEnd).coerceIn(0f, 1f)

/** La pillola si raccoglie verso il cerchio mentre sparisce. */
fun gallerySearchPillScale(progress: Float): Float = lerp(1f, PillMinScale, progress.coerceIn(0f, 1f))

private const val PillFadeEnd = 0.5f
private const val PillMinScale = 0.92f
