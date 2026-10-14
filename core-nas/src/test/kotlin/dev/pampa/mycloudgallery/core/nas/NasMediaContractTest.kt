package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ServerCopy
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.noop.NoNasMediaReader
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Un NAS dei media visto "da fuori" (il PC, un altro dispositivo) e da dentro ([reader], [serverCopy]): lo stesso
 * contratto si prova sul NAS finto di P0 ([InMemoryNasMedia]) e sul simulatore SMB vero di P1
 * ([SmbNasMediaContractTest]).
 */
interface MediaNasFixture {
  val reader: NasMediaReader
  val serverCopy: ServerCopy

  /** L'ora del NAS che il fixture usa per le date. */
  var nasNow: Long

  /** I byte letti dai file finora. */
  val bytesRead: Long

  fun addShare(name: String)

  fun putFile(share: String, path: String, content: NasContent, modifiedAt: Long = nasNow, attributes: Int = NasAttributes.ARCHIVE): Long

  fun putDir(share: String, path: String, attributes: Int)

  fun rename(share: String, from: String, to: String)

  fun lock(share: String, path: String)

  fun unlock(share: String, path: String)

  fun exists(share: String, path: String): Boolean

  /** L'[n]-esimo elenco contato da adesso (da 1) fallisce con un errore di rete, anche dopo i nuovi tentativi. */
  fun failListCall(n: Int)

  /** Niente piu' guasti programmati. */
  fun clearFaults()

  /** L'errore che un guasto di rete produce su questo fixture. */
  val networkError: NasError
}

/**
 * Il contratto della lettura dei media (Fase 4, P0): e' cio' su cui P4, P5, P7 e P8 lavorano. P1 fa passare gli
 * stessi casi sul simulatore SMB ([SmbNasMediaContractTest]).
 */
abstract class NasMediaReaderContract {

  /** Un NAS nuovo con la share `alessio`. */
  abstract fun TestScope.fixture(): MediaNasFixture

  private suspend fun MediaNasFixture.listAll(dir: SharePath?, pageSize: Int = 1000): Pair<List<NasEntry>, List<Int>> {
    val entries = ArrayList<NasEntry>()
    val pages = ArrayList<Int>()
    val result = reader.list("alessio", dir, Purpose.BACKGROUND, pageSize) { page ->
      pages += page.size
      entries += page
    }
    assertThat(result).isInstanceOf(NasResult.Ok::class.java)
    return entries to pages
  }

  @Test
  fun elencaAPagineSenzaDistinguereLeMaiuscole() = runTest {
    val nas = fixture()
    repeat(2500) { nas.putFile("alessio", "Foto/IMG_$it.JPG", NasContent.of(byteArrayOf(it.toByte()))) }
    nas.putDir("alessio", "Foto/.recycle", attributes = NasAttributes.HIDDEN)

    val (entries, pages) = nas.listAll(SharePath.of("foto"), pageSize = 1000)

    assertThat(pages).containsExactly(1000, 1000, 501).inOrder()
    assertThat(entries.count { !it.isDirectory }).isEqualTo(2500)
    assertThat(entries.single { it.isDirectory }.isHidden).isTrue()
    assertThat(entries.first { it.name == "IMG_7.JPG" }.size).isEqualTo(1)
    assertThat(entries.map { it.fileId }.toSet()).hasSize(2501)
  }

