package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.LocalFluidPressImmediate
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto

// La chrome delle griglie della galleria, "vetro adattivo" (direzione C scelta dall'utente, G2, docs/grafica-progetto.md
// §4, §11, §12): pochi pezzi, tutti parametrizzati da uno stile (pellicola + inchiostro) che chi li posa sceglie dal
// contenuto che passa sotto. Le regole che valgono per tutti:
//  - il vetro va sul contenitore: un gruppo di tasti e' UNA capsula, i tasti dentro sono solo bersagli;
//  - ogni bersaglio e' di almeno 48 dp;
//  - il testo non si sfuma mai (niente alfa sull'inchiostro): la gerarchia la fanno taglia e peso;
//  - i pezzi sono sotto i 64 dp di lato corto: la regola della taglia dell'engine non li tocca;
//  - l'etichetta e la capsula di una riga hanno la stessa altezza e la stessa sagoma (capsula): due oggetti di vetro
//    gemelli, non un cartellino accanto a un bottone.

/** Lo stile di un pezzo di chrome: la pellicola del vetro ([tint]) e l'inchiostro di testo e icone. */
@Immutable
data class GalleryChromeStyle(
  val tint: GlassTint,
  val ink: Color,
  val destructiveInk: Color,
)

/** L'inchiostro della chrome in cui ci si trova (i tasti dentro una capsula lo leggono da qui). */
val LocalGalleryChromeInk = compositionLocalOf<Color?> { null }

/** Lo stile del vetro adattivo e la sua tavolozza. */
object GalleryChromeStyles {

  /** Pellicola e inchiostro scelti da [GalleryLegibility.adapt] per il contenuto che c'e' sotto. */
  @Composable
  fun adaptive(choice: AdaptiveInk): GalleryChromeStyle {
    val scheme = MaterialTheme.colorScheme
    val worst = ContentRange(listOf(if (choice.darkInk) scheme.scrim else GalleryOnPhoto.Content))
    return GalleryChromeStyle(
      tint = GlassTint(
        overlay = choice.film,
        fallback = choice.film.copy(alpha = maxOf(choice.film.alpha, FallbackAlpha)),
        hairline = choice.ink.copy(alpha = AdaptiveHairline),
      ),
      ink = choice.ink,
      destructiveInk = legibleTowards(scheme.error, choice.ink, choice.film, worst, GalleryLegibility.LargeContrast),
    )
  }

  /** La tavolozza del vetro adattivo: inchiostro quasi nero o bianco, pellicola bianca o nera, opacita' fra 0,45 e 0,86. */
  @Composable
  fun adaptivePalette(): AdaptivePalette {
    val scrim = MaterialTheme.colorScheme.scrim
    return remember(scrim) { adaptivePaletteFor(scrim) }
  }

  /** [adaptivePalette] fuori dalla composizione, dal ruolo `scrim` dello schema (nero): per i test e per chi calcola. */
  fun adaptivePaletteFor(scrim: Color): AdaptivePalette = AdaptivePalette(
    darkInk = lerp(scrim, GalleryOnPhoto.Content, DarkInkLift),
    lightInk = GalleryOnPhoto.Content,
    lightFilm = GalleryOnPhoto.Content,
    darkFilm = scrim,
    floor = AdaptiveFloor,
    ceiling = AdaptiveCeiling,
  )

  /**
   * [color] portato verso [pole] del minimo che serve per [target] sopra [film] sul caso peggiore di [range]: un rosso
   * distruttivo resta riconoscibile e si legge anche su una pellicola scura (la regola di `legibleInk` dell'engine).
   */
  fun legibleTowards(color: Color, pole: Color, film: Color, range: ContentRange, target: Float): Color {
    if (GalleryLegibility.worstContrast(color, film, range) >= target) return color
    var low = 0f
    var high = 1f
    repeat(SearchSteps) {
      val mid = (low + high) / 2f
      if (GalleryLegibility.worstContrast(lerp(color, pole, mid), film, range) >= target) high = mid else low = mid
    }
    return lerp(color, pole, high)
  }

