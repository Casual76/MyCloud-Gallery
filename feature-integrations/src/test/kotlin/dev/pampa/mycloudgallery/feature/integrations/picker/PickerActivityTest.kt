package dev.pampa.mycloudgallery.feature.integrations.picker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.picker.testing.FakeEntryUnlock
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 5B (P10, D23, C17; E-H1, E-X1, E-X4, E-X7): `PickerActivity` come la apre un'altra app, con il telefono finto e il NAS finto. Un
 * intento ostile chiude subito; con le porte non installate non si mostra e non si consegna niente; con l'app bloccata si vede solo lo sblocco
 * (nessuna foto in composizione); la scelta restituisce solo il permesso di lettura; indietro annulla senza lasciare id.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class PickerActivityTest {

  @get:Rule
  val compose = createEmptyComposeRule()

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val library = FakeIntegrationLibrary()
  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val unlock = FakeEntryUnlock(gate)
  private val table = DocumentIdTable(random = java.util.Random(4))
  private val documents = LibraryMediaDocuments(library, DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS"), table)

  @Before
  fun reset() {
    IntegrationCounters.reset()
    IntegrationPorts.reset()
    PickerPorts.reset()
  }

  @After
  fun cleanup() {
    IntegrationPorts.reset()
    PickerPorts.reset()
    IntegrationCounters.reset()
  }

  private fun install() {
    IntegrationPorts.install(gate, documents)
    PickerPorts.install(library, unlock)
  }

  private fun intent(
    action: String = Intent.ACTION_GET_CONTENT,
    type: String? = "image/*",
    multiple: Boolean = false,
    localOnly: Boolean = false,
  ): Intent = Intent(context, PickerActivity::class.java).apply {
    this.action = action
    if (type != null) this.type = type
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
    putExtra(Intent.EXTRA_LOCAL_ONLY, localOnly)
  }

  private fun launch(intent: Intent): ActivityScenario<PickerActivity> = ActivityScenario.launchActivityForResult<PickerActivity>(intent).also { compose.waitForIdle() }

  private fun cells() = compose.onAllNodesWithContentDescription("Foto", substring = true).fetchSemanticsNodes()

  // --- Intenti ostili -----------------------------------------------------------------------------------------------------

  @Test
  fun unIntentoOstileChiudeSubitoAnnullatoSenzaMostrareNiente() {
    install()
    for (hostile in listOf(Intent("azione.sconosciuta"), Intent(Intent.ACTION_VIEW), Intent(Intent.ACTION_SEND).setType("image/png"), Intent(Intent.ACTION_GET_CONTENT).setType("application/pdf"))) {
      hostile.setClass(context, PickerActivity::class.java)
      val activity = Robolectric.buildActivity(PickerActivity::class.java, hostile).create().get()
      assertThat(activity.isFinishing).isTrue()
      assertThat(shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
      assertThat(shadowOf(activity).resultIntent).isNull()
    }
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_REFUSED_REQUEST)).isEqualTo(4L)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_OPENED)).isEqualTo(0L)
    assertThat(library.fileInfoCalls.get()).isEqualTo(0)
  }

  // --- Porte non installate: fallisce chiuso ---------------------------------------------------------------------------

  @Test
  fun senzaLePorteInstallateNonSiMostraNienteENonSiConsegnaNiente() {
    val scenario = launch(intent())
    compose.onNodeWithText("Prima configura MyCloud Gallery").assertExists()
    assertThat(cells()).isEmpty()
    compose.onNodeWithText("Chiudi").performClick()
    compose.waitForIdle()
    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(scenario.result.resultData).isNull()
  }

  // --- Il blocco dell'app (E-X7) ------------------------------------------------------------------------------------------

  @Test
  fun conLAppBloccataPrimaLoSblocco_EDopoLaGriglia_E_X7() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    install()
    val scenario = launch(intent())
    compose.waitUntil(5_000) { cells().isNotEmpty() }
    assertThat(unlock.calls).isEqualTo(1) // la credenziale di sistema si chiede da sola, una volta
    assertThat(gate.decision).isEqualTo(EntryDecision.ALLOWED)
    assertThat(cells()).isNotEmpty()
    scenario.close()
  }

  @Test
  fun conLAppBloccataENessunoSbloccoNonCEUnaSolaFoto_E_X7() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    unlock.succeeds = false
    install()
    val scenario = launch(intent())
    compose.onNodeWithText("MyCloud Gallery è bloccata").assertExists()
    compose.onNodeWithText("Non sono riuscito a sbloccare. Riprova.").assertExists()
    assertThat(cells()).isEmpty()
    assertThat(compose.onAllNodesWithContentDescription("Video", substring = true).fetchSemanticsNodes()).isEmpty()
    assertThat(library.fileInfoCalls.get()).isEqualTo(0)
    // Il tasto riprova; riuscendo, appare la griglia.
    unlock.succeeds = true
    compose.onNodeWithText("Sblocca").performClick()
    compose.waitUntil(5_000) { cells().isNotEmpty() }
    assertThat(unlock.calls).isEqualTo(2)
    scenario.close()
  }

  @Test
  fun mentreSiSbloccaLaGrigliaNonCE() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    unlock.hangs = true
    install()
    val scenario = launch(intent())
    compose.onNodeWithText("MyCloud Gallery è bloccata").assertExists()
    assertThat(cells()).isEmpty()
    assertThat(unlock.calls).isEqualTo(1)
    scenario.close()
  }

  // --- Scegliere ----------------------------------------------------------------------------------------------------------

  @Test
  fun unTocco_ConsegnaUnaSolaCopiaConIlSoloPermessoDiLettura() {
    install()
    val scenario = launch(intent(localOnly = true))
    compose.waitUntil(5_000) { cells().isNotEmpty() }
    compose.onAllNodes(hasContentDescription("Foto", substring = true) and hasClickAction())[0].performClick()
    compose.waitForIdle()
    val result = scenario.result
    assertThat(result.resultCode).isEqualTo(Activity.RESULT_OK)
    val data = result.resultData
    assertThat(data.data!!.scheme).isEqualTo("content")
    assertThat(data.data!!.authority).isEqualTo("media")
    assertThat(data.flags).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    assertThat(data.clipData!!.itemCount).isEqualTo(1)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_DELIVERED)).isEqualTo(1L)
  }

  @Test
  fun unSoloNasEscePerIlProviderConUnIdOpaco() {
    install()
    val scenario = launch(intent(type = "*/*"))
    compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("solo sul NAS", substring = true).fetchSemanticsNodes().isNotEmpty() }
    compose.onAllNodes(hasContentDescription("solo sul NAS", substring = true) and hasClickAction())[0].performClick()
    compose.waitForIdle()
    val data = scenario.result.resultData
    val uri = data.data!!
    assertThat(uri.authority).isEqualTo(context.packageName + ".integrations.documents")
    assertThat(uri.pathSegments[0]).isEqualTo("document")
    for (id in library.visibleIds) assertThat(uri.toString()).doesNotContain(id.value)
    assertThat(data.flags).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    assertThat(table.size).isEqualTo(1)
  }

  @Test
  fun sceltaMultipla_ServeIlTocco_Scegli_ENienteEsceDaSolo() {
    install()
    val scenario = launch(intent(multiple = true, localOnly = true))
    compose.waitUntil(5_000) { cells().isNotEmpty() }
    compose.onAllNodes(hasContentDescription("Foto", substring = true) and hasClickAction())[0].performClick()
    compose.onAllNodes(hasContentDescription("Foto", substring = true) and hasClickAction())[1].performClick()
    compose.waitForIdle()
    compose.onNodeWithText("2 scelti").assertExists()
    assertThat(scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)).isTrue() // ancora aperta: nessuna consegna da sola
    compose.onNodeWithText("Scegli 2 elementi").performClick()
    compose.waitForIdle()
    val data = scenario.result.resultData
    assertThat(data.clipData!!.itemCount).isEqualTo(2)
    assertThat(data.data).isNull()
  }

  @Test
  fun ilNasCheNonRispondeSiDiceSubitoEIlSelettoreRestaAperto_E_X1() {
    library.reach = NasReach.Unreachable(NasUnreachableReason.OFFLINE)
    install()
    val scenario = launch(intent(type = "*/*"))
    compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("solo sul NAS", substring = true).fetchSemanticsNodes().isNotEmpty() }
    compose.onAllNodes(hasContentDescription("solo sul NAS", substring = true) and hasClickAction())[0].performClick()
    compose.waitForIdle()
    compose.waitUntil(5_000) { compose.onAllNodesWithText("Il NAS non risponde", substring = true).fetchSemanticsNodes().isNotEmpty() }
    assertThat(scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)).isTrue()
    assertThat(table.size).isEqualTo(0)
    scenario.close()
  }

  // --- Annullare (E-X4) -----------------------------------------------------------------------------------------------------

  @Test
  fun indietroAnnullaENonLasciaNiente_E_X4() {
    install()
    val scenario = launch(intent(multiple = true))
    compose.waitUntil(5_000) { cells().isNotEmpty() }
    compose.onAllNodes(hasContentDescription("Foto", substring = true) and hasClickAction())[0].performClick()
    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(scenario.result.resultData).isNull()
    assertThat(table.size).isEqualTo(0)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_CANCELLED)).isEqualTo(1L)
  }

  @Test
  fun ilTastoAnnullaAnnulla() {
    install()
    val scenario = launch(intent())
    compose.waitUntil(5_000) { cells().isNotEmpty() }
    compose.onNodeWithContentDescription("Annulla").performClick()
    compose.waitForIdle()
    assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun lAppSiRichiudeMentreIlSelettoreEInSecondoPiano_AlRitornoSiRiblocca_E_X7() {
    install()
    val scenario = launch(intent(multiple = true))
    compose.waitUntil(5_000) { cells().isNotEmpty() }
    compose.onAllNodes(hasContentDescription("Foto", substring = true) and hasClickAction())[0].performClick()
    compose.waitForIdle()
    gate.decision = EntryDecision.NEEDS_UNLOCK
    unlock.succeeds = false
    scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    compose.waitForIdle()
    compose.onNodeWithText("MyCloud Gallery è bloccata").assertExists()
    assertThat(cells()).isEmpty()
    scenario.close()
  }

  @Test
  fun sullaPrimaRigaIlTitoloEIlSottotitoloDicanoCosaSiScegliendo() {
    install()
    val scenario = launch(intent(type = "video/*"))
    compose.onNodeWithText("Scegli da MyCloud Gallery").assertExists()
    assertThat(compose.onAllNodesWithText("Video").fetchSemanticsNodes()).isNotEmpty()
    scenario.close()
  }
}
