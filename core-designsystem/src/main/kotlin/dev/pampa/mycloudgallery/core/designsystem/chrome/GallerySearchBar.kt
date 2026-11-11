package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
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
 *   dal cerchio alla sua posizione. Con [focus] prende il fuoco e la tastiera sale; [onFocused] consuma la richiesta (tornando dal viewer non risale).
 * - **Tastiera**: sale con lei ([lift], letto nel layout).
 * - **Chiusura**: la X e «indietro» chiamano [onClose] (la crocetta piccola dentro il campo svuota il testo, come in Foto di Apple); il campo torna
 *   cerchio verso la pillola e la tastiera scende con lui.
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
  // Il campo nasce chiuso (un cerchio) e si apre al fotogramma dopo: senza, `FluidSearchField` comparirebbe gia' aperto, senza il gesto. Lo stato sta
  // fuori ([state]): la barra che rientra dopo una selezione (la barra delle azioni le aveva preso il posto) e' gia' aperta, non rinasce dal cerchio.
  var expanded by state::expanded
  LaunchedEffect(open) {
    if (open && !expanded) state.start = circle.centerXInWindow()
    expanded = open
  }
  val currentOnCollapsed by rememberUpdatedState(onCollapsed)
  // La stessa molla del campo, partita nello stesso fotogramma: la larghezza (dell'engine) e lo scorrimento (qui) vanno insieme.
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
      Box(
        modifier = Modifier.fillMaxWidth().onPlaced { rightEdge = it.positionInWindow().x + it.size.width },
        contentAlignment = Alignment.CenterEnd,
      ) {
        val field = backdrop ?: currentGlassBackdrop()
        FluidSearchField(
          query = query,
          onQueryChange = onQueryChange,
          modifier = Modifier
            .graphicsLayer {
              val p = progress.value
              // Il cerchio del campo sostituisce quello della pillola nel primo quinto dell'apertura (e se ne va per ultimo chiudendo).
              alpha = (p / CircleCrossfade).coerceIn(0f, 1f)
              val from = start
              val edge = rightEdge
              translationX = if (from != null && edge != null) lerp(from - (edge - FluidSearchFieldDefaults.Height.toPx() / 2f), 0f, p) else 0f
            }
            .onFocusChanged { onFocusChanged(it.hasFocus) },
          backdrop = field,
          placeholder = placeholder,
          labels = labels,
          onSearch = {
            onSearch()
            keyboard?.hide()
          },
          expanded = expanded,
          onExpandedChange = { if (!it) onClose() },
          autoFocus = focus,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
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

/** La parte dell'apertura in cui il cerchio del campo prende il posto di quello della pillola. */
private const val CircleCrossfade = 0.2f

/** La pillola mentre la barra si apre ([progress] da 0 a 1): sparisce entro meta' apertura. */
fun gallerySearchPillAlpha(progress: Float): Float = (1f - progress / PillFadeEnd).coerceIn(0f, 1f)

/** La pillola si raccoglie verso il cerchio mentre sparisce. */
fun gallerySearchPillScale(progress: Float): Float = lerp(1f, PillMinScale, progress.coerceIn(0f, 1f))

private const val PillFadeEnd = 0.5f
private const val PillMinScale = 0.92f