  private const val FallbackAlpha = 0.94f
  private const val AdaptiveHairline = 0.16f
  private const val DarkInkLift = 0.08f
  /**
   * La pellicola minima del vetro adattivo. In G1 era 0,30; in G2 sale a 0,45 perche' il calcolo stima il contenuto dai
   * ThumbHash, che non vedono i dettagli fini (le righe sottili, il testo degli screenshot), e la sfocatura del vetro
   * (4,5 dp) non li media del tutto: misurato sull'emulatore con 0,30, un'icona scura sopra una fila di righe bianche e
   * nere scendeva a 2,85:1. Con 0,45 il vetro resta vetro (passa piu' di meta' della foto) e il caso peggiore regge.
   */
  private const val AdaptiveFloor = 0.45f
  private const val AdaptiveCeiling = 0.86f
  private const val SearchSteps = 14
}

/**
 * Una **capsula di chrome**: un solo pezzo di vetro con dentro uno o piu' tasti. Alta [GalleryChromeDefaults.PieceHeight],
 * piu' del bersaglio di tocco: i tasti dentro non escono mai dai bordi, quindi due capsule vicine non si compenetrano.
 *
 * Dentro, la pressione e' immediata (`LocalFluidPressImmediate`): una capsula non sta dentro qualcosa che scorre.
 */
@Composable
fun GalleryChromeCapsule(
  style: GalleryChromeStyle,
  backdrop: GlassBackdropState?,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  CompositionLocalProvider(LocalGalleryChromeInk provides style.ink, LocalFluidPressImmediate provides true) {
    Row(
      modifier = modifier
        .height(chromePieceHeight())
        .chromeGlass(style, backdrop)
        .padding(horizontal = GalleryChromeDefaults.CapsulePadding),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GalleryChromeDefaults.CapsuleGap),
      content = content,
    )
  }
}

/**
 * L'**etichetta** della chrome: la capsula di vetro gemella dei comandi, con il titolo (la data della sezione in vista, o
 * il nome della pagina) e sotto, piu' piccolo, il luogo o il riepilogo. Non si tocca: e' un'intestazione per TalkBack.
 * Cresce con il testo di sistema (nessuna altezza fissa), le righe si troncano: chi la posa le da' un peso nella riga,
 * cosi' non arriva mai sotto i tasti.
 */
@Composable
fun GalleryChromeLabel(
  title: String,
  subtitle: String?,
  style: GalleryChromeStyle,
  backdrop: GlassBackdropState?,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .height(chromePieceHeight())
      .chromeGlass(style, backdrop)
      .padding(horizontal = GalleryChromeDefaults.LabelPaddingH),
    contentAlignment = Alignment.CenterStart,
  ) {
    GalleryChromeTitle(title = title, subtitle = subtitle, ink = style.ink)
  }
}

/**
 * L'altezza dei pezzi di una riga di chrome: quella dell'etichetta **a due righe** (titolo e luogo) a questa scala del
 * testo, e mai meno di [GalleryChromeDefaults.PieceHeight]. E' fissa e non dipende da che cosa l'etichetta dice: un'etichetta
 * che crescesse quando la sezione ha un luogo sposterebbe la linea su cui si legge la sezione, e un'altra sezione senza
 * luogo la farebbe tornare indietro (un'altalena, vista con il testo a 1,3x). Etichetta e capsula sono gemelle anche con
 * il testo grande, e chi le posa conosce l'altezza della riga senza misurarla ([chromeRowHeight]).
 */
@Composable
fun chromePieceHeight(): Dp {
  val typography = MaterialTheme.typography
  val lines = with(LocalDensity.current) { typography.titleSmall.lineHeight.toDp() + typography.bodySmall.lineHeight.toDp() }
  return maxOf(GalleryChromeDefaults.PieceHeight, lines + GalleryChromeDefaults.LabelPaddingV * 2)
}

/** L'altezza della riga di chrome in alto (sotto la barra di stato): i pezzi e il loro respiro, mai meno di 60 dp. */
@Composable
fun chromeRowHeight(): Dp = maxOf(GalleryChromeDefaults.TopRowHeight, chromePieceHeight() + GalleryChromeDefaults.RowBreath * 2)

/** Il materiale di un pezzo di chrome: vetro sul fondale della pagina, o la pellicola piena se il fondale non c'e'. */
@Composable
private fun Modifier.chromeGlass(style: GalleryChromeStyle, backdrop: GlassBackdropState?): Modifier =
  if (backdrop != null) {
    glassSurface(state = backdrop, tint = style.tint, shape = FluidCapsuleShape, role = GlassRole.Floating)
  } else {
    background(style.tint.fallback, FluidCapsuleShape)
  }

