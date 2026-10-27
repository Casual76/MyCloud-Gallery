package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumListDestination
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumPageDestination
import dev.pampa.mycloudgallery.feature.albums.nav.SmartEditorDestination
import dev.pampa.mycloudgallery.feature.albums.nav.afterRulesSaved
import dev.pampa.mycloudgallery.feature.albums.nav.openAlbum
import dev.pampa.mycloudgallery.feature.albums.nav.openRules
import dev.pampa.mycloudgallery.feature.albums.nav.openSmartEditor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dopo "Salva le regole" (Fase 7, revisione di fine fase, AS-8) la pila ha **una** pagina dell'album e non due: in modifica la pagina c'e' gia' sotto l'editor,
 * prima `onSaved` faceva `popBackStack()` e ne apriva un'altra, e per tornare all'elenco servivano due "indietro". In creazione sotto l'editor c'e' l'elenco
 * e la pagina si apre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SavedRulesNavigationTest {

  private val album = AlbumId("00000000-0000-4000-8000-0000000000a1")
  private val other = AlbumId("00000000-0000-4000-8000-0000000000a2")

  private fun controller(): NavHostController {
    val nav = NavHostController(ApplicationProvider.getApplicationContext<Application>())
    nav.navigatorProvider.addNavigator(ComposeNavigator())
    nav.graph = nav.createGraph(startDestination = AlbumListDestination) {
      composable<AlbumListDestination> { }
      composable<AlbumPageDestination> { }
      composable<SmartEditorDestination> { }
    }
    return nav
  }

  /** La pila come testo breve: l'elenco, le pagine con l'album, gli editor. */
  private fun NavHostController.stack(): List<String> = currentBackStack.value.filter { it.destination.route != null && it.destination !is androidx.navigation.NavGraph }.map { entry ->
    when {
      entry.destination.hasRoute<AlbumListDestination>() -> "elenco"
      entry.destination.hasRoute<AlbumPageDestination>() -> "pagina:" + entry.toRoute<AlbumPageDestination>().id.takeLast(2)
      entry.destination.hasRoute<SmartEditorDestination>() -> "editor"
      else -> "?"
    }
  }

  @Test
  fun inModificaLaPaginaDiSottoResta_E_LEditorEsce() {
    val nav = controller()
    nav.openAlbum(album)
    nav.openRules(album)
    assertThat(nav.stack()).containsExactly("elenco", "pagina:a1", "editor").inOrder()

    nav.afterRulesSaved(album)

    assertThat(nav.stack()).containsExactly("elenco", "pagina:a1").inOrder()
    // Un solo «indietro» riporta all'elenco.
    nav.popBackStack()
    assertThat(nav.stack()).containsExactly("elenco")
  }

  @Test
  fun inCreazioneLaPaginaNuovaSostituisceLEditor() {
    val nav = controller()
    nav.openSmartEditor("Oslo 2026")
    assertThat(nav.stack()).containsExactly("elenco", "editor").inOrder()

    nav.afterRulesSaved(album)

    assertThat(nav.stack()).containsExactly("elenco", "pagina:a1").inOrder()
  }

  @Test
  fun seSottoCeLaPaginaDiUnAltroAlbumSiApreQuellaGiusta() {
    val nav = controller()
    nav.openAlbum(other)
    nav.openRules(album)

    nav.afterRulesSaved(album)

    assertThat(nav.stack()).containsExactly("elenco", "pagina:a2", "pagina:a1").inOrder()
  }
}
