package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.source.BundledModelFetcher
import dev.pampa.mycloudgallery.core.models.support.TestModels
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Il modello dentro l'APK (il ritaglio, Q11 (a), D44): si estrae da un asset con la dimensione fissata, mai oltre. */
class BundledModelFetcherTest {
  private val asset = "cutout/magic_touch.tflite"
  private val bytes = TestModels.bytes(2 * TestModels.BLOCK + 321, seed = 4)
  private val spec = ModelCatalog.CUTOUT_MAGICTOUCH.copy(sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes))
  private val partial = File(TestModels.tempDir(), "partial/${spec.sha256.hex}.part")

  private fun fetch(source: ModelSource = ModelSource.Bundled(asset), assets: BundledAssets) =
    runBlocking { BundledModelFetcher(assets).fetch(spec, source, partial, 10.seconds) }

  @Test
  fun estraeLAsset() {
    assertThat(fetch { ByteArrayInputStream(bytes) }).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun unAssetDiUnaltraLunghezzaNonEIlModello() {
    assertThat(fetch { ByteArrayInputStream(bytes + ByteArray(1)) }).isEqualTo(FetchResult.Mismatch)
    assertThat(fetch { ByteArrayInputStream(bytes + ByteArray(3 * TestModels.BLOCK)) }).isEqualTo(FetchResult.Mismatch)
    assertThat(partial.length()).isAtMost(bytes.size.toLong())
    assertThat(fetch { ByteArrayInputStream(bytes.copyOf(bytes.size - 1)) }).isEqualTo(FetchResult.Mismatch)
    assertThat(fetch { ByteArrayInputStream(ByteArray(0)) }).isEqualTo(FetchResult.Mismatch)
  }

  @Test
  fun assenteOIllegibile() {
    assertThat(fetch { null }).isEqualTo(FetchResult.NotFound)
    assertThat(fetch { throw IOException("asset") }).isEqualTo(FetchResult.NotFound)
    val breaking = BundledAssets {
      object : InputStream() {
        private var sent = 0

        override fun read(): Int = throw IOException("rotto")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (sent > 0) throw IOException("rotto")
          sent = 1
          return minOf(len, 100)
        }
      }
    }
    assertThat(fetch(assets = breaking)).isEqualTo(FetchResult.NetworkError)
  }

  @Test
  fun soloLAssetDelCatalogo() {
    var opened = false
    val assets = BundledAssets {
      opened = true
      ByteArrayInputStream(bytes)
    }
    assertThat(fetch(ModelSource.Bundled("altro/file.bin"), assets)).isEqualTo(FetchResult.Refused(FetchRefusal.SOURCE_KIND))
    assertThat(fetch(ModelSource.PcFolder("x"), assets)).isEqualTo(FetchResult.Refused(FetchRefusal.SOURCE_KIND))
    assertThat(opened).isFalse()
  }

  @Test
  fun riparteSempreDaZero() {
    partial.parentFile.mkdirs()
    partial.writeBytes(ByteArray(5000) { 3 })
    assertThat(fetch { ByteArrayInputStream(bytes) }).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }
}
