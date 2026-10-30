package dev.pampa.mycloudgallery.feature.settings.people

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
 * La voce "Persone" delle Impostazioni (Fase 8, P7, docs/fase8-progetto.md §1.3, §2.1): lo stato di questo dispositivo in una frase, il tono, la pillola per cio'
 * che chiede attenzione, il tocco. Mai nomi ne' conteggi (D37) e nessuna parola sulla famiglia (Q3).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class PeopleEntryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private fun subtitle(state: PeopleEntryState?) = PeopleEntryTexts.subtitleOf(context, state?.let { PeopleEntrySummary(it) })

  @Test
  fun senzaLegaturaLaFraseDiceATantoServe() {
    assertThat(PeopleEntryTexts.subtitleOf(context, null)).isEqualTo("Raggruppa le foto per volto. Si attiva per il tuo account, dopo una pagina che spiega cosa fa.")
  }

  @Test
  fun ogniStatoHaLaSuaFrase() {
    assertThat(subtitle(PeopleEntryState.OFF)).isEqualTo("Raggruppa le foto per volto. Si attiva per il tuo account, dopo una pagina che spiega cosa fa.")
    assertThat(subtitle(PeopleEntryState.ON)).isEqualTo("Attiva sul tuo account e su questo dispositivo")
    assertThat(subtitle(PeopleEntryState.LOCAL_ONLY)).isEqualTo("Attiva, ma ogni dispositivo ha le sue persone")
    assertThat(subtitle(PeopleEntryState.AWAITING_NOTICE)).isEqualTo("Attiva sul tuo account: qui non lavora finché non confermi")
    assertThat(subtitle(PeopleEntryState.EXCLUDED_HERE)).isEqualTo("Non attiva su questo dispositivo")
    assertThat(subtitle(PeopleEntryState.ERASING)).isEqualTo("Cancellazione in corso")
    assertThat(subtitle(PeopleEntryState.NEEDS_UPDATE)).isEqualTo("Serve aggiornare l’app")
    // Ogni stato dell'enum ha una frase diversa dalle altre, tranne "spenta" e "non legata" che dicono la stessa cosa.
    val phrases = PeopleEntryState.entries.map { subtitle(it) }
    assertThat(phrases).containsNoDuplicates()
  }

  @Test
  fun ilTonoDiceQuantoServeAttenzione() {
    assertThat(peopleEntryTone(null)).isEqualTo(FluidTone.Primary)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.OFF))).isEqualTo(FluidTone.Primary)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.ON))).isEqualTo(FluidTone.Success)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.LOCAL_ONLY))).isEqualTo(FluidTone.Info)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.AWAITING_NOTICE))).isEqualTo(FluidTone.Warning)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.NEEDS_UPDATE))).isEqualTo(FluidTone.Warning)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.ERASING))).isEqualTo(FluidTone.Warning)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.EXCLUDED_HERE))).isEqualTo(FluidTone.Neutral)
  }

  @Test
  fun laRigaSiTocca_EConLAvvisoDaVedereHaLaPillola() {
    var clicks = 0
    rule.setContent {
      MyCloudGalleryTheme {
        FluidListGroup { PeopleEntryRow(PeopleEntrySummary(PeopleEntryState.AWAITING_NOTICE)) { clicks++ } }
      }
    }
    rule.onNodeWithText("Persone").assertIsDisplayed()
    rule.onNodeWithText("Da confermare").assertIsDisplayed()
    rule.onNodeWithText("Persone").performClick()
    assertThat(clicks).isEqualTo(1)
  }

  @Test
  fun conLAppDaAggiornareLaPillolaDiceAggiorna() {
    rule.setContent { MyCloudGalleryTheme { FluidListGroup { PeopleEntryRow(PeopleEntrySummary(PeopleEntryState.NEEDS_UPDATE)) {} } } }
    rule.onNodeWithText("Aggiorna").assertIsDisplayed()
  }

  @Test
  fun inUnoStatoTranquilloNonCeLaPillola() {
    rule.setContent { MyCloudGalleryTheme { FluidListGroup { PeopleEntryRow(PeopleEntrySummary(PeopleEntryState.ON)) {} } } }
    rule.onNodeWithText("Persone").assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Da confermare").fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("Aggiorna").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun nessunaParolaSullaFamigliaNeUnNumero() {
    val all = PeopleEntryState.entries.map { subtitle(it) } + subtitle(null)
    for (phrase in all) {
      assertThat(phrase.lowercase()).doesNotContain("famigli")
      assertThat(phrase).doesNotContainMatch("[0-9]")
    }
  }

  /**
   * L350 (correzioni C): prima che le persone siano montate la voce non dice «spenta» a chi le ha accese: la sua frase non nomina nessuno stato, il tono e' neutro e non
   * c'e' pillola. E non e' la frase di «spenta» (che spiega come si attiva).
   */
  @Test
  fun l350_primaDelMontaggioLaVoceNonDiceNeSpentaNeAttiva() {
    val phrase = subtitle(PeopleEntryState.NOT_YET)
    assertThat(phrase).isEqualTo("Raggruppa le foto per volto")
    assertThat(phrase).isNotEqualTo(subtitle(PeopleEntryState.OFF))
    for (state in listOf("spenta", "attiva", "si attiva", "confermi", "cancellazione", "aggiornare")) assertThat(phrase.lowercase()).doesNotContain(state)
    assertThat(peopleEntryTone(PeopleEntrySummary(PeopleEntryState.NOT_YET))).isEqualTo(FluidTone.Neutral)

    rule.setContent { MyCloudGalleryTheme { FluidListGroup { PeopleEntryRow(PeopleEntrySummary(PeopleEntryState.NOT_YET)) {} } } }
    rule.onNodeWithText("Persone").assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Da confermare").fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("Aggiorna").fetchSemanticsNodes()).isEmpty()
  }
}
