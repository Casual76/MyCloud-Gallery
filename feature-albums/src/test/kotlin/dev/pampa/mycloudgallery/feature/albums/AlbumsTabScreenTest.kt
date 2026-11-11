package dev.pampa.mycloudgallery.feature.albums

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabEntry
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabHandlers
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabScreen
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabState
import dev.pampa.mycloudgallery.feature.albums.tab.NoAlbumsTabHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test

/**
 * G2b: la scheda Album come la usa chi la tocca e come la legge TalkBack. Ogni copertina e' un bottone che dice nome, numero e i segni (smart,
 * condiviso); le regole di accessibilita' del progetto (nome e ruolo, bersagli da 48 dp, testo non tagliato) a testo 1,0 e 2,0.
 */
class AlbumsTabScreenTest : AlbumsComposeTest() {

  private val tab = AlbumsTabScenes()

  private val scale = mutableStateOf(1f)

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun screen(state: AlbumsTabState, handlers: AlbumsTabHandlers = NoAlbumsTabHandlers): @Composable () -> Unit = {
    AlbumsTabScreen(state, tab.scenes.thumbnails.thumbnails, handlers)
  }

  private fun clean(name: String, content: @Composable () -> Unit) {
    show {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) { AlbumHost { content() } }
    }
    for (value in listOf(1f, 2f)) {
      scale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), A11yConfig(density = composeRule.density))
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  @Test
  fun dueAvvisiDistintiConHashUgualeNonChiudonoLaScheda() {
    // UUID canonici sintetici con la stessa hash String: non sono dati dell'incidente dell'utente.
    val first = SharedAlbumNotice.Inherited(AlbumId("fccc5285-938f-1c52-eb45-9818b993dc41"), "Album sintetico")
    val second = SharedAlbumNotice.Inherited(AlbumId("88d35679-7ed3-a3cd-703b-bc0cce614d0b"), "Album sintetico")
    assertThat(first).isNotEqualTo(second)
    assertThat(first.hashCode()).isEqualTo(second.hashCode())
    show { AlbumHost { screen(tab.almostEmpty().copy(notices = listOf(first, second)))() } }
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithText("Album sintetico").fetchSemanticsNodes()).hasSize(2)
  }

  @Test fun piena() = clean("scheda piena", screen(tab.full()))

  @Test fun quasiVuota() = clean("scheda quasi vuota", screen(tab.almostEmpty()))

  @Test fun inCaricamento() = clean("scheda in caricamento", screen(tab.loading()))

  @Test
  fun ogniCopertinaApreLaSuaVoceEDiceISegni() {
    val opened = ArrayList<AlbumsTabEntry>()
    var all = 0
    var created = 0
    val handlers = object : AlbumsTabHandlers {
      override fun onOpen(entry: AlbumsTabEntry) {
        opened += entry
      }

      override fun onCreate() {
        created++
      }

      override fun onOpenAllAlbums() {
        all++
      }

      override fun onDismissNotice(notice: SharedAlbumNotice) = Unit

      override fun onOpenCopy(id: AlbumId) = Unit
    }
    show { AlbumHost { screen(tab.full(), handlers)() } }

    composeRule.onNode(hasContentDescription("Fotocamera", substring = true) and hasClickAction()).performClick()
    composeRule.onNode(hasContentDescription("Oslo 2026", substring = true) and hasClickAction()).performClick()
    assertThat((opened[0] as AlbumsTabEntry.Special).kind).isEqualTo(SpecialAlbum.CAMERA)
    assertThat((opened[1] as AlbumsTabEntry.Item).album.id).isEqualTo(tab.scenes.oslo)

    // Lo smart ha il segno dell'IA, il condiviso di Marco quello del gruppo: TalkBack li dice nella frase della copertina.
    composeRule.onNode(hasContentDescription("Oslo 2026", substring = true) and hasContentDescription("album smart", substring = true)).assertExists()
    composeRule.onNode(hasContentDescription("Natale in famiglia", substring = true) and hasContentDescription("condiviso", substring = true)).assertExists()

    // "Nuovo album" resta nella barra.
    composeRule.onNode(hasContentDescription("Nuovo album") and hasClickAction()).performClick()
    assertThat(created).isEqualTo(1)
  }

  @Test
  fun iPreferitiVuotiSpieganoComeAggiungerne() {
    show { AlbumHost { screen(tab.almostEmpty())() } }
    // I4: sotto la copertina solo il nome; come aggiungerne lo dice TalkBack nella frase della voce.
    composeRule.onNode(hasContentDescription("Tocca il cuore di una foto", substring = true) and hasClickAction()).assertExists()
    assertThat(composeRule.onAllNodesWithText("Tocca il cuore di una foto", substring = true).fetchSemanticsNodes()).isEmpty()
    assertThat(isClickable("Nuovo album")).isTrue()
  }
}
