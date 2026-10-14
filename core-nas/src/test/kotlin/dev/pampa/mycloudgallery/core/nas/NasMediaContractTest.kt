package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.noop.NoNasMediaReader
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il contratto della lettura dei media (Fase 4, P0) sul NAS finto: e' cio' su cui P4, P5, P7 e P8 lavorano finche' P1
 * non consegna l'implementazione SMB. P1 fa passare gli stessi casi sul simulatore vero.
 */
class NasMediaContractTest {

  private val nas = InMemoryNasMedia().apply { addShare("alessio") }
  private val reader = nas.reader

  private suspend fun listAll(dir: SharePath?, pageSize: Int = 1000): Pair<List<NasEntry>, List<Int>> {
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
    repeat(2500) { nas.putFile("alessio", "Foto/IMG_$it.JPG", NasContent.of(byteArrayOf(it.toByte()))) }
    nas.putDir("alessio", "Foto/.recycle", attributes = NasAttributes.HIDDEN)

    val (entries, pages) = listAll(SharePath.of("foto"), pageSize = 1000)

    assertThat(pages).containsExactly(1000, 1000, 501).inOrder()
    assertThat(entries.count { !it.isDirectory }).isEqualTo(2500)
    assertThat(entries.single { it.isDirectory }.isHidden).isTrue()
    assertThat(entries.first { it.name == "IMG_7.JPG" }.size).isEqualTo(1)
  }

  @Test
  fun ilFileIdRestaAllaRinominaDiUnaCartellaELaDataDellaCartellaCambia() = runTest {
    var now = 1_800_000_000_000L
    nas.nasNow = { now }
    val id = nas.putFile("alessio", "Viaggi/2024/a.jpg", NasContent.of(ByteArray(10)))
    val before = reader.stat("alessio", SharePath.of("Viaggi", "2024", "a.jpg"), Purpose.BACKGROUND).okOrNull()!!
    now += 60_000
    nas.rename("alessio", "Viaggi/2024", "Viaggi/Spagna 2024")

    val after = reader.stat("alessio", SharePath.of("viaggi", "spagna 2024", "A.JPG"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(after.fileId).isEqualTo(id)
    assertThat(after.stamp).isEqualTo(before.stamp)
    assertThat(reader.stat("alessio", SharePath.of("Viaggi", "2024", "a.jpg"), Purpose.BACKGROUND).okOrNull()).isNull()
    val folder = reader.stat("alessio", SharePath.of("Viaggi"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(folder.modifiedAt).isEqualTo(now)
  }

  @Test
  fun leLettureAIntervalliSonoGiusteAiBordiEcontate() = runTest {
    val content = ByteArray(300_000) { (it * 31).toByte() }
    nas.putFile("alessio", "v.mp4", NasContent.of(content))
    val file = reader.open("alessio", SharePath.of("v.mp4"), Purpose.BACKGROUND).okOrNull()!!

    val regions = QuickHashAlgorithm.regions(file.size)
    val head = file.readRange(regions[0])
    val tail = file.readRange(regions[1])
    assertThat(QuickHashAlgorithm.compute(file.size, head, tail)).isEqualTo(QuickHashAlgorithm.ofContent(content))
    assertThat(file.readRange(299_990L..400_000L)).hasLength(10)
    assertThat(file.readRange(LongRange.EMPTY)).isEmpty()
    assertThat(file.readRange(500_000L..500_010L)).isEmpty()
    assertThat(nas.bytesRead.get()).isEqualTo(2L * QuickHashAlgorithm.CHUNK_BYTES + 10)
    file.close()
  }

  @Test
  fun readAllConsegnaTuttoInOrdineConLoSha256() = runTest {
    val content = Random(1).nextBytes(3 * NasMediaLimits.READ_ALL_CHUNK_BYTES + 17)
    nas.putFile("alessio", "grande.dng", NasContent.of(content))
    val file = reader.open("alessio", SharePath.of("grande.dng"), Purpose.BACKGROUND).okOrNull()!!
    val out = ByteArrayOutputStream()
    var lastProgress = 0L

    val hash = reader.readAll(file, { buf, n -> out.write(buf, 0, n) }) { lastProgress = it }.okOrNull()

    assertThat(hash).isEqualTo(Sha256.of(content))
    assertThat(out.toByteArray()).isEqualTo(content)
    assertThat(lastProgress).isEqualTo(content.size.toLong())
  }

  @Test
  fun iFileVirtualiSiLegginoAIntervalliSenzaOccupareMemoria() = runTest {
    val nineGb = 9_400_000_000L
    nas.putFile("alessio", "film.mkv", NasContent.virtual(nineGb, seed = 42, head = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
    val file = reader.open("alessio", SharePath.of("film.mkv"), Purpose.BACKGROUND).okOrNull()!!

    assertThat(file.size).isEqualTo(nineGb)
    assertThat(file.readRange(0L..3L)).isEqualTo(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
    val tail = file.readRange((nineGb - 16) until nineGb)
    assertThat(file.readRange((nineGb - 16) until nineGb)).isEqualTo(tail)
    assertThat(nas.bytesRead.get()).isEqualTo(4L + 32)
  }

  @Test
  fun erroriEGuastiArrivanoComeEsitiNonComeEccezioni() = runTest {
    nas.putFile("alessio", "x.jpg", NasContent.of(ByteArray(5)))
    nas.lock("alessio", "X.JPG")
    assertThat(reader.open("alessio", SharePath.of("x.jpg"), Purpose.INTERACTIVE).errorOrNull()).isEqualTo(NasError.FileBusy)
    nas.unlock("alessio", "x.jpg")

    var calls = 0
    nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.LIST && ++calls == 2) NasError.Timeout else null
    }
    assertThat(reader.list("alessio", null, Purpose.BACKGROUND) {}.errorOrNull()).isNull()
    assertThat(reader.list("alessio", null, Purpose.BACKGROUND) {}.errorOrNull()).isEqualTo(NasError.Timeout)
    nas.fault = null

    assertThat(reader.list("nessuna", null, Purpose.BACKGROUND) {}.errorOrNull()).isInstanceOf(NasError.ShareMissing::class.java)
    assertThat(NoNasMediaReader.stat("alessio", SharePath.of("x.jpg"), Purpose.BACKGROUND).errorOrNull())
      .isInstanceOf(NasError.NotAllowedHere::class.java)
  }

  @Test
  fun laCopiaLatoServerNonSovrascriveEDaUnFileIdNuovo() = runTest {
    nas.addShare("test-comune")
    val original = nas.putFile("alessio", "a.jpg", NasContent.of(byteArrayOf(1, 2, 3)))
    val copy = nas.serverCopy.copy("alessio", SharePath.of("a.jpg"), "test-comune", SharePath.of("Famiglia", "a.jpg"))
    assertThat(copy.okOrNull()).isEqualTo(3L)
    val copied = reader.stat("test-comune", SharePath.of("Famiglia", "a.jpg"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(copied.fileId).isNotEqualTo(original)
    assertThat(nas.serverCopy.copy("alessio", SharePath.of("a.jpg"), "test-comune", SharePath.of("famiglia", "A.jpg")).errorOrNull())
      .isEqualTo(NasError.Exists)
    assertThat(nas.exists("alessio", "a.jpg")).isTrue()
  }

  @Test
  fun unaVoceNonStampaIlNome() {
    val entry = NasEntry("segreto.jpg", false, 1, 0, null, 7, 0)
    assertThat(entry.toString()).doesNotContain("segreto")
  }
}
