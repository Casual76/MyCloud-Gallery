package dev.pampa.mycloudgallery.core.index.security

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.p3.ReaderState
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.index.p3.TestWorld
import dev.pampa.mycloudgallery.core.index.publish.IndexProvenanceException
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 10 P11, voce **S1 (a)** della revisione finale (docs/fase10-progetto.md §2.11, D36; C19, regola M3): "un account non legge dati privati
 * altrui tramite l'indice". La proprieta' su **mondi casuali**: in ogni mondo una famiglia nuova, l'area privata `test` e l'area comune
 * `test-comune` con file solo privati (con il segno riconoscibile `MCG-PRIV-<casuale>` nel nome e nei primi byte), solo comuni, con lo
 * **stesso percorso** in entrambe, con lo stesso percorso a meno delle maiuscole e con **gli stessi byte** in entrambe (quelli si'
 * possono comparire: vengono anche da un file comune). Un chiamante "sbadato" mescola file delle due aree nei cambiamenti e nei blob di una
 * share qualunque e pubblica a caso. Alla fine si leggono **tutti i byte** sotto `.mycloudgallery/` dell'area comune: nessun segno, nessun
 * percorso, nessuna impronta rapida (esadecimale o base32), nessuna identita' di media di un file privato; ogni record e ogni blob
 * dell'indice comune viene da un file comune. Il contrario vale per l'area privata.
 *
 * Quanti mondi: 1.000 in `check.ps1` (circa 20 s), **10.000** con `MCG_SECURITY_FULL=1` (`tools/security/run-all.ps1 -Livello jvm`).
 * Nessun dato personale: i segni sono sintetici.
 */
class S1ProvenanceWorldsTest {

  private val privateShare = "test"
  private val commonShare = "test-comune"

  private class Tally {
    var worlds = 0
    var staged = 0
    var rejected = 0
    var flushes = 0
    var bytesScanned = 0L
    var markersChecked = 0L
  }

  @Test
  fun inNessunMondoUnDatoPrivatoFinisceNellIndiceComune() = runBlocking {
    val worlds = if (System.getenv("MCG_SECURITY_FULL") == "1") 10_000 else 1_000
    val tally = Tally()
    val started = System.nanoTime()
    for (seed in 1..worlds) world(seed.toLong() * 7_919L + 20261031L, tally)
    val seconds = (System.nanoTime() - started) / 1e9
    assertWithMessage("il chiamante sbadato e' stato fermato").that(tally.rejected).isGreaterThan(worlds)
    assertWithMessage("e quello corretto no").that(tally.staged).isGreaterThan(worlds)
    println(
      "MCG-SEC S1a mondi=${tally.worlds} accettati=${tally.staged} rifiutati=${tally.rejected} pubblicazioni=${tally.flushes} " +
        "byte_letti=${tally.bytesScanned} confronti=${tally.markersChecked} segni_trovati=0 secondi=${"%.1f".format(java.util.Locale.ROOT, seconds)}",
    )
  }

