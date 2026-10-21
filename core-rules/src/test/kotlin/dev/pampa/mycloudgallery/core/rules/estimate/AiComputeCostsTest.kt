package dev.pampa.mycloudgallery.core.rules.estimate

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fase 6 (P5, §2.8): le misure del calcolo AI e il lavoro di `AI_COMPUTE` dal backlog vero ([AiWork], [AiComputeCosts]). */
class AiComputeCostsTest {
  private val m = DeviceMeasures.PHASE_0

  @Test
  fun leMisureDelCalcoloHannoIValoriDelProgetto() {
    assertThat(m.aiItemsPerSecond).isEqualTo(2.8)
    assertThat(m.ocrSecondsPerItem).isEqualTo(0.2)
    assertThat(m.framesPerVideo).isEqualTo(4.0)
    assertThat(m.modelBytes).isEqualTo(370L * 1024 * 1024)
    assertThat(m.analysisPreviewBytes).isEqualTo(150_000L)
    assertThat(runCatching { DeviceMeasures(framesPerVideo = 0.5) }.isFailure).isTrue()
    assertThat(runCatching { DeviceMeasures(ocrDenseShare = 1.5) }.isFailure).isTrue()
  }

  @Test
  fun ilTempoEIlMaggioreFraVettoriEOcr() {
    // Vettori: (100 + 10 x 4) / 2,8 = 50 s; OCR: 110 x 0,2 x 1,15 = 25,3 s, in parallelo.
    val work = AiWork(photos = 100, videos = 10, textReads = 110)
    assertThat(AiComputeCosts.seconds(work, m)).isWithin(1e-9).of(50.0)
    // Con l'OCR lento domina l'OCR.
    val slowOcr = m.copy(ocrSecondsPerItem = 1.0)
    assertThat(AiComputeCosts.seconds(work, slowOcr)).isWithin(1e-9).of(110 * 1.0 * 1.15)
    assertThat(AiComputeCosts.seconds(AiWork.NONE, m)).isEqualTo(0.0)
  }

  @Test
  fun ilTrafficoContaSoloLeAnteprimeNonAncoraNelBuffer() {
    val costs = NasCosts.DOC_2_10
    val all = AiComputeCosts.bytes(AiWork(photos = 10, videos = 2), costs)
    assertThat(all).isEqualTo(10 * costs.analysisBytes + 2 * costs.videoFramesBytes)
    assertThat(AiComputeCosts.bytes(AiWork(photos = 10, videos = 2, buffered = 12), costs)).isEqualTo(0)
    val withText = AiComputeCosts.bytes(AiWork(photos = 10, textReads = 10), costs, denseShare = 0.5)
    assertThat(withText).isEqualTo(10 * costs.analysisBytes + 5 * costs.hqBytes)
  }

  @Test
  fun lArretratoVeroDiventaIlLavoroDiAiCompute() = runTest {
    val work = AiWork(photos = 18_000, videos = 0)
    val facts = BacklogFacts(toAnalyze = 999, analyze = work, monthly = MonthlyArrivals(toAnalyze = 5, analyze = AiWork(photos = 200, videos = 10)))
    val backlog = FactsBacklogSource({ facts }, { ReplicaDepth.STANDARD }).current()
    val pending = backlog.pending(BackgroundActivity.AI_COMPUTE)
    // Lo stimatore divide per la stessa velocita': ritrova i secondi del calcolo.
    assertThat(pending.items / m.aiItemsPerSecond).isWithin(1.0).of(AiComputeCosts.seconds(work, m))
    assertThat(pending.bytes).isEqualTo(18_000 * NasCosts.DOC_2_10.analysisBytes)
    assertThat(backlog.monthlyNew(BackgroundActivity.AI_COMPUTE).items).isIn(240L..241L) // 200 foto + 10 video x 4 fotogrammi
    // Senza `analyze` vale il conteggio di prima.
    val old = BacklogFacts(toAnalyze = 999).toLibraryBacklog(ReplicaDepth.STANDARD).pending(BackgroundActivity.AI_COMPUTE)
    assertThat(old.items).isEqualTo(999)

    // "Analizzare 18.000 media richiede circa ... notti" (D15): 18.000 / 2,8 s = 1,79 h → una notte; con 0,5 foto/s due notti.
    assertThat(AiComputeCosts.nights(work, m)).isEqualTo(1)
    assertThat(AiComputeCosts.nights(work, m.copy(aiItemsPerSecond = 0.5))).isEqualTo(2)
    assertThat(AiComputeCosts.nights(AiWork.NONE, m)).isEqualTo(0)
    assertThat(AiComputeCosts.batteryPercent(work, m)).isWithin(1e-9).of(72.0)

    // Lo stimatore intero: l'arretrato del calcolo finisce e il calcolo non dipende dalla rete.
    val estimate = DefaultConsumptionEstimator().estimate(PresetCatalog.ruleSet(Preset.NIGHT), backlog, m)
    assertThat(estimate.perActivity.getValue(BackgroundActivity.AI_COMPUTE).daysToFinish).isNotNull()
  }

  @Test
  fun conteggiNegativiRifiutati() {
    assertThat(runCatching { AiWork(photos = -1) }.isFailure).isTrue()
  }
}
