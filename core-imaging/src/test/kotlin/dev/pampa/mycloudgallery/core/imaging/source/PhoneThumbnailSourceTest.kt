package dev.pampa.mycloudgallery.core.imaging.source

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * `PhoneThumbnailSource` (D5): gli URI del MediaStore, la taglia, la riduzione, gli errori e l'annullamento. La
 * chiamata di sistema (`ContentResolver.loadThumbnail`) e' una finta quasi dappertutto, perche' la sua decodifica
 * passa da un file descriptor che Robolectric non sa leggere su Windows; un test la prova vera fino al provider
 * `media` (URI, dimensione richiesta e segnale), dove il provider finto risponde "file mancante".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneThumbnailSourceTest {

  private class Call(val uri: Uri, val size: Size, val signal: CancellationSignal)

  /** Una `ContentThumbnails` che registra le chiamate e risponde con [answer]. */
  private class FakeContent(var answer: (Uri) -> Bitmap = { SyntheticPixels.bitmap(1, 512, 384) }) : ContentThumbnails {
    val calls = CopyOnWriteArrayList<Call>()
    override fun load(uri: Uri, size: Size, signal: CancellationSignal): Bitmap {
      calls += Call(uri, size, signal)
      return answer(uri)
    }
  }

  private fun phone(id: Long = 42, mime: String? = "image/jpeg", volume: String = "external_primary"): MediaRef.Phone =
    MediaRef.Phone(volume, id, mime)

  private fun source(content: ContentThumbnails) = PhoneThumbnailSource(content, Dispatchers.Unconfined)

  @Test
  fun unaImmagineUsaIlCatalogoDelleImmaginiConLaTagliaChiesta() = runTest {
    val content = FakeContent()
    source(content).load(phone(), 320, CancellationSignal())
    val call = content.calls.single()
    assertThat(call.uri.toString()).isEqualTo("content://media/external_primary/images/media/42")
    assertThat(call.size).isEqualTo(Size(320, 320))
  }

  @Test
  fun unVideoUsaIlCatalogoDeiVideo() = runTest {
    val content = FakeContent()
    source(content).load(phone(mime = "video/mp4"), 160, CancellationSignal())
    assertThat(content.calls.single().uri.toString()).isEqualTo("content://media/external_primary/video/media/42")
  }

  @Test
  fun unaMicroSdUsaIlSuoVolume() = runTest {
    val content = FakeContent()
    source(content).load(phone(volume = "0123-4567"), 160, CancellationSignal())
    assertThat(content.calls.single().uri.toString()).isEqualTo("content://media/0123-4567/images/media/42")
  }

  @Test
  fun senzaMimeSiProvaComeImmagineEPoiComeVideo() = runTest {
    val content = FakeContent { uri ->
      if ("/images/" in uri.toString()) throw FileNotFoundException() else SyntheticPixels.bitmap(1, 96, 96)
    }
    val bitmap = source(content).load(phone(mime = null), 160, CancellationSignal())
    assertThat(bitmap).isNotNull()
    assertThat(content.calls.map { it.uri.toString() }).containsExactly(
      "content://media/external_primary/images/media/42",
      "content://media/external_primary/video/media/42",
    ).inOrder()
  }

  @Test
  fun unaMiniaturaMoltoPiuGrandeDellaTagliaSiRiduceMantenendoLaProporzione() = runTest {
    val content = FakeContent { SyntheticPixels.bitmap(1, 512, 384) }
    val small = source(content).load(phone(), 160, CancellationSignal())!!
    assertThat(small.width).isEqualTo(160)
    assertThat(small.height).isEqualTo(120)
    // Il risparmio e' il motivo: 160x120 contro 512x384.
    assertThat(small.allocationByteCount).isLessThan(512 * 384 * 4 / 8)

    val portrait = FakeContent { SyntheticPixels.bitmap(1, 384, 512) }
    val p = source(portrait).load(phone(), 320, CancellationSignal())!!
    assertThat(p.width).isEqualTo(240)
    assertThat(p.height).isEqualTo(320)
  }

  @Test
  fun unaMiniaturaGiaDellaTagliaGiustaNonSiToccaNeSiCopia() = runTest {
    val original = SyntheticPixels.bitmap(1, 400, 300)
    val content = FakeContent { original }
    // 400 <= 320 * 1.25: abbastanza vicina, resta com'e'.
    assertThat(source(content).load(phone(), 320, CancellationSignal())).isSameInstanceAs(original)
  }

  @Test
  fun unRiferimentoDiAltroTipoNonProduceNiente() = runTest {
    val content = FakeContent()
    assertThat(source(content).load(MediaRef.Synthetic(1, 10, 10), 160, CancellationSignal())).isNull()
    assertThat(content.calls).isEmpty()
  }

  @Test
  fun glErroriDelSistemaDiventanoNullSenzaEccezioni() = runTest {
    val errors = listOf<() -> Throwable>(
      { FileNotFoundException() },
      { IOException("illeggibile") },
      { SecurityException("permesso ritirato") },
      { IllegalArgumentException() },
      { IllegalStateException() },
    )
    for (error in errors) {
      val content = FakeContent { throw error() }
      assertThat(source(content).load(phone(), 160, CancellationSignal())).isNull()
    }
  }

  @Test
  fun unSegnaleGiaScattatoNonChiamaIlSistema() = runTest {
    val content = FakeContent()
    val signal = CancellationSignal().also { it.cancel() }
    val result = runCatching { source(content).load(phone(), 160, signal) }
    assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    assertThat(content.calls).isEmpty()
  }

  @Test
  fun unOperazioneAnnullataDalSistemaDiventaUnAnnullamentoDiCoroutine() = runTest {
    val content = FakeContent { throw OperationCanceledException() }
    val result = runCatching { source(content).load(phone(), 160, CancellationSignal()) }
    assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
  }

  @Test
  fun lAnnullamentoArrivaAlSistemaConLoStessoSegnale() = runTest {
    val content = FakeContent()
    val signal = CancellationSignal()
    source(content).load(phone(), 160, signal)
    assertThat(content.calls.single().signal).isSameInstanceAs(signal)
  }

  /** Un provider `media` finto: registra la chiamata e risponde "file mancante" (la decodifica non gira su Windows). */
  class RecordingMediaProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor? {
      seen += Seen(uri.toString(), mimeTypeFilter, opts?.getParcelable("android.content.extra.SIZE", Point::class.java), signal != null)
      throw FileNotFoundException("finto")
    }

    class Seen(val uri: String, val mimeType: String, val size: Point?, val hadSignal: Boolean)

    companion object {
      val seen = CopyOnWriteArrayList<Seen>()
    }
  }

  @Test
  fun laChiamataVeraArrivaAlProviderMediaConDimensioneESegnale() = runTest {
    RecordingMediaProvider.seen.clear()
    Robolectric.buildContentProvider(RecordingMediaProvider::class.java).create("media")
    val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    val source = PhoneThumbnailSource(resolver, Dispatchers.Unconfined)
    // Senza mime: prima come immagine, poi come video; il provider risponde "mancante" a entrambi e il risultato e' null.
    assertThat(source.load(phone(id = 7, mime = null), 320, CancellationSignal())).isNull()
    val seen = RecordingMediaProvider.seen.toList()
    assertThat(seen.map { it.uri }).containsExactly(
      "content://media/external_primary/images/media/7",
      "content://media/external_primary/video/media/7",
    ).inOrder()
    assertThat(seen.all { it.size == Point(320, 320) }).isTrue()
    assertThat(seen.all { it.hadSignal }).isTrue()
    assertThat(seen.all { it.mimeType == "image/*" }).isTrue()
  }

  @Test
  fun laSorgenteSinteticaRispettaLAnnullamento() = runTest {
    val synthetic = SyntheticThumbnailSource()
    val ref = MediaRef.Synthetic(5, 400, 300)
    assertThat(synthetic.load(ref, 160, CancellationSignal())).isNotNull()
    val cancelled = CancellationSignal().also { it.cancel() }
    val result = runCatching { synthetic.load(ref, 160, cancelled) }
    assertThat(result.exceptionOrNull()).isInstanceOf(OperationCanceledException::class.java)
    // La sorgente sintetica e' deterministica: stessi pixel per lo stesso seme.
    val a = synthetic.loadNow(ref, 96)!!
    val b = synthetic.loadNow(ref, 96)!!
    assertThat(a.sameAs(b)).isTrue()
  }

  @Test
  fun ilRouterScegliePerTipoDiRiferimento() = runTest {
    val phoneContent = FakeContent()
    val router = RoutingThumbnailSource(source(phoneContent), SyntheticThumbnailSource())
    assertThat(router.load(phone(), 160, CancellationSignal())).isNotNull()
    assertThat(phoneContent.calls).hasSize(1)
    assertThat(router.load(MediaRef.Synthetic(3, 400, 300), 160, CancellationSignal())).isNotNull()
    assertThat(phoneContent.calls).hasSize(1)
    // Senza la sorgente sintetica (la release) i riferimenti sintetici non producono niente.
    val releaseRouter = RoutingThumbnailSource(source(phoneContent))
    assertThat(releaseRouter.load(MediaRef.Synthetic(3, 400, 300), 160, CancellationSignal())).isNull()
    assertThat(NoThumbnailSource.load(phone(), 160, CancellationSignal())).isNull()
  }
}
