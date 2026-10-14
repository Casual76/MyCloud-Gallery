package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.publish.IndexProvenanceException
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regola M3 come proprieta' (C19; checklist della Fase 4: "nessun byte ricavato da un file privato nell'indice
 * dell'area comune"). Due share: l'area privata `test` e l'area comune `test-comune`, con file solo privati, solo comuni
 * e file con lo **stesso percorso** in entrambe ma contenuto diverso. Per 300 giri un chiamante "sbadato" mescola file
 * delle due aree nei cambiamenti e nei blob di una share qualunque, e pubblica ogni tanto. Alla fine si leggono **tutti
 * i byte** sotto `.mycloudgallery/` dell'area comune: nessun percorso, contenuto di miniatura o impronta di un file
 * privato vi compare, e ogni record e ogni blob dell'indice comune corrisponde a un file dell'area comune.
 */
class ProvenancePropertyTest {

  private val privateShare = "test"
  private val commonShare = "test-comune"

  @Test
  fun nienteDellAreaPrivataNellIndiceComune() = runTest {
    for (seed in 1..3) check(seed)
  }

  private suspend fun check(seed: Int) {
    val random = Random(seed)
    val world = TestWorld()
    val phone = world.device(account = "test")
    val publisher = phone.publisher()
    val privateOnly = (1..25).map { world.file(privateShare, "Privato/segreto-$seed-$it.jpg", random) }
    val commonOnly = (1..25).map { world.file(commonShare, "Comune/foto-$seed-$it.jpg", random) }
    val samePathPrivate = (1..10).map { world.file(privateShare, "Doppio/stesso-$it.jpg", random) }
    val samePathCommon = (1..10).map { world.file(commonShare, "Doppio/stesso-$it.jpg", random) }
    val privateFiles = privateOnly + samePathPrivate
    val commonFiles = commonOnly + samePathCommon
    val all = privateFiles + commonFiles
    var rejected = 0
    var accepted = 0

    repeat(300) {
      val target = if (random.nextBoolean()) commonShare else privateShare
      val picked = (1..random.nextInt(1, 6)).map { all[random.nextInt(all.size)] }
      val changes = picked.map { f ->
        if (random.nextInt(8) == 0) MetaChange.Remove(f.path) else f.put(setOf(SegmentClass.THUMB_S))
      }
      val blobs = picked.filter { random.nextBoolean() }.map<TestFile, BlobPut> { it.thumb(if (random.nextBoolean()) SegmentClass.THUMB_S else SegmentClass.HQ) }
      try {
        publisher.stage(target, changes, blobs)
        accepted++
      } catch (e: IndexProvenanceException) {
        rejected++
      }
      if (random.nextInt(10) == 0) publisher.flush(target, FlushReason.UNIT_FINISHED)
    }
    publisher.flush(commonShare, FlushReason.UNIT_FINISHED)
    publisher.flush(privateShare, FlushReason.UNIT_FINISHED)
    assertWithMessage("il chiamante sbadato e' stato fermato").that(rejected).isGreaterThan(50)
    assertWithMessage("e quello corretto no").that(accepted).isGreaterThan(20)

    for ((share, own, foreign) in listOf(Triple(commonShare, commonFiles, privateOnly + samePathPrivate), Triple(privateShare, privateFiles, commonOnly + samePathCommon))) {
      // 1. Ogni record e ogni blob dell'indice della share viene da un file di quella share (percorso, timbro, impronta).
      val state = ReaderState.read(world, share, phone.id)
      val byPath = own.associateBy { it.path }
      for (record in state.records.values) {
        val file = byPath[record.path] ?: error("percorso estraneo nell'indice di $share")
        assertThat(record.file.stamp).isEqualTo(file.stamp)
        assertThat(record.file.quickHash).isEqualTo(file.meta().quickHash)
      }
      val ownKeys = own.map { it.contentKey }.toSet()
      for ((cls, key) in state.blobs.keys) assertWithMessage("$share: blob $cls estraneo").that(key in ownKeys).isTrue()

      // 2. Nessun byte ricavato da un file dell'altra area, in nessun file sotto .mycloudgallery/ della share.
      val haystack = world.nas.appFiles(share)
      val markers = foreign.filter { f -> own.none { it.contentKey == f.contentKey } }.flatMap { f ->
        listOf(f.marker(), f.meta().quickHash.hex.toByteArray(), base32QuickHash(f))
      } + (if (share == commonShare) privateOnly.map { it.path.value.toByteArray(Charsets.UTF_8) } else commonOnly.map { it.path.value.toByteArray(Charsets.UTF_8) })
      for ((path, bytes) in haystack) {
        for (marker in markers) assertWithMessage("$share: $path contiene un dato dell'altra area").that(bytes.indexOf(marker)).isEqualTo(-1)
      }
    }
  }

  private fun base32QuickHash(f: TestFile): ByteArray {
    val raw = f.meta().quickHash.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return dev.pampa.mycloudgallery.core.trust.codec.Base32.encode(raw).toByteArray()
  }

  private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..size - needle.size) {
      for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }
}
