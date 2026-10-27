package dev.pampa.mycloudgallery.utility

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.duplicates.NasTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashFeed
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashRow
import dev.pampa.mycloudgallery.feature.library.trash.testing.fakeDoubleTap
import dev.pampa.mycloudgallery.feature.library.trash.testing.fakeSingleTap
import dev.pampa.mycloudgallery.integration.Attese
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * Fase 7 (P13; P8, D26, Q8): **gli Eliminati di recente del NAS passano da `NasOperations`**. L'adattatore di `:app` ([OperationsNasTrash]) sopra le
 * vere operazioni e il vero cestino del kit di `core-nasops`:
 *  - le voci del cestino arrivano alla pagina come righe (chiave `<proprietario>/<id>`, giorni rimasti, propria = "Elimina subito");
 *  - "Ripristina" con un tocco rimette il file dov'era;
 *  - "Elimina subito" vuole **due** tocchi (con uno non parte niente) e toglie la voce;
 *  - una chiave che non c'e' (o inventata) non fa niente; con le scritture ferme non parte.
 */
class NasTrashOverOperationsTest {

  private val kit = NasOpsTestKit()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val port = OperationsNasTrash(kit.trash, kit.operations, { kit.clocks.monotonicMs() }, scope)

  @After
  fun tearDown() {
    scope.cancel()
  }

  /** Un file nel cestino del NAS come lo mette la pulizia dei Doppioni (l'altra porta di `:app`), con un tocco. */
  private suspend fun trashed(path: String, bytes: ByteArray): NasTrashRow {
    val stamp = kit.putFile(NasOpsTestKit.PRIVATE_SHARE, path, bytes)
    val copy = NasTrashRef(NasOpsTestKit.PRIVATE_SHARE, path, stamp.size, stamp.modifiedAtMillis, stamp.fileId, CopyArea.MY_PRIVATE)
    assertThat(OperationsCleanupNas(kit.operations) { kit.clocks.monotonicMs() }.trash(listOf(copy), fakeSingleTap())).isEqualTo(NasTrashOutcome.STARTED)
    port.refresh()
    return ready().rows.single()
  }

  private suspend fun ready(): NasTrashFeed.Ready = withTimeout(Attese.TETTO) { port.feed.first { it is NasTrashFeed.Ready } as NasTrashFeed.Ready }

  @Test
  fun unaVoceDelCestinoArrivaAllaPaginaComeRiga() = runBlocking<Unit> {
    val row = trashed("Backup/Pixel 9/2026/10/IMG_0001.jpg", ByteArray(1_500) { 1 })

    assertThat(row.key).isEqualTo("${NasOpsTestKit.DEVICE_A}/${row.key.substringAfter('/')}")
    assertThat(row.daysLeft).isEqualTo(30)
    assertThat(row.sizeBytes).isEqualTo(1_500)
    assertThat(row.restorable).isTrue()
    assertThat(row.deletableNow).isTrue()
    assertThat(row.isVideo).isFalse()
    assertThat(ready().writable).isTrue()
  }

  @Test
  fun ripristinaConUnToccoRimetteIlFileDovEra() = runBlocking<Unit> {
    val path = "Backup/Pixel 9/2026/10/IMG_0002.jpg"
    val row = trashed(path, ByteArray(1_200) { 2 })
    assertThat(kit.exists(NasOpsTestKit.PRIVATE_SHARE, path)).isFalse()

    assertThat(port.restore(setOf(row.key), fakeSingleTap())).isEqualTo(NasTrashOutcome.STARTED)

    assertThat(kit.exists(NasOpsTestKit.PRIVATE_SHARE, path)).isTrue()
    port.refresh()
    assertThat(ready().rows).isEmpty()
  }

  @Test
  fun eliminaSubitoVuoleDueTocchi() = runBlocking<Unit> {
    val row = trashed("Backup/Pixel 9/2026/10/IMG_0003.jpg", ByteArray(900) { 3 })

    assertThat(port.purgeNow(setOf(row.key), fakeSingleTap())).isEqualTo(NasTrashOutcome.REJECTED)
    port.refresh()
    assertThat(ready().rows).hasSize(1)

    assertThat(port.purgeNow(setOf(row.key), fakeDoubleTap())).isEqualTo(NasTrashOutcome.STARTED)
    port.refresh()
    assertThat(ready().rows).isEmpty()
  }

  @Test
  fun unaChiaveCheNonCeNonFaNiente() = runBlocking<Unit> {
    val row = trashed("Backup/Pixel 9/2026/10/IMG_0004.jpg", ByteArray(800) { 4 })

    assertThat(port.restore(setOf("${NasOpsTestKit.DEVICE_B}/inventata"), fakeSingleTap())).isEqualTo(NasTrashOutcome.REJECTED)
    assertThat(port.purgeNow(setOf("${NasOpsTestKit.DEVICE_B}/inventata"), fakeDoubleTap())).isEqualTo(NasTrashOutcome.REJECTED)
    assertThat(port.restore(emptySet(), fakeSingleTap())).isEqualTo(NasTrashOutcome.REJECTED)
    port.refresh()
    assertThat(ready().rows.map { it.key }).containsExactly(row.key)
  }

  @Test
  fun conLeScrittureFermeIlRipristinoNonParte() = runBlocking<Unit> {
    val path = "Backup/Pixel 9/2026/10/IMG_0005.jpg"
    val row = trashed(path, ByteArray(700) { 5 })
    kit.gate.byShare[NasOpsTestKit.PRIVATE_SHARE] = GateStatus.FROZEN

    assertThat(port.restore(setOf(row.key), fakeSingleTap())).isIn(listOf(NasTrashOutcome.BLOCKED, NasTrashOutcome.REJECTED))
    assertThat(kit.exists(NasOpsTestKit.PRIVATE_SHARE, path)).isFalse()
  }

  @Test
  fun iVideoSiRiconosconoDalNome() {
    assertThat(OperationsNasTrash.isVideoName("VID_0001.MP4")).isTrue()
    assertThat(OperationsNasTrash.isVideoName("clip.mov")).isTrue()
    assertThat(OperationsNasTrash.isVideoName("IMG_0001.jpg")).isFalse()
    assertThat(OperationsNasTrash.isVideoName(null)).isFalse()
  }
}
