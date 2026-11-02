package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesScreen
import dev.pampa.mycloudgallery.feature.memories.list.NoAllMemoriesHandlers
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageScreen
import dev.pampa.mycloudgallery.feature.memories.page.NoMemoryPageHandlers
import dev.pampa.mycloudgallery.feature.memories.player.NoPlayerHandlers
import dev.pampa.mycloudgallery.feature.memories.player.PlayerScreen
import dev.pampa.mycloudgallery.feature.memories.settings.MemoriesSettingsScreen
import dev.pampa.mycloudgallery.feature.memories.settings.NoMemoriesSettingsHandlers
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, E-AC9, E-MEM19, §2.7 "Ricordi e presentazione", D28): i ricordi letti come li legge TalkBack. Gli stati del
 * catalogo (pagina, "Tutti i ricordi", presentazione, impostazioni), a testo 1,0 e 2,0: nome e ruolo per ogni elemento attivo, bersagli da
 * 48 dp, nessun id o nome di file, testo non tagliato. La presentazione con TalkBack acceso offre Avanti e Indietro e non avanza da sola.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
internal class MemoriesAccessibilityTest : MemoriesScreenTest() {

  private val scenes by lazy { MemoriesScenes(rig) }
  private val names = MemoryNames(null, NoMemoryPlaceNames)
  private val thumbs get() = images.thumbnails

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  private fun page(ui: MemoryPageUi): @Composable () -> Unit = { MemoryPageScreen(ui, thumbs, NoMemoryPageHandlers, Modifier.fillMaxSize()) }

  private fun player(ui: PlayerUi): @Composable () -> Unit = { PlayerScreen(ui, thumbs, NoPlayerImages, NoPlayerClipSurface, NoPlayerHandlers) }

  private fun settings(ui: MemoriesSettingsUi): @Composable () -> Unit = { MemoriesSettingsScreen(ui, NoMemoriesSettingsHandlers) }

  /** La scala del testo di adesso: si cambia senza rimostrare la scena (`setContent` si puo' chiamare una volta per prova). */
  private val scale = mutableStateOf(1f)

  /** Mostra [content] e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena. */
  private fun clean(name: String, content: @Composable () -> Unit) {
    showAll {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) { content() }
    }
    for (value in listOf(1f, 2f)) {
      scale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config())
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  private fun all(ui: dev.pampa.mycloudgallery.feature.memories.contract.AllMemoriesUi): @Composable () -> Unit =
    { AllMemoriesScreen(ui, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize()) }

  // --- Le pagine --------------------------------------------------------------------------------------------------------------

  @Test fun paginaAnniFa() = clean("anni fa", page(scenes.pageOtd))

  @Test fun paginaViaggio() = clean("viaggio", page(scenes.pageTrip))

  @Test fun paginaPersona() = clean("persona", page(scenes.pagePerson))

  @Test fun paginaPrimoFotogramma() = clean("primo fotogramma", page(scenes.pageLoading))

  @Test fun paginaSparita() = clean("sparito", page(scenes.pageGone))

  @Test fun paginaConAnnulla() = clean("con annulla", page(scenes.pageUndo))

  @Test fun foglioMostraMeno() = clean("foglio mostra meno", page(scenes.sheetPerson))

  @Test fun foglioPeriodo() = clean("foglio periodo", page(scenes.sheetDays))

  @Test fun tuttiIRicordi() = clean("tutti i ricordi", all(scenes.listReady))

  @Test fun nessunRicordo() = clean("nessun ricordo", all(scenes.listEmpty))

  // --- La presentazione -------------------------------------------------------------------------------------------------------

  @Test fun presentazioneTitolo() = clean("titolo", player(scenes.playerIntro))

  @Test fun presentazioneFoto() = clean("foto", player(scenes.playerPhoto))

  @Test fun presentazionePausa() = clean("pausa", player(scenes.playerPaused))

  @Test fun presentazioneAnimazioniRidotte() = clean("animazioni ridotte", player(scenes.playerReduced))

  @Test fun presentazioneTalkBack() = clean("talkback", player(scenes.playerTalkBack))

  @Test fun presentazioneTalkBackTitolo() = clean("talkback titolo", player(scenes.playerTalkBackIntro))

  @Test fun presentazioneMusicaSparita() = clean("musica sparita", player(scenes.playerMusicMissing))

  @Test fun presentazioneFine() = clean("fine", player(scenes.playerEnded))

  @Test fun presentazioneSparita() = clean("sparita", player(scenes.playerGone))

  @Test fun presentazioneCaricamento() = clean("caricamento", player(scenes.playerLoading))

  // --- Le impostazioni --------------------------------------------------------------------------------------------------------

  @Test fun impostazioniComplete() = clean("impostazioni complete", settings(scenes.settingsFull))

  @Test fun impostazioniDiSerie() = clean("impostazioni di serie", settings(scenes.settingsDefault))

  @Test fun impostazioniPermessoNegato() = clean("permesso negato", settings(scenes.settingsDenied))

  @Test fun impostazioniCanaleSpento() = clean("canale spento", settings(scenes.settingsChannelOff))

  // --- La semantica attesa del §2.7 -------------------------------------------------------------------------------------------

  @Test
  fun conTalkBackLaPresentazioneHaAvantiEIndietro() {
    showAll(content = player(scenes.playerTalkBack))
    val labels = A11yAssertions.nodes(root()).flatMap(A11yAssertions::customActionLabels)
    assertThat(labels).hasSize(2)
    val names = A11yAssertions.nodes(root()).map(A11yAssertions::name)
    assertThat(names).contains("Foto successiva")
    assertThat(names).contains("Foto precedente")
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfPagina() {
    showAll(content = page(scenes.pageOtd))
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfPresentazioneInPausa() {
    showAll(content = player(scenes.playerPaused))
    composeRule.onRoot().checkRoboAccessibility()
  }
}

