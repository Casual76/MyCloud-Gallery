package dev.pampa.mycloudgallery.core.designsystem.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

// LO STATO VUOTO DELLA GALLERIA (fase I, «una sola forma di stato vuoto»). Una pagina senza niente da mostrare ha **una figura, un titolo, al piu'
// un tasto**: niente paragrafi. La figura dice il caso (la galleria, il lucchetto, il filtro spento, la valigia), il titolo lo nomina in poche parole,
// il tasto lo risolve. E' la forma della Libreria vuota (I4); la usano tutte le pagine. Il perche' lungo, se serve, sta dietro una «i» ([GalleryInfoButton]).

/** Le misure dello stato vuoto. */
object GalleryFigureStateDefaults {
  /** La larghezza massima del titolo (a tablet il titolo non corre per tutta la pagina). */
  val TextMaxWidth: Dp = 340.dp

  /** Lo spazio fra figura, titolo e tasto. */
  val Gap: Dp = 12.dp
}

/**
 * Lo stato vuoto: la figura ([GalleryIllustration]), il titolo come intestazione, sotto [action] (un tasto, una barra, niente).
 *
 * @param fill vero (di serie) per occupare tutta la pagina e stare al centro; falso dentro un elenco o una colonna che decide lei.
 */
@Composable
fun GalleryFigureState(
  icon: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
  tone: GalleryIllustrationTone = GalleryIllustrationTone.Brand,
  fill: Boolean = true,
  action: (@Composable () -> Unit)? = null,
) {
  val body: @Composable () -> Unit = {
    Column(
      modifier = Modifier.widthIn(max = GalleryFigureStateDefaults.TextMaxWidth).padding(horizontal = GalleryDimens.Gutter).testTag(GalleryFigureStateTag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(GalleryFigureStateDefaults.Gap),
    ) {
      GalleryIllustration(icon = icon, tone = tone)
      Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
      )
      action?.invoke()
    }
  }
  if (fill) {
    Column(
      modifier = modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) { body() }
  } else {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) { body() }
  }
}

/** L'etichetta di test dello stato vuoto. */
const val GalleryFigureStateTag: String = "galleria-stato-vuoto"
