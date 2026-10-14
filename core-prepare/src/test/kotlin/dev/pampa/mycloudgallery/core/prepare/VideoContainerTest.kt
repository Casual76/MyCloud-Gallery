package dev.pampa.mycloudgallery.core.prepare

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.video.BlockCache
import dev.pampa.mycloudgallery.core.prepare.video.FrameOrientation
import dev.pampa.mycloudgallery.core.prepare.video.FrameTimes
import dev.pampa.mycloudgallery.core.prepare.video.Mp4Scout
import dev.pampa.mycloudgallery.fixtures.PrepareFixtures
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * L'indice di un video letto senza scaricarlo (E-M7): la cache a blocchi, l'esploratore del `moov`, gli istanti dei
 * fotogrammi e la loro rotazione. Tutto su byte veri: nessun decoder serve per questo.
 */
@RunWith(RobolectricTestRunner::class)
class VideoContainerTest {

  private val block = 256 * 1024

  private fun cache(bytes: CountingBytes) = BlockCache(bytes, block, 16)

  private fun cache(bytes: VirtualBytes) = BlockCache(bytes, block, 16)

  private fun spec(configure: PrepareFixtures.VideoSpec.() -> Unit = {}) = PrepareFixtures.VideoSpec().apply(configure)

  // --- BlockCache --------------------------------------------------------------------------------------------------

  @Test
  fun laCacheLeggeABlocchiAllineatiENonRileggeLoStessoBlocco() = runTest {
    val data = Random(1).nextBytes(2 * 1024 * 1024 + 1000)
    val bytes = CountingBytes(data)
    val cache = cache(bytes)
    // Tre letture piccole nello stesso blocco: una sola richiesta all'origine.
    assertThat(cache.read(10, 100).toList()).isEqualTo(data.copyOfRange(10, 110).toList())
    assertThat(cache.read(5_000, 50).toList()).isEqualTo(data.copyOfRange(5_000, 5_050).toList())
    cache.read(200_000, 10)
    assertThat(bytes.reads).containsExactly(0L until block.toLong())
    assertThat(cache.originBytes).isEqualTo(block.toLong())
    // Una lettura a cavallo di due blocchi ne chiede uno solo in piu'.
    val straddle = cache.read(block - 10L, 20)
    assertThat(straddle.toList()).isEqualTo(data.copyOfRange(block - 10, block + 10).toList())
    assertThat(cache.originReads).isEqualTo(2)
  }

  @Test
  fun inFondoAlFileLaCacheDaIlResto_EOltreIlFileNiente() = runTest {
    val data = Random(2).nextBytes(block + 1000)
    val cache = cache(CountingBytes(data))
    assertThat(cache.read(block.toLong(), 5000).toList()).isEqualTo(data.copyOfRange(block, block + 1000).toList())
    assertThat(cache.read(data.size.toLong(), 10)).isEmpty()
    assertThat(cache.read(data.size + 100L, 10)).isEmpty()
    // readAt di MediaDataSource: -1 a fine file.
    assertThat(cache.readBlocking(data.size.toLong(), ByteArray(10), 0, 10)).isEqualTo(-1)
    val buffer = ByteArray(2000)
    assertThat(cache.readBlocking(block.toLong() + 500, buffer, 0, 2000)).isEqualTo(500)
  }

  @Test
  fun laCacheTieneIUltimiSediciBlocchiEIlConteggioEOnesto() = runTest {
    val data = Random(3).nextBytes(block * 40)
    val bytes = CountingBytes(data)
    val cache = cache(bytes)
    for (i in 0 until 20) cache.read(i.toLong() * block, 10)
    assertThat(cache.originReads).isEqualTo(20)
    // I primi quattro blocchi sono stati espulsi (ne restano 16): rileggerli si paga di nuovo.
    cache.read(0, 10)
    assertThat(cache.originReads).isEqualTo(21)
    // Gli ultimi no.
    cache.read(19L * block, 10)
    assertThat(cache.originReads).isEqualTo(21)
    assertThat(cache.originBytes).isEqualTo(21L * block)
  }

  @Test
  fun leLettureAnnullateEGliErroriDelNasSiFermanoERestanoRegistrati() = runTest {
    val cache = cache(CountingBytes(ByteArray(block * 2)))
    cache.cancelled = true
    assertThrows(IOException::class.java) { runBlockingRead(cache) }

    val failing = BlockCache(
      object : dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes {
        override val size: Long = 10_000_000

        override suspend fun read(range: LongRange): ByteArray = throw IOException("persa")

        override fun dataSource() = throw UnsupportedOperationException()
      },
      block, 16,
    )
    assertThrows(IOException::class.java) { runBlockingRead(failing) }
    assertThat(failing.failure).isNotNull()
  }

  private fun runBlockingRead(cache: BlockCache) = kotlinx.coroutines.runBlocking { cache.read(0, 10) }

  // --- Mp4Scout: l'indice senza scaricare -------------------------------------------------------------------------------

