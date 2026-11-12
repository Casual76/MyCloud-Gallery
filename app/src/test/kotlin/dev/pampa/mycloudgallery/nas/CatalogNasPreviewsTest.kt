package dev.pampa.mycloudgallery.nas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.contract.NasFolderPreviews
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I4 (fase I): le anteprime di Cartelle NAS lette dal catalogo ([CatalogNasPreviews]). I file della pagina che il catalogo conosce hanno il loro media; i
 * nascosti (e i file sconosciuti, come un PDF mai indicizzato) no; ogni sottocartella ha la copertina con al piu' quattro media che stanno **sotto** di lei
 * (anche in profondita'), mai quelli delle cartelle sorelle ne' i file diretti della cartella aperta; la radice della share funziona come le altre cartelle.
 */
@RunWith(AndroidJUnit4::class)
class CatalogNasPreviewsTest {

  private val me = AccountId("test")
  private val now = 1_800_000_000_000L
  private var db: GalleryDatabase? = null

  @After
  fun close() {
    db?.close()
  }

  private fun database(): GalleryDatabase = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null).also { db = it }

  private fun nasObs(path: String, share: String = "test"): Observation {
    val content = "$share/$path".toByteArray()
    return Observation(
      Location.Nas(me, share, NasPath.of(path), fileId = path.hashCode().toLong()),
      FileStamp(content.size.toLong(), now - 86_400_000L),
      Fingerprint.ofContent(content).copy(fullHash = null),
      MediaKind.PHOTO,
      MediaSource.OTHER,
    )
  }

  private suspend fun MediaCatalog.put(path: String, share: String = "test") =
    checkNotNull(record(nasObs(path, share), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId)

  @Test
  fun iFileConosciutiHannoIlLoroMediaINascostiENoti() = runBlocking<Unit> {
    val database = database()
    val catalog = MediaCatalog(database, me, zone = { ZoneOffset.UTC }) { now }
    val shown = catalog.put("Viaggi/mappa.jpg")
    val hidden = catalog.put("Viaggi/segreta.jpg")
    catalog.setPersonalFlags(listOf(hidden)) { it.copy(hidden = true) }

    val previews = CatalogNasPreviews.read(
      database, me, "test", SharePath.of("Viaggi"),
      files = listOf("Viaggi/mappa.jpg", "Viaggi/segreta.jpg", "Viaggi/biglietti.pdf").map(SharePath::parse),
      folders = emptyList(),
    )
    assertThat(previews.files).containsExactly(SharePath.parse("Viaggi/mappa.jpg"), shown)
  }

  @Test
  fun leSottocartelleHannoLaCopertinaConIMediaCheStannoSottoDiLoro() = runBlocking<Unit> {
    val database = database()
    val catalog = MediaCatalog(database, me, zone = { ZoneOffset.UTC }) { now }
    val oslo = (1..6).map { catalog.put("Viaggi/Oslo/foto$it.jpg") }
    val deep = catalog.put("Viaggi/Roma/Giorno 1/colosseo.jpg")
    catalog.put("Viaggi/mappa.jpg")
    // Una cartella che inizia con lo stesso nome non e' una sottocartella di «Oslo».
    val sibling = catalog.put("Viaggi/Oslo bis/altro.jpg")
    val hidden = catalog.put("Viaggi/Vuota/segreta.jpg")
    catalog.setPersonalFlags(listOf(hidden)) { it.copy(hidden = true) }

    val folders = listOf("Viaggi/Oslo", "Viaggi/Roma", "Viaggi/Oslo bis", "Viaggi/Vuota").map(SharePath::parse)
    val previews = CatalogNasPreviews.read(database, me, "test", SharePath.of("Viaggi"), files = emptyList(), folders = folders)

    val osloCover = previews.folders.getValue(SharePath.parse("Viaggi/Oslo"))
    assertThat(osloCover).hasSize(NasFolderPreviews.FOLDER_COVER_COUNT)
    assertThat(oslo).containsAtLeastElementsIn(osloCover)
    assertThat(previews.folders.getValue(SharePath.parse("Viaggi/Roma"))).containsExactly(deep)
    assertThat(previews.folders.getValue(SharePath.parse("Viaggi/Oslo bis"))).containsExactly(sibling)
    // Una cartella con solo un nascosto non ha copertina: resta con la sua piastrella.
    assertThat(previews.folders).doesNotContainKey(SharePath.parse("Viaggi/Vuota"))
  }

  @Test
  fun allaRadiceDellaShareLeCartelleNonPrendonoIFileDiretti() = runBlocking<Unit> {
    val database = database()
    val catalog = MediaCatalog(database, me, zone = { ZoneOffset.UTC }) { now }
    catalog.put("radice.jpg")
    val inside = catalog.put("Foto/a.jpg")
    // Un'altra share non c'entra.
    catalog.put("Foto/b.jpg", share = "test-comune")

    val previews = CatalogNasPreviews.read(
      database, me, "test", folder = null,
      files = listOf(SharePath.of("radice.jpg")),
      folders = listOf(SharePath.of("Foto")),
    )
    assertThat(previews.folders.getValue(SharePath.of("Foto"))).containsExactly(inside)
    assertThat(previews.files.keys).containsExactly(SharePath.of("radice.jpg"))
  }
}
