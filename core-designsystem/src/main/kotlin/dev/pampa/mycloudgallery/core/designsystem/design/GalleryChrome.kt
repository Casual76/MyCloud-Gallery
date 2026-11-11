package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.a11y.LocalGalleryAccessibility
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.pampa.mycloudgallery.core.designsystem.a11y.gallerySolidSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuLegibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import kotlinx.coroutines.delay

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

/**
 * Lo stile intero della capsula in cui ci si trova (B2, beta): la nuvola di stato ci legge la pellicola per portare il colore del suo
 * tono a 3:1 sopra qualunque foto. Null fuori da una [GalleryChromeCapsule].
 */
val LocalGalleryChromeStyle = compositionLocalOf<GalleryChromeStyle?> { null }

/** Lo stile del vetro adattivo e la sua tavolozza. */
object GalleryChromeStyles {

  /** Pellicola e inchiostro scelti da [GalleryLegibility.adapt] per il contenuto che c'e' sotto. */
  @Composable
  fun adaptive(choice: AdaptiveInk): GalleryChromeStyle = adaptiveFor(choice, MaterialTheme.colorScheme)

  /** [adaptive] fuori dalla composizione, da uno schema: per i test dei contrasti (la nuvola, B7 della beta) e per chi calcola. */
  fun adaptiveFor(choice: AdaptiveInk, scheme: ColorScheme): GalleryChromeStyle {
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

  /** Il colore che si legge meglio sopra [fill] fra i due poli della chrome (il nero di [scrim] e il bianco delle foto). */
  fun onTone(fill: Color, scrim: Color): Color {
    val dark = scrim.copy(alpha = 1f)
    return if (GalleryLegibility.contrast(dark, fill) >= GalleryLegibility.contrast(GalleryOnPhoto.Content, fill)) dark else GalleryOnPhoto.Content
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
 * Dal Fluid Engine 3.0 ogni pulsante e' premuto al contatto (la capsula non chiede piu' `LocalFluidPressImmediate`, che non fa niente).
 */
@Composable
fun GalleryChromeCapsule(
  style: GalleryChromeStyle,
  backdrop: GlassBackdropState?,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  CompositionLocalProvider(LocalGalleryChromeInk provides style.ink, LocalGalleryChromeStyle provides style) {
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
 *
 * **Il vetro si muove, non salta** (B1 della beta, lista dell'utente del 31/10): quando il testo cambia (la data scorrendo,
 * "3 selezionati" entrando in selezione) la capsula cambia larghezza con la molla di `FluidMotion` e il testo passa in
 * dissolvenza incrociata. Scorrendo veloce non trema, per due regole:
 *  - **un ritmo**: il testo nuovo si mostra subito, ma poi al piu' uno ogni [GalleryChromeDefaults.LabelPaceMillis] (sempre
 *    l'ultimo arrivato): una data che cambia venti volte al secondo non si legge comunque, e venti dissolvenze sovrapposte sono
 *    solo rumore e lavoro;
 *  - **una soglia**: dentro una raffica di cambi (un cambio a meno di [GalleryChromeDefaults.LabelSettleMillis] dal precedente) la
 *    capsula cresce se serve ma non si stringe; si stringe sul testo vero solo quando il testo e' fermo da tanto. Un cambio
 *    **isolato** (entrando in selezione il testo diventa "3 selezionati") non aspetta: la capsula si muove insieme al testo (GF-6).
 * Con le animazioni ridotte il testo cambia di colpo e la capsula prende subito la sua misura.
 *
 * **Mentre il contenuto sotto si muove per un gesto** ([contentMoving]: un pinch fra i livelli, uno scorrimento, lo scrubber) la larghezza
 * **non si anima**: la capsula prende subito la misura del testo nuovo, e il testo passa lo stesso in dissolvenza (P2, S25, 10/11). Il
 * vetro sta sul nodo che cambia misura, e ogni fotogramma della molla sono tre superfici nuove sulla GPU, che scacciano le miniature
 * dalla cache del RenderThread proprio mentre la griglia ne ha bisogno. Da fermi (entrando in selezione, cambiando filtro, e quando la
 * capsula si stringe a gesto finito) la molla c'e'.
 *
 * **La data non finisce con i puntini se una forma piu' corta entra** (GF-2, review della beta: sul telefono a 360 dp, con quattro
 * tasti nella capsula, restano 96 dp di testo). [shorterTitles] sono le forme piu' corte di [title], dalla piu' lunga alla piu'
 * stretta («Mer 20 nov 2024», «20 nov 2024»...): l'etichetta mostra la prima che entra nella larghezza che la riga le lascia. La
 * scelta si fa **al layout**, quando la larghezza e' nota, quindi vale dal primo fotogramma; ogni forma si misura una volta sola per
 * titolo ([ChromeTitleFit]), e mentre la capsula accanto cambia larghezza la scelta e' un confronto fra interi. TalkBack legge
 * sempre [title] per esteso.
 *
 * **Un conteggio non si taglia a meta'** (GF-22, review della beta: in cima alla Libreria, a 360 dp con quattro tasti, «1.817 foto, 18…»). Con
 * [shorterSubtitles] (anche vuota) il sottotitolo e' un riepilogo che **si mostra intero o non si mostra**: la prima forma che entra fra
 * [subtitle] e le forme piu' corte, e se nemmeno la piu' stretta entra la seconda riga manca (il riepilogo sta scritto anche in fondo alla
 * griglia). L'altezza dell'etichetta e' fissa: senza la seconda riga il titolo si centra. Con [shorterSubtitles] null (il luogo di una
 * sezione) il sottotitolo si tronca con i puntini come prima. TalkBack legge sempre titolo e sottotitolo per esteso.
 */
@Composable
fun GalleryChromeLabel(
  title: String,
  subtitle: String?,
  style: GalleryChromeStyle,
  backdrop: GlassBackdropState?,
  modifier: Modifier = Modifier,
  shorterTitles: List<String> = emptyList(),
  shorterSubtitles: List<String>? = null,
  contentMoving: () -> Boolean = { false },
) {
  val lines = remember(title, subtitle, shorterTitles, shorterSubtitles) {
    ChromeLabelLines(forms = listOf(title) + shorterTitles, subtitle = subtitle, subtitleForms = subtitleFormsOf(subtitle, shorterSubtitles))
  }
  // Una lambda sola per tutta la vita dell'etichetta: il contenuto ricordato ([ChromeLabelSlot]) non cambia se chi chiama ne passa una nuova.
  val movingNow = rememberUpdatedState(contentMoving)
  val moving = remember { { movingNow.value() } }
  val shown = rememberChromePaced(lines, GalleryChromeDefaults.LabelPaceMillis)
  val fit = rememberChromeTitleFit(shown.forms, chromeTitleStyle())
  val subtitleFit = shown.subtitleForms?.let { forms -> rememberChromeTitleFit(forms, chromeSubtitleStyle()) }
  val slot = remember { ChromeLabelSlot() }
  val ink = style.ink
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  // Un `SubcomposeLayout` al posto di un `Box`: il testo si compone sapendo gia' quanta larghezza ha. Il contenuto si ricompone solo
  // quando cambia cio' che mostra (la forma scelta, il testo, l'inchiostro), non a ogni misura: mentre la capsula dei comandi si
  // allarga con la molla la riga si rimisura a ogni fotogramma, ma il contenuto e' lo stesso finche' la forma non cambia.
  SubcomposeLayout(
    modifier = modifier
      .height(chromePieceHeight())
      .chromeGlass(style, backdrop)
      .padding(horizontal = GalleryChromeDefaults.LabelPaddingH),
  ) { constraints ->
    val form = if (constraints.hasBoundedWidth) fit.pick(constraints.maxWidth) else 0
    val subtitleForm = if (constraints.hasBoundedWidth && subtitleFit != null) subtitleFit.pick(constraints.maxWidth) else 0
    val content = slot.contentFor(ChromeShownLines.of(shown, form, subtitleForm), ink, reduced, moving)
    val placeable = subcompose(Unit, content).first().measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
  }
}

/**
 * Cio' che l'etichetta puo' dire: il titolo in tutte le sue forme (la prima e' quella intera) e il luogo (o il riepilogo). [subtitleForms] e' null se il
 * sottotitolo si tronca con i puntini (il luogo); se no sono le forme del riepilogo, dalla piu' lunga alla piu' stretta, piu' in coda la stringa vuota
 * ("niente seconda riga"): [ChromeTitleFit] sceglie la prima che entra e, se nessuna entra, l'ultima.
 */
@Immutable
private data class ChromeLabelLines(val forms: List<String>, val subtitle: String?, val subtitleForms: List<String>? = null)

/** Le forme del sottotitolo di un riepilogo: [subtitle], le [shorter] e infine nessuna. Null se il sottotitolo non e' un riepilogo da mostrare intero o niente. */
private fun subtitleFormsOf(subtitle: String?, shorter: List<String>?): List<String>? =
  if (shorter == null || subtitle.isNullOrBlank()) null else listOf(subtitle) + shorter + ""

/**
 * Le due righe che l'etichetta mostra: la forma scelta del titolo e quella del sottotitolo (il luogo, o la forma del riepilogo; nessuna se il riepilogo non
 * entra). [spoken] e' cio' che TalkBack legge quando a schermo c'e' una forma corta o manca il riepilogo (GF-2, GF-22): il titolo e il sottotitolo per esteso.
 * Null se tutto cio' che e' mostrato e' gia' per intero.
 */
@Immutable
private data class ChromeShownLines(val title: String, val subtitle: String?, val spoken: String?, val subtitleForms: List<String>? = null) {
  companion object {
    fun of(lines: ChromeLabelLines, form: Int, subtitleForm: Int = 0): ChromeShownLines {
      val subtitleForms = lines.subtitleForms
      val subtitle = if (subtitleForms == null) lines.subtitle else subtitleForms[subtitleForm].ifEmpty { null }
      val shortened = form != 0 || (subtitleForms != null && subtitleForm != 0)
      return ChromeShownLines(
        title = lines.forms[form],
        subtitle = subtitle,
        spoken = if (!shortened) null else listOfNotNull(lines.forms.first(), lines.subtitle?.takeIf { it.isNotBlank() }).joinToString(", "),
        subtitleForms = subtitleForms,
      )
    }
  }
}

/**
 * Il contenuto composto dell'etichetta, ricordato finche' mostra le stesse cose: `subcompose` ricompone solo se riceve un contenuto
 * diverso, e una misura che non cambia niente (la capsula accanto che si muove) non deve ricomporre il testo.
 */
private class ChromeLabelSlot {
  private var lines: ChromeShownLines? = null
  private var ink: Color = Color.Unspecified
  private var reduced: Boolean = false
  private var content: (@Composable () -> Unit)? = null

  /** [moving] e' la stessa lambda per tutta la vita dell'etichetta ([GalleryChromeLabel] la ricorda): non fa parte della chiave. */
  fun contentFor(lines: ChromeShownLines, ink: Color, reduced: Boolean, moving: () -> Boolean): @Composable () -> Unit {
    val current = content
    if (current != null && lines == this.lines && ink == this.ink && reduced == this.reduced) return current
    this.lines = lines
    this.ink = ink
    this.reduced = reduced
    val next: @Composable () -> Unit = {
      if (reduced) {
        GalleryChromeTitle(title = lines.title, subtitle = lines.subtitle, ink = ink, spoken = lines.spoken)
      } else {
        MovingChromeTitle(lines, ink, moving)
      }
    }
    content = next
    return next
  }
}

/**
 * Quale forma del titolo entra (GF-2): la prima, dalla piu' lunga, la cui larghezza sta in quella disponibile; se nessuna entra,
 * l'ultima (la piu' stretta: finira' con i puntini, ma solo lei). Ogni forma si misura **una volta**, quando serve: un titolo che
 * entra intero (il tablet, "Oggi") misura solo se' stesso, un titolo senza forme corte niente. Per tornare a una forma piu' lunga di
 * quella mostrata serve un margine ([backSlackPx]): la molla della capsula accanto arriva con un piccolo rimbalzo, e senza margine
 * una forma che entra per un pixel si alternerebbe con la piu' corta.
 */
internal class ChromeTitleFit(private val forms: List<String>, private val backSlackPx: Int, private val widthOf: (String) -> Int) {
  private val widths = IntArray(forms.size) { Unmeasured }
  private var last = -1

  fun pick(maxWidth: Int): Int {
    var picked = forms.lastIndex
    for (i in 0 until forms.lastIndex) {
      if (widths[i] == Unmeasured) widths[i] = widthOf(forms[i])
      val needed = if (last > i) widths[i] + backSlackPx else widths[i]
      if (needed <= maxWidth) {
        picked = i
        break
      }
    }
    last = picked
    return picked
  }

  private companion object {
    const val Unmeasured = -1
  }
}

/** Il misuratore delle forme di un titolo o di un riepilogo, con lo [style] con cui [GalleryChromeTitle] lo scrive. Uno per elenco di forme. */
@Composable
private fun rememberChromeTitleFit(forms: List<String>, style: TextStyle): ChromeTitleFit {
  val measurer = rememberTextMeasurer(cacheSize = 0)
  val slack = with(LocalDensity.current) { TitleFitBackSlack.roundToPx() }
  return remember(forms, measurer, style, slack) {
    ChromeTitleFit(forms, slack) { text -> measurer.measure(text = text, style = style, softWrap = false, maxLines = 1).size.width }
  }
}

/** Il margine per tornare da una forma corta a una piu' lunga (vedi [ChromeTitleFit]). */
private val TitleFitBackSlack = 2.dp

/**
 * Il testo dell'etichetta che cambia con la dissolvenza incrociata, dentro un contenitore la cui larghezza segue la molla (il vetro
 * che lo porta cresce e si stringe con lui) e che dentro una raffica non si stringe (la soglia di [GalleryChromeLabel]).
 *
 * Il contenitore e' largo almeno [ChromeWidthHold.held]. Un cambio **isolato** non tiene niente: la capsula va subito alla misura del
 * testo nuovo. Un cambio che arriva a meno di [GalleryChromeDefaults.LabelSettleMillis] dal precedente (una raffica) tiene la
 * larghezza del testo che c'era, e ogni cambio successivo la piu' larga vista finora; a testo fermo la tenuta torna a zero e la molla
 * porta la capsula sulla misura del testo.
 */
@Composable
private fun MovingChromeTitle(lines: ChromeShownLines, ink: Color, moving: () -> Boolean) {
  val hold = remember { ChromeWidthHold() }
  // P2 (S25, 10/11): mentre il contenuto sotto si muove per un gesto la larghezza **non** si anima. Il vetro sta sul nodo che cambia misura,
  // e ogni fotogramma della molla sono tre superfici nuove sulla GPU (il pezzo, la cattura, l'ombra): nel pinch scacciavano le miniature dalla
  // cache del RenderThread, e ogni cambio di livello le ricaricava (22 cambi di livello in ritardo su 28, contro 8 senza molla). Da fermi la
  // molla resta. La risposta si legge da un `derivedStateOf`: questa funzione si ricompone quando il gesto comincia e quando finisce, non a
  // ogni fotogramma. Togliere il modificatore a meta' di una molla porta subito la larghezza a quella del testo.
  val still by remember(moving) { derivedStateOf { !moving() } }
  LaunchedEffect(lines) {
    // La prima composizione non e' un cambio.
    if (!hold.started) {
      hold.started = true
      return@LaunchedEffect
    }
    // GF-6: la larghezza si tiene solo dentro una raffica. `inBurst` resta vero se il cambio precedente e' piu' recente della soglia
    // (questo effetto riparte a ogni cambio, e l'attesa sotto non e' finita).
    if (hold.inBurst) hold.held = maxOf(hold.held, hold.natural)
    hold.inBurst = true
    delay(GalleryChromeDefaults.LabelSettleMillis)
    hold.inBurst = false
    hold.held = 0
  }
  val held = with(LocalDensity.current) { hold.held.toDp() }
  // Il testo in arrivo, letto come stato dal contenuto: `AnimatedContent` tiene per il testo che esce la lambda di quando e' entrato.
  val target = rememberUpdatedState(lines)
  Box(
    modifier = Modifier
      .then(if (still) Modifier.animateContentSize(animationSpec = FluidMotion.intSize(), alignment = Alignment.CenterStart) else Modifier)
      .widthIn(min = held),
    contentAlignment = Alignment.CenterStart,
  ) {
    AnimatedContent(
      targetState = lines,
      transitionSpec = {
        // La misura qui non si anima: prende **subito** quella del testo nuovo (senza, durante la dissolvenza varrebbe la piu' larga
        // fra il vecchio e il nuovo, e la capsula comincerebbe a stringersi solo a dissolvenza finita). La molla la fa il contenitore,
        // che continua con la sua velocita' anche quando un cambio arriva a meta' strada (un'animazione che ricomincia a ogni cambio
        // e' il tremolio che la beta toglie) e ritaglia il testo che esce.
        (fadeIn(FluidMotion.fadeIn(LabelFadeInMillis)) togetherWith fadeOut(FluidMotion.fadeOut(LabelFadeOutMillis)))
          .using(SizeTransform(clip = false) { _, _ -> snap() })
      },
      contentAlignment = Alignment.CenterStart,
      modifier = Modifier.onSizeChanged { hold.natural = it.width },
      label = "etichetta della chrome",
    ) { shown ->
      GalleryChromeTitle(
        title = shown.title,
        subtitle = shown.subtitle,
        ink = ink,
        spoken = shown.spoken,
        // Il testo che esce non prende i puntini mentre sfuma (la riga puo' essersi stretta: e' per questo che cambia forma).
        titleOverflow = if (shown == target.value) TextOverflow.Ellipsis else TextOverflow.Clip,
      )
    }
  }
}

/**
 * La tenuta della larghezza dell'etichetta dentro una raffica di cambi (pixel). [natural] e' la misura del testo, letta al layout;
 * [inBurst] dice se l'ultimo cambio e' piu' recente della soglia.
 */
private class ChromeWidthHold {
  var held: Int by mutableIntStateOf(0)
  var natural: Int = 0
  var started: Boolean = false
  var inBurst: Boolean = false
}

/**
 * [value], ma **a ritmo**: un valore nuovo si mostra subito, poi per [intervalMillis] si aspetta e alla fine si mostra l'ultimo
 * arrivato (quelli in mezzo si saltano). L'attesa si conta in fotogrammi, non con un timer: chi la aspetta (un test, un lettore di
 * schermo) la vede finire, e durante uno scorrimento i fotogrammi ci sono comunque.
 */
@Composable
internal fun <T> rememberChromePaced(value: T, intervalMillis: Long): T {
  val latest = rememberUpdatedState(value)
  var shown by remember { mutableStateOf(value) }
  LaunchedEffect(intervalMillis) {
    snapshotFlow { latest.value }.collect { next ->
      if (next != shown) {
        shown = next
        val start = withFrameMillis { it }
        while (withFrameMillis { it } - start < intervalMillis) {
          // Il ritmo: i cambi che arrivano adesso aspettano il prossimo giro (snapshotFlow tiene solo l'ultimo).
        }
      }
    }
  }
  return shown
}

private const val LabelFadeInMillis = 180
private const val LabelFadeOutMillis = 120

/**
 * Un tasto (o un gruppo) della capsula dei comandi che **compare e scompare allargando e stringendo la capsula** (B1 della beta: il
 * tasto "torna in fondo" che compare scorrendo, la nuvola): si apre dal suo bordo con la molla di `FluidMotion` e sfuma, e il vetro
 * della capsula, che lo avvolge, si muove con lui. Con le animazioni ridotte compare e scompare di colpo.
 */
@Composable
fun RowScope.GalleryChromeSlot(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = if (reduced) EnterTransition.None else expandHorizontally(FluidMotion.intSize(), Alignment.End) + fadeIn(FluidMotion.fadeIn()),
    exit = if (reduced) ExitTransition.None else shrinkHorizontally(FluidMotion.intSize(), Alignment.End) + fadeOut(FluidMotion.fadeOut()),
    label = "tasto della capsula",
  ) { content() }
}

/**
 * Il contenuto della capsula dei comandi che **cambia forma** (B1 della beta: entrando in selezione i comandi diventano "Fine"): i
 * tasti di [state] passano in dissolvenza incrociata e la capsula va dalla larghezza vecchia alla nuova con la molla di
 * `FluidMotion`, allineata a destra (il bordo che sta fermo, contro il margine dello schermo). Senza ritaglio: un tasto premuto cresce
 * oltre il suo bersaglio, e un ritaglio lo taglierebbe. Con le animazioni ridotte cambia di colpo.
 */
@Composable
fun <S> GalleryChromeSwap(state: S, modifier: Modifier = Modifier, content: @Composable RowScope.(S) -> Unit) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  AnimatedContent(
    targetState = state,
    modifier = modifier,
    transitionSpec = {
      if (reduced) {
        (EnterTransition.None togetherWith ExitTransition.None).using(null)
      } else {
        (fadeIn(FluidMotion.fadeIn()) togetherWith fadeOut(FluidMotion.fadeOut())).using(SizeTransform(clip = false) { _, _ -> FluidMotion.intSize() })
      }
    },
    contentAlignment = Alignment.CenterEnd,
    label = "comandi della capsula",
  ) { shown ->
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GalleryChromeDefaults.CapsuleGap)) {
      content(shown)
    }
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
    galleryGlass(state = backdrop, tint = style.tint, shape = FluidCapsuleShape, role = GlassRole.Floating)
  } else if (LocalGalleryAccessibility.current.solidSurfaces) {
    gallerySolidSurface(tint = style.tint, shape = FluidCapsuleShape)
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
 *
 * [spoken]: cio' che TalkBack legge al posto delle due righe, quando il titolo a schermo e' una forma corta della data (GF-2): la
 * data per esteso e il luogo. Null = le due righe cosi' come sono.
 */
@Composable
internal fun GalleryChromeTitle(
  title: String,
  subtitle: String?,
  ink: Color,
  modifier: Modifier = Modifier,
  spoken: String? = null,
  titleOverflow: TextOverflow = TextOverflow.Ellipsis,
) {
  Column(
    modifier = modifier.semantics(mergeDescendants = true) {
      heading()
      if (spoken != null) contentDescription = spoken
    },
  ) {
    Text(
      text = title,
      style = chromeTitleStyle(),
      color = ink,
      maxLines = 1,
      overflow = titleOverflow,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = chromeSubtitleStyle(),
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** Lo stile del titolo dell'etichetta: e' anche quello con cui si misurano le sue forme corte ([ChromeTitleFit]). */
@Composable
private fun chromeTitleStyle(): TextStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)

/** Lo stile del sottotitolo: anche quello con cui si misurano le forme di un riepilogo (GF-22). */
@Composable
private fun chromeSubtitleStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)

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
  GalleryChromeMenuAnchor(paneTitle = paneTitle, modifier = modifier, entries = { entries }, trigger = trigger)
}

/**
 * Come l'altro [GalleryChromeMenuAnchor], ma le voci si costruiscono **solo a menu aperto**: [entries] si chiama dentro il
 * pop-up. Chi posa il menu non legge nella propria composizione lo stato che le voci mostrano (il livello scelto, il
 * filtro, lo stile): un cambio di livello durante un pinch non ricompone il tasto e la capsula che lo contiene (misure
 * sull'S25 del 15/10). Le azioni delle voci devono girare in uno scope di chi posa il menu, non del pop-up, che lascia la
 * composizione alla chiusura.
 */
@Composable
fun GalleryChromeMenuAnchor(
  paneTitle: String,
  modifier: Modifier = Modifier,
  entries: @Composable () -> List<GalleryMenuEntry>,
  trigger: @Composable (open: () -> Unit) -> Unit,
) {
  // Il menu della vista e' lungo (i livelli, i filtri, lo stile, le Impostazioni; in debug le librerie di prova): se il pop-up non
  // ci sta, le voci scorrono invece di restare tagliate sotto il bordo (G2, visto sull'emulatore). Lo fa il pop-up della chrome.
  GalleryChromePopoverAnchor(paneTitle = paneTitle, modifier = modifier, trigger = trigger) { close ->
    GalleryMenuPanel(entries = entries(), onItemSelected = close)
  }
}

/**
 * Un **pop-up a tendina** della chrome (B2 della beta: la nuvola di stato apre il suo pannello da qui; il menu della vista e' lo
 * stesso pop-up con le voci): [trigger] riceve l'azione che apre il pop-up dell'engine **sul tasto stesso** (cresce dal suo
 * rettangolo), con dentro [panel] sul materiale unico dei pannelli dell'app (il vetro dei modali dell'engine e, sotto, il velo
 * del colore della pagina calcolato da [menuVeil] per 4,5:1 sul caso peggiore). Se il pannello non ci sta, scorre. [panel] riceve
 * l'azione che chiude il pop-up. Senza un `FluidGlassModalHost` nello scope il tocco non apre niente.
 *
 * Il portale **si compone dalla prima apertura** e poi resta composto (cosi' l'uscita si anima): un pop-up mai aperto non costa
 * niente nel primo fotogramma della pagina, come i fogli di conferma (Tab S9, 03/11: otto portali chiusi costavano ~27 ms).
 */
@Composable
fun GalleryChromePopoverAnchor(
  paneTitle: String,
  modifier: Modifier = Modifier,
  trigger: @Composable (open: () -> Unit) -> Unit,
  panel: @Composable (close: () -> Unit) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  var armed by remember { mutableStateOf(false) }
  var bounds by remember { mutableStateOf<Rect?>(null) }
  Box(modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
    trigger {
      armed = true
      open = true
    }
  }
  if (!armed) return
  val veil = menuVeil()
  FluidGlassModalPortal(
    visible = open,
    onDismissRequest = { open = false },
    origin = { bounds },
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = paneTitle,
  ) {
    Box(modifier = Modifier.background(veil).verticalScroll(rememberScrollState())) {
      panel { open = false }
    }
  }
}

/**
 * Il velo minimo sotto le voci di un pop-up perche' `onSurface` regga 4,5:1 sopra il bianco e il nero pieni, contando la
 * pellicola dei modali (`GlassDefaults.modalTint`) e lo scrim che l'engine stende sotto (nero al 12% in chiaro, al 20% in
 * scuro, i valori della 2.13.0). La sfocatura del pop-up media il fondo e aiuta: qui non la si conta, per stare larghi.
 * Rimisurato con il Fluid Engine 3.0.0: il pop-up prende `GlassDefaults.panelTint()`, che senza colore e' proprio `modalTint()` (pellicola al 50% sullo
 * scuro e al 54% sul chiaro, come nella 2.13), e lo scrim del pop-up e' ancora nero al 12% e al 20% (`FluidGlassModal.kt`). Con il contrasto di sistema medio o
 * alto (Android 14+) `modalTint()` sale da sola verso il pieno (`forSystemContrast`): il velo, calcolato da lei, scende di conseguenza.
 */
@Composable
private fun menuVeil(): Color {
  val scheme = MaterialTheme.colorScheme
  val film = GlassDefaults.modalTint().overlay
  val dark = GlassDefaults.isDarkSurface()
  // Fase 10 P8: con le superfici piene (piu' contrasto, meno trasparenza) il velo e' pieno: il pop-up non lascia vedere niente.
  val solid = LocalGalleryAccessibility.current.solidSurfaces
  // K1: la regola (e i numeri dello scrim dell'engine) sta in `GalleryMenuLegibility`, con quella del menu ⋮ del viewer.
  return remember(scheme, film, dark, solid) { GalleryMenuLegibility.textVeil(scheme, film, dark, solid) }
}

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
   * Il ritmo dell'etichetta (B1 della beta): dopo un cambio di testo il prossimo si mostra non prima di tanto. 200 ms sono cinque
   * date al secondo scorrendo veloce: si leggono ancora, e la dissolvenza (180 ms) fa in tempo a finire.
   */
  const val LabelPaceMillis: Long = 200L

  /** La soglia dell'etichetta (B1): la capsula si stringe sul testo solo dopo che il testo e' fermo da tanto. */
  const val LabelSettleMillis: Long = 600L

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