  @Test
  fun lIndiceInCodaDiUnVideoDa90SecondiSiLeggeSenzaScaricarlo() = runTest {
    val mp4 = PrepareFixtures.video(spec())
    val bytes = VirtualBytes(mp4)
    val facts = Mp4Scout.scout(cache(bytes), maxIndexBytes = 64L * 1024 * 1024)

    assertThat(facts.isoBmff).isTrue()
    assertThat(facts.hasMoov).isTrue()
    // Il flusso audio viene prima nel file: l'esploratore sceglie il `vide`.
    assertThat(facts.sampleEntry).isEqualTo("avc1")
    assertThat(facts.width).isEqualTo(1920)
    assertThat(facts.height).isEqualTo(1080)
    assertThat(facts.rotation).isEqualTo(0)
    assertThat(facts.durationMs).isEqualTo(90_000)
    // Il file pesa 21 MB e l'indice in coda ne vale un centesimo: si leggono l'inizio, le testate dei riquadri e il moov.
    assertThat(bytes.bytesRead).isAtMost(mp4.moovSize + 3L * block)
    assertThat(bytes.bytesRead).isLessThan(mp4.size() / 10)
  }

  @Test
  fun lIndiceInTestaSiLeggeUgualmente() = runTest {
    val mp4 = PrepareFixtures.video(spec { moovLast = false })
    val facts = Mp4Scout.scout(cache(VirtualBytes(mp4)), 64L * 1024 * 1024)
    assertThat(facts.hasMoov).isTrue()
    assertThat(facts.sampleEntry).isEqualTo("avc1")
    assertThat(facts.durationMs).isEqualTo(90_000)
  }

  @Test
  fun unVideoDa9GigabyteSiEsploraConPocheCentinaiaDiKilobyte() = runTest {
    // 2 ore a 30 fps, un fotogramma chiave da 1 MB ogni 2 s: oltre 4 GiB di mdat, quindi mdat a 64 bit e co64.
    val mp4 = PrepareFixtures.video(spec {
      durationMs = 7_200_000
      gopFrames = 60
      keyframeBytes = 1_000_000
      deltaFrameBytes = 27_000
    })
    assertThat(mp4.size()).isGreaterThan(4L * 1024 * 1024 * 1024)
    val bytes = VirtualBytes(mp4)
    val facts = Mp4Scout.scout(cache(bytes), 64L * 1024 * 1024)
    assertThat(facts.hasMoov).isTrue()
    assertThat(facts.durationMs).isEqualTo(7_200_000)
    assertThat(facts.sampleEntry).isEqualTo("avc1")
    // L'indice (moov) e' qualche megabyte: tutto il resto e' una frazione di millesimo del file.
    assertThat(bytes.bytesRead).isAtMost(mp4.moovSize + 4L * block)
    assertThat(bytes.bytesRead.toDouble() / mp4.size()).isLessThan(0.001)
  }

  @Test
  fun rotazioneEMarchioDelCodecSiLeggonoDalTkhdEDalloStsd() = runTest {
    for (rotation in listOf(0, 90, 180, 270)) {
      val facts = Mp4Scout.scout(cache(VirtualBytes(PrepareFixtures.video(spec { this.rotation = rotation; durationMs = 3_000 }))), 1L shl 26)
      assertWithMessage("rotazione $rotation").that(facts.rotation).isEqualTo(rotation)
      assertThat(facts.width).isEqualTo(1920)
    }
    for (entry in listOf("avc1", "hvc1", "av01", "vp09", "apv1")) {
      val facts = Mp4Scout.scout(cache(VirtualBytes(PrepareFixtures.video(spec { sampleEntry = entry; durationMs = 3_000 }))), 1L shl 26)
      assertThat(facts.sampleEntry).isEqualTo(entry)
    }
  }

  @Test
  fun unaRegistrazioneInterrottaOUnIndiceTroncatoNonHannoMoov() = runTest {
    val mp4 = PrepareFixtures.video(spec { durationMs = 3_000 })
    val whole = mp4.toBytes()
    // Batteria finita: i dati ci sono, l'indice no.
    val noMoov = whole.copyOf(mp4.moovOffset.toInt())
    val a = Mp4Scout.scout(cache(CountingBytes(noMoov)), 1L shl 26)
    assertThat(a.isoBmff).isTrue()
    assertThat(a.hasMoov).isFalse()
    // Il moov tagliato a meta'.
    val half = whole.copyOf((mp4.moovOffset + mp4.moovSize / 2).toInt())
    val b = Mp4Scout.scout(cache(CountingBytes(half)), 1L shl 26)
    assertThat(b.hasMoov).isFalse()
  }

  @Test
  fun unFileCheNonEIsoNonSiEsplora_EUnoMalformatoNonLanciaMai() = runTest {
    assertThat(Mp4Scout.scout(cache(CountingBytes("non sono un video".toByteArray())), 1L shl 26).isoBmff).isFalse()
    assertThat(Mp4Scout.scout(cache(CountingBytes(ByteArray(8))), 1L shl 26).isoBmff).isFalse()
    // Dopo un ftyp valido, il caos: lunghezze assurde, riquadri dentro riquadri, byte casuali.
    val valid = PrepareFixtures.video(spec { durationMs = 3_000 }).toBytes()
    val random = Random(7)
    repeat(300) {
      val fuzz = valid.copyOf()
      repeat(1 + random.nextInt(40)) { fuzz[random.nextInt(fuzz.size)] = random.nextInt(256).toByte() }
      // Qualunque cosa esca, non e' un'eccezione.
      Mp4Scout.scout(cache(CountingBytes(fuzz)), 1L shl 26)
    }
  }

