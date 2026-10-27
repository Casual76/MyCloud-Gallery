package dev.pampa.mycloudgallery.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceRoute

// Fase 5B (P14, P12 "navigazione"): "Libera spazio" nelle Raccolte. Il codice della voce e' la rotta stabile di `BackupScreen.FREE_SPACE` (mai un dato
// dell'utente). Da Fase 7 (P13) la voce sta nella sezione Utility (`AppCollectionsSections.utility`).

/** Se una voce delle Raccolte e' "Libera spazio". */
internal fun isFreeSpaceEntry(code: String): Boolean = code == BackupScreen.FREE_SPACE.route

/** "Libera spazio" nel pannello di dettaglio del tablet: senza tasto indietro, sopra la pillola, senza selezione (tutto il sicuro tranne gli ultimi 30 giorni). */
@Composable
internal fun FreeSpaceEntryDetail(hostPadding: PaddingValues) {
  FreeSpaceRoute(hostPadding = hostPadding)
}
