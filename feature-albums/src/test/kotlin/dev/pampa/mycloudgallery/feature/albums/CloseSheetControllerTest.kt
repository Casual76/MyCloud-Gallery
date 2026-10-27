package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.FixedAlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheetController
import dev.pampa.mycloudgallery.feature.albums.share.ClosedAlbum
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetController
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * I fogli di chiusura (Fase 7, P10; D31 con la risposta Q5 del 22/10, E-SH7): **lo decide chi chiude, ogni volta, senza una scelta di partenza**; la conferma
 * resta spenta finche' non si sceglie; la scelta viaggia nella definizione firmata; "Riporta nelle mie cartelle" ha il suo foglio di conferma col conteggio e
 * la chiusura parte solo dopo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloseSheetControllerTest {

  private val other = AccountId("test2")

  private fun worldWithMyAlbum(vararg mine: Int): Pair<AlbumsTestWorld, dev.pampa.mycloudgallery.core.model.AlbumId> {
    val world = AlbumsTestWorld()
    val id = world.albumBy(world.me, "Prova Oslo", ShareLevel.ADD)
    world.addToShared(id, world.me, *mine.map { world.commonPhoto(it, mine = true) }.toTypedArray())
    world.addToShared(id, other, world.commonPhoto(90, mine = false))
    return world to id
  }

  @Test
  fun alleAperturaNessunaDelleDueRisposteESceltaEIlBottoneEspento() = runTest(UnconfinedTestDispatcher()) {
    val (world, id) = worldWithMyAlbum(1, 2, 3)
    val share = ShareSheetController(ScriptedRunner({ SharePlanOutcome.NotAllowed(dev.pampa.mycloudgallery.core.albums.contract.AlbumOp.ADD_MEDIA) }), world.screens, backgroundScope)
    val close = CloseSheetController(world.sharedAlbums, share, backgroundScope)
    close.open(id, CloseMode.STOP)
    val ui = close.ui.value!!
    assertThat(ui.sheet.forOthers).isNull()
    assertThat(ui.sheet.canConfirm).isFalse()
    assertThat(ui.sheet.bringBack).isFalse()
    assertThat(ui.sheet.bringBackCount).isEqualTo(3)
    // Il bottone spento: un tocco su "Smetti" non chiude niente.
    close.confirm()
    assertThat(world.sharedAlbums.visible.value.single().state).isEqualTo(SharedState.ACTIVE)
    assertThat(close.closed.value).isNull()
  }

  @Test
  fun lascelta_SparisceOCopiaViaggiaNellaChiusuraEIlCreatoreResta() = runTest(UnconfinedTestDispatcher()) {
    for (choice in OthersAfterClose.entries) {
      val (world, id) = worldWithMyAlbum(1)
      val close = CloseSheetController(world.sharedAlbums, ShareSheetController(ScriptedRunner({ error("nessun piano") }), world.screens, backgroundScope), backgroundScope)
      close.open(id, CloseMode.STOP)
      close.chooseOthers(choice)
      assertThat(close.ui.value!!.sheet.canConfirm).isTrue()
      close.confirm()
      assertThat(close.closed.value).isEqualTo(ClosedAlbum(id, CloseMode.STOP))
      assertThat(close.ui.value).isNull()
      assertThat(world.common.album(id)!!.afterClose).isEqualTo(choice)
      assertThat(world.common.album(id)!!.state).isEqualTo(SharedState.CLOSED)
    }
  }

  @Test
  fun eliminareLAlbumUsaLoStatoEliminatoEUnaCopiaSiAvvisaDelRiporto() = runTest(UnconfinedTestDispatcher()) {
    val (world, id) = worldWithMyAlbum(1, 2)
    val close = CloseSheetController(world.sharedAlbums, ShareSheetController(ScriptedRunner({ error("nessun piano") }), world.screens, backgroundScope), backgroundScope)
    close.open(id, CloseMode.DELETE)
    close.setBringBack(true)
    close.chooseOthers(OthersAfterClose.KEEP_COPY)
    // Con le copie per gli altri, riportare i miei media li toglie anche dalle loro copie: il foglio lo dice.
    assertThat(close.ui.value!!.sheet.warnCopiesLoseBroughtBack).isTrue()
    close.chooseOthers(OthersAfterClose.REMOVE)
    assertThat(close.ui.value!!.sheet.warnCopiesLoseBroughtBack).isFalse()
  }

  @Test
  fun conRiportaPrimaCeIlFoglioDelloSpostamentoPoiLaChiusura() = runTest(UnconfinedTestDispatcher()) {
    val (world, id) = worldWithMyAlbum(1, 2, 3)
    val runner = ScriptedRunner({ request -> SharePlanOutcome.Ready(ShareFixtures.bringBackPlan((request as ShareRequest.BringBack).media.size)) })
    val clock = FixedAlbumScreensClock(LocalDate.of(2026, 10, 21), 10_000_000L)
    val share = ShareSheetController(runner, clock, backgroundScope)
    val close = CloseSheetController(world.sharedAlbums, share, backgroundScope)
    close.open(id, CloseMode.STOP)
    close.setBringBack(true)
    close.chooseOthers(OthersAfterClose.REMOVE)
    close.confirm()
    // Il foglio di "Riporta" col conteggio: l'album non e' ancora chiuso.
    val plan = (share.state.value as ShareSheetState.Plan).plan
    assertThat(plan.items).hasSize(3)
    assertThat(close.closed.value).isNull()
    assertThat(world.common.album(id)!!.state).isEqualTo(SharedState.ACTIVE)
    clock.nowMs += ShareConfirmationRules.MIN_TAP_GAP_MS
    share.confirm()
    assertThat(runner.starts).hasSize(1)
    assertThat(close.closed.value).isEqualTo(ClosedAlbum(id, CloseMode.STOP))
    assertThat(world.common.album(id)!!.state).isEqualTo(SharedState.CLOSED)
  }

  @Test
  fun seIlFoglioDiRiportaSiAnnullaLAlbumNonSiChiudeEIlFoglioDiChiusuraResta() = runTest(UnconfinedTestDispatcher()) {
    val (world, id) = worldWithMyAlbum(1, 2)
    val runner = ScriptedRunner({ request -> SharePlanOutcome.Ready(ShareFixtures.bringBackPlan((request as ShareRequest.BringBack).media.size)) })
    val share = ShareSheetController(runner, FixedAlbumScreensClock(LocalDate.of(2026, 10, 21), 10_000_000L), backgroundScope)
    val close = CloseSheetController(world.sharedAlbums, share, backgroundScope)
    close.open(id, CloseMode.STOP)
    close.setBringBack(true)
    close.chooseOthers(OthersAfterClose.KEEP_COPY)
    close.confirm()
    share.dismiss()
    assertThat(runner.starts).isEmpty()
    assertThat(close.closed.value).isNull()
    assertThat(world.common.album(id)!!.state).isEqualTo(SharedState.ACTIVE)
    // Il foglio di chiusura ha ancora le sue scelte.
    assertThat(close.ui.value!!.sheet.forOthers).isEqualTo(OthersAfterClose.KEEP_COPY)
    assertThat(close.ui.value!!.sheet.bringBack).isTrue()
  }

  @Test
  fun unFamiliareNonPuoChiudereEIlFoglioNonSiApre() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(other, "Di famiglia", ShareLevel.ALL)
    val close = CloseSheetController(world.sharedAlbums, ShareSheetController(ScriptedRunner({ error("nessun piano") }), world.screens, backgroundScope), backgroundScope)
    close.open(id, CloseMode.STOP)
    assertThat(close.ui.value).isNull()
    close.confirm()
    assertThat(close.closed.value).isNull()
  }

  @Test
  fun senzaMediaMieiNonCeLOpzioneRiporta() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val id = world.albumBy(world.me, "Vuoto", ShareLevel.ADD)
    val close = CloseSheetController(world.sharedAlbums, ShareSheetController(ScriptedRunner({ error("nessun piano") }), world.screens, backgroundScope), backgroundScope)
    close.open(id, CloseMode.STOP)
    assertThat(close.ui.value!!.sheet.bringBackCount).isEqualTo(0)
    close.setBringBack(true)
    assertThat(close.ui.value!!.sheet.bringBack).isFalse()
  }
}
