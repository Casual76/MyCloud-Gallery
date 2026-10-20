package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidDetailContent
import dev.antigravity.fluidengine.ui.fluid.FluidListDetailScaffold
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenRoute
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedRoute

/**
 * La scheda **Raccolte** (D17): un elenco raggruppato di Fluid con Preferiti, Video, Screenshot, WhatsApp e, nel gruppo
 * dei posti protetti, Nascosti ed Eliminati di recente. Ogni voce apre una griglia filtrata con lo stesso `MediaGrid` e lo
 * stesso viewer della Libreria. Dalla Fase 4B, dopo i gruppi di sempre, le voci che arrivano da `:app` ([extraGroups]: "Sul
 * NAS" con Cartelle NAS, Spazio NAS, Eliminati di recente del NAS, Operazioni in corso), come **dati**.
 *
 * - **Telefono**: l'elenco e basta; una voce toccata chiama [onOpenCollection] (`:app` la porta alla rotta giusta con
 *   `NavController.openCollection`) o, per le voci di `:app`, [onOpenEntry].
 * - **Tablet** (da 840 dp: `FluidListDetailScaffold`): l'elenco a sinistra, la raccolta scelta a destra. E' la "barra
 *   laterale delle raccolte" del piano. Le voci selezionano e basta, nessuna navigazione; per una voce di `:app` il dettaglio
 *   e' [entryDetail].
 *
 * @param contentPadding lo spazio che la pillola delle schede occupa in basso (il contenuto scorre sotto, rifratto).
 * @param backdropOut consegna a `:app` il fondale dell'elenco, che la pillola rifrange.
 * @param onOpenViewer il viewer e' una sovrapposizione ospitata da `:app` (D12): qui si manda solo la richiesta.
 * @param statusHeader l'indicatore globale della Fase 2, in cima come nelle altre pagine.
 * @param extraGroups le voci in piu' di `:app` (Fase 4B); nessuna di default.
 * @param onOpenEntry sul telefono, la voce di `:app` toccata (`:app` apre la sua rotta).
 * @param entryDetail sul tablet, la pagina di una voce di `:app` nel pannello di dettaglio; null = la voce apre la rotta anche li'.
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
  viewModel: CollectionsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  CollectionsScreen(
    state = state,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    onOpenCollection = onOpenCollection,
    onOpenSettings = onOpenSettings,
    statusHeader = statusHeader,
    modifier = modifier,
    extraGroups = extraGroups,
    onOpenEntry = onOpenEntry,
    entryDetail = entryDetail,
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
  state: CollectionsUiState,
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenCollection: (CollectionKind) -> Unit,
  onOpenSettings: () -> Unit,
  statusHeader: @Composable () -> Unit,
  detail: @Composable (CollectionKind) -> Unit,
  modifier: Modifier = Modifier,
  initialSelection: CollectionKind? = null,
  extraGroups: List<CollectionEntryGroup> = emptyList(),
  onOpenEntry: (CollectionEntry) -> Unit = {},
  entryDetail: (@Composable (CollectionEntry) -> Unit)? = null,
) {
  // Su due pannelli una raccolta e' sempre scelta: quella di prima, o la prima dell'elenco (mai i Nascosti da soli:
  // chiederebbero la credenziale senza che nessuno l'abbia toccato). Il codice sopravvive alla rotazione. Una voce di `:app`
  // scelta (Fase 4B) ha il suo codice a parte: se sparisce (NAS scollegato) la scelta torna alla raccolta.
  var savedCode by rememberSaveable { mutableStateOf(initialSelection?.code) }
  var savedEntry by rememberSaveable { mutableStateOf<String?>(null) }
  val selectedKind = CollectionKind.fromCode(savedCode)?.takeIf { it in state } ?: state.media.first().kind
  val selectedEntry = if (entryDetail != null) CollectionEntries.selected(extraGroups, savedEntry) else null
  val selection: CollectionsSelection = selectedEntry?.let { CollectionsSelection.Entry(it) } ?: CollectionsSelection.Kind(selectedKind)

  FluidListDetailScaffold(
    ambient = null,
    modifier = modifier,
    list = { twoPane ->
      CollectionsList(
        state = state,
        selected = if (twoPane && selectedEntry == null) selectedKind else null,
        twoPane = twoPane,
        contentPadding = contentPadding,
        backdropOut = backdropOut,
        onSelect = { kind ->
          if (twoPane) {
            savedCode = kind.code
            savedEntry = null
          } else {
            onOpenCollection(kind)
          }
        },
        onOpenSettings = onOpenSettings,
        statusHeader = statusHeader,
        extraGroups = extraGroups,
        selectedEntry = if (twoPane) selectedEntry?.code else null,
        onSelectEntry = { entry -> if (twoPane && entryDetail != null) savedEntry = entry.code else onOpenEntry(entry) },
      )
    },
    detail = {
      FluidDetailContent(item = selection, key = { it.key }, order = { it.order(extraGroups) }) { chosen ->
        when (chosen) {
          is CollectionsSelection.Kind -> detail(chosen.kind)
          is CollectionsSelection.Entry -> entryDetail?.invoke(chosen.entry)
        }
      }
    },
  )
}

/** Che cosa ha il pannello di dettaglio del tablet: una raccolta o una voce di `:app` (Fase 4B). */
internal sealed interface CollectionsSelection {
  val key: String

