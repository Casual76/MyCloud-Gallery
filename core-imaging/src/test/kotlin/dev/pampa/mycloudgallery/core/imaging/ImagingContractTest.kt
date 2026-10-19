package dev.pampa.mycloudgallery.core.imaging

import android.os.CancellationSignal
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagingContractTest {

  private val ref = MediaRef.Synthetic(seed = 42, width = 4000, height = 3000)
  private val key = ThumbKey(MediaId.random(), versionKey = 1, bucket = ThumbBucket.M)

  @Test
  fun leTaglieCopronoIlLatoRichiesto() {
    assertThat(ThumbBucket.covering(100)).isEqualTo(ThumbBucket.S)
    assertThat(ThumbBucket.covering(161)).isEqualTo(ThumbBucket.M)
    assertThat(ThumbBucket.covering(400)).isEqualTo(ThumbBucket.L)
    assertThat(ThumbBucket.covering(5000)).isEqualTo(ThumbBucket.L)
  }

  @Test
  fun iGradientiSonoDeterministiciEDistinti() {
    val a = SyntheticPixels.argb(1, 16, 12)
    assertThat(a).isEqualTo(SyntheticPixels.argb(1, 16, 12))
    assertThat(a).isNotEqualTo(SyntheticPixels.argb(2, 16, 12))
    assertThat(a.all { (it ushr 24) == 0xFF }).isTrue()
    assertThat(SyntheticPixels.sizeFor(4000, 3000, 400)).isEqualTo(SyntheticPixels.MAX_SIDE to 72)
    assertThat(SyntheticPixels.sizeFor(3000, 4000, 40)).isEqualTo(30 to 40)
  }

  @Test
  fun ilCaricatoreFintoDaUnaBitmapEMemorizzaLeFinestre() = runTest {
    val loader = FakeThumbnailLoader()
    assertThat(loader.peek(key)).isNull()
    val image = loader.image(key, ref)
    assertThat(image.value).isNotNull()
    assertThat(loader.peek(key)).isNotNull()
    assertThat(loader.produced).isEqualTo(1)
    // Stessa chiave, stessa bitmap: nessun secondo lavoro.
    loader.image(key, ref)
    assertThat(loader.produced).isEqualTo(1)
    loader.window(listOf(ThumbRequest(key, ref)), emptyList())
    assertThat(loader.windows).hasSize(1)
    loader.trim(80)
    assertThat(loader.peek(key)).isNull()
    // Una sorgente che non conosce il riferimento non produce niente.
    assertThat(SyntheticThumbnailSource().load(MediaRef.Phone("external_primary", 1, null), 160, CancellationSignal())).isNull()
  }

  /**
   * GA-3, il finto: come la cache vera, da `UI_HIDDEN` in su le miniature visibili spariscono (la cella torna sul segnaposto) e tornano
   * con `resume` al primo piano. Lo stesso scenario sul caricatore vero sta in `PipelineThumbnailLoaderTest.ga3_…`.
   */
  @Test
  fun ga3_ilCaricatoreFintoPerdeEritrovaLeMiniatureCome_ilVero() {
    val loader = FakeThumbnailLoader()
    val image = loader.image(key, ref)
    loader.window(listOf(ThumbRequest(key, ref)), emptyList())
    assertThat(image.value).isNotNull()

    // Una pressione lieve (l'app e' visibile) non toglie niente a chi guarda.
    loader.trim(10)
    assertThat(image.value).isNotNull()

    loader.trim(40)
    assertThat(image.value).isNull()
    assertThat(loader.peek(key)).isNull()

    loader.resume()
    assertThat(image.value).isNotNull()
    assertThat(loader.peek(key)).isNotNull()
    // Senza niente di scartato, tornare in primo piano non rifa' lavoro.
    val produced = loader.produced
    loader.resume()
    assertThat(loader.produced).isEqualTo(produced)
  }

  @Test
  fun ilSegnapostoDaUnThumbHashEQuelloStesso() {
    val cache = FakePlaceholderCache()
    val hash = ThumbHash("3PcNNYSFeXhwiIiAd8e3d4h4eYeA")
    assertThat(cache.image(hash)).isSameInstanceAs(cache.image(hash))
    assertThat(cache.image(hash).width).isEqualTo(32)
  }

  @Test
  fun ilViewerFintoDaAnteprimaPoiNitida() = runTest {
    val source = FakeViewerImageSource()
    val states = source.state(ref, IntSize(1080, 2400)).toList()
    assertThat(states.first()).isInstanceOf(ViewerImageState.Preview::class.java)
    assertThat(states.last()).isInstanceOf(ViewerImageState.Sharp::class.java)
    assertThat(source.inFlight).isEqualTo(0)
    assertThat(source.maxInFlight).isEqualTo(1)
    assertThat(source.zoomSpec(ref)?.tiled).isTrue()

    // Sorgente irraggiungibile con la dimensione (E-V1, E-V2).
    source.unreachable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, sizeBytes = 5_000_000)
    assertThat(source.state(ref, IntSize(1080, 2400)).toList())
      .containsExactly(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 5_000_000))
  }

  @Test
  fun iNullObjectFallisconoChiusi() = runTest {
    assertThat(NoThumbnailLoader.image(key, ref).value).isNull()
    assertThat(NoThumbnailLoader.peek(key)).isNull()
    assertThat(NoThumbnailSource.load(ref, 160, CancellationSignal())).isNull()
    assertThat(NoPlaceholderCache.image(ThumbHash("3PcNNYSFeXhwiIiAd8e3d4h4eYeA")).width).isEqualTo(1)
    assertThat(NoViewerImageSource.state(ref, IntSize(100, 100)).toList())
      .containsExactly(ViewerImageState.Failed(ImageFailure.NOT_FOUND))
    assertThat(NoViewerImageSource.zoomSpec(ref)).isNull()
  }
}
