package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.search.models.*
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class HrProbeScreenshotBase(private val dark: Boolean, private val mode: String, private val device: String) {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  @Test fun paginaVeraConMisuraFallimentoERiprova() {
    var retries = 0
    val handlers = object : SearchSettingsHandlers by NoSearchSettingsHandlers {
      override fun onRetryProbe() { retries++ }
    }
    val sample = SearchSamples.settingsIndexer
    val state = if (mode == "normale") sample else sample.copy(
      indexer = sample.indexer.copy(probe = null, nights = null),
      models = sample.models.copy(models = sample.models.models.map { row ->
        if (row.id == dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id)
          row.copy(state = dev.pampa.mycloudgallery.core.models.contract.ModelState.Ready(row.sizeBytes, dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind.NAS, 0), source = dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind.NAS) else row
      }),
    )
    rule.setSearchContent(dark = dark) {
      SearchSettingsScreen(state, mode == "misura", true, true, true, handlers, onBack = {}, probeFailed = mode == "errore" || mode == "occupato", probeHot = mode == "caldo", probeBusy = mode == "occupato")
    }
    // I2: la misura e' un segno con due parole; la frase intera di prima e' quella che dice TalkBack.
    fun said(sentence: String) = hasContentDescription(sentence, substring = true)
    if (mode == "caldo" || mode == "occupato") {
      rule.onNode(said(if (mode == "caldo") "Il telefono è caldo: misuro più tardi." else "La misura precedente deve ancora finire.")).performScrollTo()
      assertThat(rule.onAllNodesWithText("Riprova").fetchSemanticsNodes()).isEmpty()
    }
    if (mode == "misura") {
      rule.onNode(said("Misuro la velocit\u00e0 del telefono\u2026")).performScrollTo()
      assertThat(rule.onAllNodes(said("Misuro la velocit\u00e0 del telefono\u2026")).fetchSemanticsNodes()).hasSize(1)
    }
    if (mode == "errore") {
      rule.onNode(said("Non sono riuscito a misurare.")).performScrollTo()
      rule.onNodeWithText("Riprova").performScrollTo().performClick()
      assertThat(retries).isEqualTo(1)
    }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/HR/modelli-$mode-${if (dark) "scuro" else "chiaro"}-$device.png")
  }
  companion object {
    fun parameters() = listOf("normale", "misura", "errore", "caldo", "occupato").flatMap { mode -> listOf(arrayOf<Any>(false, mode), arrayOf<Any>(true, mode)) }
  }
}
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h780dp-xhdpi")
class HrProbePhoneScreenshotTest(dark: Boolean, mode: String) : HrProbeScreenshotBase(dark, mode, "telefono") {
  companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters fun data() = parameters() }
}
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h800dp-hdpi")
class HrProbeTabletScreenshotTest(dark: Boolean, mode: String) : HrProbeScreenshotBase(dark, mode, "tablet") {
  companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters fun data() = parameters() }
}
