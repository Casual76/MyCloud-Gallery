package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure della capsula dell'analisi nel pannello informazioni ([AnalysisCapsule]; giro 4, XU2-11: in un posto solo, non costanti sparse). La
 * capsula e' alta quanto un tasto (48 dp, il bersaglio minimo), con la piastrella dell'analisi da 40 dp a sinistra e, quando si tocca, il ▶ di
 * «Analizza ora» dopo le parole.
 */
internal object AnalysisCapsuleDimens {
  /** L'altezza minima: un bersaglio da dito. */
  val Height: Dp = 48.dp

  /** Il margine a sinistra della piastrella (la piastrella e' quasi a filo del bordo tondo). */
  val Start: Dp = 6.dp

  /** Il margine a destra delle parole (o del ▶). */
  val End: Dp = 14.dp

  /** Lo spazio fra piastrella, parole e ▶. */
  val Gap: Dp = 10.dp

  /** La piastrella dell'analisi. */
  val Tile: Dp = 40.dp

  /** Il margine a destra quando la capsula e' un tasto: il ▶ ha gia' aria sua, e cosi' la capsula resta accanto a quella del backup sul telefono. */
  val EndWithAction: Dp = 10.dp

  /** XU2-3: il ▶ dell'azione, quando la capsula e' un tasto (la stessa icona del tasto «Analizza ora» della testata). */
  val Action: Dp = 18.dp

  /** Fra le parole e il ▶: il ▶ si legge come parte del tasto, non come un'altra cosa. */
  val ActionGap: Dp = 4.dp

  /** Fra la capsula e la riga del perche' sotto. */
  val LineGap: Dp = 4.dp
}
