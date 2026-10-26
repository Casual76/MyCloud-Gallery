package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingPageState
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.feature.search.contract.SearchSettingsState
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina Ricerca e modelli composta (Fase 6, P8, §2.6; D15, D20, D41; Q3, Q13): ruolo con misura e stima, lettura del testo con esclusione e spegnimento, copertura,
 * modelli con "Scarica ora" e "Rimuovi" che dice la conseguenza, codici con la loro azione.
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

  @Test
  fun ilRuoloSiCambiaConUnTocco_ELaPaginaDiceLaStima_D15() {
    show()
    rule.onNodeWithText("Analizza le foto di questo dispositivo").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Analizzare 13.760 media richiede circa 2 notti.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Analizzati 1.240 media su 15.000, mancano circa 95 minuti.").performScrollTo().assertIsDisplayed()
    scrollClick("Analizza le foto di questo dispositivo")
    scrollClick("Indicizza ora")
    assertThat(handlers.calls).containsExactly("indexer:false", "indexNow").inOrder()
  }

  @Test
  fun lAreaSenzaIndicizzatoriEUnaInformazione_E_I12() {
    show()
    rule.onNodeWithText("Nessun dispositivo analizza l’area comune.").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun laLetturaDelTestoSpentaPortaAllaPaginaDiAttivazione_D41() {
    show()
    rule.onNodeWithText("Spenta. Le parole dentro le foto non si cercano.").performScrollTo().assertIsDisplayed()
    scrollClick("Scopri e attiva")
    assertThat(handlers.calls).containsExactly("activation")
    assertThat(rule.onAllNodesWithText("Spegni").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun laLetturaAttivaSiEscludeSiRiprendeESiSpegneDopoLaConferma_Q13() {
    show(SearchSamples.settingsReadingOn)
    rule.onNodeWithText("Attiva per il tuo account, anche su questo dispositivo.").performScrollTo().assertIsDisplayed()
    scrollClick("Non su questo dispositivo")
    scrollClick("Spegni")
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
    rule.onNodeWithText("Attiva per il tuo account, ma non su questo dispositivo.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Il testo della spiegazione è cambiato: leggilo e conferma.").performScrollTo().assertIsDisplayed()
    scrollClick("Leggi il nuovo testo")
    scrollClick("Usa anche su questo dispositivo")
    assertThat(handlers.calls).containsExactly("activation", "include").inOrder()
  }

  @Test
  fun leSezioniNonCollegateNonCiSono_D13() {
    show(hasIndexer = false, hasReading = false, hasModels = false)
    assertThat(rule.onAllNodesWithText("Analizza le foto di questo dispositivo").fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("Leggi il testo nelle foto").fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("Ricerca per contenuto").fetchSemanticsNodes()).isEmpty()
    // La copertura e le ricerche recenti si leggono sempre.
    rule.onNodeWithText("Cancella le ricerche recenti").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun iModelliHannoStatoDimensioneSorgenteEAzioni_D20() {
    show(SearchSamples.settingsIndexer)
    rule.onNodeWithText("Ricerca per contenuto").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("157 MB · dal NAS").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Scaricamento: 114 MB di 370 MB").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Ritaglio del soggetto").performScrollTo().assertIsDisplayed()
    // Solo il modello delle query si puo' togliere (il 440M e' in scaricamento, il ritaglio e' nell'APK).
    assertThat(rule.onAllNodesWithText("Rimuovi").fetchSemanticsNodes()).hasSize(1)
    scrollClick("Rimuovi")
    rule.onNodeWithText("Rimuovere il modello?").assertIsDisplayed()
    rule.onNodeWithText("Senza questo modello la ricerca per contenuto si ferma su questo dispositivo: restano le date, i luoghi e il testo già letto. Potrai scaricarlo di nuovo.").assertIsDisplayed()
    rule.onNodeWithText("Rimuovi il modello").performClick()
    assertThat(handlers.calls).containsExactly("remove:${ModelCatalog.QUERY_270M.id.value}")
  }

  @Test
  fun unModelloAssenteSiScaricaConUnaRichiestaDellUtente() {
    show(SearchSamples.settingsAbsent)
    val downloads = rule.onAllNodesWithText("Scarica ora").fetchSemanticsNodes()
    assertThat(downloads).hasSize(2)
    rule.onAllNodesWithText("Scarica ora").onFirst().performScrollTo().performClick()
    assertThat(handlers.calls.single()).isEqualTo("download:${ModelCatalog.QUERY_270M.id.value}")
  }

  @Test
  fun ogniProblemaHaIlSuoMessaggioELaSuaAzione_Paragrafo2_6() {
    show(SearchSamples.settingsProblems)
    rule.onNodeWithText("Memoria quasi piena: analisi ferma").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Scaricamento del modello non riuscito").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Spazio insufficiente per il modello (serve 370 MB)").performScrollTo().assertIsDisplayed()
    // "Libera spazio" per il calcolo fermo e per il modello senza spazio; "Riprova" e "Diagnostica" per lo scaricamento fallito.
    assertThat(rule.onAllNodesWithText("Libera spazio").fetchSemanticsNodes()).hasSize(2)
    rule.onAllNodesWithText("Libera spazio").onFirst().performScrollTo().performClick()
    rule.onAllNodesWithText("Diagnostica").onFirst().performScrollTo().performClick()
    rule.onAllNodesWithText("Riprova").onFirst().performScrollTo().performClick()
    assertThat(handlers.calls).containsExactly("freeSpace", "diagnostics", "download:${ModelCatalog.QUERY_270M.id.value}").inOrder()
  }

  @Test
  fun leRicercheRecentiSiCancellanoEDiconoDoveStanno_Q5() {
    show()
    rule.onNodeWithText("12 ricerche, solo su questo telefono.").performScrollTo().assertIsDisplayed()
    scrollClick("Cancella le ricerche recenti")
    assertThat(handlers.calls).containsExactly("clearHistory")
  }

  @Test
  fun iPulsantiSonoDa48DpAncheAlTestoGrande() {
    show(SearchSamples.settingsReadingOn, fontScale = 1.3f)
    val density = rule.density.density
    val labels = listOf("Indicizza ora", "Non su questo dispositivo", "Spegni", "Rimuovi")
    for (label in labels) {
      val node = rule.onNodeWithText(label).performScrollTo().fetchSemanticsNode()
      assertWithMessage("«$label» sotto i 48 dp").that(node.boundsInRoot.height / density).isAtLeast(47.5f)
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
    rule.onNodeWithText("In coda: aspetto il Wi-Fi").performScrollTo().assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Scaricamento non riuscito").fetchSemanticsNodes()).isEmpty()
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
    rule.onNodeWithText("In coda: in roaming non scarico").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("In coda: aspetto una rete").performScrollTo().assertIsDisplayed()
  }

  /** MO-2: sui dati mobili la conferma si chiede con la dimensione e il "Si'" e' un tasto (prima non esisteva e finiva in "non riuscito"). */
  @Test
  fun mo2_suiDatiMobiliLaPaginaChiedeLaConfermaEIlTastoLaDa() {
    val confirm = dev.pampa.mycloudgallery.core.models.contract.ModelState.NeedsConfirmation(ModelCatalog.INDEX_440M.sizeBytes)
    show(withModels(ModelCatalog.INDEX_440M.id to confirm))
    rule.onNodeWithText("Serve la tua conferma").performScrollTo().assertIsDisplayed()
    scrollClick("Scarica con i dati mobili")
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
    rule.onNodeWithText("Attiva per il tuo account, ma qui aspetta la tua conferma: finché non la dai, ML Kit non parte su questo dispositivo.").performScrollTo().assertIsDisplayed()
    scrollClick("Leggi e conferma")
    scrollClick("Non su questo dispositivo")
    assertThat(handlers.calls).containsExactly("activation", "exclude").inOrder()
  }

  /** R8: l'esclusione si da' anche da spenta, e si toglie. */
  @Test
  fun r8_lEsclusioneSiDaAncheDaSpenta() {
    show()
    scrollClick("Non su questo dispositivo")
    assertThat(handlers.calls).containsExactly("exclude")
  }

  @Test
  fun r8_unDispositivoEsclusoDaSpentoPuoRiprendere() {
    val excluded = SearchSamples.settingsIndexer.copy(
      textReading = TextReadingPageState(TextReadingState.Off, TextReadingSwitch.CURRENT_TEXT_VERSION, needsConsent = true, excludedHere = true),
    )
    show(excluded)
    rule.onNodeWithText("Spenta. Questo dispositivo non la userà nemmeno se la accendi altrove.").performScrollTo().assertIsDisplayed()
    scrollClick("Usa anche su questo dispositivo")
    assertThat(handlers.calls).containsExactly("include")
  }

  /** LT-1, R6, I8: "Spegni" vale subito qui; se gli altri non l'hanno ancora ricevuto la pagina lo dice, e dice quando vale un cambio fatto altrove. */
  @Test
  fun lt1_loSpegnimentoDaConsegnareESiDiceEQuandoValeUnCambioAltrove() {
    val pending = SearchSamples.settingsReadingOn.copy(
      textReading = SearchSamples.settingsReadingOn.textReading.copy(syncPending = true),
    )
    show(pending)
    rule.onNodeWithText("La scelta vale già qui. Arriva agli altri dispositivi quando questo si collega al NAS.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Se la accendi o la spegni da un altro dispositivo, su questo vale quando i dati personali si sincronizzano (a casa o con la VPN accesa).").performScrollTo().assertIsDisplayed()
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

  /** IX-2: "riprendo fra poco" dopo una tappa finita per tempo, non "Questo dispositivo non analizza le foto". */
  @Test
  fun ix2_dopoUnaTappaFinitaPerTempoLaPaginaDiceRiprendoFraPoco() {
    val state = SearchSamples.settingsIndexer.copy(
      indexer = SearchSamples.settingsIndexer.indexer.copy(status = AiStatus.Waiting(AiWaitReason.NEXT_STAGE)),
    )
    show(state)
    rule.onNodeWithText("Riprendo fra poco: l’analisi continua con la prossima tappa.").performScrollTo().assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Questo dispositivo non analizza le foto.").fetchSemanticsNodes()).isEmpty()
  }
}
