package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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

// LA BARRA DEI SUGGERIMENTI (H4 della fase H, 11/11). Con la ricerca aperta, al posto della data, della nuvola e degli altri pezzi in alto c'e' una sola barra
// con i primi tre suggerimenti, «come la barra delle parole suggerite quando scrivi sulla tastiera, pero' nell'app sopra» (parole dell'utente): tre voci
// affiancate di uguale peso, separate in modo leggero, su un solo pezzo di vetro della chrome (lo stesso linguaggio dei pezzi in alto di oggi: cambia il
// contenuto, non il materiale). Un tocco applica la voce alla ricerca.

/**
 * La barra dei suggerimenti nella riga in alto (sotto la barra di stato, alta quanto la riga della chrome). [labels]: le voci, al piu' tre; vuota, la barra
 * si ritira (in dissolvenza, salendo appena: niente pezzo di vetro vuoto). [descriptions]: cio' che TalkBack dice di ogni voce (di serie la voce stessa).
 *
 * Il vetro e' largo quanto la riga (al piu' [GallerySearchBarDefaults.MaxWidth], centrato) e **non cambia mai misura**: quando le voci cambiano mentre si
 * scrive cambia solo il testo, in dissolvenza. Trattiene i tocchi (fra due voci la foto dietro non si apre).
 */
@Composable
fun GallerySuggestionBar(
  labels: List<String>,
  onPick: (Int) -> Unit,
  backdrop: GlassBackdropState?,
  modifier: Modifier = Modifier,
  descriptions: List<String>? = null,
  paneTitle: String? = null,
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
          .pointerInput(Unit) { detectTapGestures { } }
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
          SuggestionRow(words, said, onPick)
        }
      }
    }
  }
}

/** Le voci affiancate, di uguale peso, separate da una linea sottile dell'inchiostro della chrome. */
@Composable
private fun SuggestionRow(labels: List<String>, descriptions: List<String>?, onPick: (Int) -> Unit) {
  val ink = LocalGalleryChromeInk.current ?: MaterialTheme.colorScheme.onSurface
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    labels.forEachIndexed { index, label ->
      if (index > 0) {
        Box(Modifier.width(SeparatorWidth).height(SeparatorHeight).background(ink.copy(alpha = SeparatorAlpha), FluidCapsuleShape))
      }
      val description = descriptions?.getOrNull(index) ?: label
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(vertical = 2.dp)
          .semantics { contentDescription = description }
          .fluidPressable(onClick = { onPick(index) }, role = Role.Button, focusShape = FluidCapsuleShape)
          .padding(horizontal = ItemPadding),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelLarge,
          color = ink,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

private val SeparatorWidth = 1.dp
private val SeparatorHeight = 20.dp
private const val SeparatorAlpha = 0.28f
private val ItemPadding = 10.dp

/** Di quanto (una frazione della sua altezza) la barra sale ritirandosi. */
private const val SlideFraction = 3
