package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
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
    item(key = "entries-header-$index") { GallerySectionHeader(title = group.title) }
    item(key = "entries-group-$index") { CollectionEntryRows(group, selectedCode, twoPane, onSelect) }
  }
}

/** Quello che sta a destra di una riga: il numero di cose da guardare, o la parola breve ("Condiviso"); niente se non c'e' nessuno dei due. */
private fun entryBadge(entry: CollectionEntry): (@Composable () -> Unit)? = when {
  entry.badge > 0 -> ({ FluidRowValue(text = GalleryNumbers.format(entry.badge)) })
  entry.trailing != null -> ({ FluidRowValue(text = entry.trailing) })
  else -> null
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
      CollectionEntryRow(entry, selectedCode, twoPane, onSelect)
    }
  }
}

/** Una sola riga di una sezione consegnata come dato: la stessa dei gruppi di sempre, con la freccia sul telefono e l'evidenza su due pannelli. */
@Composable
internal fun CollectionEntryRow(
  entry: CollectionEntry,
  selectedCode: String?,
  twoPane: Boolean,
  onSelect: (CollectionEntry) -> Unit,
  family: CollectionArtFamily = CollectionArtFamily.Other,
) {
  FluidListRow(
    title = entry.title,
    subtitle = entry.subtitle,
    tone = entry.tone,
    // H5: la piastrella delle Raccolte, piu' grande, con la copertina della voce quando c'e'.
    leading = { CollectionRowArt(entry.icon, entry.tone, family, entry.cover, entry.faces, entry.coverPlace, entry.coverPlaces) },
    leadingTile = false,
    badge = entryBadge(entry),
    selected = twoPane && entry.code == selectedCode,
    disclosure = !twoPane,
    onClick = { onSelect(entry) },
  )
}
