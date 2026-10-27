package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.duplicates.CleanupPlan
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.feature.library.collections.ComposeScreenTest
import dev.pampa.mycloudgallery.feature.library.collections.TABLET_QUALIFIERS
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeCleanupNas
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeFullHashSwitch
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeIdenticalDuplicates
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakePhoneCopyCheck
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeSimilarShots
import dev.pampa.mycloudgallery.feature.library.utility.testing.sampleMediaId
import dev.pampa.mycloudgallery.feature.library.utility.testing.samplePlanOf
import dev.pampa.mycloudgallery.feature.library.utility.testing.sampleRefOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * La pagina dei Doppioni (Fase 7, P8, D25, D27; E-U1, E-U3): le due schede, i gruppi con le copie e i loro ruoli, "Rivedi", il foglio di eliminazione
 * esistente, l'interruttore notturno. Compose su JVM (Robolectric) con un host dei fogli come alla radice dell'app; il controller e' quello vero con i
 * doppioni, il NAS e il cestino di sistema finti.
 *
 * Qui si prova anche l'accessibilita': ogni controllo toccabile e' almeno 48 dp, e gli scatti si leggono come caselle con il loro stato.
 */
open class DuplicatesScreenTest : ComposeScreenTest() {

  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val loader = fakeThumbnailLoader()
  protected val system = FakePhoneMediaIntents(canTrash = true)
  protected val identical = FakeIdenticalDuplicates()
  protected val similar = FakeSimilarShots()
  protected val nas = FakeCleanupNas()
  protected val switch = FakeFullHashSwitch()

  @After
  fun stop() {
    scope.cancel()
  }

  protected fun controller(fullHash: FullHashSwitch = switch) =
    DuplicatesController(identical, similar, nas, SenderIntents(system, testIntentSender()), fullHash, scope, phoneCheck = FakePhoneCopyCheck())

  /**
   * L'orologio monotono del foglio (R18): ogni lettura avanza di [clockStep]. Di serie un secondo, cosi' il tocco su "Elimina" arriva oltre i 600 ms dalla
   * comparsa del foglio; con 0 il tempo non passa e il tocco non conta.
   */
  protected var clockMs: Long = 0
  protected var clockStep: Long = 1_000

