package dev.pampa.mycloudgallery.feature.nas.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidPressImmediate
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.design.ActionBarHeight
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCapsule
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeLabel
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyle
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyles
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryEdgeScrim
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.design.LocalGalleryChromeInk
import dev.pampa.mycloudgallery.core.designsystem.design.chromeRowHeight
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R

// La chrome delle schermate della 4B: il linguaggio G2, direzione C "vetro adattivo" (docs/grafica-progetto.md §11, §12), lo stesso
// delle Raccolte e degli Eliminati di recente di Android. In alto UNA riga sotto la barra di stato: (indietro) | etichetta | capsula
// dei comandi, tutti pezzi di vetro adattivo su una sfumatura del colore della pagina; in basso, nel posto della pillola, la barra
// delle azioni; sotto, la pagina che scorre. Un solo fondale di vetro per pagina: lo registra [NasPage].
//
// Perche' non si riusa `GridTopChrome` di feature-library: e' `internal` (e i feature non si conoscono). Le funzioni pubbliche di
// `core-designsystem/design` bastano a rifarlo in poche righe; la proposta per il filone grafico e' portarlo nel design system.
//
// Il contenuto che passa sotto la chrome qui e' pagina e testo (non foto con ThumbHash): si garantisce il caso peggiore assoluto
// (nero e bianco pieni) sotto la sfumatura, come fanno gli Eliminati di recente e i Nascosti (`grid = null`).

/** Che cosa dice l'etichetta in alto: il titolo e, sotto, il luogo o il riepilogo. */
@Immutable
internal data class NasLabel(val title: String, val subtitle: String? = null)

/** Lo spazio che la chrome toglie al contenuto: sopra la riga in alto, sotto la barra delle azioni (o la pillola). */
@Immutable
internal data class NasInsets(val top: Dp, val bottom: Dp)

/**
 * Una pagina della 4B: lo sfondo, il fondale del vetro, la sfumatura in alto, la riga di chrome e, se serve, la barra in fondo.
 *
 * @param onBack il tasto indietro; null nel pannello di dettaglio del tablet e dove la pagina e' ospitata (non e' una rotta).
 * @param commands i comandi nella capsula a destra; null = niente capsula.
 * @param hostPadding lo spazio che la pillola delle schede occupa in basso (come `contentPadding` delle Raccolte): zero a schermo
 *   intero, il suo ingombro quando la pagina sta nel pannello di dettaglio di un tablet.
 * @param reserveBottomBar la pagina ha una barra in fondo (azioni della selezione): lo spazio e' riservato anche quando la barra non
 *   c'e', cosi' entrando in selezione la lista non si sposta (P6 della grafica).
 * @param bottomBar la barra in fondo, nel posto della pillola; riceve il fondale e lo stile adattivo. Visibile o no lo decide lei.
 */
@Composable
internal fun NasPage(
  label: NasLabel,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  commands: (@Composable RowScope.() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  reserveBottomBar: Boolean = false,
  bottomBar: (@Composable (backdrop: GlassBackdropState, style: GalleryChromeStyle) -> Unit)? = null,
  content: @Composable BoxScope.(NasInsets) -> Unit,
) {
  val backdrop = rememberGlassBackdrop()
  if (backdropOut != null) SideEffect { backdropOut(backdrop) }
  val topChrome = nasTopChromeHeight()
  val slotBottom = nasActionSlot(hostPadding)
  val bottomInset = if (reserveBottomBar) {
    slotBottom + ActionBarHeight + GalleryChromeDefaults.Gap
  } else {
    maxOf(hostPadding.calculateBottomPadding(), WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
  }
  val style = rememberNasChromeStyle(veil = nasTopVeil())
  val barStyle = rememberNasChromeStyle(veil = null)

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      content(NasInsets(top = topChrome, bottom = bottomInset))
      NasTopEdge(topChrome, Modifier.align(Alignment.TopCenter))
    }
    NasTopChrome(
      label = label,
      style = style,
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopStart),
      onBack = onBack,
      commands = commands,
    )
    if (bottomBar != null) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = slotBottom)
          .padding(horizontal = GalleryChromeDefaults.EdgeMargin),
      ) {
        bottomBar(backdrop, barStyle)
      }
    }
  }
}

