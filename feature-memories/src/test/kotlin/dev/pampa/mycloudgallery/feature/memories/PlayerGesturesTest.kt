package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryDismissSpec
import dev.pampa.mycloudgallery.feature.memories.player.PlayerGesture
import dev.pampa.mycloudgallery.feature.memories.player.PlayerGestures
import org.junit.Test

/** I gesti della presentazione (§2.4: scorrimento avanti e indietro, trascinamento in basso per uscire): le stesse soglie del viewer. */
class PlayerGesturesTest {

  private val w = 1_000f
  private val h = 2_000f

  @Test
  fun scorrimentoASinistraAvanti_aDestraIndietro() {
    assertThat(PlayerGestures.resolve(-300f, 10f, w, h)).isEqualTo(PlayerGesture.NEXT)
    assertThat(PlayerGestures.resolve(300f, -10f, w, h)).isEqualTo(PlayerGesture.PREVIOUS)
  }

  @Test
  fun unTrascinamentoCortoNonFaNiente() {
    assertThat(PlayerGestures.resolve(-(w * PlayerGestures.SWIPE_FRACTION - 1f), 0f, w, h)).isEqualTo(PlayerGesture.NONE)
    assertThat(PlayerGestures.resolve(0f, 0f, w, h)).isEqualTo(PlayerGesture.NONE)
  }

  @Test
  fun inBassoEsceSoloConAbbastanzaStradaOUnLancio_inAltoMai() {
    val enough = h * GalleryDismissSpec.TravelFraction * GalleryDismissSpec.DismissDragProgress
    assertThat(PlayerGestures.resolve(0f, enough + 1f, w, h)).isEqualTo(PlayerGesture.EXIT)
    assertThat(PlayerGestures.resolve(0f, enough - 5f, w, h)).isEqualTo(PlayerGesture.NONE)
    // Un lancio veloce chiude anche con poca strada (stessa soglia del viewer, in dp/s).
    assertThat(PlayerGestures.resolve(0f, 40f, w, h, velocityY = GalleryDismissSpec.FlingVelocityDp * 2, density = 1f)).isEqualTo(PlayerGesture.EXIT)
    assertThat(PlayerGestures.resolve(0f, -2_000f, w, h)).isEqualTo(PlayerGesture.NONE)
  }

  @Test
  fun unGestoPiuVerticaleCheOrizzontaleNonScorre() {
    assertThat(PlayerGestures.resolve(200f, 210f, w, h)).isEqualTo(PlayerGesture.NONE)
    assertThat(PlayerGestures.resolve(0f, 100f, 0f, h)).isEqualTo(PlayerGesture.NONE)
  }
}
