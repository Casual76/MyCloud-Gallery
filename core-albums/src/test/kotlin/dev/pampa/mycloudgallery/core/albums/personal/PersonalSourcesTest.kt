package dev.pampa.mycloudgallery.core.albums.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Gli album personali non toccano i file (D3, D39) e non hanno nessuna strada verso un'operazione distruttiva (C10, E-S11, §4.3): il codice di
 * `personal/` non nomina le porte che spostano, caricano o chiedono il guardiano, ne' le conferme, ne' il NAS. Un dato letto dai dati
 * personali (di un altro dispositivo dello stesso account) puo' al piu' cambiare righe di tabelle derivate.
 */
class PersonalSourcesTest {

  private val root = File("src/main/kotlin/dev/pampa/mycloudgallery/core/albums/personal")

  private val forbidden = Regex(
    """\b(AlbumMover|AlbumUploader|SafeCopyRequester|SafeCopyRequests|TicketStatus|TransferStart|ShareConfirmation|NasOperations|NasFile\w*|UserConfirmation|""" +
      """InMemoryNas\w*)\b""",
  )

  private fun stripComments(source: String): String =
    source.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { it.substringBefore("//") }

  @Test
  fun ilCodiceDegliAlbumPersonaliNonHaNessunaPortaCheSposti() {
    val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    assertThat(files).isNotEmpty()
    val found = files.filter { forbidden.containsMatchIn(stripComments(it.readText())) }.map { it.name }
    assertWithMessage("sorgenti di personal/ che nominano porte, conferme o il NAS").that(found).isEmpty()
  }

  @Test
  fun leTabelleDerivateSiScrivonoSoloDallaProiezione() {
    // Le scritture sulle tabelle degli album (`AlbumDao`, regole, esclusioni) stanno in CatalogAlbumProjection: l'editor e la lettura non scrivono.
    val writers = listOf("upsert(", "upsertItems(", "upsertItem(", "removeItem(", "clearItems(", "delete(", "upsertAll(", "upsertExclusions(", "removeExclusion(")
    for (name in listOf("AlbumEditor.kt", "AlbumReader.kt", "CatalogAlbums.kt", "MediaResolver.kt")) {
      val text = stripComments(File(root, name).readText())
      val hits = writers.filter { text.contains("Dao().$it") || text.contains("albumDao().$it") }
      assertWithMessage("$name scrive nelle tabelle derivate").that(hits).isEmpty()
    }
  }
}
