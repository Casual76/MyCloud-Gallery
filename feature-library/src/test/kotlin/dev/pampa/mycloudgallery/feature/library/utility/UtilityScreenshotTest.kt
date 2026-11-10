package dev.pampa.mycloudgallery.feature.library.utility

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.feature.library.collections.ComposeScreenTest
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.SHOT_PHONE_QUALIFIERS
import dev.pampa.mycloudgallery.feature.library.collections.TABLET_QUALIFIERS
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryRows
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedController
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedHost
import dev.pampa.mycloudgallery.feature.library.trash.TrashHiddenMask
import dev.pampa.mycloudgallery.feature.library.trash.testing.FakeNasTrash
import dev.pampa.mycloudgallery.feature.library.trash.testing.sampleNasRows
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeCleanupNas
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeFullHashSwitch
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeIdenticalDuplicates
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakePhoneCopyCheck
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeSimilarShots
import dev.pampa.mycloudgallery.feature.library.utility.testing.sampleRefOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P8 (Fase 7: Utility, Doppioni, Eliminati di recente): chiaro e scuro, telefono (360 dp) e tablet (1280 dp), piu' il testo
 * grande (1,3x) sulle pagine principali. Sui fake: la libreria sintetica, i doppioni e il NAS finti, il cestino di sistema e quello del NAS di prova.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*UtilityScreenshot*"` scrive i PNG in `docs/screenshots/7-P8/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
