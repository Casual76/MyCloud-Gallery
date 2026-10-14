package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.scan.ScanConfig
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La scansione di una share sul NAS sintetico (docs/fase4-progetto.md §2.3, "Fatto quando" di P5): prima scansione dai
 * piu' recenti con ripresa a meta', verifica senza novita' senza letture di file, E-N5 (cartelle enormi a pagine), E-M12,
 * E-S11 (la scansione non scrive sul NAS), E-R15 (scopo "background").
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScanFirstAndVerifyTest {

  private lateinit var h: ScanHarness

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  /** Il profilo piccolo nella share privata `test`: 303 media, 2 non-media, 1 in arrivo, 2 cartelle di sistema. */
  private fun piccolo(shuffle: Random? = null, config: ScanConfig = ScanConfig(), latency: (Op) -> Long = { 0L }) {
    h = ScanHarness(shuffle = shuffle, config = config, latency = latency)
    h.synthetic.populate(SyntheticNas.Profile.PICCOLO, privateShare = "test", commonShare = "test-comune")
  }

  @Test
  fun laPrimaScansioneRilevaIMediaEContaIlResto() = runTest {
    piccolo()
    val before = h.nas.fileCount("test")
    val report = h.scan(ScanMode.Full)

    assertThat(report.complete).isTrue()
    assertThat(report.resumed).isFalse()
    assertThat(report.stoppedBy).isNull()
    // Camera 120 + altre cartelle 160 + 20 video + 3 nomi particolari.
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(303)
    assertThat(report.files).isEqualTo(305) // i 303 media e i due non-media (zero byte, byte casuali)
    assertThat(report.detected).isEqualTo(304) // il file a zero byte non si apre
    assertThat(report.notMedia).isEqualTo(2) // E-M12
    assertThat(report.arriving).isEqualTo(1) // `in arrivo.mp4.part`, E-N4
    assertThat(report.skippedSystem).isEqualTo(2) // `.recycle` e `@eaDir`, E-N16
    assertThat(report.unchanged).isEqualTo(0)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(report.freeze).isNull()
    assertThat(h.store.appliedMetadata).hasSize(303)
    // E-S11: la scansione non scrive sul NAS.
    assertThat(h.nas.fileCount("test")).isEqualTo(before)
    assertThat(h.scanner.status.value).isInstanceOf(NasScanStatus.Finished::class.java)
  }

  @Test
  fun nelCatalogoNonEntraNienteDiCioCheNonEUnMedia() = runTest {
    piccolo()
    h.scan()
    val keys = listOf("Download/vuoto.jpg", "Download/finto.jpg", "Download/in arrivo.mp4.part", ".recycle/vecchia.jpg", "@eaDir/SYNOFILE_THUMB_M.jpg")
    keys.forEach { assertThat(h.store.stateOf(h.keyOf(it))).isNull() }
    assertThat(h.store.stateOf(h.keyOf("Download/Città 🌅 al tramonto.jpg"))).isNotNull()
    // Le cartelle di sistema non si elencano nemmeno.
    assertThat(h.reader.listed).containsNoneOf(".recycle", "@eaDir")
  }

  @Test
  fun laSorgenteSiRiconosceDalNomeEDallaCartellaCosiCommeSulTelefono() = runTest {
    piccolo()
    h.scan()
    suspend fun source(path: String) = h.catalog.media(checkNotNull(h.store.mediaIdAt(h.keyOf(path))))!!.source
    assertThat(source("WhatsApp Images/IMG-20230115-WA00000.jpg")).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Screenshots/Screenshot_2024-05-02-10-00-1.png")).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(source("Pixel/PXL_20240601_3.jpg")).isEqualTo(MediaSource.OTHER)
  }

  @Test
  fun laVerificaSenzaNovitaNonLeggeUnFile() = runTest {
    piccolo(shuffle = Random(11))
    val first = h.scan(ScanMode.Full)
    val opens = h.reader.opens
    val reads = h.reader.reads
    val bytes = h.nas.bytesRead.get()
    val listsBefore = h.reader.lists

    val report = h.scan(ScanMode.Verify)
    assertThat(report.detected).isEqualTo(0)
    assertThat(report.adopted).isEqualTo(0)
    assertThat(report.moved).isEqualTo(0)
    assertThat(report.bytesRead).isEqualTo(0)
    assertThat(report.unchanged).isEqualTo(303)
    assertThat(report.complete).isTrue()
    assertThat(report.vanished).isEqualTo(0)
    // Nessuna apertura e nessun byte: solo gli elenchi, uno per cartella.
    assertThat(h.reader.opens).isEqualTo(opens)
    assertThat(h.reader.reads).isEqualTo(reads)
    assertThat(h.nas.bytesRead.get()).isEqualTo(bytes)
    assertThat(h.reader.lists - listsBefore).isEqualTo(first.folders)
    // E-R15: tutto in background.
    assertThat(h.reader.purposes).containsExactly(Purpose.BACKGROUND)
  }

  @Test
  fun lePagineDegliElenchiSonoQuelleChiesteENonSiTieneLaListaIntera() = runTest {
    piccolo(shuffle = Random(5), config = ScanConfig(pageSize = 50))
    h.scan()
    assertThat(h.reader.pageSizes.max()).isAtMost(50)
    assertThat(h.reader.pageSizes.count { it == 50 }).isAtLeast(2) // Camera ha 120 file: 50 + 50 + 20
  }

  @Test
  fun unaCartellaDa8910FileSiLeggeAPagineDa1000EVieneRegistrataALotti() = runTest(timeout = 10.minutes) {
    h = ScanHarness(shuffle = Random(3), writeMetadata = false)
    repeat(8_910) { i -> h.photo("Camera/IMG_$i.jpg", ageMs = (i + 1) * 600_000L + 3_600_000L, size = 1_800_000, seed = i.toLong()) }
    val report = h.scan(ScanMode.Full)
    assertThat(report.complete).isTrue()
    assertThat(report.detected).isEqualTo(8_910)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(8_910)
    assertThat(h.reader.pageSizes.max()).isAtMost(1_000)
    assertThat(h.reader.pageSizes.sum()).isEqualTo(8_910 + 1) // i file di Camera e la voce `Camera` nella radice
    assertThat(h.reader.pageSizes.size).isAtLeast(9) // 8.910 voci a pagine da 1.000
    // Il rilevamento legge testa e coda, mai un file intero: ~190 KiB a foto da 1,8 MB.
    assertThat(report.bytesRead / 8_910).isAtMost(200L * 1024)
  }

  @Test
  fun laPrimaScansioneVaDaiPiuRecentiEPuoRiprendereAMeta() = runTest {
    // Un'apertura costa 10 ms virtuali; il budget della prima tappa basta per ~60 file.
    piccolo(
      shuffle = Random(9),
      config = ScanConfig(batchSize = 10, detectConcurrency = 1, cursorSaveEvery = 1),
      latency = { if (it == Op.OPEN) 10L else 0L },
    )
    val firstTick = h.scan(ScanMode.Full, budgetMs = 600)
    assertThat(firstTick.stoppedBy).isEqualTo(ScanStop.BUDGET)
    assertThat(firstTick.complete).isFalse()
    assertThat(firstTick.resumed).isFalse()
    assertThat(firstTick.detected).isIn(50..70)

    // Le cartelle vanno dalla piu' recente: prima `Download` (ha il file di adesso), poi `Camera`.
    assertThat(h.reader.listed.take(3)).containsExactly("", "Download", "Camera").inOrder()
    // Dentro Camera la tappa ha fatto i file piu' recenti, senza buchi: IMG_..._100000, 100001, ...
    val recorded = (0 until 120).filter { h.store.stateOf(h.keyOf(cameraName(it))) != null }
    assertThat(recorded).isEqualTo((0 until recorded.size).toList())
    assertThat(recorded.size).isIn(10..40)

    val opensAfterFirst = h.reader.opens
    val secondTick = h.scan(ScanMode.Full, budgetMs = 3_600_000)
    assertThat(secondTick.resumed).isTrue()
    assertThat(secondTick.complete).isTrue()
    assertThat(secondTick.stoppedBy).isNull()
    // Quello che la prima tappa aveva fatto non si rilegge: nessun file e' stato aperto due volte (304 letti in tutto).
    assertThat(h.reader.opens).isEqualTo(304)
    assertThat(h.reader.opens - opensAfterFirst).isEqualTo(304 - firstTick.detected)
    // Camera era a meta': i file che la prima tappa aveva fatto si confermano, senza riaprirli.
    assertThat(secondTick.unchanged).isAtLeast(recorded.size)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(303)
    assertThat(secondTick.vanished).isEqualTo(0)
  }

  private fun cameraName(i: Int) = "Camera/IMG_${20240101 + i % 365}_${100000 + i}.jpg"

  @Test
  fun loStatoDellaScansioneRaccontaLAvanzamentoEFinisceConIlResoconto() = runTest {
    piccolo()
    val seen = ArrayList<NasScanStatus>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { h.scanner.status.collect { seen += it } }
    val report = h.scan(ScanMode.Full)
    job.cancel()
    val scanning = seen.filterIsInstance<NasScanStatus.Scanning>()
    assertThat(scanning).isNotEmpty()
    assertThat(scanning.last().foldersDone).isAtLeast(5)
    assertThat(scanning.map { it.detected }).isInOrder() // non decresce mai
    assertThat(seen.last()).isEqualTo(NasScanStatus.Finished(report))
  }
}
