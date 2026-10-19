package dev.pampa.mycloudgallery.feature.integrations.picker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIssuer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P10, D23, C17; E-H1, E-X1, E-X4, E-X6, E-X7): lo stato del selettore con il telefono finto e il NAS finto. Il blocco dell'app prima di
 * tutto, i nascosti mai, un tocco per consegnare, i tetti, il NAS che non risponde e l'annullamento a meta'.
 */
@RunWith(AndroidJUnit4::class)
class PickerViewModelTest {

  private val labels = DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS")
  private val authority = "test.pkg.integrations.documents"

  @Before
  fun reset() = IntegrationCounters.reset()

  private val scopes = mutableListOf<CoroutineScope>()

  @After
  fun cleanup() {
    scopes.forEach { it.cancel() }
    IntegrationCounters.reset()
  }

  /**
   * Lo scope del view model: sullo stesso orologio del test ma **non** `backgroundScope` (i cui compiti `advanceUntilIdle` non esegue). Si chiude
   * a fine prova: la raccolta della vista non finisce mai da sola.
   */
  private fun TestScope.vmScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

  private class Rig(
    val library: FakeIntegrationLibrary,
    val gate: FakeEntryGate,
    val table: DocumentIdTable,
    val documents: LibraryMediaDocuments,
    val vm: PickerViewModel,
  )

  private fun TestScope.rig(
    request: PickerRequest = request(),
    decision: EntryDecision = EntryDecision.ALLOWED,
    library: FakeIntegrationLibrary = FakeIntegrationLibrary(),
    issuerWrap: (DocumentIssuer) -> DocumentIssuer = { it },
    probeMs: Long = 5_000,
  ): Rig {
    val gate = FakeEntryGate(decision)
    val table = DocumentIdTable(random = Random(5))
    val documents = LibraryMediaDocuments(library, labels, table)
    val vm = PickerViewModel(request, gate, library, issuerWrap(documents), authority, vmScope(), probeMs)
    return Rig(library, gate, table, documents, vm)
  }

  private fun request(multiple: Boolean = true, images: Boolean = true, videos: Boolean = true, localOnly: Boolean = false) = PickerRequest(
    action = PickerAction.GET_CONTENT,
    images = images,
    videos = videos,
    allowMultiple = multiple,
    localOnly = localOnly,
    mimeFilters = listOf("*/*"),
  )

  private fun Rig.open(scope: TestScope) {
    vm.refreshGate()
    scope.advanceUntilIdle()
  }

  private val Rig.ui get() = vm.ui.value

  // --- Il cancello (E-X7) -----------------------------------------------------------------------------------------------