  /** L'ordine delle pagine per la transizione laterale: le raccolte prima, le voci di `:app` dopo, nell'ordine dell'elenco. */
  fun order(groups: List<CollectionEntryGroup>): Int

  data class Kind(val kind: CollectionKind) : CollectionsSelection {
    override val key: String get() = kind.code

    override fun order(groups: List<CollectionEntryGroup>): Int = kind.ordinal
  }

  data class Entry(val entry: CollectionEntry) : CollectionsSelection {
    override val key: String get() = "entry:" + entry.code

    override fun order(groups: List<CollectionEntryGroup>): Int =
      CollectionKind.entries.size + CollectionEntries.codes(groups).indexOf(entry.code).coerceAtLeast(0)
  }
}

@Composable
private fun CollectionsList(
  state: CollectionsUiState,
  selected: CollectionKind?,
  twoPane: Boolean,
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onSelect: (CollectionKind) -> Unit,
  onOpenSettings: () -> Unit,
  statusHeader: @Composable () -> Unit,
  extraGroups: List<CollectionEntryGroup>,
  selectedEntry: String?,
  onSelectEntry: (CollectionEntry) -> Unit,
) {
  FluidScreen(
    title = stringResource(R.string.collections_title),
    actions = {
      GalleryBarButton(
        icon = Icons.Rounded.Settings,
        contentDescription = stringResource(R.string.collections_open_settings),
        onClick = onOpenSettings,
      )
    },
    extraBottomPadding = contentPadding.calculateBottomPadding(),
    // La pillola non e' qui dentro (e' una sola per tutte le schede): questa pagina le consegna solo il suo fondale.
    overlay = { pageBackdrop -> SideEffect { backdropOut(pageBackdrop) } },
  ) {
    item(key = "status") { statusHeader() }
    item(key = "phone-header") { FluidSectionHeader(title = stringResource(R.string.collections_section_phone)) }
    item(key = "phone") {
      CollectionGroup(state.media, selected, twoPane, onSelect)
    }
    item(key = "protected-header") { FluidSectionHeader(title = stringResource(R.string.collections_section_protected)) }
    item(key = "protected") {
      CollectionGroup(state.protectedRows, selected, twoPane, onSelect)
    }
    // Fase 4B: le voci di `:app` ("Sul NAS"), dopo i gruppi di sempre.
    collectionEntryItems(extraGroups, selectedEntry, twoPane, onSelectEntry)
  }
}

/** Un gruppo di righe, come in Impostazioni: un contenitore, separatori rientrati, la tinta sulla piastrella. */
@Composable
private fun CollectionGroup(
  rows: List<CollectionRow>,
  selected: CollectionKind?,
  twoPane: Boolean,
  onSelect: (CollectionKind) -> Unit,
) {
  FluidListGroup {
    rows.forEachIndexed { index, row ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = stringResource(row.kind.titleRes),
        subtitle = rowSubtitle(row),
        tone = row.kind.tone,
        leading = { Icon(imageVector = row.kind.icon, contentDescription = null) },
        selected = twoPane && row.kind == selected,
        // Su due pannelli la riga sceglie cosa mostrare accanto: la freccia "si apre una pagina" prometterebbe altro.
        disclosure = !twoPane,
        onClick = { onSelect(row.kind) },
      )
    }
  }
}

/**
 * La riga sotto il nome: il numero di elementi per le raccolte-filtro. I Nascosti **non dicono quanti sono** (E-H1) e
 * gli Eliminati di recente dicono per quanto restano, non quanti.
 */
@Composable
private fun rowSubtitle(row: CollectionRow): String = when (row.kind) {
  CollectionKind.HIDDEN -> stringResource(R.string.collection_hidden_subtitle)
  CollectionKind.RECENTLY_DELETED ->
    stringResource(phoneTrashText(R.string.collection_recently_deleted_subtitle, R.string.collection_recently_deleted_subtitle_gallery))
  else -> {
    val count = row.count ?: 0
    if (count == 0) stringResource(R.string.collection_count_none) else countText(R.plurals.collection_count, count)
  }
}