  @Test
  fun ilFileIdRestaAllaRinominaDiUnaCartellaELaDataDellaCartellaCambia() = runTest {
    val nas = fixture()
    nas.nasNow = 1_800_000_000_000L
    val id = nas.putFile("alessio", "Viaggi/2024/a.jpg", NasContent.of(ByteArray(10)))
    val before = nas.reader.stat("alessio", SharePath.of("Viaggi", "2024", "a.jpg"), Purpose.BACKGROUND).okOrNull()!!
    nas.nasNow += 60_000
    nas.rename("alessio", "Viaggi/2024", "Viaggi/Spagna 2024")

    val after = nas.reader.stat("alessio", SharePath.of("viaggi", "spagna 2024", "A.JPG"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(after.fileId).isEqualTo(id)
    assertThat(after.stamp).isEqualTo(before.stamp)
    assertThat(after.name).isEqualTo("a.jpg")
    assertThat(nas.reader.stat("alessio", SharePath.of("Viaggi", "2024", "a.jpg"), Purpose.BACKGROUND).okOrNull()).isNull()
    val folder = nas.reader.stat("alessio", SharePath.of("Viaggi"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(folder.isDirectory).isTrue()
    assertThat(folder.modifiedAt).isEqualTo(nas.nasNow)
  }

  @Test
  fun leLettureAIntervalliSonoGiusteAiBordiEcontate() = runTest {
    val nas = fixture()
    val content = ByteArray(300_000) { (it * 31).toByte() }
    nas.putFile("alessio", "v.mp4", NasContent.of(content))
    val file = nas.reader.open("alessio", SharePath.of("v.mp4"), Purpose.BACKGROUND).okOrNull()!!
    val start = nas.bytesRead

    val regions = QuickHashAlgorithm.regions(file.size)
    val head = file.readRange(regions[0])
    val tail = file.readRange(regions[1])
    assertThat(QuickHashAlgorithm.compute(file.size, head, tail)).isEqualTo(QuickHashAlgorithm.ofContent(content))
    assertThat(file.readRange(299_990L..400_000L)).hasLength(10)
    assertThat(file.readRange(LongRange.EMPTY)).isEmpty()
    assertThat(file.readRange(500_000L..500_010L)).isEmpty()
    assertThat(file.readRange(0L..0L)).isEqualTo(byteArrayOf(content[0]))
    assertThat(file.readRange(299_999L..299_999L)).isEqualTo(byteArrayOf(content[299_999]))
    val one = ByteArray(4)
    assertThat(file.read(299_999, one, 1, 3)).isEqualTo(1)
    assertThat(one[1]).isEqualTo(content[299_999])
    assertThat(file.read(300_000, one, 0, 4)).isEqualTo(-1)
    assertThat(nas.bytesRead - start).isEqualTo(2L * QuickHashAlgorithm.CHUNK_BYTES + 10 + 2 + 1)
    file.close()
  }

  @Test
  fun readAllConsegnaTuttoInOrdineConLoSha256() = runTest {
    val nas = fixture()
    val content = Random(1).nextBytes(3 * NasMediaLimits.READ_ALL_CHUNK_BYTES + 17)
    nas.putFile("alessio", "grande.dng", NasContent.of(content))
    val file = nas.reader.open("alessio", SharePath.of("grande.dng"), Purpose.BACKGROUND).okOrNull()!!
    val out = ByteArrayOutputStream()
    var lastProgress = 0L

    val hash = nas.reader.readAll(file, { buf, n -> out.write(buf, 0, n) }) { lastProgress = it }.okOrNull()

    assertThat(hash).isEqualTo(Sha256.of(content))
    assertThat(out.toByteArray()).isEqualTo(content)
    assertThat(lastProgress).isEqualTo(content.size.toLong())
  }

  @Test
  fun iFileVirtualiSiLegginoAIntervalliSenzaOccupareMemoria() = runTest {
    val nas = fixture()
    val nineGb = 9_400_000_000L
    nas.putFile("alessio", "film.mkv", NasContent.virtual(nineGb, seed = 42, head = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
    val file = nas.reader.open("alessio", SharePath.of("film.mkv"), Purpose.BACKGROUND).okOrNull()!!
    val start = nas.bytesRead

    assertThat(file.size).isEqualTo(nineGb)
    assertThat(file.readRange(0L..3L)).isEqualTo(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
    val tail = file.readRange((nineGb - 16) until nineGb)
    assertThat(file.readRange((nineGb - 16) until nineGb)).isEqualTo(tail)
    assertThat(nas.bytesRead - start).isEqualTo(4L + 32)
  }

  @Test
  fun erroriEGuastiArrivanoComeEsitiNonComeEccezioni() = runTest {
    val nas = fixture()
    nas.putFile("alessio", "x.jpg", NasContent.of(ByteArray(5)))
    nas.putDir("alessio", "cartella", NasAttributes.DIRECTORY)
    nas.lock("alessio", "X.JPG")
    assertThat(nas.reader.open("alessio", SharePath.of("x.jpg"), Purpose.INTERACTIVE).errorOrNull()).isEqualTo(NasError.FileBusy)
    nas.unlock("alessio", "x.jpg")
    assertThat(nas.reader.open("alessio", SharePath.of("cartella"), Purpose.INTERACTIVE).errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(nas.reader.open("alessio", SharePath.of("manca.jpg"), Purpose.INTERACTIVE).errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(nas.reader.list("alessio", SharePath.of("manca"), Purpose.BACKGROUND) {}.errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(nas.reader.list("alessio", SharePath.of("x.jpg"), Purpose.BACKGROUND) {}.errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(nas.reader.stat("alessio", SharePath.of("manca", "a.jpg"), Purpose.BACKGROUND).okOrNull()).isNull()

    nas.failListCall(2)
    assertThat(nas.reader.list("alessio", null, Purpose.BACKGROUND) {}.errorOrNull()).isNull()
    assertThat(nas.reader.list("alessio", null, Purpose.BACKGROUND) {}.errorOrNull()).isEqualTo(nas.networkError)
    nas.clearFaults()

    assertThat(nas.reader.list("nessuna", null, Purpose.BACKGROUND) {}.errorOrNull()).isInstanceOf(NasError.ShareMissing::class.java)
    assertThat(NoNasMediaReader.stat("alessio", SharePath.of("x.jpg"), Purpose.BACKGROUND).errorOrNull())
      .isInstanceOf(NasError.NotAllowedHere::class.java)
  }

  @Test
  fun laCopiaLatoServerNonSovrascriveEDaUnFileIdNuovo() = runTest {
    val nas = fixture()
    nas.addShare("test-comune")
    val original = nas.putFile("alessio", "a.jpg", NasContent.of(byteArrayOf(1, 2, 3)))
    val copy = nas.serverCopy.copy("alessio", SharePath.of("a.jpg"), "test-comune", SharePath.of("Famiglia", "a.jpg"))
    assertThat(copy.okOrNull()).isEqualTo(3L)
    val copied = nas.reader.stat("test-comune", SharePath.of("Famiglia", "a.jpg"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(copied.fileId).isNotEqualTo(original)
    assertThat(nas.serverCopy.copy("alessio", SharePath.of("a.jpg"), "test-comune", SharePath.of("famiglia", "A.jpg")).errorOrNull())
      .isEqualTo(NasError.Exists)
    assertThat(nas.serverCopy.copy("alessio", SharePath.of("manca.jpg"), "test-comune", SharePath.of("b.jpg")).errorOrNull())
      .isEqualTo(NasError.NotFound)
    assertThat(nas.exists("alessio", "a.jpg")).isTrue()
  }

  @Test
  fun unaVoceNonStampaIlNome() {
    val entry = NasEntry("segreto.jpg", false, 1, 0, null, 7, 0)
    assertThat(entry.toString()).doesNotContain("segreto")
  }
}

/** Il contratto sul NAS finto di P0 (sopra il contratto, senza SMB). */
class NasMediaContractTest : NasMediaReaderContract() {

  override fun TestScope.fixture(): MediaNasFixture = FakeFixture(InMemoryNasMedia().apply { addShare("alessio") })

  private class FakeFixture(private val nas: InMemoryNasMedia) : MediaNasFixture {
    override val reader: NasMediaReader get() = nas.reader
    override val serverCopy: ServerCopy get() = nas.serverCopy
    private var now = 1_791_936_000_000L

    init {
      nas.nasNow = { now }
    }

    override var nasNow: Long
      get() = now
      set(value) {
        now = value
      }

    override val bytesRead: Long get() = nas.bytesRead.get()

    override fun addShare(name: String) = nas.addShare(name)

    override fun putFile(share: String, path: String, content: NasContent, modifiedAt: Long, attributes: Int): Long =
      nas.putFile(share, path, content, modifiedAt, attributes)

    override fun putDir(share: String, path: String, attributes: Int) = nas.putDir(share, path, attributes)

    override fun rename(share: String, from: String, to: String) = nas.rename(share, from, to)

    override fun lock(share: String, path: String) = nas.lock(share, path)

    override fun unlock(share: String, path: String) = nas.unlock(share, path)

    override fun exists(share: String, path: String): Boolean = nas.exists(share, path)

    override fun failListCall(n: Int) {
      var calls = 0
      nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.LIST && ++calls == n) NasError.Timeout else null }
    }

    override fun clearFaults() {
      nas.fault = null
    }

    override val networkError: NasError = NasError.Timeout
  }
}
