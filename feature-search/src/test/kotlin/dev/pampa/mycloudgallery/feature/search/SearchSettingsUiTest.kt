package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.feature.search.contract.SearchSettingsState
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingPageState
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina Ricerca e modelli composta (Fase 6, P8, §2.6; D15, D20, D41; Q3, Q13), nella forma della fase I (I2: anelli e segni al posto delle frasi): la
 * copertura come anello con i motivi come segni, le aree come piastrelle con il loro segno, l'interruttore del ruolo con lo stato e la stima, la lettura
 * del testo con esclusione e spegnimento, i modelli come anelli con il dettaglio e le azioni dietro un tocco ("Rimuovi" che dice la conseguenza), i codici
 * con la loro azione, le ricerche recenti. Le frasi intere di prima restano: le dice TalkBack (qui si leggono come descrizioni) o stanno dietro la «i».
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h2400dp-xhdpi")
class SearchSettingsUiTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val handlers = RecordingSettingsHandlers()

  private fun show(
    state: SearchSettingsState = SearchSamples.settingsIndexer,
    measuring: Boolean = false,
    hasIndexer: Boolean = true,
    hasReading: Boolean = true,
    hasModels: Boolean = true,
    fontScale: Float? = null,
  ) {
    rule.setSearchContent(fontScale = fontScale) { SearchSettingsScreen(state, measuring, hasIndexer, hasReading, hasModels, handlers, onBack = {}) }
  }

  private fun scrollClick(text: String) {
    rule.onNodeWithText(text).performScrollTo().performClick()
  }

  /** Il nodo che TalkBack legge con [sentence] (una parte della sua descrizione). */
  private fun said(sentence: String): SemanticsNodeInteraction = rule.onNode(hasContentDescription(sentence, substring = true))

  /**
   * Un numero o un'etichetta corta a vista (I2): dove la riga dice a TalkBack la frase intera, il testo disegnato resta solo nell'albero non fuso.
   */
  private fun shown(text: String): SemanticsNodeInteraction = rule.onNodeWithText(text, useUnmergedTree = true)

  private fun noneSaid(sentence: String) = assertThat(rule.onAllNodes(hasContentDescription(sentence, substring = true)).fetchSemanticsNodes()).isEmpty()

  /** Il dettaglio di un modello: si apre toccando il suo anello (TalkBack dice il nome per intero). */
  private fun openModel(name: String) = said(name).performScrollTo().performClick()

  /** CU-19: la riga della lettura del testo apre il perche' e le scelte che non sono quella principale. */
  private fun openReading() = said("Leggi il testo nelle foto").performScrollTo().performClick()


  @Test
  fun laCoperturaEUnAnelloConIlNumeroEIMotiviSonoSegni_I2() {
    show()
    shown("82%").assertIsDisplayed()
    shown("12.300").assertIsDisplayed()
    shown("/ 15.000").assertIsDisplayed()
    // Un motivo: il segno, due parole, il numero; la frase intera la dice TalkBack.
    said("In attesa della preparazione: 2.700").performScrollTo().assertIsDisplayed()
    // Le frasi dietro la «i»: la copertura per intero, che cosa vuol dire analizzare, «niente esce da qui».
    rule.onNodeWithContentDescription("Come funziona la copertura").performClick()
    rule.onNode(hasText("La ricerca per contenuto copre 12.300 foto e video su 15.000.", substring = true)).assertIsDisplayed()
    rule.onNode(hasText("Un dispositivo che analizza calcola una volta per tutta la famiglia", substring = true)).assertIsDisplayed()
    rule.onNode(hasText("niente esce da qui", substring = true)).assertIsDisplayed()
    // Le frasi lunghe non sono piu' sulla pagina.
    assertThat(rule.onAllNodesWithText("La ricerca per contenuto copre 12.300 foto e video su 15.000.").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilRuoloSiCambiaConUnTocco_ELaPaginaDiceLaStima_D15() {
    show()
    rule.onNodeWithText("Questo dispositivo analizza").performScrollTo().assertIsDisplayed()
    // Lo stato e la stima: segni e numeri a vista, le frasi intere per TalkBack. CU-19: che cosa fa adesso questo telefono e' il tempo che manca, non
    // un secondo «quanti su quanti» sotto quello della copertura.
    // Pacchetto X: lo stato sta in testata, detto una volta (sotto resta solo la misura).
    // Grafica XU: la testata dell'analisi, sorella del Backup: due parole e una riga.
    shown("In corso").assertIsDisplayed()
    shown("Restano 13.760 · circa 95\u00A0min").assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("1.240 / 15.000", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    shown("≈ 2 notti").performScrollTo().assertIsDisplayed()
    said("Analizzare 13.760 foto e video richiede circa 2 notti.").assertExists()
    said("Sta analizzando le foto adesso. Restano 13.760 foto").assertExists()
    // CS-15: niente sigle sotto la stima.
    assertThat(rule.onAllNodesWithText("CPU", substring = true, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("GPU", substring = true, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    scrollClick("Questo dispositivo analizza")
    scrollClick("Analizza ora")
    assertThat(handlers.calls).containsExactly("indexer:false", "indexNow").inOrder()
  }

  /** CU-19: sotto l'analisi un tasto solo, quello che serve adesso; le altre azioni, con le frasi intere, dietro il tocco sulla riga dello stato. */
  @Test
  fun leAzioniSonoAVistaSenzaFoglio() {
    show(SearchSamples.settingsProblems)
    // Il calcolo e' fermo per lo spazio: il tasto e' il rimedio, non «Indicizza ora».
    assertThat(rule.onAllNodesWithText("Libera spazio").fetchSemanticsNodes()).hasSize(1)
    // Pacchetto X: niente foglio dietro la riga di stato (lo stato sta in testata e nella pagina Analisi): le azioni sono a vista.
    scrollClick("Analizza ora")
    assertThat(handlers.calls).containsExactly("indexNow")
    // Senza ricerche recenti nessun «0» accanto al tasto.
    assertThat(rule.onAllNodesWithText("0", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  /** I2: «nessun dispositivo analizza quest'area» solo qui, come piastrelle con il loro segno (E-I12: un'informazione). */
  @Test
  fun leAreeSonoPiastrelleConIlLoroSegno_E_I12() {
    show()
    shown("Area comune").performScrollTo().assertIsDisplayed()
    shown("Area privata").assertIsDisplayed()
    said("Nessun dispositivo analizza l’area comune.").assertExists()
    said("Un dispositivo analizza la tua area privata.").assertExists()
    // Nessun tasto largo «Attiva su questo dispositivo»: l'interruttore e' uno solo, subito sotto.
    assertThat(rule.onAllNodesWithText("Attiva su questo dispositivo").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun laLetturaDelTestoSpentaPortaAllaPaginaDiAttivazione_D41() {
    show()
    rule.onNodeWithText("Spenta").performScrollTo().assertIsDisplayed()
    said("Spenta. Le parole dentro le foto non si cercano.").assertExists()
    scrollClick("Scopri e attiva")
    assertThat(handlers.calls).containsExactly("activation")
    assertThat(rule.onAllNodesWithText("Spegni").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun laLetturaAttivaSiEscludeSiRiprendeESiSpegneDopoLaConferma_Q13() {
    show(SearchSamples.settingsReadingOn)
    rule.onNodeWithText("Attiva").performScrollTo().assertIsDisplayed()
    said("Attiva per il tuo account, anche su questo dispositivo.").assertExists()
    // CU-19: attiva e usata qui non c'e' niente da fare: nessun tasto a vista, le scelte dietro il tocco sulla riga.
    assertThat(rule.onAllNodesWithText("Non su questo dispositivo").fetchSemanticsNodes()).isEmpty()
    openReading()
    rule.onNodeWithText("Non su questo dispositivo").performClick()
    openReading()
    rule.onNodeWithText("Spegni").performClick()
    rule.onNodeWithText("Spegnere la lettura del testo?").assertIsDisplayed()
    assertThat(handlers.calls).containsExactly("exclude")
    // "Spegni" nel foglio: la conseguenza e' scritta, il tocco la conferma.
    rule.onNodeWithText("Da quel momento ML Kit non parte più, su nessun dispositivo del tuo account. Il testo già letto resta e si può cercare.").assertIsDisplayed()
    rule.onAllNodesWithText("Spegni").onLast().performClick()
    assertThat(handlers.calls).containsExactly("exclude", "disable").inOrder()
  }

  @Test
  fun unDispositivoEsclusoPuoRiprendereESeIlTestoECambiatoSiRichiede() {
    show(SearchSamples.settingsReadingChanged)
    rule.onNodeWithText("Attiva, non qui").performScrollTo().assertIsDisplayed()
    said("Attiva per il tuo account, ma non su questo dispositivo.").assertExists()
    rule.onNodeWithText("Testo cambiato: rileggilo").performScrollTo().assertIsDisplayed()
    said("Il testo della spiegazione è cambiato: leggilo e conferma.").assertExists()
    scrollClick("Leggi il nuovo testo")
    openReading()
    rule.onNodeWithText("Usa anche su questo dispositivo").performClick()
    assertThat(handlers.calls).containsExactly("activation", "include").inOrder()
  }

  @Test
  fun leSezioniNonCollegateNonCiSono_D13() {
    show(hasIndexer = false, hasReading = false, hasModels = false)
    assertThat(rule.onAllNodesWithText("Questo dispositivo analizza").fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("Leggi il testo nelle foto").fetchSemanticsNodes()).isEmpty()
    noneSaid("Ricerca per contenuto")
    // La copertura e le ricerche recenti si leggono sempre.
    shown("82%").assertExists()
    rule.onNodeWithText("Cancella le ricerche recenti").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun iModelliSonoAnelliConStatoDimensioneSorgenteEAzioniDietroUnTocco_D20() {
    show(SearchSamples.settingsIndexer)
    shown("Ricerca").performScrollTo().assertIsDisplayed()
    shown("Ritaglio").assertIsDisplayed()
    said("Ricerca per contenuto · Pronto · 157 MB · dal NAS").assertExists()
    // Il modello in arrivo dice quanto con l'anello e la percentuale.
    shown("Analisi").assertIsDisplayed()
    val percent = (120_000_000f / ModelCatalog.INDEX_440M.sizeBytes * 100f).toInt()
    shown("$percent%").assertIsDisplayed()
    openModel("Analisi delle foto")
    rule.onNode(hasText("Scaricamento: 114 MB di 370 MB", substring = true)).assertIsDisplayed()
    // Il 440M e' in arrivo e il ritaglio e' nell'APK: nessuno dei due si toglie.
    assertThat(rule.onAllNodesWithText("Rimuovi").fetchSemanticsNodes()).isEmpty()
    rule.onNodeWithText("Chiudi").performClick()
    openModel("Ricerca per contenuto")
    rule.onNodeWithText("Rimuovi").performClick()
    rule.onNodeWithText("Rimuovere il modello?").assertIsDisplayed()
    rule.onNodeWithText("Senza questo modello la ricerca per contenuto si ferma su questo dispositivo: restano le date, i luoghi e il testo già letto. Potrai scaricarlo di nuovo.").assertIsDisplayed()
    rule.onNodeWithText("Rimuovi il modello").performClick()
    assertThat(handlers.calls).containsExactly("remove:${ModelCatalog.QUERY_270M.id.value}")
  }

  @Test
  fun unModelloAssenteSiScaricaConUnaRichiestaDellUtente() {
    show(SearchSamples.settingsAbsent)
    said("Ricerca per contenuto · Non scaricato").assertExists()
    said("Analisi delle foto · Non scaricato").assertExists()
    openModel("Ricerca per contenuto")
    rule.onNodeWithText("Scarica ora").performClick()
    assertThat(handlers.calls.single()).isEqualTo("download:${ModelCatalog.QUERY_270M.id.value}")
  }

  @Test
  fun ogniProblemaHaIlSuoMessaggioELaSuaAzione_Paragrafo2_6() {
    show(SearchSamples.settingsProblems)
    shown("Memoria quasi piena: analisi ferma").performScrollTo().assertIsDisplayed()
    // "Libera spazio" per il calcolo fermo, sotto l'interruttore.
    rule.onAllNodesWithText("Libera spazio").onFirst().performScrollTo().performClick()
    // Il modello delle query: lo scaricamento non riuscito, con "Riprova" e "Diagnostica" nel suo dettaglio.
    openModel("Ricerca per contenuto")
    rule.onNode(hasText("Scaricamento del modello non riuscito", substring = true)).assertIsDisplayed()
    rule.onNodeWithText("Diagnostica").performClick()
    openModel("Ricerca per contenuto")
    rule.onNodeWithText("Riprova").performClick()
    // Il modello di analisi: lo spazio che manca, con "Libera spazio".
    openModel("Analisi delle foto")
    rule.onNode(hasText("Spazio insufficiente per il modello (serve 370 MB)", substring = true)).assertIsDisplayed()
    rule.onAllNodesWithText("Libera spazio").onLast().performClick()
    assertThat(handlers.calls).containsExactly("freeSpace", "diagnostics", "download:${ModelCatalog.QUERY_270M.id.value}", "freeSpace").inOrder()
  }

  @Test
  fun leRicercheRecentiSiCancellanoEDiconoDoveStanno_Q5() {
    show()
    said("12 ricerche, solo su questo telefono.").performScrollTo().assertIsDisplayed()
    scrollClick("Cancella le ricerche recenti")
    assertThat(handlers.calls).containsExactly("clearHistory")
  }

  @Test
  fun iPulsantiEGliAnelliSonoDa48DpAncheAlTestoGrande() {
    show(SearchSamples.settingsReadingOn, fontScale = 1.3f)
    val density = rule.density.density
    val indexNow = rule.onNodeWithText("Analizza ora").performScrollTo().fetchSemanticsNode()
    assertWithMessage("«Analizza ora» sotto i 48 dp").that(indexNow.boundsInRoot.height / density).isAtLeast(47.5f)
    // Le scelte della lettura stanno nel foglio dietro il tocco: anche li' 48 dp.
    openReading()
    for (label in listOf("Non su questo dispositivo", "Spegni")) {
      val node = rule.onNodeWithText(label).fetchSemanticsNode()
      assertWithMessage("«$label» sotto i 48 dp").that(node.boundsInRoot.height / density).isAtLeast(47.5f)
    }
    rule.onNodeWithText("Chiudi").performClick()
    for (said in listOf("Come funziona la copertura", "Ricerca per contenuto")) {
      val node = said(said).performScrollTo().fetchSemanticsNode()
      assertWithMessage("«$said» sotto i 48 dp").that(node.boundsInRoot.height / density).isAtLeast(47.5f)
    }
    assertThat(rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()).isNotEmpty()
  }

  // --- Revisione B: i difetti della pagina (MO-2, MO-8, LT-1, LT-2, IX-8, R8) --------------------------------------------------------------

  private fun withModels(vararg states: Pair<dev.pampa.mycloudgallery.core.models.contract.ModelId, dev.pampa.mycloudgallery.core.models.contract.ModelState>) =
    SearchSamples.settingsIndexer.copy(
      models = SearchSamples.settingsIndexer.models.copy(
        models = SearchSamples.settingsIndexer.models.models.map { row -> states.firstOrNull { it.first == row.id }?.let { row.copy(state = it.second, source = null) } ?: row },
      ),
    )

  /** MO-8: un rifiuto della rete non e' "non riuscito": il modello e' in coda con il suo motivo. */
  @Test
  fun mo8_unModelloInAttesaDellaReteDiceIlMotivo() {
    val queued = dev.pampa.mycloudgallery.core.models.contract.ModelState.Queued(
      dev.pampa.mycloudgallery.core.models.contract.ModelTrigger.RULES,
      dev.pampa.mycloudgallery.core.models.contract.ModelWait.WIFI,
    )
    show(withModels(ModelCatalog.QUERY_270M.id to queued))
    said("In coda: aspetto il Wi-Fi").assertExists()
    noneSaid("Scaricamento non riuscito")
  }

  @Test
  fun mo8_inRoamingEsenzaReteLaCodaDiceAncheQuesto() {
    val roaming = dev.pampa.mycloudgallery.core.models.contract.ModelState.Queued(
      dev.pampa.mycloudgallery.core.models.contract.ModelTrigger.USER_NOW,
      dev.pampa.mycloudgallery.core.models.contract.ModelWait.ROAMING,
    )
    val offline = dev.pampa.mycloudgallery.core.models.contract.ModelState.Queued(
      dev.pampa.mycloudgallery.core.models.contract.ModelTrigger.USER_NOW,
      dev.pampa.mycloudgallery.core.models.contract.ModelWait.NETWORK,
    )
    show(withModels(ModelCatalog.QUERY_270M.id to roaming, ModelCatalog.INDEX_440M.id to offline))
    said("In coda: in roaming non scarico").assertExists()
    said("In coda: aspetto una rete").assertExists()
  }

  /** MO-2: sui dati mobili la conferma si chiede con la dimensione e il "Si'" e' un tasto (prima non esisteva e finiva in "non riuscito"). */
  @Test
  fun mo2_suiDatiMobiliLaPaginaChiedeLaConfermaEIlTastoLaDa() {
    val confirm = dev.pampa.mycloudgallery.core.models.contract.ModelState.NeedsConfirmation(ModelCatalog.INDEX_440M.sizeBytes)
    show(withModels(ModelCatalog.INDEX_440M.id to confirm))
    said("Analisi delle foto · Serve la tua conferma").assertExists()
    openModel("Analisi delle foto")
    rule.onNode(hasText("Scaricare 370 MB con i dati mobili?", substring = true)).assertIsDisplayed()
    rule.onNodeWithText("Scarica con i dati mobili").performClick()
    assertThat(handlers.calls).containsExactly("confirmMobile:${ModelCatalog.INDEX_440M.id.value}")
  }

  /** LT-2: l'account ha acceso la lettura altrove: qui non parte finche' non si conferma, e ci si puo' escludere. */
  @Test
  fun lt2_conLAccountAccesoAltroveQuiServeLAvvisoOLEsclusione() {
    val state = SearchSamples.settingsIndexer.copy(
      textReading = TextReadingPageState(
        TextReadingState.AwaitingNotice(TextReadingSwitch.CURRENT_TEXT_VERSION),
        TextReadingSwitch.CURRENT_TEXT_VERSION,
        needsConsent = true,
      ),
    )
    show(state)
    rule.onNodeWithText("Aspetta la tua conferma").performScrollTo().assertIsDisplayed()
    said("Attiva per il tuo account, ma qui aspetta la tua conferma: finché non la dai, ML Kit non parte su questo dispositivo.").assertExists()
    scrollClick("Leggi e conferma")
    openReading()
    rule.onNodeWithText("Non su questo dispositivo").performClick()
    assertThat(handlers.calls).containsExactly("activation", "exclude").inOrder()
  }

  /** R8: l'esclusione si da' anche da spenta, e si toglie. */
  @Test
  fun r8_lEsclusioneSiDaAncheDaSpenta() {
    show()
    // CU-19: da spenta il tasto a vista e' «Scopri e attiva»; l'esclusione sta dietro il tocco sulla riga.
    openReading()
    rule.onNodeWithText("Non su questo dispositivo").performClick()
    assertThat(handlers.calls).containsExactly("exclude")
  }

  @Test
  fun r8_unDispositivoEsclusoDaSpentoPuoRiprendere() {
    val excluded = SearchSamples.settingsIndexer.copy(
      textReading = TextReadingPageState(TextReadingState.Off, TextReadingSwitch.CURRENT_TEXT_VERSION, needsConsent = true, excludedHere = true),
    )
    show(excluded)
    rule.onNodeWithText("Spenta, non qui").performScrollTo().assertIsDisplayed()
    said("Spenta. Questo dispositivo non la userà nemmeno se la accendi altrove.").assertExists()
    scrollClick("Usa anche su questo dispositivo")
    assertThat(handlers.calls).containsExactly("include")
  }

  /** LT-1, R6, I8: "Spegni" vale subito qui; se gli altri non l'hanno ancora ricevuto la pagina lo dice (un segno), e il quando di un cambio fatto altrove sta dietro la «i». */
  @Test
  fun lt1_loSpegnimentoDaConsegnareESiDiceEQuandoValeUnCambioAltrove() {
    val pending = SearchSamples.settingsReadingOn.copy(
      textReading = SearchSamples.settingsReadingOn.textReading.copy(syncPending = true),
    )
    show(pending)
    rule.onNodeWithText("Agli altri dispositivi dopo").performScrollTo().assertIsDisplayed()
    said("La scelta vale già qui. Arriva agli altri dispositivi quando questo si collega al NAS.").assertExists()
    openReading()
    rule.onNode(hasText("Se la accendi o la spegni da un altro dispositivo, su questo vale quando i dati personali si sincronizzano (a casa o con la VPN accesa).", substring = true)).assertIsDisplayed()
  }

  /** IX-8: spento il ruolo, con il modello di analisi sul telefono la pagina chiede se toglierlo; "Tienilo" lo lascia. */
  @Test
  fun ix8_spentoIlRuoloLaPaginaChiedeDelModelloDiAnalisi() {
    show(SearchSamples.settingsIndexer.copy(offerRemoveIndexModel = true))
    rule.onNodeWithText("Togliere anche il modello di analisi?").assertIsDisplayed()
    rule.onNodeWithText("Questo dispositivo non analizza più le foto. Il modello occupa 370 MB: puoi toglierlo, e lo riscaricherai se riaccendi l’analisi.").assertIsDisplayed()
    rule.onNodeWithText("Tienilo").performClick()
    assertThat(handlers.calls).containsExactly("keepIndexModel")
  }

  /** IX-2: "riprende fra poco" dopo una tappa finita per tempo, non "Questo dispositivo non analizza le foto". */
  @Test
  fun ix2_dopoUnaTappaFinitaPerTempoLaPaginaDiceRiprendoFraPoco() {
    val state = SearchSamples.settingsIndexer.copy(
      indexer = SearchSamples.settingsIndexer.indexer.copy(
        status = AiStatus.Waiting(AiWaitReason.NEXT_STAGE),
        analysis = dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus.Queued(remaining = 13_760, ready = 12_300, total = 15_000),
      ),
    )
    show(state)
    shown("In coda").assertIsDisplayed()
    said("Le foto da analizzare sono in coda. Restano 13.760 foto").assertExists()
    noneSaid("Questo dispositivo non analizza le foto.")
  }
}
