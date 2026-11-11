package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCapsule
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.LocalGalleryChromeInk
import dev.pampa.mycloudgallery.core.designsystem.design.chromeRowHeight
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.core.designsystem.design.rememberAnyContentChromeStyle

// LA BARRA DEI SUGGERIMENTI (H4 della fase H, 11/11; I2 della fase I). Con la ricerca aperta, al posto della data, della nuvola e degli altri pezzi in alto
// c'e' una sola barra con i primi tre suggerimenti, «come la barra delle parole suggerite quando scrivi sulla tastiera, pero' nell'app sopra» (parole
// dell'utente), su un solo pezzo di vetro della chrome (lo stesso linguaggio dei pezzi in alto: cambia il contenuto, non il materiale). Un tocco applica la
// voce alla ricerca. Le voci si mostrano intere ([SuggestionFit]).
// I2 (11/11 sera, «qualche esempio con una foto d'anteprima»): ogni voce e' una **capsula** con davanti un **tondo** ([leading]: la foto del suo primo
// risultato, un volto, o il segno del suo tipo) e l'etichetta corta accanto. La capsula e il tondo dicono che si tocca anche quando la voce e' una sola (non
// sembra un titolo); le capsule sono larghe quanto il loro contenuto e stanno al centro della barra.

/** Le misure delle voci della barra dei suggerimenti (I2). */
object GallerySuggestionBarDefaults {
  /**
   * Il tondo dell'anteprima di una voce. I conti (CU-2 con il tondo): a 360 dp ogni capsula ha 48 dp fissi (margine, tondo, stacco, margine) e tre voci
   * corte («mare ad agosto», «Roma», «Video») restano intere alla seconda misura del testo; con voci piu' lunghe se ne vedono due, intere.
   */
  val DiscSize: Dp = 28.dp

  /** Fra il bordo sinistro della capsula e il tondo (il tondo sta dentro la capsula come in una pillola di Foto). */
  val DiscInset: Dp = 4.dp

  /** Fra il tondo e l'etichetta. */
  val DiscGap: Dp = 6.dp

  /** Il margine della capsula a destra dell'etichetta (e a sinistra, senza tondo). */
  val TextPadding: Dp = 10.dp

  /** Fra due capsule. */
  val PillGap: Dp = 4.dp

  /** Il respiro sopra e sotto una capsula, dentro il vetro della barra. */
  val PillInset: Dp = 6.dp
}

/**
 * La barra dei suggerimenti nella riga in alto (sotto la barra di stato, alta quanto la riga della chrome). [labels]: le voci, al piu' tre; vuota, la barra
 * si ritira (in dissolvenza, salendo appena: niente pezzo di vetro vuoto). [descriptions]: cio' che TalkBack dice di ogni voce (di serie la voce stessa).
 * [leading]: il tondo davanti alla voce di posto `index` (lato [GallerySuggestionBarDefaults.DiscSize], gia' ritagliato tondo); null = voci di solo testo.
 *
 * Il vetro e' largo quanto la riga (al piu' [GallerySearchBarDefaults.MaxWidth], centrato) e **non cambia mai misura**: quando le voci cambiano mentre si
 * scrive cambia solo il contenuto, in dissolvenza. Trattiene i tocchi come ogni capsula della chrome (fase H, D2: fra due voci la foto dietro non si apre).
 */
@Composable
fun GallerySuggestionBar(
  labels: List<String>,
  onPick: (Int) -> Unit,
  backdrop: GlassBackdropState?,
  modifier: Modifier = Modifier,
  descriptions: List<String>? = null,
  paneTitle: String? = null,
  leading: (@Composable (index: Int) -> Unit)? = null,
) {
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  // Le voci che si vedono anche mentre la barra si ritira (sono quelle di prima, non un vetro vuoto).
  val shown = remember { mutableStateOf(labels to descriptions) }
  if (labels.isNotEmpty() && (labels to descriptions) != shown.value) shown.value = labels to descriptions
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = statusBar)
      .height(chromeRowHeight())
      .padding(horizontal = GalleryChromeDefaults.EdgeMargin),
    contentAlignment = Alignment.Center,
  ) {
    AnimatedVisibility(
      visible = labels.isNotEmpty(),
      enter = fadeIn(FluidMotion.fadeIn()) + if (reduced) fadeIn(FluidMotion.fadeIn()) else slideInVertically(FluidMotion.snappy()) { -it / SlideFraction },
      exit = fadeOut(FluidMotion.fadeOut()) + if (reduced) fadeOut(FluidMotion.fadeOut()) else slideOutVertically(FluidMotion.snappy()) { -it / SlideFraction },
    ) {
      val (current, spoken) = shown.value
      GalleryChromeCapsule(
        style = rememberAnyContentChromeStyle(),
        backdrop = backdrop,
        modifier = Modifier
          .widthIn(max = GallerySearchBarDefaults.MaxWidth)
          .fillMaxWidth()
          .semantics {
            isTraversalGroup = true
            if (paneTitle != null) this.paneTitle = paneTitle
          }
          .galleryChromeTag("suggerimenti"),
      ) {
        Crossfade(
          targetState = current to spoken,
          animationSpec = FluidMotion.crossFade(),
          modifier = Modifier.weight(1f),
          label = "suggerimenti",
        ) { (words, said) ->
          SuggestionRow(words, said, onPick, leading)
        }
      }
    }
  }
}

