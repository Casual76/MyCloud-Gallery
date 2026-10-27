package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.BufferKeepers
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 8, D17 (P4): il buffer di analisi non butta un'anteprima che serve ancora ai volti. `ComputeSession` chiede a [BufferKeepers] quali delle
 * chiavi che sta per togliere (inizio tappa: `pruneFinished`; fine unita'; fine tappa del telefono) servono ancora, e quelle resta. Di serie
 * nessuno tiene niente: il comportamento della Fase 6 (le altre prove di questa cartella lo provano).
 */
class BufferKeepersTest {
  @get:Rule val tmp = TemporaryFolder()

  private val forever = Duration.INFINITE
  private val common = TestFamily.COMMON

  private fun diskBuffer(free: Long = 8L * 1024 * 1024 * 1024) = DiskAiBuffer(tmp.newFolder(), freeSpace = { free })

  private fun device(f: TestFamily, disk: DiskAiBuffer) = SimDevice(f.nas, "telefono-anna", "anna", listOf(TestFamily.ANNA_PRIVATE, common), buffer = disk)

  /** Chi tiene le chiavi di [wanted] e conta quante volte e' stato interrogato. */
  private class Keeper(var wanted: Set<ContentKey>) : BufferKeepers {
    var asked = 0
    val askedAbout = ArrayList<Collection<ContentKey>>()

    override suspend fun stillWanted(keys: Collection<ContentKey>): Set<ContentKey> {
      asked++
      askedAbout += keys.toList()
      return keys.filterTo(HashSet()) { it in wanted }
    }
  }

  private suspend fun deliver(disk: DiskAiBuffer, keys: List<ContentKey>) {
    keys.forEach { disk.put(it, SegmentClass.ANALYSIS, "FAKE:volti".toByteArray()) }
  }

  /** Fine unita': le anteprime che i volti chiedono ancora restano nel buffer, le altre si tolgono (Fase 6). */
  @Test
  fun fineUnitaLeAnteprimeChiesteRestano() = runTest {
    val f = TestFamily()
    val keys = (1..5).map { f.nas.add(common, preview = false, concepts = "gita $it") }
    val disk = diskBuffer()
    deliver(disk, keys)
    val phone = device(f, disk)
    val keeper = Keeper(setOf(keys[1], keys[3]))
    phone.keepers = keeper
    phone.restart()

    val r = phone.engine.run(AiScope.Share(common), forever)

    assertThat(r.computed).isEqualTo(5)
    assertThat(disk.keys().toSet()).containsExactly(keys[1], keys[3])
    assertThat(keeper.asked).isAtLeast(1)
  }

  /** Senza chi tiene (di serie) il buffer si svuota come prima: nessuna prova della Fase 6 cambia. */
  @Test
  fun diSerieNessunoTieneNiente() = runTest {
    val f = TestFamily()
    val keys = (1..4).map { f.nas.add(common, preview = false, concepts = "mare $it") }
    val disk = diskBuffer()
    deliver(disk, keys)
    val phone = device(f, disk)

    phone.engine.run(AiScope.Share(common), forever)

    assertThat(disk.keys()).isEmpty()
    assertThat(disk.usedBytes.value).isEqualTo(0)
  }

  /** `pruneFinished` a inizio tappa: cio' che i volti chiedono ancora non si butta; quando smettono di chiederlo la tappa dopo lo toglie. */
  @Test
  fun inizioTappaPruneFinishedRispettaChiTiene() = runTest {
    val f = TestFamily()
    val done = (1..3).map { f.nas.add(common, preview = false, concepts = "neve $it") }
    val disk = diskBuffer()
    val phone = device(f, disk)
    // La prima tappa calcola i tre media dalle anteprime del buffer e lo svuota (di serie nessuno tiene): da qui in poi i risultati ci sono.
    deliver(disk, done)
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(disk.keys()).isEmpty()

    // Anteprime di media gia' calcolati che tornano nel buffer (es. riscaricate per i volti): la tappa dopo le butta, tranne quelle tenute.
    val keeper = Keeper(setOf(done[0]))
    phone.keepers = keeper
    phone.restart()
    deliver(disk, done)
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(disk.keys().toSet()).containsExactly(done[0])

    // I volti hanno finito: la tappa dopo (a inizio tappa) libera anche l'ultima.
    keeper.wanted = emptySet()
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(disk.keys()).isEmpty()
    assertThat(disk.usedBytes.value).isEqualTo(0)
  }

  /** Il solo-telefono (fine tappa) rispetta chi tiene allo stesso modo. */
  @Test
  fun mediaDelSoloTelefonoRispettanoChiTiene() = runTest {
    val f = TestFamily()
    val disk = diskBuffer()
    val phone = device(f, disk)
    val a = f.nas.newKey()
    val b = f.nas.newKey()
    phone.phoneMedia[a] = dev.pampa.mycloudgallery.core.model.MediaKind.PHOTO to "gatto"
    phone.phoneMedia[b] = dev.pampa.mycloudgallery.core.model.MediaKind.PHOTO to "cane"
    phone.keepers = Keeper(setOf(b))
    phone.restart()

    val r = phone.engine.run(AiScope.PhoneOnly, forever)

    assertThat(r.computed).isEqualTo(2)
    assertThat(disk.keys().toSet()).containsExactly(b)
  }

  /** Un guasto di chi tiene vale "nessuno tiene niente": la tappa non cade e il buffer si svuota come nella Fase 6. */
  @Test
  fun unGuastoDiChiTieneNonFermaNiente() = runTest {
    val f = TestFamily()
    val keys = (1..3).map { f.nas.add(common, preview = false, concepts = "sole $it") }
    val disk = diskBuffer()
    deliver(disk, keys)
    val phone = device(f, disk)
    phone.keepers = BufferKeepers { throw IOException("archivio dei volti non aperto") }
    phone.restart()

    val r = phone.engine.run(AiScope.Share(common), forever)

    assertThat(r.computed).isEqualTo(3)
    assertThat(disk.keys()).isEmpty()
  }

  /** Sotto 1 GiB libero (E-A17) il buffer si svuota comunque: chi tiene non puo' riempire un telefono che non ha posto. */
  @Test
  fun sottoUnGiBLiberoNessunoTiene() = runTest {
    val f = TestFamily()
    val keys = (1..2).map { f.nas.add(common, preview = false, concepts = "pieno $it") }
    var free = 8L * 1024 * 1024 * 1024
    val disk = DiskAiBuffer(tmp.newFolder(), freeSpace = { free })
    deliver(disk, keys)
    free = AiBuffer.MIN_FREE_BYTES - 1
    val phone = device(f, disk)
    phone.freeBytes = free
    val keeper = Keeper(keys.toSet())
    phone.keepers = keeper
    phone.restart()

    phone.engine.run(AiScope.Share(common), forever)

    assertThat(disk.keys()).isEmpty()
    assertThat(keeper.asked).isEqualTo(0)
  }
}
