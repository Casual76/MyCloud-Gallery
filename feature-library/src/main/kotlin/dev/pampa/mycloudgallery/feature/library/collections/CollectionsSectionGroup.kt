package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText

/**
 * Le righe di una sezione, come un gruppo di Impostazioni: un contenitore, separatori rientrati, la tinta sulla piastrella. Le raccolte
 * che la scheda sa aprire da sola ([SectionRow.Kind]) chiamano [onSelectKind]; le voci consegnate come dato ([SectionRow.Entry]) chiamano
 * [onSelectEntry]. Su due pannelli la riga scelta e' evidenziata e la freccia "si apre una pagina" non c'e'.
 */
@Composable
internal fun SectionGroup(
  section: CollectionsSection,
  selectedKind: CollectionKind?,
  selectedEntry: String?,
  twoPane: Boolean,
  onSelectKind: (CollectionKind) -> Unit,
  onSelectEntry: (CollectionEntry) -> Unit,
) {
  // CS-1: la famiglia della sezione da' il colore alle piastrelle senza foto.
  val family = CollectionArtFamily.of(section.code)
  FluidListGroup {
    section.rows.forEachIndexed { index, row ->
      if (index > 0) FluidListDivider()
      when (row) {
        is SectionRow.Kind -> KindRow(row.row, selected = twoPane && row.row.kind == selectedKind, twoPane = twoPane, onSelect = onSelectKind, family = family)
        is SectionRow.Entry -> CollectionEntryRow(row.entry, selectedEntry, twoPane, onSelectEntry, family)
      }
    }
  }
}

@Composable
private fun KindRow(row: CollectionRow, selected: Boolean, twoPane: Boolean, onSelect: (CollectionKind) -> Unit, family: CollectionArtFamily) {
  FluidListRow(
    title = stringResource(row.kind.titleRes),
    subtitle = rowSubtitle(row),
    tone = row.kind.tone,
    // H5: la stessa piastrella delle voci consegnate come dato, senza copertina (Nascosti ed Eliminati di recente non mostrano mai il contenuto).
    leading = { CollectionRowArt(row.kind.icon, row.kind.tone, family) },
    leadingTile = false,
    selected = selected,
    // Su due pannelli la riga sceglie cosa mostrare accanto: la freccia "si apre una pagina" prometterebbe altro.
    disclosure = !twoPane,
    onClick = { onSelect(row.kind) },
  )
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
