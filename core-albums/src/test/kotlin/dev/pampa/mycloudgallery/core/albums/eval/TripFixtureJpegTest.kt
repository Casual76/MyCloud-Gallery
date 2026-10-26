package dev.pampa.mycloudgallery.core.albums.eval

import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileInputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I JPEG DEL GENERATORE LI LEGGE UN LETTORE VERO (riga P12 del §4.4): il campione di `tools/fixtures/trips/campione/` (scritto da `generate_trips.py`, committato)
 * riletto da `android.media.ExifInterface` (quello che usa Android per la data dello scatto e il GPS; legge anche le dimensioni dall'intestazione JPEG). Il
 * generatore scrive l'EXIF a mano: se scrivesse e rileggesse allo stesso modo sbagliato, nessun test suo se ne accorgerebbe. Qui ciascuna foto e' confrontata con la
 * sua riga della specifica: data e ora locali, fuso, marca, GPS (con i riferimenti N/S ed E/W), dimensioni. Dati sintetici.
 */
@RunWith(AndroidJUnit4::class)
class TripFixtureJpegTest {

  private val sample: File = File(repositoryRoot(), "tools/fixtures/trips/campione")

  private class Entry(val file: File, val row: TripFixtureSpec.Row)

  private fun entries(): List<Entry> {
    val spec = TripFixtureSpec.parse(File(sample, "spec-campione.tsv").readText(Charsets.UTF_8), "")
    val manifest = File(sample, "storia-99/MANIFEST.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    // Il manifesto: file (relativo), storia, n, ...
    return manifest.map { c ->
      val n = c[2].toInt()
      Entry(File(sample, "storia-99/" + c[0]), spec.rows.single { it.n == n })
    }
  }

  @Test
  fun ilCampioneHaTuttiIGeneriDiFoto() {
    val all = entries()
    assertThat(all).hasSize(11)
    assertThat(all.count { it.row.lat == null }).isAtLeast(2)
    assertThat(all.count { it.row.screen }).isEqualTo(1)
    assertThat(all.map { it.row.zone }.distinct()).containsAtLeast(60, -300, 540, 660)
    assertThat(all.count { it.row.lat != null && it.row.lat < 0 }).isAtLeast(2)
    assertThat(all.count { it.row.lon != null && it.row.lon < 0 }).isAtLeast(2)
    assertThat(all.map { it.row.device }.toSet()).containsAtLeast(TripFixtureSpec.PHONE, TripFixtureSpec.TABLET, "-")
    assertThat(all.all { it.file.isFile }).isTrue()
  }

  @Test
  fun ExifInterfaceLeggeDataFusoMarcaEGps() {
    for (e in entries()) {
      val exif = FileInputStream(e.file).use { ExifInterface(it) }
      val r = e.row
      val local = "%04d:%02d:%02d %02d:%02d:00".format(r.day.year, r.day.monthValue, r.day.dayOfMonth, r.minute / 60, r.minute % 60)
      assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo(local)
      val offset = "%s%02d:%02d".format(if (r.zone >= 0) "+" else "-", Math.abs(r.zone) / 60, Math.abs(r.zone) % 60)
      assertThat(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)).isEqualTo(offset)
      assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isEqualTo("MCG Prova")
      val lat = degrees(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE), exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
      val lon = degrees(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE), exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF))
      if (r.lat == null) {
        assertThat(lat).isNull()
        assertThat(lon).isNull()
      } else {
        assertThat(lat).isNotNull()
        assertThat(lat!!).isWithin(1e-9).of(r.lat)
        assertThat(lon!!).isWithin(1e-9).of(r.lon!!)
      }
    }
  }

  /** Gradi, primi e secondi come li da' ExifInterface ("59/1,54/1,500400/10000") e il riferimento N/S/E/W: i gradi con il segno. */
  private fun degrees(value: String?, ref: String?): Double? {
    if (value == null || ref == null) return null
    val parts = value.split(',').map { p -> p.split('/').let { it[0].toDouble() / it[1].toDouble() } }
    val d = parts[0] + parts[1] / 60.0 + parts[2] / 3600.0
    return if (ref == "S" || ref == "W") -d else d
  }

  @Test
  fun ilGiornoLocaleEQuelloDellaSpecificaAnchePerChiScattaVicinoAMezzanotte() {
    // New York alle 23:50 (-05:00) e' gia' il giorno dopo in UTC; Tokyo alle 00:20 (+09:00) e' ancora il giorno prima in UTC: l'EXIF porta l'ora locale e il fuso.
    val byN = entries().associateBy { it.row.n }
    val newYork = FileInputStream(byN.getValue(2).file).use { ExifInterface(it) }
    assertThat(newYork.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).startsWith("2024:12:31 23:50")
    assertThat(newYork.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)).isEqualTo("2025:01:01")
    val tokyo = FileInputStream(byN.getValue(3).file).use { ExifInterface(it) }
    assertThat(tokyo.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).startsWith("2025:01:01 00:20")
    assertThat(tokyo.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)).isEqualTo("2024:12:31")
  }

  @Test
  fun leDimensioniDellIntestazioneJpegSonoQuelleDelGeneratore() {
    for (e in entries()) {
      val exif = FileInputStream(e.file).use { ExifInterface(it) }
      // ExifInterface le legge dal marcatore SOF, non dall'EXIF: e' l'intestazione JPEG che scriviamo a mano.
      assertThat(exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)).isEqualTo(if (e.row.screen) 56 else 96)
      assertThat(exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)).isEqualTo(if (e.row.screen) 120 else 72)
      assertThat(e.file.readBytes().let { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() && it[it.size - 2] == 0xFF.toByte() && it[it.size - 1] == 0xD9.toByte() }).isTrue()
    }
    // Ogni foto e' diversa dalle altre (un'impronta per file).
    assertThat(entries().map { it.file.readBytes().contentHashCode() }.distinct()).hasSize(11)
  }

  @Test
  fun laSpecificaDelCampioneENelFormatoDelTest() {
    // Il campione e' scritto a mano nel formato che il test di esportazione produce: la stessa lettura lo capisce.
    val spec = TripFixtureSpec.parse(File(sample, "spec-campione.tsv").readText(Charsets.UTF_8), "")
    assertThat(spec.rows).hasSize(11)
    val world = TripFixtureSpec.world(spec.rows)
    assertThat(world.items()).hasSize(11)
    assertThat(world.items().count { it.c.screen }).isEqualTo(1)
    assertThat(world.items().count { it.c.isVideo }).isEqualTo(1)
  }

  private fun repositoryRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle").isFile) return dir
      dir = dir.parentFile
    }
    error("Radice del repository non trovata.")
  }
}