/**
 * Le voci affiancate, ognuna una capsula (I2) con il suo tondo davanti, al centro della barra. CU-2 (giri 1 e 2): **voci intere**, come la barra delle
 * parole della tastiera. Il testo si rimpicciolisce fino all'etichetta piccola del tema; si mostrano le voci che entrano intere (fino a tre: due intere sono
 * meglio di tre mozzate) e, se nemmeno una entra, una sola con i puntini in coda ([SuggestionFit]): con il tondo ogni voce ha meno posto, e il conto lo sa
 * (il tondo, i margini della capsula e lo stacco fra due capsule sono lo spazio fisso di ogni voce). TalkBack dice sempre la voce intera.
 */
@Composable
private fun SuggestionRow(labels: List<String>, descriptions: List<String>?, onPick: (Int) -> Unit, leading: (@Composable (Int) -> Unit)?) {
  val ink = LocalGalleryChromeInk.current ?: MaterialTheme.colorScheme.onSurface
  val typography = MaterialTheme.typography
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val withDisc = leading != null
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val rowWidth = constraints.maxWidth
    val styles = remember(typography) { suggestionStyles(typography.labelLarge, typography.labelMedium) }
    val fit = remember(labels, rowWidth, density, styles, withDisc) {
      val fixed = with(density) { pillFixedWidth(withDisc).toPx() }
      val gap = with(density) { GallerySuggestionBarDefaults.PillGap.toPx() }
      SuggestionFit.layout(labels, rowWidth.toFloat(), fixed, gap, styles.size) { text, step ->
        measurer.measure(text, styles[step], maxLines = 1, softWrap = false).size.width.toFloat()
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth().fillMaxHeight(),
      horizontalArrangement = Arrangement.spacedBy(GallerySuggestionBarDefaults.PillGap, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      fit.shown.forEach { shown ->
        val description = descriptions?.getOrNull(shown.index) ?: labels[shown.index]
        SuggestionPill(
          text = shown.text,
          style = styles[fit.step],
          ink = ink,
          description = description,
          onClick = { onPick(shown.index) },
          disc = leading?.let { slot -> { slot(shown.index) } },
        )
      }
    }
  }
}

/** Lo spazio fisso di una capsula, oltre all'etichetta: il tondo con i suoi margini (se c'e') e il margine dopo il testo. */
internal fun pillFixedWidth(withDisc: Boolean): Dp = with(GallerySuggestionBarDefaults) {
  if (withDisc) DiscInset + DiscSize + DiscGap + TextPadding else TextPadding * 2
}

/**
 * Una voce: una capsula con un velo leggero dell'inchiostro della chrome (si legge come un tasto su qualunque foto dietro il vetro), il tondo davanti e
 * l'etichetta. Il bersaglio del tocco e' alto quanto la barra (48 dp e piu').
 */
@Composable
private fun SuggestionPill(text: String, style: TextStyle, ink: androidx.compose.ui.graphics.Color, description: String, onClick: () -> Unit, disc: (@Composable () -> Unit)?) {
  Box(
    modifier = Modifier
      .fillMaxHeight()
      .semantics { contentDescription = description }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape)
      .padding(vertical = GallerySuggestionBarDefaults.PillInset),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .fillMaxHeight()
        .background(ink.copy(alpha = PillVeilAlpha), FluidCapsuleShape)
        .padding(
          start = if (disc != null) GallerySuggestionBarDefaults.DiscInset else GallerySuggestionBarDefaults.TextPadding,
          end = GallerySuggestionBarDefaults.TextPadding,
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (disc != null) {
        Box(Modifier.size(GallerySuggestionBarDefaults.DiscSize).clip(FluidCapsuleShape)) { disc() }
        Spacer(Modifier.width(GallerySuggestionBarDefaults.DiscGap))
      }
      Text(
        text = text,
        style = style,
        color = ink,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Start,
      )
    }
  }
}

/**
 * Gli stili del testo delle voci, dal piu' grande al piu' piccolo: quello della chrome ([largest]) e, un passo alla volta, fino a [smallest], l'etichetta
 * piccola del tema, ancora leggibile (come la barra dei suggerimenti della tastiera). Niente misure scritte a mano: i passi stanno fra i due stili.
 */
private fun suggestionStyles(largest: TextStyle, smallest: TextStyle): List<TextStyle> =
  List(SizeSteps) { step -> lerp(largest, smallest, step / (SizeSteps - 1f)) }

/** Quante misure del testo si provano prima di accorciare (15, 14 e 13 punti con gli stili della chrome). */
private const val SizeSteps = 3

/** Il velo di una capsula: l'inchiostro della chrome appena accennato (abbastanza per vedere la forma, poco per non spegnere il vetro). */
private const val PillVeilAlpha = 0.10f

/** Di quanto (una frazione della sua altezza) la barra sale ritirandosi. */
private const val SlideFraction = 3
