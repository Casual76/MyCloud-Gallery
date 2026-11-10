package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryRows
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerImages
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesScreen
import dev.pampa.mycloudgallery.feature.memories.list.NoAllMemoriesHandlers
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageScreen
import dev.pampa.mycloudgallery.feature.memories.page.NoMemoryPageHandlers
import dev.pampa.mycloudgallery.feature.memories.player.NoPlayerHandlers
import dev.pampa.mycloudgallery.feature.memories.player.PlayerScreen
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionSources
import dev.pampa.mycloudgallery.feature.memories.settings.MemoriesSettingsScreen
import dev.pampa.mycloudgallery.feature.memories.settings.NoMemoriesSettingsHandlers
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot dei ricordi (Fase 9, P9): **ogni stato del §1.5** -- la sezione delle Raccolte (pronta, vuota, libreria non pronta, in generazione), la pagina del
 * ricordo (anni fa, viaggio con "Apri l'album", persona, primo fotogramma, "non c'e' piu'", non trovato, con "Annulla", i due fogli), "Tutti i ricordi" (con e senza
 * copertine, vuoto), la presentazione (titolo, foto, video, pausa, animazioni ridotte, TalkBack, avviso della musica, carta finale, "non c'e' piu'") e le Impostazioni
 * (di serie, complete, senza persone, con permesso chiedibile, negato e canale spento, brani). Chiaro e scuro, telefono (360 dp) e tablet (1280 dp), piu' il testo a
 * 1,3x sul telefono. **Nessuna foto vera**: gradienti; titoli, luoghi e nomi inventati.
 *
 * `gradlew :feature-memories:recordRoborazziDebug --tests "*MemoriesScreenshot*"` scrive i PNG in `docs/screenshots/9-P9/`. Nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano e dicano le cose giuste.
 */
internal abstract class MemoriesShots(private val dark: Boolean, private val device: String) : MemoriesScreenTest() {

  private val scenes by lazy { MemoriesScenes(rig) }
  private val names = MemoryNames(null, NoMemoryPlaceNames)
  private val thumbs get() = images.thumbnails
  private val look: String get() = if (dark) "scuro" else "chiaro"