/** Un tasto a icona dentro una capsula (o una barra): 48 x 48 dp, l'inchiostro della chrome. */
@Composable
fun GalleryChromeIconButton(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  tint: Color? = null,
) {
  val ink = tint ?: LocalGalleryChromeInk.current ?: MaterialTheme.colorScheme.onSurface
  Box(
    modifier = modifier
      .size(GalleryDimens.MinTouchTarget)
      .semantics { this.contentDescription = contentDescription }
      .fluidPressable(onClick = onClick, enabled = enabled, role = Role.Button, focusShape = FluidCapsuleShape)
      .alpha(if (enabled) 1f else DisabledAlpha),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(GalleryChromeDefaults.Glyph))
  }
}

/** Un tasto di testo dentro una capsula ("Fine", "Seleziona tutto"): alto 48 dp, l'etichetta in grassetto leggero. */
@Composable
fun GalleryChromeTextButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leading: ImageVector? = null,
  trailing: ImageVector? = null,
  contentDescription: String? = null,
) {
  val ink = LocalGalleryChromeInk.current ?: MaterialTheme.colorScheme.onSurface
  Row(
    modifier = modifier
      .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
      .then(if (contentDescription != null) Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription } else Modifier)
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape)
      .padding(horizontal = GalleryChromeDefaults.TextButtonPadding),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GalleryChromeDefaults.LeadingGap),
  ) {
    if (leading != null) Icon(imageVector = leading, contentDescription = null, tint = ink, modifier = Modifier.size(GalleryChromeDefaults.SmallGlyph))
    Text(text = label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    if (trailing != null) Icon(imageVector = trailing, contentDescription = null, tint = ink, modifier = Modifier.size(GalleryChromeDefaults.SmallGlyph))
  }
}

/**
 * Il testo dell'etichetta: il titolo in grassetto (15 sp) e sotto il luogo (13 sp, medio). Una riga ciascuno, troncati.
 * L'inchiostro e' pieno per tutte e due le righe: la gerarchia la fanno taglia e peso, mai l'alfa.
 */
