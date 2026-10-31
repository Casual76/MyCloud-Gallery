package dev.pampa.mycloudgallery.core.designsystem.adaptive

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

// LE REGOLE DEI PANNELLI (Fase 10, P5, D12, D13; docs/fase10-progetto.md §2.3, §1.5 `PanesState`). **File nuovo** in `adaptive/`. Una tabella sola, pura e provata sulla JVM, che dice per ogni
// schermata quanti pannelli ha una finestra: le schermate non misurano da sole, leggono [GalleryWindow] e chiedono qui. Le soglie sono quelle di D12 (compatta < 600, media 600-839, estesa
// 840-1199, ampia >= 1200) e **non cambiano** rispetto alle soglie di prima (840: Raccolte, viewer, Onboarding, Cartelle NAS). Le proporzioni sono quelle di D13; come si disegnano (divisore,
// margini) lo decide la fase grafica.

/** Le due finestre di una finestra divisa in due pannelli: ognuna e' una [GalleryWindow] piu' stretta, che le schermate dentro leggono al posto di quella dell'app. */
@Immutable
data class PaneWindows(
  /** Il pannello della griglia: a sinistra nella Libreria affiancata al viewer, a destra nella mappa con la griglia della zona. */
  val grid: GalleryWindow,
  /** L'altro pannello: il viewer, o la mappa. */
  val other: GalleryWindow,
)

object GalleryPaneRules {
  /** L'elenco di Raccolte e Impostazioni (il `FluidListDetailScaffold` dell'engine lo porta fra 360 e 500 dp). */
  const val LIST_FRACTION: Float = 0.4f

  /** La griglia della zona, a destra della mappa (la mappa e' il 60%, D13 (2)). */
  const val ZONE_FRACTION: Float = 0.4f

  /** La Libreria a sinistra del viewer (il viewer e' il 60%, D13 (5)). */
  const val DOCKED_GRID_FRACTION: Float = 0.4f

  /** Da qui in su elenco e dettaglio, mappa e griglia della zona stanno uno accanto all'altro (840 dp: la soglia di sempre). */
  fun twoPanes(window: GalleryWindow): Boolean = window.width >= GalleryWidth.EXPANDED

  /** Il viewer mostra le informazioni in una colonna a destra (da estesa, 840 dp: la soglia di sempre) invece che in un foglio. */
  fun infoBeside(window: GalleryWindow): Boolean = window.width >= GalleryWidth.EXPANDED

  /** Da qui in su si puo' affiancare il viewer alla Libreria (1200 dp, e solo a scelta). */
  fun canDock(window: GalleryWindow): Boolean = window.width >= GalleryWidth.LARGE

  /** Raccolte e Impostazioni: elenco e dettaglio da estesa, una pagina sola sotto. */
  fun listDetail(window: GalleryWindow): GalleryPanes =
    if (twoPanes(window)) GalleryPanes.ListDetail(LIST_FRACTION) else GalleryPanes.Single

  /** La mappa: da estesa, con una zona aperta, la griglia della zona sta a destra; senza zona (o sotto 840 dp) la mappa e' intera. */
  fun mapZone(window: GalleryWindow, zoneOpen: Boolean): GalleryPanes =
    if (twoPanes(window) && zoneOpen) GalleryPanes.Docked(ZONE_FRACTION) else GalleryPanes.Single

  /**
   * La Libreria con il viewer: affiancati solo da ampia, solo con il viewer aperto e solo se l'utente ha scelto "Affianca" ([wantsDock]); altrimenti il viewer e' a finestra intera
   * (D13 (5)).
   */
  fun libraryViewer(window: GalleryWindow, viewerOpen: Boolean, wantsDock: Boolean): GalleryPanes =
    if (canDock(window) && viewerOpen && wantsDock) GalleryPanes.Docked(DOCKED_GRID_FRACTION) else GalleryPanes.Single

  /** La larghezza in dp del pannello della griglia in una finestra divisa. */
  fun gridWidthDp(window: GalleryWindow, panes: GalleryPanes.Docked): Int = (window.widthDp * panes.gridFraction).roundToInt().coerceIn(1, window.widthDp - 1)

  /** Le finestre dei due pannelli: le larghezze si sommano a quella della finestra (nessun dp perso negli arrotondamenti). */
  fun split(window: GalleryWindow, panes: GalleryPanes.Docked): PaneWindows {
    val grid = gridWidthDp(window, panes)
    return PaneWindows(grid = window.withWidthDp(grid), other = window.withWidthDp(window.widthDp - grid))
  }
}
