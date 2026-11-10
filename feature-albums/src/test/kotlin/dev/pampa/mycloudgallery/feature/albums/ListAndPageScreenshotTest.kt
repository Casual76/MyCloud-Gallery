package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumUnavailableReason
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListScreen
import dev.pampa.mycloudgallery.feature.albums.list.NoAlbumListHandlers
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageScreen
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageUi
import dev.pampa.mycloudgallery.feature.albums.page.NoAlbumPageHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot dell'elenco e della pagina degli album (Fase 7, P10): ogni stato del §1.5 `AlbumListState` e `AlbumPageState`, chiaro e scuro, telefono (360 dp)
 * e tablet (1280 dp), piu' il testo grande. Sui fake: la libreria sintetica e gli album veri del riferimento (`Scenes`).
 *
 * `gradlew :feature-albums:recordRoborazziDebug --tests "*ScreenshotTest*"` scrive i PNG in `docs/screenshots/7-P10/`. Nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano e dicano le cose giuste.
 */
abstract class ListAndPageShots(private val dark: Boolean, private val device: String) : AlbumsComposeTest() {

  private val scenes = Scenes()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, expect: String? = null, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { AlbumHost { content() } }
    if (expect != null) {
      assertWithMessage("manca «$expect» nella scena $name").that(composeRule.onAllNodesWithText(expect, substring = true).fetchSemanticsNodes()).isNotEmpty()
    }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    if (ScreenshotPolicy.capture(name, device, fontScale)) composeRule.onRoot().captureRoboImage("../docs/screenshots/7-P10/$name-$look-$device$suffix.png")
  }

  private fun list(ui: dev.pampa.mycloudgallery.feature.albums.list.AlbumListUi, back: Boolean = false): @Composable () -> Unit = {
    AlbumListScreen(ui, scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers, onBack = if (back) ({}) else null)
  }

  private fun page(ui: AlbumPageUi, title: String = ""): @Composable () -> Unit = {
    AlbumPageScreen(ui, title, scenes.thumbnails.thumbnails, NoAlbumPageHandlers)
  }

  // --- Elenco --------------------------------------------------------------------------------------------------------------------

  @Test
  fun elencoAlbum() = shoot("elenco", "Compleanno di Anna", content = list(scenes.listUi()))

  @Test
  fun elencoAlbumTestoGrande() = shoot("elenco", "Album", LARGE_TEXT, list(scenes.listUi()))

  @Test
  fun elencoPerNomeConTitoloDellaSezione() =
    shoot("elenco-per-nome", "Natale in famiglia", content = list(scenes.listUi(scenes.listReady(AlbumListSort.NAME)), back = true))

  @Test
  fun elencoVuoto() = shoot("elenco-vuoto", content = list(scenes.listUi(AlbumListState.Empty)))

  @Test
  fun elencoInCaricamento() = shoot("elenco-caricamento", content = list(scenes.listUi(AlbumListState.Loading)))

  @Test
  fun elencoConAvvisoDiChiusuraConCopiaEAlbumLasciati() {
    val notices = listOf(
      SharedAlbumNotice.Closed(scenes.christmas, "Gita al lago", CloseMode.STOP, keptCopy = scenes.lake),
      SharedAlbumNotice.Closed(scenes.grandpa, "Cena di Natale", CloseMode.DELETE, keptCopy = null),
    )
    val left = listOf(scenes.album(scenes.grandpa) as dev.pampa.mycloudgallery.core.albums.contract.Album.Shared)
    shoot("elenco-avvisi", content = list(scenes.listUi(scenes.listReady(notices = notices), left = left)))
  }

  @Test
  fun elencoPersonalizzatoOffreSpostaSuESpostaGiu() =
    shoot("elenco-personalizzato", "Compleanno di Anna", content = list(scenes.listUi(scenes.listReady(AlbumListSort.CUSTOM), reorderable = true)))

  // --- Pagina ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun paginaAlbumManuale() = shoot("pagina-manuale", "Compleanno di Anna", content = page(scenes.pageUi(scenes.birthday)))

  @Test
  fun paginaAlbumManualeTestoGrande() = shoot("pagina-manuale", "Compleanno di Anna", LARGE_TEXT, page(scenes.pageUi(scenes.birthday)))

  @Test
  fun paginaAlbumVuoto() = shoot("pagina-vuota", "Da riempire", content = page(scenes.pageUi(scenes.empty)))

  @Test
  fun paginaAlbumSmartConSuggerite() =
    shoot("pagina-smart", "Oslo 2026", content = page(scenes.pageUi(scenes.oslo, banners = listOf(AlbumBanner.NewItems(3)), suggested = true)))

  @Test
  fun paginaAlbumCondivisoMioConAvanzamentoEPermessi() {
    val running = ShareOpState.Running(ShareOpId("op-1"), scenes.osloShared, SharePlanKind.SHARE, done = 7, total = 18)
    shoot(
      "pagina-condiviso-mio", "Vacanza a Oslo",
      content = page(scenes.pageUi(scenes.osloShared, banners = listOf(AlbumBanner.Sharing(running), AlbumBanner.Incoming(4)))),
    )
  }

  @Test
  fun paginaAlbumCondivisoMioInPausaPerLoSpazio() {
    val paused = ShareOpState.Paused(ShareOpId("op-2"), scenes.osloShared, SharePlanKind.SHARE, ShareWait.NO_SPACE, done = 7, total = 18)
    shoot("pagina-condiviso-pausa", "Vacanza a Oslo", content = page(scenes.pageUi(scenes.osloShared, banners = listOf(AlbumBanner.Sharing(paused)))))
  }

  @Test
  fun paginaAlbumCondivisoMioConCondivisioneParziale() {
    val partly = ShareOpState.PartlyDone(ShareOpId("op-3"), scenes.osloShared, SharePlanKind.SHARE, done = 15, failures = mapOf(ShareFailure.SOURCE_CHANGED to 2), cancelled = 1)
    shoot("pagina-condiviso-parziale", "Vacanza a Oslo", content = page(scenes.pageUi(scenes.osloShared, banners = listOf(AlbumBanner.Sharing(partly)))))
  }

  @Test
  fun paginaAlbumCondivisoMioConCondivisioneFinita() {
    val done = ShareOpState.Done(ShareOpId("op-4"), scenes.osloShared, SharePlanKind.SHARE, done = 18)
    shoot("pagina-condiviso-finita", "Vacanza a Oslo", content = page(scenes.pageUi(scenes.osloShared, banners = listOf(AlbumBanner.Sharing(done)))))
  }

  @Test
  fun paginaAlbumCondivisoDiUnAltro() =
    shoot("pagina-condiviso-altro", "Natale in famiglia", content = page(scenes.pageUi(scenes.christmas, banners = listOf(AlbumBanner.FilesFromPc(3)))))

  @Test
  fun paginaAlbumInSolaVisioneConFotoNonAggiunte() =
    shoot("pagina-sola-visione", "Le foto del nonno", content = page(scenes.pageUi(scenes.grandpa, banners = listOf(AlbumBanner.NotAdded(2), AlbumBanner.FolderChanged))))

  @Test
  fun paginaAlbumEreditato() =
    shoot("pagina-ereditato", "Natale in famiglia", content = page(scenes.pageUi(scenes.christmas, banners = listOf(AlbumBanner.Inherited))))

  @Test
  fun paginaScegliCopertina() = shoot("pagina-scegli-copertina", "Compleanno di Anna", content = page(scenes.pageUi(scenes.birthday, pickingCover = true)))

  @Test
  fun paginaInRicalcolo() {
    val smart = scenes.album(scenes.oslo)
    shoot("pagina-ricalcolo", "Oslo 2026", content = page(AlbumPageUi(page = AlbumPageState.Recomputing(smart, 1_200, 4_000))))
  }

  @Test
  fun paginaChiusaConLaCopia() {
    val notice = SharedAlbumNotice.Closed(scenes.christmas, "Gita al lago", CloseMode.STOP, keptCopy = scenes.lake)
    shoot("pagina-chiusa-copia", "Gita al lago", content = page(AlbumPageUi(page = AlbumPageState.Closed(notice))))
  }

  @Test
  fun paginaEliminataSenzaCopia() {
    val notice = SharedAlbumNotice.Closed(scenes.christmas, "Cena di Natale", CloseMode.DELETE, keptCopy = null)
    shoot("pagina-eliminata", "Cena di Natale", content = page(AlbumPageUi(page = AlbumPageState.Closed(notice))))
  }

  @Test
  fun paginaNonTrovata() =
    shoot("pagina-non-trovata", content = page(AlbumPageUi(page = AlbumPageState.Unavailable(AlbumUnavailableReason.NOT_FOUND)), title = "Album"))

  @Test
  fun paginaLasciata() =
    shoot("pagina-lasciata", content = page(AlbumPageUi(page = AlbumPageState.Unavailable(AlbumUnavailableReason.LEFT)), title = "Natale in famiglia"))

  @Test
  fun paginaSenzaFamiglia() =
    shoot("pagina-senza-famiglia", content = page(AlbumPageUi(page = AlbumPageState.Unavailable(AlbumUnavailableReason.NO_FAMILY)), title = "Album"))

  @Test
  fun paginaInCaricamento() = shoot("pagina-caricamento", content = page(AlbumPageUi(), title = "Compleanno di Anna"))

  companion object {
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_360_QUALIFIERS)
class ListAndPagePhoneScreenshotTest(dark: Boolean) : ListAndPageShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class ListAndPageTabletScreenshotTest(dark: Boolean) : ListAndPageShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
