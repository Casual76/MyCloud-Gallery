package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesRefs
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerPreviewSource
import dev.pampa.mycloudgallery.feature.memories.player.ThumbnailPlayerImages
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Le immagini della presentazione (Fase 9, P9; §2.4, D13, E-MEM14, E-MEM15): **mai un'attesa**. Una sorgente lenta, che non risponde mai, non blocca
 * niente; le due immagini successive si chiedono in anticipo; in memoria restano al piu' tre anteprime; cio' che esce dalla finestra si lascia.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ThumbnailPlayerImagesTest {

  private val snapshot: TimelineSnapshot = MemoriesRig().lib.snapshot()
  private val ids = (0 until 8).map { snapshot.idAt(it) }

  private val refs = object : MemoriesRefs {
    override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef = MediaRef.Synthetic(index.toLong(), 100, 100)
  }

  private fun thumbnails(loader: FakeThumbnailLoader, preview: PlayerPreviewSource?) = MemoriesThumbnails(loader, FakePlaceholderCache(), refs, preview)

  @Test
  fun unaSorgenteCheNonRispondeMaiNonBloccaNiente() = runTest {
    val loader = FakeThumbnailLoader { _, _ -> null }
    val images = ThumbnailPlayerImages(thumbnails(loader, PlayerPreviewSource { _, _ -> awaitCancellation() }), backgroundScope)
    // `want` torna subito (non sospende): se aspettasse la sorgente questo test non finirebbe.
    images.want(snapshot, ids[0], ids.subList(1, 3))
    runCurrent()
    assertThat(images.previewOf(ids[0])).isNull()
    // Il caricatore ha gia' la sua finestra: la prima in vista, le due successive in arrivo (decodificate prima).
    val (visible, ahead) = loader.windows.last()
    assertThat(visible.map { it.key.mediaId }).containsExactly(ids[0])
    assertThat(ahead.map { it.key.mediaId }).containsExactly(ids[1], ids[2]).inOrder()
    // Passato il tempo massimo la richiesta lenta si lascia perdere, senza lasciare tracce.
    advanceTimeBy(ThumbnailPlayerImages.PREVIEW_WAIT_MS + 1)
    runCurrent()
    assertThat(images.previewOf(ids[0])).isNull()
  }

  @Test
  fun lAnteprimaLocaleChePronta_siDaSubito_inMemoriaAlPiuTre() = runTest {
    val loader = FakeThumbnailLoader { _, _ -> null }
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
    val images = ThumbnailPlayerImages(thumbnails(loader, PlayerPreviewSource { _, _ -> bitmap }), backgroundScope)
    images.want(snapshot, ids[0], listOf(ids[1], ids[2]))
    runCurrent()
    assertThat(listOf(ids[0], ids[1], ids[2]).map { images.previewOf(it) != null }).containsExactly(true, true, true)
    // Si avanza: l'anteprima di quello che e' uscito dalla finestra non si tiene.
    images.want(snapshot, ids[1], listOf(ids[2], ids[3]))
    runCurrent()
    assertThat(images.previewOf(ids[0])).isNull()
    assertThat(ids.count { images.previewOf(it) != null }).isAtMost(3)
    // Con la finestra piu' avanti ancora, restano solo gli ultimi tre.
    images.want(snapshot, ids[5], listOf(ids[6], ids[7]))
    runCurrent()
    assertThat(ids.filter { images.previewOf(it) != null }).containsExactly(ids[5], ids[6], ids[7])
  }

  @Test
  fun unaSorgenteCheSiRompeNonFaCrollare_eNonSiRiprovaSenzaFine() = runTest {
    val loader = FakeThumbnailLoader { _, _ -> null }
    var calls = 0
    val images = ThumbnailPlayerImages(thumbnails(
        loader,
        PlayerPreviewSource { _, _ ->
          calls++
          error("sorgente rotta")
        },
      ), backgroundScope)
    repeat(3) {
      images.want(snapshot, ids[0], listOf(ids[1], ids[2]))
      runCurrent()
    }
    assertThat(images.previewOf(ids[0])).isNull()
    // Una richiesta per elemento della finestra, non una a ogni giro.
    assertThat(calls).isAtMost(3)
  }

  @Test
  fun senzaAnteprimaLocaleResteLaMiniatura_enientaltro() = runTest {
    val loader = FakeThumbnailLoader { _, _ -> null }
    val images = ThumbnailPlayerImages(thumbnails(loader, preview = null), backgroundScope)
    images.want(snapshot, ids[0], listOf(ids[1]))
    runCurrent()
    assertThat(ids.none { images.previewOf(it) != null }).isTrue()
    assertThat(loader.windows).isNotEmpty()
  }

  @Test
  fun clearSvuotaTuttoELaFinestraDelCaricatore() = runTest {
    val loader = FakeThumbnailLoader { _, _ -> null }
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
    val images = ThumbnailPlayerImages(thumbnails(loader, PlayerPreviewSource { _, _ -> bitmap }), backgroundScope)
    images.want(snapshot, ids[0], listOf(ids[1], ids[2]))
    runCurrent()
    images.clear()
    assertThat(ids.none { images.previewOf(it) != null }).isTrue()
    assertThat(loader.windows.last().first).isEmpty()
    assertThat(loader.windows.last().second).isEmpty()
  }
}
