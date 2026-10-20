package dev.pampa.mycloudgallery.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceRoute
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup

// Fase 5B (P14, P12 "navigazione"): "Libera spazio" nelle Raccolte, gruppo "Utilita'". Il codice della voce e' la rotta stabile di
// `BackupScreen.FREE_SPACE` (mai un dato dell'utente).

/** Il gruppo "Utilita'" delle Raccolte, solo con il backup (senza, il gruppo non c'e' nemmeno come intestazione). */
@Composable
internal fun utilityCollectionGroups(available: Boolean): List<CollectionEntryGroup> {
  if (!available) return emptyList()
  val title = stringResource(R.string.fiveb_utility_group)
  val name = stringResource(R.string.fiveb_free_space_title)
  val detail = stringResource(R.string.fiveb_free_space_subtitle)
  return remember(title, name, detail) {
    listOf(CollectionEntryGroup(title, listOf(CollectionEntry(BackupScreen.FREE_SPACE.route, name, detail, Icons.Rounded.CleaningServices, FluidTone.Primary))))
  }
}

/** Se una voce delle Raccolte e' "Libera spazio". */
internal fun isFreeSpaceEntry(code: String): Boolean = code == BackupScreen.FREE_SPACE.route

/** "Libera spazio" nel pannello di dettaglio del tablet: senza tasto indietro, sopra la pillola, senza selezione (tutto il sicuro tranne gli ultimi 30 giorni). */
@Composable
internal fun FreeSpaceEntryDetail(hostPadding: PaddingValues) {
  FreeSpaceRoute(hostPadding = hostPadding)
}
