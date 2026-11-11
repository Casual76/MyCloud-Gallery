package dev.pampa.mycloudgallery.feature.library.collections

import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.designsystem.instant.onFirstDraw
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidDetailContent
import dev.antigravity.fluidengine.ui.fluid.FluidDetailPlaceholder
import dev.antigravity.fluidengine.ui.fluid.FluidListDetailScaffold
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.adaptive.FluidPaneSizes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberGalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenRoute
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedRoute

/**
 * Fase 10 (P5, D13 (3)): chiude la pagina di una voce nel pannello di dettaglio (il suo "Indietro"): torna alla raccolta scelta prima. Fuori da un pannello (o sul telefono) non fa niente.
 */
val LocalCollectionDetailClose = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * La scheda **Raccolte** (D17, D33): le sezioni, una sotto l'altra, nell'ordine che l'utente ha scelto e senza quelle che ha nascosto.
 * Ogni sezione e' un **dato** ([CollectionEntryGroup], con un codice stabile) che un fornitore registrato consegna
 * ([CollectionsSectionProvider]: Album, Album smart, Viaggi, Utility, Nascosti... lo decide chi le possiede): la scheda non le conosce
 * una per una. Fa da sola le raccolte che c'erano gia' (Telefono: Preferiti, Video, Screenshot, WhatsApp; Da tenere al sicuro: Eliminati
 * di recente; Nascosti), che ogni fornitore con lo stesso codice puo' sostituire. Ogni voce di una raccolta-filtro apre una griglia con lo
 * stesso `MediaGrid` e lo stesso viewer della Libreria.
 *
 * - **"Modifica Raccolte"** (nel menu in alto, con le Impostazioni): le sezioni si trascinano dalla maniglia, o con le azioni di TalkBack "Sposta su" e "Sposta
 *   giu'", e un interruttore le mostra o le nasconde (Album no, mai). Ordine e visibilita' si sincronizzano fra i dispositivi
 *   dell'utente ([CollectionsLayoutStore]); una sezione nuova va in coda (E-L14).
 * - **Telefono**: l'elenco e basta; una voce toccata chiama [onOpenCollection] (`:app` la porta alla rotta giusta con
 *   `NavController.openCollection`) o, per le voci consegnate come dati, [onOpenEntry].
 * - **Tablet** (da 840 dp, secondo la finestra comune `GalleryWindow`: `FluidListDetailScaffold`): l'elenco delle sezioni a sinistra, la raccolta scelta a destra, nello stesso
 *   ordine del telefono. Le voci selezionano e basta, nessuna navigazione; per una voce di `:app` il dettaglio e' [entryDetail].
 *
 * @param contentPadding lo spazio che la pillola delle schede occupa in basso (il contenuto scorre sotto, rifratto).
 * @param backdropOut consegna a `:app` il fondale dell'elenco, che la pillola rifrange.
 * @param onOpenViewer il viewer e' una sovrapposizione ospitata da `:app` (D12): qui si manda solo la richiesta.
 * @param statusHeader l'indicatore globale della Fase 2, in cima come nelle altre pagine.
 * @param extraGroups i gruppi di `:app` (Fase 4B: "Sul NAS"; Fase 5B: Utility) che non hanno ancora un fornitore: ognuno e' una sezione,
 *   con il codice che dichiara o che si ricava dalle sue voci ([CollectionSections.codeOf]); nessuno di default.
 * @param onOpenEntry sul telefono, la voce di una sezione consegnata come dato (`:app` apre la sua rotta).
 * @param entryDetail sul tablet, la pagina di una voce nel pannello di dettaglio; null = la voce apre la rotta anche li'.
 * @param entryHasDetail sul tablet, se [entryDetail] ha una pagina per quella voce (Fase 7, P13): le voci che non ce l'hanno (la pagina di un album, che
 *   e' una rotta con la sua barra) si aprono con [onOpenEntry] anche li'.
 */
