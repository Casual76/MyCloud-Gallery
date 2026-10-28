package dev.pampa.mycloudgallery.gallery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.library.grid.SavedGridPosition
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Test

/**
 * Avvio istantaneo (28/10): il punto della Libreria fra un avvio e l'altro ([LibraryInstant]). Un elemento ancorato e tre numeri, scritti con
 * `StateFiles` in `replica/`; un file di un'altra versione, troncato o con valori assurdi vale "nessun punto" (si parte dai piu' recenti, come
 * sempre), mai un'eccezione verso la Libreria. Nessun dato oltre all'id: niente nomi, luoghi o date.
 */
class LibraryInstantTest {

  private val dir: File = Files.createTempDirectory("instant").toFile()
  private val file = File(dir, LibraryInstant.POSITION_FILE)

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun ilPuntoSiScriveESiRilegge() {
    val position = SavedGridPosition(UUID.randomUUID().toString(), fy = 0.25f, screenFraction = 0.6f, atNewest = false)
    assertThat(LibraryInstant.writePosition(file, position)).isTrue()
    assertThat(LibraryInstant.readPosition(file)).isEqualTo(position)
    val newest = position.copy(atNewest = true)
    assertThat(LibraryInstant.writePosition(file, newest)).isTrue()
    assertThat(LibraryInstant.readPosition(file)).isEqualTo(newest)
    // Il file contiene l'id e tre numeri, nient'altro.
    assertThat(file.readLines()).hasSize(5)
  }

  @Test
  fun senzaFileNessunPunto() {
    assertThat(LibraryInstant.readPosition(file)).isNull()
  }

  @Test
  fun unFileStranoValeNessunPunto() {
    val id = UUID.randomUUID().toString()
    val bad = listOf(
      "",
      "MCGP0\n$id\n0.5\n0.5\n0",
      "MCGP1\nnon-un-id\n0.5\n0.5\n0",
      "MCGP1\n$id\nNaN\n0.5\n0",
      "MCGP1\n$id\n0.5\n0.5\nforse",
      "MCGP1\n$id\n0.5",
    )
    for (text in bad) {
      file.writeText(text)
      assertThat(LibraryInstant.readPosition(file)).isNull()
    }
  }

  @Test
  fun iNumeriFuoriScalaSiRiportanoDentro() {
    val id = UUID.randomUUID().toString()
    file.writeText("MCGP1\n$id\n1.5\n-2\n0")
    assertThat(LibraryInstant.readPosition(file)).isEqualTo(SavedGridPosition(id, 1f, 0f, atNewest = false))
  }
}
