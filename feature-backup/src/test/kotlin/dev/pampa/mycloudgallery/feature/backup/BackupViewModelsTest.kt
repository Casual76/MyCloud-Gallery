package dev.pampa.mycloudgallery.feature.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupSettings
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.folder.DeviceFolderViewModel
import dev.pampa.mycloudgallery.feature.backup.folder.FolderUi
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageViewModel
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsViewModel
import dev.pampa.mycloudgallery.feature.backup.settings.FoldersState
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.testing.FakeBackupOverview
import dev.pampa.mycloudgallery.feature.backup.testing.FakeBackupRules
import dev.pampa.mycloudgallery.feature.backup.testing.FakeDeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.testing.FakePhoneFolders
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * I ViewModel delle schermate del backup (D30): leggono lo stato dalle porte e inoltrano i tocchi, **non decidono niente**. Sulle porte
 * finte; senza porte (`Optional` vuoti, quello che Dagger da' finche' `:app` non le lega) dicono "non configurato" e non fanno niente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelsTest {

  private val dispatcher = UnconfinedTestDispatcher()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  // --- La pagina -------------------------------------------------------------------------------------------------------------

  @Test
  fun laPaginaSegueLoStatoELeSorgenti() = runTest(dispatcher) {
    val overview = FakeBackupOverview(BackupStatus.TestOnly)
    val settings = FakeBackupSettings(folder = BackupSamples.deviceFolder)
    val vm = BackupPageViewModel(Optional.of(overview), Optional.of(settings), Optional.of(RecordingBackupActions()))
    backgroundScope.launch { vm.state.collect {} }

    assertThat(vm.state.value.status).isEqualTo(BackupStatus.TestOnly)
    assertThat(vm.state.value.sources).isEqualTo(BackupSamples.sources)
    assertThat(vm.state.value.deviceFolder).isEqualTo(BackupSamples.deviceFolder)

    overview.status.value = BackupStatus.UpToDate(6_658, null)
    assertThat(vm.state.value.status).isEqualTo(BackupStatus.UpToDate(6_658, null))
    overview.sources.value = emptyList()
    assertThat(vm.state.value.sources).isEmpty()
  }

  @Test
  fun ilFoglioDelPrimoBackupSiApreESiChiudeDaSolo() = runTest(dispatcher) {
    val actions = RecordingBackupActions()
    val vm = BackupPageViewModel(Optional.of(FakeBackupOverview()), Optional.empty(), Optional.of(actions))
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.sheetOpen).isFalse()
    vm.openFirstBackup()
    assertThat(vm.state.value.sheetOpen).isTrue()
    vm.closeFirstBackup()
    assertThat(vm.state.value.sheetOpen).isFalse()
    // Chiudere non sceglie niente.
    assertThat(actions.firstBackupChoices).isEmpty()
  }

  @Test
  fun leDueScelteChiudonoIlFoglioEInoltranoLaScelta() = runTest(dispatcher) {
    val actions = RecordingBackupActions()
    val vm = BackupPageViewModel(Optional.of(FakeBackupOverview()), Optional.empty(), Optional.of(actions))
    backgroundScope.launch { vm.state.collect {} }
    vm.openFirstBackup()
    vm.startFirstBackup(tonight = true)
    assertThat(vm.state.value.sheetOpen).isFalse()
    vm.openFirstBackup()
    vm.startFirstBackup(tonight = false)
    assertThat(actions.firstBackupChoices).containsExactly(true, false).inOrder()
  }

  @Test
  fun iTastiDellaPaginaInoltranoUnaVoltaSola() = runTest(dispatcher) {
    val actions = RecordingBackupActions()
    val vm = BackupPageViewModel(Optional.of(FakeBackupOverview()), Optional.empty(), Optional.of(actions))
    vm.perform(BackupAction.DIAGNOSTICS)
    vm.backupNow()
    vm.stop()
    vm.open(BackupScreen.SETTINGS)
    assertThat(actions.performed).containsExactly(BackupAction.DIAGNOSTICS)
    assertThat(actions.backupNowCalls).isEqualTo(1)
    assertThat(actions.stopCalls).isEqualTo(1)
    assertThat(actions.opened).containsExactly(BackupScreen.SETTINGS)
  }

  /** BK-3, BK-13: ogni ritorno alla pagina arriva alle azioni di `:app` (che ricalcolano lo stato e rifanno il giro dopo un problema sistemato). */
  @Test
  fun ilRitornoAllaPaginaArrivaAlleAzioni() = runTest(dispatcher) {
    val actions = RecordingBackupActions()
    val vm = BackupPageViewModel(Optional.of(FakeBackupOverview()), Optional.empty(), Optional.of(actions))
    assertThat(actions.resumedCalls).isEqualTo(0)
    vm.onResumed()
    vm.onResumed()
    assertThat(actions.resumedCalls).isEqualTo(2)
    // Senza porte il ritorno non fa niente, e non lancia.
    BackupPageViewModel(Optional.empty(), Optional.empty(), Optional.empty()).onResumed()
  }

  @Test
  fun senzaPorteLaPaginaDiceNonConfiguratoENonFaNiente() = runTest(dispatcher) {
    val vm = BackupPageViewModel(Optional.empty(), Optional.empty(), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.status).isEqualTo(BackupStatus.NotConfigured(setOf(BackupSetupStep.NAS)))
    assertThat(vm.state.value.sources).isEmpty()
    assertThat(vm.state.value.deviceFolder).isNull()
    // I tocchi non fanno niente (il null-object): nessuna eccezione, nessuna azione per sbaglio.
    vm.perform(BackupAction.GRANT_MEDIA)
    vm.backupNow()
    vm.stop()
    vm.startFirstBackup(true)
    vm.open(BackupScreen.SETTINGS)
  }

  // --- Le impostazioni -------------------------------------------------------------------------------------------------------

  @Test
  fun leSorgentiSiAccendonoESiSpengonoNelleImpostazioni() = runTest(dispatcher) {
    val settings = FakeBackupSettings(sources = BackupSources.DEFAULT)
    val vm = BackupSettingsViewModel(Optional.of(settings), Optional.of(FakePhoneFolders()), Optional.of(FakeBackupRules()), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.folders).isInstanceOf(FoldersState.Ready::class.java)

    val telegram = BackupSamples.phoneFolders.last().source
    vm.setSource(telegram, true)
    assertThat(settings.sources.value.sources).contains(telegram)
    assertThat(vm.state.value.choices.first { it.source == telegram }.chosen).isTrue()
    vm.setSource(BackupSource.Camera, false)
    assertThat(settings.sources.value.sources).doesNotContain(BackupSource.Camera)
    // Spegnerla due volte non cambia niente.
    vm.setSource(BackupSource.Camera, false)
    // Restano gli screenshot, i ritagli (sorgente accesa di default, Fase 6 P13, D47) e la cartella appena scelta.
    assertThat(settings.sources.value.sources).containsExactly(BackupSource.Screenshots, CutoutBackup.source(), telegram)
  }

  @Test
  fun leRigheStannoNellOrdineDelProgetto() = runTest(dispatcher) {
    val vm = BackupSettingsViewModel(Optional.of(FakeBackupSettings()), Optional.of(FakePhoneFolders()), Optional.empty(), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    val ids = vm.state.value.choices.map { it.source.id }
    assertThat(ids.take(2)).containsExactly(BackupSource.Camera.id, BackupSource.Screenshots.id).inOrder()
    // Poi le cartelle aggiuntive, dalla piu' grande: WhatsApp (2.105), poi DCIM (1.088), Quick Share (654), Telegram (224).
    assertThat(vm.state.value.choices.drop(2).map { it.media }).isInOrder(compareByDescending<Int?> { it })
    // In piu' la riga dei ritagli: accesa di default (D47), resta in lista anche se il telefono non la elenca ancora (E-B12).
    assertThat(vm.state.value.choices).hasSize(BackupSamples.phoneFolders.size + 1)
    assertThat(vm.state.value.choices.single { it.source == CutoutBackup.source() }.chosen).isTrue()
  }

  @Test
  fun unaCartellaGiaScelta_ChePiuNonSiElenca_ResteInListaPerPoterlaTogliere() = runTest(dispatcher) {
    val gone = BackupSource.Folder("external_primary", "Pictures/Vecchia/")
    val settings = FakeBackupSettings(sources = BackupSources(setOf(BackupSource.Camera, gone)))
    val vm = BackupSettingsViewModel(Optional.of(settings), Optional.of(FakePhoneFolders(folders = emptyList())), Optional.empty(), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    val choice = vm.state.value.choices.first { it.source == gone }
    assertThat(choice.chosen).isTrue()
    assertThat(choice.media).isNull()
  }

  @Test
  fun seLeCartelleNonSiLeggonoLeImpostazioniLoDicono() = runTest(dispatcher) {
    val vm = BackupSettingsViewModel(Optional.of(FakeBackupSettings()), Optional.of(FakePhoneFolders(failing = true)), Optional.empty(), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.folders).isEqualTo(FoldersState.Unavailable)
    // Si vedono comunque la fotocamera e gli screenshot.
    assertThat(vm.state.value.choices.map { it.source.id }).containsAtLeast(BackupSource.Camera.id, BackupSource.Screenshots.id)
  }

  @Test
  fun senzaLeImpostazioniNienteSiPuoScegliere() = runTest(dispatcher) {
    val vm = BackupSettingsViewModel(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.editable).isFalse()
    assertThat(vm.state.value.rulesAvailable).isFalse()
    vm.setSource(BackupSource.Camera, true)
    vm.setProtectNewPhotos(true)
    assertThat(vm.state.value.chosen).isEqualTo(BackupSources.NONE)
  }

  @Test
  fun proteggiSubitoLeFotoNuoveSiAccendeESiSpegne() = runTest(dispatcher) {
    val rules = FakeBackupRules(on = false)
    val vm = BackupSettingsViewModel(Optional.of(FakeBackupSettings()), Optional.of(FakePhoneFolders()), Optional.of(rules), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.protectNewPhotos).isFalse()
    vm.setProtectNewPhotos(true)
    assertThat(rules.protectNewPhotos.value).isTrue()
    assertThat(vm.state.value.protectNewPhotos).isTrue()
  }

  /**
   * D-9 (prove sul campo): la cartella del dispositivo dalle impostazioni porta al riepilogo del primo backup, non alla scelta della cartella da
   * sola. Scelta li', il primo backup partiva al giro delle regole e il riepilogo con la stima non compariva piu'.
   */
  @Test
  fun d9_laCartellaDelDispositivoDalleImpostazioniApreIlRiepilogoDelPrimoBackup() = runTest(dispatcher) {
    val actions = RecordingBackupActions()
    val vm = BackupSettingsViewModel(Optional.of(FakeBackupSettings()), Optional.empty(), Optional.empty(), Optional.of(actions))
    vm.openDeviceFolder()
    assertThat(actions.opened).containsExactly(BackupScreen.FIRST_BACKUP)
  }

  // --- La cartella del dispositivo -------------------------------------------------------------------------------------------

  @Test
  fun seSulNasNonCeLaCartellaSiPropone_ESiSceglieUnaVolta() = runTest(dispatcher) {
    val settings = FakeBackupSettings()
    val vm = DeviceFolderViewModel(Optional.of(settings), Optional.of(FakeDeviceFolderProposals(BackupSamples.freshFolderProposal)))
    backgroundScope.launch { vm.state.collect {} }
    val choose = vm.state.value as FolderUi.Choose
    assertThat(choose.existing).isNull()
    vm.choose(choose.suggested)
    assertThat(settings.deviceFolder.value).isEqualTo(BackupSamples.deviceFolder)
    // Scelta: la pagina dice solo "scelta" e non propone piu'.
    assertThat(vm.state.value).isEqualTo(FolderUi.Chosen(BackupSamples.deviceFolder))
  }

  @Test
  fun seLaCartellaCeGiaSiChiedeContinuaLiOppureNuova() = runTest(dispatcher) {
    val settings = FakeBackupSettings()
    val vm = DeviceFolderViewModel(Optional.of(settings), Optional.of(FakeDeviceFolderProposals(BackupSamples.existingFolderProposal)))
    backgroundScope.launch { vm.state.collect {} }
    val choose = vm.state.value as FolderUi.Choose
    assertThat(choose.existing?.files).isEqualTo(1_234)
    assertThat(choose.alternative).isEqualTo(DeviceFolder("S25 di Mario (2)"))
    vm.choose(choose.alternative!!)
    assertThat(settings.deviceFolder.value).isEqualTo(DeviceFolder("S25 di Mario (2)"))
  }

  @Test
  fun senzaUnaPropostaLaPaginaDiceNonPosso() = runTest(dispatcher) {
    val proposals = FakeDeviceFolderProposals(proposal = null)
    val vm = DeviceFolderViewModel(Optional.of(FakeBackupSettings()), Optional.of(proposals))
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value).isEqualTo(FolderUi.Unavailable)
    // Riprovando, quando la proposta c'e', arriva.
    proposals.proposal = BackupSamples.freshFolderProposal
    vm.load()
    assertThat(vm.state.value).isInstanceOf(FolderUi.Choose::class.java)
  }

  @Test
  fun unaCartellaGiaScelta_NonSiRipropone() = runTest(dispatcher) {
    val vm = DeviceFolderViewModel(Optional.of(FakeBackupSettings(folder = BackupSamples.deviceFolder)), Optional.of(FakeDeviceFolderProposals()))
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value).isEqualTo(FolderUi.Chosen(BackupSamples.deviceFolder))
  }
}