@Composable
fun CollectionsTab(
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  onOpenCollection: (CollectionKind) -> Unit,
  onOpenSettings: () -> Unit,
  statusHeader: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  extraGroups: List<CollectionEntryGroup> = emptyList(),
  onOpenEntry: (CollectionEntry) -> Unit = {},
  entryDetail: (@Composable (CollectionEntry) -> Unit)? = null,
  entryHasDetail: (CollectionEntry) -> Boolean = { true },
  viewModel: CollectionsLayoutViewModel = hiltViewModel(),
) {
  val inputs by viewModel.inputs.collectAsStateWithLifecycle()
  val state = remember(inputs, extraGroups) {
    CollectionsLayout.state(inputs.present, extraGroups, inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed)
  }
  CollectionsScreen(
    state = state,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    onOpenCollection = onOpenCollection,
    onOpenSettings = onOpenSettings,
    statusHeader = statusHeader,
    // Avvio istantaneo: l'apertura della scheda si chiude al primo fotogramma (le sezioni sono quelle gia' in memoria).
    modifier = modifier.onFirstDraw { AvvioTrace.pageDrawn(AvvioTrace.PAGE_COLLECTIONS) },
    onOpenEntry = onOpenEntry,
    entryDetail = entryDetail,
    entryHasDetail = entryHasDetail,
    onEdit = viewModel::startEditing,
    onDone = viewModel::stopEditing,
    onReorder = viewModel::reorder,
    onSetShown = viewModel::setShown,
    detail = { kind ->
      // Il pannello di dettaglio: la stessa pagina della rotta a schermo intero, senza il tasto indietro e senza la
      // pillola (e' sopra l'elenco): il fondale per la pillola lo da' l'elenco.
      when (kind) {
        CollectionKind.HIDDEN -> HiddenRoute(onOpenViewer = onOpenViewer, contentPadding = contentPadding)
        CollectionKind.RECENTLY_DELETED -> RecentlyDeletedRoute(contentPadding = contentPadding)
        else -> CollectionGridRoute(kind = kind, onOpenViewer = onOpenViewer, contentPadding = contentPadding)
      }
    },
  )
}

/**
 * La scheda per uno [state] dato: un pannello o due secondo la larghezza. Senza Hilt, cosi' la provano i test e gli
 * screenshot con un [detail] di prova.
 */
@Composable
internal fun CollectionsScreen(
  state: CollectionsLayoutState,
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenCollection: (CollectionKind) -> Unit,
  onOpenSettings: () -> Unit,
  statusHeader: @Composable () -> Unit,
  detail: @Composable (CollectionKind) -> Unit,
  modifier: Modifier = Modifier,
  initialSelection: CollectionKind? = null,
  onOpenEntry: (CollectionEntry) -> Unit = {},
  entryDetail: (@Composable (CollectionEntry) -> Unit)? = null,
  entryHasDetail: (CollectionEntry) -> Boolean = { true },
  onEdit: () -> Unit = {},
  onDone: () -> Unit = {},
  onReorder: (List<String>) -> Unit = {},
  onSetShown: (String, Boolean) -> Unit = { _, _ -> },
) {
  // Su due pannelli una raccolta e' sempre scelta: quella di prima, o la prima raccolta-filtro dell'elenco (mai i Nascosti da soli:
  // chiederebbero la credenziale senza che nessuno l'abbia toccato). Il codice sopravvive alla rotazione. Una voce consegnata come
  // dato ha il suo codice a parte: se sparisce (NAS scollegato, sezione nascosta) la scelta torna a una raccolta. Senza nessuna
  // raccolta che si vede (Telefono nascosto) il pannello dice di sceglierne una.
  var savedCode by rememberSaveable { mutableStateOf(initialSelection?.code) }
  var savedEntry by rememberSaveable { mutableStateOf<String?>(null) }
  val selectedKind = CollectionKind.fromCode(savedCode)?.takeIf { it in state } ?: state.firstFilter
  val selectedEntry = if (entryDetail != null) state.findEntry(savedEntry)?.takeIf(entryHasDetail) else null
  val selection: CollectionsSelection = when {
    selectedEntry != null -> CollectionsSelection.Entry(selectedEntry)
    selectedKind != null -> CollectionsSelection.Kind(selectedKind)
    else -> CollectionsSelection.None
  }

  // Fase 10 (P5, D12): uno o due pannelli lo decide la finestra comune (da 840 dp, la soglia di sempre), non una misura propria.
  val window = rememberGalleryWindow()
  FluidListDetailScaffold(
    ambient = null,
    modifier = modifier,
    sizes = FluidPaneSizes.listDetail(window),
    list = { twoPane ->
      CollectionsList(
        state = state,
        selectedKind = if (twoPane && selectedEntry == null) selectedKind else null,
        selectedEntry = if (twoPane) selectedEntry?.code else null,
        twoPane = twoPane,
        contentPadding = contentPadding,
        backdropOut = backdropOut,
        onSelectKind = { kind ->
          if (twoPane) {
            savedCode = kind.code
            savedEntry = null
          } else {
            onOpenCollection(kind)
          }
        },
        onSelectEntry = { entry -> if (twoPane && entryDetail != null && entryHasDetail(entry)) savedEntry = entry.code else onOpenEntry(entry) },
        onOpenSettings = onOpenSettings,
        statusHeader = statusHeader,
        onEdit = onEdit,
        onDone = onDone,
        onReorder = onReorder,
        onSetShown = onSetShown,
      )
    },
    detail = {
      FluidDetailContent(item = selection, key = { it.key }, order = { it.order(state) }) { chosen ->
        when (chosen) {
          is CollectionsSelection.Kind -> detail(chosen.kind)
          // Il "Indietro" di una pagina nel pannello (album, persona, ricordo) la chiude e torna alla raccolta di prima (Fase 10, P5).
          is CollectionsSelection.Entry -> CompositionLocalProvider(LocalCollectionDetailClose provides { savedEntry = null }) { entryDetail?.invoke(chosen.entry) }
          CollectionsSelection.None -> FluidDetailPlaceholder(
            title = stringResource(R.string.collections_detail_empty_title),
            message = stringResource(R.string.collections_detail_empty_message),
            icon = Icons.Rounded.Collections,
          )
        }
      }
    },
  )
}

