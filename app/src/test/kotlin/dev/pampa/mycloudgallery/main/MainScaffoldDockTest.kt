package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvideGalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.testing.InMemoryLibraryPreferences
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.runBlocking
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
 * Fase 10 (P5, D13 (5)): la scelta "Affianca" si ricorda **per profilo**. `MainScaffold` lega l'ospite del viewer alle preferenze della Libreria (che stanno nei dati del profilo): la scelta salvata arriva all'ospite,
 * la scelta fatta dal menu del viewer si salva, e un altro profilo (altre preferenze) parte da "finestra intera". Il viewer affiancato non sopravvive a un'altra scheda.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
class MainScaffoldDockTest {

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

  private var tab by mutableStateOf(GalleryTab.Library)

  private fun show(host: ViewerHostState, state: LibraryTabState) {
    compose.setContent {
      MyCloudGalleryTheme {
        ProvideGalleryWindow(GalleryWindow.of(1280, 800)) {
          CompositionLocalProvider(LocalViewerHost provides host) {
            MainScaffold(
              status = GlobalStatus.HOME_CONNECTED,
              selectedTab = tab,
              onSelectTab = { tab = it },
              onStatusClick = {},
              onOpenSettings = {},
              libraryState = state,
            )
          }
        }
      }
    }
    compose.waitForIdle()
  }

  private fun prefsOf(state: LibraryTabState): InMemoryLibraryPreferences = state.preferences as InMemoryLibraryPreferences

  private fun requestOf(state: LibraryTabState): ViewerRequest {
    val items = state.viewerItems
    return ViewerRequest(items, items.view.value.snapshot.idAt(0), state.grid.viewerAnchor, secure = false)
  }

  @Test
  fun laSceltaSalvataDelProfiloArrivaAllOspiteDelViewer() {
    val state = syntheticLibraryTabState()
    runBlocking { prefsOf(state).setViewerDocked(true) }
    val host = ViewerHostState(null)
    show(host, state)
    compose.waitUntil(5_000) { host.dockChoice }
    assertThat(host.dockChoice).isTrue()
  }

  @Test
  fun unAltroProfiloParteDaFinestraIntera() {
    val first = syntheticLibraryTabState()
    runBlocking { prefsOf(first).setViewerDocked(true) }
    // Un altro profilo ha le sue preferenze (un altro file, nei suoi dati): non le vede.
    val other = syntheticLibraryTabState()
    val host = ViewerHostState(null)
    show(host, other)
    compose.waitForIdle()
    assertThat(host.dockChoice).isFalse()
    assertThat(prefsOf(other).viewerDocked.value).isFalse()
    assertThat(prefsOf(first).viewerDocked.value).isTrue()
  }

  @Test
  fun affiancaScelto_dalMenuDelViewer_siSalvaNelleDelProfilo_eSiToglieConSchermoIntero() {
    val state = syntheticLibraryTabState()
    val host = ViewerHostState(null)
    show(host, state)
    host.canDock = true
    host.open(requestOf(state), ViewerServices(), fromLibrary = true)
    assertThat(host.offered).isTrue()
    assertThat(host.docked).isFalse()
    host.chooseDocked(true)
    compose.waitUntil(5_000) { prefsOf(state).viewerDocked.value }
    assertThat(host.docked).isTrue()
    host.chooseDocked(false)
    compose.waitUntil(5_000) { !prefsOf(state).viewerDocked.value }
    assertThat(host.docked).isFalse()
    host.close()
  }

  @Test
  fun cambiandoSchedaIlViewerAffiancatoSiChiude_quelloANormaleResta() {
    val state = syntheticLibraryTabState()
    val host = ViewerHostState(null)
    show(host, state)
    host.canDock = true
    // Non affiancato: cambiare scheda non lo tocca (come prima della Fase 10).
    host.open(requestOf(state), ViewerServices(), fromLibrary = true)
    tab = GalleryTab.Collections
    compose.waitForIdle()
    assertThat(host.request).isNotNull()
    host.close()
    tab = GalleryTab.Library
    compose.waitForIdle()
    // Affiancato: il suo posto e' accanto alla griglia della Libreria, quindi un'altra scheda lo chiude.
    host.chooseDocked(true)
    host.open(requestOf(state), ViewerServices(), fromLibrary = true)
    assertThat(host.docked).isTrue()
    tab = GalleryTab.Collections
    compose.waitForIdle()
    assertThat(host.request).isNull()
  }
}
