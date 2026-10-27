package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumAction
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumUnavailableReason
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageController
import dev.pampa.mycloudgallery.feature.albums.share.ClosedAlbum
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pagina di un album (Fase 7, P10; D9, D10, D21, D22, D39, §2.4, §2.5): quali azioni offre secondo la tabella dei permessi, cosa dice quando
 * un'azione non e' andata, e il menu di un elemento. Sopra gli esecutori di riferimento di core-albums, con il livello vero a ogni passo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumPageControllerTest {

  private val other = AccountId("test2")

  private fun TestScope.controller(world: AlbumsTestWorld, id: dev.pampa.mycloudgallery.core.model.AlbumId, members: AlbumMembers = AlbumMembers { _, _ -> null }) =
    AlbumPageController(id, world.albums, world.sharedAlbums, world.albums, world.runner, AccountNamesAsIs, members, NoAlbumPending, world.screens, backgroundScope)

  private fun AlbumPageController.ready(): AlbumPageState.Ready = ui.value.page as AlbumPageState.Ready

  @Test
  fun unAlbumCheNonCeSidiceNonTrovatoEUnoLasciatoSidiceLasciato() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val missing = controller(world, dev.pampa.mycloudgallery.core.model.AlbumId("00000000-0000-4000-8000-0000000000f1"))
    assertThat(missing.ui.value.page).isEqualTo(AlbumPageState.Unavailable(AlbumUnavailableReason.NOT_FOUND))
    val id = world.albumBy(other, "Di famiglia")
    val page = controller(world, id)
    assertThat(page.ui.value.page).isInstanceOf(AlbumPageState.Ready::class.java)
    world.sharedAlbums.leave(id)
    assertThat(page.ui.value.page).isEqualTo(AlbumPageState.Unavailable(AlbumUnavailableReason.LEFT))
  }

  @Test
  fun aGliAltriAggiungonoUnFamiliareAggiungeMaNonRinomina() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(other, "Di famiglia", ShareLevel.ADD)
    val ui = controller(world, id).ui.value
    val ready = ui.page as AlbumPageState.Ready
    assertThat(ready.permissions?.level).isEqualTo(ShareLevel.ADD)
    assertThat(ready.permissions?.mine).isFalse()
    assertThat(ui.actions).contains(AlbumAction.ADD_MEDIA)
    assertThat(ui.actions).contains(AlbumAction.SHOW_PERMISSIONS)
    assertThat(ui.actions).contains(AlbumAction.LEAVE)
    assertThat(ui.actions).containsNoneOf(AlbumAction.RENAME, AlbumAction.STOP_SHARING, AlbumAction.DELETE_ALBUM, AlbumAction.SET_COVER)
    assertThat(ui.creatorName).isEqualTo("test2")
  }

  @Test
  fun aGliAltriGuardanoNonSiOffreDiAggiungere() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(other, "Di famiglia", ShareLevel.VIEW)
    val ui = controller(world, id).ui.value
    assertThat(ui.actions).doesNotContain(AlbumAction.ADD_MEDIA)
    assertThat(ui.actions).contains(AlbumAction.SHOW_PERMISSIONS)
  }

  @Test
  fun ilCreatoreHaLeAzioniDelCreatoreEMaiLeAvanzaDeiFamiliari() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(world.me, "Mio condiviso", ShareLevel.VIEW)
    val ui = controller(world, id).ui.value
    assertThat(ui.actions).containsAtLeast(
      AlbumAction.ADD_MEDIA, AlbumAction.RENAME, AlbumAction.SET_COVER, AlbumAction.SET_SORT, AlbumAction.SHOW_PERMISSIONS, AlbumAction.STOP_SHARING, AlbumAction.DELETE_ALBUM,
    )
    assertThat(ui.actions).doesNotContain(AlbumAction.LEAVE)
    assertThat((ui.page as AlbumPageState.Ready).permissions?.mine).isTrue()
  }

  @Test
  fun unAzioneNonPermessaDaUnMessaggioENonCambiaNiente() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(other, "Di famiglia", ShareLevel.ADD)
    val page = controller(world, id)
    page.rename("Un altro nome")
    assertThat(page.ui.value.message).isEqualTo(AlbumMessage.NotAllowed)
    assertThat(page.ready().content.album.title).isEqualTo("Di famiglia")
    page.dismissMessage()
    assertThat(page.ui.value.message).isNull()
  }

  @Test
  fun unNomeNonValidoSiDiceENonSiScrive() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val page = controller(world, id)
    page.rename("   ")
    assertThat(page.ui.value.message).isEqualTo(AlbumMessage.InvalidTitle(TitleProblem.EMPTY))
    page.rename("Mare")
    assertThat(page.ready().content.album.title).isEqualTo("Mare")
  }

  @Test
  fun unAlbumPersonaleRiceveLeFotoSubitoEUnoCondivisoApreIlFoglioDiConferma() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    world.library.put(world.photo(1), world.photo(2))
    val mine = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val personal = controller(world, mine)
    personal.add(listOf(AlbumsTestWorld.media(1), AlbumsTestWorld.media(2)))
    assertThat(personal.ready().content.items.size).isEqualTo(2)
    assertThat(personal.share.state.value).isNull()

    val shared = world.albumBy(other, "Di famiglia", ShareLevel.ADD)
    val page = controller(world, shared)
    page.add(listOf(AlbumsTestWorld.media(1)))
    // Mai un'aggiunta diretta (D12): il piano da confermare.
    assertThat(page.share.state.value).isInstanceOf(ShareSheetState.Plan::class.java)
    assertThat(page.ready().content.items.size).isEqualTo(0)
  }

  @Test
  fun aGliAltriGuardanoUnaggiuntaNonApreNessunFoglio() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    world.library.put(world.photo(1))
    val shared = world.albumBy(other, "Di famiglia", ShareLevel.VIEW)
    val page = controller(world, shared)
    page.add(listOf(AlbumsTestWorld.media(1)))
    // La tabella non lo permette: nessun foglio di conferma, un messaggio nella pagina, e niente e' partito.
    assertThat(page.share.state.value).isNull()
    assertThat(page.ui.value.message).isEqualTo(AlbumMessage.NotAllowed)
    page.share.confirm()
    assertThat(world.diary.openOps()).isEmpty()
  }

  @Test
  fun ilMenuDiUnElementoDipendeDaChiLHaAggiuntoEDalLivello() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(other, "Di famiglia", ShareLevel.ADD)
    val mine = world.commonPhoto(1, mine = true)
    val theirs = world.commonPhoto(2, mine = false)
    world.addToShared(id, world.me, mine)
    world.addToShared(id, other, theirs)
    val page = controller(world, id) { album, media -> if (media == mine.id) world.me else other }
    page.openItemMenu(mine.id)
    val own = page.ui.value.itemMenu!!
    assertThat(own.addedBy).isEqualTo(world.me)
    // A "Gli altri aggiungono" ognuno toglie le sue e le puo' riportare; la copertina e' del creatore.
    assertThat(own.actions.removeFromAlbum).isTrue()
    assertThat(own.actions.bringBack).isTrue()
    assertThat(own.actions.setAsCover).isFalse()
    page.closeItemMenu()
    page.openItemMenu(theirs.id)
    // L'elemento di un altro non si toglie e non si riporta: nessuna azione, quindi nessun menu.
    assertThat(page.ui.value.itemMenu).isNull()
  }

  @Test
  fun scegliereLaCopertinaEUnaModalitaCheSiChiudeConLaScelta() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    world.library.put(world.photo(1), world.photo(2))
    val id = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val page = controller(world, id)
    page.add(listOf(AlbumsTestWorld.media(1), AlbumsTestWorld.media(2)))
    page.startPickingCover()
    assertThat(page.ui.value.pickingCover).isTrue()
    page.setCover(AlbumsTestWorld.media(1))
    assertThat(page.ui.value.pickingCover).isFalse()
    assertThat(page.ready().content.album.cover.media).isEqualTo(AlbumsTestWorld.media(1))
  }

  @Test
  fun unAlbumPersonaleSiTogliePerSempreEriMandaIndietro() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val page = controller(world, id)
    page.deletePersonal()
    assertThat(page.deleted.value).isTrue()
  }

  @Test
  fun unAlbumChiusoMentreLoGuardiSiDiceConLAvviso() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(world.me, "Mio condiviso", ShareLevel.ADD)
    val page = controller(world, id)
    val closed = (page.close.apply { open(id, CloseMode.DELETE) })
    assertThat(closed.ui.value).isNotNull()
    // Nessuna scelta di partenza (Q5): senza, il foglio non chiude.
    closed.confirm()
    assertThat(page.ready().content.album.title).isEqualTo("Mio condiviso")
    closed.chooseOthers(OthersAfterClose.REMOVE)
    closed.confirm()
    assertThat(closed.closed.value).isEqualTo(ClosedAlbum(id, CloseMode.DELETE))
    assertThat(page.ui.value.page).isNotInstanceOf(AlbumPageState.Ready::class.java)
  }

  @Test
  fun leStrisceNonOffronoUnaConfermaDaSole() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(world.me, "Mio condiviso", ShareLevel.ADD)
    val page = controller(world, id)
    world.clocks.advance(ShareConfirmationRules.MAX_AGE_MS)
    page.share.confirm()
    assertThat(world.diary.openOps()).isEmpty()
    assertThat(page.ui.value.page).isInstanceOf(AlbumPageState.Ready::class.java)
  }

  @Test
  fun ilMenuDiUnElementoNonSiApreMaiPerUnIdCheLaVistaNonHa_D22() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    world.library.put(world.photo(1), world.photo(2, hidden = true))
    val id = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val page = controller(world, id)
    // Anche un nascosto puo' essere un elemento dell'album (D22), ma non si mostra e non si raggiunge per id.
    world.albums.add(id, listOf(AlbumsTestWorld.media(1), AlbumsTestWorld.media(2)))
    page.openItemMenu(AlbumsTestWorld.media(2))
    assertThat(page.ui.value.itemMenu).isNull()
    page.openItemMenu(AlbumsTestWorld.media(99))
    assertThat(page.ui.value.itemMenu).isNull()
    page.openItemMenu(AlbumsTestWorld.media(1))
    assertThat(page.ui.value.itemMenu?.media).isEqualTo(AlbumsTestWorld.media(1))
  }
}
