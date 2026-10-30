package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryRows
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryAction
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesHandlers
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesScreen
import dev.pampa.mycloudgallery.feature.memories.list.NoAllMemoriesHandlers
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageHandlers
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageScreen
import dev.pampa.mycloudgallery.feature.memories.page.NoMemoryPageHandlers
import dev.pampa.mycloudgallery.feature.memories.player.NoPlayerHandlers
import dev.pampa.mycloudgallery.feature.memories.player.PlayerHandlers
import dev.pampa.mycloudgallery.feature.memories.player.PlayerScreen
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionSources
import dev.pampa.mycloudgallery.feature.memories.settings.MemoriesSettingsHandlers
import dev.pampa.mycloudgallery.feature.memories.settings.MemoriesSettingsScreen
import dev.pampa.mycloudgallery.feature.memories.settings.NoMemoriesSettingsHandlers
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Le schermate dei ricordi (Fase 9, P9; §1.5, §2.4, §2.5, D13, D14, D34, E-MEM19): ogni stato dice le cose giuste, i controlli rispondono con l'azione giusta, i
 * bersagli sono da 48 dp, a 1,3x nulla si sovrappone e per TalkBack ogni voce e ogni foto ha la sua frase. I fogli si aprono (un host c'e' sempre: `MemoriesHost`).
 */
@Config(application = Application::class, qualifiers = "w412dp-h2600dp-xhdpi")
internal class MemoriesScreensTest : MemoriesScreenTest() {

  private val scenes by lazy { MemoriesScenes(rig) }
  private val names = MemoryNames(null, NoMemoryPlaceNames)
  private val thumbs get() = images.thumbnails

  // --- Le scene ------------------------------------------------------------------------------------------------------------------

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

  private fun page(ui: MemoryPageUi, handlers: MemoryPageHandlers = NoMemoryPageHandlers): @Composable () -> Unit =
    { MemoryPageScreen(ui, thumbs, handlers, Modifier.fillMaxSize()) }

  private fun list(): @Composable () -> Unit = { AllMemoriesScreen(scenes.listReady, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize()) }

  private fun player(ui: PlayerUi, handlers: PlayerHandlers = NoPlayerHandlers): @Composable () -> Unit =
    { PlayerScreen(ui, thumbs, NoPlayerImages, NoPlayerClipSurface, handlers) }

  private fun settings(ui: MemoriesSettingsUi, handlers: MemoriesSettingsHandlers = NoMemoriesSettingsHandlers): @Composable () -> Unit =
    { MemoriesSettingsScreen(ui, handlers) }

  // --- La sezione, la pagina, l'elenco ----------------------------------------------------------------------------------------------

  @Test
  fun laSezioneDiceLeCoseGiuste_inOgniStato() {
    val set = switching<MemoriesSectionState>(scenes.sectionReady) { SectionScene(it) }
    assertShows("sezione pronta", "RICORDI", "Un anno fa", "2 anni fa", "Il meglio di ottobre 2026", "Tutti i ricordi", "Nuovo", "3 ricordi")
    set(scenes.sectionEmpty)
    assertShows("sezione vuota", "I ricordi arrivano quando ci sono abbastanza foto")
    set(scenes.sectionLibrary)
    assertShows("libreria non pronta", "Aspetto la tua libreria")
    set(scenes.sectionWorking)
    assertShows("in generazione", "Sto preparando i ricordi")
  }

  @Test
  fun laPaginaDelRicordo_ognuStato() {
    val set = switching(scenes.pageOtd) { MemoryPageScreen(it, thumbs, NoMemoryPageHandlers, Modifier.fillMaxSize()) }
    assertShows("pagina", "Un anno fa", "Riproduci", "Citta' di prova")
    set(scenes.pageLoading)
    assertShows("primo fotogramma", "Un anno fa")
    set(scenes.pageGone)
    assertShows("sparito", "Questo ricordo non c’è più", "Torna ai ricordi")
    set(scenes.pageNotFound)
    assertShows("non trovato", "Non trovo questo ricordo", "Torna ai ricordi")
    set(scenes.pageUndo)
    assertShows("annulla", "Non lo vedrai più nei ricordi.", "Annulla")
    set(scenes.pageTrip)
    assertShows("viaggio", "Citta' di prova, marzo 2025")
    set(scenes.pagePerson)
    assertShows("persona", "Persona di prova nel 2025")
  }

