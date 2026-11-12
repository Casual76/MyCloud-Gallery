package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fase I, giro di correzione 1, **CU-24**: nella mappa in anteprima una foto appuntata non va sotto la capsula «Mappa» ne' sotto il credito della carta
 * ([pinTouches]: il tondo della foto tocca il riquadro della capsula?). Una foto che ci cadrebbe non si appunta: resta nel carosello sotto.
 */
class CollectionMapKeepOutTest {

  /** La capsula in basso a sinistra di una carta 1000 x 500: 20 px dal bordo, 300 x 80. */
  private val chip = IntRect(20, 400, 320, 480)

  @Test
  fun unTondoSopraLaCapsula_latocca() {
    assertThat(pinTouches(chip, 150, 440, 50)).isTrue()
  }

  @Test
  fun unTondoCheNeSfioraIlBordo_latocca() {
    // Il centro e' 40 px sopra la capsula, il raggio 50: il tondo ne copre il bordo alto.
    assertThat(pinTouches(chip, 150, 360, 50)).isTrue()
  }

  @Test
  fun unTondoLontano_nonLaTocca() {
    assertThat(pinTouches(chip, 150, 300, 50)).isFalse()
    assertThat(pinTouches(chip, 400, 440, 50)).isFalse()
  }

  @Test
  fun unaCapsulaNonAncoraMisurata_nonTieneFuoriNiente() {
    assertThat(pinTouches(IntRect(20, 480, 20, 480), 20, 480, 50)).isFalse()
  }
}