/** Che cosa ha il pannello di dettaglio del tablet: una raccolta, una voce consegnata come dato, o niente ancora. */
internal sealed interface CollectionsSelection {
  val key: String

  /** La posizione nell'elenco, per la direzione della transizione laterale: nell'ordine in cui le righe compaiono. */
  fun order(state: CollectionsLayoutState): Int

  data class Kind(val kind: CollectionKind) : CollectionsSelection {
    override val key: String get() = kind.code

    override fun order(state: CollectionsLayoutState): Int =
      state.visibleRows.indexOfFirst { it is SectionRow.Kind && it.row.kind == kind }.coerceAtLeast(0)
  }

  data class Entry(val entry: CollectionEntry) : CollectionsSelection {
    override val key: String get() = "entry:" + entry.code

    override fun order(state: CollectionsLayoutState): Int =
      state.visibleRows.indexOfFirst { it is SectionRow.Entry && it.entry.code == entry.code }.coerceAtLeast(0)
  }

  data object None : CollectionsSelection {
    override val key: String get() = "none"

    override fun order(state: CollectionsLayoutState): Int = 0
  }
}

@Composable
private fun CollectionsList(
  state: CollectionsLayoutState,
  selectedKind: CollectionKind?,
  selectedEntry: String?,
  twoPane: Boolean,
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onSelectKind: (CollectionKind) -> Unit,
  onSelectEntry: (CollectionEntry) -> Unit,
  onOpenSettings: () -> Unit,
  statusHeader: @Composable () -> Unit,
  onEdit: () -> Unit,
  onDone: () -> Unit,
  onReorder: (List<String>) -> Unit,
  onSetShown: (String, Boolean) -> Unit,
) {
  FluidScreen(
    title = stringResource(if (state.editing) R.string.collections_edit_title else R.string.collections_title),
    actions = {
      // Un solo gruppo di tasti per volta: in modifica c'e' "Fine" e basta, cosi' nessun comando sta accanto a un altro che non c'entra.
      if (state.editing) {
        GalleryBarButton(
          icon = Icons.Rounded.Check,
          contentDescription = stringResource(R.string.collections_edit_done),
          onClick = onDone,
        )
      } else {
        // H5 (11/11): un solo tasto, il menu, come la scheda Album e la Libreria. Le Impostazioni sono un comando dell'app (l'ultima voce, dopo
        // una banda, come nel menu della Libreria), non un tasto con l'ingranaggio accanto alle raccolte: l'utente le leggeva come "un'altra
        // raccolta" e la barra aveva due tondi uguali per due cose diverse.
        GalleryMenuButton(
          entries = listOf(
            GalleryMenuEntry.Item(label = stringResource(R.string.collections_edit), onClick = onEdit, icon = Icons.Rounded.Edit),
            GalleryMenuEntry.GroupBreak,
            GalleryMenuEntry.Item(label = stringResource(R.string.collections_open_settings), onClick = onOpenSettings, icon = Icons.Rounded.Settings),
          ),
          contentDescription = stringResource(R.string.collections_more),
        )
      }
    },
    extraBottomPadding = contentPadding.calculateBottomPadding(),
    // La pillola non e' qui dentro (e' una sola per tutte le schede): questa pagina le consegna solo il suo fondale.
    overlay = { pageBackdrop -> SideEffect { backdropOut(pageBackdrop) } },
  ) {
    if (state.editing) {
      collectionsEditItems(state, onReorder, onSetShown)
      return@FluidScreen
    }
    item(key = "status") { statusHeader() }
    if (state.visible.isEmpty()) {
      item(key = "all-hidden") {
        CollectionEmptyState(
          icon = Icons.Rounded.VisibilityOff,
          title = stringResource(R.string.collections_all_hidden_title),
          detail = stringResource(R.string.collections_all_hidden_detail),
          actionLabel = stringResource(R.string.collections_edit),
          onAction = onEdit,
        )
      }
    }
    for (section in state.visible) {
      item(key = "header-${section.code}") { GallerySectionHeader(title = section.title.resolve()) }
      item(key = "section-${section.code}") {
        SectionGroup(section, selectedKind, selectedEntry, twoPane, onSelectKind, onSelectEntry)
      }
    }
  }
}
