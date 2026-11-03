package dev.pampa.mycloudgallery.release

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.state.NasFirstReleaseClock
import dev.pampa.mycloudgallery.core.release.state.ReleaseStates
import dev.pampa.mycloudgallery.core.release.testing.FakeManifestSource
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.NextStage
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.YieldReason
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R2 della revisione di sicurezza della Fase 10 (D29: "al giro di sincronizzazione gia' esistente e all'avvio se l'ultima lettura ha piu' di 24 ore"). Prima il manifest lo
 * leggeva solo una tappa della sincronizzazione dell'indice **finita**: una sincronizzazione che non finiva mai (o che lanciava a ogni giro) teneva lontano il blocco.
 * Qui, con lo stato vero di core-release e un manifest `WRITES` firmato nel test (mai K1, K2 o KT vere): una tappa lasciata a meta' lo legge, una tappa che le regole hanno
 * fermato no (la sessione non si e' usata), l'apertura dell'app lo legge con il ritmo di 24 ore e solo con la famiglia pronta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManifestReadsTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val signer = ReleaseTestSigner()
  private val version = AppVersion(VERSION_CODE, "1.0.0", ReleaseChannel.STABLE)
  private var wallNow = 1_800_000_000_000L
  private val nasNow = Instant.ofEpochMilli(1_800_000_000_000L)
  private val stats = StageStats(batches = 1, bytes = 0, roundBytes = 0, elapsed = kotlin.time.Duration.ZERO, resumed = false)

  private fun TestScope.release(): ReleaseGraph {
    val dir = temp.newFolder()
    val state = ReleaseStates.create(ReleaseStates.fileStore(dir), version, NasFirstReleaseClock({ nasNow }) { nasNow }, signer.trust())
    return testReleaseGraph(dir, state, StandardTestDispatcher(testScheduler)) { wallNow }
  }

  private fun writesManifest(): ManifestRead = ManifestRead.Found(
    signer.seal(
      ReleaseManifest(
        schema = ManifestLimits.SCHEMA,
        app = ManifestLimits.APP_ID,
        seq = 1,
        issued = nasNow.minusSeconds(60),
        expires = nasNow.plus(Duration.ofDays(30)),
        minVersionCode = null,
        blocks = listOf(VersionBlock(1, VERSION_CODE + 10, BlockLevel.WRITES, ReleaseMessage.SECURITY)),
        models = emptyList(),
      ),
      ReleaseKeyIds.K1,
    ),
  )

  @Test
  fun r2_unaTappaLasciataAMetaPerTempoLeggeIlManifest() = runTest {
    val release = release()
    release.load()
    val source = FakeManifestSource(writesManifest())
    val reads = ManifestReads(release) { source }

    val yielded = StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.WHEN_ALLOWED, stats)
    assertThat(reads.afterIndexSync(yielded)).isEqualTo(OfferResult.APPLIED)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
  }

  @Test
  fun r2_unaTappaFermataONonPartitaPerLeRegoleNonLeggeNiente() = runTest {
    val release = release()
    release.load()
    val source = FakeManifestSource(writesManifest())
    val reads = ManifestReads(release) { source }

    assertThat(reads.afterIndexSync(StageOutcome.Stopped(WaitReason.Offline, emptyList(), stats))).isNull()
    assertThat(reads.afterIndexSync(StageOutcome.Waiting(WaitReason.Offline, emptyList(), stats))).isNull()
    assertThat(source.reads).isEqualTo(0)
    // Il controllo che la prova non passi a vuoto: una tappa finita legge.
    assertThat(reads.afterIndexSync(StageOutcome.Finished(stats))).isEqualTo(OfferResult.APPLIED)
  }

  @Test
  fun r2_allAperturaSiLeggeSenzaIlGiroDellIndice_alPiuUnaVoltaOgni24Ore() = runTest {
    val release = release()
    release.load()
    val source = FakeManifestSource(writesManifest())
    val reads = ManifestReads(release) { source }

    // La famiglia non e' pronta (o l'area comune non e' nota): niente, e l'ora della lettura non si segna.
    assertThat(reads.atAppStart(ready = false)).isNull()
    assertThat(source.reads).isEqualTo(0)
    // Nessuna lettura prima: si legge subito, anche se nessun giro dell'indice e' mai finito.
    assertThat(reads.atAppStart(ready = true)).isEqualTo(OfferResult.APPLIED)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
    // Un'ora dopo (un altro ritorno in primo piano): non si rilegge.
    wallNow += 3_600_000L
    assertThat(reads.atAppStart(ready = true)).isNull()
    assertThat(source.reads).isEqualTo(1)
    // Passate le 24 ore: si rilegge (lo stesso manifest: niente cambia).
    wallNow += 24 * 3_600_000L
    reads.atAppStart(ready = true)
    assertThat(source.reads).isEqualTo(2)
  }

  private companion object {
    const val VERSION_CODE = 7
  }
}
