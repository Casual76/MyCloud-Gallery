package dev.pampa.mycloudgallery.feature.diagnostics.release

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La simulazione del passo (P10, §2.10): le quattro aree in un giro solo, i totali, lo spazio dopo il passo, le cose che fermano il passo (E-SV1).
 * Tempo virtuale (`runTest`): nessuna attesa vera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepSimulationTest {

  private class Sources(
    val forecasts: Map<StepArea, () -> StepForecast>,
    val spaceSource: () -> StepSpace? = { StepSpace(1L shl 40) },
  ) : StepSources {
    val asked = mutableListOf<StepArea>()
    var spaceAsked = 0

    override suspend fun forecast(area: StepArea): StepForecast {
      asked += area
      return forecasts.getValue(area)()
    }

    override suspend fun space(): StepSpace? {
      spaceAsked++
      return spaceSource()
    }
  }

  private fun writes(area: StepArea, files: Long, bytes: Long, certainty: StepCertainty = StepCertainty.EXACT, folder: StepFolder = StepFolder.BACKUP) =
    StepForecast.Writes(area, certainty, listOf(StepWrite(StepPlace.PRIVATE_AREA, folder, files, bytes)))

  private fun normal(): Map<StepArea, () -> StepForecast> = mapOf(
    StepArea.INDEX to { writes(StepArea.INDEX, 10, 300L shl 20, StepCertainty.ESTIMATE, StepFolder.APP_FOLDER) },
    StepArea.BACKUP to { writes(StepArea.BACKUP, 1200, 12L shl 30) },
    StepArea.FACES to { StepForecast.Idle(StepArea.FACES, StepIdle.FEATURE_OFF) },
    StepArea.ALBUMS to { StepForecast.Idle(StepArea.ALBUMS, StepIdle.ON_USER_ACTION) },
  )

  @Test
  fun leQuattroAreeInUnGiroSoloNelOrdineFisso() = runTest {
    val sources = Sources(normal())
    val report = StepSimulation(sources).run()
    assertThat(sources.asked).containsExactly(StepArea.INDEX, StepArea.BACKUP, StepArea.FACES, StepArea.ALBUMS).inOrder()
    assertThat(report.forecasts.map { it.area }).containsExactly(StepArea.INDEX, StepArea.BACKUP, StepArea.FACES, StepArea.ALBUMS).inOrder()
    assertThat(sources.spaceAsked).isEqualTo(1)
  }

  @Test
  fun iTotaliSommanoSoloLeScritture() = runTest {
    val report = StepSimulation(Sources(normal())).run()
    assertThat(report.totalFiles).isEqualTo(1210L)
    assertThat(report.totalBytes).isEqualTo((300L shl 20) + (12L shl 30))
  }

  @Test
  fun loSpazioDopoIlPassoEQuelloDiOggiMenoTuttiIByte() = runTest {
    val free = 100L shl 30
    val report = StepSimulation(Sources(normal(), spaceSource = { StepSpace(free) })).run()
    assertThat(report.freeAfterBytes).isEqualTo(free - report.totalBytes)
    assertThat(report.blocked).isFalse()
    assertThat(report.attention).containsExactly(StepAttention.ESTIMATES_PRESENT)
  }

  @Test
  fun sottoIlMargineDiUnGigaIlPassoNonParte() = runTest {
    val total = (300L shl 20) + (12L shl 30)
    val report = StepSimulation(Sources(normal(), spaceSource = { StepSpace(total + StepLimits.MIN_FREE_AFTER_BYTES - 1) })).run()
    assertThat(report.attention).contains(StepAttention.SPACE_BELOW_MARGIN)
    assertThat(report.blocked).isTrue()
    val exact = StepSimulation(Sources(normal(), spaceSource = { StepSpace(total + StepLimits.MIN_FREE_AFTER_BYTES) })).run()
    assertThat(exact.attention).doesNotContain(StepAttention.SPACE_BELOW_MARGIN)
  }

  @Test
  fun unGuastoDiUnAreaLaSegnaENonFermaLeAltre() = runTest {
    val forecasts = normal().toMutableMap()
    forecasts[StepArea.BACKUP] = { error("la lettura del piano e' caduta") }
    val report = StepSimulation(Sources(forecasts)).run()
    assertThat(report.forecasts[1]).isEqualTo(StepForecast.Unavailable(StepArea.BACKUP, StepUnavailable.FAILED))
    assertThat(report.forecasts[0]).isInstanceOf(StepForecast.Writes::class.java)
    assertThat(report.attention).contains(StepAttention.AREA_UNAVAILABLE)
    assertThat(report.blocked).isTrue()
    assertThat(report.totalFiles).isEqualTo(10L)
  }

  @Test
  fun unAreaNonDisponibileBloccaIlPasso() = runTest {
    val forecasts = normal().toMutableMap()
    forecasts[StepArea.FACES] = { StepForecast.Unavailable(StepArea.FACES, StepUnavailable.NOT_READY) }
    val report = StepSimulation(Sources(forecasts)).run()
    assertThat(report.attention).contains(StepAttention.AREA_UNAVAILABLE)
    assertThat(report.blocked).isTrue()
  }

  @Test
  fun unaRispostaPerUnAltraAreaNonSiFidaESegnaLaLettura() = runTest {
    val forecasts = normal().toMutableMap()
    forecasts[StepArea.INDEX] = { writes(StepArea.BACKUP, 1, 1) }
    val report = StepSimulation(Sources(forecasts)).run()
    assertThat(report.forecasts[0]).isEqualTo(StepForecast.Unavailable(StepArea.INDEX, StepUnavailable.FAILED))
  }

  @Test
  fun loSpazioNonLettoBloccaIlPasso() = runTest {
    val unread = StepSimulation(Sources(normal(), spaceSource = { null })).run()
    assertThat(unread.space).isNull()
    assertThat(unread.freeAfterBytes).isNull()
    assertThat(unread.attention).contains(StepAttention.SPACE_UNKNOWN)
    assertThat(unread.blocked).isTrue()
    val failing = StepSimulation(Sources(normal(), spaceSource = { error("il NAS non risponde") })).run()
    assertThat(failing.attention).contains(StepAttention.SPACE_UNKNOWN)
  }

  @Test
  fun leStimeInformanoMaNonFermano() = runTest {
    val report = StepSimulation(Sources(normal())).run()
    assertThat(StepAttention.ESTIMATES_PRESENT.blocking).isFalse()
    assertThat(report.attention).contains(StepAttention.ESTIMATES_PRESENT)
    assertThat(report.blocked).isFalse()
  }

  @Test
  fun laCancellazioneDelLavoroPassaESiFermaIlGiro() = runTest {
    val forecasts = normal().toMutableMap()
    forecasts[StepArea.BACKUP] = { throw CancellationException("lavoro annullato") }
    val sources = Sources(forecasts)
    val thrown = runCatching { StepSimulation(sources).run() }.exceptionOrNull()
    assertThat(thrown).isInstanceOf(CancellationException::class.java)
    assertThat(sources.asked).containsExactly(StepArea.INDEX, StepArea.BACKUP).inOrder()
    assertThat(sources.spaceAsked).isEqualTo(0)
  }

  @Test
  fun ilModelloFaUnGiroAllaVoltaETornaAFermoSeAnnullato() = runTest {
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    val sources = object : StepSources {
      override suspend fun forecast(area: StepArea): StepForecast {
        if (area == StepArea.BACKUP) gate.await()
        return StepForecast.Idle(area, StepIdle.NOTHING_TO_DO)
      }

      override suspend fun space(): StepSpace? = StepSpace(10L shl 30)
    }
    val model = StepSimulationModel(StepSimulation(sources))
    assertThat(model.state.value).isEqualTo(StepSimulationState.Idle)
    val first = async { model.run() }
    testScheduler.runCurrent()
    assertThat(model.state.value).isEqualTo(StepSimulationState.Running)
    model.run()
    assertThat(model.state.value).isEqualTo(StepSimulationState.Running)
    first.cancel()
    testScheduler.runCurrent()
    assertThat(model.state.value).isEqualTo(StepSimulationState.Idle)
    gate.complete(Unit)
    model.run()
    assertThat(model.state.value).isInstanceOf(StepSimulationState.Done::class.java)
  }
}
