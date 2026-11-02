package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPaneRules
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPanes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvideGalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberGalleryWindow
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerAnchor
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Fase 10 (P5, D13 (5)): il viewer affiancato alla Libreria. L'ospite del viewer decide quando e' affiancato (finestra ampia, viewer della Libreria, scelta "Affianca"), cambia la foto a un tocco sulla griglia a
 * lato senza riaprirlo, ricorda la scelta e non affianca mai i Nascosti ne' le altre griglie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerHostDockTest {

  private val library = SyntheticLibrary(seed = 3, count = 30)
  private val services = ViewerServices()

  private fun request(index: Int, secure: Boolean = false) = ViewerRequest(library.viewerItems(), library.snapshot.idAt(index), FakeViewerAnchor(), secure)

  private fun host(canDock: Boolean = true, choice: Boolean = true, saved: MutableList<Boolean> = mutableListOf()) = ViewerHostState(null).also {
    it.canDock = canDock
    it.dockChoice = choice
    it.saveDockChoice = { docked -> saved += docked }
  }

  @Test
  fun senzaViewerAperto_nonCeNienteDaAffiancare() {
    val host = host()
    assertThat(host.offered).isFalse()
    assertThat(host.docked).isFalse()
  }

  @Test
  fun affiancatoSoloConLaFinestraAmpiaELaScelta() {
    val wide = host()
    wide.open(request(2), services, fromLibrary = true)
    assertThat(wide.offered).isTrue()
    assertThat(wide.docked).isTrue()
    wide.close()
    // Finestra non ampia: "Affianca" non si offre e il viewer e' a finestra intera, anche con la scelta fatta.
    val narrow = host(canDock = false)
    narrow.open(request(2), services, fromLibrary = true)
    assertThat(narrow.offered).isFalse()
    assertThat(narrow.docked).isFalse()
    narrow.close()
    // Senza la scelta il viewer resta a finestra intera (di base, come in Apple Foto), ma "Affianca" si offre.
    val full = host(choice = false)
    full.open(request(2), services, fromLibrary = true)
    assertThat(full.offered).isTrue()
    assertThat(full.docked).isFalse()
    full.close()
  }

  @Test
  fun iNascostiEOgniAltraGrigliaNonSiAffiancanoMai() {
    val hidden = host()
    hidden.open(request(2, secure = true), services, fromLibrary = true)
    assertThat(hidden.isLibraryViewer).isFalse()
    assertThat(hidden.docked).isFalse()
    hidden.close()
    val album = host()
    album.open(request(2), services, fromLibrary = false)
    assertThat(album.offered).isFalse()
    assertThat(album.docked).isFalse()
    album.close()
  }

  @Test
  fun unTocco_conIlViewerAffiancato_cambiaLaFotoSenzaRiaprire() {
    val host = host()
    host.open(request(2), services, fromLibrary = true)
    val first = host.request
    assertThat(host.jump).isNull()
    // Un tocco su un'altra cella della griglia a lato: il viewer resta aperto (stessa richiesta) e riceve il salto.
    host.open(request(7), services, fromLibrary = true)
    assertThat(host.request).isSameInstanceAs(first)
    val jump = checkNotNull(host.jump)
    assertThat(jump.media).isEqualTo(library.snapshot.idAt(7))
    // Il viewer lo esegue e lo segna fatto; un secondo tocco sulla stessa foto e' un salto nuovo (altro numero).
    host.jumpHandled(jump)
    assertThat(host.jump).isNull()
    host.open(request(7), services, fromLibrary = true)
    assertThat(checkNotNull(host.jump).serial).isGreaterThan(jump.serial)
    host.close()
  }

  @Test
  fun senzaAffiancare_ilTocco_nonFaNiente() {
    val host = host(choice = false)
    host.open(request(2), services, fromLibrary = true)
    host.open(request(7), services, fromLibrary = true)
    assertThat(host.jump).isNull()
    host.close()
    // Un tocco da una griglia fuori dalla Libreria non cambia la foto di un viewer affiancato.
    val docked = host()
    docked.open(request(2), services, fromLibrary = true)
    docked.open(request(7), services, fromLibrary = false)
    assertThat(docked.jump).isNull()
    docked.open(request(7, secure = true), services, fromLibrary = true)
    assertThat(docked.jump).isNull()
    docked.close()
  }

  @Test
  fun laSceltaSiRicordaEChiudereTogliIlSalto() {
    val saved = mutableListOf<Boolean>()
    val host = host(choice = false, saved = saved)
    host.open(request(2), services, fromLibrary = true)
    host.chooseDocked(true)
    assertThat(host.docked).isTrue()
    host.chooseDocked(false)
    assertThat(host.docked).isFalse()
    assertThat(saved).containsExactly(true, false).inOrder()
    host.chooseDocked(true)
    host.open(request(5), services, fromLibrary = true)
    assertThat(host.jump).isNotNull()
    host.close()
    assertThat(host.request).isNull()
    assertThat(host.jump).isNull()
    // Chiuso il viewer la Libreria torna a tutta la finestra, ma la scelta resta per il prossimo.
    assertThat(host.docked).isFalse()
    assertThat(host.dockChoice).isTrue()
  }
}

