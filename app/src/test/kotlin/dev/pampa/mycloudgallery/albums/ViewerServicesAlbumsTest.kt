package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.menu.NoViewerAlbumActions
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumActions
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumMenu
import dev.pampa.mycloudgallery.feature.viewer.menu.AlbumMenuResult
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import org.junit.Test

/**
 * Fase 7 (P13): [withAlbums] copia **ogni** campo di `ViewerServices` (non ha una `copy`). Si legge il costruttore nel sorgente di feature-viewer: un
 * campo nuovo che la copia non nomina sparirebbe in silenzio dal viewer aperto da un album o dai Nascosti (il ritaglio, la lettura del testo...).
 */
class ViewerServicesAlbumsTest {

  private fun source(path: String): String {
    val file = listOf(File(path), File("..", path)).first { it.isFile }
    return file.readText()
  }

  @Test
  fun laCopiaNominaOgniCampoDelCostruttore() {
    val services = source("feature-viewer/src/main/kotlin/dev/pampa/mycloudgallery/feature/viewer/ViewerServices.kt")
    val constructor = services.substringAfter("class ViewerServices(").substringBefore("\n)")
    val fields = Regex("""^\s*val\s+(\w+)\s*:""", RegexOption.MULTILINE).findAll(constructor).map { it.groupValues[1] }.toList()
    assertThat(fields).contains("albums")
    assertThat(fields.size).isAtLeast(30)

    val copy = source("app/src/main/kotlin/dev/pampa/mycloudgallery/albums/ViewerServicesAlbums.kt")
    for (field in fields - "albums") assertWithMessage("campo non copiato: $field").that(copy).contains("  $field = $field,")
    assertThat(copy).contains("  albums = albums,")
  }

  @Test
  fun soloLeVociDegliAlbumCambiano() {
    val base = ViewerServices()
    val mine = object : ViewerAlbumActions {
      override fun menu(id: MediaId) = ViewerAlbumMenu(addToAlbum = true, removeFromAlbum = true, setAsCover = true)

      override fun addToAlbum(id: MediaId) = Unit

      override suspend fun removeFromAlbum(id: MediaId) = AlbumMenuResult.DONE

      override suspend fun setAsCover(id: MediaId) = AlbumMenuResult.DONE
    }
    val copied = base.withAlbums(mine)
    assertThat(base.albums).isSameInstanceAs(NoViewerAlbumActions)
    assertThat(copied.albums).isSameInstanceAs(mine)
    assertThat(copied.images).isSameInstanceAs(base.images)
    assertThat(copied.motion).isSameInstanceAs(base.motion)
    assertThat(copied.similarSearch).isSameInstanceAs(base.similarSearch)
  }
}
