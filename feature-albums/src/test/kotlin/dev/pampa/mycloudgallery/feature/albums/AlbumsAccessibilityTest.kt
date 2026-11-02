package dev.pampa.mycloudgallery.feature.albums

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumUnavailableReason
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListScreen
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListUi
import dev.pampa.mycloudgallery.feature.albums.list.NoAlbumListHandlers
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageScreen
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageUi
import dev.pampa.mycloudgallery.feature.albums.page.NoAlbumPageHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Raccolte, album, Persone", D28): l'elenco e la pagina degli album letti come li legge TalkBack. Ogni stato
 * del catalogo (gli stessi degli screenshot di P10 della Fase 7), a testo 1,0 e 2,0: nome e ruolo per ogni elemento attivo, bersagli da 48 dp
 * misurati, nessun id o nome di file, testo non tagliato. E l'ordinamento personalizzato offre "Sposta su" e "Sposta giu'" al posto del
 * trascinamento.
 */
class AlbumsAccessibilityTest : AlbumsComposeTest() {

  private val scenes = Scenes()

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  private fun list(ui: AlbumListUi): @Composable () -> Unit = {
    AlbumListScreen(ui, scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers, onBack = {})
  }

  private fun page(ui: AlbumPageUi, title: String = ""): @Composable () -> Unit = {
    AlbumPageScreen(ui, title, scenes.thumbnails.thumbnails, NoAlbumPageHandlers)
  }

  /** La scala del testo di adesso: si cambia senza rimostrare la scena (`setContent` si puo' chiamare una volta per prova). */
  private val scale = mutableStateOf(1f)

  /** Mostra [content] nella pagina degli album e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena. */
  private fun clean(name: String, content: @Composable () -> Unit) {
    show {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) { AlbumHost { content() } }
    }
    for (value in listOf(1f, 2f)) {
      scale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config())
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  private val notices
    get() = listOf(
      SharedAlbumNotice.Closed(scenes.christmas, "Gita al lago", CloseMode.STOP, keptCopy = scenes.lake),
      SharedAlbumNotice.Closed(scenes.grandpa, "Cena di Natale", CloseMode.DELETE, keptCopy = null),
    )

  // --- L'elenco ---------------------------------------------------------------------------------------------------------------

  @Test fun elenco() = clean("elenco", list(scenes.listUi()))

  @Test fun elencoPerNome() = clean("elenco per nome", list(scenes.listUi(scenes.listReady(AlbumListSort.NAME))))

  @Test fun elencoVuoto() = clean("elenco vuoto", list(scenes.listUi(AlbumListState.Empty)))

  @Test fun elencoInCaricamento() = clean("elenco in caricamento", list(scenes.listUi(AlbumListState.Loading)))

  @Test
  fun elencoConAvvisi() =
    clean("elenco con avvisi", list(scenes.listUi(scenes.listReady(notices = notices), left = listOf(scenes.album(scenes.grandpa) as Album.Shared))))

  @Test fun elencoPersonalizzato() = clean("elenco personalizzato", list(scenes.listUi(scenes.listReady(AlbumListSort.CUSTOM), reorderable = true)))

  // --- La pagina --------------------------------------------------------------------------------------------------------------

  @Test fun paginaManuale() = clean("pagina manuale", page(scenes.pageUi(scenes.birthday)))

  @Test fun paginaVuota() = clean("pagina vuota", page(scenes.pageUi(scenes.empty)))

  @Test
  fun paginaSmart() = clean("pagina smart", page(scenes.pageUi(scenes.oslo, banners = listOf(AlbumBanner.NewItems(3)), suggested = true)))

  @Test
  fun paginaCondivisoMioInCorso() {
    val running = ShareOpState.Running(ShareOpId("op-1"), scenes.osloShared, SharePlanKind.SHARE, done = 7, total = 18)
    clean("condiviso mio in corso", page(scenes.pageUi(scenes.osloShared, banners = listOf(AlbumBanner.Sharing(running), AlbumBanner.Incoming(4)))))
  }

  @Test
  fun paginaCondivisoDiUnAltro() = clean("condiviso di un altro", page(scenes.pageUi(scenes.christmas, banners = listOf(AlbumBanner.FilesFromPc(3)))))

  @Test
  fun paginaSolaVisione() =
    clean("sola visione", page(scenes.pageUi(scenes.grandpa, banners = listOf(AlbumBanner.NotAdded(2), AlbumBanner.FolderChanged))))

  @Test fun paginaEreditato() = clean("ereditato", page(scenes.pageUi(scenes.christmas, banners = listOf(AlbumBanner.Inherited))))

  @Test fun paginaScegliCopertina() = clean("scegli copertina", page(scenes.pageUi(scenes.birthday, pickingCover = true)))

  @Test
  fun paginaChiusaConLaCopia() {
    val notice = SharedAlbumNotice.Closed(scenes.christmas, "Gita al lago", CloseMode.STOP, keptCopy = scenes.lake)
    clean("chiusa con la copia", page(AlbumPageUi(page = AlbumPageState.Closed(notice))))
  }

  @Test
  fun paginaNonTrovata() =
    clean("non trovata", page(AlbumPageUi(page = AlbumPageState.Unavailable(AlbumUnavailableReason.NOT_FOUND)), title = "Album"))

  @Test fun paginaInCaricamento() = clean("in caricamento", page(AlbumPageUi(), title = "Compleanno di Anna"))

  // --- La semantica attesa del §2.7 -------------------------------------------------------------------------------------------

  @Test
  fun lOrdinamentoPersonalizzatoHaSpostaSuESpostaGiuSenzaTrascinamento() {
    show { AlbumHost { list(scenes.listUi(scenes.listReady(AlbumListSort.CUSTOM), reorderable = true))() } }
    val labels = A11yAssertions.nodes(root()).flatMap(A11yAssertions::customActionLabels).toSet()
    assertThat(labels).containsAtLeast("Sposta su", "Sposta giù")
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfElenco() {
    show { AlbumHost { list(scenes.listUi())() } }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfPagina() {
    show { AlbumHost { page(scenes.pageUi(scenes.birthday))() } }
    composeRule.onRoot().checkRoboAccessibility()
  }
}

