package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.search.contract.Today
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gli album smart sul **grafo vero dell'app** (Fase 7, revisione di fine fase, AS-1, AS-2, AS-5): `AlbumsGraph` montato sul catalogo vero (foto sul NAS finto),
 * nessuno chiama `refresh`. Prima il motore scriveva le righe su Room e nessuno rinfrescava l'elenco: lo smart restava a zero finche' non si usciva e si
 * rientrava, e la suite di contratto non lo vedeva perche' chiamava `onCatalogChanged()` a mano dopo ogni giro (lo fanno i lavori di `:app`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SmartAlbumsAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val rigs = mutableListOf<BackupRig>()
  private val graphs = mutableListOf<AlbumsGraph>()
  private val today = Today { LocalDate.of(2026, 10, 26) }

  @After
  fun tearDown() {
    graphs.forEach { runBlocking { it.stop() } }
    rigs.forEach { rig ->
      runBlocking { rig.gallery.shutdown() }
      rig.close()
    }
  }

  private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

  @Test
  fun unoSmartCreatoSulGrafoVeroSiRiempieEElencoEPaginaLoVedonoSenzaRientrare() = runBlocking<Unit> {
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend))
    phone.graph.start()
    phone.graph.foundFamily()
    // Tre foto proprie sul NAS, di due giorni fa (byte diversi: il codificatore JPEG di Robolectric non dipende dai pixel).
    val taken = time.now() - 2 * DAY
    (1..3).forEach { n ->
      backend.put(TestNas.ADMIN_USER, "Foto/Oslo/IMG_000$n.jpg", BackupRig.jpeg(tone = 20 + n * 50) + ByteArray(100 * n) { (n * 31 + it).toByte() }, lastWriteMs = taken)
    }
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    rig.open()
    phone.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(phone.context, phone.graph, rig.gallery, search = null, today = today).also { graphs += it }
    val live = checkNotNull(albums.ensureLive()) { "album non montati" }
    withTimeout(Attese.TETTO) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == 3 } }

    // Gli album non sono piu' «in lettura»: sono montati e letti.
    withTimeout(Attese.TETTO) { live.albums.status.first { it !is AlbumsStatus.Loading } }

    val period = SmartPeriod.Ranges(listOf(LocalDayRange(dayOf(taken).minusDays(3), dayOf(taken).plusDays(3))))
    val created = live.albums.create("Oslo", AlbumKind.SMART, SmartRules(period, null, null)) as CreateOutcome.Created

    // Il grafo calcola e rinfresca da solo: elenco e pagina vedono le tre foto, e lo stato torna pronto.
    val list = withTimeout(Attese.TETTO) { live.albums.all.first { it.byId(created.id)?.count == 3 } }
    assertThat(list.byId(created.id)!!.count).isEqualTo(3)
    withTimeout(Attese.TETTO) { live.albums.status.first { it == AlbumsStatus.Ready } }
    val page = withTimeout(Attese.TETTO) { live.albums.content(created.id).first { it?.items?.size == 3 } }
    assertThat(page!!.items.size).isEqualTo(3)
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
