package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La griglia vera su Robolectric: i budget di ricomposizione del §2.6, il pinch ancorato al dito, la selezione a
 * trascinamento con lo scorrimento automatico, l'ancora del viewer, lo scrubber per TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class MediaGridBehaviourTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val padding = PaddingValues(top = 100.dp, bottom = 120.dp)
  private var scope: CoroutineScope? = null

  private fun show(state: MediaGridState, onOpen: (MediaId) -> Unit = {}, withBackdrop: Boolean = false) {
    composeRule.showThemed {
      scope = rememberCoroutineScope()
      if (withBackdrop) {
        val backdrop = rememberGlassBackdrop()
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
          MediaGrid(state = state, contentPadding = padding, onOpen = onOpen, backdrop = backdrop)
        }
      } else {
        MediaGrid(state = state, contentPadding = padding, onOpen = onOpen)
      }
    }
  }

  /**
   * Una cella intera sullo schermo, nell'area dei contenuti, in una fila con almeno [column] + 1 celle: la piu' vicina al
   * centro, [rowFromCenter] file piu' in giu' (o piu' in su se negativo). Restituisce la posizione e il centro della cella.
   */
  private fun visibleCell(state: MediaGridState, rowFromCenter: Int = 0, column: Int = 1): Pair<Int, Offset> {
    val l = state.layout!!
    val v = state.viewport
    val top = state.contentTop(l)
    val rows = GridGeometry.rowsBetween(l, v.padTop.toFloat(), (v.height - v.padBottom).toFloat(), top, v)
      .filter { row ->
        val screenTop = GridGeometry.rowScreenTop(l, row, top, v)
        l.rowKind(row) == RowKind.CELLS && l.itemsOf(row).count() > column &&
          screenTop >= v.padTop && screenTop + l.rowHeight(row) <= v.height - v.padBottom
      }
    check(rows.isNotEmpty()) { "Nessuna fila con ${column + 1} celle sullo schermo." }
    val center = state.rowAtCenter(l)
    val sorted = rows.sortedBy { kotlin.math.abs(it - center) }
    val base = sorted.first()
    val row = rows.getOrNull(rows.indexOf(base) + rowFromCenter) ?: base
    val position = l.itemsOf(row).first + column
    val screenTop = GridGeometry.rowScreenTop(l, row, top, v)
    return position to Offset(l.itemLeft(position) + l.itemWidth(position) / 2f, screenTop + l.rowHeight(row) / 2f)
  }

  @Test
  fun unaMiniaturaCheArrivaNonRicomponeNienteEAggiornaSoloLaSuaCella() {
    val loader = ControlledThumbnailLoader()
    val (state, _) = syntheticGrid(count = 600, loader = loader)
    val probe = CountingProbe()
    state.probe = probe
    show(state)
    val (position, point) = visibleCell(state)
    val id = state.idAt(position)!!
    val key = loader.states.keys.first { it.mediaId == id }
    val loading = composeRule.onAllNodes(hasStateDescription("Anteprima in caricamento")).fetchSemanticsNodes()
    val rowsBefore = probe.snapshotRows()
    val cellsBefore = probe.snapshotCells()

    composeRule.runOnIdle { loader.deliver(key, ImageBitmap(8, 8)) }
    composeRule.waitForIdle()

    // Nessuna riga ricomposta, al piu' una cella (in pratica nessuna: la miniatura la legge il nodo della cella).
    assertThat(probe.snapshotRows()).isEqualTo(rowsBefore)
    val recomposedCells = probe.snapshotCells().filter { (p, n) -> n != (cellsBefore[p] ?: 0) }
    assertThat(recomposedCells.size).isAtMost(1)
    // Solo quella cella non e' piu' "in caricamento"; le altre (senza miniatura) lo sono ancora.
    val still = composeRule.onAllNodes(hasStateDescription("Anteprima in caricamento")).fetchSemanticsNodes()
    assertThat(still.size).isEqualTo(loading.size - 1)
    assertThat(still.none { it.boundsInRoot.contains(point) }).isTrue()
  }

  @Test
  fun scorrereDiUnaRigaNonRicomponeLeRigheFerme() {
    val (state, _) = syntheticGrid(count = 1_200)
    val probe = CountingProbe()
    state.probe = probe
    show(state)
    val l = state.layout!!
    val rowsBefore = probe.snapshotRows()
    val cellsBefore = probe.snapshotCells()
    val step = l.rowHeight(state.rowAtCenter(l)) + l.spec.gapPx

    composeRule.runOnIdle { scope!!.launch { state.listState.scrollBy(-step.toFloat()) } }
    composeRule.waitForIdle()

    val rowsAfter = probe.snapshotRows()
    for ((row, count) in rowsBefore) assertThat(rowsAfter[row]).isEqualTo(count)
    val cellsAfter = probe.snapshotCells()
    for ((cell, count) in cellsBefore) assertThat(cellsAfter[cell]).isEqualTo(count)
    // Una riga nuova e' entrata (sopra la sovrascansione), e solo le righe nuove si sono composte.
    assertThat(rowsAfter.keys - rowsBefore.keys).isNotEmpty()
  }

  @Test
  fun ilPinchTieneLElementoSottoIlDitoECambiaLivello() {
    val (state, _) = syntheticGrid(count = 2_000, level = GridLevel.DAYS)
    show(state)
    composeRule.runOnIdle { state.userScrolled = true; state.listState.requestScrollToItem(state.layout!!.rowCount / 2, 0) }
    composeRule.waitForIdle()
    val (position, focus) = visibleCell(state, rowFromCenter = 1, column = 2)
    val id = state.idAt(position)!!
    assertThat(state.positionAt(focus)).isEqualTo(position)

    val half = 160f
    val steps = 12
    composeRule.onRoot().performTouchInput {
      down(0, focus - Offset(half, 0f))
      down(1, focus + Offset(half, 0f))
      for (i in 1..steps) {
        // Le dita si avvicinano attorno allo stesso punto: il baricentro resta sul dito.
        val k = 1f - 0.45f * i / steps
        updatePointerTo(0, focus - Offset(half * k, 0f))
        updatePointerTo(1, focus + Offset(half * k, 0f))
        move()
      }
    }
    composeRule.waitForIdle()
    // A meta' gesto: il livello e' cambiato e la riga dell'elemento che era sotto il dito e' ancora sotto il dito.
    assertThat(state.level).isNotEqualTo(GridLevel.DAYS)
    assertRowUnderFinger(state, id, focus)

    composeRule.onRoot().performTouchInput {
      up(0)
      up(1)
    }
    composeRule.waitForIdle()
    // Assestato: scala 1, il livello mostrato e' quello scelto, e la riga e' ancora sotto il dito.
    assertThat(state.transform().isIdentity).isTrue()
    assertThat(state.layout!!.spec.level).isEqualTo(state.level)
    assertRowUnderFinger(state, id, focus)
  }

  /** I bordi (sullo schermo, trasformazione inclusa) della cella di [id] contengono l'ordinata del dito. */
  private fun assertRowUnderFinger(state: MediaGridState, id: MediaId, finger: Offset) {
    val bounds = state.viewerAnchor.boundsOf(id)
    assertThat(bounds).isNotNull()
    val y = finger.y + state.originInWindow.y
    assertThat(y).isAtLeast(bounds!!.top - 1f)
    assertThat(y).isAtMost(bounds.bottom + 1f)
  }

  @Test
  fun iSegmentiSaltanoDiLivelloRestandoAncorati() {
    val (state, _) = syntheticGrid(count = 2_000, level = GridLevel.DAYS)
    show(state)
    composeRule.runOnIdle { state.userScrolled = true; state.listState.requestScrollToItem(state.layout!!.rowCount / 2, 0) }
    composeRule.waitForIdle()
    // I segmenti si ancorano al centro dell'area dei contenuti: si porta una cella (non un'intestazione) al centro.
    // (Il centro esatto cade nello spazio fra due colonne: si guarda un filo a destra, la riga e' la stessa.)
    val center = state.viewportCenter()
    val probe = Offset(center.x + 24f, center.y)
    var tries = 0
    while (state.positionAt(probe) < 0 && tries++ < 40) {
      composeRule.runOnIdle { scope!!.launch { state.listState.scrollBy(-15f) } }
      composeRule.waitForIdle()
    }
    val before = state.idAt(state.positionAt(probe))
    composeRule.runOnIdle { scope!!.launch { state.selectLevel(GridLevel.MONTHS, reducedMotion = false) } }
    composeRule.waitForIdle()
    assertThat(state.layout!!.spec.level).isEqualTo(GridLevel.MONTHS)
    assertRowUnderFinger(state, before!!, center)
  }

  @Test
  fun inModalitaSelezioneUnTrascinamentoOrizzontaleSeleziona() {
    val (state, _) = syntheticGrid(count = 1_200)
    show(state)
    composeRule.runOnIdle { state.startSelecting() }
    composeRule.waitForIdle()
    val (fourth, fourthCenter) = visibleCell(state, column = 3)
    val l = state.layout!!
    val position = fourth - 3
    val width = l.itemWidth(position).toFloat()
    val start = Offset(l.itemLeft(position) + width / 2f, fourthCenter.y)
    composeRule.onRoot().performTouchInput {
      down(start)
      for (i in 1..10) moveTo(start + Offset(width * 2.4f * i / 10, 0f))
      up()
    }
    composeRule.waitForIdle()
    val selected = state.selection.selected.value
    assertThat(selected.size).isEqualTo(3)
    assertThat(state.idAt(position)!! in selected).isTrue()
  }

  @Test
  fun pressioneLungaETrascinamentoAlBordoScorronoESelezionano() {
    val (state, _) = syntheticGrid(count = 1_200)
    show(state)
    val (_, start) = visibleCell(state, rowFromCenter = 2)
    val firstBefore = state.listState.firstVisibleItemIndex
    val v = state.viewport
    composeRule.onRoot().performTouchInput {
      down(start)
      advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
      move()
      // Il dito va nella fascia del bordo alto (64 dp) e resta li': la griglia scorre verso i piu' vecchi.
      for (i in 1..8) moveTo(Offset(start.x, start.y + (v.padTop + 20f - start.y) * i / 8))
      repeat(10) {
        advanceEventTime(80)
        move()
      }
      up()
    }
    composeRule.waitForIdle()
    assertThat(state.selecting).isTrue()
    assertThat(state.listState.firstVisibleItemIndex).isLessThan(firstBefore)
    assertThat(state.selection.selected.value.size).isGreaterThan(4)
  }

  @Test
  fun unToccoApreLElementoGiusto() {
    val (state, _) = syntheticGrid(count = 600)
    val opened = mutableListOf<MediaId>()
    show(state, onOpen = { opened += it })
    val (position, point) = visibleCell(state, column = 1)
    composeRule.onRoot().performTouchInput { click(point) }
    composeRule.waitForIdle()
    assertThat(opened).containsExactly(state.idAt(position))
  }

  @Test
  fun lAncoraDelViewerConosceIBordiEPortaInVista() {
    val (state, _) = syntheticGrid(count = 1_200)
    show(state)
    val cells = composeRule.onAllNodesWithContentDescription("Foto, ", substring = true).fetchSemanticsNodes()
    assertThat(cells).isNotEmpty()
    val node = cells[cells.size / 2]
    val nodeBounds = node.boundsInWindow
    val position = state.positionAt(nodeBounds.center - state.originInWindow)
    val id = state.idAt(position)!!
    val bounds = state.viewerAnchor.boundsOf(id)!!
    assertThat(bounds.left).isWithin(1.5f).of(nodeBounds.left)
    assertThat(bounds.top).isWithin(1.5f).of(nodeBounds.top)
    assertThat(bounds.right).isWithin(1.5f).of(nodeBounds.right)
    assertThat(bounds.bottom).isWithin(1.5f).of(nodeBounds.bottom)

    // Il piu' vecchio sta in cima, lontanissimo: "porta in vista" lo riporta al centro.
    val oldest = state.idAt(0)!!
    assertThat(state.viewerAnchor.boundsOf(oldest)).isNull()
    composeRule.runOnIdle { scope!!.launch { state.viewerAnchor.reveal(oldest) } }
    composeRule.waitForIdle()
    val revealed = state.viewerAnchor.boundsOf(oldest)
    assertThat(revealed).isNotNull()
  }

  @Test
  fun leDescrizioniSonoQuelleCheIlLettoreDiSchermoECollaudoAspettano() {
    val (state, _) = syntheticGrid(count = 600)
    show(state)
    val pattern = Regex("^(Foto|Video|Foto in movimento), \\d{1,2} \\p{L}+ \\d{4}.*", RegexOption.DOT_MATCHES_ALL)
    val nodes = composeRule.onAllNodesWithContentDescription(", ", substring = true).fetchSemanticsNodes()
    val descriptions = nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
      .filter { it.startsWith("Foto") || it.startsWith("Video") }
    assertThat(descriptions).isNotEmpty()
    descriptions.forEach { assertThat(it).matches(pattern.toPattern()) }
    // Le azioni per TalkBack: "Apri" e "Seleziona".
    val config = nodes.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Foto") == true }.config
    assertThat(config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo("Apri")
    assertThat(config.getOrNull(SemanticsActions.OnLongClick)?.label).isEqualTo("Seleziona")
  }

  @Test
  fun loScrubberPerTalkBackEUnCursoreCheSalta() {
    val (state, _) = syntheticGrid(count = 3_000)
    show(state, withBackdrop = true)
    val scrubber = composeRule.onNodeWithContentDescription("Scorri la timeline")
    val info = scrubber.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
    assertThat(info.current).isWithin(0.01f).of(1f)
    scrubber.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
    composeRule.waitForIdle()
    assertThat(state.listState.firstVisibleItemIndex).isAtMost(1)
    val label = scrubber.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    val first = state.layout!!.sections.first().firstMonth
    assertThat(label).isEqualTo(GridText.month(first))
  }

  /**
   * Segnalazione di P10: passare da una vista all'altra (una Raccolta filtrata, un filtro) con un layout piu' corto, o
   * dare alla griglia un altro stato nello stesso posto, mentre l'intestazione agganciata e' in vista: nessuna lettura
   * deve usare righe calcolate sul layout vecchio.
   */
  @Test
  fun cambiareVistaOStatoConLIntestazioneAgganciataNonUsaRigheVecchie() {
    val (first, library) = syntheticGrid(count = 2_000)
    var current by mutableStateOf(first)
    composeRule.showThemed {
      scope = rememberCoroutineScope()
      val backdrop = rememberGlassBackdrop()
      Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
        MediaGrid(state = current, contentPadding = padding, backdrop = backdrop)
      }
    }
    composeRule.runOnIdle {
      first.userScrolled = true
      first.listState.requestScrollToItem(first.layout!!.rowCount - 40, 0)
    }
    composeRule.waitForIdle()

    // 1. La stessa griglia riceve una vista molto piu' corta (un filtro): righe e layout cambiano sotto l'intestazione.
    composeRule.runOnIdle { first.view = library.view(LibraryFilter.FAVORITES) }
    composeRule.waitForIdle()
    assertThat(first.layout!!.itemCount).isEqualTo(library.view(LibraryFilter.FAVORITES).size)
    composeRule.runOnIdle { scope!!.launch { first.listState.scrollBy(-300f) } }
    composeRule.waitForIdle()

    // 2. Un altro stato, con un'altra vista corta, nello stesso posto della composizione.
    val (second, _) = syntheticGrid(count = 150)
    composeRule.runOnIdle { current = second }
    composeRule.waitForIdle()
    assertThat(second.layout).isNotNull()
    composeRule.runOnIdle { scope!!.launch { second.listState.scrollBy(-200f) } }
    composeRule.waitForIdle()

    // 3. E indietro alla vista lunga, con un salto di livello in mezzo.
    composeRule.runOnIdle { current = first }
    composeRule.waitForIdle()
    composeRule.runOnIdle {
      first.view = library.view(LibraryFilter.ALL)
      scope!!.launch { first.selectLevel(GridLevel.YEARS, reducedMotion = false) }
    }
    composeRule.waitForIdle()
    assertThat(first.layout!!.spec.level).isEqualTo(GridLevel.YEARS)
  }

  @Test
  fun siApreInFondoSuiPiuRecenti() {
    val (state, _) = syntheticGrid(count = 1_200)
    val opened = mutableListOf<MediaId>()
    show(state, onOpen = { opened += it })
    assertThat(state.listState.canScrollForward).isFalse()
    assertThat(state.listState.canScrollBackward).isTrue()
    // La prima cella dell'albero di accessibilita' e' sullo schermo, e toccarla apre.
    composeRule.onAllNodesWithContentDescription("Foto, ", substring = true)[0].performClick()
    composeRule.waitForIdle()
    assertThat(opened).hasSize(1)
  }
}