/** I due pannelli della galleria alle larghezze del riquadro: nessuna sovrapposizione e nessuno stato perso passando da uno a due. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class GalleryPanesLayoutTest {

  private val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private var widthDp by mutableStateOf(1280)
  private var viewerOpen by mutableStateOf(true)
  private var wantsDock by mutableStateOf(true)
  private var libraryCreated = 0

  /** Compone i due pannelli una volta sola (`setContent` si chiama una volta per test); larghezza e scelta le cambia il test. */
  private fun start(initialWidthDp: Int, dock: Boolean) {
    widthDp = initialWidthDp
    wantsDock = dock
    compose.setContent {
      val window = GalleryWindow.of(widthDp, 900)
      ProvideGalleryWindow(window) {
        val panes = GalleryPaneRules.libraryViewer(window, viewerOpen, wantsDock)
        val split = (panes as? GalleryPanes.Docked)?.let { GalleryPaneRules.split(window, it) }
        Box(Modifier.requiredSize(widthDp.dp, 900.dp)) {
          GalleryPanesLayout(
            panes = panes,
            split = split,
            library = {
              // Lo scorrimento e la selezione della Libreria: non devono ripartire quando il viewer si affianca.
              var taps by rememberSaveable { mutableIntStateOf(0) }
              DisposableEffect(Unit) {
                libraryCreated++
                onDispose { }
              }
              Box(Modifier.fillMaxSize().testTag("libreria")) { Text("libreria ${rememberGalleryWindow().widthDp} selezione $taps", modifier = Modifier.clickable { taps++ }) }
            },
            viewer = { Box(Modifier.fillMaxSize().testTag("viewer")) { Text("viewer ${rememberGalleryWindow().widthDp}") } },
          )
        }
      }
    }
    compose.waitForIdle()
  }

  private fun resizeTo(dp: Int) {
    widthDp = dp
    compose.waitForIdle()
  }

  @Test
  fun sottoLaSogliaOSenzaScelta_tuttoAFinestraIntera_eLeSchermateVedonoLaFinestraVera() {
    start(360, dock = true)
    for (dp in listOf(360, 600, 840, 1199)) {
      resizeTo(dp)
      compose.onNodeWithText("libreria $dp selezione 0").assertExists()
      compose.onNodeWithText("viewer $dp").assertExists()
      assertThat(compose.onNodeWithTag("libreria").getUnclippedBoundsInRoot().width).isEqualTo(dp.dp)
    }
    // Con "Affianca" non scelto anche una finestra ampia tiene il viewer a finestra intera.
    wantsDock = false
    resizeTo(1280)
    compose.onNodeWithText("libreria 1280 selezione 0").assertExists()
  }

  @Test
  fun daAmpiaAffiancati_laLibreriaEIl40eIlViewerIl60_senzaSovrapporsi() {
    start(1200, dock = true)
    for (dp in listOf(1200, 1280, 1920)) {
      resizeTo(dp)
      val library = compose.onNodeWithTag("libreria").getUnclippedBoundsInRoot()
      val viewer = compose.onNodeWithTag("viewer").getUnclippedBoundsInRoot()
      val split = GalleryPaneRules.split(GalleryWindow.of(dp, 900), GalleryPanes.Docked(GalleryPaneRules.DOCKED_GRID_FRACTION))
      // Ogni schermata dentro un pannello legge la larghezza del suo pannello, non quella dell'app.
      compose.onNodeWithText("libreria ${split.grid.widthDp} selezione 0").assertExists()
      compose.onNodeWithText("viewer ${split.other.widthDp}").assertExists()
      assertThat(library.right).isAtMost(viewer.left)
      assertThat(viewer.width).isEqualTo(split.other.widthDp.dp)
    }
  }

  @Test
  fun affiancareEToglierenonPerdeLoStatoDiNessunPannello() {
    start(1280, dock = false)
    compose.onNodeWithText("libreria 1280 selezione 0").performClick()
    compose.onNodeWithText("libreria 1280 selezione 1").assertExists()
    wantsDock = true
    compose.waitForIdle()
    compose.onNodeWithText("libreria 512 selezione 1").assertExists()
    wantsDock = false
    compose.waitForIdle()
    compose.onNodeWithText("libreria 1280 selezione 1").assertExists()
    // Con il viewer chiuso la Libreria e' a tutta la finestra, anche con la scelta fatta.
    wantsDock = true
    viewerOpen = false
    compose.waitForIdle()
    compose.onNodeWithText("libreria 1280 selezione 1").assertExists()
    assertThat(libraryCreated).isEqualTo(1)
  }
}
