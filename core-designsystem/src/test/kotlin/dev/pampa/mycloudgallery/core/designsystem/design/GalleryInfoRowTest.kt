package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CS-21 (fase I): la riga della «i». Un tocco sulla riga apre la frase sotto e un altro la richiude; TalkBack sente le due parole e il perche' come un
 * tasto solo, senza tocco, e la frase aperta non si rilegge.
 */
@RunWith(AndroidJUnit4::class)
class GalleryInfoRowTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun unToccoApreLaFraseEUnAltroLaRichiude() {
    composeRule.setContent { MyCloudGalleryTheme(settings = EngineSettings()) { GalleryInfoRow(label = "Restano 30 giorni", detail = "Poi il telefono li cancella.") } }
    val row = composeRule.onNodeWithTag(GalleryInfoRowTag)
    row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Restano 30 giorni. Poi il telefono li cancella.")))
    assertThat(composeRule.onNodeWithText("Poi il telefono li cancella.", useUnmergedTree = true).isDisplayedSafely()).isFalse()

    row.performClick()
    composeRule.onNodeWithText("Poi il telefono li cancella.", useUnmergedTree = true)
      .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))

    row.performClick()
    assertThat(composeRule.onNodeWithText("Poi il telefono li cancella.", useUnmergedTree = true).isDisplayedSafely()).isFalse()
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteraction.isDisplayedSafely(): Boolean =
    try {
      fetchSemanticsNode()
      true
    } catch (_: AssertionError) {
      false
    }
}
