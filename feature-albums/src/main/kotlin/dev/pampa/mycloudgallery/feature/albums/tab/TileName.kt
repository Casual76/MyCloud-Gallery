package dev.pampa.mycloudgallery.feature.albums.tab

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

// IL NOME SOTTO UNA COPERTINA DELLA SCHEDA ALBUM (GF-20 della review della beta). A 360 dp la cella e' larga 98,7 dp: la voce fissa «Messaggistica» (una parola
// sola) ne chiede circa 104 al corpo del nome, e con una riga sola finiva in «Messaggist…» insieme a meta' dei nomi. Adesso il nome sta su **due righe al
// massimo**, con l'altezza di **due righe sempre riservata** (le copertine e i conteggi restano allineati in griglia anche se un nome e' corto), e una parola
// che da sola non entra nella cella **scende di corpo** quanto basta (mai oltre [TileNameFit.MIN_SCALE]): si legge intera, non spezzata a meta'. Un nome davvero
// lungo finisce con i puntini alla seconda riga.

/**
 * Il misuratore dei nomi della scheda, per una cella larga [maxWidthPx] (pixel). Si fa una volta per cambio di larghezza o di testo (`remember`), non per
 * fotogramma; [scaleFor] misura le sole parole del nome una volta per nome.
 */
@Stable
internal class TileNameFit(private val maxWidthPx: Int, private val widthOf: (word: String) -> Int) {

  /** Il fattore del corpo del nome [title]: 1 se la sua parola piu' larga entra nella cella, se no quanto serve (non meno di [MIN_SCALE]). */
  fun scaleFor(title: String): Float {
    if (maxWidthPx <= 0) return 1f
    var widest = 0
    for (word in title.split(SPACES)) {
      if (word.isNotEmpty()) widest = maxOf(widest, widthOf(word))
    }
    if (widest <= maxWidthPx) return 1f
    return (maxWidthPx.toFloat() / widest * SAFETY).coerceAtLeast(MIN_SCALE)
  }

  companion object {
    /** Il corpo non scende sotto l'85%: sotto si leggerebbe peggio di un nome che finisce a capo a meta' parola. */
    const val MIN_SCALE: Float = 0.85f

    /** Un margine perche' la misura a corpo ridotto non sia per un pixel piu' larga della cella. */
    private const val SAFETY: Float = 0.98f

    private val SPACES = Regex("\\s+")
  }
}

/** Il misuratore dei nomi per una cella larga [cellWidth] dp (non specificata = nessuna misura: il corpo di serie), con lo stile del nome. */
@Composable
internal fun rememberTileNameFit(cellWidth: Dp, style: TextStyle): TileNameFit {
  val measurer: TextMeasurer = rememberTextMeasurer(cacheSize = NameMeasureCache)
  val density = LocalDensity.current
  val maxWidthPx = if (cellWidth.isSpecified) with(density) { cellWidth.roundToPx() } else 0
  return remember(measurer, style, maxWidthPx) {
    TileNameFit(maxWidthPx) { word -> measurer.measure(text = word, style = style, softWrap = false, maxLines = 1).size.width }
  }
}

private const val NameMeasureCache = 16

/**
 * Il nome: due righe al massimo, con l'altezza di due righe sempre riservata. Il corpo e' quello di [style], o piu' piccolo se la parola piu' larga del nome non
 * entra nella cella ([TileNameFit]).
 */
@Composable
internal fun TileName(title: String, fit: TileNameFit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
  val base = MaterialTheme.typography.titleSmall
  val scale = remember(fit, title) { fit.scaleFor(title) }
  val style = if (scale == 1f) base else base.copy(fontSize = scaledSize(base.fontSize, scale))
  // Le due righe le riserva il testo stesso (`minLines`), non un'altezza calcolata a parte. L'altezza vera di due righe si arrotonda riga per riga: con il
  // testo piu' grande del normale, o a una densita' non intera (2,625 sui telefoni da 411 dp, 2,8125), superava di qualche pixel `lineHeight * 2`, e Compose
  // dentro quell'altezza teneva una riga sola con i puntini, lasciando vuota la seconda (FAB-30 della review generale). Il corpo ridotto tiene l'interlinea
  // del corpo di serie (cambia solo `fontSize`), quindi un nome ridotto e uno normale hanno sotto il conteggio alla stessa altezza.
  Text(
    text = title,
    style = style,
    color = color,
    minLines = NAME_LINES,
    maxLines = NAME_LINES,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier.fillMaxWidth(),
  )
}

/** Le righe del nome: due, sempre riservate. */
internal const val NAME_LINES: Int = 2

private fun scaledSize(size: TextUnit, scale: Float): TextUnit = size * scale
