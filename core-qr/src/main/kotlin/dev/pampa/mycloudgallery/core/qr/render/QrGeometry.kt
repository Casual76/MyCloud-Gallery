package dev.pampa.mycloudgallery.core.qr.render

import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import kotlin.math.floor
import kotlin.math.min

/** La zona di rispetto attorno al QR, in moduli: il minimo della specifica (ISO 18004) e' 4. */
internal const val QUIET_ZONE_MODULES: Int = 4

/**
 * Dove cade il QR dentro un'area: la misura di un modulo e dove comincia il primo.
 *
 * **Nitidezza.** Se l'area basta, il modulo e' un numero **intero** di pixel (si arrotonda per difetto): un
 * modulo di 3,4 pixel verrebbe sfumato dal disegno e i bordi fra moduli neri e bianchi si mescolerebbero in
 * grigio, che e' proprio quel che confonde un decodificatore a bassa risoluzione. I pixel che avanzano non
 * si distribuiscono fra i moduli: allargano il margine, centrato. La zona di rispetto e' sempre di almeno
 * [QUIET_ZONE_MODULES] moduli. Solo se l'area e' cosi' piccola da non avere un pixel per modulo la scala
 * diventa frazionaria (il QR sara' illeggibile in ogni caso, ma resta proporzionato).
 */
internal class QrGeometry(
  /** Moduli per lato, senza la zona di rispetto. */
  val modules: Int,
  /** Lato di un modulo, in pixel. */
  val module: Float,
  /** Ascissa del primo modulo (in alto a sinistra), cioe' dopo la zona di rispetto. */
  val originX: Float,
  /** Ordinata del primo modulo. */
  val originY: Float,
) {
  /** Lato del quadrato coperto dal QR con la sua zona di rispetto, in pixel. */
  val framed: Float get() = module * (modules + 2 * QUIET_ZONE_MODULES)

  companion object {
    fun of(width: Float, height: Float, modules: Int): QrGeometry {
      require(modules > 0) { "moduli: $modules" }
      val total = modules + 2 * QUIET_ZONE_MODULES
      val side = min(width, height)
      val module = if (side >= total) floor(side / total) else side / total
      val framed = module * total
      val left = floor((width - framed) / 2f)
      val top = floor((height - framed) / 2f)
      return QrGeometry(
        modules = modules,
        module = module,
        originX = left + QUIET_ZONE_MODULES * module,
        originY = top + QUIET_ZONE_MODULES * module,
      )
    }
  }
}

/**
 * I moduli scuri di una matrice, a righe orizzontali: per ogni tratto continuo tre numeri `y, x, lunghezza`.
 * Disegnare un rettangolo per tratto, e non uno per modulo, dimezza il lavoro del disegno e non lascia
 * fessure fra moduli adiacenti.
 */
internal fun QrBits.darkRuns(): IntArray {
  val out = ArrayList<Int>()
  for (y in 0 until size) {
    var x = 0
    while (x < size) {
      if (!this[x, y]) {
        x++
        continue
      }
      val start = x
      while (x < size && this[x, y]) x++
      out += y
      out += start
      out += x - start
    }
  }
  return out.toIntArray()
}
