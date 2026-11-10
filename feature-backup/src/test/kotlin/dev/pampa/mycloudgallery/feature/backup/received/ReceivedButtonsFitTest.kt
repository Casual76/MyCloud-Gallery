package dev.pampa.mycloudgallery.feature.backup.received

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.backup.BackupComposeBase
import dev.pampa.mycloudgallery.feature.backup.LARGE_TEXT
import dev.pampa.mycloudgallery.feature.backup.testing.ReceivedSamples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K4: "Riprova" e "Scarta" nella scheda di un file ricevuto, sul telefono dell'utente (360 dp, una pagina alta) e con il testo grande. Accanto alla
 * miniatura la colonna dei tasti e' di ~200 dp: "Riprova" andava a capo a meta' parola ("Riprov/a"). Adesso i tasti stanno **interi**, su una riga se
 * entrano o uno sotto l'altro, e non si sovrappongono mai.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h1700dp-xhdpi")
class ReceivedButtonsFitTest : BackupComposeBase() {

  private val items = listOf(
    ReceivedSamples.item(1, ReceivedStatus.COPY_CHANGED, folder = "Ricevute"),
    ReceivedSamples.item(2, ReceivedStatus.NETWORK, folder = "Viaggi/Estate 2026"),
  )

  private fun showList(fontScale: Float? = null) =
    show(fontScale = fontScale) { ReceivedScreen(state = ReceivedUi(items, null, null), actions = ReceivedActions(), onBack = {}) }

  /** Quante righe scrive ogni "[label]" sullo schermo (nell'albero non unito: il nodo del solo testo). */
  private fun lineCounts(label: String): List<Int> =
    composeRule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
      val results = ArrayList<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
      results.single().lineCount
    }

  private fun assertWholeWords(scale: String) {
    for (label in listOf("Riprova", "Scarta")) {
      val counts = lineCounts(label)
      assertThat(counts).hasSize(2)
      assertWithMessage("\"$label\" a 360 dp ($scale) va a capo: $counts").that(counts.all { it == 1 }).isTrue()
    }
  }

  private fun assertNoOverlap() {
    // I soli tasti delle schede ("Riprova: Ricevute", "Scarta: ..."): la freccia "indietro" della chrome e' toccabile anche lei.
    val buttons = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
      .filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { it.startsWith("Riprova: ") || it.startsWith("Scarta: ") } == true }
      .map { it.boundsInRoot }
    assertThat(buttons).hasSize(4)
    for (i in buttons.indices) for (j in i + 1 until buttons.size) {
      assertWithMessage("i tasti $i e $j si sovrappongono: ${buttons[i]} / ${buttons[j]}").that(buttons[i].overlaps(buttons[j])).isFalse()
    }
  }

  @Test
  fun iTastiStannoInteriA360() {
    showList()
    assertWholeWords("testo normale")
    assertNoOverlap()
  }

  @Test
  fun iTastiStannoInteriA360ConIlTestoGrande() {
    showList(LARGE_TEXT)
    assertWholeWords("testo grande")
    assertNoOverlap()
  }
}
