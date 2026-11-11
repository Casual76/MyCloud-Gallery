package dev.pampa.mycloudgallery.feature.people

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.people.contract.HiddenPeopleState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.hidden.HiddenPeopleScreen
import dev.pampa.mycloudgallery.feature.people.list.NoOthersHandlers
import dev.pampa.mycloudgallery.feature.people.list.NoPeopleListHandlers
import dev.pampa.mycloudgallery.feature.people.list.OthersScreen
import dev.pampa.mycloudgallery.feature.people.list.PeopleListScreen
import org.junit.Test

/**
 * Le schermate dell'elenco delle persone, di "Altri volti" e delle persone nascoste (Fase 8, P9; §1.5 `PeopleListState`, `FaceReviewState`
 * `OTHERS`, `HiddenPeopleState`, Q2 (b), D24, D28, E-F23, E-F27, E-F28, E-F38): ogni stato si compone e dice le cose giuste, le persone nascoste non ci
 * sono mai, i tocchi arrivano ai gestori, bersagli da 48 dp e controlli che non si sovrappongono nemmeno a 1,3x, e le frasi e le azioni di TalkBack.
 */
class PeopleListScreensTest : PeopleScreenTest() {

  // --- Tutte le persone -------------------------------------------------------------------------------------------------------------

  @Test
  fun elenco_dice_le_persone_le_proposte_altri_volti_e_le_nascoste() {
    showAll { PeopleListScreen(scenes.list(), scenes.crops, NoPeopleListHandlers) }
    assertShows("elenco", "Persone", "Anna", "Marco", "Giulia", "Luca", "È la stessa persona?", "Altri volti (3)", "Persone nascoste (1)")
    // Q2 (b): le persone senza nome non sono in primo piano; la persona nascosta non si vede mai, nemmeno col nome (D28, E-F28).
    assertDoesNotShow("elenco", "Aggiungi un nome", "Nonna Rosa", "Persona senza nome")
  }

  @Test
  fun elenco_q2a_leSenzaNomeStannoDopoQuelleConUnNome() {
    val all = PeopleScenes(namedOnly = false)
    try {
      showAll { PeopleListScreen(all.list(), all.crops, NoPeopleListHandlers) }
      // I1 (fase I): una persona senza nome e' solo il volto (niente invito scritto sulla scheda: il nome si aggiunge dalla sua pagina); TalkBack la dice.
      assertShows("elenco q2a", "Anna", "SENZA NOME")
      assertDoesNotShow("elenco q2a", "Altri volti", "Aggiungi un nome")
      assertThat(spokenControls()).contains("Persona senza nome, 5 foto")
    } finally {
      all.close()
    }
  }

  @Test
  fun elenco_primoCalcolo_conLaPercentuale() {
    showAll { PeopleListScreen(scenes.listWorking(35), scenes.crops, NoPeopleListHandlers) }
    assertShows("calcolo", "Sto riconoscendo i volti", "Fatto il 35%")
  }

  @Test
  fun elenco_primoCalcolo_inAttesaESenzaPersone_eConUnMessaggio() {
    val set = switching(scenes.listWorking(null)) { PeopleListScreen(it, scenes.crops, NoPeopleListHandlers) }
    assertShows("calcolo senza percentuale", "Sto riconoscendo i volti", "Aspetto il momento giusto")
    set(scenes.listEmpty())
    assertShows("vuoto", "Ancora nessuna persona")
    set(scenes.listWithMessage(PeopleMessage.NOT_ACTIVE))
    assertShows("messaggio", "Persone non è attiva su questo dispositivo")
  }

  @Test
  fun elenco_tocchi() {
    val handlers = RecordingList()
    showAll { PeopleListScreen(scenes.list(), scenes.crops, handlers) }
    composeRule.onNode(hasContentDescription("Anna, 4 foto")).performClick()
    assertThat(handlers.opened).containsExactly(scenes.world.anna)
    composeRule.onNodeWithText("Altri volti (3)", useUnmergedTree = true).performClick()
    assertThat(handlers.others).isEqualTo(1)
    composeRule.onNodeWithText("Persone nascoste (1)", useUnmergedTree = true).performClick()
    assertThat(handlers.hidden).isEqualTo(1)
    // La riga della proposta ("Anna e Marco"): un tocco apre il foglio con i due volti.
    // L'intestazione della sezione e' in maiuscolo: il testo esatto e' solo il titolo della riga.
    composeRule.onNodeWithText("È la stessa persona?", useUnmergedTree = true).performClick()
    assertThat(handlers.proposals).hasSize(1)
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    assertThat(handlers.selecting).containsExactly(true)
  }

