package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto

// LA SCHEDA DI COPERTINA (I1 e I4, fase I: «l'anteprima vince sull'icona»). Una sola forma per le gallerie dell'app: la copertina a tutta scheda, il titolo
// in basso, bianco sopra la sfumatura dello scrim (la coppia di [GalleryOnPhoto]: si legge anche su una foto bianca), al piu' una riga piccola sotto, e un
// punto dell'accento quando e' nuova. La usano i caroselli delle Raccolte, «Tutti i ricordi» e «Viaggi»: prima ognuno ne aveva una copia.

/** Le misure della scheda di copertina. */
object GalleryCoverCardDefaults {
  /** Il margine del titolo dal bordo della scheda (sopra la sfumatura ne ha il doppio). */
  val TextInset: Dp = 12.dp

  /** Il punto "nuovo" in alto a destra e il suo filo chiaro. */
  val NewDot: Dp = 10.dp

  /** Quanto sale la sfumatura sopra il titolo (dalla foto allo scrim pieno). */
  val FadeHeight: Dp = 28.dp

  /**
   * La sfumatura corta delle schede piccole (sotto i 140 dp, con il titolo di una riga, come i volti di «Tutte le persone»): copre meno della
   * copertina. Lo scrim pieno dietro le righe resta lo stesso, quindi anche il contrasto.
   */
  val FadeHeightCompact: Dp = 14.dp
  val NewDotRing: Dp = 2.dp
}

/**
 * La scheda di copertina.
 *
 * @param modifier la misura, il tocco, la semantica, il ritaglio e il fondo della scheda: li decide chi la usa (un carosello, una griglia).
 * @param title il titolo in basso; null = nessuna scritta (una persona senza nome: solo il volto).
 * @param caption la riga piccola sotto il titolo («Viaggio», il mese); null = nessuna.
 * @param titleStyle il corpo del titolo (le schede larghe dei caroselli lo hanno piu' grande).
 * @param titleMaxLines quante righe al piu' per il titolo.
 * @param isNew il punto dell'accento in alto a destra (la parola la dice la descrizione della scheda).
 * @param labelsSpoken falso (di serie): la scheda ha la sua descrizione (`contentDescription` con `mergeDescendants`) e i testi si vedono ma TalkBack
 *   non li rilegge (una sola lettura per scheda, CS-6 della fase I); vero solo per una scheda senza descrizione propria.
 * @param fadeHeight quanto sale la sfumatura sopra il titolo: [GalleryCoverCardDefaults.FadeHeight], o `FadeHeightCompact` sulle schede piccole.
 * @param cover la copertina (la foto, il volto, il segno della famiglia mentre la foto non c'e'), sotto a tutto.
 */
@Composable
fun GalleryCoverCard(
  modifier: Modifier,
  title: String?,
  caption: String? = null,
  titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
  titleMaxLines: Int = 2,
  isNew: Boolean = false,
  labelsSpoken: Boolean = false,
  fadeHeight: Dp = GalleryCoverCardDefaults.FadeHeight,
  cover: @Composable BoxScope.() -> Unit,
) {
  Box(modifier = modifier) {
    cover()
    if (title != null) {
      GalleryCoverLabel(
        title = title,
        caption = caption,
        titleStyle = titleStyle,
        titleMaxLines = titleMaxLines,
        spoken = labelsSpoken,
        fadeHeight = fadeHeight,
        modifier = Modifier.align(Alignment.BottomStart),
      )
    }
    if (isNew) GalleryNewDot(Modifier.align(Alignment.TopEnd))
  }
}

/**
 * Il titolo di una scheda in basso, bianco, con la riga piccola sotto. La sfumatura sale dalla foto **sopra** le righe e arriva piena dove comincia
 * il titolo: dietro le lettere lo scrim e' gia' intero, e il bianco regge una foto bianca (5,7:1, `GalleryCoverCardContrastTest`). Prima la sfumatura
 * correva dietro le righe stesse e il titolo stava nella parte chiara (2-2,6:1 su una copertina chiara, CU-2 della fase I).
 */
@Composable
private fun GalleryCoverLabel(title: String, caption: String?, titleStyle: TextStyle, titleMaxLines: Int, spoken: Boolean, fadeHeight: Dp, modifier: Modifier) {
  val scrim = GalleryOnPhoto.scrim()
  val quiet = if (spoken) Modifier else Modifier.semantics { hideFromAccessibility() }
  val inset = GalleryCoverCardDefaults.TextInset
  Column(modifier = modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth().height(fadeHeight).background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0f), scrim))))
    GalleryCoverLines(title, caption, titleStyle, titleMaxLines, quiet, Modifier.fillMaxWidth().background(scrim).padding(start = inset, end = inset, bottom = inset))
  }
}

@Composable
private fun GalleryCoverLines(title: String, caption: String?, titleStyle: TextStyle, titleMaxLines: Int, quiet: Modifier, modifier: Modifier) {
  Column(modifier = modifier) {
    Text(text = title, style = titleStyle, color = GalleryOnPhoto.Content, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis, modifier = quiet)
    if (caption != null) {
      Text(text = caption, style = MaterialTheme.typography.labelMedium, color = GalleryOnPhoto.Content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = quiet)
    }
  }
}

/** Il punto "nuovo" in alto a destra: l'accento con un filo chiaro. */
@Composable
private fun GalleryNewDot(modifier: Modifier) {
  Box(
    modifier = modifier
      .padding(GalleryCoverCardDefaults.TextInset)
      .size(GalleryCoverCardDefaults.NewDot)
      .clip(FluidCapsuleShape)
      .background(MaterialTheme.colorScheme.primary)
      .border(GalleryCoverCardDefaults.NewDotRing, GalleryOnPhoto.Content, FluidCapsuleShape),
  )
}
