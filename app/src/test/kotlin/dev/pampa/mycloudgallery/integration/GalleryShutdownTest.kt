package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.gallery.GalleryStoppedException
import dev.pampa.mycloudgallery.integration.GalleryRig.Companion.photo
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Revisione di sicurezza della Fase 3, R7: l'azzeramento ferma la galleria viva **prima** di cancellare i file, e dopo
 * niente puo' riscrivere `replica/timeline.idx` ne' il database. Si prova su `GalleryGraph` montato come in produzione
 * (catalogo vero, lettura del telefono vera sopra il MediaStore finto), con l'attesa dell'istantanea lunga abbastanza da
 * cogliere una riscrittura accorpata ancora in coda al momento dell'azzeramento.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GalleryShutdownTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val rigs = mutableListOf<GalleryRig>()

  private fun rig(
    snapshotDelayMs: Long = 150L,
    family: MutableStateFlow<SetupState> = MutableStateFlow(SetupState.Ready(admin = false)),
    databaseName: String? = null,
  ): GalleryRig = GalleryRig(
    app = app,
    replica = File(temp.root, "replica"),
    preferences = File(temp.root, "library.preferences_pb"),
    store = GalleryRig.newStore(),
    access = GalleryRig.accessOf(MediaAccess.FULL),
    databaseName = databaseName,
    snapshotDelayMs = snapshotDelayMs,
    family = family,
  ).also { rigs += it }

  @After
  fun tearDown() {
    rigs.forEach { it.close() }
  }

  @Test
  fun dopoLoShutdownNienteRiscriveLaReplicaNemmenoUnaScritturaInCoda() = runBlocking<Unit> {
    val replica = File(temp.root, "replica")
    val rig = rig(snapshotDelayMs = 1_200L)
    repeat(4) { rig.store.put(photo(it + 1L, minutesAgo = 60L * (it + 1))) }
    rig.startAndSync()
    until("la prima istantanea su file") { rig.gallery.repository.snapshotWrites > 0 && File(replica, "timeline.idx").exists() }

    // Una foto nuova: l'indice si aggiorna a pezzi e la riscrittura accorpata dell'istantanea e' in coda (fra 1,2 s).
    rig.store.put(photo(5, minutesAgo = 1))
    rig.gallery.core().phone.sync.syncNow(SyncReason.CONTENT_CHANGE)
    rig.awaitView { it.size == 5 }

    // L'azzeramento: prima si ferma la galleria, poi si cancella.
    rig.gallery.shutdown()
    assertThat(replica.deleteRecursively()).isTrue()

    // Nemmeno la scrittura che era in coda deve riapparire.
    delay(1_800)
    assertThat(replica.exists()).isFalse()

    // Ne' quelle che arrivano da fuori: secondo piano, memoria scarsa, fine della manutenzione.
    rig.gallery.flush()
    rig.gallery.onBackground()
    rig.gallery.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    rig.gallery.onForeground()
    delay(300)
    assertThat(replica.exists()).isFalse()
  }

  @Test
  fun dopoLoShutdownLaGalleriaNonDaPiuIlCatalogoENonMostraNiente() = runBlocking<Unit> {
    val rig = rig()
    repeat(3) { rig.store.put(photo(it + 1L, minutesAgo = 10L * (it + 1))) }
    val view = rig.startAndSync()
    val someone = view.snapshot.idAt(view.snapshotIndex(0))
    assertThat(rig.gallery.visibleTimeline.view(LibraryFilter.ALL).value.size).isEqualTo(3)

    rig.gallery.shutdown()

    assertThat(rig.gallery.coreIfReady()).isNull()
    assertThrows(GalleryStoppedException::class.java) { runBlocking { rig.gallery.core() } }
    assertThat(rig.gallery.runIfActive { 42 }).isNull()
    // Nascondere o aggiungere un preferito non scrive piu' (e l'errore arriva a chi ha chiesto: R8).
    assertThrows(GalleryStoppedException::class.java) { runBlocking { rig.gallery.actions.setHidden(setOf(someone), true) } }
    assertThrows(GalleryStoppedException::class.java) { runBlocking { rig.gallery.actions.setFavorite(setOf(someone), true) } }
    // Il database e' chiuso, la pipeline delle immagini ferma, la Libreria vuota.
    assertThat(rig.gallery.database.isOpen).isFalse()
    assertThat(rig.gallery.started.value).isFalse()
    until("la Libreria vuota") { rig.gallery.visibleTimeline.view(LibraryFilter.ALL).value.size == 0 }
    // Idempotente.
    rig.gallery.shutdown()
  }

  @Test
  fun unLavoroInCorsoSiFermaEShutdownLoAspetta() = runBlocking<Unit> {
    val rig = rig()
    rig.store.put(photo(1, minutesAgo = 5))
    rig.startAndSync()
    val inside = CompletableDeferred<Unit>()
    var cleanedUp = false

    val work = async(Dispatchers.Default) {
      rig.gallery.runIfActive<Unit> {
        inside.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          // Il lavoro chiude cio' che aveva aperto (tempo che shutdown deve aspettare).
          withContext(NonCancellable) {
            delay(300)
            cleanedUp = true
          }
        }
      }
    }
    withTimeout(GalleryRig.TIMEOUT_MS) { inside.await() }

    rig.gallery.shutdown()

    assertThat(cleanedUp).isTrue()
    assertThat(work.await()).isNull()
  }

  @Test
  fun unaGalleriaMaiPartitaNonSiRitiraEPartePoiConLaFamigliaPronta() = runBlocking<Unit> {
    val family = MutableStateFlow<SetupState>(SetupState.NotConfigured)
    val rig = rig(family = family)
    rig.store.put(photo(1, minutesAgo = 5))

    // L'azzeramento ferma "la galleria", ma qui non c'e' niente di vivo: una configurazione fatta dopo, nello stesso processo, funziona.
    rig.gallery.shutdown()
    family.value = SetupState.Ready(admin = false)
    val view = rig.startAndSync()

    assertThat(view.size).isEqualTo(1)
  }

  @Test
  fun conLaFamigliaNonProntaIlCatalogoGiaInMemoriaNonSiDa() = runBlocking<Unit> {
    val family = MutableStateFlow<SetupState>(SetupState.Ready(admin = false))
    val rig = rig(family = family)
    rig.store.put(photo(1, minutesAgo = 5))
    rig.startAndSync()
    assertThat(rig.gallery.coreIfReady()).isNotNull()

    family.value = SetupState.Blocked(BlockReason.REGISTRY_FORK)
    assertThat(rig.gallery.coreIfReady()).isNull()
    assertThat(rig.gallery.runIfActive { 1 }).isNull()

    // Chi lo chiede aspetta che la famiglia torni pronta (un blocco passeggero non ferma la galleria per sempre).
    val waiting = async(Dispatchers.Default) { rig.gallery.core() }
    delay(250)
    assertThat(waiting.isCompleted).isFalse()
    family.value = SetupState.Ready(admin = false)
    assertThat(withTimeout(GalleryRig.TIMEOUT_MS) { waiting.await() }).isNotNull()
    assertThat(rig.gallery.coreIfReady()).isNotNull()
  }
}
