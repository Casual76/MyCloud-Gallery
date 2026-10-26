package dev.pampa.mycloudgallery.feature.viewer.cutout

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La maschera del soggetto nel viewer (R20): una foto da 12 megapixel pesa 12,6 MB per ogni copia dell'alfa, e con poca memoria ogni copia in meno conta.
 * `ViewerMask(...)` copia (il chiamante puo' riusare l'array), `adopting` no, `readAlpha` presta l'alfa senza copiarlo.
 */
class ViewerMaskTest {

  private fun alpha(w: Int, h: Int) = ByteArray(w * h) { (it % 251).toByte() }

  @Test
  fun ilCostruttoreCopiaEAdoptingNo() {
    val source = alpha(8, 4)
    val copied = ViewerMask(8, 4, source)
    source[0] = 99
    assertThat(copied.at(0, 0)).isNotEqualTo(99)

    val owned = alpha(8, 4)
    val adopted = ViewerMask.adopting(8, 4, owned)
    owned[0] = 99
    // Chi adotta non ha una copia sua: cio' che il chiamante non tocca piu' e' della maschera.
    assertThat(adopted.at(0, 0)).isEqualTo(99)
  }

  @Test
  fun readAlphaPrestaLAlfaSenzaCopiarlo_AlphaNo() {
    val mask = ViewerMask.adopting(8, 4, alpha(8, 4))
    var lent: ByteArray? = null
    mask.readAlpha { lent = it }
    mask.readAlpha { assertThat(it).isSameInstanceAs(lent) }
    // `alpha()` invece e' una copia: modificarla non cambia la maschera.
    val copy = mask.alpha()
    copy[1] = 77
    assertThat(mask.at(1, 0)).isNotEqualTo(77)
  }

  @Test
  fun unaMascheraConLaDimensioneSbagliataNonSiCostruisce() {
    assertThrows(IllegalArgumentException::class.java) { ViewerMask(4, 4, ByteArray(10)) }
    assertThrows(IllegalArgumentException::class.java) { ViewerMask.adopting(4, 4, ByteArray(10)) }
  }
}
