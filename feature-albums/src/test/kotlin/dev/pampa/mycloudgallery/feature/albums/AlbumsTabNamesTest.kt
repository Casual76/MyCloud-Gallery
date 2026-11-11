package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabEntry
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabScreen
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabState
import dev.pampa.mycloudgallery.feature.albums.tab.NoAlbumsTabHandlers
import dev.pampa.mycloudgallery.feature.albums.tab.TileNameFit
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import java.util.UUID
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * GF-20 (review della beta): nella scheda Album a 360 dp meta' dei nomi finiva con i puntini, compresa la voce fissa «Messaggistica» (una parola sola, piu' larga
 * dei 98,7 dp di una cella a tre colonne). Il nome sta su due righe al massimo, con due righe sempre riservate, e una parola che non entra scende di corpo:
 * - le cinque voci fisse si leggono **intere** a testo normale (una riga, niente puntini, parola non spezzata);
 * - due celle vicine con un nome di una riga e uno di due hanno il conteggio alla stessa altezza;
 * - lo stesso col testo grande (due colonne) e sul tablet (sette colonne).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Il telefono e' a 360 dp di larghezza ma alto abbastanza da comporre tutta la scheda (a 1,3x le colonne sono due e le righe di copertine piu' alte).
@Config(application = Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
class AlbumsTabNamesTest : AlbumsComposeTest() {

  private val tab = AlbumsTabScenes()

  private val fixedNames = listOf("Preferiti", "Fotocamera", "Video", "Screenshot", "Messaggistica")

  private fun layoutsOf(text: String): List<TextLayoutResult> =
    composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
      val results = ArrayList<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
      results.single()
    }

  private fun yOf(text: String): Float =
    composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().single().positionInRoot.y

  private fun screen(state: AlbumsTabState.Ready): @Composable () -> Unit = {
    AlbumHost { AlbumsTabScreen(state, tab.scenes.thumbnails.thumbnails, NoAlbumsTabHandlers) }
  }

  /** Un album a mano con il [title] e il [count] dati (il conteggio e' il testo che si cerca per l'altezza). */
  private fun album(n: Int, title: String, count: Int) = AlbumsTabEntry.Item(
    Album.Manual(
      id = AlbumId(UUID(0x6_0000_0000L, n.toLong()).toString()),
      title = title,
      cover = CoverRef.Auto(FakeAlbumLibrary.id(n)),
      count = count,
      sort = AlbumSort.NEWEST_FIRST,
      updatedAtNas = 1_000L + n,
      newestDay = null,
    ),
  )

  private fun assertFixedNamesWhole(scale: Float) {
    for (name in fixedNames) {
      val layouts = layoutsOf(name)
      assertWithMessage("«$name» non e' sullo schermo (testo $scale)").that(layouts).hasSize(1)
      val layout = layouts.single()
      assertWithMessage("«$name» va a capo a meta' parola (testo $scale)").that(layout.lineCount).isEqualTo(1)
      assertWithMessage("«$name» finisce con i puntini (testo $scale)").that(layout.isLineEllipsized(0)).isFalse()
    }
  }

  @Test
  fun leVociFisseSiLeggonoIntereA360DpATestoNormale() {
    show { screen(tab.full())() }
    assertFixedNamesWhole(1f)
  }

  @Test
  fun leVociFisseSiLeggonoIntereATestoGrande() {
    show(fontScale = LARGE_TEXT) { screen(tab.full())() }
    assertFixedNamesWhole(LARGE_TEXT)
  }

  @Test
  fun unNomeDiDueRigheSiLeggeIntero() {
    val state = AlbumsTabState.Ready(listOf(album(1, "Weekend in montagna", 7)), pinned = 0)
    show { screen(state)() }
    val layout = layoutsOf("Weekend in montagna").single()
    assertThat(layout.lineCount).isEqualTo(2)
    assertThat(layout.isLineEllipsized(1)).isFalse()
  }

  @Test
  fun unNomeMoltoLungoFinisceConIPuntiniAllaSecondaRiga() {
    val long = "Vacanza al mare con tutta la famiglia di zia Carla e gli amici di sempre"
    val state = AlbumsTabState.Ready(listOf(album(1, long, 7)), pinned = 0)
    show { screen(state)() }
    val layout = layoutsOf(long).single()
    assertThat(layout.lineCount).isEqualTo(2)
    assertThat(layout.isLineEllipsized(1)).isTrue()
  }

  /**
   * FAB-30 (review generale, giro 3): con il testo piu' grande del normale le due righe vere erano piu' alte dell'altezza riservata a parte, e il nome restava su
   * una riga con i puntini e una riga vuota sotto. Ora le due righe le riserva il testo stesso.
   */
  @Test
  fun unNomeLungoStaSuDueRigheConIlTestoUnPoPiuGrande() = assertLongNameOnTwoLines(1.15f)

  @Test
  fun unNomeLungoStaSuDueRigheConIlTestoGrande() = assertLongNameOnTwoLines(LARGE_TEXT)

  private fun assertLongNameOnTwoLines(scale: Float) {
    val long = "Compleanno della nonna Giuseppina"
    val state = AlbumsTabState.Ready(listOf(album(1, long, 7)), pinned = 0)
    show(fontScale = scale) { screen(state)() }
    val layout = layoutsOf(long).single()
    assertWithMessage("a testo $scale il nome lungo deve usare tutte e due le righe").that(layout.lineCount).isEqualTo(2)
    assertWithMessage("a testo $scale la prima riga non finisce con i puntini").that(layout.isLineEllipsized(0)).isFalse()
  }

  /** FAB-30, l'altro caso: una densita' non intera (2,625, i telefoni larghi 411 dp), dove l'altezza di ogni riga si arrotonda in su. */
  @Test
  @Config(qualifiers = "w411dp-h2400dp-420dpi")
  fun unNomeLungoStaSuDueRigheAncheADensitaNonIntera() {
    val long = "Compleanno della nonna Giuseppina"
    val state = AlbumsTabState.Ready(listOf(album(1, long, 7)), pinned = 0)
    show { screen(state)() }
    val layout = layoutsOf(long).single()
    assertThat(layout.lineCount).isEqualTo(2)
    assertThat(layout.isLineEllipsized(0)).isFalse()
  }

  /**
   * Due celle vicine, il nome di una riga e quello di due: i nomi partono alla stessa altezza (e quello della terza, "Compleanno", pure). I4: sotto la copertina
   * non c'e' piu' il numero, e il nome lo dice TalkBack con il conteggio.
   */
  @Test
  fun iNomiSuUnaRigaESuDueStannoAllaStessaAltezzaA360Dp() {
    val state = AlbumsTabState.Ready(
      listOf(album(1, "Gita", 71), album(2, "Weekend in montagna", 72), album(3, "Compleanno", 73)),
      pinned = 0,
    )
    show { screen(state)() }
    val first = yOf("Gita")
    assertWithMessage("«Gita» (una riga) e «Weekend in montagna» (due righe): nomi a quote diverse").that(yOf("Weekend in montagna")).isEqualTo(first)
    assertThat(yOf("Compleanno")).isEqualTo(first)
    assertThat(composeRule.onAllNodesWithText("71", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun iNomiSuUnaRigaESuDueStannoAllaStessaAltezzaATestoGrande() {
    // A 1,3x le colonne sono due: si guardano le prime due celle, affiancate.
    val state = AlbumsTabState.Ready(listOf(album(1, "Gita", 71), album(2, "Weekend in montagna con tutti", 72)), pinned = 0)
    show(fontScale = LARGE_TEXT) { screen(state)() }
    assertThat(yOf("Weekend in montagna con tutti")).isEqualTo(yOf("Gita"))
  }

  /** La misura: una parola che entra non cambia il corpo; una che non entra lo riduce quanto serve, non oltre il minimo. */
  @Test
  fun ilCorpoScendeSoloSeLaParolaPiuLargaNonEntra() {
    val fit = TileNameFit(maxWidthPx = 100) { word -> word.length * 10 }
    assertThat(fit.scaleFor("Video")).isEqualTo(1f)
    assertThat(fit.scaleFor("Natale in famiglia con i nonni")).isEqualTo(1f) // la parola piu' larga ("famiglia", 8 caratteri = 80) entra
    assertThat(fit.scaleFor("Messaggistica")).isLessThan(1f) // 13 caratteri = 130 > 100
    assertThat(fit.scaleFor("Messaggistica")).isAtLeast(TileNameFit.MIN_SCALE)
    // Troppo larga anche al minimo: scende fino al minimo e poi va a capo (un nome lungo non si rimpicciolisce all'infinito).
    assertThat(fit.scaleFor("Supercalifragilistichespiralidoso")).isEqualTo(TileNameFit.MIN_SCALE)
    assertThat(fit.scaleFor("")).isEqualTo(1f)
    assertThat(TileNameFit(maxWidthPx = 0) { 999 }.scaleFor("Messaggistica")).isEqualTo(1f)
  }
}

/** Il tablet in orizzontale: sette colonne, i nomi stanno intere e i conteggi allineati. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class AlbumsTabNamesTabletTest : AlbumsComposeTest() {

  private val tab = AlbumsTabScenes()

  @Test
  fun leVociFisseSulTabletSiLeggonoIntere() {
    show { AlbumHost { AlbumsTabScreen(tab.full(), tab.scenes.thumbnails.thumbnails, NoAlbumsTabHandlers) } }
    for (name in listOf("Preferiti", "Fotocamera", "Video", "Screenshot", "Messaggistica")) {
      val nodes = composeRule.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes()
      assertWithMessage("«$name» sul tablet").that(nodes).hasSize(1)
      val results = ArrayList<TextLayoutResult>()
      nodes.single().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
      assertThat(results.single().lineCount).isEqualTo(1)
      assertThat(results.single().isLineEllipsized(0)).isFalse()
    }
  }
}
