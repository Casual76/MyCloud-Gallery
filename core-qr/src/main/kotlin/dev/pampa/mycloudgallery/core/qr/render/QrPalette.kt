package dev.pampa.mycloudgallery.core.qr.render

import androidx.compose.ui.graphics.Color

/**
 * I due colori del QR: moduli neri su fondo bianco, **sempre**, in chiaro e in scuro.
 *
 * Eccezione consapevole alla regola "nessun colore scritto a mano" (la stessa logica di `GalleryOnPhoto`,
 * che vive nel design system): un QR non e' un'immagine che segue il tema. Gli scanner leggono moduli scuri
 * su fondo chiaro e molti non leggono affatto il contrario (ZXing compreso, e il nostro non prova
 * l'inverso: raddoppierebbe il lavoro a ogni fotogramma). In tema scuro il QR e' quindi un rettangolo
 * bianco su una pagina scura, come su ogni altra app, e il bianco e' proprio quello che serve: il margine
 * bianco fa parte del codice.
 *
 * I soli `Color.White` e `Color.Black` di `:core-qr` stanno qui; `NoHandWrittenStyleTest` li ammette solo in
 * questo file.
 */
internal object QrPalette {

  /** Il fondo del QR e la sua zona di rispetto. */
  val Plate: Color = Color.White

  /** I moduli scuri. */
  val Module: Color = Color.Black
}