  @Test
  fun elenco_pressioneLunga_entraNellaSelezione() {
    val handlers = RecordingList()
    showAll { PeopleListScreen(scenes.list(), scenes.crops, handlers) }
    composeRule.onNode(hasContentDescription("Marco, 5 foto")).performTouchInput { longClick() }
    assertThat(handlers.started).containsExactly(scenes.world.marco)
  }

  @Test
  fun elenco_inSelezione_unTocco_sceglie_eUnisciVuoleDueScelte() {
    val handlers = RecordingList()
    showAll { PeopleListScreen(scenes.listSelecting(), scenes.crops, handlers) }
    assertShows("selezione", "2 selezionate")
    assertDoesNotShow("selezione", "È la stessa persona?", "Altri volti (3)") // in selezione niente altro in mezzo
    composeRule.onNode(hasContentDescription("Giulia, 5 foto")).performClick()
    assertThat(handlers.toggled).containsExactly(scenes.giulia)
    composeRule.onNodeWithContentDescription("Unisci").performClick()
    assertThat(handlers.mergeSelected).isEqualTo(1)
    composeRule.onNodeWithContentDescription("Fine").performClick()
    assertThat(handlers.selecting).containsExactly(false)
  }

  @Test
  fun elenco_conUnaPersonaSolaScelta_unisciENonFaNiente() {
    val handlers = RecordingList()
    showAll { PeopleListScreen(scenes.listSelecting().copy(selected = setOf(scenes.world.anna)), scenes.crops, handlers) }
    assertShows("una scelta", "1 selezionata")
    composeRule.onNodeWithContentDescription("Unisci").performClick()
    assertThat(handlers.mergeSelected).isEqualTo(0)
  }

  @Test
  fun elenco_talkback_nomeContoEAzioneSeleziona() {
    val handlers = RecordingList()
    showAll { PeopleListScreen(scenes.list(), scenes.crops, handlers) }
    assertThat(spokenControls()).containsAtLeast("Anna, 4 foto", "Marco, 5 foto", "Giulia, 5 foto", "Luca, 5 foto")
    assertThat(actionLabels(controlSpoken("Anna, 4 foto"))).containsExactly("Seleziona")
    runAction(controlSpoken("Marco, 5 foto"), "Seleziona")
    assertThat(handlers.started).containsExactly(scenes.world.marco)
  }

  @Test
  fun elenco_inSelezione_laTesseraDiceSeEScelta() {
    showAll { PeopleListScreen(scenes.listSelecting(), scenes.crops, NoPeopleListHandlers) }
    val chosen = controlSpoken("Anna, 4 foto")
    assertThat(chosen.config.getOrNull(SemanticsProperties.ToggleableState)).isEqualTo(ToggleableState.On)
    assertThat(chosen.config.getOrNull(SemanticsProperties.Selected)).isEqualTo(true)
    val free = controlSpoken("Giulia, 5 foto")
    assertThat(free.config.getOrNull(SemanticsProperties.ToggleableState)).isEqualTo(ToggleableState.Off)
    assertThat(actionLabels(free)).isEmpty() // in selezione un tocco sceglie: l'azione "Seleziona" non serve
  }

