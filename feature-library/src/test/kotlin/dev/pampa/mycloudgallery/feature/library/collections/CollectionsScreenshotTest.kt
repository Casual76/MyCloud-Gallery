package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenDenial
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenScreen
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenUiState
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedController
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedScreen
import dev.pampa.mycloudgallery.feature.library.trash.TrashEntry
import dev.pampa.mycloudgallery.feature.library.trash.TrashUiState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P10 (Raccolte, griglie filtrate, Nascosti, Eliminati di recente): chiaro e scuro, telefono (412 dp) e
 * tablet (1280 dp), piu' il testo grande. Sui fake: la libreria sintetica, i nascosti del mondo finto, un cestino di sistema
 * di prova.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*CollectionsScreenshot*"` scrive i PNG in
 * `docs/screenshots/3-P10/`. Nei normali `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
abstract class CollectionsScreenshotBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val environment = fakeGridEnvironment(loader = fakeThumbnailLoader())
  private val now = 1_800_000_000_000L

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun trashEntries(count: Int, hiddenAt: Set<Int> = emptySet()): List<TrashEntry> =
    fakeTrashedItems(now, count).mapIndexed { index, item ->
      TrashEntry(
        RecentlyDeletedController.trashEntryId(item.ref), item, RecentlyDeletedController.daysLeft(item.expiresAtMs, now),
        hidden = index in hiddenAt,
      )
    }

  private fun shoot(name: String, fontScale: Float? = null, afterShow: () -> Unit = {}, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    afterShow()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  private fun grid(
    kind: CollectionKind,
    items: ViewerItems = world.source.items(kind.filter!!),
    back: Boolean = true,
  ): @Composable () -> Unit = {
    CollectionGridScreen(kind, items, environment, { _, _ -> }, {}, {}, onBack = if (back) ({}) else null)
  }

  private fun hiddenContent(): HiddenUiState = HiddenUiState.Content(runBlocking { world.hiddenLibrary.read() })

  private fun hidden(state: HiddenUiState, back: Boolean = true): @Composable () -> Unit = {
    HiddenScreen(
      state, environment, onUnlock = {}, onRetry = {}, onOpenSecuritySettings = {}, onShow = {}, onOpenViewer = {},
      onBack = if (back) ({}) else null,
    )
  }

  private fun trash(state: TrashUiState, selection: Set<MediaId> = emptySet(), back: Boolean = true): @Composable () -> Unit = {
    RecentlyDeletedScreen(state, selection, environment.loader, {}, {}, {}, {}, {}, onBack = if (back) ({}) else null)
  }

  private fun list(initial: CollectionKind? = null): @Composable () -> Unit = {
    CollectionsScreen(
      state = layoutOf(world),
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = {},
      onOpenSettings = {},
      statusHeader = {},
      initialSelection = initial,
      detail = { kind ->
        when (kind) {
          // Il pannello di dettaglio non ha il tasto indietro: la raccolta non e' una pagina che si chiude.
          CollectionKind.HIDDEN -> hidden(HiddenUiState.Locked, back = false)()
          CollectionKind.RECENTLY_DELETED -> trash(TrashUiState.Loaded(trashEntries(14)), back = false)()
          else -> grid(kind, back = false)()
        }
      },
    )
  }

  // --- Raccolte ---------------------------------------------------------------------------------------------------

  @Test
  fun raccolte() = shoot("raccolte", content = list())

  @Test
  fun raccolteTestoGrande() = shoot("raccolte", fontScale = LARGE_TEXT, content = list())

  @Test
  fun raccolteConIlCestino() = shoot("raccolte-eliminati", content = list(CollectionKind.RECENTLY_DELETED))

  @Test
  fun raccolteConINascosti() = shoot("raccolte-nascosti", content = list(CollectionKind.HIDDEN))

  // --- Griglie filtrate ------------------------------------------------------------------------------------------

  @Test
  fun preferiti() = shoot("preferiti", content = grid(CollectionKind.FAVORITES))

  @Test
  fun video() = shoot("video", content = grid(CollectionKind.VIDEOS))

  @Test
  fun screenshot() = shoot("screenshot", content = grid(CollectionKind.SCREENSHOTS))

  @Test
  fun whatsapp() = shoot("whatsapp", content = grid(CollectionKind.WHATSAPP))

  @Test
  fun preferitiTestoGrande() = shoot("preferiti", fontScale = LARGE_TEXT, content = grid(CollectionKind.FAVORITES))

  @Test
  fun preferitiVuoti() = shoot("preferiti-vuoti", content = grid(CollectionKind.FAVORITES, EmptyViewerItems))

  @Test
  fun preferitiConSelezione() = shoot(
    "preferiti-selezione",
    afterShow = {
      visibleCell().performTouchInput { longClick() }
      composeRule.waitForIdle()
      assertThat(composeRule.onAllNodes(hasContentDescription("Nascondi")).fetchSemanticsNodes()).isNotEmpty()
    },
    content = grid(CollectionKind.FAVORITES),
  )

  // --- Nascosti --------------------------------------------------------------------------------------------------

  @Test
  fun nascostiChiusi() = shoot("nascosti-chiusi", content = hidden(HiddenUiState.Locked))

  @Test
  fun nascostiChiusiTestoGrande() = shoot("nascosti-chiusi", fontScale = LARGE_TEXT, content = hidden(HiddenUiState.Locked))

  @Test
  fun nascostiAnnullato() = shoot("nascosti-annullato", content = hidden(HiddenUiState.Denied(HiddenDenial.CANCELLED)))

  @Test
  fun nascostiSenzaBloccoSchermo() = shoot("nascosti-senza-blocco", content = hidden(HiddenUiState.Denied(HiddenDenial.NO_SCREEN_LOCK)))

  @Test
  fun nascostiAperti() = shoot("nascosti-aperti", content = hidden(hiddenContent()))

  @Test
  fun nascostiVuoti() = shoot(
    "nascosti-vuoti",
    content = hidden(HiddenUiState.Content(runBlocking { FakeCollectionsWorld(initiallyHidden = emptyList()).hiddenLibrary.read() })),
  )

  // --- Eliminati di recente --------------------------------------------------------------------------------------

  @Test
  fun eliminati() = shoot("eliminati", content = trash(TrashUiState.Loaded(trashEntries(14))))

  @Test
  fun eliminatiTestoGrande() = shoot("eliminati", fontScale = LARGE_TEXT, content = trash(TrashUiState.Loaded(trashEntries(14))))

  @Test
  fun eliminatiChiusi() = shoot("eliminati-chiusi", content = trash(TrashUiState.Locked))

  @Test
  fun eliminatiChiusiTestoGrande() = shoot("eliminati-chiusi", fontScale = LARGE_TEXT, content = trash(TrashUiState.Locked))

  @Test
  fun eliminatiAnnullato() = shoot("eliminati-annullato", content = trash(TrashUiState.Denied(HiddenDenial.CANCELLED)))

  @Test
  fun eliminatiConNascosti() = shoot("eliminati-nascosti", content = trash(TrashUiState.Loaded(trashEntries(14, hiddenAt = setOf(1, 4, 8)))))

  @Test
  fun eliminatiConNascostiSelezionati() {
    val entries = trashEntries(14, hiddenAt = setOf(1, 4, 8))
    shoot("eliminati-nascosti-selezione", content = trash(TrashUiState.Loaded(entries), selection = setOf(entries[1].id, entries[2].id)))
  }

  @Test
  fun eliminatiConSelezione() {
    val entries = trashEntries(14)
    shoot("eliminati-selezione", content = trash(TrashUiState.Loaded(entries), selection = setOf(entries[0].id, entries[2].id, entries[5].id)))
  }

  @Test
  fun eliminatiVuoti() = shoot("eliminati-vuoti", content = trash(TrashUiState.Loaded(emptyList())))

  @Test
  fun eliminatiNonDisponibili() = shoot("eliminati-non-disponibili", content = trash(TrashUiState.Unsupported))

  companion object {
    const val DIR = "../docs/screenshots/3-P10"
    const val LARGE_TEXT = 1.3f
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class CollectionsPhoneScreenshotTest(dark: Boolean) : CollectionsScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class CollectionsTabletScreenshotTest(dark: Boolean) : CollectionsScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
