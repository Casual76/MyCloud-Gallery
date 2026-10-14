package dev.pampa.mycloudgallery.core.index.replica

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.replica.pack.PackDirectory
import dev.pampa.mycloudgallery.core.index.sync.Fx
import dev.pampa.mycloudgallery.core.index.sync.Harness
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import java.io.IOException
import java.nio.MappedByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** D15: pacchi immutabili, mappati una volta, letti senza copie; nessun file per miniatura. */
class PackDirectoryTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun unPaccoSiMappaUnaVoltaESiLeggeSenzaCopie() {
    val packs = PackDirectory(File(tmp.root, "packs"))
    val bytes = ByteArray(10_000) { (it % 251).toByte() }
    packs.writeImmutable("abc123", bytes)
    repeat(1_000) { i ->
      val offset = (i * 7) % 9_000L
      val window = packs.slice("abc123", offset, 64)
      // Una finestra della mappatura: memoria diretta, sola lettura, nessun array sul heap.
      assertThat(window.isDirect).isTrue()
      assertThat(window.isReadOnly).isTrue()
      assertThat(window.hasArray()).isFalse()
      assertThat(window).isInstanceOf(MappedByteBuffer::class.java)
      assertThat(window.remaining()).isEqualTo(64)
      assertThat(window.get(0)).isEqualTo(bytes[offset.toInt()])
    }
    assertThat(packs.mapCount.get()).isEqualTo(1)
    // Idempotente: la stessa scrittura dopo un crash non tocca il file.
    packs.writeImmutable("abc123", bytes)
    assertThat(File(tmp.root, "packs").listFiles()!!.map { it.name }).containsExactly("abc123")
  }

  @Test
  fun unPaccoAssenteOUnaFinestraOltreLaFineSonoErroriDiLetturaNonCrash() {
    val packs = PackDirectory(File(tmp.root, "packs"))
    assertThrows(IOException::class.java) { packs.slice("assente", 0, 10) }
    packs.writeImmutable("corto", ByteArray(10))
    assertThrows(IOException::class.java) { packs.slice("corto", 5, 10) }
    assertThrows(IllegalArgumentException::class.java) { packs.fileOf("../fuori") }
    assertThrows(IllegalArgumentException::class.java) { packs.fileOf("A") }
  }

  @Test
  fun unPaccoPropioCresceInCodaESiRimappaSoloQuandoServe() {
    val packs = PackDirectory(File(tmp.root, "packs"))
    var length = 0L
    val first = ByteArray(100) { 1 }
    length = packs.append("own-2-1", length, first) + first.size
    assertThat(packs.slice("own-2-1", 0, 100).get(99)).isEqualTo(1.toByte())
    // Byte scritti prima di un crash e mai registrati: la prossima aggiunta li sovrascrive.
    packs.append("own-2-1", length, ByteArray(50) { 9 })
    val second = ByteArray(80) { 2 }
    val offset = packs.append("own-2-1", length, second)
    assertThat(offset).isEqualTo(100)
    assertThat(packs.slice("own-2-1", 100, 80).get(0)).isEqualTo(2.toByte())
    assertThat(packs.slice("own-2-1", 0, 10).get(0)).isEqualTo(1.toByte())
    assertThat(packs.mapCount.get()).isEqualTo(2)
  }

  @Test
  fun lAvvioTogliePacchiOrfaniETemporanei() {
    val root = File(tmp.root, "packs").apply { mkdirs() }
    File(root, "tmp-zzz").writeBytes(ByteArray(3))
    File(root, "orfano").writeBytes(ByteArray(3))
    File(root, "noto").writeBytes(ByteArray(3))
    PackDirectory(root).sweep(setOf("noto"))
    assertThat(root.listFiles()!!.map { it.name }).containsExactly("noto")
  }

  @Test
  fun leMiniatureScaricateStannoInUnPaccoPerSegmentoELeProprieInUnPaccoInCoda() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val thumbs = (1..300).map { Fx.key(it) to Fx.pixels(SegmentClass.THUMB_S, it) }
    val records = (1..300).map { Fx.put("Foto/$it.jpg", it.toLong(), Fx.file(it, prepared = setOf(SegmentClass.THUMB_S))) }
    nas.publish("alessio", "a", listOf(Fx.meta(*records.toTypedArray()), Fx.images(SegmentClass.THUMB_S, *thumbs.toTypedArray())))
    val me = Harness("b", tmp.root, nas)
    me.sync("alessio")
    // 300 miniature, un solo file.
    assertThat(me.packs.root.listFiles()!!.size).isEqualTo(1)
    thumbs.forEach { (key, bytes) -> assertThat(me.blob(key, SegmentClass.THUMB_S)).isEqualTo(bytes) }
    assertThat(me.packs.mapCount.get()).isEqualTo(1)

    // Le immagini preparate qui (OwnIndexSink): un pacco proprio in coda, solo le classi del livello (Standard: S e M).
    val own = (301..340).flatMap { n ->
      listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.HQ, SegmentClass.ANALYSIS)
        .map { cls -> BlobPut.Image(cls, Fx.key(n), Fx.pixels(cls, n, "b")) }
    }
    own.chunked(4).forEach { me.replica.applyOwn("alessio", emptyList(), it) }
    assertThat(me.replica.keptClasses).isEqualTo(QualityLevel.STANDARD.classes)
    val names = me.packs.root.listFiles()!!.map { it.name }
    assertThat(names).hasSize(3)
    assertThat(names).containsAtLeast("own-2-1", "own-3-1")
    assertThat(me.blob(Fx.key(320), SegmentClass.THUMB_M)).isEqualTo(Fx.pixels(SegmentClass.THUMB_M, 320, "b"))
    assertThat(me.replica.locate(Fx.key(320).short, SegmentClass.HQ)).isNull()
    assertThat(me.replica.locate(Fx.key(320).short, SegmentClass.ANALYSIS)).isNull()
  }
}
