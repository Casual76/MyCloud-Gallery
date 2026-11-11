package dev.pampa.mycloudgallery.feature.albums

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreview
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.CloseSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListHandlers
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListScreen
import dev.pampa.mycloudgallery.feature.albums.list.ListMove
import dev.pampa.mycloudgallery.feature.albums.list.MoveAlbumSheet
import dev.pampa.mycloudgallery.feature.albums.list.NoAlbumListHandlers
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageScreen
import dev.pampa.mycloudgallery.feature.albums.page.NoAlbumPageHandlers
import dev.pampa.mycloudgallery.feature.albums.page.NoSuggestionsHandlers
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionKind
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsHandlers
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsScreen
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsUi
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheet
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheetUi
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheet
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.testing.AlbumSamples
import dev.pampa.mycloudgallery.feature.albums.trips.NoTripsHandlers
import dev.pampa.mycloudgallery.feature.albums.trips.TripsScreen
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test

/**
 * Il comportamento e l'accessibilita' delle schermate degli album (Fase 7, P10): bersagli di almeno 48 dp, controlli che non si sovrappongono nemmeno a 1,3x,
 * azioni di TalkBack (Sposta su/giu', cella, apertura), conferma spenta finche' non si sceglie (Q5), conferma di condivisione solo dal tocco sul foglio,
 * dichiarazione dei permessi sempre visibile (L57), avviso dei nascosti (E-SH9). Su JVM con Robolectric.
 */
class AlbumsBehaviourTest : AlbumsComposeTest() {

  private val scenes = Scenes()

  private fun showAll(fontScale: Float? = null, dark: Boolean = false, content: @Composable () -> Unit) =
    show(dark = dark, fontScale = fontScale) { AlbumHost { content() } }

  // --- Bersagli e sovrapposizioni -------------------------------------------------------------------------------------------------

  /** I nodi su cui si tocca (con un'azione di click), senza lo sfondo oscurato dei fogli che copre tutto lo schermo. */
  private fun controls(): List<SemanticsNode> {
    val root = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
    val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    return root.filter { node ->
      val b = node.boundsInRoot
      // «Torna all'inizio» e' il titolo della barra di vetro dell'engine (FluidScreen): la sua area passa sotto i tasti della barra, che stanno sopra e vincono il tocco.
      b.width * b.height < screen.width * screen.height * 0.8f && b.width > 0f && b.height > 0f && label(node) != "Torna all'inizio"
    }
  }

  private fun label(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "?"

  private fun assertTouchTargets(scene: String) {
    val density = composeRule.density.density
    for (node in controls()) {
      val b = node.touchBoundsInRoot
      val w = b.width / density
      val h = b.height / density
      assertWithMessage("$scene: «${label(node)}» e' ${"%.1f".format(w)}x${"%.1f".format(h)} dp, meno di 48").that(minOf(w, h)).isAtLeast(47.5f)
    }
  }

  private fun overlap(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return if (w > 1f && h > 1f) w * h else 0f
  }

  private fun assertNoOverlappingControls(scene: String) {
    val nodes = controls()
    for (i in nodes.indices) for (j in i + 1 until nodes.size) {
      val area = overlap(nodes[i].boundsInRoot, nodes[j].boundsInRoot)
      assertWithMessage("$scene: «${label(nodes[i])}» e «${label(nodes[j])}» si sovrappongono").that(area).isEqualTo(0f)
    }
  }

  private fun listScene(): @Composable () -> Unit = { AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers) }

  private fun pageScene(id: AlbumId): @Composable () -> Unit = { AlbumPageScreen(scenes.pageUi(id), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers) }

  private fun tripsScene(): @Composable () -> Unit = {
    TripsScreen(TripsState.Proposals(listOf(AlbumSamples.trip, AlbumSamples.event), hasOlder = true), scenes.thumbnails.thumbnails, NoTripsHandlers)
  }

  private fun checkScene(name: String, fontScale: Float?, scene: @Composable () -> Unit) {
    showAll(fontScale = fontScale, content = scene)
    if (fontScale != null) assertNoOverlappingControls("$name 1,3x")
    assertTouchTargets(if (fontScale != null) "$name 1,3x" else name)
  }

  @Test fun elencoBersagliEControlli() = checkScene("elenco", null, listScene())

  @Test fun elencoBersagliEControlliATestoGrande() = checkScene("elenco", LARGE_TEXT, listScene())

  @Test fun paginaManualeBersagliEControlli() = checkScene("pagina manuale", null, pageScene(scenes.birthday))

