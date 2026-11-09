package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

// La nuvola di stato della chrome in alto (B2 della beta, lista dell'utente del 31/10: "la classica nuvolina del cloud con tanti stati
// diversi"): un tasto della capsula dei comandi, accanto a "seleziona" e alla vista, che dice con la forma se si sta sincronizzando,
// se e' tutto salvato o se c'e' un problema, e che apre il pannello dei dettagli.
//
// Perche' non `FluidSyncIndicator` dell'engine: e' un cerchio di 26 dp con un punto, un cerchio che gira o un "!" (nessuna nuvola), le
// sue frasi sono quelle dell'engine, e "il tono nel materiale" vuol dire un secondo vetro dentro la capsula (un layer in piu' sopra la
// griglia che scorre). Qui il tasto e' piatto dentro la capsula, come gli altri (il vetro sta sul contenitore), il glifo e' una nuvola
// con l'inchiostro adattivo della capsula, e il tono sta in un segno sul glifo, portato a 3:1 sopra la pellicola di quel momento.

/** Che cosa dice la nuvola: lo stato di sincronizzazione e backup, ridotto a una forma. */
enum class GalleryCloudState {
  /** Tutto salvato: la nuvola con la spunta. */
  Saved,

  /** Sincronizzazione o backup in corso: la nuvola con le frecce. */
  Syncing,

  /** In attesa per una ragione normale (in coda, in prova, al prossimo giro): la nuvola vuota. */
  Waiting,

  /** Nessuna rete: la nuvola barrata. */
  Offline,

  /** Un problema da guardare, senza colpa (il NAS non risponde): la nuvola con il segno di attenzione. */
  Attention,

  /** Un problema che chiede l'utente (lavori fermi, password, NAS pieno): la nuvola con il segno d'errore. */
  Alarm,
  ;

  /** Se il pannello parla di un problema invece che dello spazio. */
  val isProblem: Boolean get() = this == Offline || this == Attention || this == Alarm
}

/**
 * Il **tasto della nuvola** dentro una [GalleryChromeCapsule]: 48 x 48 dp, il glifo di [state] con l'inchiostro adattivo della capsula
 * e, per un problema, il segno del tono (attenzione o errore) sul glifo. Cambiando stato il glifo passa in dissolvenza con un piccolo
 * zoom (i token di `FluidMotion`; di colpo con le animazioni ridotte). Niente animazioni infinite: anche durante un backup lungo la
 * nuvola sta ferma, e la pagina non ridisegna niente per lei.
 *
 * @param contentDescription cio' che TalkBack dice: lo stato e, se c'e', il problema ("C'e' un problema: il NAS non risponde").
 */
@Composable
fun GalleryChromeCloudButton(
  state: GalleryCloudState,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val style = LocalGalleryChromeStyle.current
  val ink = style?.ink ?: LocalGalleryChromeInk.current ?: scheme.onSurface
  val marks = remember(style, scheme) { style?.let { GalleryCloudMarks.of(it, scheme) } }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  Box(
    modifier = modifier
      .size(GalleryDimens.MinTouchTarget)
      .semantics { this.contentDescription = contentDescription }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    AnimatedContent(
      targetState = state,
      transitionSpec = {
        if (reduced) {
          (EnterTransition.None togetherWith ExitTransition.None).using(null)
        } else {
          (fadeIn(FluidMotion.fadeIn()) + scaleIn(FluidMotion.standard(), initialScale = GlyphEnterScale))
            .togetherWith(fadeOut(FluidMotion.fadeOut()) + scaleOut(FluidMotion.standard(), targetScale = GlyphEnterScale))
            .using(null)
        }
      },
      contentAlignment = Alignment.Center,
      label = "nuvola di stato",
    ) { shown ->
      Box(modifier = Modifier.size(GalleryChromeDefaults.Glyph), contentAlignment = Alignment.Center) {
        Icon(imageVector = shown.glyph(), contentDescription = null, tint = ink, modifier = Modifier.size(GalleryChromeDefaults.Glyph))
        val fill = when (shown) {
          GalleryCloudState.Attention -> marks?.attention ?: scheme.tertiary
          GalleryCloudState.Alarm -> marks?.alarm ?: scheme.error
          else -> null
        }
        if (fill != null) {
          val mark = if (shown == GalleryCloudState.Alarm) marks?.onAlarm else marks?.onAttention
          ToneMark(fill = fill, glyph = mark ?: scheme.surface)
        }
      }
    }
  }
}

/** Il segno del tono: un disco pieno in basso a destra del glifo, con il punto esclamativo. */
@Composable
private fun ToneMark(fill: Color, glyph: Color) {
  Box(
    modifier = Modifier
      .offset(x = MarkOffset, y = MarkOffset)
      .size(MarkSize)
      .background(fill, FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = Icons.Rounded.PriorityHigh, contentDescription = null, tint = glyph, modifier = Modifier.size(MarkGlyph))
  }
}

/** Il glifo di uno stato. Per un problema la nuvola e' vuota: il segno del tono ci si legge sopra. */
fun GalleryCloudState.glyph(): ImageVector = when (this) {
  GalleryCloudState.Saved -> Icons.Rounded.CloudDone
  GalleryCloudState.Syncing -> Icons.Rounded.CloudSync
  GalleryCloudState.Waiting, GalleryCloudState.Attention, GalleryCloudState.Alarm -> Icons.Rounded.CloudQueue
  GalleryCloudState.Offline -> Icons.Rounded.CloudOff
}

/**
 * I colori dei segni della nuvola dentro un pezzo di chrome di stile [GalleryChromeStyle] (B7 della beta: anche la nuvola si legge
 * sopra le foto chiare): l'errore e l'attenzione dello schema portati a 3:1 sopra la pellicola ([GalleryChromeStyles.toneInk]), e il
 * punto esclamativo nel polo che si legge meglio sopra ciascuno. Funzione pura: i contrasti si verificano senza comporre.
 */
@Immutable
data class GalleryCloudMarks(val alarm: Color, val onAlarm: Color, val attention: Color, val onAttention: Color) {
  companion object {
    fun of(style: GalleryChromeStyle, scheme: ColorScheme): GalleryCloudMarks {
      val alarm = GalleryChromeStyles.toneInk(scheme.error, style, scheme.scrim)
      val attention = GalleryChromeStyles.toneInk(scheme.tertiary, style, scheme.scrim)
      return GalleryCloudMarks(
        alarm = alarm,
        onAlarm = GalleryChromeStyles.onTone(alarm, scheme.scrim),
        attention = attention,
        onAttention = GalleryChromeStyles.onTone(attention, scheme.scrim),
      )
    }
  }
}

private val MarkSize: Dp = 12.dp
private val MarkGlyph: Dp = 10.dp
private val MarkOffset: Dp = 6.dp
private const val GlyphEnterScale = 0.6f
