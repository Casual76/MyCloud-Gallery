package dev.pampa.mycloudgallery.feature.settings.map

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * La voce "Mappa" delle Impostazioni (Fase 9, P4, docs/fase9-progetto.md §2.8, D24, **Q1 (c)**): lo stato della mappa dettagliata di questo dispositivo in una frase (accesa, spenta, non disponibile), il tono, il
 * tocco. Mai un luogo, una zona, un nome o un numero (D33); mai una parola sulla famiglia.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class MapEntryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private fun subtitle(state: MapEntryState?) = MapEntryTexts.subtitleOf(context, state?.let { MapEntrySummary(it) })

  @Test
  fun senzaLegaturaLaFraseDiceATantoServe() {
    assertThat(MapEntryTexts.subtitleOf(context, null)).isEqualTo("Da scegliere")
  }

  @Test
  fun ogniStatoHaLaSuaFrase() {
    assertThat(subtitle(MapEntryState.ON)).isEqualTo("Accesa")
    assertThat(subtitle(MapEntryState.OFF)).isEqualTo("Spenta")
    assertThat(subtitle(MapEntryState.UNAVAILABLE)).isEqualTo("Semplice")
    assertThat(MapEntryState.entries.map { subtitle(it) }).containsNoDuplicates()
  }

  @Test
  fun ilTonoNonUsaMaiIlGiallo_LaMappaAccesaEUnInformazioneNonUnAllarme() {
    assertThat(mapEntryTone(null)).isEqualTo(FluidTone.Info)
    assertThat(mapEntryTone(MapEntrySummary(MapEntryState.ON))).isEqualTo(FluidTone.Info)
    assertThat(mapEntryTone(MapEntrySummary(MapEntryState.OFF))).isEqualTo(FluidTone.Neutral)
    assertThat(mapEntryTone(MapEntrySummary(MapEntryState.UNAVAILABLE))).isEqualTo(FluidTone.Neutral)
  }

  @Test
  fun laRigaSiTocca() {
    var clicks = 0
    rule.setContent { MyCloudGalleryTheme { FluidListGroup { MapEntryRow(MapEntrySummary(MapEntryState.ON)) { clicks++ } } } }
    rule.onNodeWithText("Mappa").assertIsDisplayed()
    rule.onNodeWithText("Accesa").assertIsDisplayed()
    rule.onNodeWithText("Mappa").performClick()
    assertThat(clicks).isEqualTo(1)
  }

  @Test
  fun nessunaParolaSullaFamigliaNeUnNumeroNeUnLuogo() {
    val all = MapEntryState.entries.map { subtitle(it) } + subtitle(null)
    for (phrase in all) {
      assertThat(phrase.lowercase()).doesNotContain("famigli")
      assertThat(phrase.lowercase()).doesNotContain("casa")
      assertThat(phrase).doesNotContainMatch("[0-9]")
    }
  }
}
