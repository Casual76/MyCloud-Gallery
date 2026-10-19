package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.integration.GalleryRig.Companion.photo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * La verifica funzionale delle fasi 1-5A (docs/review/funzionale-fasi-1-5a.md), Galleria del telefono: le voci GA che si provano
 * sul grafo vero della galleria (`GalleryGraph` montato come in produzione sopra il MediaStore finto).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VerificaFunzionaleGalleriaTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val rigs = mutableListOf<GalleryRig>()

  private fun rig(): GalleryRig = GalleryRig(
    app = app,
    replica = File(temp.root, "replica"),
    preferences = File(temp.root, "library.preferences_pb"),
    store = GalleryRig.newStore(),
    access = GalleryRig.accessOf(MediaAccess.FULL),
  ).also { rigs += it }

  @After
  fun tearDown() {
    rigs.forEach { it.close() }
  }

  private fun TimelineView.isFavorite(id: MediaId): Boolean {
    val index = snapshot.indexOf(id)
    return index >= 0 && CellFlags.has(snapshot.flagsAt(index), CellFlags.FAVORITE)
  }

  /**
   * GA-1: dal viewer dei Nascosti "Mostra nella Libreria", "Preferito" ed "Elimina" scrivevano il catalogo, ma la vista dei
   * Nascosti era una fotografia fatta una volta sola: a schermo non cambiava niente (la foto tornava in Libreria, il viewer
   * restava sulla foto e la griglia la mostrava ancora). Ora la vista segue il catalogo finche' qualcuno la guarda.
   */
  @Test
  fun ga1_laVistaDeiNascostiSegueLeAzioniDelViewer() = runBlocking<Unit> {
    val rig = rig()
    repeat(4) { rig.store.put(photo(it + 1L, minutesAgo = 10L * (it + 1))) }
    val view = rig.startAndSync()
    val first = view.snapshot.idAt(view.snapshotIndex(0))
    val second = view.snapshot.idAt(view.snapshotIndex(1))
    rig.gallery.actions.setHidden(setOf(first, second), true)
    val hidden = rig.gallery.hiddenLibrary.read()
    assertThat(hidden.view.value.size).isEqualTo(2)

    // La griglia dei Nascosti e il viewer guardano la vista.
    val watching = launch(Dispatchers.Default) { hidden.view.collect { } }
    try {
      // "Preferito" dal viewer: la stella cambia anche nella vista (non solo nel segno provvisorio del viewer).
      rig.gallery.actions.setFavorite(setOf(second), true)
      withTimeout(GalleryRig.TIMEOUT_MS) { hidden.view.first { it.isFavorite(second) } }

      // "Mostra nella Libreria": l'elemento esce dai Nascosti e il viewer passa al vicino.
      rig.gallery.actions.setHidden(setOf(first), false)
      val after = withTimeout(GalleryRig.TIMEOUT_MS) { hidden.view.first { it.viewIndexOf(first) < 0 } }
      assertThat(after.size).isEqualTo(1)
      assertThat(hidden.refOrNull(first)).isNull()
      assertThat(hidden.refOrNull(second)).isNotNull()
      // E in Libreria e' tornato.
      rig.awaitView { it.viewIndexOf(first) >= 0 }
    } finally {
      watching.cancel()
    }
  }

  /**
   * GA-1, la sicurezza: la vista dei Nascosti non resta in ascolto del catalogo quando nessuno la guarda (la pagina e' uscita), ed
   * entrando di nuovo trova lo stato di adesso. I nascosti non si leggono fuori da una schermata aperta.
   */
  @Test
  fun ga1_senzaNessunoCheGuardaLaVistaNonSiRilegge() = runBlocking<Unit> {
    val rig = rig()
    repeat(3) { rig.store.put(photo(it + 1L, minutesAgo = 10L * (it + 1))) }
    val view = rig.startAndSync()
    val ids = (0 until 3).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    rig.gallery.actions.setHidden(setOf(ids[0]), true)
    val hidden = rig.gallery.hiddenLibrary.read()
    val watching = launch(Dispatchers.Default) { hidden.view.collect { } }
    withTimeout(GalleryRig.TIMEOUT_MS) { hidden.view.first { it.size == 1 } }
    watching.cancel()
    delay(200)

    // Nessuno guarda: un nascosto in piu' non viene letto.
    rig.gallery.actions.setHidden(setOf(ids[1]), true)
    delay(500)
    assertThat(hidden.view.value.size).isEqualTo(1)

    // Si torna a guardare: la vista riparte da cio' che c'e' adesso.
    val again = withTimeout(GalleryRig.TIMEOUT_MS) { hidden.view.first { it.size == 2 } }
    assertThat(again.size).isEqualTo(2)
  }
}
