package dev.pampa.mycloudgallery.feature.people.activation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.feature.people.contract.ActivationProblem
import dev.pampa.mycloudgallery.feature.people.contract.ActivationRequirements
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina di attivazione di Persone (docs/fase8-progetto.md §2.1, D3, D5, D34; riga P7 del §4.4): il testo del progetto, il consenso con la versione del
 * testo e l'istante della pagina, "Attiva" e "Non ora" con lo stesso peso, i requisiti, l'esito, e l'accessibilita' (TalkBack, testo a 1,3x, bersagli da 48 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class FacesActivationScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val met = ActivationRequirements(privateArea = true, indexers = 1, thisDeviceIndexer = false, canBecomeIndexer = true, localOnly = false)
  private val explain = FacesActivationState.Explain(FacesSwitch.CURRENT_TEXT_VERSION, met)

  private class Calls {
    var consent: FacesActivationConsent? = null
    var notNow = 0
    var become = 0
    var licenses = 0
    var retry = 0
    var done = 0
    var back = 0
  }

  private fun show(state: FacesActivationState?, calls: Calls = Calls(), dark: Boolean = false, fontScale: Float? = null): Calls {
    rule.setPeopleContent(dark = dark, fontScale = fontScale) {
      FacesActivationScreen(
        state = state,
        onActivate = { calls.consent = it },
        onNotNow = { calls.notNow++ },
        onBecomeIndexer = { calls.become++ },
        onOpenLicenses = { calls.licenses++ },
        onRetry = { calls.retry++ },
        onDone = { calls.done++ },
        onBack = { calls.back++ },
      )
    }
    return calls
  }

  private fun text(vararg phrases: String) {
    for (phrase in phrases) node(phrase, substring = true).assertIsDisplayed()
  }

  private fun count(phrase: String): Int = rule.onAllNodesWithText(phrase, substring = true).fetchSemanticsNodes().size

  private fun exactCount(phrase: String): Int = rule.onAllNodesWithText(phrase).fetchSemanticsNodes().size

  // --- La spiegazione ---------------------------------------------------------------------------------------------------------------

  /** Porta in vista la riga con questo testo: la pagina e' una lista pigra, e cio' che sta sotto lo schermo non esiste finche' non ci si arriva scorrendo. */
  private fun node(label: String, substring: Boolean = false): SemanticsNodeInteraction {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label, substring = substring))
    return rule.onNodeWithText(label, substring = substring)
  }

  @Test
  fun laPaginaDiceCosaFaDoveRestanoIDatiEComeSiCancella() {
    show(explain)
    text(
      "Persone raggruppa le tue foto per volto",
      "Nessuna foto e nessun volto escono da casa.",
      "I gruppi e i nomi sono solo tuoi",
      "Nessun familiare li vede, nemmeno per le foto dell’area comune.",
      "Nelle foto ci sono anche amici e bambini",
      "«Disattiva e cancella»",
      "un dispositivo spento lo fa appena si riaccende",
      "Su ogni tuo dispositivo puoi dire",
      "YuNet (licenza MIT) e SFace (licenza Apache 2.0), dentro l’app.",
    )
  }

  @Test
  fun attivaDaIlConsensoConLaVersioneDelTestoELIstanteInCuiLaPaginaEraSuSchermo() {
    val calls = show(explain)
    val before = System.nanoTime()
    node("Attiva").performClick()
    val consent = calls.consent
    assertThat(consent).isNotNull()
    assertThat(consent!!.textVersion).isEqualTo(FacesSwitch.CURRENT_TEXT_VERSION)
    assertThat(consent.textVersion).isEqualTo(FacesActivationText.VERSION)
    // L'istante e' quello della pagina sullo schermo (monotono), prima del tocco.
    assertThat(consent.shownAtMonotonicNanos).isAtMost(before)
    assertThat(calls.notNow).isEqualTo(0)
  }

  @Test
  fun nonOraNonDaConsensoENonCambiaNiente() {
    val calls = show(explain)
    node("Non ora").performClick()
    assertThat(calls.consent).isNull()
    assertThat(calls.notNow).isEqualTo(1)
  }

  @Test
  fun senzaUnTocco_NessunConsensoEsiste() {
    val calls = show(explain)
    assertThat(calls.consent).isNull()
  }

  @Test
  fun iDuePulsantiHannoLoStessoPesoELaStessaMisura() {
    show(explain)
    val yes = node("Attiva").fetchSemanticsNode().boundsInRoot
    val no = rule.onNodeWithText("Non ora").fetchSemanticsNode().boundsInRoot
    assertThat(yes.width).isWithin(1f).of(no.width)
    assertThat(yes.height).isWithin(1f).of(no.height)
    assertThat(yes.top).isWithin(1f).of(no.top)
  }

  @Test
  fun ilLinkAlleLicenzeApreInformazioniELicenze() {
    val calls = show(explain)
    node("Informazioni e licenze").performClick()
    assertThat(calls.licenses).isEqualTo(1)
  }

  @Test
  fun sulleShareVereLaPaginaDiceCheOgniDispositivoHaLeSue() {
    show(FacesActivationState.Explain(1, met.copy(localOnly = true)))
    text("Per ora ogni dispositivo ha le sue persone", "Finché la sincronizzazione delle persone non è accesa")
  }

  @Test
  fun sullaShareDiProvaLaNotaNonCe() {
    show(explain)
    assertThat(count("ogni dispositivo ha le sue")).isEqualTo(0)
  }

  // --- Accessibilita' -----------------------------------------------------------------------------------------------------------------

  /** TalkBack: i due pulsanti dicono cosa fanno (non solo "Attiva"), e le parole del pulsante restano quelle visibili. */
  @Test
  fun iPulsantiHannoUnaDescrizionePerTalkBack() {
    show(explain)
    rule.onNode(hasContentDescription("Attiva il riconoscimento dei volti per il tuo account")).assertExists()
    rule.onNode(hasContentDescription("Non ora: Persone resta spenta")).assertExists()
    rule.onNode(hasContentDescription("Apri Informazioni e licenze, con la nota sui dati usati per addestrare i modelli")).assertExists()
  }

  @Test
  fun iBersagliSonoDaAlmeno48Dp() {
    show(explain)
    val density = rule.density.density
    for (label in listOf("Attiva", "Non ora", "Informazioni e licenze")) {
      node(label)
      assertThat(rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height / density).isAtLeast(47.5f)
    }
  }

  @Test
  fun ilTestoA13xSiLeggeEIPulsantiRestanoDa48Dp() {
    show(explain, fontScale = 1.3f)
    val density = rule.density.density
    for (label in listOf("Attiva", "Non ora")) {
      // Con il carattere grande la pagina scorre: i pulsanti si portano in vista prima di misurarli.
      node(label).assertIsDisplayed()
      assertThat(rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height / density).isAtLeast(47.5f)
    }
    text("Nessuna foto e nessun volto escono da casa.")
  }

  @Test
  fun ilTemaScuroCompone() {
    show(explain, dark = true)
    node("Attiva").assertIsDisplayed()
  }

  // --- Requisiti (D5, E-F10) --------------------------------------------------------------------------------------------------------------

  @Test
  fun senzaAreaPrivataDiceSoloQuello() {
    val calls = show(FacesActivationState.NoPrivateArea)
    text("Serve un’area privata sul NAS")
    assertThat(count("Persone raggruppa le tue foto")).isEqualTo(0)
    assertThat(exactCount("Attiva")).isEqualTo(0)
    node("Chiudi").performClick()
    assertThat(calls.done).isEqualTo(1)
    assertThat(calls.consent).isNull()
  }

  @Test
  fun senzaIndicizzatoriProponeFalloSuQuestoDispositivo() {
    val calls = show(FacesActivationState.NeedsIndexer(canBecomeIndexer = true))
    text("Nessun tuo dispositivo analizza le foto")
    node("Fallo su questo dispositivo").performClick()
    assertThat(calls.become).isEqualTo(1)
    assertThat(calls.consent).isNull()
  }

  @Test
  fun senzaIndicizzatoriEConUnRuoloNonOffribileDiceDoveAccenderlo() {
    show(FacesActivationState.NeedsIndexer(canBecomeIndexer = false))
    text("Nessun tuo dispositivo analizza le foto", "Accendi «Analizza le foto» su uno dei tuoi dispositivi")
    assertThat(count("Fallo su questo dispositivo")).isEqualTo(0)
  }

  // --- Dopo "Attiva" ------------------------------------------------------------------------------------------------------------------

  @Test
  fun mentreAttivaDiceCheAttiva() {
    show(FacesActivationState.Activating)
    text("Attivo Persone…")
    assertThat(count("Non ora")).isEqualTo(0)
  }

  @Test
  fun attivataDiceCheVaSubito_ESeLaVoceAspettaLaSincronizzazioneLoDice() {
    val calls = show(FacesActivationState.Activated(syncPending = true))
    text("Persone è attiva", "Su questo dispositivo vale da subito.", "Sto per dirlo agli altri tuoi dispositivi")
    node("Fatto").performClick()
    assertThat(calls.done).isEqualTo(1)
  }

  @Test
  fun attivataConLaVoceGiaArrivata_NonDiceDiAspettare() {
    show(FacesActivationState.Activated(syncPending = false))
    text("Persone è attiva")
    assertThat(count("Sto per dirlo")).isEqualTo(0)
  }

  /** TalkBack annuncia da solo l'esito: il messaggio e' una regione "live". */
  @Test
  fun lEsitoSiAnnuncia() {
    show(FacesActivationState.Activated(syncPending = false))
    rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
  }

  @Test
  fun untestoCambiatoMentreSiLeggevaSiRilegge() {
    val calls = show(FacesActivationState.Failed(ActivationProblem.STALE_CONSENT))
    text("La pagina è cambiata", "Il testo di questa pagina è cambiato mentre la leggevi.")
    node("Riprova").performClick()
    assertThat(calls.retry).isEqualTo(1)
    assertThat(calls.consent).isNull()
  }

  @Test
  fun unaVoceDiUnAppPiuNuovaDiceDiAggiornare_ENonOffreDiRiprovare() {
    val calls = show(FacesActivationState.Failed(ActivationProblem.NEEDS_UPDATE))
    text("Aggiorna l’app")
    assertThat(count("Riprova")).isEqualTo(0)
    node("Chiudi").performClick()
    assertThat(calls.done).isEqualTo(1)
  }

  @Test
  fun quandoNonEDisponibileNonEcambiatoNienteEsiPuoRiprovare() {
    val calls = show(FacesActivationState.Failed(ActivationProblem.UNAVAILABLE))
    text("Persone non è disponibile adesso", "Non è cambiato niente")
    node("Riprova").performClick()
    assertThat(calls.retry).isEqualTo(1)
  }

  @Test
  fun finoAllaLetturaDeiRequisitiLaPaginaAspetta() {
    show(null)
    text("Controllo che Persone si possa accendere")
    assertThat(exactCount("Attiva")).isEqualTo(0)
  }
}
