package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryRows
import dev.pampa.mycloudgallery.feature.people.contract.HiddenPeopleState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.hidden.HiddenPeopleScreen
import dev.pampa.mycloudgallery.feature.people.list.NoOthersHandlers
import dev.pampa.mycloudgallery.feature.people.list.NoPeopleListHandlers
import dev.pampa.mycloudgallery.feature.people.list.OthersScreen
import dev.pampa.mycloudgallery.feature.people.list.PeopleListScreen
import dev.pampa.mycloudgallery.feature.people.page.NoPersonPageHandlers
import dev.pampa.mycloudgallery.feature.people.page.PersonPageScreen
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewScreen
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionSources
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionStates
import dev.pampa.mycloudgallery.feature.people.section.SectionData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot delle persone (Fase 8, P9): **ogni stato del §1.5** -- la sezione delle Raccolte (spenta, avviso, esclusa, primo calcolo, pronta, solo su questo
 * dispositivo, i tre "non disponibile"), "Tutte le persone" (pronta, Q2 a, selezione, vuota, in calcolo, i fogli di unione e le proposte), "Altri volti", la pagina di una
 * persona (con nome, senza nome, nascosta, dimenticata, non trovata, in caricamento, i fogli del nome, di "Dimentica", della copertina e dell'unione), "Controlla le foto",
 * "Dividi" e "Persone nascoste". Chiaro e scuro, telefono (360 dp) e tablet (1280 dp), piu' il testo a 1,3x sul telefono. **Nessun volto vero**: i ritagli sono sagome
 * astratte, i nomi inventati.
 *
 * `gradlew :feature-people:recordRoborazziDebug --tests "*PeopleScreenshot*"` scrive i PNG in `docs/screenshots/8-P9/`. Nei normali `testDebugUnitTest` si verifica solo
 * che le schermate compongano e dicano le cose giuste.
 */
abstract class PeopleShots(private val dark: Boolean, private val device: String) : PeopleScreenTest() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, vararg expect: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    showAll(dark = dark, fontScale = fontScale, content = content)
    for (text in expect) assertShows(name, text)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    if (ScreenshotPolicy.capture(name, device, fontScale)) composeRule.onRoot().captureRoboImage("../docs/screenshots/8-P9/$name-$look-$device$suffix.png")
  }

  // --- La sezione Persone delle Raccolte ----------------------------------------------------------------------------------------------

  private fun groupOf(section: SectionData) = CollectionEntryGroup(
    title = section.title,
    entries = section.entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
    code = section.code,
    hideable = section.hideable,
    hint = section.hint,
  )

  /** La sezione come la mostra la scheda Raccolte: l'intestazione e il gruppo di righe (le stesse righe di `CollectionEntryRows`). */
  @Composable
  private fun SectionScene(make: (android.content.res.Resources) -> SectionData) {
    val section = make(LocalContext.current.resources)
    FluidScreen(title = "Raccolte", modifier = Modifier.fillMaxSize()) {
      item { FluidSectionHeader(title = section.title) }
      item { CollectionEntryRows(groupOf(section), selectedCode = null, twoPane = false, onSelect = {}) }
    }
  }

  private val generation = FaceGeneration.random()
  private val on = FacesState.On(generation, HereState.ACTIVE)
  private val idle = FacesStatus.Idle(atNas = 1L)

  private fun section(state: FacesState, status: FacesStatus, list: PeopleList = scenes.world.people.list.value): (android.content.res.Resources) -> SectionData = { resources ->
    PeopleSectionSources.sectionOf(resources, PeopleSectionStates.of(state, list, status), list)
  }

  @Test fun sezioneSpenta() = shoot("sezione-spenta", "Attiva riconoscimento dei volti") { SectionScene(make = section(FacesState.Off, FacesStatus.Off)) }

  @Test fun sezioneAvviso() = shoot("sezione-avviso", "Persone è attiva sul tuo account") {
    SectionScene(make = section(FacesState.AwaitingNotice(generation, 1), FacesStatus.Waiting(FacesWait.NOTICE)))
  }

  @Test fun sezioneEsclusa() = shoot("sezione-esclusa", "Non attiva su questo dispositivo") { SectionScene(make = section(FacesState.On(generation, HereState.EXCLUDED), idle)) }

  @Test fun sezionePrimoCalcolo() = shoot("sezione-primo-calcolo", "Sto riconoscendo i volti: 35%") {
    SectionScene(make = section(on, idle, PeopleList(emptyList(), emptyList(), 0, 0, 35)))
  }

  @Test fun sezionePronta() = shoot("sezione-pronta", "Anna", "Tutte le persone", "Altri volti (") { SectionScene(make = section(on, idle)) }

  @Test fun sezioneProntaTestoGrande() = shoot("sezione-pronta", "Anna", fontScale = LARGE_TEXT) { SectionScene(make = section(on, idle)) }

  @Test fun sezioneSoloQui() = shoot("sezione-solo-qui", "Solo su questo dispositivo") { SectionScene(make = section(FacesState.On(generation, HereState.LOCAL_ONLY), idle)) }

  @Test fun sezioneAggiorna() = shoot("sezione-aggiorna", "Aggiorna l'app") { SectionScene(make = section(FacesState.NeedsUpdate, FacesStatus.Waiting(FacesWait.NEEDS_UPDATE))) }

  @Test fun sezioneCancellazione() = shoot("sezione-cancellazione", "Cancellazione in corso") {
    SectionScene(make = section(FacesState.Erasing(generation), FacesStatus.Waiting(FacesWait.ERASING)))
  }

  @Test fun sezioneModelli() = shoot("sezione-modelli", "Modelli non disponibili") {
    SectionScene(make = section(on, FacesStatus.Waiting(FacesWait.MODELS), PeopleList.EMPTY))
  }

  // --- Tutte le persone --------------------------------------------------------------------------------------------------------------

  @Test fun elenco() = shoot("elenco", "Anna", "Altri volti (3)") { PeopleListScreen(scenes.list(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoTestoGrande() = shoot("elenco", "Anna", fontScale = LARGE_TEXT) { PeopleListScreen(scenes.list(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoSelezione() = shoot("elenco-selezione", "2 selezionate") { PeopleListScreen(scenes.listSelecting(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoVuoto() = shoot("elenco-vuoto", "Ancora nessuna persona") { PeopleListScreen(scenes.listEmpty(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoInCalcolo() = shoot("elenco-calcolo", "Sto riconoscendo i volti") { PeopleListScreen(PeopleListUi(PeopleListState.Working(35)), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoQ2a() {
    val all = PeopleScenes(namedOnly = false)
    try {
      shoot("elenco-tutti", "SENZA NOME") { PeopleListScreen(all.list(), all.crops, NoPeopleListHandlers) }
    } finally {
      all.close()
    }
  }

  @Test fun elencoUnioneConDueNomi() = shoot("foglio-unione-nomi", "Quale nome tieni?") { PeopleListScreen(scenes.listMergeChoosePickName(), scenes.crops, NoPeopleListHandlers) }

  @Test fun elencoUnioneConDueNomiTestoGrande() = shoot("foglio-unione-nomi", "Quale nome tieni?", fontScale = LARGE_TEXT) {
    PeopleListScreen(scenes.listMergeChoosePickName(), scenes.crops, NoPeopleListHandlers)
  }

  @Test fun elencoPropostaFraDueGruppi() = shoot("foglio-unione-proposta", "Sì, è la stessa persona") {
    PeopleListScreen(scenes.listProposalConfirm(), scenes.crops, NoPeopleListHandlers)
  }

  @Test fun elencoPropostaFraDueNomi() = shoot("foglio-unione-proposta-nomi", "No, sono diverse") {
    PeopleListScreen(scenes.listProposalNames(), scenes.crops, NoPeopleListHandlers)
  }

  // --- Altri volti ---------------------------------------------------------------------------------------------------------------------

  @Test fun altriVolti() = shoot("altri-volti", "Altri volti") { OthersScreen(scenes.others(), scenes.crops, NoOthersHandlers) }

  @Test fun altriVoltiTestoGrande() = shoot("altri-volti", "Altri volti", fontScale = LARGE_TEXT) { OthersScreen(scenes.others(), scenes.crops, NoOthersHandlers) }

  @Test fun altriVoltiSelezione() = shoot("altri-volti-selezione", "2 selezionati") { OthersScreen(scenes.othersSelecting(), scenes.crops, NoOthersHandlers) }

  @Test fun altriVoltiVuoto() = shoot("altri-volti-vuoto", "Nessun gruppo senza nome") { OthersScreen(scenes.othersEmpty(), scenes.crops, NoOthersHandlers) }

  @Test fun altriVoltiDimentica() = shoot("altri-volti-dimentica", "Dimenticare questa persona?") { OthersScreen(scenes.othersForgetting(), scenes.crops, NoOthersHandlers) }

  @Test fun altriVoltiUnione() = shoot("altri-volti-unione", "Unire queste persone?") { OthersScreen(scenes.othersMerge(), scenes.crops, NoOthersHandlers) }

  // --- La pagina di una persona ------------------------------------------------------------------------------------------------------

  private fun page(ui: dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi): @Composable () -> Unit =
    { PersonPageScreen(ui, scenes.thumbs.thumbnails, scenes.crops, NoPersonPageHandlers) }

  @Test fun paginaPersona() = shoot("pagina-persona", "Anna", "Controlla", "FORSE È ANCHE…", content = page(scenes.pageUi(scenes.world.anna)))

  @Test fun paginaPersonaTestoGrande() = shoot("pagina-persona", "Anna", fontScale = LARGE_TEXT, content = page(scenes.pageUi(scenes.world.anna)))

  @Test fun paginaSenzaNome() = shoot("pagina-senza-nome", "Persona senza nome", "Nome", content = page(scenes.pageUnnamed()))

  @Test fun paginaNascosta() = shoot("pagina-nascosta", "Mostra di nuovo", content = page(scenes.pageHidden()))

  @Test fun paginaDimenticata() = shoot("pagina-dimenticata", "Persona dimenticata", content = page(scenes.pageForgotten()))

  @Test fun paginaNonTrovata() = shoot("pagina-non-trovata", "Questa persona non c'è", content = page(scenes.pageNotFound()))

  @Test fun paginaInCaricamento() = shoot("pagina-caricamento", "Persona", content = page(scenes.pageLoading()))

  @Test fun paginaConMessaggio() = shoot("pagina-messaggio", "Il nome deve avere da 1 a 64 caratteri", content = page(scenes.pageWithMessage()))

  @Test fun paginaMenu() {
    showAll(dark = dark) { PersonPageScreen(scenes.pageUi(scenes.world.anna), scenes.thumbs.thumbnails, scenes.crops, NoPersonPageHandlers) }
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    assertShows("pagina-menu", "Unisci con…", "Crea album smart", "Dimentica persona")
    if (ScreenshotPolicy.capture("pagina-menu", device, null)) composeRule.onRoot().captureRoboImage("../docs/screenshots/8-P9/pagina-menu-$look-$device.png")
  }

  @Test fun foglioNome() = shoot("foglio-nome", "Aggiungi un nome", "Salva", content = page(scenes.pageNaming()))

  @Test fun foglioNomeNonValido() = shoot("foglio-nome-non-valido", "Da 1 a 64 caratteri", content = page(scenes.pageNaming(invalid = true)))

  @Test fun foglioRinomina() = shoot("foglio-rinomina", "Togli il nome", content = page(scenes.pageRenaming()))

  @Test fun foglioDimentica() = shoot("foglio-dimentica", "Dimenticare questa persona?", content = page(scenes.pageForgetting()))

  @Test fun foglioCopertina() = shoot("foglio-copertina", "Scegli copertina", "Usa quella automatica", content = page(scenes.pageCovers()))

  @Test fun foglioUnioneScelta() = shoot("foglio-unione-scelta", "Unisci con…", content = page(scenes.pageMergeChoose()))

  @Test fun foglioUnioneNomiDallaPagina() = shoot("foglio-unione-nomi-pagina", "Quale nome tieni?", content = page(scenes.pageMergePickName()))

  @Test fun foglioUnioneConferma() = shoot("foglio-unione-conferma", "Unire queste persone?", content = page(scenes.pageMergeConfirm()))

  @Test fun foglioPropostaDallaPagina() = shoot("foglio-proposta-pagina", "No, sono diverse", content = page(scenes.pageProposal()))

  // --- Controlla le foto e Dividi ------------------------------------------------------------------------------------------------------

  @Test fun controlla() = shoot("controlla", "Controlla le foto", "Non è Anna") { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun controllaTestoGrande() = shoot("controlla", "Controlla le foto", fontScale = LARGE_TEXT) { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun controllaSelezione() = shoot("controlla-selezione", "Non è Anna") { FaceReviewScreen(scenes.checkSelecting(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun dividi() = shoot("dividi", "Dividi", "Sposta in una persona nuova") { FaceReviewScreen(scenes.split(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun dividiSelezione() = shoot("dividi-selezione", "Sposta in una persona nuova") { FaceReviewScreen(scenes.splitSelecting(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun controllaFatto() = shoot("controlla-fatto", "Fatto") { FaceReviewScreen(scenes.reviewDone(2), "Anna", scenes.crops, RecordingReview()) }

  @Test fun controllaVuoto() = shoot("controlla-vuoto", "Nessun volto da controllare") { FaceReviewScreen(scenes.reviewEmpty(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun controllaInCorso() = shoot("controlla-in-corso", "Controlla le foto") { FaceReviewScreen(scenes.reviewApplying(), "Anna", scenes.crops, RecordingReview()) }

  // --- Persone nascoste ----------------------------------------------------------------------------------------------------------------

  @Test fun personeNascoste() = shoot("persone-nascoste", "Persone nascoste", "Nonna Rosa") { HiddenPeopleScreen(scenes.hiddenMany(), null, scenes.crops, RecordingHidden()) }

  @Test fun personeNascosteCaricamento() = shoot("persone-nascoste-caricamento", "Persone nascoste") { HiddenPeopleScreen(HiddenPeopleState.Loading, null, scenes.crops, RecordingHidden()) }

  @Test fun personeNascosteVuoto() = shoot("persone-nascoste-vuoto", "Nessuna persona nascosta") { HiddenPeopleScreen(HiddenPeopleState.Empty, null, scenes.crops, RecordingHidden()) }

  /** Nessuna immagine e' stata chiesta per un volto che non si puo' mostrare: i ritagli chiesti sono tutti di celle mostrabili. */
  @Test fun iRitagliChiestiSonoSoloDiVoltiMostrabili() {
    showAll(dark = dark) { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, RecordingReview()) }
    val hidden = scenes.world.facesOf(scenes.world.anna).filter { scenes.world.media.getValue(it.ref.contentKey) in scenes.world.hidden }.map { it.locator }.toSet()
    assertWithMessage("ritagli chiesti per un volto di un media nascosto").that(scenes.crops.asked.toSet().intersect(hidden)).isEmpty()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class PeoplePhoneScreenshotTest(dark: Boolean) : PeopleShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class PeopleTabletScreenshotTest(dark: Boolean) : PeopleShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