  /** [scrollTo]: una pagina piu' lunga dello schermo si fotografa dov'e' [scrollTo] (la lista e' pigra: quello che sta sotto non e' composto). */
  private fun shoot(name: String, vararg expect: String, fontScale: Float? = null, scrollTo: String? = null, content: @Composable () -> Unit) {
    showAll(dark = dark, fontScale = fontScale, content = content)
    if (scrollTo != null) {
      composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(scrollTo, substring = true))
      composeRule.waitForIdle()
    }
    for (text in expect) assertShows(name, text)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    if (ScreenshotPolicy.capture(name, device, fontScale)) composeRule.onRoot().captureRoboImage("../docs/screenshots/9-P9/$name-$look-$device$suffix.png")
  }

  @Composable
  private fun SectionScene(state: MemoriesSectionState) {
    val section = MemoriesSectionSources.sectionOf(LocalContext.current.resources, state, names)
    val group = CollectionEntryGroup(
      title = section.title, entries = section.entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
      code = section.code, hideable = section.hideable, hint = section.hint,
    )
    FluidScreen(title = "Raccolte", modifier = Modifier.fillMaxSize()) {
      item { FluidSectionHeader(title = section.title) }
      item { CollectionEntryRows(group, selectedCode = null, twoPane = false, onSelect = {}) }
    }
  }

  private fun page(ui: dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi): @Composable () -> Unit = { MemoryPageScreen(ui, thumbs, NoMemoryPageHandlers, Modifier.fillMaxSize()) }

  private fun player(ui: dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi): @Composable () -> Unit =
    { PlayerScreen(ui, thumbs, NoPlayerImages, NoPlayerClipSurface, NoPlayerHandlers) }

  private fun settings(ui: dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi): @Composable () -> Unit = { MemoriesSettingsScreen(ui, NoMemoriesSettingsHandlers) }

  // --- Sezione ---------------------------------------------------------------------------------------------------------------------

  @Test fun sezionePronta() = shoot("sezione-pronta", "Un anno fa", "Tutti i ricordi") { SectionScene(scenes.sectionReady) }

  @Test fun sezioneProntaTestoGrande() = shoot("sezione-pronta", "Un anno fa", fontScale = LARGE_TEXT) { SectionScene(scenes.sectionReady) }

  @Test fun sezioneVuota() = shoot("sezione-vuota", "I ricordi arrivano quando ci sono abbastanza foto") { SectionScene(scenes.sectionEmpty) }

  @Test fun sezioneLibreriaNonPronta() = shoot("sezione-libreria", "Aspetto la tua libreria") { SectionScene(scenes.sectionLibrary) }

  @Test fun sezioneInGenerazione() = shoot("sezione-generazione", "Sto preparando i ricordi") { SectionScene(scenes.sectionWorking) }

  // --- Pagina del ricordo --------------------------------------------------------------------------------------------------------------

  @Test fun paginaAnniFa() = shoot("pagina-anni-fa", "Un anno fa", "Riproduci", content = page(scenes.pageOtd))

  @Test fun paginaAnniFaTestoGrande() = shoot("pagina-anni-fa", "Riproduci", fontScale = LARGE_TEXT, content = page(scenes.pageOtd))

  @Test fun paginaViaggio() = shoot("pagina-viaggio", "Citta' di prova, marzo 2025", "Riproduci", content = page(scenes.pageTrip))

  @Test fun paginaPersona() = shoot("pagina-persona", "Persona di prova nel 2025", content = page(scenes.pagePerson))

  @Test fun paginaPrimoFotogramma() = shoot("pagina-primo-fotogramma", "Un anno fa", content = page(scenes.pageLoading))

  @Test fun paginaSparita() = shoot("pagina-sparita", "Questo ricordo non c’è più", content = page(scenes.pageGone))

  @Test fun paginaNonTrovata() = shoot("pagina-non-trovata", "Non trovo questo ricordo", content = page(scenes.pageNotFound))

  @Test fun paginaConAnnulla() = shoot("pagina-annulla", "Non lo vedrai più nei ricordi.", content = page(scenes.pageUndo))

  @Test fun foglioMostraMeno() = shoot("foglio-mostra-meno", "Mostra meno Persona di prova", "Mai nei ricordi", content = page(scenes.sheetPerson))

  @Test fun foglioMostraMenoTestoGrande() = shoot("foglio-mostra-meno", "Mai nei ricordi", fontScale = LARGE_TEXT, content = page(scenes.sheetPerson))

  @Test fun foglioPeriodo() = shoot("foglio-periodo", "Non ricordarmi questo periodo", "Solo un giorno", content = page(scenes.sheetDays))

  @Test fun foglioPeriodoTestoGrande() = shoot("foglio-periodo", "Solo un giorno", fontScale = LARGE_TEXT, content = page(scenes.sheetDays))

  // --- Tutti i ricordi -------------------------------------------------------------------------------------------------------------

  @Test fun tuttiIRicordi() = shoot("tutti-i-ricordi", "NOVEMBRE 2026", "Un anno fa") {
    AllMemoriesScreen(scenes.listReady, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize())
  }

  @Test fun tuttiIRicordiTestoGrande() = shoot("tutti-i-ricordi", "Un anno fa", fontScale = LARGE_TEXT) {
    AllMemoriesScreen(scenes.listReady, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize())
  }

  @Test fun tuttiIRicordiSenzaCopertine() = shoot("tutti-i-ricordi-primo-fotogramma", "Un anno fa") {
    AllMemoriesScreen(scenes.listNoCovers, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize())
  }

  @Test fun tuttiIRicordiVuoto() = shoot("tutti-i-ricordi-vuoto", "Ancora nessun ricordo") {
    AllMemoriesScreen(scenes.listEmpty, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize())
  }

  // --- Presentazione ---------------------------------------------------------------------------------------------------------------

  @Test fun presentazioneTitolo() = shoot("presentazione-titolo", "Un anno fa", content = player(scenes.playerIntro))

  @Test fun presentazioneFoto() = shoot("presentazione-foto", "Chiudi", content = player(scenes.playerPhoto))

  @Test fun presentazioneVideo() = shoot("presentazione-video", "Chiudi", content = player(scenes.playerVideo))

  @Test fun presentazionePausa() = shoot("presentazione-pausa", "Riproduci", "Avanti", content = player(scenes.playerPaused))

  @Test fun presentazionePausaTestoGrande() = shoot("presentazione-pausa", "Riproduci", fontScale = LARGE_TEXT, content = player(scenes.playerPaused))

  @Test fun presentazioneAnimazioniRidotte() = shoot("presentazione-animazioni-ridotte", "Chiudi", content = player(scenes.playerReduced))

  @Test fun presentazioneTalkBack() = shoot("presentazione-talkback", "Avanti", content = player(scenes.playerTalkBack))

  @Test fun presentazioneTalkBackTitolo() = shoot("presentazione-talkback-titolo", "Tocca per cominciare", content = player(scenes.playerTalkBackIntro))

  @Test fun presentazioneMusicaSparita() = shoot("presentazione-musica-sparita", "La canzone che avevi scelto non c’è più: la presentazione resta muta.", content = player(scenes.playerMusicMissing))

  @Test fun presentazioneFine() = shoot("presentazione-fine", "Fine del ricordo", "Rivedi", content = player(scenes.playerEnded))

  @Test fun presentazioneFineTestoGrande() = shoot("presentazione-fine", "Rivedi", fontScale = LARGE_TEXT, content = player(scenes.playerEnded))

  @Test fun presentazioneSparita() = shoot("presentazione-sparita", "Questo ricordo non c’è più", content = player(scenes.playerGone))

  @Test fun presentazioneCaricamento() = shoot("presentazione-caricamento", "Sto preparando la presentazione", content = player(scenes.playerLoading))

  // --- Impostazioni ----------------------------------------------------------------------------------------------------------------

  @Test fun impostazioni() = shoot("impostazioni", "Ricordi e notifiche", "Ricordi con le persone", content = settings(scenes.settingsFull))

  @Test fun impostazioniTestoGrande() = shoot("impostazioni", "Ricordi e notifiche", fontScale = LARGE_TEXT, content = settings(scenes.settingsFull))

  @Test fun impostazioniDiSerie() = shoot("impostazioni-di-serie", "Poche a settimana", content = settings(scenes.settingsDefault))

  @Test fun impostazioniSenzaPersone() = shoot("impostazioni-senza-persone", "Una notifica quando c’è un ricordo", content = settings(scenes.settingsPeopleHidden))

  @Test fun impostazioniNotificheSpente() = shoot("impostazioni-notifiche-spente", "Una notifica quando c’è un ricordo", content = settings(scenes.settingsNotifyOff))

  @Test fun impostazioniPermessoChiedibile() = shoot("impostazioni-permesso", "Consenti", content = settings(scenes.settingsAskable))

  @Test fun impostazioniPermessoNegato() = shoot("impostazioni-permesso-negato", "Apri le impostazioni di Android", content = settings(scenes.settingsDenied))

  @Test fun impostazioniCanaleSpento() = shoot("impostazioni-canale-spento", "Apri il canale Ricordi", content = settings(scenes.settingsChannelOff))

  @Test fun impostazioniMusica() = shoot("impostazioni-musica", "Una tua canzone", "Il pacchetto di musica libera non è ancora disponibile.", scrollTo = "Il pacchetto di musica libera non è ancora disponibile.", content = settings(scenes.settingsDefault))

  @Test fun impostazioniBrani() = shoot("impostazioni-brani", "Brano di prova uno", scrollTo = "Non riuscito: riprova", content = settings(scenes.settingsTracks))
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
internal class MemoriesPhoneScreenshotTest(dark: Boolean) : MemoriesShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
internal class MemoriesTabletScreenshotTest(dark: Boolean) : MemoriesShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
