package dev.pampa.mycloudgallery.core.designsystem.adaptive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry

// LA VOCE "APRI IN UNA NUOVA FINESTRA" (Fase 10, P5, D16, E-T6; docs/fase10-progetto.md §2.4). **File nuovo** in `adaptive/`. La porta (`LocalNewWindow`, legata da `:app`) dice se la voce si offre per la finestra **dell'app**
// (estesa, o piu' finestre sullo schermo): le schermate che hanno un menu (album, ricordo) ne mettono una sola riga, con le loro stringhe e il loro modo di aprire.

/** La voce di menu "Apri in una nuova finestra" con l'etichetta [label], o null se la finestra dell'app non la offre (telefono, senza porta, Android sotto la 12). [open] riceve la porta. */
@Composable
fun rememberNewWindowEntry(label: String, open: (NewWindowPort) -> Unit): GalleryMenuEntry.Item? {
  val port = LocalNewWindow.current ?: return null
  val window = rememberAppWindow()
  if (!port.isOffered(window)) return null
  return GalleryMenuEntry.Item(label = label, onClick = { open(port) }, icon = Icons.AutoMirrored.Rounded.OpenInNew)
}
