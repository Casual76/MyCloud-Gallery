package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.dataset.PlacesOpenResult
import dev.pampa.mycloudgallery.core.places.dataset.PlacesPin
import dev.pampa.mycloudgallery.core.places.dataset.PlacesStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** L'estrazione dell'asset nella cartella dell'app: impronta, scrittura atomica, riuso, riparazione. */
class PlacesStoreTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val bytes = TestData.miniBytes
  private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private val pin = PlacesPin(version = 20250214, sha256 = sha, sizeBytes = bytes.size.toLong())

  private fun asset(content: ByteArray = bytes): () -> InputStream? = { ByteArrayInputStream(content) }

  private fun opened(result: PlacesOpenResult) = (result as PlacesOpenResult.Opened).data

  @Test
  fun senzaImprontaNellCodiceNonCeDatasetReale() {
    val dir = File(temp.root, "places")
    assertThat(PlacesStore.open(null, asset(), dir)).isEqualTo(PlacesOpenResult.NotBundled)
    assertThat(dir.exists()).isFalse()
  }

  @Test
  fun laPrimaVoltaEstraeEdEPronto() {
    val dir = File(temp.root, "places")
    val data = opened(PlacesStore.open(pin, asset(), dir))
    assertThat(dir.listFiles()!!.map { it.name }).containsExactly("places-20250214.mcgp1")
    assertThat(File(dir, "places-20250214.mcgp1").readBytes()).isEqualTo(bytes)
    assertThat(data.nearest(GeoPoint(41.8902, 12.4922))!!.place.name).isEqualTo("Roma")
    assertThat(data.dataset).isEqualTo(20250214)
  }

  @Test
  fun levolteDopoNonRileggeLAsset() {
    val dir = File(temp.root, "places")
    opened(PlacesStore.open(pin, asset(), dir))
    // L'asset non c'e' piu': il file gia' estratto basta.
    val again = PlacesStore.open(pin, { null }, dir)
    assertThat(again).isInstanceOf(PlacesOpenResult.Opened::class.java)
  }

  @Test
  fun unAssetDiversoDaQuelloFissatoNonDaMaiLuoghi() {
    val dir = File(temp.root, "places")
    val tampered = bytes.copyOf().also { it[it.size / 3] = (it[it.size / 3] + 1).toByte() }
    val result = PlacesStore.open(pin, asset(tampered), dir)
    assertThat(result).isInstanceOf(PlacesOpenResult.Failed::class.java)
    assertThat((result as PlacesOpenResult.Failed).reason).isEqualTo(PlacesOpenResult.Reason.HASH_MISMATCH)
    // Niente a meta': ne' il file ne' il temporaneo.
    assertThat(dir.listFiles()!!.toList()).isEmpty()
    val shorter = PlacesStore.open(pin, asset(bytes.copyOf(bytes.size - 10)), dir)
    assertThat((shorter as PlacesOpenResult.Failed).reason).isEqualTo(PlacesOpenResult.Reason.HASH_MISMATCH)
  }

  @Test
  fun senzaAssetCeUnMotivo() {
    val result = PlacesStore.open(pin, { null }, File(temp.root, "places"))
    assertThat((result as PlacesOpenResult.Failed).reason).isEqualTo(PlacesOpenResult.Reason.ASSET_MISSING)
  }

  @Test
  fun unFileEstrattoDanneggiatoSiRiestrae() {
    val dir = File(temp.root, "places")
    val first = opened(PlacesStore.open(pin, asset(), dir))
    val target = File(dir, "places-20250214.mcgp1")
    // Si danneggia un byte nel file estratto (stessa dimensione): il CRC-32 lo scopre.
    RandomAccessFile(target, "rw").use { file ->
      file.seek((bytes.size / 2).toLong())
      val original = file.read()
      file.seek((bytes.size / 2).toLong())
      file.write(original xor 0x55)
    }
    // Con il file mappato di prima ancora vivo, Windows non lascia cancellare il file: si prova una cartella nuova
    // per la parte di "riestrazione", e qui si verifica soltanto che il danno viene visto.
    val damaged = PlacesStore.open(pin, { null }, dir)
    assertThat(damaged).isInstanceOf(PlacesOpenResult.Failed::class.java)
    assertThat(first.placeCount).isEqualTo(opened(PlacesStore.open(pin, asset(), File(temp.root, "altra"))).placeCount)
  }

  @Test
  fun unFileTroncatoSiRiestrae() {
    val dir = File(temp.root, "places").also { it.mkdirs() }
    File(dir, "places-20250214.mcgp1").writeBytes(bytes.copyOf(bytes.size / 2))
    val data = opened(PlacesStore.open(pin, asset(), dir))
    assertThat(data.placeCount).isGreaterThan(400)
    assertThat(File(dir, "places-20250214.mcgp1").length()).isEqualTo(bytes.size.toLong())
  }

  @Test
  fun leVersioniVecchieSiCancellano() {
    val dir = File(temp.root, "places").also { it.mkdirs() }
    File(dir, "places-20200101.mcgp1").writeBytes(byteArrayOf(1, 2, 3))
    File(dir, "altro.txt").writeText("non e' mio")
    opened(PlacesStore.open(pin, asset(), dir))
    assertThat(dir.listFiles()!!.map { it.name }).containsExactly("places-20250214.mcgp1", "altro.txt")
  }

  @Test
  fun iTemporaneiDiEstrazioniInterrotteVecchiSiCancellanoQuelliRecentiNo() {
    val dir = File(temp.root, "places").also { it.mkdirs() }
    val old = File(dir, "places-1.tmp").also {
      it.writeText("x")
      it.setLastModified(System.currentTimeMillis() - 2 * 3_600_000L)
    }
    val fresh = File(dir, "places-2.tmp").also { it.writeText("x") }
    opened(PlacesStore.open(pin, asset(), dir))
    assertThat(old.exists()).isFalse()
    assertThat(fresh.exists()).isTrue()
  }

  @Test
  fun unaVersioneDiversaDaQuellaFissataNonVaBene() {
    // L'impronta e la dimensione sono giuste ma il file dichiara un'altra versione: non si accetta.
    val other = PlacesPin(version = 20990101, sha256 = sha, sizeBytes = bytes.size.toLong())
    val result = PlacesStore.open(other, asset(), File(temp.root, "places"))
    assertThat((result as PlacesOpenResult.Failed).reason).isEqualTo(PlacesOpenResult.Reason.INVALID_FORMAT)
  }
}