/** La riga di chrome in alto: indietro, etichetta, comandi. Ogni pezzo e' vetro adattivo con la stessa pellicola. */
@Composable
internal fun NasTopChrome(
  label: NasLabel,
  style: GalleryChromeStyle,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  commands: (@Composable RowScope.() -> Unit)? = null,
) {
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = statusBar)
      .height(chromeRowHeight())
      .padding(horizontal = GalleryChromeDefaults.EdgeMargin),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      GalleryChromeCapsule(style = style, backdrop = backdrop, modifier = Modifier.galleryChromeTag("indietro")) {
        GalleryChromeIconButton(
          icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
          contentDescription = stringResource(R.string.nas_back),
          onClick = onBack,
        )
      }
      Spacer(Modifier.width(GalleryChromeDefaults.Gap))
    }
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      GalleryChromeLabel(
        title = label.title,
        subtitle = label.subtitle,
        style = style,
        backdrop = backdrop,
        modifier = Modifier.galleryChromeTag("titolo"),
      )
    }
    if (commands != null) {
      Spacer(Modifier.width(GalleryChromeDefaults.Gap))
      GalleryChromeCapsule(style = style, backdrop = backdrop, modifier = Modifier.galleryChromeTag("comandi"), content = commands)
    }
  }
}

/**
 * Lo stile del vetro adattivo per un contenuto qualunque (nero e bianco pieni) sotto un [veil] (la sfumatura della pagina alla
 * quota del pezzo), nella polarita' del tema: la pellicola piu' leggera che tiene 4,5:1 sul caso peggiore. E' quello che
 * `GridTopChrome` sceglie per le pagine senza ThumbHash, con la stessa aritmetica di `GalleryLegibility.adapt`.
 */
@Composable
internal fun rememberNasChromeStyle(veil: Color?): GalleryChromeStyle {
  val palette = GalleryChromeStyles.adaptivePalette()
  val darkTheme = GlassDefaults.isDarkSurface()
  val choice = remember(palette, darkTheme, veil) { nasChromeChoice(palette.unknown, palette, darkTheme, veil) }
  return GalleryChromeStyles.adaptive(choice)
}

/** La scelta pura (pellicola e inchiostro) per [range] sotto [veil]: la usano la composizione e i test di contrasto. */
internal fun nasChromeChoice(
  range: ContentRange,
  palette: dev.pampa.mycloudgallery.core.designsystem.design.AdaptivePalette,
  darkTheme: Boolean,
  veil: Color?,
): dev.pampa.mycloudgallery.core.designsystem.design.AdaptiveInk {
  val seen = if (veil == null) range else ContentRange(range.samples.map { veil.compositeOver(it) })
  return GalleryLegibility.adapt(seen, palette, previousDark = !darkTheme)
}

/** Il velo della sfumatura in alto alla quota del bordo basso dei pezzi di chrome: il piu' leggero, quindi il piu' prudente. */
@Composable
private fun nasTopVeil(): Color = MaterialTheme.colorScheme.background.copy(alpha = EdgeRowAlpha)

/** L'altezza della chrome in alto, barra di stato compresa: la linea sotto cui comincia il contenuto. */
@Composable
internal fun nasTopChromeHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + chromeRowHeight()

/**
 * Dove sta la barra delle azioni in fondo: nel posto della pillola se la pagina non ne ha una sotto (la rotta a schermo intero
 * del telefono), sopra la pillola se c'e' ([hostPadding], il pannello di dettaglio del tablet).
 */