  @Test
  fun elenco_fogliUnione() {
    val handlers = RecordingList()
    val set = switching(scenes.listMergeChoosePickName()) { PeopleListScreen(it, scenes.crops, handlers) }
    assertShows("unione nomi", "Quale nome tieni?", "Tieni «Anna»", "Tieni «Marco»", "Continua")
    composeRule.onNodeWithText("Tieni «Marco»", useUnmergedTree = true).performClick()
    assertThat(handlers.log.picked).containsExactly(scenes.world.marco)

    set(scenes.listProposalNames())
    assertShows("proposta nomi", "Quale nome tieni?", "No, sono diverse", "Non ora")
    composeRule.onNodeWithText("No, sono diverse", useUnmergedTree = true).performClick()
    assertThat(handlers.log.rejected).isEqualTo(1)

    set(scenes.listProposalConfirm())
    assertShows("proposta", "È la stessa persona?", "Sì, è la stessa persona", "No, sono diverse", "Non ora")
    composeRule.onNodeWithText("Sì, è la stessa persona", useUnmergedTree = true).performClick()
    assertThat(handlers.log.confirmed).isEqualTo(1)
    composeRule.onNodeWithText("Non ora", useUnmergedTree = true).performClick()
    assertThat(handlers.log.dismissed).isEqualTo(1)
  }

  @Test
  fun elenco_bersagliEControlli() = checkScenes(
    null,
    "elenco" to { PeopleListScreen(scenes.list(), scenes.crops, NoPeopleListHandlers) },
    "elenco selezione" to { PeopleListScreen(scenes.listSelecting(), scenes.crops, NoPeopleListHandlers) },
    "elenco calcolo" to { PeopleListScreen(scenes.listWorking(35), scenes.crops, NoPeopleListHandlers) },
  )

  @Test
  fun elenco_bersagliEControlliATestoGrande() = checkScenes(
    LARGE_TEXT,
    "elenco" to { PeopleListScreen(scenes.list(), scenes.crops, NoPeopleListHandlers) },
    "elenco selezione" to { PeopleListScreen(scenes.listSelecting(), scenes.crops, NoPeopleListHandlers) },
    "elenco proposta" to { PeopleListScreen(scenes.listProposalConfirm(), scenes.crops, NoPeopleListHandlers) },
    "elenco nomi" to { PeopleListScreen(scenes.listProposalNames(), scenes.crops, NoPeopleListHandlers) },
  )

  // --- Altri volti ---------------------------------------------------------------------------------------------------------------

  @Test
  fun altriVolti_ogniGruppoSenzaNome_conIlSuoConto() {
    showAll { OthersScreen(scenes.others(), scenes.crops, NoOthersHandlers) }
    assertShows("altri volti", "Altri volti")
    // Tre gruppi senza nome con almeno 3 foto mostrabili; il gruppo con soli media nascosti non c'e' (E-F38), e nemmeno i nomi.
    assertThat(spokenControls().filter { it.startsWith("Persona senza nome") }).containsExactly(
      "Persona senza nome, 5 foto", "Persona senza nome, 5 foto", "Persona senza nome, 5 foto",
    )
    assertDoesNotShow("altri volti", "Anna", "Nonna Rosa")
  }

  @Test
  fun altriVolti_vuoto() {
    showAll { OthersScreen(scenes.othersEmpty(), scenes.crops, NoOthersHandlers) }
    assertShows("altri volti vuoto", "Nessun gruppo senza nome", "almeno tre foto")
  }

  @Test
  fun altriVolti_tocchi() {
    val handlers = RecordingOthers()
    showAll { OthersScreen(scenes.others(), scenes.crops, handlers) }
    composeRule.onAllNodesWithContentDescription("Persona senza nome, 5 foto")[0].performClick()
    assertThat(handlers.opened).hasSize(1)
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    assertThat(handlers.selecting).containsExactly(true)
  }

  @Test
  fun altriVolti_selezione_unisciEDimentica() {
    val handlers = RecordingOthers()
    showAll { OthersScreen(scenes.othersSelecting(), scenes.crops, handlers) }
    assertShows("altri volti selezione", "2 selezionati")
    composeRule.onNodeWithContentDescription("Unisci").performClick()
    composeRule.onNodeWithContentDescription("Dimentica").performClick()
    assertThat(handlers.mergeSelected).isEqualTo(1)
    assertThat(handlers.askedForget).isEqualTo(1)
  }

  @Test
  fun altriVolti_conUnGruppoSoloScelto_unisciENonFaNiente() {
    val handlers = RecordingOthers()
    showAll { OthersScreen(scenes.othersForgetting(), scenes.crops, handlers) }
    composeRule.onNodeWithContentDescription("Unisci").performClick()
    assertThat(handlers.mergeSelected).isEqualTo(0)
  }

