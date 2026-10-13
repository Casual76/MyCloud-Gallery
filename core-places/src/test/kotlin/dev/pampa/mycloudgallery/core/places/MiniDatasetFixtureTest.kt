package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * Il dataset di prova e il formato sono "congelati" da un SHA-256 scritto nel codice: se cambia un byte del formato MCGP1,
 * dell'algoritmo di costruzione o dei file in `src/test/resources/places/geonames-mini`, questo test lo dice. Se il
 * cambiamento e' voluto, si aggiorna l'impronta qui sotto (e `docs/luoghi.md`).
 */
class MiniDatasetFixtureTest {

  @Test
  fun ilMiniDatasetHaLImprontaFissata() {
    val sha = MessageDigest.getInstance("SHA-256").digest(TestData.miniBytes).joinToString("") { "%02x".format(it) }
    assertThat(sha).isEqualTo(MINI_SHA256)
    assertThat(TestData.miniBytes.size).isEqualTo(MINI_SIZE)
  }

  @Test
  fun ilMiniDatasetEUnaPiccolaCosaDiFattiReali() {
    val data = PlacesDataset.openForTest(TestData.miniBytes)
    assertThat(data.placeCount).isIn(400..600)
    assertThat(data.sizeBytes).isLessThan(60_000)
    // I file di prova sono nel formato dei dump di GeoNames (19 colonne, tabulazione): sono poche centinaia di righe,
    // non una copia di GeoNames.
    val lines = listOf("cities1000.txt", "IT.txt").sumOf { File(TestData.miniDirectory, it).readLines().size }
    assertThat(lines).isLessThan(1_000)
    File(TestData.miniDirectory, "cities1000.txt").readLines().forEach { assertThat(it.split('\t')).hasSize(19) }
  }

  private companion object {
    // Aggiornare se cambia il formato, l'algoritmo di costruzione o i dump di prova.
    const val MINI_SHA256 = "0d953814bb173e982de08ac71af829ba891caeb35e61d0ae8b05978a9e9193c3"
    const val MINI_SIZE = 25_716
  }
}
