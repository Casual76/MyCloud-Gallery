package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTabletTest
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import org.junit.Test

/**
 * Il test dei bordi della 5B sul telefono (412 dp) e sul tablet (1280 dp), a 1x e a 1,3x: bersagli da 48 dp, niente fuori dallo schermo, niente
 * sopra un altro controllo. Le scene sono in [FiveBLayoutSuite], le stesse per i due dispositivi.
 */
class FiveBPhoneLayoutTest : ComposeBackupTallTest() {

  /** Un campo di testo ha il suo nome nell'etichetta sopra di lui (il campo dell'engine non la fonde con se'): qui basta che non sia senza nome. */
  private fun SemanticsNode.fieldAwareName(): String = name().ifEmpty { if (config.contains(SemanticsProperties.EditableText)) "campo di testo" else "" }

  private val harness = object : LayoutHarness {
    override fun mount(content: @Composable () -> Unit) = show(content = content)

    override fun fontScale(value: Float) = setFontScale(value)

    override fun onUi(block: () -> Unit) = composeRule.runOnIdle(block)

    override fun idle() = composeRule.waitForIdle()

    override fun clickables(): List<Pair<SemanticsNode, String>> = clickable().map { it to it.fieldAwareName() }

    override fun rootBounds(): Rect = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot

    override fun minTargetPx(): Float = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }

    override fun pxOf(dp: Float): Float = with(composeRule.density) { dp.dp.toPx() }
  }

  @Test
  fun liberaSpazioPagina() = FiveBLayoutSuite.freeSpacePage(harness)

  @Test
  fun liberaSpazioFogli() = FiveBLayoutSuite.freeSpaceSheets(harness)

  @Test
  fun eliminaSoloDalTelefono() = FiveBLayoutSuite.phoneOnlySheet(harness)

  @Test
  fun versioni() = FiveBLayoutSuite.versionsSheet(harness)

  @Test
  fun correggi() = FiveBLayoutSuite.correctionSheet(harness)

  @Test
  fun daDecidere() = FiveBLayoutSuite.pendingTasks(harness)

  @Test
  fun iPulsantiDiUnFoglioAlto640DpRestanoInVista() = FiveBLayoutSuite.biggestSheetsKeepTheirButtonsInView(harness)
}

/** Lo stesso sul tablet: le pagine restano nella colonna leggibile e i bordi non cambiano. */
class FiveBTabletLayoutTest : ComposeBackupTallTabletTest() {

  private fun SemanticsNode.fieldAwareName(): String = name().ifEmpty { if (config.contains(SemanticsProperties.EditableText)) "campo di testo" else "" }

  private val harness = object : LayoutHarness {
    override fun mount(content: @Composable () -> Unit) = show(content = content)

    override fun fontScale(value: Float) = setFontScale(value)

    override fun onUi(block: () -> Unit) = composeRule.runOnIdle(block)

    override fun idle() = composeRule.waitForIdle()

    override fun clickables(): List<Pair<SemanticsNode, String>> = clickable().map { it to it.fieldAwareName() }

    override fun rootBounds(): Rect = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot

    override fun minTargetPx(): Float = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }

    override fun pxOf(dp: Float): Float = with(composeRule.density) { dp.dp.toPx() }
  }

  @Test
  fun liberaSpazioPagina() = FiveBLayoutSuite.freeSpacePage(harness)

  @Test
  fun versioni() = FiveBLayoutSuite.versionsSheet(harness)

  @Test
  fun correggi() = FiveBLayoutSuite.correctionSheet(harness)

  @Test
  fun daDecidere() = FiveBLayoutSuite.pendingTasks(harness)
}
