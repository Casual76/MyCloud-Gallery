package dev.pampa.mycloudgallery.core.designsystem.adaptive

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 10 (P0, D12, D13, D15): le soglie delle classi di finestra (compatta < 600, media 600-839, estesa 840-1199, ampia >= 1200) e i pannelli. */
class GalleryWindowTest {

  @Test
  fun leSoglieDiD12() {
    val cases = mapOf(
      320 to GalleryWidth.COMPACT, 599 to GalleryWidth.COMPACT, 600 to GalleryWidth.MEDIUM, 839 to GalleryWidth.MEDIUM,
      840 to GalleryWidth.EXPANDED, 1199 to GalleryWidth.EXPANDED, 1200 to GalleryWidth.LARGE, 2560 to GalleryWidth.LARGE,
    )
    for ((dp, width) in cases) assertThat(GalleryWidth.of(dp)).isEqualTo(width)
    assertThat(GalleryWindow.of(800, 479).shortHeight).isTrue()
    assertThat(GalleryWindow.of(800, 480).shortHeight).isFalse()
    assertThat(GalleryWindow.of(1280, 800, PointerKind.MOUSE).pointer).isEqualTo(PointerKind.MOUSE)
    assertThat(GalleryWindow.MIN_WIDTH_DP to GalleryWindow.MIN_HEIGHT_DP).isEqualTo(320 to 400)
  }

  @Test
  fun iPannelli() {
    assertThrows(IllegalArgumentException::class.java) { GalleryPanes.ListDetail(1f) }
    assertThrows(IllegalArgumentException::class.java) { GalleryPanes.Docked(0f) }
    assertThat(GalleryPanes.Docked(0.4f).gridFraction).isEqualTo(0.4f)
  }
}
