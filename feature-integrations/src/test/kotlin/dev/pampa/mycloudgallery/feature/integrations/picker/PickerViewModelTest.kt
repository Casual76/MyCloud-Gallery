package dev.pampa.mycloudgallery.feature.integrations.picker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.provider.DeliveryPreparer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIssuer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.provider.NoPreparation
import dev.pampa.mycloudgallery.feature.integrations.provider.PreparedBatch
import dev.pampa.mycloudgallery.feature.integrations.provider.PreparedInfo
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
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
    preparer: DeliveryPreparer = NoPreparation,
  ): Rig {
    val gate = FakeEntryGate(decision)
    val table = DocumentIdTable(random = Random(5))
    val documents = LibraryMediaDocuments(library, labels, table, preparer = preparer)
    val vm = PickerViewModel(request, gate, library, issuerWrap(documents), authority, vmScope(), probeMs, preparer)
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

  /**
   * IN-3 della verifica funzionale 5B: a processo freddo l'app sta ancora leggendo il suo stato. Il selettore non decide e non mostra niente (prima:
   * "Prima configura MyCloud Gallery" o lo sblocco senza richiesta), aspetta, e quando lo stato c'e' decide da solo.
   */
  @Test
  fun in3_aFreddoIlSelettoreAspettaLoStatoEPoiMostraLaGriglia() = runTest {
    val r = rig(decision = EntryDecision.LOADING)
    r.vm.refreshGate()
    runCurrent() // senza far scorrere il tempo: l'attesa e' in corso
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.CHECKING) // ne' "non pronta" ne' lo sblocco
    assertThat(r.ui.items.size).isEqualTo(0)
    r.gate.decision = EntryDecision.ALLOWED
    advanceUntilIdle()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.GRID)
    assertThat(r.ui.items.size).isGreaterThan(0)
  }

  @Test
  fun in3_seLoStatoNonArrivaNonSiMostraNiente_EIlRitornoRiprova() = runTest {
    val r = rig(decision = EntryDecision.LOADING)
    r.gate.loadTimeoutMs = 50
    r.open(this)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.NOT_READY)
    r.gate.decision = EntryDecision.ALLOWED
    r.open(this)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.GRID)
  }

  /** IN-10: chi annulla la schermata di sistema non legge "Non sono riuscito a sbloccare"; chi non ha un blocco schermo legge cosa fare. */
  @Test
  fun in10_loSbloccoDiceIlMotivoSeLoSa() = runTest {
    val r = rig(decision = EntryDecision.NEEDS_UNLOCK)
    r.open(this)
    assertThat(r.vm.beginUnlock()).isTrue()
    r.vm.endUnlock(false, UnlockFailure.CANCELLED)
    assertThat(r.ui.unlockFailed).isTrue()
    assertThat(r.ui.unlockFailure).isEqualTo(UnlockFailure.CANCELLED)
    assertThat(r.vm.beginUnlock()).isTrue() // il tasto resta
    assertThat(r.ui.unlockFailure).isNull()
    r.vm.endUnlock(false, UnlockFailure.NO_SCREEN_LOCK)
    assertThat(r.ui.unlockFailure).isEqualTo(UnlockFailure.NO_SCREEN_LOCK)
    // Le notifiche della vista non cancellano il motivo.
    r.library.showAll()
    advanceUntilIdle()
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.LOCKED)
    assertThat(r.ui.unlockFailure).isEqualTo(UnlockFailure.NO_SCREEN_LOCK)
  }

  // --- Accesso parziale (IN-9) ---------------------------------------------------------------------------------------------

  /**
   * IN-9: con l'accesso parziale la vista della Libreria e' una parte dell'indice. La griglia del selettore scorreva l'indice intero e offriva anche
   * le foto che il telefono non concede (senza miniatura) e i solo-NAS che la Libreria nasconde; e una scelta fuori dalla vista usciva lo stesso.
   */
  @Test
  fun in9_conLAccessoParzialeLaGrigliaEQuellaDellaVista() = runTest {
    val r = rig()
    val allowed = r.library.visibleIds.filterIndexed { n, _ -> n % 9 == 4 }.toSet()
    r.library.restrictView(allowed)
    r.open(this)
    assertThat(r.ui.items.size).isEqualTo(allowed.size)
    assertThat((0 until r.ui.items.size).map { r.ui.items.idAt(it) }.toSet()).isEqualTo(allowed)

    // La concessione cambia mentre la griglia e' aperta: cio' che non e' piu' concesso esce dalla selezione.
    val keep = allowed.first()
    val drop = allowed.last()
    r.vm.onTap(keep)
    r.vm.onTap(drop)
    r.library.restrictView(setOf(keep))
    advanceUntilIdle()
    assertThat(r.ui.selected).containsExactly(keep)
    assertThat(r.ui.items.size).isEqualTo(1)
  }

  @Test
  fun in9_UnaScelta_FuoriDallaVista_NonEsceAnche_SeLIndiceLaConosce() = runTest {
    val r = rig()
    val allowed = r.library.phoneIds().take(2).toSet()
    r.library.restrictView(allowed)
    r.open(this)
    val outside = r.library.phoneIds().first { it !in allowed }
    // Anche se qualcuno ci arriva (una selezione fatta prima della restrizione), la consegna ricontrolla la vista di adesso.
    r.library.showAll()
    r.vm.onTap(outside)
    r.library.restrictView(allowed)
    r.vm.confirm()
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isNull()
  }

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

  /** CA-2 delle revisioni della Fase 10: con la versione fermata dal manifest il selettore lo dice e non offre niente (prima mostrava la Libreria e consegnava). */
  @Test
  fun ca2_conLaVersioneFermataIlSelettoreLoDiceENonOffreNiente() = runTest {
    val r = rig(decision = EntryDecision.STOPPED)
    r.open(this)
    assertThat(r.ui.screen).isEqualTo(PickerScreenState.STOPPED)
    assertThat(r.ui.items.size).isEqualTo(0)
    // Fermata a meta' (il manifest arriva con il selettore aperto): la griglia e la selezione spariscono.
    val open = rig()
    open.open(this)
    open.vm.onTap(open.library.phoneIds()[0])
    open.gate.decision = EntryDecision.STOPPED
    open.vm.refreshGate()
    assertThat(open.ui.screen).isEqualTo(PickerScreenState.STOPPED)
    assertThat(open.ui.selected).isEmpty()
    assertThat(open.ui.items.size).isEqualTo(0)
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

  /**
   * R8 della revisione di sicurezza 5B: senza `EXTRA_ALLOW_MULTIPLE` un tocco sceglie (una sola, un altro tocco la sostituisce, lo stesso la toglie)
   * e la consegna e' "Scegli": un tocco da una sovrapposizione non consegna niente da solo.
   */
  @Test
  fun r8_senzaSceltaMultiplaUnTocco_Sceglie_EServeScegli() = runTest {
    val r = rig(request = request(multiple = false))
    r.open(this)
    val ids = r.library.phoneIds()
    r.vm.onTap(ids[0])
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isNull() // niente e' uscito
    assertThat(r.vm.ui.value.selected).containsExactly(ids[0])
    r.vm.onTap(ids[1]) // un'altra scelta sostituisce la prima
    assertThat(r.vm.ui.value.selected).containsExactly(ids[1])
    r.vm.onTap(ids[1]) // lo stesso tocco la toglie
    assertThat(r.vm.ui.value.selected).isEmpty()
    r.vm.confirm() // niente da consegnare
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isNull()
    r.vm.onTap(ids[0])
    r.vm.confirm()
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

  // --- "Rimuovi la posizione" acceso: le copie si preparano prima di consegnare (decisione del 20/10/2026) ------------------------

  /** Una preparazione scritta dal test: ferma dove si vuole, risponde "pronta" o "fermata per...", ricorda cosa le e' stato chiesto. */
  private class ScriptedPreparer(var on: Boolean = true) : DeliveryPreparer {
    var hold: CompletableDeferred<Unit>? = null
    var refuse: PreparedBatch.Refused? = null
    val asked = ArrayList<List<MediaId>>()
    val released = ArrayList<MediaId>()

    override fun removeLocation(): Boolean = on

    override suspend fun prepare(ids: List<MediaId>, delivered: Boolean, onItem: (Int, Int) -> Unit, onBytes: (Long, Long) -> Unit): PreparedBatch {
      asked += ids
      onItem(0, ids.size)
      onBytes(10L, 100L)
      hold?.await()
      refuse?.let { return it }
      onItem(ids.size, ids.size)
      return PreparedBatch.Ready(ids)
    }

    override fun infoOf(id: MediaId): PreparedInfo? = null

    override fun open(id: MediaId): DocumentReader? = null

    override fun release(ids: Collection<MediaId>) {
      released += ids
    }

    override fun releaseAll() = Unit
  }

  @Test
  fun conLaPosizioneDaTogliereLaConsegnaMostraLAvanzamentoEPoiEsceConIdDelProvider() = runTest {
    val preparer = ScriptedPreparer().apply { hold = CompletableDeferred() }
    val r = rig(preparer = preparer)
    r.open(this)
    val ids = r.library.phoneIds().take(2) + r.library.nasOnlyIds().take(1)
    ids.forEach { r.vm.onTap(it) }

    r.vm.confirm()
    runCurrent()

    // La preparazione e' in corso: niente e' uscito, e la schermata ha l'avanzamento (media fatti e byte del media in corso).
    assertThat(r.vm.ui.value.screen).isEqualTo(PickerScreenState.DELIVERING)
    assertThat(r.vm.ui.value.progress).isEqualTo(PickerProgress(done = 0, total = 3, bytesDone = 10, bytesTotal = 100))
    assertThat(r.vm.outcome.value).isNull()
    preparer.hold!!.complete(Unit)
    advanceUntilIdle()

    val out = r.vm.outcome.value as PickerOutcome.Deliver
    // Anche le foto del telefono escono come documenti del provider (con la copia senza posizione), mai come indirizzi del MediaStore.
    assertThat(out.uris).hasSize(3)
    out.uris.forEach { assertThat(it.authority).isEqualTo(authority) }
    assertThat(preparer.asked.single()).containsExactlyElementsIn(ids)
  }

  @Test
  fun annullareLaPreparazioneTornaAllaGrigliaConLaSceltaENienteEsce() = runTest {
    val preparer = ScriptedPreparer().apply { hold = CompletableDeferred() }
    val r = rig(preparer = preparer)
    r.open(this)
    val ids = r.library.phoneIds().take(2)
    ids.forEach { r.vm.onTap(it) }
    r.vm.confirm()
    runCurrent()
    assertThat(r.vm.ui.value.screen).isEqualTo(PickerScreenState.DELIVERING)

    r.vm.cancelDelivery()
    advanceUntilIdle()

    assertThat(r.vm.ui.value.screen).isEqualTo(PickerScreenState.GRID)
    assertThat(r.vm.ui.value.progress).isNull()
    assertThat(r.vm.ui.value.selected).containsExactlyElementsIn(ids)
    assertThat(r.vm.outcome.value).isNull()
    // E la preparazione non riparte da sola: la consegna e' finita.
    preparer.hold!!.complete(Unit)
    advanceUntilIdle()
    assertThat(r.vm.outcome.value).isNull()
  }

  @Test
  fun unFileCheNonSiPuoSenzaPosizioneSiTogliDallaSceltaENienteEsce() = runTest {
    val r0 = FakeIntegrationLibrary()
    val ids = r0.phoneIds().take(3)
    val preparer = ScriptedPreparer().apply { refuse = PreparedBatch.Refused(ShareStop.CANNOT_REMOVE_LOCATION, ids[1]) }
    val r = rig(library = r0, preparer = preparer)
    r.open(this)
    ids.forEach { r.vm.onTap(it) }

    r.vm.confirm()
    advanceUntilIdle()

    assertThat(r.vm.outcome.value).isNull()
    assertThat(r.vm.ui.value.screen).isEqualTo(PickerScreenState.GRID)
    assertThat(r.vm.ui.value.notice).isEqualTo(PickerNotice.NoLocation(ShareStop.CANNOT_REMOVE_LOCATION, removedFromChoice = true))
    assertThat(r.vm.ui.value.selected).containsExactly(ids[0], ids[2])
    // Con un altro "Scegli" gli altri escono.
    preparer.refuse = null
    r.vm.confirm()
    advanceUntilIdle()
    assertThat((r.vm.outcome.value as PickerOutcome.Deliver).uris).hasSize(2)
  }

  @Test
  fun conLaPosizioneDaTogliereSiScelgonoAlPiuCentoElementi() = runTest {
    val preparer = ScriptedPreparer()
    val r = rig(preparer = preparer)
    r.open(this)
    val ids = r.library.phoneIds()
    ids.take(100).forEach { r.vm.onTap(it) }
    assertThat(r.vm.ui.value.selected).hasSize(100)

    r.vm.onTap(ids[100])

    assertThat(r.vm.ui.value.selected).hasSize(100)
    assertThat(r.vm.ui.value.notice).isEqualTo(PickerNotice.TooMany(100))
    // Spenta, il tetto torna quello di sempre.
    preparer.on = false
    r.vm.dismissNotice()
    r.vm.onTap(ids[100])
    assertThat(r.vm.ui.value.selected).hasSize(101)
  }
}
