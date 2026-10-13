package dev.pampa.mycloudgallery.core.imaging.contract

import android.graphics.drawable.AnimatedImageDrawable
import dev.pampa.mycloudgallery.core.model.MediaRef

// Aggiunta di P6 ai contratti (additiva: nessuna firma di P0 e' cambiata). `ViewerImageSource.animated` dice SE e COME
// si anima un'immagine (E-M11); questa interfaccia da' il `Drawable` che il viewer mostra.

/**
 * Il disegno animato (GIF, WebP animato) di un media. Il viewer lo mostra con `AnimatedImageDrawable` (E-M11); nella
 * griglia le immagini animate restano ferme (la miniatura e' il primo fotogramma). L'implementazione di
 * `PipelineViewerImageSource` la fornisce insieme a [ViewerImageSource].
 */
interface AnimatedDrawableSource {

  /**
   * Un `AnimatedImageDrawable` nuovo per [ref], con il lato lungo al massimo [maxSide] pixel (un GIF da 4000 px
   * tiene in memoria due fotogrammi a quella taglia: si decodifica alla taglia dello schermo). Null se [ref] non e'
   * animato o non si legge. Sospende: la decodifica gira fuori dal thread principale. Il chiamante lo avvia
   * (`start()`) e lo ferma (`stop()`) con la pagina, e rilascia il riferimento quando la pagina esce.
   */
  suspend fun drawable(ref: MediaRef, maxSide: Int): AnimatedImageDrawable?
}
