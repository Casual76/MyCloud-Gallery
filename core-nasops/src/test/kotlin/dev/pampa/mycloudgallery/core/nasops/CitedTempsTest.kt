package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.clean.OwnFilesCleaner
import dev.pampa.mycloudgallery.core.nasops.contract.CitedTemps
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 5A, D33 (E-B21): un temporaneo del backup `<io>-bk-<id>` fermo da giorni (un video a meta', telefono fuori casa) e citato dal
 * diario del backup **sopravvive** alla pulizia dei propri file; uno non citato da nessun diario e vecchio si toglie come prima; se il
 * diario del backup non risponde, in quel giro non si toglie nessun temporaneo.
 */
class CitedTempsTest {

  private val kit = NasOpsTestKit()
  private val me = kit.deviceId
  private val day = 24L * 60 * 60 * 1000
  private val now get() = kit.clocks.nasNow!!
  private val share = NasOpsTestKit.PRIVATE_SHARE

  private fun temp(name: String): SharePath = NasLayout.ROOT.child("tmp").child("$me-$name")

  private fun cleaner(cited: CitedTemps) = OwnFilesCleaner(
    deviceId = me,
    accountId = "test",
    fileOps = kit.fileOps,
    reader = kit.nas.reader,
    store = kit.store,
    clocks = kit.clocks,
    gate = kit.gate,
    lock = NasOpsLock(),
    registry = { null },
    otherCited = cited,
  )

  private fun seed(): Triple<SharePath, SharePath, SharePath> {
    val citedVideo = temp("bk-17") // video a meta' da tre giorni, citato dal diario del backup
    val forgotten = temp("bk-18") // del backup ma non piu' citato (elemento chiuso): si toglie come ogni temporaneo vecchio
    val recent = temp("bk-19") // recente: resta comunque (meno di un giorno)
    kit.nas.putFile(share, citedVideo.value, dev.pampa.mycloudgallery.core.nas.testing.NasContent.of(ByteArray(4096) { 1 }), modifiedAt = now - 3 * day)
    kit.nas.putFile(share, forgotten.value, dev.pampa.mycloudgallery.core.nas.testing.NasContent.of(ByteArray(10) { 2 }), modifiedAt = now - 3 * day)
    kit.nas.putFile(share, recent.value, dev.pampa.mycloudgallery.core.nas.testing.NasContent.of(ByteArray(10) { 3 }), modifiedAt = now - 60_000)
    return Triple(citedVideo, forgotten, recent)
  }

  @Test
  fun unTemporaneoDelBackupCitatoDalSuoDiarioSopravviveAllaPulizia() = runTest {
    val (citedVideo, forgotten, recent) = seed()
    val report = cleaner { setOf(citedVideo) }.clean(listOf(share), 5.minutes)
    assertThat(kit.nas.exists(share, citedVideo.value)).isTrue()
    assertThat(kit.nas.bytes(share, citedVideo.value)!!.size).isEqualTo(4096)
    assertThat(kit.nas.exists(share, recent.value)).isTrue()
    // Il controllo che il test non passi per caso: lo stesso giro toglie davvero il temporaneo vecchio non citato.
    assertThat(kit.nas.exists(share, forgotten.value)).isFalse()
    assertThat(report.removed).isEqualTo(1)
  }

  @Test
  fun senzaLaPortaIlTemporaneoDelBackupSarebbeTolto() = runTest {
    val (citedVideo, _, _) = seed()
    cleaner(CitedTemps.NONE).clean(listOf(share), 5.minutes)
    // E' esattamente il difetto che D33 chiude: senza le citazioni del backup il video a meta' sparirebbe dopo un giorno.
    assertThat(kit.nas.exists(share, citedVideo.value)).isFalse()
  }

  @Test
  fun seIlDiarioDelBackupNonRispondeNessunTemporaneoSiToglie() = runTest {
    val (citedVideo, forgotten, recent) = seed()
    val report = cleaner { error("diario del backup non leggibile") }.clean(listOf(share), 5.minutes)
    assertThat(kit.nas.exists(share, citedVideo.value)).isTrue()
    assertThat(kit.nas.exists(share, forgotten.value)).isTrue()
    assertThat(kit.nas.exists(share, recent.value)).isTrue()
    assertThat(report.removed).isEqualTo(0)
  }
}
