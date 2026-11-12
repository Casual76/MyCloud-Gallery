package dev.pampa.mycloudgallery.feature.people

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.PersonAction
import dev.pampa.mycloudgallery.feature.people.page.NoPersonPageHandlers
import dev.pampa.mycloudgallery.feature.people.page.PersonPageScreen
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewScreen
import org.junit.Test

/**
 * La pagina di una persona e la griglia dei volti (Fase 8, P9; §1.5 `PersonPageState`, `FaceReviewState`, §2.6, D22, D24, D28, E-F20, E-F21, E-F23,
 * E-F27, E-F28): ogni stato si compone e dice le cose giuste, i fogli (nome, copertina, "Dimentica", unione) si aprono dallo stato giusto, il menu ha le
 * azioni che la persona consente, i volti hanno le azioni di TalkBack ("Seleziona", "Non e' …"), bersagli da 48 dp e controlli che non si sovrappongono
 * nemmeno a 1,3x.
 */
class PersonPageScreensTest : PeopleScreenTest() {

  private fun page(ui: dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi, handlers: dev.pampa.mycloudgallery.feature.people.page.PersonPageHandlers = NoPersonPageHandlers): @androidx.compose.runtime.Composable () -> Unit =
    { PersonPageScreen(ui, scenes.thumbs.thumbnails, scenes.crops, handlers) }

