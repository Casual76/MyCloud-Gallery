package dev.pampa.mycloudgallery.core.qr.render

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec
import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import org.junit.Assert.assertThrows
import org.junit.Test

/** Margini e nitidezza del disegno: modulo intero, zona di rispetto di quattro moduli, centratura. */
class QrGeometryTest {

  private val modulesPerVersion = listOf(21, 25, 29, 41, 77, 101, 177)

  @Test
  fun ilModuloEIntero_quandoLAreaBasta() {
    for (modules in modulesPerVersion) for (side in listOf(200f, 333f, 412f, 720f, 1000f, 1081f)) {
      val g = QrGeometry.of(side, side, modules)
      if (side >= modules + 2 * QUIET_ZONE_MODULES) {
        assertWithMessage("moduli $modules, lato $side").that(g.module).isEqualTo(g.module.toInt().toFloat())
        assertThat(g.module).isAtLeast(1f)
      }
    }
  }

  @Test
  fun laZonaDiRispetto_eDiAlmenoQuattroModuli_suOgniLato() {
    for (modules in modulesPerVersion) for (side in listOf(150f, 200f, 333f, 412f, 720f, 1000f, 1081f)) {
      val g = QrGeometry.of(side, side, modules)
      val tolerance = 0.001f
      val left = g.originX
      val top = g.originY
      val right = side - (g.originX + modules * g.module)
      val bottom = side - (g.originY + modules * g.module)
      for ((name, margin) in listOf("sinistra" to left, "sopra" to top, "destra" to right, "sotto" to bottom)) {
        assertWithMessage("moduli $modules, lato $side, margine $name").that(margin)
          .isAtLeast(QUIET_ZONE_MODULES * g.module - tolerance)
      }
    }
  }

  @Test
  fun ilQrEcentrato_connUnoScartoDiUnPixelAlMassimo() {
    for (modules in modulesPerVersion) for (side in listOf(200f, 333f, 412f, 1000f)) {
      val g = QrGeometry.of(side, side, modules)
      val left = g.originX
      val right = side - (g.originX + modules * g.module)
      assertWithMessage("moduli $modules, lato $side").that(kotlin.math.abs(left - right)).isAtMost(1.0f)
    }
  }

  @Test
  fun inUnAreaNonQuadrata_siUsaIlLatoMinore_esiCentraSuEntrambiGliAssi() {
    val g = QrGeometry.of(width = 800f, height = 400f, modules = 29)
    assertThat(g.framed).isAtMost(400f)
    assertThat(g.originX).isGreaterThan(g.originY) // piu' spazio a sinistra che sopra
    assertThat(800f - (g.originX + 29 * g.module) - g.originX).isAtMost(1f)
  }

  @Test
  fun inUnAreaTroppoPiccola_laScalaDiventaFrazionaria_maRestaProporzionata() {
    val g = QrGeometry.of(40f, 40f, 101)
    assertThat(g.module).isLessThan(1f)
    assertThat(g.module).isGreaterThan(0f)
    assertThat(g.framed).isWithin(0.01f).of(40f)
  }

  @Test
  fun moduliNonPositivi_siRifiutano() {
    assertThrows(IllegalArgumentException::class.java) { QrGeometry.of(100f, 100f, 0) }
  }

  @Test
  fun iTrattiScuri_coprono_esattamenteIModuliScuri() {
    for (text in listOf("A", "MCG1:" + "xYz-_".repeat(40), "è".repeat(50))) {
      val bits = ZxingQrCodec.encode(text)
      val runs = bits.darkRuns()
      assertThat(runs.size % 3).isEqualTo(0)
      val rebuilt = BooleanArray(bits.size * bits.size)
      var i = 0
      while (i < runs.size) {
        val y = runs[i]
        val x = runs[i + 1]
        val length = runs[i + 2]
        assertThat(length).isAtLeast(1)
        for (k in 0 until length) {
          assertThat(rebuilt[y * bits.size + x + k]).isFalse() // nessun modulo disegnato due volte
          rebuilt[y * bits.size + x + k] = true
        }
        i += 3
      }
      assertThat(QrBits(bits.size, rebuilt)).isEqualTo(bits)
    }
  }

  @Test
  fun iTrattiNonSiToccano_unaRigaNonPassaLaProssima() {
    val bits = QrBits(3, booleanArrayOf(true, true, true, false, true, false, true, true, true))
    assertThat(bits.darkRuns().toList()).containsExactly(0, 0, 3, 1, 1, 1, 2, 0, 3).inOrder()
  }
}
