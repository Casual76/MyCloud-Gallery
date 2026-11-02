package dev.pampa.mycloudgallery.feature.widget.frame

import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize

/**
 * Le tre misure di D3 dalla grandezza vera del widget sulla Home (in dp). **Una sola misura per widget alla volta** (il registro tiene un piano per widget, D9: un
 * tocco deve sapere quali celle ci sono): il widget e' composto a `SizeMode.Single` e ripianificato quando il launcher lo ridimensiona (`onAppWidgetOptionsChanged`),
 * non a `SizeMode.Responsive`, che consegna al launcher tre disegni (con tutte le loro bitmap, fuori dai 2 MiB di D7) e lascia a lui la scelta.
 *
 * - **piccola** (2x2: una foto, o la copertina con il titolo): sotto [WIDE_MIN_WIDTH_DP] di larghezza;
 * - **larga** (4x2: la copertina piu' tre foto, o quattro foto): almeno [WIDE_MIN_WIDTH_DP] di larghezza e meno di [LARGE_MIN_HEIGHT_DP] di altezza;
 * - **grande** (4x4: la copertina e sei foto): almeno [WIDE_MIN_WIDTH_DP] per [LARGE_MIN_HEIGHT_DP].
 *
 * Le soglie stanno fra le misure di una griglia di Android (una cella da `70n - 30` dp: 2 celle = 110 dp, 3 = 180, 4 = 250) e sono larghe: una Home con celle piu'
 * grandi o piu' piccole cade nella misura giusta lo stesso.
 */
object WidgetSizing {
  const val WIDE_MIN_WIDTH_DP: Int = 220
  const val LARGE_MIN_HEIGHT_DP: Int = 220

  /** Se il launcher non dice la misura (zeri), vale il minimo dichiarato dal widget (2x2): la misura piccola. */
  fun classify(widthDp: Int, heightDp: Int): WidgetSize = when {
    widthDp < WIDE_MIN_WIDTH_DP -> WidgetSize.SMALL
    heightDp < LARGE_MIN_HEIGHT_DP -> WidgetSize.WIDE
    else -> WidgetSize.LARGE
  }

  /**
   * La misura da `AppWidgetManager.getAppWidgetOptions` (minimi e massimi in dp): in **verticale** il widget e' largo quanto il minimo e alto quanto il massimo, in
   * **orizzontale** il contrario (la regola documentata di Android). Valori mancanti o non positivi non ci sono: se mancano, [classify] cade sulla misura piccola.
   */
  fun fromOptions(minWidthDp: Int, maxWidthDp: Int, minHeightDp: Int, maxHeightDp: Int, portrait: Boolean): Pair<Int, Int> {
    val width = if (portrait) minWidthDp else maxWidthDp
    val height = if (portrait) maxHeightDp else minHeightDp
    return width.coerceAtLeast(0) to height.coerceAtLeast(0)
  }
}
