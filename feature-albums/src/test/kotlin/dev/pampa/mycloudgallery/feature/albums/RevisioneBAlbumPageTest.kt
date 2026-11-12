package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumAction
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageController
import dev.pampa.mycloudgallery.feature.albums.page.AlbumStrips
import dev.pampa.mycloudgallery.feature.albums.page.StripAction
import dev.pampa.mycloudgallery.feature.albums.share.ClosedAlbum
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Revisione B della Fase 7 (`docs/review/funzionale-7.md`, `docs/review/7-fase7.md`) sulla pagina di un album:
 * - R15: "Usa la nuova cartella" e "Ripristina" che lanciano diventano un messaggio, non la caduta dell'app;
 * - SH-3: "Aggiungi i file della cartella" c'e' solo con dei file aggiunti dal PC;
 * - SH-2: la striscia dei problemi dice il motivo e "Riprova" apre il foglio con gli elementi non andati; un'attesa prima di partire dice i 10 minuti;
 * - SC-1: chiusa con "Smetti di condividere", la pagina sa dove portare il creatore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RevisioneBAlbumPageTest {

  private val other = AccountId("test2")
  private val resources = ApplicationProvider.getApplicationContext<Application>().resources

  private fun TestScope.controller(world: AlbumsTestWorld, id: AlbumId, shared: SharedAlbums = world.sharedAlbums, runner: ShareRunner = world.runner) =
    AlbumPageController(id, world.albums, shared, world.albums, runner, AccountNamesAsIs, AlbumMembers { _, _ -> null }, NoAlbumPending, world.screens, backgroundScope)

  // --- R15 ----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun r15UnaCartellaNonAccettataDiventaUnMessaggioNonUnaCaduta() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(world.me, "Mio condiviso", ShareLevel.ADD)
    // Come il pubblicatore vero con una cartella oltre 1.024 byte: `require` lancia IllegalArgumentException.
    val throwing = object : SharedAlbums by world.sharedAlbums {
      override suspend fun useNewFolder(id: AlbumId): EditOutcome = throw IllegalArgumentException("Modifica non ammessa dal formato")

      override suspend fun restoreRevoked(id: AlbumId): EditOutcome = throw IllegalArgumentException("Modifica non ammessa dal formato")
    }
    val page = controller(world, id, shared = throwing)
    page.useNewFolder()
    assertThat(page.ui.value.message).isEqualTo(AlbumMessage.NotDone)
    page.dismissMessage()
    page.restoreRevoked()
    assertThat(page.ui.value.message).isEqualTo(AlbumMessage.NotDone)
    assertThat(page.ui.value.page).isInstanceOf(AlbumPageState.Ready::class.java)
  }

  // --- SH-3 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh3LaVoceDeiFileDellaCartellaCeSoloConFileDelPc() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(other, "Di famiglia", ShareLevel.ADD)
    val notices = MutableStateFlow<List<SharedAlbumNotice>>(emptyList())
    val shared = object : SharedAlbums by world.sharedAlbums {
      override val notices: StateFlow<List<SharedAlbumNotice>> get() = notices
    }
    val page = controller(world, id, shared = shared)
    assertThat(page.ui.value.actions).contains(AlbumAction.ADD_MEDIA)
    assertThat(page.ui.value.actions).doesNotContain(AlbumAction.ADOPT_FOLDER_FILES)
    notices.value = listOf(SharedAlbumNotice.FilesAddedFromPc(id, "Di famiglia", 2))
    assertThat(page.ui.value.actions).contains(AlbumAction.ADOPT_FOLDER_FILES)
    assertThat((page.ui.value.page as AlbumPageState.Ready).banners).contains(AlbumBanner.FilesFromPc(2))
  }

  // --- SH-2 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sh2LaStrisciaDiceIlMotivoERiprovaApreIlFoglio() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(other, "Di famiglia", ShareLevel.ADD)
    world.library.put(world.photo(1))
    val op = ShareOpId("op-riprova")
    val partly = ShareOpState.PartlyDone(op, id, SharePlanKind.SHARE, done = 4, failures = mapOf(ShareFailure.SOURCE_CHANGED to 1), cancelled = 0)
    var dismissed: ShareOpId? = null
    var retry: ShareRequest? = ShareRequest.AddToShared(id, listOf(AlbumsTestWorld.media(1)))
    val runner = object : ShareRunner by world.runner {
      override val operations: StateFlow<List<ShareOpState>> = MutableStateFlow(listOf(partly))

      override suspend fun retryRequest(op: ShareOpId): ShareRequest? = retry

      override suspend fun dismiss(op: ShareOpId) {
        dismissed = op
      }
    }
    val page = controller(world, id, runner = runner)
    val ready = page.ui.value.page as AlbumPageState.Ready
    val strip = AlbumStrips.of(resources, ready, page.ui.value.suggested, null).single { it.id == "sharing" }
    assertThat(strip.action).isEqualTo(StripAction.RETRY_SHARE)
    assertThat(strip.detail).contains(resources.getString(R.string.album_problem_source_changed))
    // "Riprova": la richiesta diventa un piano e il foglio di conferma di sempre (niente parte senza il tocco).
    page.retryShare()
    val sheet = page.share.state.value as ShareSheetState.Plan
    assertThat(sheet.plan.items.map { it.media }).containsExactly(AlbumsTestWorld.media(1))
    assertThat(world.transfers.started).isEmpty()
    // Niente da riprovare: la striscia si chiude.
    page.share.dismiss()
    retry = null
    page.retryShare()
    assertThat(dismissed).isEqualTo(op)
  }

  private val scenes by lazy { Scenes() }

  @Test
  fun sh2UnAttesaPrimaDiPartireDiceIDieciMinuti() {
    val album = scenes.osloShared
    val started = ShareOpState.Paused(ShareOpId("op-a"), album, SharePlanKind.SHARE, ShareWait.NO_SPACE, 0, 3)
    val waiting = started.copy(notStarted = 3)
    val catalog = started.copy(reason = ShareWait.CATALOG)
    fun detail(state: ShareOpState) = AlbumStrips.of(resources, readyWith(state), dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState.None, null).single { it.id == "sharing" }.detail
    assertThat(detail(waiting)).contains("10 minuti")
    assertThat(detail(started)).doesNotContain("10 minuti")
    assertThat(detail(catalog)).isNotEqualTo(detail(started))
  }

  /**
   * CA-1 della revisione funzionale della Fase 10: la condivisione in pausa per le scritture ferme, quando a fermarle e' il manifest firmato, dice "questa versione va
   * aggiornata" invece di "Riprendo da sola" (che non diventerebbe mai vero); lo stesso il messaggio "non si puo' scrivere". Con l'emergenza dell'utente resta la frase
   * di sempre.
   */
  @Test
  fun ca1_conIlManifestLeScrittureFermeDiconoDiAggiornareENonDiAspettare() {
    val album = scenes.osloShared
    val paused = ShareOpState.Paused(ShareOpId("op-b"), album, SharePlanKind.SHARE, ShareWait.WRITES_BLOCKED, 0, 3)
    fun detail(held: Boolean) = AlbumStrips.of(
      resources, readyWith(paused), dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState.None, null, heldByRelease = held,
    ).single { it.id == "sharing" }.detail
    assertThat(detail(held = false)).contains("Riprendo da sola")
    // Pacchetto U (12/11): l'app si aggiorna da sola, le frasi non mandano piu' al Pampa Store: dicono di aggiornarla.
    assertThat(detail(held = true)).contains("aggiorn")
    assertThat(detail(held = true)).doesNotContain("Riprendo")
    val blocked = resources.getString(dev.pampa.mycloudgallery.feature.albums.ui.AlbumText.blocked(dev.pampa.mycloudgallery.feature.albums.contract.WhyBlocked.WRITES_PAUSED, true))
    assertThat(blocked).contains("aggiorn")
    assertThat(blocked).doesNotContain("Riprovo")
  }

  private fun readyWith(state: ShareOpState): AlbumPageState.Ready = scenes.pageUi(scenes.osloShared, banners = listOf(AlbumBanner.Sharing(state))).page as AlbumPageState.Ready

  // --- SC-1 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun sc1DopoSmettiDiCondividereLaPaginaSaDoveAndare() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(world.me, "Mio condiviso", ShareLevel.ADD)
    val personal = AlbumId("00000000-0000-4000-8000-0000000000c1")
    val shared = object : SharedAlbums by world.sharedAlbums {
      override suspend fun keptAfterClose(id: AlbumId): AlbumId = personal
    }
    val page = controller(world, id, shared = shared)
    page.close.open(id, CloseMode.STOP)
    page.close.chooseOthers(OthersAfterClose.REMOVE)
    page.close.confirm()
    assertThat(page.close.closed.value).isEqualTo(ClosedAlbum(id, CloseMode.STOP, personal))
    // Mentre la pagina porta via l'utente non dice "non trovato".
    assertThat(page.ui.value.page).isNotEqualTo(AlbumPageState.Unavailable(dev.pampa.mycloudgallery.feature.albums.contract.AlbumUnavailableReason.NOT_FOUND))
  }
}