  // --- Gli istanti dei fotogrammi ----------------------------------------------------------------------------------------

  @Test
  fun unFotogrammaOgniTrentaSecondiDa1A8PiuLaCopertina() {
    val spec = PrepSpec()
    // Un video breve ha un fotogramma chiave (a meta') e la copertina a mezzo secondo.
    FrameTimes.plan(1_000, spec, withKeys = true).let {
      assertThat(it.coverMs).isEqualTo(500)
      assertThat(it.all.size).isAtMost(2)
    }
    val twenty = FrameTimes.plan(20_000, spec, withKeys = true)
    assertThat(twenty.coverMs).isEqualTo(1_000)
    assertThat(twenty.keyMs).containsExactly(10_000L)
    // Un video da 75 s: tre fette da 25 s, un fotogramma al centro di ciascuna.
    assertThat(FrameTimes.plan(75_000, spec, withKeys = true).keyMs).containsExactly(12_500L, 37_500L, 62_500L).inOrder()
    // Oltre i quattro minuti sono otto, distribuiti su tutto il video (un'ora: uno ogni 7,5 minuti).
    val hour = FrameTimes.plan(3_600_000, spec, withKeys = true)
    assertThat(hour.keyMs).hasSize(8)
    assertThat(hour.keyMs.last()).isGreaterThan(3_000_000)
    assertThat(hour.all).hasSize(9)
    assertThat(hour.all).isInOrder()
    // Senza durata, solo la copertina; senza richiesta dei fotogrammi, solo la copertina.
    assertThat(FrameTimes.plan(0, spec, withKeys = true).all).containsExactly(0L)
    assertThat(FrameTimes.plan(600_000, spec, withKeys = false).keyMs).isEmpty()
  }

  @Test
  fun gliIstantiNonSuperanoMaiLaFineENonSiRipetono() {
    for (duration in listOf(300L, 999L, 1_000L, 5_000L, 29_999L, 30_000L, 31_000L, 90_000L, 5_000_000L)) {
      val plan = FrameTimes.plan(duration, PrepSpec(), withKeys = true)
      for (t in plan.all) assertWithMessage("durata $duration").that(t).isIn(0L..duration)
      assertThat(plan.all).containsNoDuplicates()
      assertThat(plan.all.size).isAtMost(9)
    }
  }

  // --- La rotazione dei fotogrammi ---------------------------------------------------------------------------------------

  @Test
  fun laRotazioneSiDecideDalleProporzioniNonDaUnaSupposizione() {
    // Un video verticale memorizzato 1920x1080 con rotazione 90.
    // Fotogramma arrivato non ruotato (landscape): lo ruotiamo noi.
    FrameOrientation.decide(1920, 1080, 90, 1024, 576, learned = null).let {
      assertThat(it.degrees).isEqualTo(90)
      assertThat(it.platformRotated).isFalse()
    }
    // Fotogramma gia' ruotato dal sistema (portrait): non si tocca.
    FrameOrientation.decide(1920, 1080, 90, 576, 1024, learned = null).let {
      assertThat(it.degrees).isEqualTo(0)
      assertThat(it.platformRotated).isTrue()
    }
    // 270 allo stesso modo.
    assertThat(FrameOrientation.decide(1920, 1080, 270, 1024, 576, null).degrees).isEqualTo(270)
    assertThat(FrameOrientation.decide(1920, 1080, 270, 576, 1024, null).degrees).isEqualTo(0)
    // Nessuna rotazione dichiarata: niente da fare.
    assertThat(FrameOrientation.decide(1920, 1080, 0, 1024, 576, null).degrees).isEqualTo(0)
  }

  @Test
  fun a180GradiELaRotazioneSiUsaQuelloCheSiEVistoAltrove() {
    // Le proporzioni non dicono nulla a 180: senza esperienza si suppone che il sistema ruoti, con l'esperienza si segue.
    assertThat(FrameOrientation.decide(1920, 1080, 180, 1024, 576, learned = null).degrees).isEqualTo(0)
    assertThat(FrameOrientation.decide(1920, 1080, 180, 1024, 576, learned = true).degrees).isEqualTo(0)
    assertThat(FrameOrientation.decide(1920, 1080, 180, 1024, 576, learned = false).degrees).isEqualTo(180)
    // Un video quadrato a 90 gradi ha lo stesso problema.
    assertThat(FrameOrientation.decide(1080, 1080, 90, 512, 512, learned = false).degrees).isEqualTo(90)
    assertThat(FrameOrientation.decide(1080, 1080, 90, 512, 512, learned = null).degrees).isEqualTo(0)
  }
}
