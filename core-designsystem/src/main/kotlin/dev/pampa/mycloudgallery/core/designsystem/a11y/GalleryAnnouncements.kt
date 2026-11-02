package dev.pampa.mycloudgallery.core.designsystem.a11y

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Fase 10, P8 (D23 (6)): gli annunci. Uno stato che cambia da solo ("12 selezionati", "Backup: 12 in coda", "NAS non
// raggiungibile") si dice **una volta**, con una regione viva *educata*: non interrompe quello che il lettore sta dicendo e non si
// ripete a ogni rinfresco, solo quando il testo cambia.

/**
 * Un annuncio senza disegno: un nodo di un punto con [text] come descrizione e `liveRegion` educata. Compare quando c'e' qualcosa da
 * dire e sparisce quando non c'e' piu'; il lettore di schermo lo dice a ogni cambio del testo.
 */
@Composable
fun GalleryLiveAnnouncement(text: String, modifier: Modifier = Modifier) {
  Spacer(
    modifier = modifier
      .size(1.dp)
      .semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = text
      },
  )
}

/**
 * Rende educata e dichiarata una regione che cambia da sola: da posare sul contenitore di uno stato ("Backup: 12 in coda"). Un
 * punto solo, cosi' nessuna schermata scrive a mano `liveRegion` e il test dei nodi lo cerca per nome.
 */
fun Modifier.galleryLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
