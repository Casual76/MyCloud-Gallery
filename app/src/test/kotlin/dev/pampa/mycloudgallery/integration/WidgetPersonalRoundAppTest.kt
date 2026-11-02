package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.noop.NoWidgetMemories
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetLock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.RecordingWidgetRefresher
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.widget.LocalWidgetThumbs
import dev.pampa.mycloudgallery.widget.WidgetCounters
import dev.pampa.mycloudgallery.widget.WidgetGraph
import dev.pampa.mycloudgallery.widget.WidgetWiring
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P13, "Fatto quando" del riquadro P13; docs/fase10-progetto.md §2.1 "Nascosto arrivato da un altro dispositivo", D5): **due dispositivi finti** dello stesso
 * account sul NAS finto, con il grafo vero (galleria, NAS, dati personali). Il widget del tablet mostra delle foto (il suo registro); sul telefono se ne nasconde una; i giri
 * dei dati personali la portano sul tablet, e il legame del widget (`WidgetWiring.followHidden`: il giro della sincronizzazione personale e l'istantanea) dice al ridisegno
 * **esattamente** quella foto, che si svuota dalla Home e si ridisegna. Prima del "Nascondi" nessuna chiamata (la prova non passa a vuoto).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class WidgetPersonalRoundAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private var rig: FacesRig? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() {
    scope.cancel()
    rig?.close()
  }

  @Test
  fun unNascostoSulTelefonoSvuotaIlWidgetDelTablet() = runBlocking<Unit> {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val phoneDevice = faces.ai.foundedDevice()
    val tabletDevice = faces.joinDevice(phoneDevice, "tablet", TestNas.ADMIN_USER, TestNas.ADMIN_PASSWORD)
    faces.putPhotos(TestNas.ADMIN_USER, PHOTOS)
    val phone = faces.node("telefono", phoneDevice, TestNas.ADMIN_USER)
    val tablet = faces.node("tablet", tabletDevice, TestNas.ADMIN_USER)
    faces.catalogue(phone, PHOTOS)
    faces.catalogue(tablet, PHOTOS)

    // Il widget del tablet, con le porte vere della sua galleria e il registro in memoria: mostra tutte le foto (due widget, come due sulla Home).
    val gallery = tablet.mounted.gallery
    val ledger = InMemoryWidgetLedger()
    val widget = WidgetGraph.create(
      context = tablet.device.context,
      feed = gallery.widgetFeed,
      visible = gallery.visibleMedia,
      mask = gallery.hiddenMask,
      memories = NoWidgetMemories,
      thumbs = LocalWidgetThumbs({ id -> gallery.repository.mediaRefOf(id) }, gallery.thumbnailSource),
      lock = FakeWidgetLock(),
      prefs = FakeWidgetPrefs(),
      places = WidgetPlaceNames { null },
      awaitShelf = {},
      stoppedByRelease = { false },
      counters = WidgetCounters(),
      clock = FakeWidgetClock(),
      ledger = ledger,
    )
    val refresher = RecordingWidgetRefresher()
    widget.refresher = refresher
    val shown = tabletIds(tablet)
    assertThat(shown).hasSize(PHOTOS)
    ledger.record(plan(1, shown.take(4)))
    ledger.record(plan(2, shown.drop(4)))
    WidgetWiring.followHidden(widget, gallery, scope)
    assertThat(widget.watch()).isEmpty()

    // Sul telefono: "Nascondi" su una foto. I giri dei dati personali (telefono, poi tablet) la portano sul tablet.
    val phoneSnapshot = checkNotNull(phone.mounted.gallery.visibleMedia.snapshot.value)
    phone.mounted.gallery.actions.setHidden(setOf(phoneSnapshot.idAt(0)), true)
    var hiddenOnTablet: Set<MediaId> = emptySet()
    rounds("il nascosto arriva sul tablet", phone, tablet) {
      hiddenOnTablet = gallery.hiddenMask.hiddenAmong(shown)
      hiddenOnTablet.isNotEmpty()
    }
    assertThat(hiddenOnTablet).hasSize(1)

    // Il legame del widget l'ha visto da solo (fine del giro personale o istantanea nuova): il ridisegno sa esattamente quale foto svuotare.
    until("il widget del tablet riceve il nascosto") { refresher.hidden.isNotEmpty() }
    assertWithMessage("le foto dette al ridisegno").that(refresher.hidden.flatten().toSet()).isEqualTo(hiddenOnTablet)
    assertThat(refresher.calls.toSet()).containsExactly("hiddenChanged")
  }

  /** Gli id delle foto nell'istantanea del tablet. */
  private fun tabletIds(tablet: FacesNode): List<MediaId> {
    val snapshot = checkNotNull(tablet.mounted.gallery.visibleMedia.snapshot.value)
    return (0 until snapshot.size).map { snapshot.idAt(it) }
  }

  private fun plan(slot: Int, media: List<MediaId>) = WidgetPlan(
    WidgetSlot(slot), WidgetSize.LARGE, WidgetMode.Photos, media.mapIndexed { i, id -> WidgetCell(i, id, MediaKind.PHOTO, 20261025) }, epoch = 1,
  )

  /** I giri dei dati personali di [nodes] (l'ora del NAS avanza) finche' [condition] vale. */
  private suspend fun rounds(what: String, vararg nodes: FacesNode, condition: suspend () -> Boolean) {
    val rig = checkNotNull(rig)
    try {
      withTimeout(Attese.TETTO) {
        while (!condition()) {
          for (node in nodes) {
            node.device.graph.trustGuard.preflight(Purpose.BACKGROUND)
            node.nas.organizer.syncPersonal(30.seconds)
          }
          rig.time.advance(6.minutes.inWholeMilliseconds)
        }
      }
    } catch (e: TimeoutCancellationException) {
      throw AssertionError("Dopo ${Attese.TETTO} non e' successo: $what", e)
    }
  }

  private companion object {
    const val PHOTOS = 6
  }
}