  @Test
  fun conLAppBloccata_PrimaLoSblocco_EdellaLibreriaNonRestaNiente_E_X7() = runTest {
    val r = rig(decision = EntryDecision.NEEDS_UNLOCK)
    r.open(this)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.LOCKED)
    assertThat(r.ui.items.size).isEqualTo(0)
    assertThat(r.ui.selected).isEmpty()
    // Un tocco non porta da nessuna parte: non c'e' nessuna griglia, quindi niente si sceglie ne' si consegna.
    r.vm.onTap(r.library.visibleIds.first())
    r.vm.confirm()
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isNull()
    assertThat(r.library.fileInfoCalls.get()).isEqualTo(0)
    assertThat(r.library.nasReachCalls.get()).isEqualTo(0)
    assertThat(r.table.size).isEqualTo(0)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_LOCKED)).isEqualTo(1L)
  }

  @Test
  fun dopoLoSbloccoAppareLaGriglia() = runTest {
    val r = rig(decision = EntryDecision.NEEDS_UNLOCK)
    r.open(this)
    assertThat(r.vm.beginUnlock()).isTrue()
    assertThat(r.ui.unlocking).isTrue()
    assertThat(r.vm.beginUnlock()).isFalse() // uno alla volta
    r.gate.decision = EntryDecision.ALLOWED
    r.vm.endUnlock(true)
    advanceUntilIdle()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.GRID)
    assertThat(r.ui.unlocking).isFalse()
    assertThat(r.ui.items.size).isGreaterThan(0)
  }

  @Test
  fun unoSbloccoCheNonCambiaIlCancelloNonVale() = runTest {
    val r = rig(decision = EntryDecision.NEEDS_UNLOCK)
    r.open(this)
    r.vm.beginUnlock()
    r.vm.endUnlock(true) // il cancello dice ancora "bloccata": lo sblocco non vale
    advanceUntilIdle()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.LOCKED)
    assertThat(r.ui.unlockFailed).isTrue()
    r.vm.beginUnlock()
    r.vm.endUnlock(false)
    assertThat(r.ui.unlockFailed).isTrue()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.LOCKED)
  }

  @Test
  fun appNonPronta_NonSiOffreNiente() = runTest {
    val r = rig(decision = EntryDecision.NOT_READY)
    r.open(this)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.NOT_READY)
    assertThat(r.ui.items.size).isEqualTo(0)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_NOT_READY)).isEqualTo(1L)
  }

  @Test
  fun seIlBloccoSiRichiudeLaGrigliaELaSelezioneSvaniscono() = runTest {
    val r = rig()
    r.open(this)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.GRID)
    r.vm.onTap(r.library.phoneIds()[0])
    r.vm.onTap(r.library.phoneIds()[1])
    assertThat(r.ui.selected).hasSize(2)
    r.gate.decision = EntryDecision.NEEDS_UNLOCK
    r.vm.refreshGate() // il ritorno in primo piano rilegge il cancello
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.LOCKED)
    assertThat(r.ui.items.size).isEqualTo(0)
    assertThat(r.ui.selected).isEmpty()
  }

  @Test
  fun niente_EsceSeIlBloccoSiERichiusoAlMomentoDelTocco() = runTest {
    val r = rig()
    r.open(this)
    r.vm.onTap(r.library.phoneIds().first())
    r.vm.onTap(r.library.nasOnlyIds().first())
    r.gate.decision = EntryDecision.NEEDS_UNLOCK // chiuso fra l'ultimo ritorno e il tocco su "Scegli"
    r.vm.confirm()
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isNull()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.LOCKED)
    // Il cancello si rilegge al tocco: non si chiede niente al catalogo e al NAS, non si crea nessun id.
    assertThat(r.library.fileInfoCalls.get()).isEqualTo(0)
    assertThat(r.library.nasReachCalls.get()).isEqualTo(0)
    assertThat(r.table.size).isEqualTo(0)
  }

  @Test
  fun seIlBloccoSiRichiudeDuranteLaConsegnaGliIdAppenaCreatiSiRevocano_E_X7() = runTest {
    val minted = mutableListOf<DocumentId>()
    val r = rig(
      issuerWrap = { real ->
        object : DocumentIssuer by real {
          override fun issue(id: MediaId): DocumentId? = real.issue(id)?.also { minted += it }
        }
      },
    )
    r.open(this)
    // La prova del NAS e' lenta: nel frattempo il blocco si chiude. Gli id nascono comunque (non si sospende piu'), ma l'ultimo cancello li ferma.
    r.library.beforeNasReach = { r.gate.decision = EntryDecision.NEEDS_UNLOCK }
    r.library.nasOnlyIds().take(2).forEach { r.vm.onTap(it) }
    r.vm.confirm()
    advanceUntilIdle()
    assertThat(minted).hasSize(2)
    assertThat(r.vm.outcome.value).isNull()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.LOCKED)
    assertThat(r.ui.selected).isEmpty()
    // Nessun id resta valido: l'altra app non riceve niente e niente apre niente.
    assertThat(r.table.size).isEqualTo(0)
    for (token in minted) assertThat(r.documents.isDelivered(token)).isFalse()
  }

  // --- La griglia: nascosti mai (E-H1), tipo, solo locale ----------------------------------------------------------------

  @Test
  fun laGrigliaNonHaMaiIMediaNascosti() = runTest {
    val library = FakeIntegrationLibrary().also { it.leakHidden = true }
    val r = rig(library = library)
    r.open(this)
    val shown = (0 until r.ui.items.size).map { r.ui.items.idAt(it) }.toSet()
    assertThat(shown).containsNoneIn(library.hiddenIds)
    assertThat(shown).hasSize(library.visibleIds.size)
  }

  @Test
  fun unMediaNascostoDopoLaGrigliaEsceAncheDallaSelezione() = runTest {
    val r = rig()
    r.open(this)
    val ids = r.library.phoneIds().take(3)
    ids.forEach { r.vm.onTap(it) }
    assertThat(r.ui.selected).containsExactlyElementsIn(ids)
    r.library.hide(setOf(ids[1]))
    advanceUntilIdle()
    assertThat(r.ui.selected).containsExactly(ids[0], ids[2])
    assertThat((0 until r.ui.items.size).map { r.ui.items.idAt(it) }).doesNotContain(ids[1])
    r.vm.confirm()
    advanceUntilIdle()
    val out = r.vm.outcome.value as PickerOutcome.Deliver
    assertThat(out.uris).hasSize(2)
  }

  @Test
  fun leFotoSoloFotoNonMostranoVideo() = runTest {
    val r = rig(request = request(videos = false))
    r.open(this)
    val kinds = (0 until r.ui.items.size).map { r.ui.items.snapshot.kindAt(r.ui.items.snapshotIndex(it)) }
    assertThat(kinds).doesNotContain(MediaKind.VIDEO)
    val videos = rig(request = request(images = false))
    videos.open(this)
    val videoKinds = (0 until videos.ui.items.size).map { videos.ui.items.snapshot.kindAt(videos.ui.items.snapshotIndex(it)) }
    assertThat(videoKinds.toSet()).containsExactly(MediaKind.VIDEO)
  }

  @Test
  fun conSoloLocaleNonSiOffronoIMediaSoloNas() = runTest {
    val r = rig(request = request(localOnly = true))
    r.open(this)
    val shown = (0 until r.ui.items.size).map { r.ui.items.idAt(it) }.toSet()
    assertThat(shown).containsNoneIn(r.library.nasOnlyIds())
    assertThat(shown).isNotEmpty()
  }

  // --- Un tocco e la consegna --------------------------------------------------------------------------------------------

  @Test
  fun senzaSceltaMultiplaUnTocco_Consegna() = runTest {
    val r = rig(request = request(multiple = false))
    r.open(this)
    val id = r.library.phoneIds().first()
    r.vm.onTap(id)
    advanceUntilIdle()
    val out = r.vm.outcome.value as PickerOutcome.Deliver
    assertThat(out.uris).hasSize(1)
    assertThat(out.skipped).isEqualTo(0)
  }

  @Test
  fun conSceltaMultiplaServeIlTocco_Scegli() = runTest {
    val r = rig()
    r.open(this)
    r.library.phoneIds().take(3).forEach { r.vm.onTap(it) }
    assertThat(r.vm.outcome.value).isNull() // niente esce da solo
    r.vm.confirm()
    advanceUntilIdle()
    assertThat((r.vm.outcome.value as PickerOutcome.Deliver).uris).hasSize(3)
    // Un secondo "Scegli" non consegna di nuovo.
    val first = r.vm.outcome.value
    r.vm.confirm()
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isSameInstanceAs(first)
  }

  @Test
  fun unSecondoTocco_ToglieDallaSelezione() = runTest {
    val r = rig()
    r.open(this)
    val id = r.library.phoneIds().first()
    r.vm.onTap(id)
    r.vm.onTap(id)
    assertThat(r.ui.selected).isEmpty()
    r.vm.confirm()
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isNull() // niente scelto, niente da consegnare
  }

  // --- Tetti (E-X6) -----------------------------------------------------------------------------------------------------

  @Test
  fun ilCentounesimoSoloNasNonSiAggiungeESpiegaPerche_E_X6() = runTest {
    val r = rig()
    r.open(this)
    val nas = r.library.nasOnlyIds()
    nas.take(DocumentPolicy.MAX_NAS_ONLY_ITEMS).forEach { r.vm.onTap(it) }
    assertThat(r.ui.selected).hasSize(DocumentPolicy.MAX_NAS_ONLY_ITEMS)
    r.vm.onTap(nas[DocumentPolicy.MAX_NAS_ONLY_ITEMS])
    assertThat(r.ui.selected).hasSize(DocumentPolicy.MAX_NAS_ONLY_ITEMS)
    assertThat(r.ui.notice).isInstanceOf(PickerNotice.TooManyNasOnly::class.java)
    // Una copia del telefono si puo' aggiungere ancora.
    r.vm.onTap(r.library.phoneIds().first())
    assertThat(r.ui.selected).hasSize(DocumentPolicy.MAX_NAS_ONLY_ITEMS + 1)
    assertThat(r.ui.notice).isNull()
  }

  @Test
  fun ilTettoTotaleDegliElementi_E_X6() = runTest {
    // Piu' di cinquecento elementi insieme non si consegnano: l'utente trova sempre il motivo.
    val large = FakeIntegrationLibrary(SyntheticLibrary(seed = 3, count = 900, mix = null))
    val r = rig(library = large)
    r.open(this)
    large.visibleIds.take(DocumentPolicy.MAX_PICKED_ITEMS + 5).forEach { r.vm.onTap(it) }
    assertThat(r.ui.selected).hasSize(DocumentPolicy.MAX_PICKED_ITEMS)
    assertThat(r.ui.notice).isEqualTo(PickerNotice.TooMany(DocumentPolicy.MAX_PICKED_ITEMS))
  }

  @Test
  fun troppiSoloNasDimensioneAlConfermare_LaSelezioneRestaPerCorreggerla_E_X6() = runTest {
    val r = rig()
    r.open(this)
    val ids = r.library.nasOnlyIds().take(3)
    ids.forEach { r.library.sizeOverride[it] = 1024L * 1024 * 1024 }
    ids.forEach { r.vm.onTap(it) }
    r.vm.confirm()
    advanceUntilIdle()
    val notice = r.ui.notice as PickerNotice.TooManyNasOnly
    assertThat(notice.bytes).isEqualTo(3L * 1024 * 1024 * 1024)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.GRID)
    assertThat(r.ui.selected).containsExactlyElementsIn(ids)
    assertThat(r.vm.outcome.value).isNull()
    assertThat(r.table.size).isEqualTo(0)
    // Toglie uno e consegna.
    r.vm.onTap(ids[0])
    r.vm.confirm()
    advanceUntilIdle()
    assertThat((r.vm.outcome.value as PickerOutcome.Deliver).uris).hasSize(2)
  }

  // --- NAS irraggiungibile (E-X1) ---------------------------------------------------------------------------------------

  @Test
  fun ilNasNonRisponde_ErroreSubitoConIlMotivo_LaSelezioneRestaEdELaScelta_E_X1() = runTest {
    val r = rig()
    r.open(this)
    r.library.reach = NasReach.Unreachable(NasUnreachableReason.METERED)
    val nas = r.library.nasOnlyIds().take(2)
    val phone = r.library.phoneIds().take(2)
    (nas + phone).forEach { r.vm.onTap(it) }
    r.vm.confirm()
    advanceUntilIdle()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.GRID)
    assertThat(r.ui.notice).isEqualTo(PickerNotice.NasUnreachable(NasUnreachableReason.METERED))
    assertThat(r.ui.selected).hasSize(4)
    assertThat(r.vm.outcome.value).isNull()
    assertThat(r.table.size).isEqualTo(0)
    // Toglie i solo-NAS e consegna le copie del telefono.
    nas.forEach { r.vm.onTap(it) }
    r.vm.confirm()
    advanceUntilIdle()
    assertThat((r.vm.outcome.value as PickerOutcome.Deliver).uris).hasSize(2)
  }

  @Test
  fun ilNasCheNonRispondeNonTieneInAttesa_E_X1() = runTest {
    val r = rig(probeMs = 200)
    r.open(this)
    r.library.beforeNasReach = { awaitCancellation() }
    r.library.nasOnlyIds().take(2).forEach { r.vm.onTap(it) }
    r.vm.confirm()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.DELIVERING)
    advanceUntilIdle() // il tempo virtuale arriva alla scadenza della prova
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.GRID)
    assertThat(r.ui.notice).isEqualTo(PickerNotice.NasUnreachable(NasUnreachableReason.OFFLINE))
  }

  // --- Annullamento a meta' (E-X4) ----------------------------------------------------------------------------------------

  @Test
  fun annullareMentreSiPreparaLaConsegnaNonLasciaNienteECancella_E_X4() = runTest {
    val r = rig()
    r.open(this)
    r.library.beforeNasReach = { awaitCancellation() }
    r.library.nasOnlyIds().take(3).forEach { r.vm.onTap(it) }
    r.vm.confirm()
    runCurrent() // la prova del NAS e' in corso e non finisce (senza far scorrere il tempo fino alla sua scadenza)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.DELIVERING)
    r.vm.cancel()
    runCurrent()
    assertThat(r.vm.outcome.value).isEqualTo(PickerOutcome.Cancel)
    assertThat(r.table.size).isEqualTo(0)
    assertThat(r.library.nasReaders).isEmpty()
    // La consegna annullata e' davvero finita: lasciando scorrere il tempo la scadenza della prova non cambia piu' niente.
    advanceUntilIdle()
    assertThat(r.ui.notice).isNull()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.DELIVERING)
    // Dopo l'annullamento niente puo' piu' consegnare.
    r.vm.confirm()
    r.vm.onTap(r.library.phoneIds().first())
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isEqualTo(PickerOutcome.Cancel)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_CANCELLED)).isEqualTo(1L)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_DELIVERED)).isEqualTo(0L)
  }

  @Test
  fun annullareDallaGrigliaEUnRisultatoAnnullato() = runTest {
    val r = rig()
    r.open(this)
    r.vm.onTap(r.library.phoneIds().first())
    r.vm.cancel()
    assertThat(r.vm.outcome.value).isEqualTo(PickerOutcome.Cancel)
    r.vm.refreshGate()
    assertThat(r.vm.outcome.value).isEqualTo(PickerOutcome.Cancel)
  }

  // --- Cio' che si dice ------------------------------------------------------------------------------------------------

  @Test
  fun loStatoNonScriveIdNeNomiNelToString() = runTest {
    val r = rig()
    r.open(this)
    r.library.phoneIds().take(2).forEach { r.vm.onTap(it) }
    assertThat(r.ui.toString()).matches("PickerUi\\(GRID, 2 scelti\\)")
    assertThat(PickerNotice.NothingAvailable.toString()).doesNotContain("IMG_")
  }
}
