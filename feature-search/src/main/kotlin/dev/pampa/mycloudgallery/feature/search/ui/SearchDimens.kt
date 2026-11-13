package dev.pampa.mycloudgallery.feature.search.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout della ricerca e della pagina Ricerca e modelli, in un posto solo (e' l'unico file del modulo che scrive dei `dp`:
 * `NoHandWrittenStyleTest` lo verifica). Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`,
 * `FluidMotion`); i margini di pagina e i bersagli da 48 dp da `GalleryDimens`.
 */
internal object SearchDimens {

  /** Il lato minimo di una cella dei risultati: sul telefono 3 colonne, sul tablet tante quante ne entrano. */
  val CellMin: Dp = 92.dp

  /** Lo spazio fra due blocchi di una pagina. */
  val BlockGap: Dp = 12.dp

  /** Lo spazio stretto fra due elementi della stessa riga (pillole, pulsanti). */
  val TightGap: Dp = 8.dp

  /** Il margine sopra e sotto un avviso o un blocco di testo. */
  val NoticeInset: Dp = 8.dp

  /** La larghezza massima del testo di una pagina di lettura (attivazione): righe corte anche sul tablet. */
  val ReadingWidth: Dp = 560.dp

  /** L'intestazione dei meno probabili, sopra le loro celle nella griglia dei risultati (H4, CU-3). */
  val DividerHeight: Dp = 40.dp

  /** Il filo fra i sicuri e i meno probabili: lo spazio che occupa nella griglia e lo spessore della linea (CU-3). */
  val BoundaryHeight: Dp = 16.dp
  val BoundaryLine: Dp = 1.dp

  // --- I2: la pagina Ricerca e modelli con anelli e segni (gli anelli: `GalleryProgressRing` con le sue misure, giro 1) ---------------------------------

  /** Il margine interno della scheda della copertura. */
  val HeroPadding: Dp = 16.dp

  /** Il glifo dentro l'anello della copertura (fatto, modello che manca), dentro l'anello di un modello e accanto a un numero. */
  val HeroGlyph: Dp = 44.dp
  val ModelGlyph: Dp = 24.dp
  val InlineGlyph: Dp = 18.dp

  /** Fra due piastrelle affiancate (le aree, i modelli). */
  val TileGap: Dp = 10.dp

  /** Il margine interno di una piastrella (aree, modelli). */
  val TilePadding: Dp = 12.dp

  // --- Pacchetto X / XU: l'analisi delle foto (testata sorella di quella del Backup, il nastro delle anteprime) ---------------------------------

  /** La testata: gli stessi numeri di `BackupDimens` (margine 20, fra le righe 10 e 4, segno sull'anello 30 rientrato di 8, glifo al centro 40). */
  val AnalysisHeroPadding: Dp = 20.dp
  val AnalysisHeroGap: Dp = 10.dp
  val AnalysisLineGap: Dp = 4.dp
  val AnalysisHeroMark: Dp = 30.dp
  val AnalysisHeroMarkInset: Dp = 8.dp
  val AnalysisHeroGlyph: Dp = 40.dp

  /** Quanto i numeri dentro l'anello restano lontani dal bordo (il tratto dell'anello grande e un respiro). */
  val AnalysisRingInset: Dp = 16.dp

  /** Il nastro: l'anteprima di adesso (fra il minimo e il massimo, secondo lo spazio), le altre in fila, lo spazio fra loro. */
  val LaneCurrentMin: Dp = 148.dp
  val LaneCurrentMax: Dp = 340.dp
  val LaneSideMin: Dp = 64.dp
  val LaneSideMax: Dp = 112.dp
  val LaneGap: Dp = 12.dp

  /** Il mirino intorno all'anteprima di adesso: quanto sta fuori, quanto e' lungo un angolo, il tratto, quanto si stringe all'arrivo. */
  val LaneGateInset: Dp = 10.dp
  val LaneGateCorner: Dp = 26.dp
  val LaneGateStroke: Dp = 4.dp
  val LaneGateFocus: Dp = 10.dp

  /** Il segno «fatto» sulle finite e il segno del video, a misura fissa (non scalano con l'anteprima). */
  val LaneMark: Dp = 22.dp
  val LaneVideoGlyph: Dp = 20.dp

  /** L'altezza minima del nastro (telefono in orizzontale, testo grande) e la parte dello schermo che prende. */
  val LaneMinHeight: Dp = 220.dp

  /** Quanto il nastro passa oltre i suoi limiti: sul telefono il margine della pagina (da bordo a bordo), accanto alla testata niente. */
  val LaneNoBleed: Dp = 0.dp

  /** Da questa larghezza testata e nastro stanno affiancati (tablet in orizzontale); la testata li' e' larga cosi'. */
  val AnalysisSideBySide: Dp = 840.dp

  /** XU2-13 (giro 4): con poca altezza (il telefono in orizzontale, sotto [AnalysisShortHeight]) testata e nastro si affiancano gia' da questa larghezza. */
  val AnalysisSideBySideShort: Dp = 600.dp

  /** Sotto questa altezza dello schermo la pagina dell'analisi e' "bassa" (il telefono in orizzontale). */
  val AnalysisShortHeight: Dp = 480.dp
  val AnalysisHeroSideWidth: Dp = 380.dp

  /** La misura di lettura della pagina "Analisi delle foto": larga, perche' il nastro usa la larghezza del tablet. */
  val AnalysisPageMaxWidth: Dp = 1240.dp
}
