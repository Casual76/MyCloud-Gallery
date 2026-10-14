package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanSettings
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.scan.ScanConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ripresa e tetti (docs/fase4-progetto.md §4.3: "ogni passo in piu' tappe e' idempotente e ha un test di ripresa dopo un
 * guasto a ogni chiamata"; D22: tappe da 8 minuti e 50 MB fuori casa).
 */
@RunWith(AndroidJUnit4::class)
class ScanResilienceTest {

  private lateinit var h: ScanHarness

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  /** 11 cartelle, 21 foto: due livelli di annidamento, fratelli prima e dopo la catena di ripresa. */
  private fun tree() {
    var seed = 1L
    fun put(path: String) = h.photo(path, seed = seed++)
    put("r1.jpg")
    (1..5).forEach { put("Foto/a$it.jpg") }
    (1..4).forEach { put("Foto/Sub/b$it.jpg") }
    put("Foto/Sub/Z/z1.jpg")
    (1..3).forEach { put("Altro/c$it.jpg") }
    put("Altro/Alfa/a.jpg")
    (1..3).forEach { put("Altro/Deep/d$it.jpg") }
    put("Altro/Deep/Er/e1.jpg")
    put("Beta/1/x/y.jpg")
    put("Beta/1/w.jpg")
  }

  private val folders = 11
  private val files = 21

  private fun assertAllThereOnce() {
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(files)
    assertThat(h.store.vanishedTotal).isEqualTo(0)
    val ids = HashSet<Any>()
    for (p in listOf("r1.jpg", "Foto/a1.jpg", "Foto/Sub/Z/z1.jpg", "Altro/Alfa/a.jpg", "Altro/Deep/Er/e1.jpg", "Beta/1/x/y.jpg", "Beta/1/w.jpg")) {
      assertThat(h.store.mediaIdAt(h.keyOf(p))).isNotNull()
      assertThat(ids.add(h.store.mediaIdAt(h.keyOf(p))!!)).isTrue()
    }
  }

  @Test
  fun riprendeDaOgniCartellaSenzaRifareNeSaltareNulla() = runTest {
    for (k in 1..folders + 1) {
      h = ScanHarness(config = ScanConfig(cursorSaveEvery = 1), latency = { if (it == Op.LIST) 10L else 0L }, writeMetadata = false)
      tree()
      val first = h.scan(ScanMode.Full, budgetMs = 10L * k)
      val second = h.scan(ScanMode.Full)

      assertThat(second.complete).isTrue()
      assertThat(second.stoppedBy).isNull()
      if (first.stoppedBy != null) {
        assertThat(first.stoppedBy).isEqualTo(ScanStop.BUDGET)
        assertThat(first.complete).isFalse()
        assertThat(second.resumed).isTrue()
      }
      // Ogni foto aperta una sola volta in tutto, nessuna assenza, nessun doppione.
      assertThat(h.reader.opens).isEqualTo(files)
      assertThat(first.detected + second.detected).isEqualTo(files)
      assertAllThereOnce()
      // Tutte le cartelle sono state elencate; si rifanno solo la catena di ripresa (al massimo 4) e la cartella interrotta.
      assertWithMessage("k=$k elenchi=${h.reader.listed}").that(h.reader.listed.toSet()).hasSize(folders)
      // Un giro finito subito (k = 12) fa il suo secondo giro intero da capo: le cartelle si rielencano tutte.
      val relisted = if (first.stoppedBy == null) folders else 5
      assertWithMessage("k=$k elenchi=${h.reader.listed}").that(h.reader.listed.size).isAtMost(folders + relisted)
      h.close()
    }
  }

  @Test
  fun riprendeDopoUnGuastoDelNasAOgniChiamata() = runTest {
    // Prima, quante chiamate al NAS fa un giro intero.
    h = ScanHarness(writeMetadata = false)
    tree()
    var calls = 0
    h.nas.fault = FakeNasFault { _, _, _ -> calls++; null }
    h.scan()
    val total = calls
    h.close()
    assertThat(total).isAtLeast(folders + files)

    for (n in 1..total + 1) {
      h = ScanHarness(writeMetadata = false)
      tree()
      var count = 0
      h.nas.fault = FakeNasFault { _, _, _ -> if (++count == n) NasError.Timeout else null }
      var tries = 0
      var report = h.scan()
      // Un guasto ferma il giro (NAS irraggiungibile) o fa saltare un file (si riprova): al massimo tre giri.
      while ((!report.complete || h.store.knownCount(h.me, "test") < files) && tries++ < 4) report = h.scan()
      assertThat(report.complete).isTrue()
      assertAllThereOnce()
      h.close()
    }
  }

  @Test
  fun fuoriCasaIlRilevamentoLeggeAlMassimo50MBPerGiroEIlRestoAspetta() = runTest {
    h = ScanHarness(config = ScanConfig(batchSize = 50), writeMetadata = false, settings = ScanSettings(detectionByteCap = 50_000_000))
    repeat(600) { h.photo("Foto/f$it.jpg", seed = it.toLong()) }
    val away = h.scan()
    assertThat(away.stoppedBy).isEqualTo(ScanStop.BYTE_CAP)
    assertThat(away.complete).isFalse()
    assertThat(away.bytesRead).isAtMost(50_000_000)
    assertThat(h.nas.bytesRead.get()).isAtMost(50_000_000)
    assertThat(away.bytesRead).isAtLeast(40_000_000) // il tetto si usa quasi tutto
    assertThat(away.detected).isIn(200..300)
    val doneAway = h.store.knownCount(h.me, "test")
    assertThat(doneAway).isAtLeast(200)
    assertThat(doneAway).isAtMost(300)

    // Di nuovo fuori casa: altri 50 MB, e la prima tappa non si rifa'.
    val away2 = h.scan()
    assertThat(away2.stoppedBy).isEqualTo(ScanStop.BYTE_CAP)
    assertThat(away2.resumed).isTrue()
    assertThat(away2.bytesRead).isAtMost(50_000_000)
    assertThat(h.store.knownCount(h.me, "test")).isGreaterThan(doneAway)

    // A casa non c'e' tetto: il resto si legge e il giro finisce.
    h.settings = ScanSettings(detectionByteCap = null)
    val home = h.scan()
    assertThat(home.complete).isTrue()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(600)
    // Nessuna foto letta due volte, tranne al piu' quelle rimaste a meta' quando il tetto e' arrivato (una per lettura in parallelo).
    assertThat(h.nas.opens.get()).isAtMost(600 + 2 * 4)
  }

  @Test
  fun ilTettoDelGiroNonSiRaggiungeConLaVerificaSenzaNovita() = runTest {
    h = ScanHarness(writeMetadata = false)
    repeat(100) { h.photo("Foto/f$it.jpg", seed = it.toLong()) }
    h.scan()
    h.settings = ScanSettings(detectionByteCap = 0)
    val report = h.scan()
    assertThat(report.stoppedBy).isNull()
    assertThat(report.complete).isTrue()
    assertThat(report.bytesRead).isEqualTo(0)
  }

  @Test
  fun laMiniaturaExifInunFileRealeDiventaLaProvvisoriaDellaReplica() = runTest {
    val thumb = Jpegs.thumbnail(160, 120, padding = 2_000)
    h = ScanHarness(extractor = scripted { _, _ -> Extraction.Read(MediaMetadata(width = 4000, height = 3000), MediaKind.PHOTO) })
    h.nas.putFile("test", "Foto/vera.jpg", NasContent.of(Jpegs.withExif(4000, 3000, thumb)), h.nasNow - DAY)
    h.scan()
    val media = h.catalog.media(checkNotNull(h.store.mediaIdAt(h.keyOf("Foto/vera.jpg"))))!!
    assertThat(h.replica.get(ContentKey.of(media.currentVersion.fingerprint).short)).isEqualTo(thumb)
  }
}
