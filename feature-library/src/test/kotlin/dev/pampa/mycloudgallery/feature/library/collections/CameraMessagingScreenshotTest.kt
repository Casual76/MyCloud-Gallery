package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * G2b: le griglie delle due raccolte-filtro nuove, aperte dalla scheda Album: **Fotocamera** (scattate con questo dispositivo) e **Messaggistica**
 * (WhatsApp, Telegram e Signal). La stessa `CollectionGridScreen` delle altre raccolte; chiaro e scuro, telefono e tablet, piu' la Fotocamera vuota.
 * Nei normali `testDebugUnitTest` si controlla che la griglia sia quella del filtro; con `recordRoborazziDebug` i PNG vanno in
 * `docs/screenshots/beta-grafica/G2b/`.
 */
abstract class CameraMessagingShots(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val environment = fakeGridEnvironment(loader = fakeThumbnailLoader())
  private val look: String get() = if (dark) "scuro" else "chiaro"

  /** [title] null: la griglia si apre in fondo, sui piu' recenti, e il titolo grande e' fuori vista (la barra dice la data). */
  private fun shoot(name: String, title: String?, content: @Composable () -> Unit) {
    show(dark = dark, content = content)
    if (title != null) composeRule.onNodeWithText(title).assertExists()
    composeRule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/G2b/$name-$look-$device.png")
  }

  private fun grid(kind: CollectionKind, items: ViewerItems = world.source.items(kind.filter!!)): @Composable () -> Unit = {
    CollectionGridScreen(kind, items, environment, { _, _ -> }, {}, {}, onBack = {})
  }

  @Test
  fun fotocamera() {
    // La griglia e' quella del filtro: solo le celle della fotocamera.
    val view = world.source.items(LibraryFilter.CAMERA).view.value
    assertThat(view.size).isGreaterThan(0)
    assertThat((0 until view.size).all { view.snapshot.sourceAt(view.snapshotIndex(it)) == MediaSource.CAMERA }).isTrue()
    shoot("griglia-fotocamera", title = null, content = grid(CollectionKind.CAMERA))
  }

  @Test
  fun fotocameraVuota() = shoot("griglia-fotocamera-vuota", "Nessuna foto della fotocamera", grid(CollectionKind.CAMERA, EmptyViewerItems))

  @Test
  fun messaggistica() = shoot("griglia-messaggistica", title = null, content = grid(CollectionKind.WHATSAPP))

  companion object {
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class CameraMessagingPhoneScreenshotTest(dark: Boolean) : CameraMessagingShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class CameraMessagingTabletScreenshotTest(dark: Boolean) : CameraMessagingShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
