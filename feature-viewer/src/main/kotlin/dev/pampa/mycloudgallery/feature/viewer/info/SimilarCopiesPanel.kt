package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopies
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat

// Fase 5A, P15 (docs/fase5-progetto.md §2.12; riga P15 del §4.4): il PUNTO D'INGRESSO della sezione "N copie". Il file del pannello
// (`InfoPanel.kt`, `InfoBuilder.kt`) e il costruttore dei servizi (`ViewerServices.kt`) li modifica P5: questa sezione NON e' agganciata.
// P7 la aggancia in tre punti, descritti in [rememberSimilarCopiesPanel].

/**
 * La sezione e il foglio di conferma che il viewer monta.
 *
 * @property sections da aggiungere alle sezioni del pannello (vuote se il media non ha copie, se non c'e' il rilevatore o se il
 *   pannello e' chiuso). Va dopo la sezione del file e prima di quella del telefono, o dove P7 preferisce: e' una sezione come le altre.
 * @property confirmSheet il foglio "Separare questa copia?": un composable da chiamare **una volta** accanto agli altri fogli del
 *   viewer (`DeleteSheet`, `MapsSheet`), dentro lo stesso `FluidGlassModalHost`. Non disegna niente finche' non serve.
 */
@Immutable
internal class SimilarCopiesPanel(
  val sections: List<InfoSectionModel>,
  val confirmSheet: @Composable () -> Unit,
) {
  companion object {
    /** Nessuna sezione e nessun foglio: il viewer senza rilevatore (capacita' assente, D13). */
    val NONE: SimilarCopiesPanel = SimilarCopiesPanel(emptyList()) {}
  }
}

/**
 * Lo stato e le azioni della sezione "N copie" per il media [id] mentre il pannello e' aperto ([active]).
 *
 * **Come e' agganciata** (P7; H2 per il posto nel pannello), in tre punti:
 * 1. `ViewerServices` ha il campo `similarCopies: SimilarCopies?` (null = capacita' assente: nessuna sezione), legato in `:app` al
 *    rilevatore vero (`CatalogSimilarCopies`).
 * 2. `ViewerOverlay` la chiede con il pannello aperto e la passa a `InfoContent` fra le sezioni in piu', dopo la mappa e le persone.
 * 3. Dove si montano gli altri fogli (`DeleteSheet`, `MapsSheet`): `similar.confirmSheet()`.
 *
 * @param refreshKey cambia quando il catalogo cambia (di norma il `seq` dell'istantanea): la sezione rilegge il gruppo, cosi' un nascosto
 *   fatto altrove o una copia arrivata dal NAS compaiono a pannello aperto.
 * @param onView il viewer va alla copia [MediaId] del gruppo (tocco su una riga).
 */
@Composable
internal fun rememberSimilarCopiesPanel(
  source: SimilarCopies?,
  id: MediaId,
  active: Boolean,
  refreshKey: Long = 0L,
  onView: (MediaId) -> Unit,
): SimilarCopiesPanel {
  if (source == null || !active) return SimilarCopiesPanel.NONE
  val model: SimilarCopiesViewModel = viewModel(key = "$VIEW_MODEL_KEY-${System.identityHashCode(source)}", factory = SimilarCopiesViewModel.factory(source))
  LaunchedEffect(model, id, refreshKey) { model.show(id) }
  val state by model.state.collectAsState()
  val context = LocalContext.current
  val latestOnView by rememberUpdatedState(onView)
  val current = state
  val sections = remember(current, context) {
    if (current == null || current.viewing != id) {
      emptyList()
    } else {
      listOf(
        similarCopiesInfoSection(
          format = ViewerFormat(context),
          group = current.group,
          viewing = current.viewing,
          actions = SimilarCopiesActions(onView = { latestOnView(it) }, onMakeMain = model::makeMain, onSeparate = model::askSeparate),
          busy = current.busy,
        ),
      )
    }
  }
  return SimilarCopiesPanel(sections) {
    SeparateCopySheet(visible = current?.confirmSeparate == true, onConfirm = model::confirmSeparate, onCancel = model::cancelSeparate)
  }
}

private const val VIEW_MODEL_KEY = "viewer_similar_copies"

/**
 * Il foglio di conferma di "Separa": una conferma leggera (un tocco sul bottone), perche' non toglie niente a nessuno: la copia diventa
 * una foto a sé e si puo' ancora guardare. E' il foglio del design system, con la difesa dal tapjacking di tutti gli altri.
 */
@Composable
internal fun SeparateCopySheet(visible: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
  val title = stringResource(R.string.viewer_similar_separate_title)
  GalleryConfirmSheet(visible = visible, onDismissRequest = onCancel, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.CallSplit,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(R.string.viewer_similar_separate_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.viewer_similar_separate_confirm),
          onClick = onConfirm,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(
          text = stringResource(R.string.viewer_cancel),
          onClick = onCancel,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}
