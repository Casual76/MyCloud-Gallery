package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// LA PAGINA "ANALISI DELLE FOTO" (Pacchetto X; grafica XU, 12/11/2026). Due pezzi e basta:
//  - **la testata**, sorella di quella del Backup ([AnalysisHero]): anello grande con le foto pronte, il segno dello stato, due parole, una riga, il
//    tasto quando serve;
//  - **il nastro** ([AnalysisLane]): le foto e i video che passano nel mirino mentre vengono analizzati; a riposo, nel mirino, l'icona del perche'
//    con le ultime analizzate ferme accanto.
// Sul telefono stanno uno sopra l'altro e il nastro prende il resto dello schermo; sul tablet in orizzontale stanno affiancati e il nastro usa tutta la
// larghezza (la misura di lettura della pagina e' larga apposta). Niente etichette da modulo («ADESSO», «PROSSIME»): lo dice il mirino.
// Superfici piene, nessun vetro; la pagina legge due flussi gia' pronti (stato e nastro) e non interroga mai il database.

/** Che cosa la pagina puo' chiedere: far partire l'analisi, o il rimedio di un problema. */
internal interface AnalysisHandlers {
  fun onAnalyzeNow()

  fun onCodeAction(action: CodeAction)
}

internal object NoAnalysisHandlers : AnalysisHandlers {
  override fun onAnalyzeNow() = Unit

  override fun onCodeAction(action: CodeAction) = Unit
}

@Composable
internal fun AnalysisScreen(
  status: LibraryAnalysisStatus,
  flow: AnalysisFlow,
  thumbnails: SearchThumbnails?,
  handlers: AnalysisHandlers,
  onBack: () -> Unit,
  frozenShift: Float? = null,
) {
  val resources = LocalResources.current
  // XU-15: la finestra del caricatore la apre il nastro, solo per le anteprime che entrano nello schermo (AnalysisLane).
  val buttons = heroButtons(status, handlers)
  FluidScreen(
    title = stringResource(R.string.analysis_page_title),
    onBack = onBack,
    contentMaxWidth = SearchDimens.AnalysisPageMaxWidth,
  ) {
    item(key = "analysis-stage") { AnalysisStage(status, flow, thumbnails, buttons, frozenShift) }
  }
  // Uno stato che cambia da solo si annuncia una volta (come la testata del backup); «tutto pronto» non e' una notizia. XU-17: senza il conto, che
  // cambia a ogni foto (la regione viva riparlerebbe di continuo).
  if (status != LibraryAnalysisStatus.AllReady) {
    analysisSentence(resources, status, withCount = false).takeIf { it.isNotEmpty() }?.let { GalleryLiveAnnouncement(it) }
  }
}

/** Testata e nastro: affiancati se c'e' larghezza (tablet in orizzontale), altrimenti uno sopra l'altro con il nastro che prende il resto. */
@Composable
private fun LazyItemScope.AnalysisStage(
  status: LibraryAnalysisStatus,
  flow: AnalysisFlow,
  thumbnails: SearchThumbnails?,
  buttons: List<AnalysisHeroButton>,
  frozenShift: Float?,
) {
  val tall = Modifier.fillParentMaxHeight(StageOfScreenSide).heightIn(min = SearchDimens.LaneMinHeight)
  val lane = Modifier.fillParentMaxHeight(LaneOfScreen).heightIn(min = SearchDimens.LaneMinHeight)
  // XU2-13 (giro 4): il telefono in orizzontale (poca altezza) affianca anche sotto la larghezza del tablet, o il nastro finirebbe sotto la piega.
  val short = LocalConfiguration.current.screenHeightDp.dp < SearchDimens.AnalysisShortHeight
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    if (maxWidth >= SearchDimens.AnalysisSideBySide || (short && maxWidth >= SearchDimens.AnalysisSideBySideShort)) {
      // XU-8: la testata in alto, sotto il titolo (non a meta' altezza); il nastro prende tutta l'altezza accanto. Con poca altezza la riga e' alta
      // quanto la testata o il nastro (il piu' alto dei due), cosi' la testata non si taglia.
      Row(
        modifier = (if (short) Modifier else tall).fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap),
      ) {
        FluidListGroup(modifier = Modifier.width(SearchDimens.AnalysisHeroSideWidth)) { AnalysisHero(status, buttons = buttons, lineGlyph = false) }
        AnalysisLane(
          status,
          flow,
          thumbnails,
          modifier = Modifier.weight(1f).then(if (short) Modifier.fillParentMaxHeight(StageOfScreenSide).heightIn(min = SearchDimens.LaneMinHeight) else Modifier.fillMaxHeight()),
          bleed = SearchDimens.LaneNoBleed,
          frozenShift = frozenShift,
        )
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap)) {
        FluidListGroup { AnalysisHero(status, buttons = buttons, lineGlyph = false) }
        AnalysisLane(status, flow, thumbnails, modifier = lane.fillMaxWidth(), frozenShift = frozenShift)
      }
    }
  }
}

/**
 * I tasti della testata: il rimedio di un problema (o del modello che manca) pieno, perche' e' la cosa da fare; «Analizza ora» tonale in coda o in
 * una pausa che una richiesta supera ([analyzeNowShown]), perche' e' una possibilita'. Chi non analizza, chi ha finito, chi l'ha spenta, chi aspetta il
 * NAS o il Wi-Fi e le foto in preparazione non hanno tasti.
 */
@Composable
private fun heroButtons(status: LibraryAnalysisStatus, handlers: AnalysisHandlers): List<AnalysisHeroButton> {
  val codes = when (status) {
    is LibraryAnalysisStatus.Problem -> SearchCodes.actions(DiagCode(status.code))
    LibraryAnalysisStatus.ModelMissing -> SearchCodes.actions(DiagCode(MODEL_MISSING))
    else -> emptyList()
  }
  if (codes.isNotEmpty()) {
    return codes.mapIndexed { index, action ->
      AnalysisHeroButton(stringResource(action.label), codeActionIcon(action), filled = index == 0) { handlers.onCodeAction(action) }
    }
  }
  // XU2-4 (giro 4): solo dove una richiesta la fa davvero partire (la stessa regola di Ricerca e modelli).
  return if (analyzeNowShown(status)) {
    listOf(AnalysisHeroButton(stringResource(R.string.models_index_now), Icons.Rounded.PlayArrow, filled = false, onClick = handlers::onAnalyzeNow))
  } else {
    emptyList()
  }
}

private const val MODEL_MISSING = "AI_MODEL_MISSING"

/** Il nastro sul telefono prende circa meta' dello schermo (sotto la testata: lo riempie); affiancato, quasi tutta l'altezza sotto il titolo. */
private const val LaneOfScreen = 0.5f
private const val StageOfScreenSide = 0.8f
