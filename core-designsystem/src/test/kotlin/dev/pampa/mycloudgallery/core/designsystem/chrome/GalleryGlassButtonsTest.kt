package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I tasti di vetro del viewer (G3, B4): il tasto tondo che e' lo stesso per la chiusura e per il ⋮, e le voci del menu della galleria portate nel menu a goccia dell'engine.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryGlassButtonsTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun item(label: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit = {}) =
    GalleryMenuEntry.Item(label = label, onClick = onClick, icon = Icons.Rounded.Share, destructive = destructive, enabled = enabled)

  @Test
  fun leVociDiventanoLeVociDelMenuDellEngine_conLeStesseEtichetteIconeEStato() {
    var shared = 0
    val entries = listOf(
      GalleryMenuEntry.Item("Preferito", {}, Icons.Rounded.Favorite),
      item("Condividi", onClick = { shared++ }),
      item("Copia", enabled = false),
      GalleryMenuEntry.GroupBreak,
      GalleryMenuEntry.Item("Elimina…", {}, Icons.Rounded.Delete, destructive = true),
    )
    val actions = entries.toContextActions()
    assertThat(actions.map { it.label }).containsExactly("Preferito", "Condividi", "Copia", "Elimina…").inOrder()
    assertThat(actions.map { it.icon != null }).containsExactly(true, true, true, true).inOrder()
    assertThat(actions.map { it.enabled }).containsExactly(true, true, false, true).inOrder()
    assertThat(actions.map { it.destructive }).containsExactly(false, false, false, true).inOrder()
    // L'interruzione di gruppo e' l'inizio di una sezione sulla voce che segue, e solo su quella.
    assertThat(actions.map { it.startsSection }).containsExactly(false, false, false, true).inOrder()
    actions[1].onClick()
    assertThat(shared).isEqualTo(1)
  }

  @Test
  fun unaInterruzioneInTestaInCodaODoppiaNonFaUnaSezione() {
    val entries = listOf(
      GalleryMenuEntry.GroupBreak,
      item("A"),
      GalleryMenuEntry.GroupBreak,
      GalleryMenuEntry.GroupBreak,
      item("B"),
      item("C"),
      GalleryMenuEntry.GroupBreak,
    )
    val actions = entries.toContextActions()
    assertThat(actions.map { it.label }).containsExactly("A", "B", "C").inOrder()
    assertThat(actions.map { it.startsSection }).containsExactly(false, true, false).inOrder()
  }

  @Test
  fun ilMenuAGocciaStaNelloSchermoSoloSeLeRigheCi() {
    // Otto voci in tre gruppi, righe da 48 dp: 12 + 8 x 48 + 2 x 8 = 412 dp.
    val entries = buildList {
      repeat(3) { add(item("a$it")) }
      add(GalleryMenuEntry.GroupBreak)
      repeat(3) { add(item("b$it")) }
      add(GalleryMenuEntry.GroupBreak)
      repeat(2) { add(item("c$it")) }
    }
    assertThat(galleryMenuFits(entries, rowHeight = 48.dp, available = 412.dp)).isTrue()
    assertThat(galleryMenuFits(entries, rowHeight = 48.dp, available = 411.dp)).isFalse()
    // Col testo grande le righe crescono: lo stesso menu non ci sta piu'.
    assertThat(galleryMenuFits(entries, rowHeight = 72.dp, available = 412.dp)).isFalse()
    // Un menu vuoto sta sempre (e il pulsante non lo apre).
    assertThat(galleryMenuFits(emptyList(), rowHeight = 48.dp, available = 24.dp)).isTrue()
  }

  @Test
  fun ilTastoDiVetroHaNomeRuoloAzioneEUnBersaglioDa48() {
    var clicks = 0
    composeRule.setContent {
      MyCloudGalleryTheme {
        GalleryGlassIconButton(icon = Icons.Rounded.Favorite, contentDescription = "Preferito", onClick = { clicks++ })
      }
    }
    val node = composeRule.onNodeWithContentDescription("Preferito")
    node.assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
    val config = node.fetchSemanticsNode().config
    assertThat(config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
    assertThat(SemanticsActions.OnClick in config).isTrue()
    node.performClick()
    assertThat(clicks).isEqualTo(1)
  }
}
