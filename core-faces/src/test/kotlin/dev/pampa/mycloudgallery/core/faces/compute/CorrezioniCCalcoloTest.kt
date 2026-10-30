package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FaceGrouping
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesPermit
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.InputWait
import dev.pampa.mycloudgallery.core.faces.store.FacesUnavailable
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8, correzioni C (CA-1, CA-7): il progresso e' della libreria e non dell'unita' da 200 media, e un archivio che non si apre dice il suo motivo vero e ha il
 * suo codice. Ogni prova cade senza la correzione.
 */
class CorrezioniCCalcoloTest {
  private val forever = SimWorld.FOREVER

  /** Sette foto in tre unita' da 3 (due mesi): il progresso di una libreria da 7 media, non da 3. */
  private class Library(val config: FacesComputeConfig = FacesComputeConfig(unitSize = 3, batch = 2, reducedBatch = 1)) {
    val w = SimWorld()
    val phone = w.device(w.anna, "telefono-anna")
    val seen = ArrayList<FacesStatus.Running>()
    val atGrouping = ArrayList<FacesStatus>()

    /** Quante volte il calcolo ha chiesto i mesi di ogni area (il conto della libreria e la pianificazione delle unita'). */
    val monthReads = HashMap<FacesArea, Int>()

    init {
      repeat(4) { w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(it + 1)), month = "2024-03") }
      repeat(3) { w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(it + 5)), month = "2024-02") }
      // Il raggruppamento a fine unita' vede lo stato di adesso: quello della libreria, mai "100%" dell'unita'.
      val grouping = object : FaceGrouping {
        override val holder: StateFlow<HolderState> = MutableStateFlow(HolderState.Unknown)
        override suspend fun incremental(budget: Duration): GroupingReport {
          atGrouping += phone.engine.status.value
          return GroupingReport.EMPTY
        }

        override suspend fun nightly(budget: Duration): GroupingReport = GroupingReport.EMPTY

        override suspend fun recluster(budget: Duration): GroupingReport = GroupingReport.EMPTY
      }
      val spy = object : FacesCatalog by phone.catalog {
        override suspend fun months(area: FacesArea): List<String> {
          monthReads[area] = (monthReads[area] ?: 0) + 1
          return phone.catalog.months(area)
        }
      }
      phone.restart(config, phone.parts().copy(grouping = grouping, catalog = spy))
      // Lo stato di ogni media, preso quando il rilevatore parte (lo stato e' scritto prima di calcolarlo).
      phone.ticking.onDetect = { (phone.engine.status.value as? FacesStatus.Running)?.let { seen += it } }
    }
  }

  // ---- CA-1 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun ca1_ilProgressoEDellaLibreriaENonDellUnita() = runTest {
    val lib = Library()

    lib.phone.engine.runAll(forever)

    assertThat(lib.seen).hasSize(7)
    // Il totale e' la libreria (7), contato una volta: mai la misura dell'unita' (3).
    assertWithMessage("il totale e' della libreria").that(lib.seen.map { it.total }.toSet()).containsExactly(7)
    // I fatti crescono di uno a ogni media e non tornano indietro a ogni unita' (0..6).
    assertThat(lib.seen.map { it.done }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
    assertThat(lib.seen.all { it.phase == FacesPhase.DETECT }).isTrue()
  }

  @Test
  fun ca1_raggruppamentoEPubblicazioneNonDicono100PerCentoAFineUnita() = runTest {
    val lib = Library()

    lib.phone.engine.runAll(forever)

    // A fine unita' il raggruppamento vede la libreria: 3 di 7, poi 6 di 7, poi 7 di 7 (mai Running(3, 3) = 100% a meta' libreria).
    val grouped = lib.atGrouping.filterIsInstance<FacesStatus.Running>()
    assertThat(grouped).isNotEmpty()
    assertThat(grouped.map { it.total }.toSet()).containsExactly(7)
    assertThat(grouped.map { it.phase }.toSet()).containsExactly(FacesPhase.GROUPING)
    assertThat(grouped.first().done).isEqualTo(3)
    assertThat(grouped.map { it.done }).isInOrder()
    assertThat(grouped.last().done).isEqualTo(7)
  }

  @Test
  fun ca1_unaTappaCheRipartePartiDaiMediaGiaFatti() = runTest {
    val lib = Library()
    // La regola ferma la tappa al secondo lotto (a 2 media su 7).
    lib.phone.control.onAsk = { n -> if (n == 2) lib.phone.control.permit = FacesPermit.Stop(FacesStop.RULES) }
    val first = lib.phone.engine.runAll(forever).single().second
    assertThat(first.stop).isEqualTo(FacesStop.RULES)
    val doneBefore = first.analyzed
    assertThat(doneBefore).isGreaterThan(0)

    lib.seen.clear()
    lib.phone.control.onAsk = {}
    lib.phone.control.permit = FacesPermit.Go(reducedPace = false)
    lib.phone.engine.runAll(forever)

    // La seconda tappa conta la libreria da capo (una volta) e riparte dai fatti di prima: stesso totale, nessun ritorno a zero.
    assertThat(lib.seen.map { it.total }.toSet()).containsExactly(7)
    assertThat(lib.seen.first().done).isEqualTo(doneBefore)
    assertThat(lib.seen.last().done).isEqualTo(6)
  }

  @Test
  fun ca1_laLibreriaSiContaUnaVoltaPerTappaNonAOgniMedia() = runTest {
    val lib = Library()

    lib.phone.engine.runAll(forever)

    // Il conto legge i mesi di ogni area (e i loro media): una volta per tappa. Per i media del solo telefono, dove non c'e' niente da lavorare, le letture
    // sono due (il conto e la pianificazione dell'ambito); contato a ogni media sarebbero state almeno sette.
    assertThat(lib.monthReads[FacesArea.PHONE_ONLY]).isAtMost(2)
    assertThat(lib.monthReads[FacesArea.PHONE_ONLY]).isAtLeast(1)
  }

  @Test
  fun ca1_unaTappaChePartePerNienteNonContaLaLibreria() = runTest {
    val w = SimWorld()
    val phone = w.device(w.anna, "telefono-anna")
    val before = phone.catalog.calls
    // Niente da lavorare: nessuna unita', quindi nessun conto (una tappa che si ferma subito non paga la lettura del catalogo).
    phone.control.permit = FacesPermit.Stop(FacesStop.RULES)

    val results = phone.engine.runAll(forever)

    assertThat(results.single().second.stop).isEqualTo(FacesStop.RULES)
    assertThat(phone.catalog.calls).isEqualTo(before)
  }

  @Test
  fun ca1_unMediaSenzaAnteprimaNonFaAvanzareLaLibreria() = runTest {
    val lib = Library()
    // Il terzo media non ha l'anteprima da nessuna parte: aspetta (D17) e non e' "fatto".
    lib.phone.inputs.scripted[lib.w.nas.mediaIn(lib.w.anna.privateShare).keys.first()] = FaceInput.Waiting(InputWait.NO_PREVIEW)

    val results = lib.phone.engine.runAll(forever)

    assertThat(lib.seen.map { it.total }.toSet()).containsExactly(7)
    // I media che il rilevatore vede sono sei; quando parte l'ultimo ne sono finiti al piu' cinque: quello che aspetta non e' "fatto".
    assertThat(lib.seen).hasSize(6)
    assertThat(lib.seen.maxOf { it.done }).isAtMost(5)
    assertThat(results.first().second.waiting).isEqualTo(1)
  }

  // ---- CA-7 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun ca7_unArchivioCheNonSiApreDiceIlSuoMotivo() = runTest {
    val expected = mapOf(
      FacesUnavailable.KEYSTORE to FacesWait.KEYSTORE,
      FacesUnavailable.NEWER_DATABASE to FacesWait.NEEDS_UPDATE,
      FacesUnavailable.STORAGE to FacesWait.STORAGE,
    )
    for ((reason, wait) in expected) {
      val lib = Library()
      lib.phone.restart(lib.config, lib.phone.parts().copy(stores = FacesStoreSource { ComputeStoreOpen.Unavailable(reason) }))

      val result = lib.phone.engine.run(lib.phone.area(AreaKind.PRIVATE), forever)

      assertThat(result.stop).isEqualTo(FacesStop.STORE_UNAVAILABLE)
      assertWithMessage("$reason: lo stato dice il motivo vero").that(lib.phone.engine.status.value).isEqualTo(FacesStatus.Waiting(wait))
      assertThat(lib.phone.engine.counters.storeUnavailable.get()).isEqualTo(1)
      assertThat(lib.phone.signals).contains(FacesDiag.FACES_STORE_UNAVAILABLE to 1)
      // Niente e' stato analizzato e niente e' stato cancellato.
      assertThat(lib.phone.ticking.detects).isEqualTo(0)
    }
    // Un Keystore che non risponde non e' "poco spazio libero sul telefono".
    assertThat(FacesWait.KEYSTORE).isNotEqualTo(FacesWait.STORAGE)
  }

  @Test
  fun ca7_ilCodiceDellaDiagnosticaEUnoSoloEStaNelContratto() {
    assertThat(FacesDiag.FACES_STORE_UNAVAILABLE).isIn(FacesDiag.ALL)
  }

  // ---- La stima (CA-2): la libreria contata e i ritmi di partenza ---------------------------------------------------------------------

  @Test
  fun ca2_ilContoDiceQuantoEFattoEQuantoResta() = runTest {
    val lib = Library()
    val backlog = FacesBacklog(lib.phone.switch, lib.phone.requirements, lib.phone.catalog, { lib.phone.rawArchive })
    assertThat(backlog.tally()).isEqualTo(FacesTally(done = 0, work = FacesWork(photos = 7, videos = 0)))

    lib.phone.control.onAsk = { n -> if (n == 2) lib.phone.control.permit = FacesPermit.Stop(FacesStop.RULES) }
    val analyzed = lib.phone.engine.runAll(forever).single().second.analyzed

    val tally = backlog.tally()
    assertThat(tally.done).isEqualTo(analyzed.toLong())
    assertThat(tally.work.total).isEqualTo(7L - analyzed)
    assertThat(tally.total).isEqualTo(7L)
    assertThat(backlog.count()).isEqualTo(tally.work)
  }

  @Test
  fun ca2_iRitmiDiPartenzaSonoLaMisuraDelCampoDellS25() {
    val rates = FacesRates.DEFAULT
    // 8,8 foto al secondo (F104 del 27/10): 12.000 foto, circa mezz'ora e piu' con la seconda passata sul 20% (37 minuti), non i 14 minuti dell'ipotesi di 25 al secondo.
    assertThat(1.0 / rates.secondsPerPhoto).isWithin(1e-9).of(8.8)
    assertThat(FacesEstimate.minutes(FacesWork(photos = 12_000, videos = 0), rates)).isEqualTo(37)
    assertThat(FacesRates.forPhotosPerSecond(8.8)).isEqualTo(rates)
    // Un video e' la copertina e i suoi fotogrammi (D18): quattro foto.
    assertThat(rates.secondsPerVideo).isWithin(1e-9).of(4 * rates.secondsPerPhoto)
    // Un dispositivo piu' lento ha una stima piu' lunga (e il numero non puo' essere zero o negativo).
    assertThat(FacesEstimate.minutes(FacesWork(1_000, 0), FacesRates.forPhotosPerSecond(4.4)))
      .isGreaterThan(FacesEstimate.minutes(FacesWork(1_000, 0), rates))
    assertThat(runCatching { FacesRates.forPhotosPerSecond(0.0) }.isFailure).isTrue()
  }
}
