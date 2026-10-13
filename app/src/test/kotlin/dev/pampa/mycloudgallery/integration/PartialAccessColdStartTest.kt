package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.integration.GalleryRig.Companion.PRIMARY
import dev.pampa.mycloudgallery.integration.GalleryRig.Companion.photo
import java.io.File
import kotlinx.coroutines.delay
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
 * Revisione di sicurezza della Fase 3, R5, **come in produzione**: l'utente ha scelto "Seleziona foto"; cambiare il permesso
 * uccide il processo, e all'apertura a freddo l'istantanea `MCGT1` e il catalogo ci sono gia' (con tutte le foto lette
 * quando l'accesso era completo). Finche' la prima lettura non risponde con la selezione, la Libreria non mostra niente
 * di quel catalogo e dice che sta leggendo; poi mostra solo la selezione.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PartialAccessColdStartTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val rigs = mutableListOf<GalleryRig>()

  /**
   * Un "processo" della galleria. Le preferenze stanno in un file a parte per ogni processo: due DataStore vivi sullo stesso
   * file sono un errore di DataStore (il processo di prima e' morto, qui il suo ambito si annulla ma non e' immediato).
   */
  private fun rig(store: dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore, media: MediaAccess): GalleryRig = GalleryRig(
    app = app,
    replica = File(temp.root, "replica"),
    preferences = File(temp.root, "library-${rigs.size}.preferences_pb"),
    store = store,
    access = GalleryRig.accessOf(media),
    databaseName = "avvio-a-freddo.db",
    snapshotDelayMs = 100L,
  ).also { rigs += it }

  @After
  fun tearDown() {
    rigs.forEach { it.close() }
  }

  @Test
  fun alloAvvioAFreddoConAccessoParzialeLaLibreriaNonMostraNientePrimaDellaPrimaLettura() = runBlocking<Unit> {
    val store = GalleryRig.newStore()
    repeat(4) { store.put(photo(it + 1L, minutesAgo = 10L * (it + 1))) }

    // Il processo "di prima": accesso completo, il catalogo e l'istantanea si riempiono, poi il processo muore.
    val before = rig(store, MediaAccess.FULL)
    before.startAndSync()
    withTimeout(GalleryRig.TIMEOUT_MS) { while (before.gallery.repository.snapshotWrites == 0) delay(20) }
    before.gallery.flush()
    before.gallery.shutdown()
    before.close()

    // Il processo "di adesso": solo due foto sono accessibili, ma nessuno lo sa ancora.
    val after = rig(store, MediaAccess.PARTIAL)
    store.setSelection(PRIMARY, setOf(1L, 3L))
    val gallery = after.gallery
    gallery.startWhenReady()
    withTimeout(GalleryRig.TIMEOUT_MS) { gallery.started.first { it } }

    // Il repository ha gia' letto l'istantanea (4 elementi) ma la Libreria non ne mostra nessuno e dice che legge.
    assertThat(gallery.repository.view(LibraryFilter.ALL).value.size).isEqualTo(4)
    assertThat(gallery.partialSelection.value).isNull()
    assertThat(gallery.visibleTimeline.view(LibraryFilter.ALL).value.size).isEqualTo(0)
    assertThat(gallery.libraryPhoneStatus.value.access).isEqualTo(MediaAccess.PARTIAL)
    assertThat(gallery.libraryPhoneStatus.value.selectionPending).isTrue()

    // La prima lettura risponde: da qui in poi solo la selezione, e il caricamento e' finito.
    gallery.core().phone.sync.syncNow(SyncReason.PERMISSION_CHANGED)
    val visible = withTimeout(GalleryRig.TIMEOUT_MS) { gallery.visibleTimeline.view(LibraryFilter.ALL).first { it.size == 2 } }
    assertThat(visible.size).isEqualTo(2)
    assertThat(gallery.libraryPhoneStatus.value.selectionPending).isFalse()
    // Il catalogo non ha segnato assenze per le altre due.
    assertThat(gallery.repository.view(LibraryFilter.ALL).value.size).isEqualTo(4)
  }

  @Test
  fun conAccessoCompletoNonCambiaNiente() = runBlocking<Unit> {
    val store = GalleryRig.newStore()
    repeat(3) { store.put(photo(it + 1L, minutesAgo = 10L * (it + 1))) }
    val rig = rig(store, MediaAccess.FULL)

    assertThat(rig.gallery.libraryPhoneStatus.value.selectionPending).isFalse()
    val view = rig.startAndSync()

    assertThat(view.size).isEqualTo(3)
    assertThat(rig.gallery.visibleTimeline.view(LibraryFilter.ALL).value.size).isEqualTo(3)
  }
}
