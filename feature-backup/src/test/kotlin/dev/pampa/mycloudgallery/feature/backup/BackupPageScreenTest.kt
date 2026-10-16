package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.page.otherProblems
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import org.junit.Test

/**
 * La pagina Backup (`docs/fase5-progetto.md` §1.5, §2.6, D16): **ogni caso di `BackupStatus` ha la sua scheda**, ogni problema il suo
 * messaggio e i suoi tasti (che chiamano la porta una volta sola), una riga per sorgente, "Fai ora" e "Ferma". La scena si mostra una volta
 * per prova e lo stato cambia da uno stato, come fa il ViewModel.
 */
class BackupPageScreenTest : ComposeBackupTest() {

  private val actions = RecordingBackupActions()

  /** Un tasto con questo testo (nel foglio "Stanotte" e' anche il titolo di una riga della stima: quella non si tocca). */
  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())
  private val ui = mutableStateOf(page(BackupStatus.TestOnly))

  private fun page(
    status: BackupStatus,
    sources: List<dev.pampa.mycloudgallery.core.backup.contract.SourceStatus> = BackupSamples.sources,
    sheetOpen: Boolean = false,
  ) = BackupPageUi(status = status, sources = sources, deviceFolder = BackupSamples.deviceFolder, sheetOpen = sheetOpen)

  private fun pageActions() = BackupPageActions(
    perform = actions::perform,
    backupNow = actions::backupNow,
    stop = actions::stop,
    open = actions::open,
    openSheet = { ui.value = ui.value.copy(sheetOpen = true) },
    closeSheet = { ui.value = ui.value.copy(sheetOpen = false) },
    startFirstBackup = { tonight ->
      ui.value = ui.value.copy(sheetOpen = false)
      actions.startFirstBackup(tonight)
    },
  )

  private fun start(status: BackupStatus, sources: List<dev.pampa.mycloudgallery.core.backup.contract.SourceStatus> = BackupSamples.sources) {
    ui.value = page(status, sources)
    show { BackupPageScreen(state = ui.value, actions = pageActions(), onBack = {}) }
  }

  private fun set(status: BackupStatus) {
    composeRule.runOnIdle { ui.value = page(status) }
    composeRule.waitForIdle()
  }

  @Test
  fun ogniCasoDelloStatoHaLaSuaScheda() {
    start(BackupSamples.statuses.first())
    for (status in BackupSamples.statuses + BackupSamples.problems) {
      set(status)
      // La pagina compone senza errori e dice qualcosa: il titolo della chrome e la scheda.
      composeRule.onNodeWithText("Backup").assertExists()
      assertThat(composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test
  fun nonConfiguratoDiceCosaManca() {
    start(BackupStatus.NotConfigured(setOf(BackupSetupStep.MEDIA_PERMISSION, BackupSetupStep.DEVICE_FOLDER)))
    composeRule.onNodeWithText("Il backup non è ancora pronto").assertExists()
    composeRule.onNodeWithText("• Consenti l’accesso a foto e video.", substring = true).assertExists()
    composeRule.onNodeWithText("• Scegli la cartella del dispositivo sul NAS.", substring = true).assertExists()
    composeRule.onNodeWithText("Consenti l’accesso").performClick()
    assertThat(actions.performed).containsExactly(BackupAction.GRANT_MEDIA)
    composeRule.onNodeWithText("Apri le impostazioni").performClick()
    assertThat(actions.opened).containsExactly(BackupScreen.SETTINGS)
  }

  @Test
  fun inProvaDiceCheSiScriveSoloNelleShareDiProva() {
    start(BackupStatus.TestOnly)
    composeRule.onNodeWithText("Backup in prova").assertExists()
    composeRule.onNodeWithText("Si scrive solo nelle share di prova: le foto vere non si toccano.").assertExists()
  }

  @Test
  fun tuttoAlSicuroDiceQuantiEQuando() {
    start(BackupStatus.UpToDate(safeCount = 6_658, atNasMillis = 1_792_119_120_000))
    composeRule.onNodeWithText("Tutto al sicuro").assertExists()
    composeRule.onNodeWithText("6.658 media al sicuro sul NAS.").assertExists()
    composeRule.onNodeWithText("Ultimo giro:", substring = true).assertExists()
    composeRule.onNodeWithText("Fai ora").performClick()
    assertThat(actions.backupNowCalls).isEqualTo(1)
  }

  @Test
  fun inCodaDiceQuantiEPerche() {
    start(BackupStatus.Waiting(items = 312, bytes = 1_400_000_000, reason = WaitReason.CHARGING))
    composeRule.onNodeWithText("312 in coda").assertExists()
    composeRule.onNodeWithText("Aspetta che il telefono sia in carica.", substring = true).assertExists()
    composeRule.onNodeWithText("1,4", substring = true).assertExists()
    composeRule.onNodeWithText("Fai ora").performClick()
    assertThat(actions.backupNowCalls).isEqualTo(1)
  }

  @Test
  fun unGiroInCorsoDiceADoveEPuoEssereFermato() {
    start(BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP))
    composeRule.onNodeWithText("Primo backup in corso").assertExists()
    composeRule.onNodeWithText("Foto: 1.240 di 6.658", substring = true).assertExists()
    composeRule.onNodeWithText("circa 1 h 20 min", substring = true).assertExists()
    composeRule.onNodeWithText("Fai ora").assertDoesNotExist()
    composeRule.onNodeWithText("Ferma").performClick()
    assertThat(actions.stopCalls).isEqualTo(1)
  }

  @Test
  fun ilTitoloDelGiroDipendeDaChiLHaAvviato() {
    fun running(trigger: BackupTrigger) = BackupStatus.Running(BackupKind.VIDEOS, 3, 40, 1_000_000, 9_000_000_000, null, trigger)
    start(running(BackupTrigger.USER_NOW))
    composeRule.onNodeWithText("Backup in corso, avviato da te").assertExists()
    composeRule.onNodeWithText("Video: 3 di 40", substring = true).assertExists()
    set(running(BackupTrigger.SCHEDULED))
    composeRule.onNodeWithText("Backup in corso").assertExists()
    set(running(BackupTrigger.QUICK_NEW_PHOTOS))
    composeRule.onNodeWithText("Backup in corso").assertExists()
  }

  @Test
  fun fermatoDallUtentePropoineRiprendi() {
    start(BackupStatus.Stopped(StopBy.USER))
    composeRule.onNodeWithText("Backup fermo").assertExists()
    composeRule.onNodeWithText("Riprendi").performClick()
    assertThat(actions.backupNowCalls).isEqualTo(1)
    set(BackupStatus.Stopped(StopBy.SYSTEM))
    composeRule.onNodeWithText("Android ha fermato il lavoro. Riprende da solo.").assertExists()
    set(BackupStatus.Stopped(StopBy.RESET))
    composeRule.onNodeWithText("Riprendi").assertDoesNotExist()
    composeRule.onNodeWithText("Fai ora").assertDoesNotExist()
  }

  @Test
  fun ilPrimoBackupApreIlFoglioConIDueTasti() {
    start(BackupStatus.FirstBackupReady(BackupSamples.firstBackupSummary))
    composeRule.onNodeWithText("Pronto per il primo backup").assertExists()
    composeRule.onAllNodes(hasText("Stanotte")).assertCountEquals(0)
    composeRule.onNodeWithText("Vedi il riepilogo").performClick()
    composeRule.waitForIdle()
    button("Stanotte").assertExists()
    button("Inizia ora").assertExists()
    button("Stanotte").performClick()
    composeRule.waitForIdle()
    assertThat(actions.firstBackupChoices).containsExactly(true)
    // La scelta chiude il foglio.
    composeRule.onAllNodes(hasText("Inizia ora")).assertCountEquals(0)
  }

  @Test
  fun ilPrimoBackupMentreConfrontaDiceChePrepara() {
    start(BackupStatus.FirstBackupReady(BackupSamples.comparingSummary))
    composeRule.onNodeWithText("Preparo il primo backup").assertExists()
    composeRule.onNodeWithText("Sto confrontando 120 foto con le copie sul NAS…").assertExists()
  }

  @Test
  fun ognunoDeiProblemiDelContrattoHaIlSuoMessaggioEITastiChiamanoLaPortaUnaVolta() {
    start(BackupSamples.problems.first())
    for (problem in BackupSamples.problems) {
      val code = problem.code
      set(problem)
      val text = dev.pampa.mycloudgallery.feature.backup.ui.BackupProblemTexts.of(code)
      val title = text.titleCount?.takeIf { problem.count > 0 }
        ?.let { composeRule.activity.resources.getQuantityString(it, problem.count, problem.count.toString()) }
        ?: composeRule.activity.getString(text.title)
      composeRule.onNodeWithText(title).assertExists()
      actions.performed.clear()
      for (action in code.actions) {
        val label = composeRule.activity.getString(dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts.of(action).label)
        if (dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts.of(action).isButton) {
          // Un tasto: una volta il tocco, una volta la porta (il tasto "Fai ora" del problema dei dati e' un'azione, non "backupNow").
          composeRule.onAllNodes(hasText(label))[0].performClick()
        } else {
          composeRule.onNodeWithText(label).assertExists()
        }
      }
      val expected = code.actions.filter { dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts.of(it).isButton }
      assertWithMessage("$code: azioni eseguite").that(actions.performed).containsExactlyElementsIn(expected).inOrder()
    }
  }

  @Test
  fun leSorgentiDiconoQuantiMediaEQuantiAlSicuro() {
    start(BackupStatus.UpToDate(6_658, null))
    composeRule.onNodeWithText("Fotocamera").assertExists()
    composeRule.onNodeWithText("1.250 media · 1.250 al sicuro").assertExists()
    composeRule.onNodeWithText("Screenshot").assertExists()
    composeRule.onNodeWithText("915 media · 900 al sicuro · 12 in coda · 3 con problemi").assertExists()
    composeRule.onNodeWithText("Telegram").assertExists()
    composeRule.onNodeWithText("Non salvata").assertExists()
    composeRule.onNodeWithText("Al sicuro").assertExists()
  }

  @Test
  fun iProblemiDelleSorgentiCompaionoConLaLoroAzione() {
    start(BackupStatus.UpToDate(6_658, null))
    composeRule.onNode(hasText("Da controllare") and isHeading()).assertExists()
    composeRule.onNodeWithText("3 foto cancellate prima del backup").assertExists()
    composeRule.onNodeWithText("Apri il cestino di Galleria").performClick()
    assertThat(actions.performed).containsExactly(BackupAction.OPEN_GALLERY_TRASH)
  }

  @Test
  fun unProblemaGiaInCimaNonSiRipeteTraLeSorgenti() {
    val withProblem = page(BackupStatus.Problem(BackupProblemCode.LOST_BEFORE_BACKUP, 3))
    assertThat(otherProblems(withProblem)).isEmpty()
    val without = page(BackupStatus.UpToDate(1, null))
    assertThat(otherProblems(without).map { it.first }).containsExactly(BackupProblemCode.LOST_BEFORE_BACKUP)
    assertThat(otherProblems(without).single().second).isEqualTo(3)
  }

  @Test
  fun senzaSorgentiLaPaginaDiceDiScegliere() {
    start(BackupStatus.NotConfigured(setOf(BackupSetupStep.SOURCES)), sources = emptyList())
    composeRule.onNodeWithText("Nessuna sorgente scelta").assertExists()
  }

  @Test
  fun laCartellaDelDispositivoScelta_NonSiTocca_ENonScelta_SiApre() {
    start(BackupStatus.UpToDate(1, null))
    composeRule.onNodeWithText("Le copie vanno in «S25 di Mario».").assertExists()
    composeRule.onNodeWithText("Impostazioni del backup", substring = true).assertExists()
    composeRule.runOnIdle { ui.value = ui.value.copy(deviceFolder = null) }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Si sceglie prima del primo backup.").performClick()
    assertThat(actions.opened).containsExactly(BackupScreen.DEVICE_FOLDER)
  }

  @Test
  fun idueTastiDelFoglioSonoSpentiMentreConfronta() {
    ui.value = page(BackupStatus.FirstBackupReady(BackupSamples.comparingSummary), sheetOpen = true)
    show { BackupPageScreen(state = ui.value, actions = pageActions(), onBack = {}) }
    button("Stanotte").assertIsNotEnabled()
    button("Inizia ora").assertIsNotEnabled()
    composeRule.onAllNodes(hasText("Sto confrontando 120 foto con le copie sul NAS…", substring = true)).assertCountEquals(2)
  }
}
