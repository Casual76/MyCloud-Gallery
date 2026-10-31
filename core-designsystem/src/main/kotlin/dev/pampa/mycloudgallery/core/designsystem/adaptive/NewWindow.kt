package dev.pampa.mycloudgallery.core.designsystem.adaptive

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

// "APRI IN UNA NUOVA FINESTRA" PER LE SCHERMATE (Fase 10, P4, D16, E-T6; docs/fase10-progetto.md §2.4). **File nuovo** in `adaptive/`: nessun componente esistente cambia.
// Le schermate (feature-*) non conoscono `MainActivity` ne' i task: chiedono una finestra a questa porta, che `:app` lega a `WindowLauncher`. Senza porta (test, anteprime,
// Android sotto la 12) la voce di menu non si offre.

/**
 * La porta con cui una schermata chiede **una finestra nuova** dell'app, che parte dalla pagina scelta. Gli id viaggiano come stringhe (il valore dell'id di album o ricordo):
 * il design system non conosce i moduli dei dati, e `:app` li ricostruisce validandoli. **Mai dati nell'intent**: il tipo di pagina e l'id restano nel processo (D16).
 */
@Stable
interface NewWindowPort {
  /**
   * La voce "Apri in una nuova finestra" si offre in questa [window]? Solo da finestra estesa o con piu' finestre sullo schermo (DeX, finestra libera), e solo dove l'app
   * puo' averne piu' di una (Android 12 o piu'). Le schermate la chiamano in composizione: dev'essere economica e senza effetti.
   */
  fun isOffered(window: GalleryWindow): Boolean

  /** Una finestra nuova sulla Libreria. */
  fun openLibrary()

  /** Una finestra nuova sulla pagina dell'album [albumId] (il valore dell'`AlbumId`). */
  fun openAlbum(albumId: String)

  /** Una finestra nuova sulla pagina del ricordo [memoryId] (il valore del `MemoryId`). */
  fun openMemory(memoryId: String)

  /** Una finestra nuova sulla mappa. */
  fun openMap()
}

/** La porta delle finestre nuove della finestra che si sta componendo; `null` = non si offre (test, anteprime). La mette `MainActivity` alla radice di ogni finestra. */
val LocalNewWindow = staticCompositionLocalOf<NewWindowPort?> { null }
