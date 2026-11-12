package dev.pampa.mycloudgallery.feature.update.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto U (giro 1 delle critiche): la pagina Impostazioni -> Aggiornamenti e l'avviso, con stati di esempio ([UpdateSamples]): che cosa si vede
 * (poche parole, lo stato una volta, nessuna frase dell'engine), che cosa sente TalkBack, che cosa fanno i tasti, e che non c'e' mai un'attesa senza tasti.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h1800dp-xhdpi")
class UpdatesScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val called = mutableListOf<String>()
  private val channels = mutableListOf<UpdateChannel>()

  private val actions = UpdateActions(
    check = { called += "check" },
    install = { called += "install" },
    downloadMetered = { called += "metered" },
    cancelMetered = { called += "cancelMetered" },
    allow = { called += "allow" },
    cancelDownload = { called += "cancelDownload" },
    cancelReady = { called += "cancelReady" },
    close = { called += "close" },
    chooseChannel = { channels += it },
    later = { called += "later" },
    hide = { called += "hide" },
  )

  private fun show(content: @Composable () -> Unit) {
    rule.setContent { MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { content() } }
    rule.waitForIdle()
  }

  private fun page(state: UpdatesState) = show { UpdatesScreen(state = state, actions = actions, onBack = {}) }

  private fun prompt(state: UpdatesState) = show { UpdatePromptCard(state = state, actions = actions) }

  private fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

  private fun texts(): Set<String> =
    rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }.toSet()

  private fun choice(name: String) =
    rule.onNode(hasContentDescription(name, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

  @Test
  fun primaDiControllare_laVersioneInstallataUnaVoltaEUnTasto() {
    page(UpdateSamples.idle)
    // In pagina: il titolo, la versione installata (una volta), il tasto, le versioni da ricevere con le due scelte. Nient'altro da leggere.
    assertThat(texts()).containsExactly(
      string(R.string.update_title),
      string(R.string.update_current, "1.0.0"),
      string(R.string.update_check_now),
      // L'intestazione di sezione comune scrive in maiuscolo.
      string(R.string.update_channel).uppercase(),
      string(R.string.update_channel_stable),
      string(R.string.update_channel_stable_detail),
      string(R.string.update_channel_beta),
      string(R.string.update_channel_beta_detail),
      // Con Stabile (di serie) nessuna riga «Se torno a Stabile»: e' solo per chi riceve le beta (giro 2, UU2-5).
    )
    rule.onNodeWithText(string(R.string.update_check_now)).performClick()
    assertThat(called).containsExactly("check")
  }

  @Test
  fun ilCanaleSonoDueScelteConIlSegno_StabileDiSerie_senzaLaRigaDiChiTorna() {
    page(UpdateSamples.upToDate)
    val stable = choice(string(R.string.update_channel_stable)).fetchSemanticsNode()
    val beta = choice(string(R.string.update_channel_beta)).fetchSemanticsNode()
    assertThat(stable.config.getOrNull(SemanticsProperties.Selected)).isTrue()
    assertThat(stable.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(string(R.string.update_selected))
    assertThat(beta.config.getOrNull(SemanticsProperties.Selected)).isFalse()
    choice(string(R.string.update_channel_beta)).performClick()
    assertThat(channels).containsExactly(UpdateChannel.BETA)
    assertThat(rule.onAllNodesWithText(string(R.string.update_channel_beta_why_label), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun conBetaScelto_laIDiChiTornaAStabile() {
    // Giro 2, UU2-5: «Se torno a Stabile» solo con Beta scelto; dietro la «i», chi torna tiene la beta finche' non ne esce una stabile piu' nuova.
    page(UpdateSamples.foundBeta)
    rule.onNodeWithText(string(R.string.update_channel_beta_why_label), useUnmergedTree = true).assertExists()
    rule.onNode(hasContentDescription(string(R.string.update_channel_beta_why), substring = true)).assertExists()
  }

  @Test
  fun conLAppAggiornataControllaAdessoEUnTastoLeggero() {
    page(UpdateSamples.upToDate)
    rule.onNodeWithText(string(R.string.update_up_to_date)).assertExists()
    rule.onNodeWithText(string(R.string.update_current, "1.0.0")).assertExists()
    rule.onNodeWithText(string(R.string.update_check_now)).assertExists()
  }

  @Test
  fun unaVersioneNuova_daQualeAQualePesoNovitaEAggiorna() {
    page(UpdateSamples.found)
    rule.onNodeWithText(string(R.string.update_found)).assertExists()
    // UU-7 e UU-13: le due versioni e il peso in una riga, «110 MB».
    rule.onNodeWithText("1.0.0 → 1.1.0 · 110 MB").assertExists()
    rule.onNodeWithText(string(R.string.update_whats_new)).assertExists()
    // La nota di rilascio senza Markdown, a punti (le righe a capo rientrano sotto il testo).
    rule.onNodeWithText("Album più veloci da aprire.").assertExists()
    assertThat(texts().none { it.contains("**") }).isTrue()
    // Il `### Novità` del changelog non si ripete sotto il titolo del riquadro.
    val whatsNew = string(R.string.update_whats_new)
    assertThat(texts().none { it != whatsNew && it.startsWith(whatsNew) }).isTrue()
    rule.onNodeWithText(string(R.string.update_action)).performClick()
    assertThat(called).containsExactly("install")
  }

  @Test
  fun mentreScarica_lAnelloEUnaBarraDAvanzamento_eLeSceltePiuTenuiESpente() {
    page(UpdateSamples.downloading)
    // UU-11: l'anello e' un avanzamento vero e annuncia ogni quarto (42 -> 25).
    val ring = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
    assertThat(ring.config[SemanticsProperties.ProgressBarRangeInfo].current).isWithin(0.001f).of(0.42f)
    assertThat(ring.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(string(R.string.update_progress_spoken, 25))
    rule.onNodeWithText(string(R.string.update_downloading)).assertExists()
    // Mentre scarica il canale non si cambia, e TalkBack lo dice.
    assertThat(choice(string(R.string.update_channel_beta)).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)).isTrue()
    choice(string(R.string.update_channel_beta)).performClick()
    assertThat(channels).isEmpty()
    rule.onNodeWithText(string(R.string.update_cancel)).performClick()
    assertThat(called).containsExactly("cancelDownload")
  }

  @Test
  fun suiDatiMobiliPrimaSiChiede_ilPesoUnaVolta() {
    page(UpdateSamples.metered)
    rule.onNodeWithText(string(R.string.update_metered)).assertExists()
    assertThat(rule.onAllNodesWithText("110 MB", substring = true).fetchSemanticsNodes()).hasSize(1)
    rule.onNodeWithText(string(R.string.update_download_anyway)).performClick()
    rule.onNodeWithText(string(R.string.update_cancel)).performClick()
    assertThat(called).containsExactly("metered", "cancelMetered").inOrder()
  }

  @Test
  fun ilPermesso_unaFiguraEUnaRigaEsatta_ilPercheDietroLaI() {
    page(UpdateSamples.permission)
    rule.onNodeWithText(string(R.string.update_permission)).assertExists()
    rule.onNodeWithText(string(R.string.update_permission_detail)).assertExists()
    rule.onNodeWithContentDescription(string(R.string.update_permission_why)).assertExists()
    rule.onNodeWithText(string(R.string.update_allow)).performClick()
    rule.onNodeWithText(string(R.string.update_close)).performClick()
    assertThat(called).containsExactly("allow", "close").inOrder()
  }

  @Test
  fun prontaDaInstallare_InstallaEAnnulla_eDiceCheLAppSiChiude() {
    // UU-1 e UU-2: mai un'attesa senza tasti; prima della sessione una riga dice che l'app si chiude.
    page(UpdateSamples.ready)
    rule.onNodeWithText(string(R.string.update_ready)).assertExists()
    rule.onNodeWithText(string(R.string.update_closes)).assertExists()
    rule.onNodeWithText(string(R.string.update_install)).performClick()
    rule.onNodeWithText(string(R.string.update_cancel)).performClick()
    assertThat(called).containsExactly("install", "cancelReady").inOrder()
  }

  @Test
  fun annullataInAndroid_nonEUnGuasto() {
    page(UpdateSamples.interrupted)
    rule.onNodeWithText(string(R.string.update_ready)).assertExists()
    rule.onNodeWithText(string(R.string.update_interrupted)).assertExists()
    assertThat(rule.onAllNodesWithText(string(R.string.update_failed)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun mentreInstalla_unaRigaDiceCheLAppSiChiude() {
    page(UpdateSamples.installing)
    rule.onNodeWithText(string(R.string.update_installing)).assertExists()
    rule.onNodeWithText(string(R.string.update_closes)).assertExists()
  }

  @Test
  fun unGuastoCheNonSiRisolveRiprovando_haSoloChiudiEAChiRivolgersi() {
    page(UpdateSamples.signature)
    rule.onNodeWithText(string(R.string.update_failed)).assertExists()
    rule.onNodeWithText(string(R.string.update_problem_signature) + ". " + string(R.string.update_tell_sender)).assertExists()
    assertThat(rule.onAllNodesWithText(string(R.string.update_retry)).fetchSemanticsNodes()).isEmpty()
    rule.onNodeWithText(string(R.string.update_close)).performClick()
    assertThat(called).containsExactly("close")
  }

  @Test
  fun unGuastoCheSiRisolveRiprovando_haRiprova() {
    page(UpdateSamples.noSpace)
    rule.onNodeWithText(string(R.string.update_problem_space)).assertExists()
    rule.onNodeWithText(string(R.string.update_retry)).performClick()
    assertThat(called).containsExactly("install")
  }

  @Test
  fun nessunaParolaDellEngineAdOgniStato() {
    // UU-6: «APK», «firma», il registro tecnico non arrivano mai a schermo.
    val states = listOf(UpdateSamples.signature, UpdateSamples.noSpace, UpdateSamples.unusable, UpdateSamples.permission, UpdateSamples.interrupted)
    show { Column { for (state in states) UpdatePanel(state = state, actions = actions) } }
    assertThat(texts()).isNotEmpty()
    assertThat(texts().none { it.contains("APK") || it.contains("firma", ignoreCase = true) || it.contains("Play Protect") }).isTrue()
  }

  @Test
  fun unaVersionePubblicataMale_nonAncoraPronta_senzaAggiorna() {
    page(UpdateSamples.unusable)
    rule.onNodeWithText(string(R.string.update_unusable)).assertExists()
    // La clessidra dice gia' «aspetta»: nessun «Riprova domani» accanto a «Controlla adesso» (giro 2, UU2-10).
    rule.onNodeWithText(string(R.string.update_check_now)).assertExists()
    assertThat(texts().none { it.contains("domani") }).isTrue()
    assertThat(rule.onAllNodesWithText(string(R.string.update_action)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun nonRiescoAControllare_Riprova() {
    // Giro 2, UU2-10: dopo un controllo non riuscito il tasto e' «Riprova» (lo stesso controllo).
    page(UpdateSamples.unavailable)
    rule.onNodeWithText(string(R.string.update_unavailable)).assertExists()
    rule.onNodeWithText(string(R.string.update_retry)).performClick()
    assertThat(called).containsExactly("check")
  }

  @Test
  fun dentroLaVersioneFermata_senzaVersioneNuova_arrivaPresto_maiAggiornata() {
    // Giro 2, UU2-7: «Questa versione e' stata fermata» sopra e «App aggiornata ✓» sotto si contraddicevano.
    assertThat(UpdatePanels.of(UpdateSamples.upToDate, stopped = true).figure).isEqualTo(UpdateFigure.COMING)
    assertThat(UpdatePanels.of(UpdateSamples.upToDate, stopped = true).primaryFilled).isTrue()
    assertThat(statusOf(UpdateFigure.COMING)).isNotEqualTo(statusOf(UpdateFigure.UP_TO_DATE))
    show { UpdatePanel(state = UpdateSamples.upToDate, actions = actions, stopped = true) }
    rule.onNodeWithText(string(R.string.update_coming)).assertExists()
    assertThat(rule.onAllNodesWithText(string(R.string.update_up_to_date)).fetchSemanticsNodes()).isEmpty()
    rule.onNodeWithText(string(R.string.update_check_now)).performClick()
    assertThat(called).containsExactly("check")
  }

  @Test
  fun prontaNonHaLaSpuntaDiAggiornata() {
    // Giro 2, UU2-3: la spunta verde vuol dire «fatto»; «Pronta» ha il segno di chi deve toccare, come «Nuova versione».
    assertThat(statusOf(UpdateFigure.READY)).isNotEqualTo(statusOf(UpdateFigure.INSTALLED))
    assertThat(statusOf(UpdateFigure.READY)).isEqualTo(statusOf(UpdateFigure.FOUND))
    assertThat(statusOf(UpdateFigure.UP_TO_DATE)).isEqualTo(statusOf(UpdateFigure.INSTALLED))
  }

  @Test
  fun lAvvisoDiUnGuasto_ChiudiLoChiudeDavvero_senzaContareComeDopo() {
    // Giro 2, UU2-2: «Chiudi» sull'avviso chiude la scheda, non la riporta a «Nuova versione». Giro 3, UU3-6: chiude il guasto e nasconde la scheda,
    // senza «Dopo» (nessuno dei tre «Dopo» consumato).
    prompt(UpdateSamples.signature)
    rule.onNodeWithText(string(R.string.update_close)).performClick()
    assertThat(called).containsExactly("close", "hide").inOrder()
  }

  @Test
  fun lAvvisoPronta_DopoEInstalla_senzaLaXAccantoAdAnnulla() {
    // Giro 3, UU3-4: la «×» (nasconde) e «Annulla» (butta il file da 110 MB) vicini si confondevano. Sull'avviso: «Dopo» e «Installa»; «Annulla» sta
    // nella pagina.
    prompt(UpdateSamples.ready)
    assertThat(rule.onAllNodesWithText(string(R.string.update_cancel)).fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodes(hasContentDescription(string(R.string.update_hide))).fetchSemanticsNodes()).isEmpty()
    rule.onNodeWithText(string(R.string.update_later)).performClick()
    rule.onNodeWithText(string(R.string.update_install)).performClick()
    assertThat(called).containsExactly("later", "install").inOrder()
  }

  @Test
  fun lAvviso_nuovaVersioneAggiornaEDopo() {
    prompt(UpdateSamples.found)
    rule.onNodeWithText(string(R.string.update_prompt_title)).assertExists()
    rule.onNodeWithText("1.1.0 · 110 MB").assertExists()
    rule.onNodeWithText(string(R.string.update_later)).performClick()
    rule.onNodeWithText(string(R.string.update_action)).performClick()
    assertThat(called).containsExactly("later", "install").inOrder()
  }

  @Test
  fun lAvvisoMentreScarica_soloAnnulla_eLaXLoNasconde() {
    // UU-10: «Dopo» non e' «nascondi»; mentre scarica c'e' la «×».
    prompt(UpdateSamples.downloading)
    rule.onNodeWithText(string(R.string.update_cancel)).assertExists()
    assertThat(rule.onAllNodesWithText(string(R.string.update_later)).fetchSemanticsNodes()).isEmpty()
    rule.onNodeWithContentDescription(string(R.string.update_hide)).performClick()
    assertThat(called).containsExactly("hide")
  }

  @Test
  fun lAvvisoAggiornata_unaVoltaConOk() {
    prompt(UpdateSamples.justUpdated)
    rule.onNodeWithText(string(R.string.update_installed)).assertExists()
    rule.onNodeWithText("1.1.0").assertExists()
    // Giro 2, UU2-11: «Ok» sta nella riga del testo (al posto della «×»), la scheda e' di una riga sola.
    val title = rule.onNodeWithText(string(R.string.update_installed)).fetchSemanticsNode().boundsInRoot
    val ok = rule.onNodeWithText(string(R.string.update_ok)).fetchSemanticsNode().boundsInRoot
    assertThat(ok.top).isLessThan(title.bottom + 1f)
    rule.onNodeWithText(string(R.string.update_ok)).performClick()
    assertThat(called).containsExactly("later")
  }

  @Test
  fun iTastiDellAvvisoSonoAltiAlmeno48dp() {
    // UU-11: «Dopo» e «Aggiorna» nella scheda, la «×»: almeno 48 dp per il dito.
    val states = listOf(UpdateSamples.found, UpdateSamples.downloading, UpdateSamples.ready)
    show { Column { for (state in states) UpdatePromptCard(state = state, actions = actions) } }
    val min = with(rule.density) { 48.dp.toPx() } - 0.5f
    val heights = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.size.height }
    // Dopo, Aggiorna; Annulla e la «×»; Dopo e Installa (giro 3: niente «×» con il file pronto).
    assertThat(heights).hasSize(6)
    for (height in heights) assertThat(height.toFloat()).isAtLeast(min)
  }

  @Test
  fun lAvvisoNonHaControllaAdesso() {
    // Il modello dell'avviso non ha tasti di controllo: «Controlla adesso» e' della pagina.
    assertThat(promptButtons(UpdatePanels.of(UpdateSamples.upToDate)).mapNotNull { it.action }).isEmpty()
  }
}
