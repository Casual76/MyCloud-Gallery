package dev.pampa.mycloudgallery.debugtools.eval

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DB-1: lo strumento di valutazione (P9) mostra le **miniature locali** della Libreria. Prima `thumbnail()` rispondeva sempre null e la griglia era
 * una distesa di riquadri grigi. Qui il caricatore e' finto ma segue il contratto vero: `image` non avvia niente, il lavoro lo fa la finestra
 * (`window`), e le richieste del NAS sono "in arrivo" (la sorgente del NAS non legge l'originale se non sono visibili, D33).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class EvalThumbnailsTest {

  private val library = SyntheticLibrary(seed = 5, count = 40)
  private val snapshot = library.snapshot

  private fun phoneRef(i: Int) = MediaRef.Phone("external_primary", 100L + i, "image/jpeg")

  private fun nasRef(i: Int) = MediaRef.Nas(locationId = 1L, contentKey = 500L + i, mime = "image/jpeg")

  /** Il caricatore finto: carica solo cio' che la finestra chiede (visibile o in arrivo), con le regole del contratto. */
  private class WindowLoader : ThumbnailLoader {
    val states = HashMap<ThumbKey, MutableState<ImageBitmap?>>()

    /** Se vero, il caricatore non produce niente (sorgente lenta): le attese restano aperte. */
    var holdBack: Boolean = false
    val windows = ArrayList<Pair<List<ThumbRequest>, List<ThumbRequest>>>()

    override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> = states.getOrPut(key) { mutableStateOf(null) }

    override fun peek(key: ThumbKey): ImageBitmap? = states[key]?.value

    override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
      windows += visible to ahead
      if (holdBack) return
      // Cio' che la finestra chiede si carica: le richieste del telefono e quelle in arrivo (la replica locale del NAS).
      for (r in visible + ahead) states.getOrPut(r.key) { mutableStateOf(null) }.value = bitmap()
    }

    override fun trim(level: Int) = Unit

    override val stats: StateFlow<ThumbStats> = MutableStateFlow(ThumbStats())

    private fun bitmap(): ImageBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
  }

  private fun thumbnails(loader: ThumbnailLoader, waitMs: Long = 1_000, refAt: (Int) -> MediaRef?) =
    EvalThumbnails(loader, { snapshot }, { _, i -> refAt(i) }, waitMs)

  private fun idAt(i: Int): MediaId = snapshot.idAt(i)

  @Test
  fun unaMiniaturaDelTelefonoArrivaDallaFinestraDelCaricatore() = runTest {
    val loader = WindowLoader()
    val t = thumbnails(loader, refAt = ::phoneRef)
    assertThat(t.of(idAt(3))).isNotNull()
    // La richiesta e' passata come visibile, con la sorgente del telefono.
    val request = loader.windows.first().first.single()
    assertThat(request.ref).isEqualTo(phoneRef(3))
    assertThat(request.key.mediaId).isEqualTo(idAt(3))
  }

  /** Il caricatore lavora a finestre: una finestra da una richiesta sola cancellerebbe le altre. Le celle in vista si tengono tutte nell'insieme voluto. */
  @Test
  fun leCelleInVistaNonSiAnnullanoAVicenda() = runTest {
    val loader = WindowLoader()
    val t = thumbnails(loader, waitMs = 60_000, refAt = ::phoneRef)
    // Tre celle che aspettano (il caricatore non risponde: nessuna bitmap), una dopo l'altra.
    loader.holdBack = true
    val jobs = (0 until 3).map { i -> async { t.of(idAt(i)) } }
    runCurrent()
    val last = loader.windows.last()
    assertThat(last.first.map { it.key.mediaId }).containsExactly(idAt(0), idAt(1), idAt(2))
    jobs.forEach { it.cancel() }
    runCurrent()
    // Finite le attese, l'insieme voluto si svuota: nessuna richiesta resta in volo per celle che non ci sono piu'.
    assertThat(loader.windows.last().first).isEmpty()
  }

  /** Un media solo sul NAS e' "in arrivo": la sorgente del NAS legge l'originale solo per il contenuto visibile, e qui non lo e' mai (D33). */
  @Test
  fun unMediaSoloSulNasEUnaRichiestaInArrivoMaiVisibile() = runTest {
    val loader = WindowLoader()
    val t = thumbnails(loader) { i -> if (i == 2) nasRef(i) else phoneRef(i) }
    assertThat(t.of(idAt(2))).isNotNull()
    val window = loader.windows.first()
    assertThat(window.first).isEmpty()
    assertThat(window.second.single().ref).isEqualTo(nasRef(2))
  }

  @Test
  fun unaMiniaturaGiaInMemoriaSiDaSubitoSenzaFinestra() = runTest {
    val loader = WindowLoader()
    val t = thumbnails(loader, refAt = ::phoneRef)
    assertThat(t.of(idAt(1))).isNotNull()
    val windows = loader.windows.size
    assertThat(t.of(idAt(1))).isNotNull()
    assertThat(loader.windows.size).isEqualTo(windows)
  }

  @Test
  fun unMediaCheNonCEOSenzaSorgenteRispondeNullSenzaChiedereNiente() = runTest {
    val loader = WindowLoader()
    val t = thumbnails(loader) { null }
    assertThat(t.of(idAt(0))).isNull()
    assertThat(t.of(MediaId.random())).isNull()
    assertThat(loader.windows).isEmpty()
  }

  @Test
  fun seLaMiniaturaNonArrivaLAttesaFinisceELaRichiestaSiToglie() = runTest {
    val loader = WindowLoader()
    loader.holdBack = true
    val t = thumbnails(loader, waitMs = 500, refAt = ::phoneRef)
    val result = async { t.of(idAt(5)) }
    advanceTimeBy(600)
    runCurrent()
    assertThat(result.await()).isNull()
    assertThat(loader.windows.last().first).isEmpty()
  }
}