  @Composable
  private fun Stage(content: @Composable () -> Unit) {
    val host = rememberFluidGlassModalHostState()
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
        FluidGlassModalHost(state = host, backdrop = backdrop)
      }
    }
  }

  protected fun showPage(c: DuplicatesController, dark: Boolean = false, fontScale: Float? = null) = show(dark = dark, fontScale = fontScale) {
    Stage {
      DuplicatesHost(
        controller = c,
        loader = loader,
        refOf = ::sampleRefOf,
        familyName = { share -> if (share == "marco") "Marco" else null },
        onBack = {},
        monotonicMs = { clockMs += clockStep; clockMs },
      )
    }
  }

  /** Porta in vista la voce con questo testo (la lista compone solo cio' che si vede) e aspetta che si assesti. */
  protected fun scrollToText(text: String, substring: Boolean = false) {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text, substring = substring))
    composeRule.waitForIdle()
  }

  // --- Identici --------------------------------------------------------------------------------------------------------------

  @Test
  fun mostraLeDueSchedeIlRiepilogoELaPuliziaGuidata() {
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Doppioni").assertExists()
    composeRule.onNodeWithText("Identici").assertExists()
    composeRule.onNodeWithText("Simili").assertExists()
    composeRule.onNodeWithText("2 gruppi da pulire").assertExists()
    composeRule.onNodeWithText("Pulizia guidata").assertExists()
    // Si tolgono tre copie; il riepilogo lo dice e dice che restano 30 giorni nei cestini.
    composeRule.onNodeWithText("Si tolgono 3 copie", substring = true).assertExists()
    composeRule.onNodeWithText("30 giorni", substring = true).assertExists()
  }

  @Test
  fun ogniGruppoDiceDoveSonoLeCopieEChe_cosaNeSara() {
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Foto · 2 copie · sul telefono").assertExists()
    composeRule.onNodeWithContentDescription("Telefono, DCIM/Camera/IMG_0001.jpg, Da tenere", substring = true).assertExists()
    composeRule.onNodeWithContentDescription("Telefono, Download/IMG_0001 (1).jpg, Da eliminare", substring = true).assertExists()
    scrollToText("Foto · 4 copie · sul NAS")
    composeRule.onNodeWithContentDescription("verificata, Da tenere", substring = true).assertExists()
    composeRule.onNodeWithContentDescription("Backup/Pixel 9/2026/10/IMG_0002.jpg", substring = true).assertExists()
  }

  @Test
  fun e_u1_UnaCopiaNellAreaDiUnAltroSiDice_ENonSiProponeDaTogliere() {
    showPage(controller())
    composeRule.waitForIdle()

    scrollToText("1 copia non si tocca", substring = true)
    composeRule.onNodeWithText("1 copia non si tocca", substring = true).assertExists()
    scrollToText("Foto · 4 copie · sul NAS")
    assertThat(composeRule.onAllNodes(hasContentDescription("NAS · area di Marco", substring = true)).fetchSemanticsNodes()).isNotEmpty()
    assertThat(composeRule.onAllNodes(hasContentDescription("Resta: non hai il permesso", substring = true)).fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun unVideoSenzaHashCompleto_EDaVerificare() {
    showPage(controller())
    composeRule.waitForIdle()

    scrollToText("Video · 2 copie · sul NAS")
    composeRule.onNodeWithText("Video · 2 copie · sul NAS").assertExists()
    assertThat(composeRule.onAllNodesWithText("Da verificare").fetchSemanticsNodes()).isNotEmpty()
    // Mai una copia da eliminare in un gruppo da verificare.
    assertThat(composeRule.onAllNodes(hasContentDescription("Video/Estate/VID_0003.mp4, Da eliminare", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun inUnGruppoConUnaCopiaDiAlbumCondivisoNientePuoEssereTolto() {
    showPage(controller())
    composeRule.waitForIdle()

    scrollToText("Niente da togliere")
    composeRule.onNodeWithText("Niente da togliere").assertExists()
    assertThat(composeRule.onAllNodes(hasContentDescription("Resta: è in un album condiviso", substring = true)).fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun rivedi_MostraLeCopieEPermetteDiScegliereQuellaDaTenere() {
    val c = controller()
    showPage(c)
    composeRule.waitForIdle()

    // Il primo gruppo e' quello del telefono: tiene la copia di DCIM, toglie quella di Download.
    composeRule.onAllNodesWithText("Rivedi")[0].performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Tieni questa")[0].performClick()
    composeRule.waitForIdle()

    val group = c.state.value.identical.groups.first { it.group.media == sampleMediaId(1) }
    assertThat(group.keep!!.location.value).isEqualTo(11L)
    assertThat(group.remove.map { it.location.value }).containsExactly(10L)
    composeRule.onNodeWithText("Torna alla proposta").assertExists()
  }

  @Test
  fun lePulizia_ApreIlFoglioEsistente_ENonCancellaNiente() {
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Pulizia guidata").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Elimina ovunque").assertExists()
    composeRule.onNodeWithText("Annulla").assertExists()
    composeRule.onNodeWithText("Restano 30 giorni nel cestino", substring = true).assertExists()
    // L'area di un altro familiare c'e' nell'elenco e non si tocca (E-U1).
    composeRule.onNodeWithText("1 copia non si tocca: non hai il permesso.").assertExists()
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()

    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Elimina ovunque").assertCountEquals(0)
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun dp6_ilTitoloDelFoglioContaLeCopieEDiceIGb_ELaCopiaComuneDiceCheSparisceVeramente() {
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Pulizia guidata").performClick()
    composeRule.waitForIdle()

    // 3 copie (una del telefono e due del NAS), non "N foto"; e ci sono i GB.
    composeRule.onNodeWithText("Eliminare 3 copie (", substring = true).assertExists()
    composeRule.onAllNodesWithText("foto?", substring = true).assertCountEquals(0)
    // E-D8: una copia dell'area comune la vedono tutti i familiari.
    composeRule.onNodeWithText("Sparirà per tutti i familiari", substring = true).assertExists()
  }

  @Test
  fun r18_UnTocco_PrimaDelTempoMinimoDallaComparsaDelFoglio_NonEliminaNiente() {
    clockStep = 0
    identical.planOverride = samplePlanOf(identical.proposals).let { CleanupPlan(it.groups, emptyList(), it.nas, 0, 0) }
    showPage(controller())
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Pulizia guidata").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Elimina ovunque").performClick()
    composeRule.waitForIdle()

    // L'orologio non e' avanzato: il tocco non vale, il foglio resta e il NAS non e' stato toccato.
    assertThat(nas.calls).isEmpty()
    composeRule.onNodeWithText("Elimina ovunque").assertExists()

    // Passato il tempo, lo stesso tocco conta.
    clockStep = 1_000
    composeRule.onNodeWithText("Elimina ovunque").performClick()
    composeRule.waitForIdle()
    assertThat(nas.calls).hasSize(1)
  }

  @Test
  fun ilTocco_SuEliminaOvunque_ManodaLaPuliziaDelNas() {
    // Solo copie del NAS: nessun dialogo di sistema da lanciare, la pulizia parte e la pagina lo dice.
    identical.planOverride = samplePlanOf(identical.proposals).let { CleanupPlan(it.groups, emptyList(), it.nas, 0, 0) }
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Pulizia guidata").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Elimina ovunque").performClick()
    composeRule.waitForIdle()

    assertThat(nas.calls).hasSize(1)
    assertThat(nas.calls.single().first).hasSize(2)
    assertThat(system.trashRequests).isEmpty()
    composeRule.onAllNodesWithText("Elimina ovunque").assertCountEquals(0)
    composeRule.onNodeWithText("Pulizia avviata", substring = true).assertExists()
  }

  // --- Correzioni F, R3: un file di un album condiviso in cui non si puo' eliminare -----------------------------------------------------------

  @Test
  fun r3_UnaSelezioneMista_LAvvisoDiceQuanteCopieSonoPartiteEQuanteNoEPerche() {
    identical.planOverride = samplePlanOf(identical.proposals).let { CleanupPlan(it.groups, emptyList(), it.nas, 0, 0) }
    nas.protectedAmong = { copies -> copies.drop(1) }
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Pulizia guidata").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Elimina ovunque").performClick()
    composeRule.waitForIdle()

    // «Pulizia avviata per 1 copia ... Non toccate: 1 copia, perche' fanno parte di un album condiviso...»: la pagina dice cosa e' partito e cosa no.
    composeRule.onNodeWithText("Pulizia avviata per 1 copia sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("Non toccate: 1 copia, perché fanno parte di un album condiviso in cui non puoi eliminarle, spostarle o rinominarle", substring = true).assertExists()
  }

  @Test
  fun r3_SeTutteLeCopieSonoDiUnAlbumCondiviso_LAvvisoDiceCheNonERiuscitoEPerche() {
    identical.planOverride = samplePlanOf(identical.proposals).let { CleanupPlan(it.groups, emptyList(), it.nas, 0, 0) }
    nas.protectedAmong = { copies -> copies }
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Pulizia guidata").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Elimina ovunque").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Non è riuscito: le 2 copie sul NAS fanno parte di un album condiviso in cui non puoi eliminarle, spostarle o rinominarle", substring = true).assertExists()
    composeRule.onNodeWithText("Non è stato toccato niente", substring = true).assertExists()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun senzaDoppioniDicePercheEVuota() {
    identical.proposals = emptyList()
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Nessun doppione identico").assertExists()
    composeRule.onAllNodesWithText("Pulizia guidata").assertCountEquals(0)
  }

  @Test
  fun unaLetturaFallita_LoDice_ERiprovareRilegge() {
    identical.throwOnScan = true
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Non riesco a cercare i doppioni").assertExists()
    identical.throwOnScan = false
    composeRule.onAllNodesWithText("Riprova")[0].performClick()
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("Non riesco a cercare i doppioni").assertCountEquals(0)
    composeRule.onNodeWithText("2 gruppi da pulire").assertExists()
  }

  // --- Simili ----------------------------------------------------------------------------------------------------------------

  private fun openSimilar(): DuplicatesController {
    val c = controller()
    showPage(c)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Simili").performClick()
    composeRule.waitForIdle()
    return c
  }

  @Test
  fun leSimili_MostranoIGruppiLaMiglioreEIlTastoDiPulizia() {
    openSimilar()

    composeRule.onNodeWithText("2 gruppi di scatti simili").assertExists()
    composeRule.onNodeWithText("Elimina 2 scatti").assertExists()
    composeRule.onNodeWithText("4 scatti", substring = true).assertExists()
    composeRule.onNodeWithContentDescription("Foto 2 di 4, Da tenere, la migliore").assertExists()
    composeRule.onNodeWithContentDescription("Foto 1 di 4, Da eliminare").assertExists()
  }

  @Test
  fun e_u3_LaPreferitaELaFotoInUnAlbumSonoDaTenere_EAvvisaSeLeScegliTu() {
    val c = openSimilar()

    composeRule.onNodeWithContentDescription("Foto 3 di 4, Da tenere, preferita").assertExists()
    composeRule.onNodeWithContentDescription("Foto 4 di 4, Da tenere, in un album").assertExists()
    composeRule.onAllNodesWithText("Hai scelto di eliminare anche una preferita", substring = true).assertCountEquals(0)

    composeRule.onNodeWithContentDescription("Foto 3 di 4", substring = true).performScrollTo().performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("Foto 3 di 4, Da eliminare, preferita").assertExists()
    composeRule.onNodeWithText("Hai scelto di eliminare anche una preferita", substring = true).assertExists()
    assertThat(c.state.value.similar.clusters[0].remove).contains(sampleMediaId(103))
  }

  @Test
  fun gliScattiSiLeggonoComeCaselleConIlLoroStato() {
    openSimilar()

    composeRule.onNodeWithContentDescription("Foto 1 di 4", substring = true).assertIsOn()
    composeRule.onNodeWithContentDescription("Foto 2 di 4", substring = true).assertIsOff()
  }

  @Test
  fun inUnGruppoAlmenoUnoScattoResta_ESiDice() {
    val c = openSimilar()
    val key = c.state.value.similar.clusters[1].cluster.key
    // Il secondo gruppo ha due scatti: uno da eliminare, uno da tenere. Provare a eliminare anche l'altro non si puo'.
    composeRule.onAllNodes(hasContentDescription("Foto 1 di 2", substring = true))[0].performScrollTo().performClick()
    composeRule.waitForIdle()

    assertThat(c.state.value.similar.clusters.first { it.cluster.key == key }.keep).hasSize(1)
    composeRule.onNodeWithText("In un gruppo almeno uno scatto resta", substring = true).assertExists()
  }

  @Test
  fun laPuliziaDeiSimili_ApreIlFoglioConLeDueScelte() {
    openSimilar()

    composeRule.onNodeWithText("Elimina 2 scatti").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Solo dal telefono").assertExists()
    composeRule.onNodeWithText("Ovunque").assertExists()
    composeRule.onNodeWithText("Elimina dal telefono").assertExists()
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()
    composeRule.onNodeWithText("Ovunque").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Elimina ovunque").assertExists()
  }

  @Test
  fun seNonCeNienteDaMostrare_DicePerche() {
    similar.batches = emptyList()
    openSimilar()

    composeRule.onNodeWithText("Nessuno scatto simile").assertExists()
    composeRule.onAllNodesWithText("Elimina", substring = true).assertCountEquals(0)
  }

  // --- L'interruttore notturno (D27) -----------------------------------------------------------------------------------------

  @Test
  fun lInterruttoreNotturnoSiAccende_ESegueIlValore() {
    val c = controller()
    showPage(c)
    composeRule.waitForIdle()

    scrollToText("Controlla di notte i video del NAS")
    composeRule.onNodeWithText("Controlla di notte i video del NAS").assertExists()
    composeRule.onNodeWithText("circa otto ore", substring = true).assertExists()
    composeRule.onNodeWithText("Controlla di notte i video del NAS").performClick()
    composeRule.waitForIdle()

    assertThat(switch.sets).containsExactly(true)
    assertThat(c.state.value.nightly.on).isTrue()
    composeRule.onNodeWithText("Controlla di notte i video del NAS").performClick()
    composeRule.waitForIdle()
    assertThat(switch.sets).containsExactly(true, false).inOrder()
  }

  @Test
  fun senzaLaPortaDellInterruttore_LaRigaNonCe() {
    showPage(controller(fullHash = NoFullHashSwitch))
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("Controlla di notte i video del NAS").assertCountEquals(0)
  }

  // --- Accessibilita' e aspetto ------------------------------------------------------------------------------------------------

  /** Ogni nodo toccabile e' almeno 48 x 48 dp (la chrome e' di un altro test: qui i controlli della pagina). */
  private fun assertTargets(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() } - 1f
    val nodes = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = false).fetchSemanticsNodes()
    assertThat(nodes).isNotEmpty()
    for (node in nodes) {
      // La misura del controllo, non quella visibile: uno scatto tagliato dal bordo della riga che scorre e' largo uguale. Un nodo non ancora
      // disposto (la lista lo prepara in anticipo) non e' ancora un bersaglio.
      val b = node.size
      if (b.width == 0 && b.height == 0) continue
      val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
      assertWithMessage("$where: bersaglio \"$name\" $b").that(minOf(b.width, b.height).toFloat()).isAtLeast(min)
    }
  }

  @Test
  fun iControlliSonoAlmeno48dp_IdenticiESimili() {
    openSimilar()
    assertTargets("Simili")
    composeRule.onNodeWithText("Identici").performClick()
    composeRule.waitForIdle()
    assertTargets("Identici")
  }

  @Test
  fun conIlTestoIngrandito1_3_TuttoSiLeggeEIlTastoRestaAlmeno48dp() {
    showPage(controller(), fontScale = 1.3f)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("2 gruppi da pulire").assertExists()
    composeRule.onNodeWithText("Pulizia guidata").assertExists()
    assertTargets("testo 1,3x")
    composeRule.onNodeWithText("Pulizia guidata").performClick()
    composeRule.waitForIdle()
    // Il foglio ha i bottoni sempre in vista.
    composeRule.onNodeWithText("Elimina ovunque").assertExists()
    composeRule.onNodeWithText("Annulla").assertExists()
  }

  @Test
  fun nelTemaScuroLaPaginaSiDisegna() {
    showPage(controller(), dark = true)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("2 gruppi da pulire").assertExists()
    composeRule.onNodeWithText("Pulizia guidata").assertExists()
  }

  @Test
  fun leFinestreDelleMiniatureSeguonoLaScheda() {
    val c = controller()
    c.start()
    val identicalWindow = windowRequests(c.state.value, ::sampleRefOf)
    assertThat(identicalWindow).isNotEmpty()
    assertThat(identicalWindow.values.flatten().map { it.key.mediaId }).contains(sampleMediaId(1))
    c.selectTab(DuplicatesTab.SIMILAR)
    val similarWindow = windowRequests(c.state.value, ::sampleRefOf)
    assertThat(similarWindow.values.flatten().map { it.key.mediaId })
      .containsAtLeast(sampleMediaId(101), sampleMediaId(102), sampleMediaId(103), sampleMediaId(104))
  }
}

/** La stessa pagina sul tablet: una colonna centrata, senza controlli fuori misura. */
@Config(qualifiers = TABLET_QUALIFIERS)
class DuplicatesScreenTabletTest : DuplicatesScreenTest()
