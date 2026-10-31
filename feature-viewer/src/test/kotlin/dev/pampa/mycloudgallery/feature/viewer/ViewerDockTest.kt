package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvidePaneWindow
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Un ospite finto del viewer affiancato: stato in memoria, come lo darebbe `:app`. */
internal class FakeViewerDock(offered: Boolean = true, docked: Boolean = false) : ViewerDock {
  override var offered: Boolean by mutableStateOf(offered)
  override var docked: Boolean by mutableStateOf(docked)
  override var jump: ViewerJump? by mutableStateOf(null)
  val handled = mutableListOf<ViewerJump>()
  val choices = mutableListOf<Boolean>()

  override fun chooseDocked(docked: Boolean) {
    this.docked = docked
    choices += docked
  }

  override fun jumpHandled(jump: ViewerJump) {
    handled += jump
    if (this.jump == jump) this.jump = null
  }
}

/**
 * Fase 10 (P5, D13 (5)): il viewer affiancato alla Libreria. La voce "Affianca alla Libreria" / "Schermo intero" si offre solo dove serve (finestra ampia, viewer della Libreria, mai i Nascosti), e un tocco
 * sulla griglia a lato cambia la foto del viewer senza riaprirlo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class ViewerDockMenuTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
  private val handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {}, {})
  private val capabilities = ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true)

  private fun labels(entries: List<GalleryMenuEntry>): List<String> = entries.map { if (it is GalleryMenuEntry.Item) it.label else "|" }

  private fun menu(dock: ViewerDockEntry?) = labels(viewerMenuEntries(resources, capabilities, isFavorite = false, secure = false, handlers = handlers, dock = dock))

  @Test
  fun laVoceDiceAffiancaOSchermoInteroSecondoLoStato() {
    val dock = resources.getString(R.string.viewer_action_dock)
    val undock = resources.getString(R.string.viewer_action_undock)
    assertThat(menu(ViewerDockEntry(docked = false) {})).contains(dock)
    assertThat(menu(ViewerDockEntry(docked = false) {})).doesNotContain(undock)
    assertThat(menu(ViewerDockEntry(docked = true) {})).contains(undock)
    assertThat(menu(ViewerDockEntry(docked = true) {})).doesNotContain(dock)
    // Senza porta il menu e' quello di sempre.
    assertThat(menu(null)).doesNotContain(dock)
    assertThat(menu(null)).doesNotContain(undock)
  }

  @Test
  fun laVoceStaFraLeAzioniEIlCestino_inUnGruppoSuo() {
    val shown = menu(ViewerDockEntry(docked = false) {})
    val dock = shown.indexOf(resources.getString(R.string.viewer_action_dock))
    val info = shown.indexOf(resources.getString(R.string.viewer_action_info))
    val delete = shown.indexOf(resources.getString(R.string.viewer_action_delete))
    assertThat(dock).isGreaterThan(info)
    assertThat(dock).isLessThan(delete)
    assertThat(shown[dock - 1]).isEqualTo("|")
    assertThat(shown[dock + 1]).isEqualTo("|")
  }

  @Test
  fun laVoceSiOffreSoloConLaPortaCheLaOffre_eMaiPerINascosti() {
    assertThat(null.entryOf(secure = false)).isNull()
    assertThat(FakeViewerDock(offered = false, docked = false).entryOf(secure = false)).isNull()
    assertThat(FakeViewerDock(offered = true).entryOf(secure = true)).isNull()
    assertThat(FakeViewerDock(offered = true).entryOf(secure = false)?.docked).isFalse()
    // Gia' affiancato resta la voce "Schermo intero", anche se la finestra non offrirebbe piu' di affiancare.
    assertThat(FakeViewerDock(offered = false, docked = true).entryOf(secure = false)?.docked).isTrue()
  }

  @Test
  fun sceglierLaVoceCambiaLaPortaEIlSuoValore() {
    val dock = FakeViewerDock(offered = true)
    dock.entryOf(secure = false)!!.onToggle()
    assertThat(dock.docked).isTrue()
    dock.entryOf(secure = false)!!.onToggle()
    assertThat(dock.docked).isFalse()
    assertThat(dock.choices).containsExactly(true, false).inOrder()
  }

  private fun ViewerDock?.entryOf(secure: Boolean) = this.entry(secure)
}

/** Il viewer vero (Robolectric, sui fake) che segue i salti della griglia a lato e legge la finestra del suo pannello. */
class ViewerDockOverlayTest : ViewerTestBase() {

  private fun showDocked(dock: ViewerDock, window: GalleryWindow? = null) {
    val request = fixture.request()
    val services = services()
    composeRule.setContent {
      ViewerTestHost(dark = true) {
        CompositionLocalProvider(LocalViewerDock provides dock) {
          ProvidePaneWindow(window) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) }
        }
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun unSaltoDallaGrigliaCambiaLaFotoSenzaRiaprire() {
    val dock = FakeViewerDock(docked = true)
    showDocked(dock)
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    val jump = ViewerJump(fixture.idAt(11), serial = 1)
    dock.jump = jump
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithContentDescription("12 di 50").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithContentDescription("12 di 50").assertExists()
    assertThat(dock.handled).containsExactly(jump)
    assertThat(dock.jump).isNull()
    assertThat(closed).isEqualTo(0)
    // Un secondo salto sulla stessa foto (altro numero) e' gestito e non cambia niente.
    val again = ViewerJump(fixture.idAt(11), serial = 2)
    dock.jump = again
    composeRule.waitUntil(TIMEOUT) { dock.handled.size == 2 }
    composeRule.onNodeWithContentDescription("12 di 50").assertExists()
  }

  @Test
  fun unSaltoVersoUnaFotoCheLaVistaNonHa_nonSiSpostaEViene_scartato() {
    val missing = dev.pampa.mycloudgallery.core.model.MediaId("00000000-0000-4000-8000-0000000000aa")
    // La vista non ha quel media (un nascosto, uno sparito, un altro filtro): nessuna pagina.
    assertThat(ViewerJumps.pageOf(fixture.library.view(), missing)).isNull()
    assertThat(ViewerJumps.pageOf(fixture.library.view(), fixture.idAt(3))).isEqualTo(3)
    val dock = FakeViewerDock(docked = true)
    showDocked(dock)
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    val jump = ViewerJump(missing, serial = 1)
    dock.jump = jump
    composeRule.waitForIdle()
    composeRule.waitUntil(TIMEOUT) { dock.handled.isNotEmpty() }
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    assertThat(dock.jump).isNull()
  }

  @Test
  fun inUnPannelloDa768dpLeInformazioniStannoInUnFoglio_nonInColonna() {
    val dock = FakeViewerDock(docked = true)
    showDocked(dock, window = GalleryWindow.of(768, 800))
    // Nessuna colonna: il pannello informazioni non c'e' finche' non lo si apre, e il viewer legge la finestra del pannello (media), non quella dell'app.
    composeRule.onAllNodesWithContentDescription("Chiudi").assertCountEquals(1)
  }

  @Test
  fun ilViewerInfoSidePanelSiVedeInUnaFinestraEstesa() {
    // 900 dp: estesa. La soglia e' quella di prima (840).
    val dock = FakeViewerDock(docked = false, offered = false)
    showDocked(dock, window = GalleryWindow.of(900, 800))
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
  }

  companion object {
    private const val TIMEOUT = 5_000L
  }
}