@Composable
internal fun nasActionSlot(hostPadding: PaddingValues): Dp {
  val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val pill = hostPadding.calculateBottomPadding()
  return if (pill > NasDimens.None) navigation + pill + GalleryChromeDefaults.Gap else navigation + FluidFloatingTabBarDefaults.BottomMargin
}

/** La sfumatura del colore della pagina in alto, da posare **dentro** il fondale perche' il vetro sopra la veda. */
@Composable
private fun NasTopEdge(topChrome: Dp, modifier: Modifier = Modifier) {
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val stops = nasTopEdgeStops(statusBar, topChrome)
  GalleryEdgeScrim(color = MaterialTheme.colorScheme.background, stops = stops, modifier = modifier.fillMaxWidth().height(stops.last().first))
}

/** Le tappe della sfumatura: piena sotto la barra di stato, leggera sotto i pezzi di chrome, poi a zero (le stesse delle griglie). */
internal fun nasTopEdgeStops(statusBar: Dp, topChrome: Dp): List<Pair<Dp, Float>> =
  listOf(NasDimens.None to EdgeTopAlpha, statusBar to EdgeStatusAlpha, topChrome to EdgeRowAlpha, topChrome + NasDimens.TopEdgeFade to 0f)

// Le opacita' sono quelle di GridPageChrome (G2): densa solo dove ci sono le icone di sistema, leggera sotto i pezzi.
private const val EdgeTopAlpha = 0.85f
private const val EdgeStatusAlpha = 0.55f
private const val EdgeRowAlpha = 0.15f
private const val DisabledAlpha = 0.38f

/**
 * La capsula in fondo, nel posto della pillola, alta quanto lei: **un solo pezzo di vetro** adattivo con dentro i pulsanti della
 * pagina (le azioni di una selezione, "Sposta qui", "Ripristina"). Come `GalleryChromeActionBar`, ma con un contenuto qualunque.
 */
@Composable
internal fun NasActionCapsule(
  style: GalleryChromeStyle,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  CompositionLocalProvider(LocalGalleryChromeInk provides style.ink, LocalFluidPressImmediate provides true) {
    Row(
      modifier = modifier
        .height(ActionBarHeight)
        .glassSurface(state = backdrop, tint = style.tint, shape = FluidCapsuleShape, role = GlassRole.Floating)
        .padding(horizontal = NasDimens.BarButtonGap),
      horizontalArrangement = Arrangement.spacedBy(NasDimens.BarButtonGap),
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
  }
}

/**
 * Un pulsante di testo (con il suo glifo) dentro una capsula: alto almeno 48 dp. L'etichetta va a capo invece di uscire dal
 * bordo quando il testo di sistema e' grande. [ink] e' l'inchiostro della capsula, o il suo rosso per un'azione che cancella.
 */
@Composable
internal fun NasBarButton(
  label: String,
  onClick: () -> Unit,
  ink: Color,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
) {
  Row(
    modifier = modifier
      .defaultMinSize(minWidth = NasDimens.BarButtonMinWidth, minHeight = GalleryDimens.MinTouchTarget)
      .alpha(if (enabled) 1f else DisabledAlpha)
      .fluidPressable(onClick = onClick, enabled = enabled, role = Role.Button, focusShape = FluidCapsuleShape)
      .padding(horizontal = NasDimens.BarButtonPadding),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(NasDimens.BarButtonGap, Alignment.CenterHorizontally),
  ) {
    if (icon != null) Icon(imageVector = icon, contentDescription = null, tint = ink)
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = ink,
      maxLines = 2,
      textAlign = TextAlign.Center,
      modifier = Modifier.weight(1f, fill = false),
    )
  }
}

/** Mostra [content] con la dissolvenza della chrome (una barra che compare e scompare, non una pagina). */
@Composable
internal fun NasFade(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  AnimatedVisibility(visible = visible, enter = fadeIn(FluidMotion.fadeIn()), exit = fadeOut(FluidMotion.fadeOut()), modifier = modifier) {
    content()
  }
}
