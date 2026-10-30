package dev.pampa.mycloudgallery.core.map.points

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointCounts
import dev.pampa.mycloudgallery.core.map.contract.MapPointSource
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.testing.FakeMapPointSource
import dev.pampa.mycloudgallery.core.map.testing.SyntheticMapPoints
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 9 (P3; D27, E-MAP5, E-MAP15; `docs/fase9-progetto.md` §2.10 "Aggiornamento"): il servizio dei punti. Niente all'avvio, l'indice alla prima
 * `OPEN`, a caldo niente di nuovo, la calma di 2 s, lo scambio atomico, l'annullamento, l'errore, i conteggi. Tempo **virtuale** (`runTest`):
 * nessuna attesa vera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeoMapPointsTest {

  private val world = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)

  /** Una sorgente che conta quante letture sono partite e che il test puo' fermare o far fallire. */
  private class ScriptedSource(var points: List<MapPoint>, var withoutPosition: Int = 0, var invalid: Int = 0) : MapPointSource {
    var reads = 0
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null

    override fun pages(): Flow<List<MapPoint>> = flow {
      reads++
      gate?.await()
      failure?.let { throw it }
      for (chunk in points.chunked(MapPoints.PAGE_SIZE)) emit(chunk)
    }

    override suspend fun counts(): MapPointCounts = MapPointCounts(withoutPosition, invalid)
  }

  private fun TestScope.service(source: MapPointSource, quiet: Long = MapPoints.REBUILD_QUIET_MS) =
    GeoMapPoints(source, StandardTestDispatcher(testScheduler), quiet)

  private fun pts(count: Int, seed: Long = 1): List<MapPoint> = SyntheticMapPoints.generate(count, seed, withEdges = false)

  @Test
  fun costruireIlServizioNonLeggeNienteEPrimaDellOpenSiCostruisce() = runTest {
    val source = ScriptedSource(pts(50))
    val points = service(source)
    assertThat(points.index.value).isEqualTo(GeoIndexState.Building)
    advanceUntilIdle()
    assertThat(source.reads).isEqualTo(0)
    assertThat(points.index.value).isEqualTo(GeoIndexState.Building)
  }

  @Test
  fun laPrimaOpenFaLIndiceConIConteggi() = runTest {
    val source = ScriptedSource(pts(5_000), withoutPosition = 12, invalid = 3)
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    val ready = points.index.value as GeoIndexState.Ready
    assertThat(ready.index.size).isEqualTo(5_000)
    assertThat(ready.withoutPosition).isEqualTo(12)
    assertThat(ready.invalid).isEqualTo(3)
    assertThat(ready.index.clusters(world, 4.0, 72).sumOf { it.count }).isEqualTo(5_000)
    assertThat(ready.toString()).doesNotContain(UUID(0, 0).toString())
  }

  /** I punti che non entrano e arrivano lo stesso dalla sorgente si scartano e si sommano ai suoi scarti (E-MAP7). */
  @Test
  fun lePosizioniAssurdeCheLaSorgenteLasciaPassareSiContano() = runTest {
    val base = pts(100, 7)
    val absurd = MapPoint(MediaId(UUID(5, 5).toString()), 0, 0, 20250101, 0)
    val source = ScriptedSource(base + absurd, invalid = 2)
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    val ready = points.index.value as GeoIndexState.Ready
    assertThat(ready.index.size).isEqualTo(100)
    assertThat(ready.invalid).isEqualTo(3)
  }

  /** E-MAP5: nessuna foto con la posizione = `Empty` con quante ne mancano. */
  @Test
  fun senzaPuntiLoStatoEVuotoConIMediaSenzaPosizione() = runTest {
    val points = service(ScriptedSource(emptyList(), withoutPosition = 1_240))
    points.rebuild(RebuildReason.OPEN)
    assertThat(points.index.value).isEqualTo(GeoIndexState.Empty(1_240))
  }

  @Test
  fun aCaldoUnaSecondaOpenNonRileggeNiente() = runTest {
    val source = ScriptedSource(pts(300))
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    val first = points.index.value
    points.rebuild(RebuildReason.OPEN)
    points.rebuild(RebuildReason.OPEN)
    assertThat(source.reads).isEqualTo(1)
    assertThat(points.index.value).isSameInstanceAs(first)
  }

  @Test
  fun unCambioDiIstantaneaAspettaDueSecondiDiCalma() = runTest {
    val source = ScriptedSource(pts(300))
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    val before = points.index.value
    source.points = pts(400, 2)
    val job = launch { points.rebuild(RebuildReason.SNAPSHOT_CHANGED) }
    advanceTimeBy(MapPoints.REBUILD_QUIET_MS - 1)
    runCurrent()
    assertThat(source.reads).isEqualTo(1)
    assertThat(points.index.value).isSameInstanceAs(before)
    advanceTimeBy(1)
    job.join()
    assertThat(source.reads).isEqualTo(2)
    assertThat((points.index.value as GeoIndexState.Ready).index.size).isEqualTo(400)
  }

  /** Una raffica di cambi (ognuno dentro la calma del precedente) fa **una** ricostruzione, dopo l'ultimo. */
  @Test
  fun unaRafficaDiCambiFaUnaSolaRicostruzione() = runTest {
    val source = ScriptedSource(pts(300))
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    source.points = pts(500, 3)
    val jobs = List(6) { n ->
      launch {
        delay(500L * n)
        points.rebuild(RebuildReason.SNAPSHOT_CHANGED)
      }
    }
    jobs.forEach { it.join() }
    assertThat(source.reads).isEqualTo(2)
    assertThat((points.index.value as GeoIndexState.Ready).index.size).isEqualTo(500)
    // L'ultima richiesta e' partita a 2,5 s e ha aspettato i suoi 2 s.
    assertThat(testScheduler.currentTime).isEqualTo(2_500L + MapPoints.REBUILD_QUIET_MS)
  }

  /** E-MAP15, D31: un nascosto non aspetta la calma. */
  @Test
  fun unNascostoRicostruisceSubito() = runTest {
    val source = ScriptedSource(pts(300))
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    source.points = source.points.drop(1)
    points.rebuild(RebuildReason.HIDDEN_CHANGED)
    assertThat(testScheduler.currentTime).isEqualTo(0L)
    assertThat((points.index.value as GeoIndexState.Ready).index.size).isEqualTo(299)
    assertThat(source.reads).isEqualTo(2)
  }

  /** Lo scambio e' atomico: durante la ricostruzione chi legge vede ancora l'indice vecchio, intero; dopo, quello nuovo, intero. */
  @Test
  fun quandoSiRicostruisceSiVedeSempreUnIndiceIntero() = runTest {
    val source = ScriptedSource(pts(300))
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    val old = points.index.value as GeoIndexState.Ready
    source.points = pts(700, 4)
    source.gate = CompletableDeferred()
    val seen = ArrayList<GeoIndexState>()
    val watcher = launch { points.index.collect { seen += it } }
    val job = launch { points.rebuild(RebuildReason.HIDDEN_CHANGED) }
    runCurrent()
    // La lettura e' ferma a meta': lo stato e' ancora quello vecchio (non torna a `Building`, non e' parziale).
    assertThat(points.index.value).isSameInstanceAs(old)
    assertThat(old.index.clusters(world, 4.0, 72).sumOf { it.count }).isEqualTo(300)
    source.gate!!.complete(Unit)
    job.join()
    runCurrent()
    watcher.cancelAndJoin()
    val now = points.index.value as GeoIndexState.Ready
    assertThat(now.index.clusters(world, 4.0, 72).sumOf { it.count }).isEqualTo(700)
    assertThat(seen.filterIsInstance<GeoIndexState.Ready>().map { it.index.size }).containsExactly(300, 700).inOrder()
    assertThat(seen.any { it === GeoIndexState.Building }).isFalse()
  }

  @Test
  fun unaRicostruzioneAnnullataNonPubblicaENonSegnaLIndiceAlPasso() = runTest {
    val source = ScriptedSource(pts(300))
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    val old = points.index.value
    source.points = pts(600, 5)
    source.gate = CompletableDeferred()
    val job = launch { points.rebuild(RebuildReason.HIDDEN_CHANGED) }
    runCurrent()
    job.cancelAndJoin()
    assertThat(points.index.value).isSameInstanceAs(old)
    // La prossima apertura lo rifa, perche' il mondo e' cambiato e l'indice non l'ha letto.
    source.gate = null
    points.rebuild(RebuildReason.OPEN)
    assertThat((points.index.value as GeoIndexState.Ready).index.size).isEqualTo(600)
  }

  @Test
  fun unaLetturaCheFallisceLasciaLIndiceCheCeraERiprova() = runTest {
    val source = ScriptedSource(pts(300))
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    val old = points.index.value
    source.failure = IllegalStateException("guasto")
    points.rebuild(RebuildReason.HIDDEN_CHANGED)
    assertThat(points.index.value).isSameInstanceAs(old)
    source.failure = null
    points.rebuild(RebuildReason.OPEN)
    assertThat(points.index.value).isNotSameInstanceAs(old)
    assertThat((points.index.value as GeoIndexState.Ready).index.size).isEqualTo(300)
  }

  @Test
  fun unaPrimaLetturaCheFallisceNonLasciaUnaRotellaEterna() = runTest {
    val source = ScriptedSource(pts(300))
    source.failure = IllegalStateException("guasto")
    val points = service(source)
    points.rebuild(RebuildReason.OPEN)
    assertThat(points.index.value).isEqualTo(GeoIndexState.Empty(0))
    source.failure = null
    points.rebuild(RebuildReason.OPEN)
    assertThat((points.index.value as GeoIndexState.Ready).index.size).isEqualTo(300)
  }

  /** Un media cancellato fra due pagine fa fallire la lettura mentre l'istantanea e' gia' cambiata: niente "nessuna foto", la richiesta che segue ricostruisce. */
  @Test
  fun unaLetturaCheFallisceMentreIlMondoCambiaNonMostraVuotoEIlSeguenteRiesce() = runTest {
    val source = ScriptedSource(pts(300))
    source.gate = CompletableDeferred()
    source.failure = IllegalStateException("cursore perso")
    val points = service(source)
    val first = launch { points.rebuild(RebuildReason.OPEN) }
    runCurrent()
    val change = launch { points.rebuild(RebuildReason.SNAPSHOT_CHANGED) }
    runCurrent()
    source.gate!!.complete(Unit)
    runCurrent()
    assertThat(points.index.value).isEqualTo(GeoIndexState.Building)
    source.failure = null
    source.gate = null
    first.join()
    change.join()
    assertThat((points.index.value as GeoIndexState.Ready).index.size).isEqualTo(300)
    assertThat(source.reads).isEqualTo(2)
  }

  /** Due `OPEN` insieme: una sola lettura (la seconda trova l'indice gia' al passo). */
  @Test
  fun dueOpenInsiemeLeggonoUnaVolta() = runTest {
    val source = ScriptedSource(pts(300))
    source.gate = CompletableDeferred()
    val points = service(source)
    val a = async { points.rebuild(RebuildReason.OPEN) }
    val b = async { points.rebuild(RebuildReason.OPEN) }
    runCurrent()
    source.gate!!.complete(Unit)
    a.await()
    b.await()
    assertThat(source.reads).isEqualTo(1)
  }

  @Test
  fun laSorgenteFintaDiP0AlimentaIlServizio() = runTest {
    val points = service(FakeMapPointSource(SyntheticMapPoints.generate(2_500, seed = 8), withoutPosition = 4))
    points.rebuild(RebuildReason.OPEN)
    val ready = points.index.value as GeoIndexState.Ready
    assertThat(ready.withoutPosition).isEqualTo(4)
    assertThat(ready.invalid).isEqualTo(3)
    assertThat(points.toString()).isEqualTo("GeoMapPoints")
  }
}
