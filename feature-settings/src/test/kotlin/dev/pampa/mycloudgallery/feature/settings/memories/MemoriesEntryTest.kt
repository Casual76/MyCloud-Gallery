package dev.pampa.mycloudgallery.feature.settings.memories

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La voce "Ricordi e notifiche" delle Impostazioni (Fase 9, P9, docs/fase9-progetto.md §2.6, D15, D18): come sono le notifiche di questo dispositivo in una frase, il tono, la
 * pillola per cio' che chiede attenzione, il tocco. Mai un titolo, un luogo, un nome, un'ora o un numero (D33).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class MemoriesEntryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private fun subtitle(state: MemoriesEntryState?) = MemoriesEntryTexts.subtitleOf(context, state?.let { MemoriesEntrySummary(it) })

  @Test
  fun senzaLegaturaLaFraseDiceATantoServe() {
    assertThat(MemoriesEntryTexts.subtitleOf(context, null)).isEqualTo("Da scegliere")
  }

  @Test
  fun ogniStatoHaLaSuaFrase() {
    assertThat(subtitle(MemoriesEntryState.FEW_PER_WEEK)).isEqualTo("Poche")
    assertThat(subtitle(MemoriesEntryState.DAILY)).isEqualTo("Una al giorno")
    assertThat(subtitle(MemoriesEntryState.ANNIVERSARIES)).isEqualTo("Anniversari")
    assertThat(subtitle(MemoriesEntryState.OFF)).isEqualTo("Spente")
    assertThat(MemoriesEntryState.entries.map { subtitle(it) }).containsNoDuplicates()
  }

  @Test
  fun ilTonoDiceQuantoServeAttenzione() {
    assertThat(memoriesEntryTone(null)).isEqualTo(FluidTone.Primary)
    assertThat(memoriesEntryTone(MemoriesEntrySummary(MemoriesEntryState.FEW_PER_WEEK))).isEqualTo(FluidTone.Success)
    assertThat(memoriesEntryTone(MemoriesEntrySummary(MemoriesEntryState.DAILY, needsAttention = true))).isEqualTo(FluidTone.Warning)
    assertThat(memoriesEntryTone(MemoriesEntrySummary(MemoriesEntryState.OFF))).isEqualTo(FluidTone.Neutral)
  }

  @Test
  fun laRigaSiTocca_EConUnPermessoCheBloccaHaLaPillola() {
    var clicks = 0
    rule.setContent {
      MyCloudGalleryTheme { FluidListGroup { MemoriesEntryRow(MemoriesEntrySummary(MemoriesEntryState.FEW_PER_WEEK, needsAttention = true)) { clicks++ } } }
    }
    rule.onNodeWithText("Ricordi e notifiche").assertIsDisplayed()
    rule.onNodeWithText("Da sistemare").assertIsDisplayed()
    rule.onNodeWithText("Ricordi e notifiche").performClick()
    assertThat(clicks).isEqualTo(1)
  }

  @Test
  fun inUnoStatoTranquilloNonCeLaPillola() {
    rule.setContent { MyCloudGalleryTheme { FluidListGroup { MemoriesEntryRow(MemoriesEntrySummary(MemoriesEntryState.DAILY)) {} } } }
    rule.onNodeWithText("Ricordi e notifiche").assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Da sistemare").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun nessunaParolaSullaFamigliaNeUnNumeroNeUnOra() {
    val all = MemoriesEntryState.entries.map { subtitle(it) } + subtitle(null)
    for (phrase in all) {
      assertThat(phrase.lowercase()).doesNotContain("famigli")
      assertThat(phrase).doesNotContainMatch("[0-9]")
    }
  }
}