abstract class UtilityScreenshotBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val world = FakeCollectionsWorld()
  private val loader = fakeThumbnailLoader()
  private val environment = fakeGridEnvironment(loader = loader)
  private val system = FakePhoneMediaIntents(canTrash = true)
  private val now = 1_800_000_000_000L
  private var tapClock = 100_000L

  private val look: String get() = if (dark) "scuro" else "chiaro"

  @After
  fun stop() {
    scope.cancel()
  }

  @Composable
  private fun Stage(content: @Composable () -> Unit) {
    val host = rememberFluidGlassModalHostState()
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
        FluidGlassModalHost(state = host, backdrop = backdrop)
      }
    }
  }

  private fun shoot(name: String, fontScale: Float? = null, afterShow: () -> Unit = {}, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { Stage(content) }
    composeRule.waitForIdle()
    afterShow()
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  // --- Doppioni --------------------------------------------------------------------------------------------------

  private fun duplicates(
    identical: FakeIdenticalDuplicates = FakeIdenticalDuplicates(),
    similar: FakeSimilarShots = FakeSimilarShots(),
    fullHash: FakeFullHashSwitch = FakeFullHashSwitch(),
  ): @Composable () -> Unit {
    val controller = DuplicatesController(identical, similar, FakeCleanupNas(), SenderIntents(system, testIntentSender()), fullHash, scope, phoneCheck = FakePhoneCopyCheck())
    return {
      DuplicatesHost(controller, loader, ::sampleRefOf, familyName = { if (it == "marco") "Marco" else null }, onBack = {})
    }
  }

  private fun click(text: String, index: Int = 0) {
    composeRule.onAllNodesWithText(text)[index].performClick()
    composeRule.waitForIdle()
  }

  private fun scrollToText(text: String) {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
    composeRule.waitForIdle()
  }

  @Test
  fun doppioniIdentici() = shoot("doppioni-identici", content = duplicates())

  @Test
  fun doppioniIdenticiTestoGrande() = shoot("doppioni-identici", fontScale = LARGE_TEXT, content = duplicates())

  @Test
  fun doppioniIdenticiConGliAltri() = shoot("doppioni-identici-altre-aree", afterShow = { scrollToText("Foto · 4 copie · sul NAS") }, content = duplicates())

  @Test
  fun doppioniIdenticiRivedi() = shoot("doppioni-identici-rivedi", afterShow = { click("Rivedi") }, content = duplicates())

  @Test
  fun doppioniIdenticiFoglio() = shoot("doppioni-identici-foglio", afterShow = { click("Pulizia guidata") }, content = duplicates())

  @Test
  fun doppioniIdenticiFoglioTestoGrande() =
    shoot("doppioni-identici-foglio", fontScale = LARGE_TEXT, afterShow = { click("Pulizia guidata") }, content = duplicates())

  @Test
  fun doppioniDaVerificare() = shoot("doppioni-da-verificare", afterShow = { scrollToText("Video · 2 copie · sul NAS") }, content = duplicates())

  @Test
  fun doppioniSimili() = shoot("doppioni-simili", afterShow = { click("Simili") }, content = duplicates())

  @Test
  fun doppioniSimiliTestoGrande() = shoot("doppioni-simili", fontScale = LARGE_TEXT, afterShow = { click("Simili") }, content = duplicates())

  @Test
  fun doppioniSimiliConUnaPreferitaScelta() = shoot(
    "doppioni-simili-preferita",
    afterShow = {
      click("Simili")
      composeRule.onNodeWithContentDescription("Foto 3 di 4", substring = true).performScrollTo().performClick()
      composeRule.waitForIdle()
    },
    content = duplicates(),
  )

  @Test
  fun doppioniSimiliFoglio() = shoot(
    "doppioni-simili-foglio",
    afterShow = {
      click("Simili")
      click("Elimina 2 scatti")
    },
    content = duplicates(),
  )

  @Test
  fun doppioniVuoti() = shoot(
    "doppioni-vuoti",
    content = duplicates(FakeIdenticalDuplicates(proposals = emptyList())),
  )

  @Test
  fun doppioniSimiliVuoti() = shoot(
    "doppioni-simili-vuoti",
    afterShow = { click("Simili") },
    content = duplicates(similar = FakeSimilarShots(batches = emptyList())),
  )

  @Test
  fun doppioniInterruttoreNotturno() = shoot(
    "doppioni-notturno",
    afterShow = {
      scrollToText("Controlla di notte i video del NAS")
      composeRule.onNodeWithText("Controlla di notte i video del NAS").performClick()
      composeRule.waitForIdle()
    },
    content = duplicates(),
  )

  // --- Utility ---------------------------------------------------------------------------------------------------

  private fun entries(): @Composable () -> Unit = {
    val group = utilityEntryGroup(
      counts = mapOf(UtilityPage.SCREENSHOTS to 214, UtilityPage.VIDEOS to 1_380, UtilityPage.EDITED to 37, UtilityPage.WHATSAPP to 4_905),
    )
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(horizontal = GalleryDimens.Gutter),
      contentPadding = PaddingValues(top = 48.dp),
    ) {
      item { CollectionEntryRows(group, selectedCode = null, twoPane = false, onSelect = {}) }
    }
  }

  @Test
  fun utilityVoci() = shoot("utility-voci", content = entries())

  @Test
  fun utilityVociTestoGrande() = shoot("utility-voci", fontScale = LARGE_TEXT, content = entries())

  private fun filterPage(page: UtilityPage, empty: Boolean = false): @Composable () -> Unit = {
    UtilityFilterScreen(
      page = page,
      items = if (empty) EmptyViewerItems else world.source.items(page.filter!!),
      environment = environment,
      onFavorite = { _, _ -> },
      onHide = {},
      onOpenViewer = {},
      onBack = {},
    )
  }

  @Test
  fun modificate() = shoot("modificate", content = filterPage(UtilityPage.EDITED))

  @Test
  fun modificateVuote() = shoot("modificate-vuote", content = filterPage(UtilityPage.EDITED, empty = true))

  @Test
  fun screenshotDellUtility() = shoot("screenshot", content = filterPage(UtilityPage.SCREENSHOTS))

  // --- Eliminati di recente --------------------------------------------------------------------------------------

  private fun trash(nas: FakeNasTrash = FakeNasTrash(sampleNasRows())): @Composable () -> Unit {
    val controller = RecentlyDeletedController(
      HiddenAccessGate(TestClock(), graceMillis = 60_000L),
      FakeRecentlyDeletedSource(fakeTrashedItems(now, count = 6)),
      TrashHiddenMask { emptySet() },
      FakePhoneMediaIntents(canTrash = true),
      scope,
      nas,
    ) { now }
    return { RecentlyDeletedHost(controller, loader, keepRecentsHidden = { false }, authenticator = FakeAuthenticator(), onBack = {}, monotonicMs = { tapClock }) }
  }

  private fun tapRow(name: String) {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasContentDescription(name, substring = true))
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription(name, substring = true).performClick()
    composeRule.waitForIdle()
  }

  @Test
  fun eliminatiDueCestini() = shoot("eliminati-due-cestini", content = trash())

  @Test
  fun eliminatiDueCestiniTestoGrande() = shoot("eliminati-due-cestini", fontScale = LARGE_TEXT, content = trash())

  @Test
  fun eliminatiDelNasConLaSelezione() = shoot(
    "eliminati-nas-selezione",
    afterShow = { tapRow("IMG_20261012_184501.jpg") },
    content = trash(),
  )

  @Test
  fun eliminatiEliminaSubito() = shoot(
    "eliminati-elimina-subito",
    afterShow = {
      tapRow("IMG_20261012_184501.jpg")
      composeRule.onNodeWithContentDescription("Elimina subito").performClick()
      composeRule.waitForIdle()
    },
    content = trash(),
  )

  @Test
  fun eliminatiUltimaConferma() = shoot(
    "eliminati-ultima-conferma",
    afterShow = {
      tapRow("IMG_20261012_184501.jpg")
      composeRule.onNodeWithContentDescription("Elimina subito").performClick()
      composeRule.waitForIdle()
      tapClock += 5_000
      composeRule.onNodeWithText("Elimina subito").performClick()
      composeRule.waitForIdle()
    },
    content = trash(),
  )

  @Test
  fun eliminatiSoloIlTelefono() = shoot("eliminati-solo-telefono", content = trash(FakeNasTrash(emptyList())))

  companion object {
    const val DIR = "../docs/screenshots/7-P8"
    const val LARGE_TEXT = 1.3f
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class UtilityPhoneScreenshotTest(dark: Boolean) : UtilityScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class UtilityTabletScreenshotTest(dark: Boolean) : UtilityScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
