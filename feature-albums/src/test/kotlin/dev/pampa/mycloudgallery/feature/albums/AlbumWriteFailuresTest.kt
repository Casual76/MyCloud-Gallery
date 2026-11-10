package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.InMemoryAlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListController
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumResult
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageController
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabController
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K3b, SOL-10, OA-11 e SOL-11 (review generale): con la memoria piena il registro dei dati personali lancia `IOException`, e prima arrivava non gestita a una coroutine
 * dell'interfaccia (l'app si chiudeva); due tocchi su "Crea" creavano due album.
 *
 * Per ogni percorso uno store (qui: l'`Albums` che lancia) che lancia `IOException`: nessuna eccezione non gestita (il `backgroundScope` del test la farebbe fallire), un
 * messaggio che dice "Non salvato", lo stato dell'interfaccia di nuovo usabile (niente "occupato" per sempre) e, riprovando a memoria libera, la modifica riesce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumWriteFailuresTest {

  private val media = FakeAlbumLibrary.id(1)

  // --- SOL-11: un invio alla volta ---------------------------------------------------------------------------------------------------

  /** Un `Albums` che conta le creazioni e le tiene sospese finche' [gate] non si apre (o le fa lanciare). */
  private class CreatingAlbums(val inner: Albums, var gate: CompletableDeferred<Unit>? = null, var failure: Throwable? = null) : Albums by inner {
    var calls = 0

    override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome {
      calls++
      gate?.await()
      failure?.let { throw it }
      return inner.create(title, kind, rules)
    }
  }

  private fun TestScope.tabController(albums: Albums, world: AlbumsTestWorld, tab: AlbumsTabScenes) =
    AlbumsTabController(albums, world.sharedAlbums, tab.specials, InMemoryAlbumOpenings(), backgroundScope, worker = UnconfinedTestDispatcher(testScheduler))

  private fun TestScope.listController(albums: Albums, world: AlbumsTestWorld) = AlbumListController(albums, world.sharedAlbums, backgroundScope)

  /** Le due schermate che aprono il foglio "Nuovo album" hanno lo stesso comportamento: lo si prova su entrambe. */
  private class Creator(val create: suspend (String) -> CreateAlbumResult, val creating: () -> Boolean)

  private fun TestScope.creators(albums: CreatingAlbums, world: AlbumsTestWorld, tab: AlbumsTabScenes): Map<String, Creator> {
    val tabC = tabController(albums, world, tab)
    val listC = listController(albums, world)
    return mapOf(
      "scheda Album" to Creator(tabC::createManual) { tabC.creating.value },
      "Tutti gli album" to Creator(listC::createManual) { listC.creating.value },
    )
  }

  @Test
  fun dueTocchiMentreIlPrimoSalvataggioESospesoFannoUnaChiamataSola() = runTest(UnconfinedTestDispatcher()) {
    val tab = AlbumsTabScenes()
    val world = tab.scenes.world
    val albums = CreatingAlbums(world.albums, gate = CompletableDeferred())
    for ((name, creator) in creators(albums, world, tab)) {
      albums.calls = 0
      albums.gate = CompletableDeferred()
      val first = async { creator.create("Natale $name") }
      runCurrent()
      assertWithMessage("$name: sta salvando").that(creator.creating()).isTrue()
      // Il secondo tocco, mentre il primo e' sospeso: non chiede niente.
      assertWithMessage("$name: il secondo invio si rifiuta").that(creator.create("Natale $name")).isEqualTo(CreateAlbumResult.Busy)
      assertWithMessage("$name: una chiamata sola").that(albums.calls).isEqualTo(1)

      albums.gate!!.complete(Unit)
      assertThat(first.await()).isInstanceOf(CreateAlbumResult.Created::class.java)
      assertWithMessage("$name: finito, di nuovo libero").that(creator.creating()).isFalse()
      // E un album solo con quel nome.
      assertThat(world.albums.all.value.albums.count { it.title == "Natale $name" }).isEqualTo(1)
    }
  }

  @Test
  fun sullErroreELaCancellazioneIlTastoTornaLibero() = runTest(UnconfinedTestDispatcher()) {
    val tab = AlbumsTabScenes()
    val world = tab.scenes.world
    val albums = CreatingAlbums(world.albums)
    for ((name, creator) in creators(albums, world, tab)) {
      // Errore: "Non salvato", niente occupato per sempre, e un secondo tentativo parte davvero.
      albums.calls = 0
      albums.failure = IOException("No space left on device")
      assertWithMessage("$name: la memoria piena non chiude niente").that(creator.create("Mare")).isEqualTo(CreateAlbumResult.NotSaved)
      assertThat(creator.creating()).isFalse()
      albums.failure = null
      assertThat(creator.create("Mare")).isInstanceOf(CreateAlbumResult.Created::class.java)
      assertWithMessage("$name: due tentativi, un album solo").that(albums.calls).isEqualTo(2)

      // Cancellazione: la coroutine annullata mentre salva non lascia il tasto spento.
      albums.gate = CompletableDeferred()
      val job = async(start = CoroutineStart.UNDISPATCHED) { creator.create("Monti") }
      runCurrent()
      assertThat(creator.creating()).isTrue()
      job.cancel()
      runCurrent()
      assertWithMessage("$name: dopo la cancellazione e' libero").that(creator.creating()).isFalse()
      albums.gate = null
      assertThat(creator.create("Monti")).isInstanceOf(CreateAlbumResult.Created::class.java)
    }
  }

  @Test
  fun unErroreDiProgrammazioneNonSiInghiotte() = runTest(UnconfinedTestDispatcher()) {
    val tab = AlbumsTabScenes()
    val world = tab.scenes.world
    val albums = CreatingAlbums(world.albums, failure = IllegalStateException("bug"))
    val creator = creators(albums, world, tab).getValue("scheda Album")
    var thrown: Throwable? = null
    try {
      creator.create("X")
    } catch (e: IllegalStateException) {
      thrown = e
    }
    assertThat(thrown).isNotNull()
    // E il tasto non resta spento.
    assertThat(creator.creating()).isFalse()
  }

  // --- SOL-10: le azioni della pagina di un album ------------------------------------------------------------------------------------

  /** Un `Albums` e uno `SmartChoices` che lanciano `IOException` finche' [full] e' vero, come il registro con la memoria piena. */
  private class FullAlbums(val inner: Albums, var full: Boolean = true) : Albums by inner {
    private fun check() {
      if (full) throw IOException("No space left on device")
    }

    override suspend fun rename(id: AlbumId, title: String): EditOutcome = check().let { inner.rename(id, title) }

    override suspend fun delete(id: AlbumId): EditOutcome = check().let { inner.delete(id) }

    override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome = check().let { inner.add(id, media) }

    override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome = check().let { inner.remove(id, media) }

    override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome = check().let { inner.setCover(id, media) }

    override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome = check().let { inner.setSort(id, sort) }

    override suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome = check().let { inner.reorder(id, moved, after) }

    override suspend fun setListSort(sort: AlbumListSort) = check().let { inner.setListSort(sort) }

    override suspend fun moveInList(id: AlbumId, after: AlbumId?): EditOutcome = check().let { inner.moveInList(id, after) }
  }

  private class FullChoices(val inner: SmartChoices, var full: Boolean = true) : SmartChoices by inner {
    private fun check() {
      if (full) throw IOException("No space left on device")
    }

    override suspend fun freeze(id: AlbumId): EditOutcome = check().let { inner.freeze(id) }

    override suspend fun unfreeze(id: AlbumId): EditOutcome = check().let { inner.unfreeze(id) }
  }

  @Test
  fun leAzioniDellaPaginaConLaMemoriaPienaDiconoNonSalvatoENonFannoCadereNiente() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val album = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val albums = FullAlbums(world.albums)
    val choices = FullChoices(world.albums)
    val page = AlbumPageController(
      album, albums, world.sharedAlbums, choices, world.runner, AccountNamesAsIs, AlbumMembers { _, _ -> null }, NoAlbumPending, world.screens, backgroundScope,
    )
    val actions: List<Pair<String, () -> Unit>> = listOf(
      "rinomina" to { page.rename("Nuovo nome") },
      "ordine" to { page.setSort(AlbumSort.OLDEST_FIRST) },
      "copertina" to { page.setCover(media) },
      "elimina" to { page.deletePersonal() },
      "congela" to { page.freeze() },
      "scongela" to { page.unfreeze() },
      "aggiungi" to { page.add(listOf(media)) },
      "porta in cima" to { page.moveToStart(media) },
      "togli" to { page.remove(media) },
    )
    for ((name, action) in actions) {
      page.dismissMessage()
      action()
      assertWithMessage("$name con la memoria piena").that(page.ui.value.message).isEqualTo(AlbumMessage.NotSaved)
    }
    // Niente e' stato eliminato e la pagina non e' "finita": si puo' ancora usare.
    assertThat(page.deleted.value).isFalse()
    assertThat(world.albums.all.value.byId(album)).isNotNull()

    // Liberata la memoria la stessa azione riesce e non resta nessun messaggio.
    albums.full = false
    choices.full = false
    page.dismissMessage()
    page.rename("Nuovo nome")
    assertThat(page.ui.value.message).isNull()
    assertThat(world.albums.all.value.byId(album)?.title).isEqualTo("Nuovo nome")
  }

  @Test
  fun lElencoConLaMemoriaPienaNonFaCadereIlRiordino() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val first = (world.albums.create("Uno", AlbumKind.MANUAL) as CreateOutcome.Created).id
    world.albums.create("Due", AlbumKind.MANUAL)
    val albums = FullAlbums(world.albums)
    val list = AlbumListController(albums, world.sharedAlbums, backgroundScope)
    // Nessuna di queste scritture lancia fino all'ambito del test: l'ordine resta com'era.
    list.setSort(AlbumListSort.NAME)
    list.setSort(AlbumListSort.CUSTOM)
    list.move(first, dev.pampa.mycloudgallery.feature.albums.list.ListMove.DOWN)
    assertThat(world.albums.all.value.sort).isNotEqualTo(AlbumListSort.NAME)
  }
}
