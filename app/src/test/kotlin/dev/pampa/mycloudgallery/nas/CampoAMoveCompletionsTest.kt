package dev.pampa.mycloudgallery.nas

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Campo, gruppo A, difetto 7 (Fase 7 §0 punto 11): la porta che l'esecutore delle operazioni chiama a copia pubblicata, prima del cestino, sul catalogo
 * vero. La posizione della sorgente diventa quella della destinazione, dello stesso media, senza la verifica (e' un altro file); prima il catalogo
 * puntava alla sorgente, gia' nel cestino, fino alla scansione dopo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CampoAMoveCompletionsTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val me = AccountId.of("alessio")
  private lateinit var db: GalleryDatabase
  private lateinit var catalog: MediaCatalog

  @Before
  fun setUp() {
    db = GalleryDatabase.build(app, name = null)
    catalog = MediaCatalog(db, me, zone = { ZoneId.of("Europe/Rome") }) { NOW }
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun nas(share: String, path: String, fileId: Long? = null) = Location.Nas(me, share, NasPath.of(path), fileId)

  @Test
  fun d7_laPosizioneNuovaEDelloStessoMediaPrimaDellaScansione() = runBlocking<Unit> {
    val bytes = ByteArray(2_000) { (it * 13).toByte() }
    val recorded = catalog.recordAll(
      listOf(Observation(nas("alessio", "Viaggi/a.jpg", 7), FileStamp(2_000, NOW), Fingerprint.ofContent(bytes), MediaKind.PHOTO, MediaSource.OTHER)),
      seenAt = NOW,
    ).single()
    val mediaId = checkNotNull(recorded.mediaId)
    val moves = CatalogMoveCompletions(catalog, me)

    moves.moved("alessio", SharePath.parse("Viaggi/a.jpg"), "famiglia", SharePath.parse("Famiglia/a.jpg"), NasStamp(2_000, NOW + 5, 42))

    assertThat(catalog.nasLocationAt(nas("alessio", "Viaggi/a.jpg"))).isNull()
    val moved = checkNotNull(catalog.nasLocationAt(nas("famiglia", "Famiglia/a.jpg")))
    assertThat(moved.locationId).isEqualTo(recorded.locationId)
    assertThat(moved.state).isEqualTo(LocationState.PRESENT)
    assertThat(moved.stamp.fileId).isEqualTo(42)
    assertThat(catalog.mediaIdOfLocation(moved.locationId)).isEqualTo(mediaId)
    assertThat(checkNotNull(catalog.media(mediaId)).locations.single { it.id == moved.locationId }.verifiedAtMillis).isNull()

    // Una ripresa dello stesso passo non cambia niente (la sorgente non c'e' piu' nel catalogo).
    moves.moved("alessio", SharePath.parse("Viaggi/a.jpg"), "famiglia", SharePath.parse("Famiglia/a.jpg"), NasStamp(2_000, NOW + 5, 42))
    assertThat(checkNotNull(catalog.media(mediaId)).locations).hasSize(1)
  }

  private companion object {
    const val NOW = 1_760_000_000_000L
  }
}
