package dev.pampa.mycloudgallery.gallery

import android.content.Context
import dev.pampa.mycloudgallery.feature.library.library.LibraryDebugHooks
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import kotlinx.coroutines.flow.StateFlow

/**
 * **Solo debug**: una Libreria di prova al posto di quella vera, con il suo stato e i servizi del suo viewer. La Libreria
 * sintetica del telefono ([SyntheticLibraryStack]) e il NAS sintetico (Fase 4A, P11) ne sono due.
 */
interface DebugLibraryStack {
  val state: LibraryTabState

  val viewerServices: ViewerServices

  /** Chiude il database separato e ferma il lavoro (si torna alla Libreria vera o si sceglie un'altra prova). */
  fun close()
}

/**
 * **Solo debug** (Fase 4A, P11, M1): il NAS sintetico, una Libreria di prova con media **solo sul NAS** passati dalla
 * scansione vera (P5) su un NAS finto in memoria, in un database separato. Si chiede da Impostazioni → Sviluppo; la
 * Libreria lo apre e il menu "Vista" riporta alla Libreria del telefono. L'implementazione sta in `app/src/debug`; in
 * release `DeveloperTools` non ne da' nessuna e la voce non esiste.
 */
interface DebugLibraryKit {
  /** True dopo la richiesta da Impostazioni → Sviluppo, finche' dalla Libreria non si sceglie un'altra vista. */
  val requested: StateFlow<Boolean>

  /** La Libreria ha lasciato la prova (menu "Vista"): la richiesta non vale piu'. */
  fun dismiss()

  fun open(context: Context, gallery: GalleryGraph, hooks: LibraryDebugHooks): DebugLibraryStack
}
