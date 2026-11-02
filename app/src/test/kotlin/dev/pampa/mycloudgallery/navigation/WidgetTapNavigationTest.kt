package dev.pampa.mycloudgallery.navigation

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerAnchor
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.main.ViewerHostState
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il tocco sul widget dentro l'app (Fase 10, correzioni del pacchetto B: WG-3, WG-4; docs/fase10-progetto.md §2.2, E-W7, E-W8). Il tocco con un viewer **gia' aperto** apre la foto
 * toccata (non resta su quella di prima); a freddo, con l'istantanea della Libreria che arriva dopo, il tocco **aspetta** invece di dire "Questa foto non c'e' piu'", e
 * quell'avviso resta per la foto che davvero non c'e'. Il tempo e' virtuale (`runTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetTapNavigationTest {

  private val library = SyntheticLibrary(seed = 3, count = 30)
  private val services = ViewerServices()

  private fun request(index: Int) = ViewerRequest(library.viewerItems(), library.snapshot.idAt(index), FakeViewerAnchor(), secure = false)

  /** WG-3: l'utente guarda la foto 2, va alla Home e tocca la foto 7 del widget: si apre la 7, non resta la 2. */
  @Test
  fun unViewerGiaApertoSiCambiaConLaFotoToccata() {
    val viewer = ViewerHostState(null)
    viewer.open(request(2), services, fromLibrary = true)
    assertThat(viewer.request?.start).isEqualTo(library.snapshot.idAt(2))
    var wentToMain = 0
    val tapped = request(7)
    showWidgetViewer(viewer, tapped, { wentToMain++ }) { viewer.open(it, services) }
    assertThat(wentToMain).isEqualTo(1)
    assertThat(viewer.request).isSameInstanceAs(tapped)
    assertThat(viewer.request?.start).isEqualTo(library.snapshot.idAt(7))
  }

  /** WG-3: senza un viewer aperto (o con la finestra senza viewer) il tocco apre il viewer come prima. */
  @Test
  fun senzaViewerApertoSiApreLaFotoToccata() {
    val viewer = ViewerHostState(null)
    val tapped = request(4)
    showWidgetViewer(viewer, tapped, {}) { viewer.open(it, services) }
    assertThat(viewer.request).isSameInstanceAs(tapped)
    // Nessun host del viewer (nei test che compongono la sola scheda): niente da chiudere, e la richiesta arriva a chi la apre.
    var opened: ViewerRequest? = null
    showWidgetViewer(null, tapped, {}) { opened = it }
    assertThat(opened).isSameInstanceAs(tapped)
  }

  /** WG-4: a freddo l'istantanea e' ancora null: il tocco aspetta e, quando arriva (200 ms dopo), trova la foto: niente "Questa foto non c'e' piu'". */
  @Test
  fun aFreddoSiAspettaLIstantaneaEConLaFotoLaSiApre() = runTest {
    val snapshot = MutableStateFlow<TimelineSnapshot?>(null)
    val media = library.snapshot.idAt(5)
    val lookup = async { lookupTappedMedia(snapshot, media) }
    advanceTimeBy(200)
    runCurrent()
    assertThat(lookup.isCompleted).isFalse()
    snapshot.value = library.snapshot
    val found = lookup.await()
    assertThat(found).isInstanceOf(TappedMedia.Found::class.java)
    assertThat((found as TappedMedia.Found).view.size).isEqualTo(1)
    assertThat(currentTime).isAtMost(1_000L)
  }

  /** WG-4: con l'istantanea in mano un media che non c'e' (nascosto o eliminato) e' davvero "non c'e' piu'": l'avviso resta per lui. */
  @Test
  fun unaFotoCheNonCEPiuEGone() = runTest {
    val snapshot = MutableStateFlow<TimelineSnapshot?>(library.snapshot)
    assertThat(lookupTappedMedia(snapshot, MediaId(UUID(9, 9).toString()))).isEqualTo(TappedMedia.Gone)
    // Anche se l'istantanea arriva dopo: aspetta, poi dice che non c'e'.
    val late = MutableStateFlow<TimelineSnapshot?>(null)
    val lookup = async { lookupTappedMedia(late, MediaId(UUID(9, 9).toString())) }
    advanceTimeBy(100)
    late.value = library.snapshot
    assertThat(lookup.await()).isEqualTo(TappedMedia.Gone)
  }

  /** WG-4: se l'istantanea non arriva nel tempo (archivio grande) non si dice "non c'e' piu'": la Libreria senza avviso, dopo il tetto e non prima. */
  @Test
  fun seLIstantaneaNonArrivaNelTempoNonEPronta() = runTest {
    val snapshot = MutableStateFlow<TimelineSnapshot?>(null)
    val lookup = async { lookupTappedMedia(snapshot, library.snapshot.idAt(1)) }
    advanceTimeBy(TAP_SNAPSHOT_WAIT_MS - 1)
    runCurrent()
    assertThat(lookup.isCompleted).isFalse()
    // Il tempo virtuale arriva al tetto e non oltre: l'esito e' "non pronta".
    assertThat(lookup.await()).isEqualTo(TappedMedia.NotReady)
    assertThat(currentTime).isEqualTo(TAP_SNAPSHOT_WAIT_MS)
  }
}
