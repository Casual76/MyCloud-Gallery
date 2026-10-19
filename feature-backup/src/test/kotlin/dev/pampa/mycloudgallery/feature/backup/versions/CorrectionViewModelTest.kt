package dev.pampa.mycloudgallery.feature.backup.versions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryVersionKeeper
import dev.pampa.mycloudgallery.core.backup.testing.ScriptedCorrections
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.backup.free.FakePhoneSystemDialogs
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import java.time.LocalDateTime
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il ViewModel di "Correggi data e luogo" e "Ruota" (`docs/fase5-progetto.md` §2.4, D19, E-L8, Q6, L40), sugli stati di `CorrectionState`:
 * `Editing`, `NeedsSafeFirst`, `AwaitingSystemDialog`, `Writing`, `Uploading`, `Done`, `Failed`. Il ViewModel traduce, chiede il piano e passa la
 * conferma nata dal tocco: la riga del telefono si riscrive solo col permesso del sistema, e senza permesso non si tocca niente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CorrectionViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val clock = FakeBackupClock()
  private val dialogs = FakePhoneSystemDialogs(ApplicationProvider.getApplicationContext())
  private val corrections = ScriptedCorrections(clock)
  private val keeper = InMemoryVersionKeeper(clock)
  private val actions = RecordingBackupActions()

  private val subject = CorrectionSubject(
    mediaId = VersionSamples.media,
    takenAt = LocalDateTime.of(2026, 10, 12, 14, 32),
    offsetMinutes = 120,
    placeLabel = "Oslo",
    hasLocation = true,
  )

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  /** Il servizio delle correzioni che ricorda la versione che ha creato (il test la fa diventare "al sicuro" per chiudere il giro). */
  private class Recording(private val inner: ScriptedCorrections) : Corrections by inner {
    var version: VersionId? = null

    override suspend fun apply(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome =
      inner.apply(plan, confirmation, writeGrant).also { if (it is CorrectionOutcome.Done) version = it.version }
  }

  private fun viewModel(service: Corrections? = corrections, places: CorrectionPlaces? = null) = CorrectionViewModel(
    Optional.ofNullable(service),
    Optional.of(keeper),
    Optional.of(dialogs),
    Optional.of(clock),
    Optional.ofNullable(places),
    Optional.of(actions),
  )

  private fun CorrectionViewModel.editing() = state.value as CorrectionUi.Editing

  private fun CorrectionViewModel.confirmation(): PhoneConfirmation = TestConfirmations.confirm(editing().plan!!, clock.monotonicMs())

  @Test
  fun senzaIServiziLaFunzioneDiceCheNonEPronta() = runTest(dispatcher) {
    val vm = viewModel(service = null)
    vm.bind(subject, CorrectionMode.ALL)
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Unavailable)
  }

  @Test
  fun siApreSullaBozzaDiAdessoSenzaPianoFincheNonSiCorreggeQualcosa() = runTest(dispatcher) {
    val vm = viewModel()
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Loading)
    vm.bind(subject, CorrectionMode.ALL)
    val editing = vm.editing()
    assertThat(editing.plan).isNull()
    assertThat(editing.fields).isEmpty()
    assertThat(editing.draft.dateText).isEqualTo("12/10/2026")
    assertThat(editing.readiness).isEqualTo(CorrectionReadiness.READY)
  }

  @Test
  fun unMediaDelTelefonoNonAlSicuroNonSiCorregge_Prima_IlBackup() = runTest(dispatcher) {
    corrections.readiness = CorrectionReadiness.NEEDS_SAFE_FIRST
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    assertThat(vm.state.value).isEqualTo(CorrectionUi.NeedsSafeFirst(subject))
    vm.safeguardNow()
    assertThat(actions.backupNowCalls).isEqualTo(1)
  }

  @Test
  fun unFormatoCheNonSiScriveLoDiceSubito_MaLaCorrezioneSiPuoFare() = runTest(dispatcher) {
    corrections.readiness = CorrectionReadiness.FORMAT_NOT_WRITABLE
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    assertThat(vm.editing().readiness).isEqualTo(CorrectionReadiness.FORMAT_NOT_WRITABLE)
    vm.edit { it.copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00") }
    assertThat(vm.editing().plan).isNotNull()
  }

  @Test
  fun leRagioniPerCuiNonSiPuoSonoUnoStatoACasa() = runTest(dispatcher) {
    for (reason in listOf(
      CorrectionReadiness.COMMON_AREA_NOTICE,
      CorrectionReadiness.READ_ONLY_AREA,
      CorrectionReadiness.NAS_UNREACHABLE,
      CorrectionReadiness.FROZEN,
      CorrectionReadiness.NOT_MINE,
      CorrectionReadiness.NOT_AVAILABLE,
    )) {
      corrections.readiness = reason
      val vm = viewModel()
      vm.bind(subject, CorrectionMode.ALL)
      assertThat(vm.state.value).isEqualTo(CorrectionUi.Blocked(reason))
    }
  }

  @Test
  fun scrivendoUnaDataValidaNasceIlPiano_UnaSbagliataDaGliErrori() = runTest(dispatcher) {
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(changeDate = true, dateText = "31/02/2026") }
    assertThat(vm.editing().errors).containsExactly(DraftError.DATE_INVALID)
    assertThat(vm.editing().plan).isNull()
    vm.edit { it.copy(dateText = "03/10/2026", timeText = "10:00") }
    assertThat(vm.editing().errors).isEmpty()
    assertThat(vm.editing().plan).isNotNull()
    vm.edit { it.copy(changeDate = false) }
    assertThat(vm.editing().plan).isNull()
    // "Niente da correggere" non e' un errore da mostrare.
    assertThat(vm.editing().errors).isEmpty()
  }

  @Test
  fun ilLuogoSiCercaPerNomeEConDueNomiSiSceglieUno() = runTest(dispatcher) {
    val bergen = PlaceChoice("Bergen", GeoPoint(60.39, 5.32))
    val finder = CorrectionPlaces { text -> if (text.startsWith("Ber")) listOf(bergen, PlaceChoice("Berlino", GeoPoint(52.52, 13.4))) else emptyList() }
    val vm = viewModel(places = finder)
    vm.bind(subject, CorrectionMode.ALL)
    assertThat(vm.editing().canSearchPlaces).isTrue()
    vm.edit { it.copy(place = PlaceEdit.CHANGE) }
    vm.searchPlaces("Ber")
    assertThat(vm.editing().places).containsExactly(bergen, PlaceChoice("Berlino", GeoPoint(52.52, 13.4))).inOrder()
    vm.choosePlace(bergen)
    assertThat(vm.editing().plan).isNotNull()
    vm.searchPlaces("Zzz")
    assertThat(vm.editing().places).isEmpty()
    assertThat(vm.editing().plan).isNull()
  }

  @Test
  fun senzaRicercaDeiLuoghiCeSoloLeCoordinate() = runTest(dispatcher) {
    val vm = viewModel(places = null)
    vm.bind(subject, CorrectionMode.ALL)
    assertThat(vm.editing().canSearchPlaces).isFalse()
    vm.edit { it.copy(place = PlaceEdit.CHANGE, coordinatesText = "45,4642 9,1900") }
    assertThat(vm.editing().plan).isNotNull()
  }

  @Test
  fun correggereUnMediaDelTelefonoChiedeIlPermessoPoiCarica_PoiEFatto() = runTest(dispatcher) {
    corrections.phoneRows = listOf(VersionSamples.phoneRow)
    val recording = Recording(corrections)
    val vm = viewModel(recording)
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00") }
    val confirmation = vm.confirmation()

    vm.apply(confirmation)
    assertThat(vm.state.value).isEqualTo(CorrectionUi.AwaitingSystemDialog(rows = 1))
    assertThat(dialogs.writeRequests).hasSize(1)
    assertThat(corrections.applied).isEmpty()

    vm.bridge.answered(true)
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Uploading(onlyInApp = false))
    assertThat(corrections.applied).hasSize(1)

    // Il backup porta la versione sul NAS e la verifica: la pagina lo vede dal custode.
    val version = recording.version!!
    keeper.setPlan(VersionSamples.plan(versions = listOf(VersionSamples.original(), VersionSamples.current(safe = true).copy(versionId = version))))
    advanceTimeBy(3_100)
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Done(onlyInApp = false))
  }

  @Test
  fun unNasSenzaTelefonoNonChiedeNessunPermesso() = runTest(dispatcher) {
    corrections.route = dev.pampa.mycloudgallery.core.backup.contract.CorrectionRoute.NAS_ONLY
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ROTATE)
    vm.edit { it.copy(quarterTurns = 1) }
    vm.apply(vm.confirmation())
    assertThat(dialogs.writeRequests).isEmpty()
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Uploading(onlyInApp = false))
  }

  @Test
  fun seLUtenteDiceDiNoAlTelefonoNonSiTocaNienteESiTornaAScrivere() = runTest(dispatcher) {
    corrections.phoneRows = listOf(VersionSamples.phoneRow)
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(place = PlaceEdit.CLEAR) }
    vm.apply(vm.confirmation())
    vm.bridge.answered(false)
    val editing = vm.editing()
    assertThat(editing.message).isEqualTo(CorrectionMessage.PERMISSION_REFUSED)
    assertThat(editing.draft.place).isEqualTo(PlaceEdit.CLEAR)
    assertThat(editing.plan).isNotNull()
    assertThat(corrections.applied).isEmpty()
  }

  @Test
  fun senzaIlDialogoDelSistemaNonSiCorreggeNiente() = runTest(dispatcher) {
    corrections.phoneRows = listOf(VersionSamples.phoneRow)
    dialogs.missing = true
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(place = PlaceEdit.CLEAR) }
    vm.apply(vm.confirmation())
    assertThat(vm.editing().message).isEqualTo(CorrectionMessage.NO_SYSTEM_DIALOG)
    assertThat(corrections.applied).isEmpty()
  }

  @Test
  fun heicRawEVideoRestanoCorrettiSoloNellApp() = runTest(dispatcher) {
    corrections.readiness = CorrectionReadiness.FORMAT_NOT_WRITABLE
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00") }
    vm.apply(vm.confirmation())
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Done(onlyInApp = true))
    assertThat(corrections.applied).isEmpty()
  }

  @Test
  fun unGuastoDiceIlSuoCodice() = runTest(dispatcher) {
    for (failure in CorrectionFailure.entries) {
      val scripted = ScriptedCorrections(clock)
      scripted.failNext = failure
      val vm = viewModel(scripted)
      vm.bind(subject, CorrectionMode.ALL)
      vm.edit { it.copy(quarterTurns = 1) }
      vm.apply(vm.confirmation())
      assertThat(vm.state.value).isEqualTo(CorrectionUi.Failed(failure))
    }
  }

  @Test
  fun unaConfermaVecchiaODiUnAltroPianoRiportaAScrivereConIlSuoMotivo() = runTest(dispatcher) {
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(quarterTurns = 1) }
    val old = vm.confirmation()
    clock.advance(PhoneConfirmationRules.MAX_AGE_MS + 1)
    vm.apply(old)
    assertThat(vm.editing().message).isEqualTo(CorrectionMessage.CONFIRMATION_EXPIRED)
    assertThat(corrections.applied).isEmpty()
    assertThat(vm.editing().plan).isNotNull()
  }

  @Test
  fun lAreaCheDiventaCongelataDuranteLaScritturaFermaTutto() = runTest(dispatcher) {
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(quarterTurns = 1) }
    val confirmation = vm.confirmation()
    corrections.readiness = CorrectionReadiness.FROZEN
    vm.apply(confirmation)
    // Il piano ricalcolato e' un altro (cambia la prontezza): il servizio lo dice, la pagina non scrive.
    assertThat(corrections.applied).isEmpty()
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Blocked(CorrectionReadiness.FROZEN))
  }

  @Test
  fun inModoRuotaLaBozzaNonVedeDataELuogo() = runTest(dispatcher) {
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ROTATE)
    vm.edit { it.copy(changeDate = true, dateText = "xx", place = PlaceEdit.CLEAR, quarterTurns = 2) }
    val editing = vm.editing()
    assertThat(editing.errors).isEmpty()
    assertThat(editing.plan!!.request.change.orientation).isEqualTo(ExifOrientation.ROTATE_180)
  }

  @Test
  fun chiudereERiaprireRiparteDaCapo() = runTest(dispatcher) {
    val vm = viewModel()
    vm.bind(subject, CorrectionMode.ALL)
    vm.edit { it.copy(quarterTurns = 1) }
    vm.reset()
    assertThat(vm.state.value).isEqualTo(CorrectionUi.Loading)
    vm.bind(subject, CorrectionMode.ALL)
    assertThat(vm.editing().draft.quarterTurns).isEqualTo(0)
  }
}