  @Test fun paginaManualeBersagliEControlliATestoGrande() = checkScene("pagina manuale", LARGE_TEXT, pageScene(scenes.birthday))

  @Test fun paginaCondivisaBersagliEControlli() = checkScene("pagina condivisa", null, pageScene(scenes.osloShared))

  @Test fun paginaCondivisaBersagliEControlliATestoGrande() = checkScene("pagina condivisa", LARGE_TEXT, pageScene(scenes.osloShared))

  @Test fun viaggiBersagliEControlli() = checkScene("viaggi", null, tripsScene())

  @Test fun viaggiBersagliEControlliATestoGrande() = checkScene("viaggi", LARGE_TEXT, tripsScene())

  // --- Elenco: tocchi e TalkBack ----------------------------------------------------------------------------------------------------

  private class RecordingList : AlbumListHandlers by NoAlbumListHandlers {
    val opened = mutableListOf<Album>()
    var created = 0
    val moves = mutableListOf<Pair<AlbumId, ListMove>>()
    var sorted: AlbumListSort? = null
    val reorderMenus = mutableListOf<Album>()

    override fun onReorderMenu(album: Album) {
      reorderMenus += album
    }

    override fun onOpen(album: Album) {
      opened += album
    }

    override fun onCreate() {
      created++
    }

    override fun onMove(id: AlbumId, move: ListMove) {
      moves += id to move
    }

    override fun onSort(sort: AlbumListSort) {
      sorted = sort
    }
  }

