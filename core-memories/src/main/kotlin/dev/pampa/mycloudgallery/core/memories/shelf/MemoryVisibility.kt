package dev.pampa.mycloudgallery.core.memories.shelf

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

/**
 * Cio' che il motore dei ricordi chiede alla Libreria sulla visibilita' (docs/fase9-progetto.md D31, E-MEM5, E-MEM6, E-H8, R17): tre cose, nessun
 * altro modo di sapere se un media si puo' mostrare. Le implementa :app (P13) sopra i due contratti dei nascosti di core-library, in un file
 * che entra nell'elenco `wiring` del registro `HiddenSurfaces` (come `GalleryGraph`):
 *
 *  - [snapshot] e' `VisibleMedia.snapshot`: l'istantanea **senza nascosti**, una cella per gruppo (da qui nascono i candidati e la vista viva);
 *  - [visibleOrThrow] e' `VisibleMedia.visibleOrThrow`: quali di [ids] si possono mostrare adesso;
 *  - [hiddenOrThrow] e' `HiddenMask.hiddenAmongOrThrow`: quali sono nascosti adesso (il filtro finale).
 *
 * **Un guasto lancia, non risponde "niente"**: un errore di lettura del database non deve passare per una Libreria vuota, che svuoterebbe lo
 * scaffale (R17). Chi confronta con lo scaffale salvato usa solo queste due letture; la prova che un nascosto non esce mai e' la riga `MEMORIES`
 * del registro (P9) sul motore vero e `MemoryEngineHiddenTest`.
 *
 * Il motore non nomina i contratti dei nascosti: li nomina questo adattatore, un file solo, e cosi' il registro sa dove cercarli.
 */
interface MemoryVisibility {
  val snapshot: StateFlow<TimelineSnapshot?>

  suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId>

  suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId>
}
