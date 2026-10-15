package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers

// Le righe delle voci in piu' delle Raccolte ([CollectionEntryGroup]): lo **stesso** gruppo lista di Impostazioni che usano gia' le
// raccolte (`FluidListGroup`, `FluidListRow`, la piastrella col tono dell'icona, la freccia su un pannello e l'evidenza della riga scelta
// su due), senza nessuno stile nuovo. Sta qui, accanto ai dati, perche' chi integra (P16) deve solo chiamarla dopo i gruppi di sempre.

/**
 * Aggiunge alla lista delle Raccolte un'intestazione e un gruppo di righe per ognuno dei [groups] non vuoti.
 *
 * @param selectedCode su due pannelli, il `code` della voce che ha il dettaglio accanto (la riga e' evidenziata); null altrimenti.
 * @param twoPane su due pannelli una riga sceglie cosa mostrare accanto, e la freccia "si apre una pagina" prometterebbe altro.
 * @param onSelect la voce toccata: sul telefono `:app` apre la sua pagina, sul tablet cambia il dettaglio.
 */
fun LazyListScope.collectionEntryItems(
  groups: List<CollectionEntryGroup>,
  selectedCode: String?,
  twoPane: Boolean,
  onSelect: (CollectionEntry) -> Unit,
) {
  CollectionEntries.visible(groups).forEachIndexed { index, group ->
    item(key = "entries-header-$index") { FluidSectionHeader(title = group.title) }
    item(key = "entries-group-$index") { CollectionEntryRows(group, selectedCode, twoPane, onSelect) }
  }
}

/** Un gruppo di righe, come `CollectionGroup` delle raccolte: un contenitore, separatori rientrati, la tinta sulla piastrella. */
@Composable
fun CollectionEntryRows(
  group: CollectionEntryGroup,
  selectedCode: String?,
  twoPane: Boolean,
  onSelect: (CollectionEntry) -> Unit,
) {
  FluidListGroup {
    group.entries.forEachIndexed { index, entry ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = entry.title,
        subtitle = entry.subtitle,
        tone = entry.tone,
        leading = { Icon(imageVector = entry.icon, contentDescription = null) },
        badge = if (entry.badge > 0) ({ FluidRowValue(text = GalleryNumbers.format(entry.badge)) }) else null,
        selected = twoPane && entry.code == selectedCode,
        disclosure = !twoPane,
        onClick = { onSelect(entry) },
      )
    }
  }
}
