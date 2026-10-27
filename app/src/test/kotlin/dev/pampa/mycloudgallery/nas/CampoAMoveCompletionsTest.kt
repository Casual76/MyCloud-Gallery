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
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.albums.CatalogMoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Campo, gruppo A, difetto 7 (Fase 7 §0 punto 11) e Fase 7 D29 (P13): la porta di completamento che l'esecutore delle operazioni chiama a copia
 * pubblicata, prima del cestino, sul catalogo vero (`CatalogMoveCompletionSink` sopra `MediaCatalog.attachMoved`, al posto di `CatalogMoveCompletions`
 * del campo A). La posizione della sorgente diventa quella della destinazione, dello stesso media; prima il catalogo puntava alla sorgente, gia' nel
 * cestino, fino alla scansione dopo. Vincolante: byte diversi dalla versione = errore (l'elemento si ferma, la sorgente resta).
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
    val sink = CatalogMoveCompletionSink({ catalog }, me)
    val source = NasFileRef("alessio", SharePath.parse("Viaggi/a.jpg"))
    val dest = NasFileRef("famiglia", SharePath.parse("Famiglia/a.jpg"))

    // Byte diversi da quelli della versione: niente si collega e la porta lo dice (il motore lascia la sorgente al suo posto e riprova).
    assertThrows(IllegalStateException::class.java) {
      runBlocking { sink.published(OP, 0, source, dest, NasStamp(2_000, NOW + 5, 42), Sha256.of(ByteArray(2_000))) }
    }
    assertThat(catalog.nasLocationAt(nas("famiglia", "Famiglia/a.jpg"))).isNull()

    sink.published(OP, 0, source, dest, NasStamp(2_000, NOW + 5, 42), Sha256.of(bytes))

    assertThat(catalog.nasLocationAt(nas("alessio", "Viaggi/a.jpg"))).isNull()
    val moved = checkNotNull(catalog.nasLocationAt(nas("famiglia", "Famiglia/a.jpg")))
    assertThat(moved.locationId).isEqualTo(recorded.locationId)
    assertThat(moved.state).isEqualTo(LocationState.PRESENT)
    assertThat(moved.stamp.fileId).isEqualTo(42)
    assertThat(catalog.mediaIdOfLocation(moved.locationId)).isEqualTo(mediaId)

    // Una ripresa dello stesso passo non cambia niente (la sorgente non c'e' piu' nel catalogo).
    sink.published(OP, 0, source, dest, NasStamp(2_000, NOW + 5, 42), Sha256.of(bytes))
    assertThat(checkNotNull(catalog.media(mediaId)).locations).hasSize(1)
  }

  private companion object {
    const val NOW = 1_760_000_000_000L
    val OP = OpId(dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.random())
  }
}
