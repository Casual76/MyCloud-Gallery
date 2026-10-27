package dev.pampa.mycloudgallery.feature.library.collections

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.design.ActionBarHeight
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.ActionNoticeCard
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.grid.ChromeLabelText
import dev.pampa.mycloudgallery.feature.library.grid.GridSelectionBar
import dev.pampa.mycloudgallery.feature.library.grid.GridScrubber
import dev.pampa.mycloudgallery.feature.library.grid.GridTopChrome
import dev.pampa.mycloudgallery.feature.library.grid.GridTopEdgeScrim
import dev.pampa.mycloudgallery.feature.library.grid.gridTopChromeHeight
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import dev.pampa.mycloudgallery.feature.library.grid.rememberMediaGridSection
import dev.pampa.mycloudgallery.feature.library.photoVideoSummary
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
 * La pagina di una griglia di raccolta: **lo stesso `MediaGrid`, lo stesso viewer e la stessa chrome** della Libreria
 * (D17; G2, docs/grafica-progetto.md §12): in alto la riga di vetro adattivo (indietro, l'etichetta con la sezione in vista
 * e il nome della raccolta, "Seleziona"), lo stato vuoto, il riepilogo in fondo, e in selezione la barra delle azioni nel
 * posto della pillola (o sopra la pillola, dove c'e': il pannello di dettaglio del tablet). La griglia non si sposta
 * entrando in selezione.
 *
 * E' scritta una volta e la usano le raccolte-filtro e i Nascosti. La vista viene da [items] e la segue: quando l'indice
 * cambia (un preferito tolto, un elemento nascosto) la griglia resta sullo stesso elemento.
 *
 * @param secure vero per i Nascosti: il viewer aperto da qui vale con `SecureScreen` (E-S5).
 * @param onBack il tasto indietro; null nel pannello di dettaglio del tablet, dove la raccolta non e' una pagina che si
 *   chiude.
 * @param contentPadding lo spazio della pillola in basso, se la pagina ce l'ha sotto (il tablet); zero a schermo intero.
 * @param backdropOut consegna a `:app` il fondale che questa pagina disegna, perche' la pillola lo rifranga; null dove
 *   la pillola non c'e' (rotte a schermo intero).
 * @param footnote una nota sotto il riepilogo (i Nascosti dicono che nascondere vale solo in app).
 * @param actionsFor le azioni della barra per la selezione di adesso; vuoto = nessuna barra.
 * @param notice l'azione che non e' riuscita (R8: nascondi, preferito, mostra), sotto la chrome finche' non si chiude.
 * @param pickMode il selettore dei media (Fase 7, P13): la pagina parte gia' in selezione e, quando la selezione finisce ("Fine", Indietro), esce
 *   con [onBack]: si sceglie e basta, la griglia non apre il viewer.
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
  pickMode: Boolean = false,
) {
  val view by items.view.collectAsState()
  // La griglia tiene il suo stato (scorrimento, livello, selezione) finche' gli elementi sono gli stessi: una vista nuova
  // (l'indice e' cambiato: un preferito tolto, un elemento nascosto) la rimette sullo stesso elemento. Solo il passaggio
  // da vuota a piena rifa' lo stato, perche' una griglia vuota non ha niente da ancorare.
  val grid = remember(items, environment, view.size == 0) { environment.gridStateFor(view, items) }
  SideEffect { if (grid.view !== view) grid.view = view }
  val selected by grid.selection.selected.collectAsState()
  val selecting = grid.selecting
  if (pickMode) {
    // Il selettore e' una selezione: parte selezionando e, finita la selezione, si esce (niente griglia "normale" da cui aprire il viewer).
    var started by remember(grid) { mutableStateOf(false) }
    LaunchedEffect(grid, selecting) {
      when {
        !started && !selecting -> grid.startSelecting()
        selecting -> started = true
        else -> onBack?.invoke()
      }
    }
  }

  val backdrop = rememberGlassBackdrop()
  if (backdropOut != null) SideEffect { backdropOut(backdrop) }
  BackHandler(enabled = selecting) { grid.stopSelecting() }
  val scope = rememberCoroutineScope()

  val topChrome = gridTopChromeHeight()
  val slotBottom = collectionActionSlot(contentPadding)
  // Lo spazio in fondo e' lo stesso dentro e fuori dalla selezione: la griglia non si sposta quando arriva la barra.
  val gridBottom = slotBottom + ActionBarHeight + GalleryChromeDefaults.Gap
  // Una raccolta-filtro ha al piu' qualche decina di migliaia di elementi: il conteggio costa meno di un fotogramma.
  val summary = remember(view) { photoAndVideoCounts(view) }
  val section = rememberMediaGridSection(grid, topChrome)
  val label = when {
    selecting && selected.isEmpty -> ChromeLabelText(stringResource(R.string.collection_select_items), null)
    selecting -> ChromeLabelText(countText(R.plurals.selection_count, selected.size), null)
    // Scorrendo, la sezione in vista; sotto, il nome della raccolta: si sa sempre dove si e'.
    section != null -> ChromeLabelText(section.title, listOfNotNull(title, section.place).joinToString(" · "))
    else -> ChromeLabelText(title, countText(R.plurals.collection_count, view.size))
  }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true }) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      if (view.size == 0) {
        Column(
          modifier = Modifier.fillMaxSize().padding(top = topChrome, bottom = contentPadding.calculateBottomPadding()),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          empty()
          GridFootnote(summary = null, footnote = footnote)
        }
      } else {
        MediaGrid(
          state = grid,
          contentPadding = PaddingValues(top = topChrome, bottom = gridBottom),
          onOpen = { id -> onOpenViewer(ViewerRequest(items = items, start = id, anchor = grid.viewerAnchor, secure = secure)) },
          footer = { GridFootnote(summary = summary, footnote = footnote) },
          stickyTop = topChrome,
          // Il livello scelto con il pinch vale per tutta la galleria: come nella Libreria, si ricorda.
          onLevelChanged = { level -> scope.launch { environment.preferences.setLevel(level) } },
        )
      }
      GridTopEdgeScrim(topChrome, Modifier.align(Alignment.TopCenter))
    }

    GridTopChrome(
      grid = grid,
      backdrop = backdrop,
      label = label,
      topChrome = topChrome,
      modifier = Modifier.align(Alignment.TopStart),
      // In selezione si esce con "Fine" (o con Indietro): la freccia porterebbe via dalla pagina con la selezione aperta.
      onBack = if (selecting) null else onBack,
      showCommands = selecting || view.size > 0,
    ) {
      if (selecting) {
        GalleryChromeTextButton(label = stringResource(R.string.library_done), onClick = { grid.stopSelecting() }, modifier = Modifier.galleryChromeTag("fine"))
      } else {
        GalleryChromeIconButton(
          icon = Icons.Rounded.CheckCircleOutline,
          contentDescription = stringResource(R.string.collection_select),
          onClick = { grid.startSelecting() },
          modifier = Modifier.galleryChromeTag("seleziona"),
        )
      }
    }

    if (notice != null) {
      ActionNoticeCard(
        notice = notice,
        backdrop = backdrop,
        onDismiss = onDismissNotice,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = topChrome + GalleryChromeDefaults.Gap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter),
      )
    }

    if (view.size > 0) {
      GridScrubber(
        grid = grid,
        backdrop = backdrop,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = topChrome + GalleryChromeDefaults.Gap, bottom = gridBottom),
      )
    }

    val specs = if (selecting && !selected.isEmpty) actionsFor(view, selected.ids) else emptyList()
    // Mentre la barra sfuma via restano le azioni di prima: una capsula che si svuota uscendo sarebbe un salto.
    val shown = remember { mutableStateOf(specs) }
    if (specs.isNotEmpty()) shown.value = specs
    GridSelectionBar(
      visible = specs.isNotEmpty(),
      grid = grid,
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = slotBottom),
    ) {
      shown.value.map { spec ->
        GalleryBarAction(
          icon = spec.icon,
          contentDescription = stringResource(spec.labelRes),
          onClick = {
            spec.run(selected.ids)
            // Nel selettore la scelta la chiude chi l'ha aperto (oltre il massimo la selezione resta, con l'avviso).
            if (!pickMode) grid.stopSelecting()
          },
        )
      }
    }
  }
}

/**
 * Dove sta la barra delle azioni (o "Ripristina") in fondo: nel posto della pillola se la pagina non ne ha una sotto (la
 * rotta a schermo intero del telefono), sopra la pillola se c'e' ([contentPadding], il pannello di dettaglio del tablet).
 */
@Composable
internal fun collectionActionSlot(contentPadding: PaddingValues): Dp {
  val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val pill = contentPadding.calculateBottomPadding()
  return if (pill > CollectionsDimens.None) navigation + pill + GalleryChromeDefaults.Gap else navigation + FluidFloatingTabBarDefaults.BottomMargin
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