  // --- La pagina ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun paginaConNome_haIlTitoloIlContoLeAzioniELeFotoMostrabili() {
    showAll(content = page(scenes.pageUi(scenes.world.anna)))
    assertShows("pagina", "Anna", "4 foto", "Rinomina", "Controlla", "FORSE È ANCHE…", "Marco")
    // Giro 1 (CU-12): il tasto scrive una parola, TalkBack dice la frase intera.
    assertThat(says("Controlla le foto")).isTrue()
    // Le foto sono quelle mostrabili: l'ultima di Anna e' nascosta e non c'e' (D28).
    assertThat(countDescribed("Foto, ")).isEqualTo(4)
    assertDoesNotShow("pagina", "Aggiungi un nome")
  }

  @Test
  fun paginaSenzaNome_invitaADareUnNome() {
    showAll(content = page(scenes.pageUnnamed()))
    assertShows("pagina senza nome", "Persona senza nome", "Nome", "Controlla", "5 foto")
    assertThat(says("Aggiungi un nome")).isTrue()
    assertThat(says("Controlla le foto")).isTrue()
  }

  @Test
  fun paginaNascosta_dicePerchePoiOffreDiMostrarlaDiNuovo() {
    val handlers = RecordingPage()
    showAll(content = page(scenes.pageHidden(), handlers))
    assertShows("pagina nascosta", "Persona nascosta", "Mostra di nuovo", "Dimentica persona")
    assertThat(countDescribed("Foto, ")).isEqualTo(0) // le sue foto non si vedono qui
    composeRule.onNodeWithText("Mostra di nuovo", useUnmergedTree = true).performClick()
    composeRule.onNodeWithText("Dimentica persona", useUnmergedTree = true).performClick()
    assertThat(handlers.actions).containsExactly(PersonAction.UNHIDE, PersonAction.FORGET).inOrder()
  }

  @Test
  fun paginaDimenticataNonTrovataECaricamento() {
    val handlers = RecordingPage()
    val set = switching(scenes.pageForgotten()) { page(it, handlers)() }
    assertShows("dimenticata", "Persona dimenticata", "Indietro")
    composeRule.onNodeWithText("Indietro", useUnmergedTree = true).performClick()
    assertThat(handlers.backs).isEqualTo(1)
    set(scenes.pageNotFound())
    assertShows("non trovata", "Questa persona non c'è", "Indietro")
    set(scenes.pageLoading())
    assertShows("caricamento", "Persona")
  }

  @Test
  fun paginaConMessaggio() {
    showAll(content = page(scenes.pageWithMessage(PeopleMessage.INVALID_NAME)))
    assertShows("messaggio", "Il nome deve avere da 1 a 64 caratteri")
  }

  @Test
  fun menu_haLeAzioniDellaPersona() {
    val handlers = RecordingPage()
    showAll(content = page(scenes.pageUi(scenes.world.anna), handlers))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    assertShows(
      "menu", "Rinomina", "Unisci con…", "Controlla le foto", "Dividi…", "Scegli copertina", "Crea album smart", "Nascondi persona", "Dimentica persona",
    )
    composeRule.onNodeWithText("Crea album smart", useUnmergedTree = true).performClick()
    assertThat(handlers.actions).containsExactly(PersonAction.SMART_ALBUM)
  }

  @Test
  fun toccareIBottoniDellaTestata() {
    val handlers = RecordingPage()
    showAll(content = page(scenes.pageUnnamed(), handlers))
    composeRule.onNodeWithContentDescription("Aggiungi un nome").performClick()
    composeRule.onNodeWithContentDescription("Controlla le foto").performClick()
    assertThat(handlers.actions).containsExactly(PersonAction.RENAME, PersonAction.REVIEW).inOrder()
  }

  @Test
  fun toccareUnaProposta_apreIlFoglio() {
    val handlers = RecordingPage()
    showAll(content = page(scenes.pageUi(scenes.world.anna), handlers))
    composeRule.onNodeWithText("Marco", useUnmergedTree = true).performClick()
    assertThat(handlers.proposals).hasSize(1)
  }

  // --- I fogli -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun foglioNome_aggiungiRinominaETogli() {
    val handlers = RecordingPage()
    val set = switching(scenes.pageNaming()) { page(it, handlers)() }
    assertShows("nome", "Aggiungi un nome", "Il nome è solo tuo", "Salva", "Annulla")
    assertDoesNotShow("nome", "Togli il nome")
    composeRule.onNode(hasSetTextAction()).performTextInput("Zio Carlo")
    composeRule.onNodeWithText("Salva", useUnmergedTree = true).performClick()
    assertThat(handlers.names).containsExactly("Zio Carlo")
    set(scenes.pageRenaming())
    assertShows("rinomina", "Rinomina", "Togli il nome")
    composeRule.onNodeWithText("Togli il nome", useUnmergedTree = true).performClick()
    assertThat(handlers.removedName).isEqualTo(1)
    composeRule.onNodeWithText("Annulla", useUnmergedTree = true).performClick()
    assertThat(handlers.dismissedName).isEqualTo(1)
  }

  @Test
  fun foglioNome_nonValido_loDice() {
    showAll(content = page(scenes.pageNaming(invalid = true)))
    assertShows("nome non valido", "Da 1 a 64 caratteri, senza caratteri speciali.")
  }

  @Test
  fun foglioDimentica_chiedeLaConferma() {
    val handlers = RecordingPage()
    showAll(content = page(scenes.pageForgetting(), handlers))
    assertShows("dimentica", "Dimenticare questa persona?", "non verranno più raggruppati", "Le foto restano in Libreria", "Anna")
    composeRule.onNodeWithText("Dimentica", useUnmergedTree = true).performClick()
    assertThat(handlers.confirmedForget).isEqualTo(1)
    composeRule.onNodeWithText("Annulla", useUnmergedTree = true).performClick()
    assertThat(handlers.dismissedForget).isEqualTo(1)
  }

  @Test
  fun foglioCopertina_offreIVoltiMostrabiliEQuellaAutomatica() {
    val handlers = RecordingPage()
    showAll(content = page(scenes.pageCovers(), handlers))
    assertShows("copertina", "Scegli copertina", "Il ritaglio non si salva da nessuna parte", "Usa quella automatica")
    // Quattro volti (la quinta foto e' nascosta): "Volto 1 di 4".
    assertThat(spokenControls()).containsAtLeast("Volto 1 di 4", "Volto 4 di 4")
    assertThat(spokenControls()).doesNotContain("Volto 5 di 5")
    composeRule.onNodeWithContentDescription("Volto 2 di 4").performClick()
    assertThat(handlers.covers).hasSize(1)
    composeRule.onNodeWithText("Usa quella automatica", useUnmergedTree = true).performClick()
    assertThat(handlers.covers.last()).isNull()
  }

  @Test
  fun foglioUnione_scegliAChi_poiIDueNomi_poiLaConferma() {
    val handlers = RecordingPage()
    val set = switching(scenes.pageMergeChoose()) { page(it, handlers)() }
    assertShows("unisci con", "Unisci con…", "Giulia", "Marco", "Luca", "Continua")
    composeRule.onNodeWithText("Luca", useUnmergedTree = true).performClick()
    assertThat(handlers.log.toggled).containsExactly(scenes.luca)
    composeRule.onNodeWithText("Continua", useUnmergedTree = true).performClick()
    assertThat(handlers.log.continuedFromChoose).isEqualTo(1)
    set(scenes.pageMergePickName())
    assertShows("due nomi", "Quale nome tieni?", "Tieni «Anna»", "Tieni «Marco»")
    set(scenes.pageMergeConfirm())
    assertShows("conferma", "Unire queste persone?", "Restano senza nome", "Unisci")
    composeRule.onNodeWithText("Unisci", useUnmergedTree = true).performClick()
    assertThat(handlers.log.confirmed).isEqualTo(1)
  }

  @Test
  fun foglioProposta_siENo() {
    val handlers = RecordingPage()
    showAll(content = page(scenes.pageProposal(), handlers))
    // Anna e Marco hanno un nome: prima si sceglie quale tenere, e anche da li' si puo' dire "No".
    assertShows("proposta", "Quale nome tieni?", "No, sono diverse")
    composeRule.onNodeWithText("No, sono diverse", useUnmergedTree = true).performClick()
    assertThat(handlers.log.rejected).isEqualTo(1)
  }

  // --- Controlla le foto e Dividi -------------------------------------------------------------------------------------------------

  @Test
  fun controlla_dicePrimaIMenoSicuriEHaLeAzioniDiTalkBack() {
    val handlers = RecordingReview()
    showAll { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, handlers) }
    assertShows("controlla", "Controlla le foto", "Tocca i volti che non sono di Anna", "Non è Anna", "Ignora questo volto")
    val tile = controlSpoken("Volto 1 di 4")
    assertThat(actionLabels(tile)).containsExactly("Seleziona", "Non è Anna").inOrder()
    runAction(tile, "Non è Anna")
    assertThat(handlers.rejectedOne).hasSize(1)
    runAction(tile, "Seleziona")
    assertThat(handlers.toggled).hasSize(1)
  }

  @Test
  fun controlla_conLaSelezione_ibottoniSiAccendono() {
    val handlers = RecordingReview()
    showAll { FaceReviewScreen(scenes.checkSelecting(), "Anna", scenes.crops, handlers) }
    assertThat(spokenControls().count { it.endsWith("scelto") }).isEqualTo(2)
    composeRule.onNodeWithText("Non è Anna", useUnmergedTree = true).performClick()
    composeRule.onNodeWithText("Ignora questo volto", useUnmergedTree = true).performClick()
    assertThat(handlers.rejected).isEqualTo(1)
    assertThat(handlers.ignored).isEqualTo(1)
  }

  @Test
  fun controlla_senzaSelezione_ibottoniSonoSpenti() {
    val handlers = RecordingReview()
    showAll { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, handlers) }
    composeRule.onNodeWithText("Non è Anna", useUnmergedTree = true).performClick()
    composeRule.onNodeWithText("Ignora questo volto", useUnmergedTree = true).performClick()
    assertThat(handlers.rejected).isEqualTo(0)
    assertThat(handlers.ignored).isEqualTo(0)
  }

  @Test
  fun dividi_nonTuttiIVolti() {
    val handlers = RecordingReview()
    val set = switching(scenes.split()) { FaceReviewScreen(it, "Anna", scenes.crops, handlers) }
    assertShows("dividi", "Dividi", "Scegli i volti che sono un'altra persona", "Sposta in una persona nuova")
    composeRule.onNodeWithText("Sposta in una persona nuova", useUnmergedTree = true).performClick()
    assertThat(handlers.split).isEqualTo(0) // niente di scelto: spento
    set(scenes.splitSelecting())
    composeRule.onNodeWithText("Sposta in una persona nuova", useUnmergedTree = true).performClick()
    assertThat(handlers.split).isEqualTo(1)
    // In "Dividi" i volti non hanno "Non e' …": si sceglie e basta.
    assertThat(actionLabels(controlSpoken("Volto 1 di 4"))).containsExactly("Seleziona")
  }

  @Test
  fun controlla_statiApplicareFattoVuotoEMessaggio() {
    val set = switching(scenes.reviewApplying()) { FaceReviewScreen(it, "Anna", scenes.crops, RecordingReview()) }
    set(scenes.reviewDone(2))
    assertShows("fatto", "Fatto")
    assertSays("fatto", "2 volti cambiati", "Valgono subito, anche sugli altri tuoi dispositivi")
    set(scenes.reviewDone(1))
    assertSays("fatto uno", "Un volto cambiato")
    set(scenes.reviewDone(0))
    assertSays("fatto niente", "Non è cambiato niente")
    set(scenes.reviewEmpty())
    assertShows("vuoto", "Nessun volto da controllare")
    set(scenes.reviewWithMessage())
    assertShows("messaggio", "Persone non è attiva su questo dispositivo")
  }

  @Test
  fun controlla_ilGrigliaDiUnaPersonaSenzaNome_dicePersonaSenzaNome() {
    showAll { FaceReviewScreen(scenes.check(), null, scenes.crops, RecordingReview()) }
    assertShows("senza nome", "una persona senza nome")
    val state = scenes.check().state as FaceReviewState.Grid
    assertThat(state.faces).hasSize(4)
  }

  @Test
  fun inNessunaGrigliaCompareUnVoltoNascosto() {
    // I ritagli chiesti sono tutti di volti mostrabili (la griglia viene da PersonFaces, che non offre i nascosti, D28).
    showAll { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, RecordingReview()) }
    composeRule.waitForIdle()
    val shown = (scenes.check().state as FaceReviewState.Grid).faces.map { it.media }.toSet()
    assertThat(shown.intersect(scenes.world.hidden)).isEmpty()
    val hiddenFaces = scenes.world.facesOf(scenes.world.anna).filter { scenes.world.media.getValue(it.ref.contentKey) in scenes.world.hidden }.map { it.locator }
    assertThat(scenes.crops.asked.toSet().intersect(hiddenFaces.toSet())).isEmpty()
  }

  // --- Bersagli e controlli ---------------------------------------------------------------------------------------------------------

  @Test
  fun pagina_bersagliEControlli() = checkScenes(
    null,
    "pagina" to page(scenes.pageUi(scenes.world.anna)),
    "pagina senza nome" to page(scenes.pageUnnamed()),
    "pagina nascosta" to page(scenes.pageHidden()),
  )

  @Test
  fun pagina_bersagliEControlliATestoGrande() = checkScenes(
    LARGE_TEXT,
    "pagina" to page(scenes.pageUi(scenes.world.anna)),
    "pagina senza nome" to page(scenes.pageUnnamed()),
    "pagina nascosta" to page(scenes.pageHidden()),
    "pagina dimenticata" to page(scenes.pageForgotten()),
  )

  @Test
  fun fogli_bersagliEControlliATestoGrande() = checkScenes(
    LARGE_TEXT,
    "foglio nome" to page(scenes.pageNaming()),
    "foglio rinomina" to page(scenes.pageRenaming()),
    "foglio dimentica" to page(scenes.pageForgetting()),
    "foglio copertina" to page(scenes.pageCovers()),
    "foglio unione scelta" to page(scenes.pageMergeChoose()),
    "foglio unione nomi" to page(scenes.pageMergePickName()),
    "foglio unione conferma" to page(scenes.pageMergeConfirm()),
    "foglio proposta" to page(scenes.pageProposal()),
  )

  @Test
  fun volti_bersagliEControlliATestoGrande() = checkScenes(
    LARGE_TEXT,
    "controlla" to { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, RecordingReview()) },
    "controlla selezione" to { FaceReviewScreen(scenes.checkSelecting(), "Anna", scenes.crops, RecordingReview()) },
    "dividi" to { FaceReviewScreen(scenes.splitSelecting(), "Anna", scenes.crops, RecordingReview()) },
  )

  @Test
  fun volti_bersagliEControlli() = checkScenes(
    null,
    "controlla" to { FaceReviewScreen(scenes.check(), "Anna", scenes.crops, RecordingReview()) },
    "dividi" to { FaceReviewScreen(scenes.split(), "Anna", scenes.crops, RecordingReview()) },
  )
}