  @Test
  fun iFogliSiAprono_conLeScelteGiuste() {
    val set = switching(scenes.sheetPerson) { MemoryPageScreen(it, thumbs, NoMemoryPageHandlers, Modifier.fillMaxSize()) }
    assertShows("Mostra meno", "Mostra meno Persona di prova", "Mai nei ricordi", "Togli la scelta")
    set(scenes.sheetPersonUnnamed)
    assertShows("senza nome", "Mostra meno questa persona")
    assertDoesNotShow("senza nome", "Togli la scelta")
    set(scenes.sheetDays)
    assertShows("periodo", "Non ricordarmi questo periodo", "6 giorni", "Solo un giorno", "Tutto il periodo del ricordo", "Al massimo 366 giorni.", "Non ricordare questo periodo")
  }

  @Test
  fun tuttiIRicordi_perMese_conIlSegnoNuovo() {
    val set = switching(scenes.listReady) { AllMemoriesScreen(it, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize()) }
    assertShows("elenco", "Tutti i ricordi", "NOVEMBRE 2026", "Un anno fa", "Nuovo")
    set(scenes.listNoCovers)
    assertShows("senza copertine", "Un anno fa")
    set(scenes.listEmpty)
    assertShows("vuoto", "Ancora nessun ricordo")
  }

  /** TalkBack: ogni voce e' una frase sola, "Nuovo. Un anno fa, 9 foto", mai solo l'immagine. */
  @Test
  fun perTalkBackOgniVoceDeiRicordiHaLaSuaFrase() {
    showAll { AllMemoriesScreen(scenes.listReady, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize()) }
    assertThat(countDescribed("Nuovo. ")).isAtLeast(2)
    assertThat(countDescribed("Nuovo. Un anno fa, 9 foto")).isAtLeast(1)
  }

  // --- I controlli rispondono ------------------------------------------------------------------------------------------------------

  private class PageLog : MemoryPageHandlers by NoMemoryPageHandlers {
    val calls = ArrayList<String>()

    override fun onPlay() {
      calls += "play"
    }

    override fun onUndo() {
      calls += "undo"
    }

    override fun onBack() {
      calls += "back"
    }

    override fun onChooseLess(level: LessLevel?) {
      calls += "less:${level?.code}"
    }

    override fun onConfirmDays() {
      calls += "days"
    }

    override fun onOnlyFirstDay() {
      calls += "first"
    }

    override fun onAdjustDays(fromDelta: Int, toDelta: Int) {
      calls += "adjust:$fromDelta:$toDelta"
    }
  }

  @Test
  fun laPaginaRispondeAiSuoiControlli() {
    val log = PageLog()
    val set = switching(scenes.pageOtd) { MemoryPageScreen(it, thumbs, log, Modifier.fillMaxSize()) }
    clickText("Riproduci")
    assertThat(log.calls).containsExactly("play")
    set(scenes.pageUndo)
    clickText("Annulla")
    clickText("Torna ai ricordi")
    assertThat(log.calls).containsExactly("play", "undo", "back").inOrder()
    set(scenes.sheetPerson)
    clickText("Mai nei ricordi")
    clickText("Togli la scelta")
    assertThat(log.calls.takeLast(2)).containsExactly("less:never", "less:null").inOrder()
    set(scenes.sheetDays)
    // Due bottoni "−1 giorno" (Dal e Al): `clickText` tocca l'ultimo dell'albero, quello di "Al".
    clickText("−1 giorno", substring = false)
    clickText("Solo un giorno")
    clickText("Non ricordare questo periodo")
    assertThat(log.calls.takeLast(3)).containsExactly("adjust:0:-1", "first", "days").inOrder()
  }

  @Test
  fun ilMenuHaLeAzioniGiuste() {
    val log = object : MemoryPageHandlers by NoMemoryPageHandlers {
      val actions = ArrayList<MemoryAction>()

      override fun onAction(action: MemoryAction) {
        actions += action
      }
    }
    val set = switching(scenes.pageTrip) { MemoryPageScreen(it, thumbs, log, Modifier.fillMaxSize()) }
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    assertShows("menu del viaggio", "Non ricordarmi questo periodo", "Non mostrare questo ricordo", "Apri l’album")
    assertDoesNotShow("menu del viaggio", "Mostra meno")
    clickText("Apri l’album")
    assertThat(log.actions).containsExactly(MemoryAction.OPEN_ALBUM)
    set(scenes.pagePerson)
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    assertShows("menu della persona", "Mostra meno Persona di prova")
    assertDoesNotShow("menu della persona", "Apri l’album")
  }

