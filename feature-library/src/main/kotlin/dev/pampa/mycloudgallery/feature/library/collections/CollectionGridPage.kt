package dev.pampa.mycloudgallery.feature.library.collections

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryActionBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.ActionNoticeCard
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.photoVideoSummary
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import kotlinx.coroutines.launch

/**
 * Un'azione sulla selezione di una griglia di questo modulo: un'icona, cosa dice a voce e cosa fa sull'insieme scelto.
 * Le azioni dipendono da dove si e' (Preferiti toglie, i Nascosti mostrano) e da cio' che e' selezionato.
 */
@Immutable
internal class SelectionAction(
  val icon: ImageVector,
  @StringRes val labelRes: Int,
  val run: (Set<MediaId>) -> Unit,
)

/**
 * La pagina di una griglia di raccolta: **lo stesso `MediaGrid` e lo stesso viewer** della Libreria (D17), con la barra
 * di vetro in cima, lo stato vuoto, il riepilogo in fondo e la barra delle azioni quando c'e' una selezione.
 *
 * E' scritta una volta e la usano le raccolte-filtro e i Nascosti. La vista viene da [items] e la segue: quando l'indice
 * cambia (un preferito tolto, un elemento nascosto) la griglia resta sullo stesso elemento.
 *
 * @param secure vero per i Nascosti: il viewer aperto da qui vale con `SecureScreen` (E-S5).
 * @param onBack il tasto indietro della barra; null nel pannello di dettaglio del tablet, dove la raccolta non e' una
 *   pagina che si chiude.
 * @param backdropOut consegna a `:app` il fondale che questa pagina disegna, perche' la pillola lo rifranga; null dove
 *   la pillola non c'e' (rotte a schermo intero).
 * @param footnote una nota sotto il riepilogo (i Nascosti dicono che nascondere vale solo in app).
 * @param actionsFor le azioni della barra per la selezione di adesso; vuoto = nessuna barra.
 * @param notice l'azione che non e' riuscita (R8: nascondi, preferito, mostra), sotto la barra finche' non si chiude.
 */
@Composable
internal fun CollectionGridPage(
  title: String,
  items: ViewerItems,
  environment: GridEnvironment,
  secure: Boolean,
  onOpenViewer: (ViewerRequest) -> Unit,
  empty: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  footnote: String? = null,
  actionsFor: (TimelineView, Set<MediaId>) -> List<SelectionAction> = { _, _ -> emptyList() },
  notice: ActionNotice? = null,
  onDismissNotice: () -> Unit = {},
) {
  val view by items.view.collectAsState()
  // La griglia tiene il suo stato (scorrimento, livello, selezione) finche' gli elementi sono gli stessi: una vista nuova
  // (l'indice e' cambiato: un preferito tolto, un elemento nascosto) la rimette sullo stesso elemento. Solo il passaggio
  // da vuota a piena rifa' lo stato, perche' una griglia vuota non ha niente da ancorare.
  val grid = remember(items, environment, view.size == 0) { environment.gridStateFor(view, items) }
  SideEffect { if (grid.view !== view) grid.view = view }
  val selected by grid.selection.selected.collectAsState()
  val selecting = grid.selecting

  val backdrop = rememberGlassBackdrop()
  if (backdropOut != null) SideEffect { backdropOut(backdrop) }
  BackHandler(enabled = selecting) { grid.stopSelecting() }
  val scope = rememberCoroutineScope()

  val density = LocalDensity.current
  var barHeightPx by remember { mutableIntStateOf(0) }
  val barHeight = with(density) { barHeightPx.toDp() }
  // Una raccolta-filtro ha al piu' qualche decina di migliaia di elementi: il conteggio costa meno di un fotogramma.
  val summary = remember(view) { photoAndVideoCounts(view) }

  // In selezione la barra delle azioni sta sopra la fine della lista: l'ultima riga deve poterci scorrere sopra.
  val gridBottom = contentPadding.calculateBottomPadding() + if (selecting) CollectionsDimens.ActionBarSlot else CollectionsDimens.None

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      if (view.size == 0) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(top = barHeight, bottom = contentPadding.calculateBottomPadding()),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          empty()
          GridFootnote(summary = null, footnote = footnote)
        }
      } else {
        MediaGrid(
          state = grid,
          contentPadding = PaddingValues(top = barHeight, bottom = gridBottom),
          onOpen = { id -> onOpenViewer(ViewerRequest(items = items, start = id, anchor = grid.viewerAnchor, secure = secure)) },
          footer = { GridFootnote(summary = summary, footnote = footnote) },
          backdrop = backdrop,
          // L'intestazione agganciata sta subito sotto la riga dei controlli, come nella Libreria.
          stickyTop = (barHeight - FluidScreenDefaults.GlassFadeTail).coerceAtLeast(CollectionsDimens.None),
          // Il livello scelto con il pinch vale per tutta la galleria: come nella Libreria, si ricorda.
          onLevelChanged = { level -> scope.launch { environment.preferences.setLevel(level) } },
        )
      }
    }

    GalleryTopBar(
      title = when {
        !selecting -> title
        selected.isEmpty -> stringResource(R.string.collection_select_items)
        else -> countText(R.plurals.selection_count, selected.size)
      },
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { barHeightPx = it.height },
      subtitle = if (selecting) null else countText(R.plurals.collection_count, view.size),
      onNavigate = if (selecting) ({ grid.stopSelecting() }) else onBack,
      navigationIcon = if (selecting) GalleryNavigationIcon.Close else GalleryNavigationIcon.Back,
      actions = {
        if (!selecting && view.size > 0) {
          GalleryBarButton(
            icon = Icons.Rounded.CheckCircleOutline,
            contentDescription = stringResource(R.string.collection_select),
            onClick = { grid.startSelecting() },
          )
        }
      },
    )

    if (notice != null) {
      ActionNoticeCard(
        notice = notice,
        backdrop = backdrop,
        onDismiss = onDismissNotice,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = barHeight, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter),
      )
    }

    if (selecting && !selected.isEmpty) {
      val specs = actionsFor(view, selected.ids)
      if (specs.isNotEmpty()) {
        val actions = specs.map { spec ->
          val label = stringResource(spec.labelRes)
          GalleryBarAction(
            icon = spec.icon,
            contentDescription = label,
            onClick = {
              spec.run(selected.ids)
              grid.stopSelecting()
            },
          )
        }
        GalleryActionBar(
          actions = actions,
          backdrop = backdrop,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = contentPadding.calculateBottomPadding() + CollectionsDimens.ActionBarGap),
        )
      }
    }
  }
}

/** Il riepilogo "N foto, M video" e, se c'e', la nota in fondo alla griglia (o allo stato vuoto). */
@Composable
private fun GridFootnote(summary: Pair<Int, Int>?, footnote: String?) {
  if (summary == null && footnote == null) return
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = GalleryDimens.Gutter, vertical = CollectionsDimens.FooterPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (summary != null) {
      Text(
        text = photoVideoSummary(summary.first, summary.second),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    if (footnote != null) {
      Text(
        text = footnote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}