@Composable
internal fun GalleryChromeTitle(title: String, subtitle: String?, ink: Color, modifier: Modifier = Modifier) {
  Column(modifier = modifier.semantics(mergeDescendants = true) { heading() }) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
      color = ink,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Una **sfumatura di bordo** (lo "scroll edge effect"): un colore che sfuma lungo l'altezza, alle opacita' [stops]
 * (distanza dal bordo alto, opacita'). Costa un rettangolo disegnato, niente layer, niente sfocatura: e' cio' che separa
 * la chrome dal contenuto al posto di una barra di vetro a tutta larghezza.
 */
@Composable
fun GalleryEdgeScrim(color: Color, stops: List<Pair<Dp, Float>>, modifier: Modifier = Modifier) {
  Spacer(
    modifier = modifier.drawBehind {
      val height = size.height.coerceAtLeast(1f)
      val colorStops = stops.map { (at, alpha) -> (at.toPx() / height).coerceIn(0f, 1f) to color.copy(alpha = alpha) }.toTypedArray()
      drawRect(Brush.verticalGradient(colorStops = colorStops, startY = 0f, endY = height))
    },
  )
}

/**
 * Un ancoraggio di menu: [trigger] riceve l'azione che apre il pop-up dell'engine **sul tasto stesso** (cresce dal suo
 * rettangolo), con le voci di [GalleryMenuPanel]. Senza un `FluidGlassModalHost` nello scope il tocco non apre niente.
 *
 * Dentro il pop-up, sotto le voci, un velo del colore della pagina **quanto basta** per 4,5:1 sul caso peggiore
 * ([menuVeil], calcolato con il margine di [GalleryChromeDefaults.MeasuredTarget]): nel tema scuro il vetro dei modali
 * dell'engine (pellicola grigia al 50%) sopra una foto bianca lascia il testo chiaro a 3,2:1, misurato sull'emulatore.
 * Nel tema chiaro il velo e' minimo o nullo.
 */
@Composable
fun GalleryChromeMenuAnchor(
  entries: List<GalleryMenuEntry>,
  paneTitle: String,
  modifier: Modifier = Modifier,
  trigger: @Composable (open: () -> Unit) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  var bounds by remember { mutableStateOf<Rect?>(null) }
  val veil = menuVeil()
  Box(modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) { trigger { open = true } }
  FluidGlassModalPortal(
    visible = open,
    onDismissRequest = { open = false },
    origin = { bounds },
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = paneTitle,
  ) {
    // Il menu della vista e' lungo (i livelli, i filtri, lo stile, le Impostazioni; in debug le librerie di prova): se il
    // pop-up non ci sta, le voci scorrono invece di restare tagliate sotto il bordo (G2, visto sull'emulatore).
    Box(modifier = Modifier.background(veil).verticalScroll(rememberScrollState())) {
      GalleryMenuPanel(entries = entries, onItemSelected = { open = false })
    }
  }
}

/**
 * Il velo minimo sotto le voci di un pop-up perche' `onSurface` regga 4,5:1 sopra il bianco e il nero pieni, contando la
 * pellicola dei modali (`GlassDefaults.modalTint`) e lo scrim che l'engine stende sotto (nero al 12% in chiaro, al 20% in
 * scuro, i valori della 2.13.0). La sfocatura del pop-up media il fondo e aiuta: qui non la si conta, per stare larghi.
 */
@Composable
private fun menuVeil(): Color {
  val scheme = MaterialTheme.colorScheme
  val film = GlassDefaults.modalTint().overlay
  val dark = GlassDefaults.isDarkSurface()
  return remember(scheme, film, dark) {
    val scrim = scheme.scrim.copy(alpha = if (dark) ModalScrimDark else ModalScrimLight)
    val under = listOf(GalleryOnPhoto.Content, scheme.scrim).map { film.compositeOver(scrim.compositeOver(it)) }
    val alpha = GalleryLegibility.minimalFilmAlpha(scheme.onSurface, scheme.surface, ContentRange(under), GalleryChromeDefaults.MeasuredTarget, 0f, MenuVeilCeiling)
    scheme.surface.copy(alpha = alpha)
  }
}

private const val ModalScrimLight = 0.12f
private const val ModalScrimDark = 0.20f
private const val MenuVeilCeiling = 0.9f

/** Le misure della chrome delle griglie. */
object GalleryChromeDefaults {
  /**
   * L'altezza dei pezzi di una riga di chrome (etichetta e capsula): quella dell'etichetta a due righe (titolo 15 sp e
   * luogo 13 sp con il loro respiro), cosi' etichetta e comandi sono gemelli. I tasti dentro sono di 48 dp.
   */
  val PieceHeight: Dp = 52.dp

  /** Lo spazio interno ai lati di una capsula: i tasti a icona restano tondi dentro la sagoma. */
  val CapsulePadding: Dp = 2.dp

  /** Fra due tasti nella stessa capsula: niente, sono bersagli contigui. */
  val CapsuleGap: Dp = 0.dp

  /** Il respiro del testo nell'etichetta: ai lati almeno mezzo raggio della capsula, cosi' il testo non tocca la curva. */
  val LabelPaddingH: Dp = 18.dp
  val LabelPaddingV: Dp = 6.dp

  val TextButtonPadding: Dp = 14.dp
  val LeadingGap: Dp = 6.dp
  val Glyph: Dp = 22.dp
  val SmallGlyph: Dp = 18.dp

  /** La riga dei controlli in alto, sotto la barra di stato (cresce col testo grande: [chromeRowHeight]). */
  val TopRowHeight: Dp = 60.dp

  /** Il respiro sopra e sotto i pezzi della riga in alto. */
  val RowBreath: Dp = 4.dp

  /** Il margine laterale della chrome flottante. */
  val EdgeMargin: Dp = 12.dp

  /** Fra due pezzi di chrome vicini. */
  val Gap: Dp = 8.dp

  /**
   * La soglia con cui si calcolano i veli sotto il vetro **dell'engine** (pop-up, pillola): 4,5 piu' un margine di 1.
   * La pellicola che l'engine disegna davvero non e' solo la sua tinta (lente, riflesso, vibrancy la schiariscono o la
   * scuriscono): misurato sull'emulatore, un velo calcolato a 4,5 si fermava a 4,4. Il margine copre quella differenza.
   */
  const val MeasuredTarget: Float = 5.5f
}

/** Il tag di test della chrome: i pezzi che il test dei bordi e gli strumenti di misura cercano portano `chrome-<nome>`. */
fun Modifier.galleryChromeTag(name: String): Modifier = testTag("$GalleryChromeTagPrefix$name")

/** Il prefisso dei tag della chrome (G2; in G1 era `g1-chrome-`). */
const val GalleryChromeTagPrefix: String = "chrome-"

private const val DisabledAlpha = 0.38f
