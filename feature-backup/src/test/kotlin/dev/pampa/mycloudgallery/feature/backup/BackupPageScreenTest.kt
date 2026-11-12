package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.page.SourceState
import dev.pampa.mycloudgallery.feature.backup.page.SourcesHalt
import dev.pampa.mycloudgallery.feature.backup.page.sourceState
import dev.pampa.mycloudgallery.feature.backup.page.coverMarkShown
import dev.pampa.mycloudgallery.feature.backup.page.coverNameLines
import dev.pampa.mycloudgallery.feature.backup.page.sourcesHalt
import dev.pampa.mycloudgallery.feature.backup.page.otherProblems
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import org.junit.Test

/**
 * La pagina Backup (`docs/fase5-progetto.md` §1.5, §2.6, D16; I3 della fase I): **ogni caso di `BackupStatus` ha la sua testata** (un'etichetta,
 * al piu' una riga, il perche' dietro la «i»), ogni problema il suo messaggio e i suoi tasti (che chiamano la porta una volta sola), una copertina
 * per sorgente, "Fai ora" e "Ferma". La scena si mostra una volta per prova e lo stato cambia da uno stato, come fa il ViewModel.
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

  /** Apre il perche' della testata (la «i» senza riga accanto). */
  private fun openWhy() {
    // Giro 1 (CS-5): la «i» della galleria dice a TalkBack anche il perche' (la frase aperta non si rilegge).
    composeRule.onNodeWithContentDescription("Perché", substring = true).performClick()
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
    composeRule.onNodeWithText("Da preparare").assertExists()
    // I3: i passi che mancano sono piastrelle con un'etichetta corta, non frasi con il punto elenco.
    composeRule.onNodeWithText("Foto e video").assertExists()
    composeRule.onNodeWithText("La cartella sul NAS").assertExists()
    composeRule.onNodeWithText("Consenti l’accesso").performClick()
    assertThat(actions.performed).containsExactly(BackupAction.GRANT_MEDIA)
    composeRule.onNodeWithText("Apri le impostazioni").performClick()
    assertThat(actions.opened).containsExactly(BackupScreen.SETTINGS)
  }

  /**
   * **BK-9**: l'area privata non si sistema da una pagina dell'app ("Impostazioni, Aree" non c'e': le aree si fissano alla fondazione della famiglia e
   * l'app non crea share). La scheda lo dice e dice chi puo' farlo, invece di un tasto che portava alla radice delle Impostazioni.
   */
  @Test
  fun bk9_manca_lAreaPrivata_DiceDiChiedereAllAdminENonOffreUnTastoCheNonPortaDaNessunaParte() {
    start(BackupStatus.NotConfigured(setOf(BackupSetupStep.PRIVATE_AREA)))
    composeRule.onNodeWithText("L’area privata").assertExists()
    // I3: chi puo' farlo sta dietro la «i».
    openWhy()
    composeRule.onNodeWithText("Chiedi all’admin del NAS").assertExists()
    composeRule.onNodeWithText("Apri Impostazioni, Aree").assertDoesNotExist()
    // Lo stesso come problema di un giro: una frase, nessun tasto.
    set(BackupStatus.Problem(BackupProblemCode.NO_PRIVATE_AREA, 0))
    openWhy()
    composeRule.onNodeWithText("Chiedi all’admin del NAS", substring = true).assertExists()
    composeRule.onNodeWithText("Apri Impostazioni, Aree").assertDoesNotExist()
    assertThat(actions.performed).isEmpty()
  }

  /** BK-3: un problema di un giro che l'utente puo' aver risolto fuori dall'app offre "Riprova" accanto alla sua azione. */
  @Test
  fun bk3_unNasPienoOUnaQuotaOffronoRiprova() {
    start(BackupStatus.Problem(BackupProblemCode.NAS_FULL, 0))
    composeRule.onNodeWithText("Riprova").performClick()
    assertThat(actions.performed).containsExactly(BackupAction.RETRY)
    set(BackupStatus.Problem(BackupProblemCode.QUOTA, 0))
    composeRule.onNodeWithText("Riprova").assertExists()
    openWhy()
    composeRule.onNodeWithText("Chiedi all’admin del NAS", substring = true).assertExists()
  }

  /** I3: lo stato «in prova» (D12) si dice una volta, in cima, senza parole da cantiere; il perche' dietro la «i». La recinzione non cambia. */
  @Test
  fun inProvaDiceUnaVoltaCheIlBackupVeroNonEAncoraAcceso() {
    start(BackupStatus.TestOnly)
    composeRule.onNodeWithText("In pausa").assertExists()
    composeRule.onNodeWithText("Il backup vero non è ancora acceso").assertExists()
    composeRule.onNodeWithText("share", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("Il backup vero non è ancora acceso").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Finché non lo accendi", substring = true).assertExists()
  }

  @Test
  fun tuttoAlSicuroDiceQuantiEQuando() {
    start(BackupStatus.UpToDate(safeCount = 6_658, atNasMillis = 1_792_119_120_000))
    composeRule.onNodeWithText("Al sicuro").assertExists()
    // L'anello: quanto e' al sicuro sul totale delle sorgenti attive (1.250 + 900 su 1.250 + 912), mai «100%» con una foto che manca.
    composeRule.onNodeWithContentDescription("99% al sicuro: 2.150 su 2.162").assertExists()
    composeRule.onNodeWithText("99%", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Ultimo giro:", substring = true).assertExists()
    composeRule.onNodeWithText("Fai ora").performClick()
    assertThat(actions.backupNowCalls).isEqualTo(1)
  }

  @Test
  fun inCodaDiceQuantiEPerche() {
    start(BackupStatus.Waiting(items = 312, bytes = 1_400_000_000, reason = WaitReason.CHARGING))
    composeRule.onNodeWithText("In attesa").assertExists()
    // Fase I (BF-8): la formula comune del motivo (la stessa del foglio, del piede e della Diagnostica). Giro 2 (BF2-3): «In attesa» detto una volta sola.
    composeRule.onNodeWithText("312 in coda · Serve la corrente").assertExists()
    composeRule.onAllNodes(hasText("In attesa della", substring = true)).assertCountEquals(0)
    composeRule.onNodeWithText("Fai ora").performClick()
    assertThat(actions.backupNowCalls).isEqualTo(1)
  }

  @Test
  fun unGiroInCorsoDiceADoveEPuoEssereFermato() {
    start(BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP))
    composeRule.onNodeWithText("Primo backup").assertExists()
    // Giro 1 (CU-11): la riga dice il tipo e il tempo; la frazione la dice solo l'anello (mai due frazioni con totali diversi).
    composeRule.onNodeWithText("Foto · circa 1 h 20 min").assertExists()
    composeRule.onNodeWithText("1.240 di 6.658", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("Fai ora").assertDoesNotExist()
    composeRule.onNodeWithText("Ferma").performClick()
    assertThat(actions.stopCalls).isEqualTo(1)
  }

  @Test
  fun ilTitoloDelGiroDipendeDaChiLHaAvviato() {
    fun running(trigger: BackupTrigger) = BackupStatus.Running(BackupKind.VIDEOS, 3, 40, 1_000_000, 9_000_000_000, null, trigger)
    start(running(BackupTrigger.USER_NOW))
    composeRule.onNodeWithText("In corso").assertExists()
    composeRule.onNodeWithText("Video").assertExists()
    set(running(BackupTrigger.SCHEDULED))
    composeRule.onNodeWithText("In corso").assertExists()
    set(running(BackupTrigger.FIRST_BACKUP))
    composeRule.onNodeWithText("Primo backup").assertExists()
  }

  @Test
  fun fermatoDallUtentePropoineRiprendi() {
    start(BackupStatus.Stopped(StopBy.USER))
    // R4: "Ferma" e' una pausa, e la pagina dice "In pausa" (non "Fermo", che resta per Android e per l'azzeramento).
    composeRule.onNodeWithText("In pausa").assertExists()
    composeRule.onNodeWithText("Fermo").assertDoesNotExist()
    composeRule.onNodeWithText("Riprendi").performClick()
    assertThat(actions.backupNowCalls).isEqualTo(1)
    set(BackupStatus.Stopped(StopBy.SYSTEM))
    composeRule.onNodeWithText("Fermo").assertExists()
    composeRule.onNodeWithText("Riprende da solo").assertExists()
    set(BackupStatus.Stopped(StopBy.RESET))
    composeRule.onNodeWithText("Riprendi").assertDoesNotExist()
    composeRule.onNodeWithText("Fai ora").assertDoesNotExist()
  }

  @Test
  fun ilPrimoBackupApreIlFoglioConIDueTasti() {
    start(BackupStatus.FirstBackupReady(BackupSamples.firstBackupSummary))
    composeRule.onNodeWithText("Pronto per partire").assertExists()
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
  fun ilPrimoBackupMentreCercaDiceChePrepara() {
    start(BackupStatus.FirstBackupReady(BackupSamples.searchingSummary))
    composeRule.onNodeWithText("Preparo il primo backup").assertExists()
    composeRule.onNodeWithText("1.234 di 5.559 file del NAS guardati").assertExists()
    // Fase I (punto 5): ferma per il caldo, la testata lo dice (e non «cerco»).
    set(BackupStatus.FirstBackupReady(BackupSamples.heldSummary))
    composeRule.onNodeWithText("Il telefono è troppo caldo: la ricerca riprende da sola").assertExists()
    // Fase I: a ricerca finita e' pronto, anche con copie simili ancora da confrontare.
    set(BackupStatus.FirstBackupReady(BackupSamples.comparingSummary))
    composeRule.onNodeWithText("Pronto per partire").assertExists()
    composeRule.onAllNodes(hasText("Preparo il primo backup")).assertCountEquals(0)
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
      // I3: un'azione che e' solo una frase sta con il perche', dietro la «i».
      if (code.actions.any { !dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts.of(it).isButton }) openWhy()
      for (action in code.actions) {
        val label = composeRule.activity.getString(dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts.of(action).label)
        if (dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts.of(action).isButton) {
          // Un tasto: una volta il tocco, una volta la porta (il tasto "Fai ora" del problema dei dati e' un'azione, non "backupNow").
          composeRule.onAllNodes(hasText(label))[0].performClick()
        } else {
          composeRule.onNodeWithText(label, substring = true).assertExists()
        }
      }
      val expected = code.actions.filter { dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts.of(it).isButton }
      assertWithMessage("$code: azioni eseguite").that(actions.performed).containsExactlyElementsIn(expected).inOrder()
    }
  }

  /** I3: una copertina per sorgente, con il numero che conta; per TalkBack nome, stato e numeri in una frase. */
  @Test
  fun leSorgentiDiconoStatoENumeroCheConta() {
    start(BackupStatus.UpToDate(6_658, null))
    composeRule.onNodeWithContentDescription("Fotocamera, Al sicuro. 1.250 al sicuro").assertExists()
    // T-1: le 3 foto cancellate prima del backup non sono della copertina (si dicono fra i problemi): la copertina dice le 12 da salvare.
    composeRule.onNodeWithContentDescription("Screenshot, In corso. 12 da salvare").assertExists()
    composeRule.onNodeWithContentDescription("Telegram, Non salvata. 224 foto e video, non si salva").assertExists()
  }

  /**
   * Giro 1 (CS-2): lo stato di tutte le copertine lo dice la testata, una volta; il segno sta solo sulla copertina diversa dalle altre (un problema,
   * una cartella spenta). Prima la pausa era sull'anello e su ognuna delle sei copertine.
   */
  /** I6 (CS-27): due righe al nome solo nelle righe di copertine dove un nome ne ha bisogno; le altre righe restano basse. */
  @Test
  fun leDueRigheDelNomeSoloDoveServono() {
    // Tre per riga: la prima riga ha «WhatsApp Images» su due righe, la seconda solo nomi corti.
    assertThat(coverNameLines(listOf(1, 1, 2, 1, 1, 1), columns = 3)).containsExactly(2, 2, 2, 1, 1, 1).inOrder()
    assertThat(coverNameLines(listOf(1, 1), columns = 3)).containsExactly(1, 1).inOrder()
    // Mai piu' di due righe, mai meno di una.
    assertThat(coverNameLines(listOf(3, 0), columns = 2)).containsExactly(2, 2).inOrder()
  }

  @Test
  fun ilSegnoStaSoloSullaCopertinaDiversaDalleAltre() {
    val all = List(6) { SourceState.Halted }
    assertThat(all.map { coverMarkShown(it, all) }).containsExactly(false, false, false, false, false, false)
    val oneProblem = all.dropLast(1) + SourceState.Problem
    assertThat(oneProblem.map { coverMarkShown(it, oneProblem) }).containsExactly(false, false, false, false, false, true).inOrder()
    val oneOff = listOf(SourceState.Working, SourceState.Safe, SourceState.Off)
    assertThat(oneOff.map { coverMarkShown(it, oneOff) }).containsExactly(false, false, true).inOrder()
    // Tutte con lo stesso problema: lo dice la testata, non sei segni.
    val allProblems = List(3) { SourceState.Problem }
    assertThat(allProblems.map { coverMarkShown(it, allProblems) }).containsExactly(false, false, false)
  }

  /**
   * D-4 (prove sul campo): con il backup in prova (D12) le righe dicevano "In corso · 0 al sicuro · 957 in coda", ma il backup non partira' mai.
   * Ora dicono che e' fermo e perche'; con il backup che puo' lavorare la stessa riga e' di nuovo "In corso".
   */
  @Test
  fun conIlBackupInProvaLeSorgentiDiconoCheEFermoENonInCorso() {
    val waiting = listOf(SourceStatus(BackupSource.Camera, active = true, media = 957, safe = 0, queued = 957, problems = emptyMap()))
    start(BackupStatus.TestOnly, sources = waiting)
    composeRule.onNodeWithText("In corso").assertDoesNotExist()
    // I3: il perche' si dice una volta, in cima; la copertina ha solo il segno della pausa e il numero che manca.
    composeRule.onNodeWithContentDescription("Fotocamera, In pausa. 957 da salvare").assertExists()
    composeRule.onNodeWithText("957").assertExists()

    composeRule.runOnIdle { ui.value = page(BackupStatus.Running(BackupKind.PHOTOS, 1, 957, 10, 1_000, null, BackupTrigger.USER_NOW), waiting) }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("In corso").assertExists()
    composeRule.onNodeWithContentDescription("Fotocamera, In corso. 957 da salvare").assertExists()
  }

  /** D-4: quali stati fermano tutte le sorgenti; "Al sicuro", i problemi e le sorgenti spente restano quello che sono. */
  @Test
  fun lePilloleDelleSorgentiSeguonoLoStatoDelBackup() {
    assertThat(sourcesHalt(BackupStatus.TestOnly)).isEqualTo(SourcesHalt.TEST_ONLY)
    assertThat(sourcesHalt(BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER)))).isEqualTo(SourcesHalt.NOT_CONFIGURED)
    assertThat(sourcesHalt(BackupStatus.Stopped(StopBy.USER))).isEqualTo(SourcesHalt.PAUSED)
    assertThat(sourcesHalt(BackupStatus.FirstBackupReady(dev.pampa.mycloudgallery.core.backup.contract.BackupSummary.EMPTY))).isEqualTo(SourcesHalt.FIRST_BACKUP)
    assertThat(sourcesHalt(BackupStatus.Stopped(StopBy.SYSTEM))).isNull()
    assertThat(sourcesHalt(BackupStatus.Waiting(3, 30, WaitReason.WIFI))).isNull()
    assertThat(sourcesHalt(BackupStatus.UpToDate(1, null))).isNull()

    fun row(safe: Int, queued: Int, active: Boolean = true, problems: Map<BackupProblemCode, Int> = emptyMap()) =
      SourceStatus(BackupSource.Camera, active, media = 10, safe = safe, queued = queued, problems = problems)
    assertThat(sourceState(row(0, 10), 0, SourcesHalt.TEST_ONLY)).isEqualTo(SourceState.Halted)
    assertThat(sourceState(row(0, 10), 0, null)).isEqualTo(SourceState.Working)
    assertThat(sourceState(row(10, 0), 0, SourcesHalt.TEST_ONLY)).isEqualTo(SourceState.Safe)
    assertThat(sourceState(row(5, 5, problems = mapOf(BackupProblemCode.VERIFY_FAILED to 1)), 1, SourcesHalt.TEST_ONLY)).isEqualTo(SourceState.Problem)
    assertThat(sourceState(row(0, 10, active = false), 0, SourcesHalt.TEST_ONLY)).isEqualTo(SourceState.Off)
  }

  /**
   * T-1 (dal telefono vero): tutto cio' che esiste e' al sicuro e una foto e' stata cancellata prima del backup. La pagina dice «Al sicuro» e 100% (la foto
   * persa non e' nel totale), la copertina e' a posto, e la foto persa si dice **una volta**, fra i problemi, con la sua azione.
   */
  @Test
  fun unaFotoCancellataPrimaDelBackupSiDiceUnaVoltaEIlRestoEAlSicuro() {
    val sources = listOf(
      SourceStatus(BackupSource.Camera, active = true, media = 40, safe = 40, queued = 0, problems = mapOf(BackupProblemCode.LOST_BEFORE_BACKUP to 1)),
    )
    start(BackupStatus.UpToDate(40, null), sources)
    composeRule.onNodeWithText("Al sicuro").assertExists()
    composeRule.onNodeWithContentDescription("100% al sicuro: 40 su 40").assertExists()
    composeRule.onNodeWithContentDescription("Fotocamera, Al sicuro. 40 al sicuro").assertExists()
    composeRule.onAllNodes(hasText("1 foto cancellata prima del backup")).assertCountEquals(1)
  }

  @Test
  fun iProblemiDelleSorgentiCompaionoConLaLoroAzione() {
    start(BackupStatus.UpToDate(6_658, null))
    composeRule.onNode(hasText("Da controllare") and isHeading()).assertExists()
    // I3: la riga dice il messaggio; il perche' e il rimedio si aprono con un tocco.
    composeRule.onNodeWithText("Apri il cestino di Galleria").assertDoesNotExist()
    composeRule.onNodeWithText("3 foto cancellate prima del backup").performClick()
    composeRule.waitForIdle()
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
    composeRule.onNodeWithText("Nessuna cartella scelta").assertExists()
  }

  /**
   * La cartella scelta non si tocca. D-9 (prove sul campo): quella non ancora scelta si sceglie con il primo backup, dal suo riepilogo: la riga
   * apre il foglio "Primo backup" con la stima, mai la scelta della cartella da sola (che faceva partire il primo backup senza riepilogo).
   */
  @Test
  fun laCartellaDelDispositivoScelta_NonSiTocca_ENonScelta_ApreIlRiepilogoDelPrimoBackup() {
    start(BackupStatus.UpToDate(1, null))
    composeRule.onNodeWithText("S25 di Mario").assertExists()
    composeRule.onNodeWithText("Impostazioni del backup", substring = true).assertExists()
    composeRule.runOnIdle { ui.value = page(BackupStatus.FirstBackupReady(BackupSamples.firstBackupSummary)).copy(deviceFolder = null) }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Da scegliere").performClick()
    composeRule.waitForIdle()
    assertThat(actions.opened).isEmpty()
    assertThat(ui.value.sheetOpen).isTrue()
    button("Inizia ora").assertExists()
  }

  /** Fase I (punto 4): mai «0 foto e video · 0 B»; il riepilogo non ancora calcolato dice che cerca. */
  @Test
  fun ilPrimoBackupSenzaNumeriNonDiceZero() {
    start(BackupStatus.FirstBackupReady(BackupSummary.EMPTY))
    composeRule.onNodeWithText("Pronto per partire").assertExists()
    composeRule.onAllNodes(hasText("0 foto e video", substring = true)).assertCountEquals(0)
    // BF-5, giro 2 (BF2-2): appena aperta l'app (riepilogo non ancora calcolato) il silenzio del piede e delle Impostazioni: il nome, nessuna riga, nessun
    // «Preparo», niente da vedere ancora.
    set(BackupStatus.FirstBackupReady(BackupSummary.COUNTING))
    composeRule.onNodeWithText("Primo backup").assertExists()
    composeRule.onAllNodes(hasText("Preparo", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("Cerco", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("Vedi il riepilogo")).assertCountEquals(0)
    composeRule.onAllNodes(hasText("0 foto e video", substring = true)).assertCountEquals(0)
  }

  /** Giro 2: la ricerca ferma per un motivo che il backup non nomina ha «Vedi perche'», che apre la Diagnostica (una volta). */
  @Test
  fun laRicercaInPausaSenzaMotivoPortaAllaDiagnostica() {
    start(BackupStatus.FirstBackupReady(BackupSamples.pausedSearchSummary))
    composeRule.onNodeWithText("Ricerca in pausa").assertExists()
    composeRule.onNodeWithText("Vedi perché").performClick()
    assertThat(actions.performed).containsExactly(BackupAction.DIAGNOSTICS)
  }

  /** Giro 2 (BF2-1): con il risparmio energetico la testata dice come riprende davvero. */
  @Test
  fun laRicercaFermaPerIlRisparmioEnergeticoDiceComeRiprende() {
    start(BackupStatus.FirstBackupReady(BackupSamples.heldSummary.copy(existing = BackupSamples.heldSummary.existing.copy(heldBy = WaitReason.POWER_SAVER))))
    composeRule.onNodeWithText("Risparmio energetico attivo: la ricerca riprende quando lo spegni").assertExists()
    composeRule.onAllNodes(hasText("da sola", substring = true)).assertCountEquals(0)
  }

  /**
   * Giro 2 (BF2-5): il primo backup grande, nei primi 15 minuti (nessuna durata misurata), dice la stessa frase del foglio; poi la durata vera. Un primo
   * backup piccolo dice solo che cosa carica.
   */
  @Test
  fun ilPrimoBackupGrandeNeiPrimiMinutiDiceCheCiVoglionoOre() {
    fun first(eta: Long?, total: Int, bytes: Long) = BackupStatus.Running(BackupKind.PHOTOS, 40, total, 80_000_000, bytes, eta, BackupTrigger.FIRST_BACKUP)
    start(first(eta = null, total = 8_445, bytes = 40_000_000_000))
    composeRule.onNodeWithText("Con tanti file ci vogliono alcune ore.").assertExists()
    set(first(eta = 95 * 60_000L, total = 8_445, bytes = 40_000_000_000))
    composeRule.onNodeWithText("Foto · circa 1 h 35 min").assertExists()
    composeRule.onAllNodes(hasText("alcune ore", substring = true)).assertCountEquals(0)
    set(first(eta = null, total = 200, bytes = 1_000_000_000))
    composeRule.onNodeWithText("Foto").assertExists()
    composeRule.onAllNodes(hasText("alcune ore", substring = true)).assertCountEquals(0)
  }

  /** BF-c: la riga di «Pronto per partire» dice quanto viaggia, come il titolo del foglio (non i gia' sul NAS insieme ai nuovi). */
  @Test
  fun prontoPerPartireDiceQuantoSiCarica() {
    start(BackupStatus.FirstBackupReady(BackupSamples.firstBackupSummary), BackupSamples.firstBackupSources)
    composeRule.onNodeWithText("Pronto per partire").assertExists()
    composeRule.onNodeWithText("5.418 da caricare", substring = true).assertExists()
    composeRule.onAllNodes(hasText("6.658", substring = true)).assertCountEquals(0)
  }

  /** BF-1: dopo «Inizia ora», finche' il lavoro non parte, «Sto partendo» con «Ferma»: mai «In attesa · al prossimo giro» con «Fai ora». */
  @Test
  fun dopoInizia_OraDiceCheStaPartendo() {
    start(BackupStatus.Running(BackupKind.PHOTOS, 0, 7_105, 0L, 3_000_000_000, null, BackupTrigger.FIRST_BACKUP, starting = true))
    composeRule.onNodeWithText("Sto partendo").assertExists()
    composeRule.onAllNodes(hasText("prossimo giro", substring = true)).assertCountEquals(0)
    composeRule.onAllNodes(hasText("In attesa")).assertCountEquals(0)
    composeRule.onAllNodes(hasText("Fai ora")).assertCountEquals(0)
    composeRule.onNodeWithText("Ferma").assertExists()
  }

  @Test
  fun idueTastiDelFoglioSonoSpentiSoloMentreCerca() {
    ui.value = page(BackupStatus.FirstBackupReady(BackupSamples.searchingSummary), sheetOpen = true)
    show { BackupPageScreen(state = ui.value, actions = pageActions(), onBack = {}) }
    button("Stanotte").assertIsNotEnabled()
    button("Inizia ora").assertIsNotEnabled()
    // La testata (con le parole) e il foglio (il numero, le parole sotto).
    composeRule.onAllNodes(hasText("1.234 di 5.559", substring = true)).assertCountEquals(2)
    // Fase I (12/11): il confronto delle copie simili non spegne le scelte.
    composeRule.runOnIdle { ui.value = page(BackupStatus.FirstBackupReady(BackupSamples.comparingSummary), sheetOpen = true) }
    composeRule.waitForIdle()
    button("Stanotte").assertIsEnabled()
    button("Inizia ora").assertIsEnabled()
  }
}
