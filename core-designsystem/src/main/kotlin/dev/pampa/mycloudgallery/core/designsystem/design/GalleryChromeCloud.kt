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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Pause
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
// con l'inchiostro adattivo della capsula, e il tono sta in un segno sul glifo: un disco del colore del tono dentro un anello
// dell'inchiostro (l'anello si legge sopra qualunque foto come il resto della chrome, il colore resta riconoscibile).

/** Che cosa dice la nuvola: lo stato di sincronizzazione e backup, ridotto a una forma. */
enum class GalleryCloudState {
  /** Tutto salvato: la nuvola con la spunta. */
  Saved,

  /** Sincronizzazione o backup in corso: la nuvola con la freccia. */
  Syncing,

  /** In attesa per una ragione normale (in coda, in prova, al prossimo giro): la nuvola vuota. */
  Waiting,

  /**
   * In pausa per scelta dell'utente (il backup fermato con "Ferma", fino a "Riprendi"): la nuvola con il segno della pausa. Non e' un
   * problema, ma non e' "tutto salvato" (OA-10, review della beta): finche' dura, le foto nuove non si salvano.
   */
  Paused,

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
        val m = marks ?: GalleryCloudMarks.of(ink, scheme)
        when (shown) {
          GalleryCloudState.Attention -> ToneMark(ring = m.ring, fill = m.attention, glyph = m.onAttention)
          GalleryCloudState.Alarm -> ToneMark(ring = m.ring, fill = m.alarm, glyph = m.onAlarm)
          // La pausa non ha un tono: il suo segno e' un disco dell'inchiostro con le due barre nel polo opposto.
          GalleryCloudState.Paused -> ToneMark(ring = m.ring, fill = m.ring, glyph = m.onRing, icon = Icons.Rounded.Pause)
          else -> Unit
        }
      }
    }
  }
}

/**
 * Il segno del tono: in basso a destra del glifo, un anello dell'inchiostro della chrome (che si legge sopra qualunque foto, come il
 * glifo) e dentro un disco del colore del tono con il punto esclamativo (o [icon]: le barre della pausa).
 */
@Composable
private fun ToneMark(ring: Color, fill: Color, glyph: Color, icon: ImageVector = Icons.Rounded.PriorityHigh) {
  Box(
    modifier = Modifier
      .offset(x = MarkOffset, y = MarkOffset)
      .size(MarkSize)
      .background(ring, FluidCapsuleShape)
      .padding(MarkRing)
      .background(fill, FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = glyph, modifier = Modifier.size(MarkGlyph))
  }
}

/** Il glifo di uno stato. Per un problema e per la pausa la nuvola e' vuota: il segno ci si legge sopra. */
fun GalleryCloudState.glyph(): ImageVector = when (this) {
  GalleryCloudState.Saved -> Icons.Rounded.CloudDone
  GalleryCloudState.Syncing -> Icons.Rounded.CloudUpload
  GalleryCloudState.Waiting, GalleryCloudState.Paused, GalleryCloudState.Attention, GalleryCloudState.Alarm -> Icons.Rounded.CloudQueue
  GalleryCloudState.Offline -> Icons.Rounded.CloudOff
}

/**
 * I colori dei segni della nuvola dentro un pezzo di chrome (B7 della beta: anche la nuvola si legge sopra le foto chiare): l'anello e'
 * l'**inchiostro** della chrome, che regge 4,5:1 sopra la pellicola adattiva come il glifo e il testo; dentro, i toni pieni dello
 * schema (l'errore; il terziario per l'attenzione, come le capsule "da guardare" del piede), riconoscibili, e il punto esclamativo
 * nel polo che si legge meglio sopra ciascuno (almeno 4,5:1: c'e' sempre, fra il nero e il bianco). Una prima versione portava il
 * colore del tono verso l'inchiostro fino a 3:1 sul nero e sul bianco pieni: in scuro l'errore e l'attenzione diventavano tutti e due
 * quasi bianchi, e il tono non si vedeva piu' (screenshot del 09/11). Funzione pura: i contrasti si verificano senza comporre.
 *
 * [onRing] e' il polo che si legge sopra l'inchiostro stesso: le barre del segno della pausa, che e' un disco pieno dell'inchiostro.
 */
@Immutable
data class GalleryCloudMarks(val ring: Color, val alarm: Color, val onAlarm: Color, val attention: Color, val onAttention: Color, val onRing: Color) {
  companion object {
    fun of(style: GalleryChromeStyle, scheme: ColorScheme): GalleryCloudMarks = of(style.ink, scheme)

    fun of(ink: Color, scheme: ColorScheme): GalleryCloudMarks = GalleryCloudMarks(
      ring = ink,
      onRing = GalleryChromeStyles.onTone(ink, scheme.scrim),
      alarm = scheme.error,
      onAlarm = GalleryChromeStyles.onTone(scheme.error, scheme.scrim),
      attention = scheme.tertiary,
      onAttention = GalleryChromeStyles.onTone(scheme.tertiary, scheme.scrim),
    )
  }
}

private val MarkSize: Dp = 14.dp
private val MarkRing: Dp = 1.5.dp
private val MarkGlyph: Dp = 10.dp
private val MarkOffset: Dp = 6.dp
private const val GlyphEnterScale = 0.6f