  // --- La presentazione -------------------------------------------------------------------------------------------------------------

  private class PlayerLog : PlayerHandlers by NoPlayerHandlers {
    val calls = ArrayList<String>()

    override fun onPlay() { calls += "play" }

    override fun onPause() { calls += "pause" }

    override fun onNext() { calls += "next" }

    override fun onPrevious() { calls += "previous" }

    override fun onRestart() { calls += "restart" }

    override fun onExit() { calls += "exit" }
  }

  @Test
  fun laPresentazione_ognuStato() {
    val set = switching(scenes.playerIntro) { PlayerScreen(it, thumbs, NoPlayerImages, NoPlayerClipSurface, NoPlayerHandlers) }
    assertShows("titolo", "Un anno fa", "Chiudi", "1 di 9")
    set(scenes.playerPhoto)
    assertShows("foto", "Chiudi", "4 di 9")
    assertDoesNotShow("foto", "Pausa")
    set(scenes.playerPaused)
    assertShows("pausa", "Foto precedente", "Riproduci", "Foto successiva", "Chiudi")
    set(scenes.playerEnded)
    assertShows("fine", "Fine del ricordo", "9 foto", "Rivedi", "Chiudi")
    set(scenes.playerMusicMissing)
    assertShows("musica sparita", "La canzone che avevi scelto non c’è più: la presentazione resta muta.")
    set(scenes.playerGone)
    assertShows("sparito", "Questo ricordo non c’è più", "Torna ai ricordi")
    set(scenes.playerLoading)
    assertShows("caricamento", "Sto preparando la presentazione")
  }

  /** Con TalkBack niente "Riproduci": non parte niente da solo; i gesti avanti e indietro ci sono, e ogni foto ha la sua descrizione (§2.4, E-MEM19). */
  @Test
  fun conTalkBackNonCeRiproduci_eOgniFotoHaLaSuaDescrizione() {
    val set = switching(scenes.playerTalkBack) { PlayerScreen(it, thumbs, NoPlayerImages, NoPlayerClipSurface, NoPlayerHandlers) }
    assertShows("talkback", "Foto precedente", "Foto successiva", "Chiudi")
    assertDoesNotShow("talkback", "Riproduci", "Pausa")
    assertThat(countDescribed("Foto del")).isAtLeast(1)
    set(scenes.playerTalkBackIntro)
    assertShows("talkback con titolo", "Tocca per cominciare")
    // Le foto che si leggono dicono il periodo e, dentro l'app, il luogo.
    set(scenes.playerPhoto)
    assertThat(countDescribed("Foto del") + countDescribed("Video del")).isAtLeast(1)
  }

  @Test
  fun iComandiDellaPresentazioneRispondono() {
    val log = PlayerLog()
    val set = switching(scenes.playerPaused) { PlayerScreen(it, thumbs, NoPlayerImages, NoPlayerClipSurface, log) }
    clickText("Foto precedente")
    clickText("Riproduci")
    clickText("Foto successiva")
    clickText("Chiudi")
    assertThat(log.calls).containsExactly("previous", "play", "next", "exit").inOrder()
    set(scenes.playerEnded)
    clickText("Rivedi")
    assertThat(log.calls.last()).isEqualTo("restart")
  }

  // --- Le impostazioni --------------------------------------------------------------------------------------------------------------

  private class SettingsLog : MemoriesSettingsHandlers by NoMemoriesSettingsHandlers {
    val calls = ArrayList<String>()

    override fun onSetNotify(on: Boolean) { calls += "notify:$on" }

    override fun onSetPeople(on: Boolean) { calls += "people:$on" }

    override fun onShiftHour(quarters: Int) { calls += "hour:$quarters" }

    override fun onFrequency(frequency: NotifyFrequency) { calls += "freq:${frequency.code}" }

    override fun onLockScreen(mode: LockScreenMode) { calls += "lock:${mode.code}" }

    override fun onAllow() { calls += "allow" }

    override fun onOpenChannel() { calls += "channel" }

    override fun onRemoveLess(choice: LessChoice) { calls += "remove" }

    override fun onNoMusic() { calls += "nomusic" }

    override fun onPickSong() { calls += "song" }

    override fun onInstallTrack(trackId: String) { calls += "install:$trackId" }

    override fun onUseTrack(trackId: String) { calls += "use:$trackId" }
  }