  @Test
  fun toccareUnaSchedaApreQuellAlbumEIlPiuApreIlFoglioDiCreazione() {
    val handlers = RecordingList()
    showAll { AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, handlers) }
    composeRule.onNodeWithText("Compleanno di Anna", substring = true, useUnmergedTree = true).performClick()
    assertThat(handlers.opened.map { it.title }).containsExactly("Compleanno di Anna")
    composeRule.onNode(hasContentDescription("Nuovo album")).performClick()
    assertThat(handlers.created).isEqualTo(1)
  }

  @Test
  fun ogniSchedaDiceAlLettoreDiSchermoNomeContoEPresenzaDiCondivisione() {
    showAll { AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers) }
    val spoken = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") }
    val natale = spoken.single { it.startsWith("Natale in famiglia") }
    assertThat(natale).contains("Condiviso")
    assertThat(natale).contains("Marco")
    assertThat(spoken.single { it.startsWith("Compleanno di Anna") }).doesNotContain("Condiviso")
  }

  private fun cardNode(title: String): SemanticsNode =
    composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
      .first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.startsWith(title) == true }

  @Test
  fun conLOrdinePersonalizzatoOgniSchedaHaSpostaSuESpostaGiuPerTalkBack() {
    val handlers = RecordingList()
    showAll { AlbumListScreen(scenes.listUi(scenes.listReady(AlbumListSort.CUSTOM), reorderable = true), scenes.thumbnails.thumbnails, scenes.people, handlers) }
    val actions = cardNode("Compleanno di Anna").config.getOrNull(SemanticsActions.CustomActions).orEmpty()
    assertThat(actions.map { it.label }).containsExactly("Sposta su", "Sposta giù").inOrder()
    actions.first { it.label == "Sposta giù" }.action()
    assertThat(handlers.moves.map { it.second }).containsExactly(ListMove.DOWN)
  }

  @Test
  fun senzaLOrdinePersonalizzatoNonCiSonoAzioniDiSpostamento() {
    showAll { AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, RecordingList()) }
    assertThat(cardNode("Compleanno di Anna").config.getOrNull(SemanticsActions.CustomActions).orEmpty()).isEmpty()
  }

  @Test
  fun conLOrdinePersonalizzatoLaPressioneProlungataApreIlFoglioSposta_ECeLIndicazione() {
    val handlers = RecordingList()
    showAll { AlbumListScreen(scenes.listUi(scenes.listReady(AlbumListSort.CUSTOM), reorderable = true), scenes.thumbnails.thumbnails, scenes.people, handlers) }
    assertThat(texts("Tieni premuto un album")).isGreaterThan(0)
    composeRule.onNode(hasContentDescription("Compleanno di Anna", substring = true) and hasClickAction()).performTouchInput { longClick() }
    assertThat(handlers.reorderMenus.map { it.title }).containsExactly("Compleanno di Anna")
    // Un tocco semplice apre l'album, non il foglio.
    composeRule.onNode(hasContentDescription("Compleanno di Anna", substring = true) and hasClickAction()).performClick()
    assertThat(handlers.opened.map { it.title }).containsExactly("Compleanno di Anna")
  }

  @Test
  fun senzaLOrdinePersonalizzatoLaPressioneProlungataNonApreNienteENonCeLIndicazione() {
    val handlers = RecordingList()
    showAll { AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, handlers) }
    assertThat(texts("Tieni premuto un album")).isEqualTo(0)
    composeRule.onNode(hasContentDescription("Compleanno di Anna", substring = true) and hasClickAction()).performTouchInput { longClick() }
    assertThat(handlers.reorderMenus).isEmpty()
  }

  @Test
  fun ilFoglioSpostaHaDueRigheEQuellaCheNonSiPuoFareNonFaNiente() {
    val moves = mutableListOf<ListMove>()
    showAll { MoveAlbumSheet(title = "Da riempire", canMoveUp = false, canMoveDown = true, onMove = { moves += it }, onDismiss = {}) }
    // Il primo album non va piu' su: la riga non e' un controllo (nessuna azione di click), e quella che si puo' fare si.
    assertThat(isClickable("Sposta su")).isFalse()
    assertThat(isClickable("Sposta giù")).isTrue()
    assertThat(moves).isEmpty()
    clickText("Sposta giù")
    assertThat(moves).containsExactly(ListMove.DOWN)
  }

  // --- Pagina: la dichiarazione, le celle ---------------------------------------------------------------------------------------------

  private fun texts(substring: String): Int = composeRule.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes().size

  @Test
  fun suUnAlbumCondivisoLaDichiarazioneDeiPermessiCeSempre() {
    showAll { AlbumPageScreen(scenes.pageUi(scenes.osloShared), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers) }
    assertThat(texts("permessi li fa rispettare")).isGreaterThan(0)
  }

  @Test
  fun suUnAlbumPersonaleNonCeLaDichiarazioneDeiPermessi() {
    showAll { AlbumPageScreen(scenes.pageUi(scenes.birthday), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers) }
    assertThat(texts("permessi li fa rispettare")).isEqualTo(0)
  }

  @Test
  fun leCelleDellaPaginaDiconoTipoEGiornoEHannoLAzioneDelMenuPerTalkBack() {
    showAll { AlbumPageScreen(scenes.pageUi(scenes.birthday), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers) }
    val cells = composeRule.onAllNodes(hasContentDescription("Foto", substring = true)).fetchSemanticsNodes()
    assertThat(cells).isNotEmpty()
    // Mai il nome del file: tipo e giorno.
    for (cell in cells) assertThat(cell.config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString(" ")).doesNotContain(".jpg")
    val longClick = cells.first().config.getOrNull(SemanticsActions.OnLongClick)
    assertThat(longClick).isNotNull()
    assertThat(longClick!!.label).isNotEmpty()
  }

  // --- Foglio di condivisione --------------------------------------------------------------------------------------------------------

  private class RecordingShare : ShareSheetHandlers {
    var confirmed = 0
    var dismissed = 0
    var hidden: HiddenChoice? = null
    var level: ShareLevel? = null

    override fun onConfirm() {
      confirmed++
    }

    override fun onDismiss() {
      dismissed++
    }

    override fun onHiddenChoice(choice: HiddenChoice) {
      hidden = choice
    }

    override fun onLevel(level: ShareLevel) {
      this.level = level
    }
  }

  private fun shareSheet(state: ShareSheetState, handlers: ShareSheetHandlers): @Composable () -> Unit = {
    AlbumPageScreen(scenes.pageUi(scenes.osloShared), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers)
    ShareSheet(state, handlers)
  }

  @Test
  fun ilFoglioDiCondivisioneDiceIConteggiELaDichiarazioneEIlBottoneConfermaUnaVolta() {
    val handlers = RecordingShare()
    showAll(content = shareSheet(ShareSheetState.Plan(ShareFixtures.newAlbumPlan(hiddenLeftOut = 3), HiddenChoice.LEAVE_OUT, ShareLevel.ADD), handlers))
    assertThat(texts("permessi li fa rispettare")).isGreaterThan(0)
    assertThat(texts("Oslo 2026")).isGreaterThan(0)
    // «Lascia fuori» e' preselezionata, e non c'e' l'avviso dei nascosti che diventano visibili (E-SH9).
    assertThat(texts("diventeranno visibili")).isEqualTo(0)
    composeRule.onNodeWithText("Sposta e condividi", useUnmergedTree = true).performClick()
    assertThat(handlers.confirmed).isEqualTo(1)
    assertThat(handlers.dismissed).isEqualTo(0)
  }

  @Test
  fun scegliereIlLivelloEIDestinoDeiNascostiPassaDalFoglio() {
    val handlers = RecordingShare()
    showAll(content = shareSheet(ShareSheetState.Plan(ShareFixtures.newAlbumPlan(hiddenLeftOut = 3), HiddenChoice.LEAVE_OUT, ShareLevel.ADD), handlers))
    clickText("Condividi anche i nascosti")
    assertThat(handlers.hidden).isEqualTo(HiddenChoice.SHARE_TOO)
    clickText("Gli altri guardano")
    assertThat(handlers.level).isEqualTo(ShareLevel.VIEW)
  }

  @Test
  fun coiNascostiInclusiIlFoglioAvvisaCheDiventanoVisibiliAiFamiliari_E_SH9() {
    showAll(content = shareSheet(ShareSheetState.Plan(ShareFixtures.newAlbumPlan(hidden = HiddenChoice.SHARE_TOO, hiddenIn = 3), HiddenChoice.SHARE_TOO, ShareLevel.ADD), RecordingShare()))
    assertThat(texts("visibili")).isGreaterThan(0)
  }

  @Test
  fun arrivaDopoLeProveNonOffreLaConferma() {
    val handlers = RecordingShare()
    showAll(content = shareSheet(ShareSheetState.Blocked(ShareBlock.TEST_SHARES_ONLY), handlers))
    assertThat(texts("Arriva dopo le prove")).isGreaterThan(0)
    assertThat(texts("Sposta e condividi")).isEqualTo(0)
    assertThat(texts("Aggiungi all’album")).isEqualTo(0)
    assertThat(handlers.confirmed).isEqualTo(0)
  }

  @Test
  fun ilFoglioDiCondivisioneANessunStatoNonSiVede() {
    showAll(content = shareSheet(ShareSheetState.Started(dev.pampa.mycloudgallery.core.albums.contract.ShareOpId("op")), RecordingShare()))
    assertThat(texts("Sposta e condividi")).isEqualTo(0)
  }

  // --- Fogli di chiusura (Q5) --------------------------------------------------------------------------------------------------------

  private class RecordingClose : CloseSheetHandlers {
    var chosen: OthersAfterClose? = null
    var bringBack: Boolean? = null
    var confirmed = 0

    override fun onChooseOthers(choice: OthersAfterClose) {
      chosen = choice
    }

    override fun onBringBack(on: Boolean) {
      bringBack = on
    }

    override fun onConfirm() {
      confirmed++
    }

    override fun onDismiss() = Unit
  }

  private fun closeUi(mode: CloseMode, forOthers: OthersAfterClose?, bringBack: Boolean): CloseSheetUi {
    val candidates = (1..20).map { ShareFixtures.media(it) }
    return CloseSheetUi(
      CloseSheetState(scenes.osloShared, "Vacanza a Oslo", mode, forOthers, candidates.size, bringBack),
      ClosePreview(scenes.osloShared, "Vacanza a Oslo", mode, candidates, otherMembers = 2),
    )
  }

  private fun closeSheet(ui: CloseSheetUi, handlers: CloseSheetHandlers): @Composable () -> Unit = {
    AlbumPageScreen(scenes.pageUi(scenes.osloShared), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers)
    CloseSheet(ui, hidden = false, handlers = handlers)
  }

  @Test
  fun senzaUnaSceltaIlBottoneDiChiusuraNonFaNiente_Q5() {
    val handlers = RecordingClose()
    showAll(content = closeSheet(closeUi(CloseMode.STOP, null, false), handlers))
    // Ne' «Sparisce per gli altri» ne' «Ognuno tiene una copia» sono scelte di partenza.
    composeRule.onNodeWithText("Smetti di condividere", useUnmergedTree = true).performClick()
    assertThat(handlers.confirmed).isEqualTo(0)
    composeRule.onNodeWithText("Ognuno tiene una copia", useUnmergedTree = true).performClick()
    assertThat(handlers.chosen).isEqualTo(OthersAfterClose.KEEP_COPY)
  }

  @Test
  fun conUnaSceltaIlBottoneChiudeEConRiportaAvvisaCheLeCopiePerdonoQuelliRiportati() {
    val handlers = RecordingClose()
    showAll(content = closeSheet(closeUi(CloseMode.STOP, OthersAfterClose.KEEP_COPY, true), handlers))
    composeRule.onNodeWithText("Smetti di condividere", useUnmergedTree = true).performClick()
    assertThat(handlers.confirmed).isEqualTo(1)
    assertThat(texts("copie")).isGreaterThan(0)
    composeRule.onNodeWithText("Sparisce per gli altri", useUnmergedTree = true).performClick()
    assertThat(handlers.chosen).isEqualTo(OthersAfterClose.REMOVE)
  }

  @Test
  fun eliminareLAlbumHaIlSuoBottoneEIlSuoTitolo() {
    showAll(content = closeSheet(closeUi(CloseMode.DELETE, OthersAfterClose.REMOVE, false), RecordingClose()))
    assertThat(texts("Elimina l’album")).isGreaterThan(0)
    assertThat(texts("Smetti di condividere")).isEqualTo(0)
  }

  // --- Suggerite e Viaggi --------------------------------------------------------------------------------------------------------------

  private class RecordingSuggestions : SuggestionsHandlers by NoSuggestionsHandlers {
    var accepted = 0
    var excluded = 0
    var shared = 0
    var selecting = 0
    val opened = mutableListOf<MediaId>()

    override fun onAccept() {
      accepted++
    }

    override fun onExclude() {
      excluded++
    }

    override fun onShare() {
      shared++
    }

    override fun onToggleSelecting() {
      selecting++
    }

    override fun onOpen(media: MediaId) {
      opened += media
    }
  }

  private fun suggestionsUi(kind: SuggestionKind, selected: Int = 0): SuggestionsUi {
    val view = scenes.content(scenes.oslo).suggested!!
    val ids = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    return SuggestionsUi(scenes.album(scenes.oslo), kind, view, view.size, selected > 0, ids.take(selected).toSet(), loaded = true)
  }

  @Test
  fun leSuggeriteDiconoQuanteSiAccettanoEEsegonoSoloAlTocco() {
    val handlers = RecordingSuggestions()
    showAll { SuggestionsScreen(suggestionsUi(SuggestionKind.SUGGESTED), scenes.thumbnails.thumbnails, handlers) }
    assertThat(texts("Accetta tutte (12)")).isGreaterThan(0)
    assertThat(handlers.accepted).isEqualTo(0)
    composeRule.onNodeWithText("Accetta tutte (12)", useUnmergedTree = true).performClick()
    assertThat(handlers.accepted).isEqualTo(1)
    composeRule.onNodeWithText("Escludi tutte (12)", useUnmergedTree = true).performClick()
    assertThat(handlers.excluded).isEqualTo(1)
  }

  @Test
  fun conUnaSelezioneIBottoniDiconoQuanteSceltePrendono() {
    showAll { SuggestionsScreen(suggestionsUi(SuggestionKind.SUGGESTED, selected = 3), scenes.thumbnails.thumbnails, RecordingSuggestions()) }
    assertThat(texts("Accetta (3)")).isGreaterThan(0)
    assertThat(texts("Escludi (3)")).isGreaterThan(0)
  }

  @Test
  fun daCondividereApreIlFoglioDiConfermaEnonSpostaDaSolo() {
    val handlers = RecordingSuggestions()
    showAll { SuggestionsScreen(suggestionsUi(SuggestionKind.TO_SHARE), scenes.thumbnails.thumbnails, handlers) }
    composeRule.onNodeWithText("Condividi tutte (12)", useUnmergedTree = true).performClick()
    assertThat(handlers.shared).isEqualTo(1)
    assertThat(handlers.accepted).isEqualTo(0)
  }

  /** I4: «Crea album» sotto la scheda, la ✕ di «Non mi interessa» in alto sulla copertina; a 1,3x i due bersagli restano separati. */
  @Test
  fun iDueTastiDiUnaPropostaDiViaggioNonSiToccano() {
    showAll(fontScale = LARGE_TEXT) { TripsScreen(TripsState.Proposals(listOf(AlbumSamples.trip), hasOlder = false), scenes.thumbnails.thumbnails, NoTripsHandlers) }
    val create = composeRule.onNode(hasText("Crea album") and hasClickAction()).fetchSemanticsNode().boundsInRoot
    val dismiss = composeRule.onNode(hasContentDescription("Non mi interessa", substring = true) and hasClickAction()).fetchSemanticsNode().boundsInRoot
    assertThat(dismiss.bottom).isAtMost(create.top + 1f)
    assertThat(dismiss.right).isWithin(2f).of(create.right)
  }
}
