package dev.pampa.mycloudgallery.core.imaging.budget

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Il budget di memoria da `memoryClass` (D5, rischio memoria del §7): le cifre che finiscono nel report. */
@RunWith(RobolectricTestRunner::class)
class ImagingBudgetTest {

  private val mb = ImagingBudget.MB

  @Test
  fun latabellaDelBudgetPerMemoryClass() {
    val table = mapOf(
      128 to (32 * mb to (128 * mb / 5)),
      192 to (48 * mb to (192 * mb / 5)),
      256 to (64 * mb to (256 * mb / 5)),
      512 to (96 * mb to (64 * mb)),
    )
    for ((memoryClass, expected) in table) {
      val budget = ImagingBudget.forMemoryClass(memoryClass)
      assertThat(budget.thumbnailCacheBytes).isEqualTo(expected.first)
      assertThat(budget.viewerBitmapBytes).isEqualTo(expected.second)
      assertThat(budget.viewerConcurrency).isEqualTo(3)
      assertThat(budget.rawMaxSide).isEqualTo(4096)
      assertThat(budget.thumbnailConcurrency).isIn(2..4)
    }
  }

  @Test
  fun pavimentoESoffittoTengonoIlBudgetInUnaFasciaUtile() {
    val tiny = ImagingBudget.forMemoryClass(8)
    assertThat(tiny.thumbnailCacheBytes).isAtLeast(24 * mb)
    assertThat(tiny.viewerBitmapBytes).isAtLeast(24 * mb)
    val huge = ImagingBudget.forMemoryClass(100_000)
    assertThat(huge.thumbnailCacheBytes).isAtMost(96 * mb)
    assertThat(huge.viewerBitmapBytes).isAtMost(64 * mb)
  }

  @Test
  fun unTelefonoConPocaRamDimezzaLaMemoriaEIlLavoro() {
    val normal = ImagingBudget.forMemoryClass(256)
    val low = ImagingBudget.forMemoryClass(256, lowRam = true)
    assertThat(low.thumbnailCacheBytes).isEqualTo(normal.thumbnailCacheBytes / 2)
    assertThat(low.viewerBitmapBytes).isEqualTo(normal.viewerBitmapBytes / 2)
    assertThat(low.thumbnailConcurrency).isEqualTo(2)
    assertThat(low.placeholderEntries).isLessThan(normal.placeholderEntries)
    assertThat(ImagingBudget.forMemoryClass(48, lowRam = true).thumbnailCacheBytes).isAtLeast(16 * mb)
  }

  @Test
  fun ilTettoDiConcorrenzaSegueIProcessoriFraDueEQuattro() {
    assertThat(ImagingBudget.forMemoryClass(256, cores = 1).thumbnailConcurrency).isEqualTo(2)
    assertThat(ImagingBudget.forMemoryClass(256, cores = 3).thumbnailConcurrency).isEqualTo(3)
    assertThat(ImagingBudget.forMemoryClass(256, cores = 16).thumbnailConcurrency).isEqualTo(4)
  }

  @Test
  fun unDngDa4096pxEntraNelTettoDaSoloDa256MbInSu() {
    val dng4096x3072 = 4096L * 3072 * 4
    assertThat(ImagingBudget.forMemoryClass(256).viewerBitmapBytes).isAtLeast(dng4096x3072)
    // Con meno memoria il DNG si campiona di piu' (lo fa il piano di decodifica), non si rifiuta.
    assertThat(ImagingBudget.forMemoryClass(128).viewerBitmapBytes).isLessThan(dng4096x3072)
  }

  @Test
  fun ilBudgetDelDispositivoStaNelleFasce() {
    val budget = ImagingBudget.of(ApplicationProvider.getApplicationContext())
    assertThat(budget.thumbnailCacheBytes).isAtLeast(16 * mb)
    assertThat(budget.thumbnailCacheBytes).isAtMost(96 * mb)
    assertThat(budget.viewerMaxPixels).isEqualTo(budget.viewerBitmapBytes / 4)
  }

  @Test(expected = IllegalArgumentException::class)
  fun unBudgetSenzaMemoriaNonSiCostruisce() {
    ImagingBudget(256, 0, 1, 1, 1, 1, 1)
  }
}
