package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.VectorPrecision

import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import java.time.ZoneId
import kotlin.random.Random

object Db {
  val ME: AccountId = AccountId.of("alessio")
  const val T0: Long = 1_760_000_000_000L
  const val DAY: Long = 24L * 60 * 60 * 1000
  const val STORE_V1: String = "store-v1"

  val SPACE: EmbeddingSpace = EmbeddingSpace(EmbeddingSpace.EMBEDDING_GEMMA2_FLOAT_V1, 4, VectorPrecision.FLOAT32)

  fun content(seed: Int, size: Int = 300_000): ByteArray = Random(seed).nextBytes(size)

  fun full(seed: Int): Fingerprint = Fingerprint.ofContent(content(seed))

  fun quick(seed: Int): Fingerprint = full(seed).copy(fullHash = null)

  fun phone(name: String, id: Long, path: String = "DCIM/Camera/", storeVersion: String = STORE_V1, volume: String = "external_primary") =
    Location.Phone(volume, path, name, id, storeVersion)

  fun nas(path: String, share: String = "alessio"): Location.Nas = Location.Nas(ME, share, NasPath.of(path))

  fun stamp(fingerprint: Fingerprint, modifiedAt: Long = T0): FileStamp = FileStamp(fingerprint.size, modifiedAt)

  fun obs(
    location: Location,
    fingerprint: Fingerprint,
    modifiedAt: Long = T0,
    capture: Long? = null,
    source: MediaSource = MediaSource.CAMERA,
  ) = Observation(location, stamp(fingerprint, modifiedAt), fingerprint, MediaKind.PHOTO, source, capture)
}

/** Un database vero in memoria (Robolectric) con il catalogo e la configurazione di famiglia di prova. */
abstract class CatalogTestBase {

  protected lateinit var db: GalleryDatabase
  protected lateinit var catalog: MediaCatalog
  protected var now: Long = Db.T0

  /** Il fuso del telefono "adesso" (giorno provvisorio dei media appena visti, E-P5). */
  protected var zone: ZoneId = ZoneId.of("Europe/Rome")

  @Before
  fun openDatabase() {
    db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    catalog = MediaCatalog(db, Db.ME, zone = { zone }) { now }
    runBlocking {
      catalog.registerShare(NasShare(Db.ME, "alessio", NasArea.PRIVATE))
      catalog.registerShare(NasShare(Db.ME, "NAS", NasArea.COMMON))
    }
  }

  @After
  fun closeDatabase() {
    db.close()
  }

  /** Mette su un media tutti i riferimenti che il piano vuole proteggere. */
  protected suspend fun decorate(mediaId: MediaId): AlbumId {
    val album = AlbumId.random()
    db.albumDao().insert(AlbumEntity(album.value, "Oslo 2026", coverMediaId = mediaId.value, createdAt = now, updatedAt = now))
    db.albumDao().upsertItem(AlbumItemEntity(album.value, mediaId.value, position = 1, addedAt = now))
    catalog.setPersonalFlags(mediaId, PersonalFlags(favorite = true))
    val current = catalog.media(mediaId)!!.currentVersionId
    catalog.putEmbedding(mediaId, EmbeddingVector.ofFloats(Db.SPACE, 0, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), current))
    // Le fasi iniziali di un media (non tutte: FACES, HQ e FULL_HASH le mette in coda solo chi le chiede).
    WorkRules.initialStages(MediaKind.PHOTO).forEach { stage ->
      val state = catalog.workStates(mediaId).first { it.stage == stage }
      catalog.updateWork(mediaId, WorkRules.succeeded(state, current, now))
    }
    return album
  }

  /** Album, preferito, vettore, stati di lavoro e copertina sono ancora attaccati a [mediaId]. */
  protected suspend fun assertReferencesIntact(mediaId: MediaId, album: AlbumId) {
    assertThat(catalog.resolve(mediaId)).isEqualTo(mediaId)
    assertThat(db.albumDao().items(album.value).map { it.mediaId }).containsExactly(mediaId.value)
    assertThat(db.albumDao().get(album.value)!!.coverMediaId).isEqualTo(mediaId.value)
    assertThat(db.personalDao().get(mediaId.value)!!.favorite).isTrue()
    assertThat(db.embeddingDao().forMedia(mediaId.value)).hasSize(1)
    assertThat(catalog.workStates(mediaId)).hasSize(5)
    assertThat(db.mediaDao().count()).isEqualTo(1)
  }
}
