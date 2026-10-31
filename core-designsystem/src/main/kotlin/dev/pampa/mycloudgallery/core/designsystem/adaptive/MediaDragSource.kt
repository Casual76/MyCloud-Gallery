package dev.pampa.mycloudgallery.core.designsystem.adaptive

import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData

// IL GESTO DEL TRASCINAMENTO (Fase 10, P0 firma; docs/fase10-progetto.md §1.2 "core-designsystem/adaptive", §2.5, D20). Solo il gesto: i dati
// li da' la porta `MediaDragHandler` di core-library (URI del provider dei documenti, sola lettura sul singolo URI), preparati **prima** che il
// dito superi la soglia. Mai dai Nascosti: le loro celle non hanno questo modificatore (E-T18).

/**
 * Rende trascinabile verso un'altra app l'elemento (pressione prolungata e trascinamento con dito, penna o mouse). [payload] e' chiamato
 * all'inizio del gesto e da' i dati gia' pronti, o null (niente trascinamento). Con [enabled] falso il modificatore non fa niente.
 *
 * **P0: solo la firma.** Oggi non aggancia nessun gesto (ritorna il modificatore com'e'); il gesto vero con `dragAndDropSource` lo scrive P6,
 * con l'ombra della miniatura locale e i tetti di E-X6.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.mediaDragSource(enabled: Boolean, payload: () -> DragAndDropTransferData?): Modifier = this