  /** E-F27: "Dimentica" chiede la conferma; il tocco sul bottone la da', "Annulla" la toglie. */
  @Test
  fun altriVolti_dimenticaChiedeLaConferma() {
    val handlers = RecordingOthers()
    showAll { OthersScreen(scenes.othersForgetting(), scenes.crops, handlers) }
    assertShows("dimentica", "Dimenticare questa persona?", "Le foto restano in Libreria")
    composeRule.onNodeWithText("Annulla", useUnmergedTree = true).performClick()
    assertThat(handlers.dismissedForget).isEqualTo(1)
    assertThat(handlers.confirmedForget).isEqualTo(0)
  }

  @Test
  fun altriVolti_fogliUnione() {
    val ui = scenes.othersMerge()
    showAll { OthersScreen(ui, scenes.crops, NoOthersHandlers) }
    assertShows("altri volti unione", "Unire queste persone?", "Restano senza nome")
  }

  @Test
  fun altriVolti_talkback() {
    val handlers = RecordingOthers()
    showAll { OthersScreen(scenes.others(), scenes.crops, handlers) }
    val tile = controlSpoken("Persona senza nome, 5 foto")
    assertThat(actionLabels(tile)).containsExactly("Seleziona")
    runAction(tile, "Seleziona")
    assertThat(handlers.started).hasSize(1)
  }

  @Test
  fun altriVolti_bersagliEControlli() = checkScenes(
    null,
    "altri volti" to { OthersScreen(scenes.others(), scenes.crops, NoOthersHandlers) },
    "altri volti selezione" to { OthersScreen(scenes.othersSelecting(), scenes.crops, NoOthersHandlers) },
  )

  @Test
  fun altriVolti_bersagliEControlliATestoGrande() = checkScenes(
    LARGE_TEXT,
    "altri volti" to { OthersScreen(scenes.others(), scenes.crops, NoOthersHandlers) },
    "altri volti selezione" to { OthersScreen(scenes.othersSelecting(), scenes.crops, NoOthersHandlers) },
    "altri volti dimentica" to { OthersScreen(scenes.othersForgetting(), scenes.crops, NoOthersHandlers) },
  )

  // --- Persone nascoste -------------------------------------------------------------------------------------------------------------

  @Test
  fun nascoste_elencoVuotoCaricamentoEMessaggio() {
    val handlers = RecordingHidden()
    val set = switching<Pair<HiddenPeopleState, PeopleMessage?>>(scenes.hiddenReady() to null) { (state, notice) ->
      HiddenPeopleScreen(state, notice, scenes.crops, handlers)
    }
    assertShows("nascoste", "Persone nascoste", "Nonna Rosa")
    composeRule.onNodeWithText("Nonna Rosa", useUnmergedTree = true).performClick()
    assertThat(handlers.shown).containsExactly(scenes.nascosta)
    set(HiddenPeopleState.Empty to null)
    assertShows("nascoste vuoto", "Nessuna persona nascosta", "foto restano in Libreria")
    set(HiddenPeopleState.Loading to null)
    set(scenes.hiddenReady() to PeopleMessage.NOT_FOUND)
    assertShows("nascoste messaggio", "Questa persona non c'è più")
  }

  @Test
  fun nascoste_talkback_dicePersonaNascostaEOffreMostraDiNuovo() {
    val handlers = RecordingHidden()
    showAll { HiddenPeopleScreen(scenes.hiddenMany(), null, scenes.crops, handlers) }
    assertThat(spokenControls()).contains("Nonna Rosa, 5 foto, nascosta")
    val row = controlSpoken("Nonna Rosa")
    assertThat(actionLabels(row)).containsExactly("Mostra di nuovo")
    runAction(row, "Mostra di nuovo")
    assertThat(handlers.shown).containsExactly(scenes.nascosta)
  }

  @Test
  fun nascoste_bersagliEControlli() = checkScenes(
    null,
    "nascoste" to { HiddenPeopleScreen(scenes.hiddenMany(), null, scenes.crops, RecordingHidden()) },
  )

  @Test
  fun nascoste_bersagliEControlliATestoGrande() = checkScenes(
    LARGE_TEXT,
    "nascoste" to { HiddenPeopleScreen(scenes.hiddenMany(), null, scenes.crops, RecordingHidden()) },
  )
}