  private suspend fun world(seed: Long, tally: Tally) {
    val random = Random(seed)
    val world = TestWorld()
    val phone = world.device(account = "test")
    val publisher = phone.publisher()
    val mark = "MCG-PRIV-" + random.nextLong().toULong().toString(16).padStart(16, '0')

    fun file(share: String, path: String, content: ByteArray): TestFile =
      TestFile(share, SharePath.parseMediaPath(path), content).also { world.catalog.put(share, it.path, it.stamp, it.meta().quickHash) }
    fun bytes(prefix: String?): ByteArray {
      val body = ByteArray(200 + random.nextInt(800)).also { random.nextBytes(it) }
      val head = prefix?.toByteArray(Charsets.US_ASCII) ?: return body
      return head + body
    }

    val privateOnly = (1..random.nextInt(1, 9)).map { file(privateShare, "Privato/$mark-$it.jpg", bytes("$mark/$it")) }
    val commonOnly = (1..random.nextInt(1, 9)).map { file(commonShare, "Comune/foto-$seed-$it.jpg", bytes(null)) }
    val samePrivate = ArrayList<TestFile>()
    val sameCommon = ArrayList<TestFile>()
    repeat(random.nextInt(0, 4)) {
      samePrivate += file(privateShare, "Doppio/stesso-$it.jpg", bytes("$mark/doppio$it"))
      sameCommon += file(commonShare, "Doppio/stesso-$it.jpg", bytes(null))
    }
    repeat(random.nextInt(0, 3)) {
      samePrivate += file(privateShare, "Maiuscole/Foto-$it.JPG", bytes("$mark/maiuscole$it"))
      sameCommon += file(commonShare, "maiuscole/foto-$it.jpg", bytes(null))
    }
    // Gli stessi byte in entrambe le aree: dall'area comune la loro impronta puo' legittimamente comparire.
    val twins = if (random.nextInt(4) == 0) {
      val content = bytes(null)
      listOf(file(privateShare, "Gemelli/a-$seed.jpg", content), file(commonShare, "Gemelli/a-$seed.jpg", content.copyOf()))
    } else {
      emptyList()
    }
    val privateFiles = privateOnly + samePrivate + twins.filter { it.share == privateShare }
    val commonFiles = commonOnly + sameCommon + twins.filter { it.share == commonShare }
    val all = privateFiles + commonFiles

    repeat(random.nextInt(6, 26)) {
      val target = if (random.nextBoolean()) commonShare else privateShare
      val picked = (1..random.nextInt(1, 5)).map { all[random.nextInt(all.size)] }
      val changes = picked.map { f -> if (random.nextInt(8) == 0) MetaChange.Remove(f.path) else f.put(setOf(SegmentClass.THUMB_S)) }
      val blobs = picked.filter { random.nextBoolean() }.map<TestFile, BlobPut> {
        it.thumb(listOf(SegmentClass.THUMB_S, SegmentClass.HQ, SegmentClass.ANALYSIS)[random.nextInt(3)])
      }
      try {
        publisher.stage(target, changes, blobs)
        tally.staged++
      } catch (e: IndexProvenanceException) {
        tally.rejected++
      }
      if (random.nextInt(6) == 0) {
        publisher.flush(target, FlushReason.UNIT_FINISHED)
        tally.flushes++
      }
    }
    publisher.flush(commonShare, FlushReason.UNIT_FINISHED)
    publisher.flush(privateShare, FlushReason.UNIT_FINISHED)
    tally.flushes += 2

    for ((share, own, foreign) in listOf(Triple(commonShare, commonFiles, privateFiles), Triple(privateShare, privateFiles, commonFiles))) {
      // 1. Ogni record e ogni blob dell'indice della share viene da un file di quella share (percorso, timbro, impronta rapida).
      val state = ReaderState.read(world, share, phone.id)
      val byPath = own.associateBy { it.path }
      for (record in state.records.values) {
        val file = byPath[record.path] ?: error("seme $seed: percorso estraneo nell'indice di $share")
        assertThat(record.file.stamp).isEqualTo(file.stamp)
        assertThat(record.file.quickHash).isEqualTo(file.meta().quickHash)
      }
      val ownKeys = own.map { it.contentKey }.toSet()
      for ((cls, key) in state.blobs.keys) assertWithMessage("seme $seed, $share: blob $cls estraneo").that(key in ownKeys).isTrue()

      // 2. Nessun byte ricavato da un file dell'altra area (salvo i gemelli con gli stessi byte), in nessun file sotto .mycloudgallery/.
      val markers = ArrayList<ByteArray>()
      for (f in foreign) {
        if (own.any { it.contentKey == f.contentKey }) continue
        markers += f.marker()
        markers += f.meta().quickHash.hex.toByteArray()
        markers += Base32.encode(f.meta().quickHash.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).toByteArray()
        markers += f.mediaId.value.toByteArray()
        if (own.none { it.path.value.equals(f.path.value, ignoreCase = true) }) markers += f.path.value.toByteArray(Charsets.UTF_8)
      }
      if (share == commonShare) markers += mark.toByteArray(Charsets.US_ASCII)
      for ((path, content) in world.nas.appFiles(share)) {
        tally.bytesScanned += content.size
        for (marker in markers) {
          tally.markersChecked++
          assertWithMessage("seme $seed, $share: $path contiene un dato dell'altra area").that(content.indexOf(marker)).isEqualTo(-1)
        }
      }
    }
    tally.worlds++
  }

  private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    val first = needle[0]
    outer@ for (i in 0..size - needle.size) {
      if (this[i] != first) continue
      for (j in 1 until needle.size) if (this[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }
}
