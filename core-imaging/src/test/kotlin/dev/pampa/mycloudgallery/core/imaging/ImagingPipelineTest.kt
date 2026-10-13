package dev.pampa.mycloudgallery.core.imaging

import android.os.CancellationSignal
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.placeholder.ThumbHashDecoder
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Il montaggio per `:app`: i pezzi della pipeline si compongono dal contesto, con thread veri. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagingPipelineTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun laPipelineSiMontaDalContestoEServeLaLibreriaSintetica() {
    val budget = ImagingBudget.of(context)
    val source = ImagingPipeline.thumbnailSource(context, synthetic = SyntheticThumbnailSource())
    val loader = ImagingPipeline.thumbnailLoader(source, budget)
    try {
      val ref = MediaRef.Synthetic(seed = 5, width = 400, height = 300)
      val key = ThumbKey(MediaId.random(), versionKey = 1, bucket = ThumbBucket.S)
      val state = loader.image(key, ref)
      loader.window(listOf(ThumbRequest(key, ref)), emptyList())
      runBlocking { withTimeout(10_000) { while (state.value == null) delay(10) } }
      assertThat(loader.peek(key)).isNotNull()
      // Un riferimento del telefono senza nulla nel MediaStore non da una miniatura e non rompe niente.
      val missing = ThumbRequest(
        ThumbKey(MediaId.random(), 1, ThumbBucket.S),
        MediaRef.Phone("external_primary", 123456, "image/jpeg"),
      )
      loader.window(listOf(missing), emptyList())
      runBlocking { delay(200) }
      assertThat(loader.peek(missing.key)).isNull()
    } finally {
      loader.close()
    }
  }

  @Test
  fun senzaLaSorgenteSinteticaIRiferimentiSinteticiNonProduconoNiente() {
    val source = ImagingPipeline.thumbnailSource(context)
    val bitmap = runBlocking { source.load(MediaRef.Synthetic(1, 10, 10), 160, CancellationSignal()) }
    assertThat(bitmap).isNull()
  }

  @Test
  fun ilViewerEIlSegnapostoSiMontanoEFallisconoChiusi() {
    val budget = ImagingBudget.of(context)
    val source = ImagingPipeline.thumbnailSource(context)
    val viewer = ImagingPipeline.viewerImageSource(context, source, budget, DefaultDispatcherProvider)
    // Un media che non c'e' nel MediaStore: stato di errore stabile, nessuna eccezione.
    val states = runBlocking { viewer.state(MediaRef.Phone("external_primary", 99, "image/jpeg"), androidx.compose.ui.unit.IntSize(1080, 2400)).toList() }
    assertThat(states.last()).isInstanceOf(ViewerImageState.Failed::class.java)

    val placeholders = ImagingPipeline.placeholderCache(budget, ThumbHashDecoder { _, side -> IntArray(side * side) { 0xFF102030.toInt() } })
    assertThat(placeholders.image(ThumbHash.of(byteArrayOf(1, 2, 3, 4))).width).isEqualTo(32)
  }
}
