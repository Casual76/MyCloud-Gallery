package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGlassMotion

// IL VETRO CHE COMPARE E SPARISCE NEL VIEWER (grafica beta, G3, B4): le barre sfumano gia' con i token di FluidMotion (`GalleryViewerChrome`); gli altri pezzi di vetro (il badge "Movimento", gli
// avvisi, le note sopra la barra) comparivano e sparivano di colpo, a ogni tocco, zoom o cambio di foto. Qui entrano ed escono con la stessa dissolvenza, e con il movimento ridotto di colpo.

/**
 * Mostra [content] mentre [value] non e' null, sfumando in entrata e in uscita. All'uscita [value] e' gia' null: si tiene l'ultimo valore, solo per il tempo dell'uscita, cosi' il vetro non
 * si svuota prima di sparire. Il posto (l'allineamento, i margini) lo da' il chiamante con [modifier]: e' il contenitore che sfuma, non il pezzo dentro.
 */
@Composable
internal fun <T : Any> ViewerGlassPresence(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
  // Un contenitore semplice e non uno stato: scriverlo durante la composizione non invalida niente (uno stato riscritto a ogni ricomposizione la rifarebbe).
  val last = remember { LastValue<T>() }
  if (value != null) last.value = value
  AnimatedVisibility(visible = value != null, modifier = modifier, enter = GalleryGlassMotion.enter(), exit = GalleryGlassMotion.exit()) {
    last.value?.let { content(it) }
  }
}

private class LastValue<T : Any> {
  var value: T? = null
}
