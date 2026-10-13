package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Format
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1FormatException
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import dev.pampa.mycloudgallery.core.places.dataset.PlacesOpenResult
import dev.pampa.mycloudgallery.core.places.dataset.PlacesPin
import dev.pampa.mycloudgallery.core.places.dataset.PlacesStore
import dev.pampa.mycloudgallery.core.places.dataset.RegionRow
import dev.pampa.mycloudgallery.core.places.noop.NoReverseGeocoder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R3 della revisione di sicurezza della Fase 3 (docs/review/3-fase3.md): un `places.mcgp1` alterato **con il CRC-32
 * ricalcolato** (il CRC non protegge da chi sa riscriverlo) deve dare un errore pulito, `Mcgp1FormatException` e quindi
 * `PlacesOpenResult.Failed(INVALID_FORMAT)` e `NoReverseGeocoder`, mai un `ArrayIndexOutOfBounds` a meta' ricerca.
 *
 * Ogni prova altera un solo campo interno (indice di paese/regione/fuso, colonna o primo luogo di una cella, offset dei
 * nomi, tabelle), richiude il file con un CRC giusto e verifica il rifiuto, con e senza la verifica del CRC: i controlli
 * strutturali non dipendono da lui.
 */
class Mcgp1TamperedTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val regions = listOf(
    RegionRow("IT.16", 3_165_361, "IT", "Toscana"),
    RegionRow("GB.ENG", 6_269_131, "GB", "Inghilterra"),
  )

  private val places = listOf(
    TestData.place(10, 43.7696, 11.2558, "Firenze", "IT", "IT.16", "Europe/Rome", cls = 2, pop = 380_000),
    TestData.place(11, 43.8056, 11.2931, "Fiesole", "IT", "IT.16", "Europe/Rome", cls = 2, pop = 14_000),
    TestData.place(20, 51.5085, -0.1257, "Londra", "GB", "GB.ENG", "Europe/London", cls = 3, pop = 8_900_000),
    TestData.place(30, 64.1355, -21.8954, "Reykjavík", "IS", null, "Atlantic/Reykjavik", cls = 3, pop = 130_000),
    TestData.place(31, -33.8679, 151.2073, "Sydney", "AU", null, null, cls = 2, pop = 5_000_000),
  )

  private val good = TestData.bytes(places, regions)

  // ---- Strumenti ----------------------------------------------------------------------------------------------------

  /** Le posizioni delle sezioni, lette dall'intestazione del file. */
  private class Sections(bytes: ByteArray) {
    val b: ByteBuffer = ByteBuffer.wrap(bytes).order(Mcgp1Format.ORDER)
    val placeCount = b.getInt(16)
    val cellCount = b.getInt(20)
    val regionCount = b.getInt(24)
    val zoneCount = b.getInt(28)
    val countryCount = b.getInt(32)
    val namesSize = b.getInt(36)
    val poolSize = b.getInt(40)
    val offRowStart = b.getInt(44)
    val offCellCol = b.getInt(48)
    val offCellFirst = b.getInt(52)
    val offCellName = b.getInt(56)
    val offPlaces = b.getInt(60)
    val offNames = b.getInt(64)
    val offRegions = b.getInt(68)
    val offZones = b.getInt(72)
    val offCountries = b.getInt(76)
    val offPool = b.getInt(80)

    fun place(i: Int) = offPlaces + i * Mcgp1Format.PLACE_SIZE
    fun region(i: Int) = offRegions + i * Mcgp1Format.REGION_SIZE
    fun zone(i: Int) = offZones + i * Mcgp1Format.ZONE_SIZE
  }

  /** Il CRC-32 in coda ricalcolato: il file alterato ha dimensione e CRC coerenti, come lo scrive chi sa farlo. */
  private fun reseal(bytes: ByteArray): ByteArray {
    val crc = CRC32()
    crc.update(bytes, 0, bytes.size - 4)
    ByteBuffer.wrap(bytes).order(Mcgp1Format.ORDER).putInt(bytes.size - 4, crc.value.toInt())
    return bytes
  }

  private fun tampered(base: ByteArray = good, edit: (Sections) -> Unit): ByteArray {
    val copy = base.copyOf()
    edit(Sections(copy))
    return reseal(copy)
  }

  private fun assertRejected(bytes: ByteArray, reason: Mcgp1FormatException.Reason) {
    for (verify in listOf(true, false)) {
      val error = assertThrows(Mcgp1FormatException::class.java) { TestData.open(bytes, verify) }
      assertThat(error.reason).isEqualTo(reason)
    }
  }

  // ---- Il controllo: un file non alterato, anche richiuso, si apre ---------------------------------------------------

  @Test
  fun unFileRichiusoSenzaAlterazioniSiApreEFunziona() {
    val file = TestData.open(reseal(good.copyOf()))
    assertThat(file.placeCount).isEqualTo(places.size)
    assertThat(file.nearest(43.7696, 11.2558, 50_000.0)).isNotNull()
    // Anche regione e fuso "nessuno" (0xFFFF) sono validi.
    val none = tampered { m ->
      m.b.putShort(m.place(0) + 8, Mcgp1Format.NONE.toShort())
      m.b.putShort(m.place(0) + 12, Mcgp1Format.NONE.toShort())
    }
    assertThat(TestData.open(none).regionOf(0)).isEqualTo(Mcgp1Format.NONE)
  }

  // ---- Indici dei luoghi: paese, regione, fuso, id ----------------------------------------------------------------------

  @Test
  fun unPaeseFuoriDallaTabellaSiRifiuta() {
    assertRejected(tampered { it.b.putShort(it.place(0) + 10, it.countryCount.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putShort(it.place(3) + 10, 0x7FFF) }, Mcgp1FormatException.Reason.BAD_RECORD)
    // 0xFFFF (NONE) per il paese non esiste: il paese c'e' sempre.
    assertRejected(tampered { it.b.putShort(it.place(1) + 10, Mcgp1Format.NONE.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
  }

  @Test
  fun unaRegioneFuoriDallaTabellaSiRifiuta() {
    assertRejected(tampered { it.b.putShort(it.place(0) + 8, it.regionCount.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putShort(it.place(2) + 8, 0xFFFE.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
  }

  @Test
  fun unFusoFuoriDallaTabellaSiRifiuta() {
    assertRejected(tampered { it.b.putShort(it.place(0) + 12, it.zoneCount.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putShort(it.place(4) + 12, 0xFFFE.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
  }

  @Test
  fun unIdNonPositivoOInvadenteSiRifiuta() {
    // PlaceId(0) o negativo lancerebbe a ogni lettura; sopra COUNTRY_ID_BASE collide con i "luoghi" dei paesi.
    assertRejected(tampered { it.b.putInt(it.place(0), 0) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putInt(it.place(0), -5) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putInt(it.place(1), Mcgp1Format.COUNTRY_ID_BASE) }, Mcgp1FormatException.Reason.BAD_RECORD)
  }

  // ---- Celle: colonne e primi luoghi ------------------------------------------------------------------------------------

  @Test
  fun unaColonnaFuoriDa0A1439SiRifiuta() {
    assertRejected(tampered { it.b.putShort(it.offCellCol, Mcgp1Format.COLS.toShort()) }, Mcgp1FormatException.Reason.BAD_INDEX)
    assertRejected(tampered { it.b.putShort(it.offCellCol + 2, 0xFFFF.toShort()) }, Mcgp1FormatException.Reason.BAD_INDEX)
  }

  @Test
  fun delleColonneNonCrescentiNellaStessaFasciaSiRifiutano() {
    // Due celle nella stessa fascia (stessa latitudine, longitudini diverse).
    val sameRow = TestData.bytes(listOf(TestData.place(1, 45.0, 9.0), TestData.place(2, 45.0, 10.0), TestData.place(3, 45.0, 11.0)))
    assertThat(TestData.open(sameRow).cellCount).isEqualTo(3)
    // Uguali: non strettamente crescenti.
    assertRejected(tampered(sameRow) { m -> m.b.putShort(m.offCellCol + 2, m.b.getShort(m.offCellCol)) }, Mcgp1FormatException.Reason.BAD_INDEX)
    // Scambiate: decrescenti.
    assertRejected(
      tampered(sameRow) { m ->
        val first = m.b.getShort(m.offCellCol)
        m.b.putShort(m.offCellCol, m.b.getShort(m.offCellCol + 2))
        m.b.putShort(m.offCellCol + 2, first)
      },
      Mcgp1FormatException.Reason.BAD_INDEX,
    )
  }

  @Test
  fun iPrimiLuoghiDelleCelleFuoriDalFileOInDisordineSiRifiutano() {
    assertThat(TestData.open(good).cellCount).isAtLeast(3)
    // Un primo luogo oltre il numero di luoghi.
    assertRejected(tampered { it.b.putInt(it.offCellFirst + 4, it.placeCount + 3) }, Mcgp1FormatException.Reason.BAD_INDEX)
    // Un primo luogo negativo.
    assertRejected(tampered { it.b.putInt(it.offCellFirst + 4, -1) }, Mcgp1FormatException.Reason.BAD_INDEX)
    // Decrescente: la cella 1 finirebbe prima di cominciare.
    assertRejected(tampered { it.b.putInt(it.offCellFirst + 8, it.b.getInt(it.offCellFirst + 4) - 1) }, Mcgp1FormatException.Reason.BAD_INDEX)
  }

  // ---- Nomi -------------------------------------------------------------------------------------------------------------

  @Test
  fun inomiFuoriDallaTabellaSiRifiutano() {
    // L'offset del nome della cella oltre la tabella, e negativo.
    assertRejected(tampered { it.b.putInt(it.offCellName, it.namesSize + 1) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putInt(it.offCellName, -4) }, Mcgp1FormatException.Reason.BAD_RECORD)
    // Una lunghezza di nome che fa uscire la somma dalla tabella.
    assertRejected(tampered { it.b.put(it.place(0) + 16, 0xFF.toByte()) }, Mcgp1FormatException.Reason.BAD_RECORD)
  }

  // ---- Tabelle: paesi, regioni, fusi ------------------------------------------------------------------------------------

  @Test
  fun unCodicePaeseNonValidoOFuoriOrdineSiRifiuta() {
    assertRejected(tampered { it.b.put(it.offCountries, 'a'.code.toByte()) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.put(it.offCountries + 1, '1'.code.toByte()) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(
      tampered { m ->
        // Scambia i primi due paesi: non piu' in ordine alfabetico.
        val a = m.b.getShort(m.offCountries)
        m.b.putShort(m.offCountries, m.b.getShort(m.offCountries + 2))
        m.b.putShort(m.offCountries + 2, a)
      },
      Mcgp1FormatException.Reason.BAD_RECORD,
    )
  }

  @Test
  fun leRegioniNonValideSiRifiutano() {
    // Il paese di una regione fuori dalla tabella dei paesi.
    assertRejected(tampered { it.b.putShort(it.region(0) + 4, it.countryCount.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
    // Id non crescenti.
    assertRejected(tampered { it.b.putInt(it.region(1), it.b.getInt(it.region(0))) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putInt(it.region(0), 0) }, Mcgp1FormatException.Reason.BAD_RECORD)
    // Il nome fuori dal pool: offset oltre, offset negativo, lunghezza che esce.
    assertRejected(tampered { it.b.putInt(it.region(0) + 6, it.poolSize) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putInt(it.region(0) + 6, -1) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putShort(it.region(1) + 10, 0xFFFF.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
  }

  @Test
  fun iFusiConIlNomeFuoriDalPoolSiRifiutano() {
    assertRejected(tampered { it.b.putInt(it.zone(0), it.poolSize) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putInt(it.zone(1), -1) }, Mcgp1FormatException.Reason.BAD_RECORD)
    assertRejected(tampered { it.b.putShort(it.zone(2) + 4, 0xFFFF.toShort()) }, Mcgp1FormatException.Reason.BAD_RECORD)
  }

  // ---- Fino all'app: errore pulito, niente luoghi ------------------------------------------------------------------------

  @Test
  fun unAssetAlteratoDaInvalidFormatENoReverseGeocoder() {
    // Il dataset di prova con un paese fuori range e il CRC rifatto; l'impronta fissata e' quella del file alterato, cioe'
    // l'asset e' stato sostituito prima della build: SHA-256 e CRC-32 tornano, la struttura no.
    val bytes = tampered(TestData.miniBytes) { it.b.putShort(it.place(0) + 10, it.countryCount.toShort()) }
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val version = ByteBuffer.wrap(bytes).order(Mcgp1Format.ORDER).getInt(12)
    val pin = PlacesPin(version = version, sha256 = sha, sizeBytes = bytes.size.toLong())

    val result = PlacesStore.open(pin, { ByteArrayInputStream(bytes) }, File(temp.root, "places"))

    assertThat(result).isInstanceOf(PlacesOpenResult.Failed::class.java)
    assertThat((result as PlacesOpenResult.Failed).reason).isEqualTo(PlacesOpenResult.Reason.INVALID_FORMAT)
    val geocoder = geocoderOf(PlacesStore.open(pin, { ByteArrayInputStream(bytes) }, File(temp.root, "places-2")))
    assertThat(geocoder).isSameInstanceAs(NoReverseGeocoder)
    assertThat(geocoder.nearest(GeoPoint(45.4641, 9.1919))).isNull()
  }

  /** Esattamente cio' che fa `PlacesDataset.open`: senza dataset utilizzabile si ripiega sul null-object. */
  private fun geocoderOf(result: PlacesOpenResult): ReverseGeocoder = (result as? PlacesOpenResult.Opened)?.data ?: NoReverseGeocoder

  // ---- Qualsiasi alterazione: o rifiutata, o letta senza eccezioni -------------------------------------------------------

  /**
   * La proprieta' che conta: dopo qualunque alterazione (byte casuali nelle sezioni strutturali, CRC rifatto) il file o e'
   * rifiutato con `Mcgp1FormatException`, o si apre e **ogni lettura** (ricerca, fuso, luogo per id, etichette) torna un
   * risultato senza lanciare. I nomi (contenuto UTF-8) restano fuori dalle mutazioni: un nome cambiato e' solo un altro nome.
   */
  @Test
  fun qualsiasiAlterazioneDellaStrutturaOERifiutataOELettaSenzaEccezioni() {
    val base = TestData.miniBytes
    val map = Sections(base)
    val structural = (map.offRowStart until map.offNames) + (map.offRegions until base.size - 4)
    val random = Random(20261013)
    val probes = listOf(
      GeoPoint(45.4641, 9.1919), GeoPoint(41.8902, 12.4922), GeoPoint(43.7696, 11.2558), GeoPoint(40.8518, 14.2681),
      GeoPoint(0.0, 0.0), GeoPoint(89.9, 10.0), GeoPoint(-89.9, -170.0), GeoPoint(44.0, 179.9), GeoPoint(44.0, -179.9),
    )
    val randomProbes = List(24) { GeoPoint(random.nextDouble(-85.0, 85.0), random.nextDouble(-179.0, 179.0)) }
    // Id che esistono davvero nel file di partenza (luoghi e regioni), piu' un paese e qualche id che non c'e'.
    val original = TestData.open(base)
    val knownIds = (0 until original.placeCount step maxOf(1, original.placeCount / 80)).map { original.placeId(it) } +
      (0 until original.regionCount).map { original.regionId(it) } + listOf(1, 2, 3, Mcgp1Format.countryPlaceId("IT"))

    var rejected = 0
    var opened = 0
    repeat(2500) {
      val copy = base.copyOf()
      repeat(1 + random.nextInt(3)) { copy[structural[random.nextInt(structural.size)]] = random.nextInt(256).toByte() }
      reseal(copy)
      val data = try {
        PlacesDataset.openForTest(copy)
      } catch (_: Mcgp1FormatException) {
        rejected++
        return@repeat
      }
      opened++
      for (p in probes + randomProbes) {
        data.nearest(p)
        data.zoneAt(p)
      }
      for (id in knownIds) {
        data.place(PlaceId(id))
        data.labelOfId(id)
      }
      data.summary(intArrayOf(10, 11, 12), 3)
    }
    // Entrambi i rami sono stati percorsi davvero: la prova non e' vuota.
    assertThat(rejected).isGreaterThan(100)
    assertThat(opened).isGreaterThan(100)
  }
}
