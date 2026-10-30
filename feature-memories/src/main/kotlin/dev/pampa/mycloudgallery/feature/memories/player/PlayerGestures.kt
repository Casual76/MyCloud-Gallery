package dev.pampa.mycloudgallery.feature.memories.player

import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryDismissSpec
import kotlin.math.abs

/** Cosa vuol dire un trascinamento sulla presentazione (§2.4: "scorrimento = avanti e indietro; trascinamento in basso = esci"). */
enum class PlayerGesture { NEXT, PREVIOUS, EXIT, NONE }

/**
 * I gesti della presentazione, in una funzione pura (si prova senza un dito): le **stesse soglie del viewer** (`GalleryDismissSpec`) per l'uscita in
 * basso, e una frazione della larghezza per lo scorrimento. Un trascinamento che e' piu' verticale che orizzontale e' un'uscita (se va verso il
 * basso e basta strada) o niente; uno piu' orizzontale e' uno scorrimento (a sinistra = avanti, a destra = indietro).
 */
object PlayerGestures {

  /** Quanta parte della larghezza deve fare un dito per contare come scorrimento. */
  const val SWIPE_FRACTION: Float = 0.12f

  fun resolve(dx: Float, dy: Float, width: Float, height: Float, velocityY: Float = 0f, density: Float = 1f): PlayerGesture {
    if (width <= 0f || height <= 0f) return PlayerGesture.NONE
    if (abs(dy) > abs(dx)) {
      if (dy <= 0f) return PlayerGesture.NONE
      val progress = GalleryDismissSpec.dragProgress(dy, height)
      return if (GalleryDismissSpec.shouldDismiss(progress, velocityY, density)) PlayerGesture.EXIT else PlayerGesture.NONE
    }
    if (abs(dx) < width * SWIPE_FRACTION) return PlayerGesture.NONE
    return if (dx < 0f) PlayerGesture.NEXT else PlayerGesture.PREVIOUS
  }
}
