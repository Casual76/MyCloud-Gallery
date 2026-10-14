package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il NAS sintetico con le proporzioni dell'inventario (docs/inventario-nas.md §4-bis, fase4-progetto §5.3): la share
 * privata `alessio` (12 mila file, una cartella da 8.910) e la comune `NAS` (20 mila file, 2.356 cartelle di musica con le
 * copertine). I numeri finiscono nel resoconto di P5; le soglie del congelamento si provano alla scala vera.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticNasScaleTest {

  private val open = ArrayList<ScanHarness>()
  private val listsMark = HashMap<ScanHarness, Int>()
  private val monoMark = HashMap<ScanHarness, Long>()

  private fun mark(h: ScanHarness) {
    listsMark[h] = h.reader.lists
    monoMark[h] = h.mono
  }

  @After
  fun close() {
    open.forEach { it.close() }
  }

  /** Latenze dell'inventario a tempo virtuale: ~19 ms per cartella, accessi casuali da 64 KB 15-21 ms. */
  private val latency: (Op) -> Long = {
    when (it) {
      Op.LIST -> 19L
      Op.OPEN -> 6L
      Op.READ -> 17L
    }
  }

  /** Il resoconto di un giro, con i tempi virtuali di quel giro (dall'ultima chiamata a [mark]). */
  private fun line(label: String, h: ScanHarness, r: ScanReport) {
    val listMs = (h.reader.lists - listsMark[h]!!) * 19L
    val readMs = (h.mono - monoMark[h]!!) - listMs
    println(
      "NUMERI $label: cartelle=${r.folders} file=${r.files} rilevati=${r.detected} adottati=${r.adopted} spostati=${r.moved} " +
        "invariati=${r.unchanged} copertine=${r.albumCovers} nonMedia=${r.notMedia} sistema=${r.skippedSystem} " +
        "elenchi=${h.reader.lists} aperture=${h.reader.opens} letture=${h.reader.reads} byte=${r.bytesRead} " +
        "tempoElenchi=${listMs / 1000.0}s tempoLetture1flusso=${readMs / 1000.0}s tempoLetture4flussi=${"%.1f".format(readMs / 1000.0 / 2.2)}s " +
        "completo=${r.complete}",
    )
  }

  @Test
  fun lAreaPrivataDiAlessioDa12MilaFileConUnaCartellaDa8910() = runTest(timeout = 20.minutes) {
    val h = ScanHarness(shuffle = Random(1), latency = latency, writeMetadata = false).also { open += it }
    h.synthetic.populate(SyntheticNas.Profile.INVENTARIO, privateShare = "test", commonShare = "test-comune")
    val inShare = h.nas.fileCount("test")

    mark(h)
    val first = h.scan(ScanMode.Full)
    line("alessio prima scansione", h, first)
    assertThat(first.complete).isTrue()
    // 12.244 media nell'inventario; qui 12.247 piu' i due file non media.
    assertThat(h.store.knownCount(h.me, "test")).isIn(12_200..12_300)
    assertThat(inShare).isIn(12_250..12_320)
    assertThat(first.folders).isEqualTo(8) // la radice e le 7 cartelle (le due di sistema non si elencano)
    assertThat(first.skippedSystem).isEqualTo(2)
    assertThat(first.notMedia).isEqualTo(2)
    // Ogni foto costa testa piu' coda (~190 KB): 12 mila file, ~2 GB di lettura invece di 279 GB.
    assertThat(first.bytesRead / first.detected).isAtMost(200L * 1024)
    assertThat(h.reader.pageSizes.max()).isAtMost(1_000)

    // La verifica senza novita': solo elenchi, nessuna lettura.
    mark(h)
    val opens = h.reader.opens
    val lists = h.reader.lists
    val verify = h.scan(ScanMode.Verify)
    line("alessio verifica senza novita", h, verify)
    assertThat(verify.detected).isEqualTo(0)
    assertThat(verify.bytesRead).isEqualTo(0)
    assertThat(h.reader.opens).isEqualTo(opens)
    assertThat(h.reader.lists - lists).isEqualTo(8)
    assertThat(verify.unchanged).isEqualTo(h.store.knownCount(h.me, "test"))

    // E-N1 alla scala vera: la cartella da 8.910 file rinominata dal PC.
    h.synthetic.renameFolder("test", "Camera", "Camera 2024")
    mark(h)
    val renamed = h.scan(ScanMode.Verify)
    line("alessio Camera rinominata (8.910 file)", h, renamed)
    assertThat(renamed.moved).isEqualTo(8_910)
    assertThat(renamed.detected).isEqualTo(0)
    assertThat(renamed.bytesRead).isEqualTo(0)
    assertThat(renamed.vanished).isEqualTo(0)
    assertThat(renamed.freeze).isNull()

    // D10 alla scala vera: spariti alla soglia si' e sotto no (la soglia segue i file presenti, 5%).
    val camera = h.nas.let { n ->
      val names = ArrayList<String>()
      n.reader.list("test", dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse("Camera 2024"), dev.pampa.mycloudgallery.core.nas.contract.Purpose.BACKGROUND, 1000) { page ->
        page.forEach { names += "Camera 2024/${it.name}" }
      }
      names.sorted()
    }
    var cursor = 0
    for (step in listOf("sotto", "sotto", "alla soglia")) {
      val known = h.store.countPresent(h.me, "test")
      val threshold = MassChangeRule.threshold(known)
      val count = if (step == "alla soglia") threshold else threshold - 1
      camera.subList(cursor, cursor + count).forEach { h.synthetic.deleteFromPc("test", it) }
      cursor += count
      val r = h.scan(ScanMode.Verify)
      println("NUMERI soglia: presenti=$known soglia=$threshold spariti=$count -> ${if (r.freeze != null) "CONGELATA" else "vanished=${r.vanished}"}")
      if (step == "alla soglia") {
        assertThat(r.freeze).isNotNull()
        assertThat(r.vanished).isEqualTo(0)
      } else {
        assertThat(r.freeze).isNull()
        assertThat(r.vanished).isEqualTo(count)
      }
    }
  }

  @Test
  fun lAreaComuneDi20MilaFileConLeCopertineDegliAlbum() = runTest(timeout = 20.minutes) {
    val priv = ScanHarness(share = "test", writeMetadata = false).also { open += it }
    priv.synthetic.populate(SyntheticNas.Profile.INVENTARIO, privateShare = "test", commonShare = "test-comune")
    val h = ScanHarness(
      share = "test-comune",
      sharedNas = priv.nas,
      clockSource = priv,
      shuffle = null,
      latency = latency,
      writeMetadata = false,
    ).also { open += it }
    val inShare = h.nas.fileCount("test-comune")

    mark(h)
    val first = h.scan(ScanMode.Full)
    line("NAS comune prima scansione", h, first)
    assertThat(first.complete).isTrue()
    assertThat(inShare).isIn(20_000..21_000)
    // 2.356 cartelle nell'inventario; qui la radice piu' album, artisti, foto e film.
    assertThat(first.folders).isIn(2_340..2_380)
    // I media veri sono 582 (137 PNG + 20 + 12 + 33 + 371 MP4 + 9 MKV nell'inventario); le copertine non entrano.
    // Due di questi hanno la data di modifica di adesso (il primo scatto e il primo film): in arrivo, E-N4.
    assertThat(first.arriving).isEqualTo(2)
    assertThat(h.store.knownCount(h.me, "test-comune")).isEqualTo(580)
    assertThat(first.albumCovers).isIn(5_000..5_300)
    assertThat(first.detected).isEqualTo(580)

    mark(h)
    val verify = h.scan(ScanMode.Verify)
    line("NAS comune verifica senza novita", h, verify)
    assertThat(verify.detected).isEqualTo(0)
    assertThat(verify.bytesRead).isEqualTo(0)
    assertThat(verify.unchanged).isEqualTo(580)
    assertThat(h.reader.pageSizes.max()).isAtMost(1_000)
  }
}