  @Test
  fun leImpostazioni_ognuStato_enessunaRigaDelFiltro() {
    val set = switching(scenes.settingsDefault) { MemoriesSettingsScreen(it, NoMemoriesSettingsHandlers) }
    assertShows("impostazioni", "Ricordi e notifiche", "Ricordi con le persone", "Una notifica quando c’è un ricordo", "19:00", "Poche a settimana", "Solo «Un ricordo per te»")
    assertShows("impostazioni", "Nessuna musica", "Una tua canzone", "Il pacchetto di musica libera non è ancora disponibile.", "Nessuna scelta.")
    // Q6 (a): nessun filtro automatico dei ricordi tristi.
    assertDoesNotShow("impostazioni", "triste", "Tristi", "filtro")
    set(scenes.settingsFull)
    assertShows("complete", "20:30", "Una al giorno", "Titolo e foto", "Persona di prova", "Una persona (1)", "Scelta dal tuo telefono", "Scegli un’altra canzone")
    set(scenes.settingsPeopleHidden)
    assertDoesNotShow("senza persone", "Ricordi con le persone")
    set(scenes.settingsNotifyOff)
    assertDoesNotShow("notifiche spente", "Poche a settimana", "Solo «Un ricordo per te»")
    set(scenes.settingsAskable)
    assertShows("permesso chiedibile", "Consenti")
    set(scenes.settingsDenied)
    assertShows("permesso negato", "Apri le impostazioni di Android")
    set(scenes.settingsChannelOff)
    assertShows("canale spento", "Spente dalle impostazioni di Android", "Apri il canale Ricordi")
    set(scenes.settingsTracks)
    assertShows("brani", "Brano di prova uno", "Installa", "In uso", "Installo… 42%", "Non riuscito: riprova")
  }

  @Test
  fun leImpostazioniRispondonoAiControlli() {
    val log = SettingsLog()
    val set = switching(scenes.settingsFull) { MemoriesSettingsScreen(it, log) }
    clickText("Una notifica quando c’è un ricordo")
    clickText("Un quarto d’ora dopo")
    clickText("Un quarto d’ora prima")
    clickText("Poche a settimana")
    clickText("Solo «Un ricordo per te»")
    clickText("Nessuna musica")
    clickText("Scegli un’altra canzone")
    assertThat(log.calls).containsExactly("notify:false", "hour:1", "hour:-1", "freq:few_per_week", "lock:generic", "nomusic", "song").inOrder()
    clickText("Togli")
    assertThat(log.calls.last()).isEqualTo("remove")
    set(scenes.settingsAskable)
    clickText("Consenti")
    set(scenes.settingsChannelOff)
    clickText("Apri il canale Ricordi")
    set(scenes.settingsTracks)
    clickText("Brano di prova uno")
    assertThat(log.calls.takeLast(3)).containsExactly("allow", "channel", "install:brano_uno").inOrder()
  }

  // --- Bersagli da 48 dp, nessuna sovrapposizione, testo a 1,3x -----------------------------------------------------------------------

  private fun accessible(fontScale: Float?) {
    checkScenes(
      fontScale,
      "pagina" to page(scenes.pageOtd),
      "pagina viaggio" to page(scenes.pageTrip),
      "pagina sparita" to page(scenes.pageUndo),
      "foglio persona" to page(scenes.sheetPerson),
      "foglio periodo" to page(scenes.sheetDays),
      "elenco" to list(),
      "presentazione" to player(scenes.playerPhoto),
      "presentazione in pausa" to player(scenes.playerPaused),
      "presentazione TalkBack" to player(scenes.playerTalkBack),
      "carta finale" to player(scenes.playerEnded),
      "impostazioni" to settings(scenes.settingsFull),
      "impostazioni, permesso" to settings(scenes.settingsDenied),
      "impostazioni, brani" to settings(scenes.settingsTracks),
    )
  }

  @Test
  fun bersagliDa48dp_nessunaSovrapposizione() = accessible(null)

  @Test
  fun testoA1_3x_nulla_siSovrappone() = accessible(LARGE_TEXT)

  @Test
  fun iIdNonCompaionoNelleFrasiLette() {
    showAll { AllMemoriesScreen(scenes.listReady, thumbs, names, NoAllMemoriesHandlers, Modifier.fillMaxSize()) }
    val id: MemoryId = scenes.otd.id
    val media: MediaId = scenes.otd.cover
    for (text in spokenControls()) {
      assertThat(text).doesNotContain(id.value)
      assertThat(text).doesNotContain(media.value)
    }
  }
}
