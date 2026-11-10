package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.menu.AlbumMenuResult
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumActions
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumMenu
import org.junit.Test

/**
 * K1 (GS-1, review sul viewer): con il menu ⋮ aperto le frecce della tastiera cambiavano foto, e il menu restava aperto con le voci della foto di prima: "Aggiungi ad album",
 * "Imposta come copertina", "Togli dall'album" e "Trova simili" agivano su una foto che non si vedeva piu'. Ora:
 *  - con il menu aperto le frecce non cambiano foto (il pop-up prende il fuoco, e il viewer non scorre comunque con un menu aperto);
 *  - chiuso il menu, le frecce tornano al viewer;
 *  - se la foto cambia per un'altra via con il menu aperto (qui: la foto sparisce dalla vista, E-V6), il menu si chiude;
 *  - le voci degli album agiscono sempre sulla foto che si vede.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerMenuKeyboardTest : ViewerTestBase() {

  /** La porta degli album di prova: tutte e tre le voci ("Togli" perche' l'elemento e' automatico in un album smart), e chi ha ricevuto ogni richiesta. */
  private class RecordingAlbums : ViewerAlbumActions {
    val calls = ArrayList<Pair<String, MediaId>>()

    override fun menu(id: MediaId) = ViewerAlbumMenu(addToAlbum = true, removeFromAlbum = true, setAsCover = true, removeIsExclude = true)

    override fun addToAlbum(id: MediaId) {
      calls += "add" to id
    }

    override suspend fun removeFromAlbum(id: MediaId): AlbumMenuResult {
      calls += "remove" to id
      return AlbumMenuResult.DONE
    }

    override suspend fun setAsCover(id: MediaId): AlbumMenuResult {
      calls += "cover" to id
      return AlbumMenuResult.DONE
    }
  }

  private val albums = RecordingAlbums()

  private fun press(key: Key) {
    composeRule.onRoot().performKeyInput { pressKey(key) }
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
  }

  private fun menuShows(text: String = "Copia"): Boolean = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private fun openMenu() {
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000L) { menuShows("Informazioni") }
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
  }

  private fun waitMenuGone() {
    composeRule.mainClock.advanceTimeBy(1_500)
    composeRule.waitForIdle()
  }

  /** La pagina si legge dal nodo del pager ("5 di 50"). */
  private fun showsPosition(text: String): Boolean = composeRule.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun conIlMenuApertoLeFrecceNonCambianoFoto_eChiusoIlMenuTornanoAlViewer() {
    show()
    assertThat(showsPosition("5 di 50")).isTrue()
    openMenu()

    press(Key.DirectionLeft)
    press(Key.DirectionRight)
    press(Key.DirectionLeft)
    // La foto e' quella di prima e il menu e' ancora aperto, con le sue voci.
    assertThat(showsPosition("5 di 50")).isTrue()
    assertThat(menuShows()).isTrue()

    // Indietro chiude il menu; poi le frecce sono di nuovo del viewer, senza un clic.
    composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    waitMenuGone()
    assertThat(menuShows()).isFalse()
    press(Key.DirectionLeft)
    assertThat(showsPosition("6 di 50")).isTrue()
  }

  @Test
  fun seLaFotoCambiaConIlMenuApertoIlMenuSiChiude() {
    show()
    openMenu()
    // La foto che si guarda sparisce dalla vista (cancellata da un altro dispositivo): il viewer passa alla vicina, e il menu della foto di prima non resta aperto.
    fixture.items.publish(fixture.without(setOf(fixture.idAt(4))))
    composeRule.waitForIdle()
    waitMenuGone()
    assertThat(menuShows()).isFalse()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun leVociDegliAlbumAgisconoSullaFotoCheSiVede() {
    show(services = services(albums = albums))
    val visible = fixture.idAt(4)

    // Per ognuna delle tre voci: il menu aperto, un cambio di foto tentato con le frecce (non succede), poi la voce.
    for (label in listOf("Aggiungi ad album", "Imposta come copertina", "Togli dall’album")) {
      openMenu()
      press(Key.DirectionLeft)
      assertThat(showsPosition("5 di 50")).isTrue()
      composeRule.onAllNodesWithText(label).onFirst().performClick()
      waitMenuGone()
    }
    assertThat(albums.calls).containsExactly("add" to visible, "cover" to visible, "remove" to visible).inOrder()

    // E dopo un cambio di foto vero (la foto sparisce dalla vista), il menu riaperto agisce sulla foto nuova, quella che si vede.
    albums.calls.clear()
    fixture.items.publish(fixture.without(setOf(visible)))
    composeRule.waitForIdle()
    waitMenuGone()
    val now = fixture.idAt(5)
    assertThat(showsPosition("5 di 49")).isTrue()
    openMenu()
    composeRule.onAllNodesWithText("Aggiungi ad album").onFirst().performClick()
    waitMenuGone()
    assertThat(albums.calls).containsExactly("add" to now)
  }

  @Test
  fun conLaFotoCambiataConIlMenuAperto_leVociNonAgisconoSullaFotoDiPrima() {
    show(services = services(albums = albums))
    val before = fixture.idAt(4)
    openMenu()
    fixture.items.publish(fixture.without(setOf(before)))
    composeRule.waitForIdle()
    waitMenuGone()
    // Il menu si e' chiuso: nessuna voce della foto di prima da toccare, e nessuna richiesta partita per lei.
    assertThat(menuShows("Aggiungi ad album")).isFalse()
    assertThat(albums.calls.map { it.second }).doesNotContain(before)
  }
}
