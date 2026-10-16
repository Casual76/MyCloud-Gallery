package dev.pampa.mycloudgallery.core.library.similar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.MediaStoreCursor
import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.domain.Observation
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before

// Fase 5A, P15: il materiale comune delle prove sul catalogo vero (Room in memoria, Robolectric). Impronte piccole: i test non leggono MB.

internal object Fx {
  val ME: AccountId = AccountId.of("alessio")
  const val T0: Long = 1_760_000_000_000L
  const val STORE: String = "store-v1"
  const val VOLUME: String = "external_primary"

  /** Il secondo di scatto comune alle copie di una prova, senza millisecondi (nessun sottosecondo scritto). */
  const val SECOND0: Long = (T0 / 1_000) * 1_000

  fun full(seed: Int): Fingerprint = Fingerprint.ofContent(Random(seed).nextBytes(2_000))

  fun exif(
    takenAt: Long,
    width: Int = 4000,
    height: Int = 3000,
    mime: String? = "image/jpeg",
    durationMs: Long? = null,
    source: CaptureTimeSource = CaptureTimeSource.EXIF_WITH_OFFSET,
    confidence: CaptureTimeConfidence = CaptureTimeConfidence.EXACT,
  ): MediaMetadata = MediaMetadata(
    mimeType = mime, width = width, height = height, durationMillis = durationMs,
    captureTime = CaptureTime(takenAt, 120, confidence, source),
  )
}

/** Un database vero in memoria con il catalogo e le due share di famiglia (`alessio` privata, `NAS` comune). */
abstract class SimilarCatalogTestBase {

  protected lateinit var db: GalleryDatabase
  protected lateinit var catalog: MediaCatalog
  protected var now: Long = Fx.T0
  private var phoneId = 100L
  private var seed = 1_000

  @Before
  fun openCatalog() {
    db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    catalog = MediaCatalog(db, Fx.ME, zone = { ZoneId.of("Europe/Rome") }) { now }
    runBlocking {
      catalog.saveMediaStoreCursor(MediaStoreCursor(Fx.VOLUME, msVersion = Fx.STORE))
      catalog.registerShare(NasShare(Fx.ME, "alessio", NasArea.PRIVATE))
      catalog.registerShare(NasShare(Fx.ME, "NAS", NasArea.COMMON))
    }
  }

  @After
  fun closeCatalog() {
    db.close()
  }

  /** Un file del telefono con la data di scatto [takenAt] gia' letta dai metadati (META ha gia' girato). */
  protected suspend fun phoneShot(
    takenAt: Long = Fx.SECOND0,
    width: Int = 4000,
    height: Int = 3000,
    kind: MediaKind = MediaKind.PHOTO,
    mime: String? = if (kind.isVideo) "video/mp4" else "image/jpeg",
    durationMs: Long? = if (kind.isVideo) 8_000 else null,
    source: CaptureTimeSource = CaptureTimeSource.EXIF_WITH_OFFSET,
    bytes: Int = 2_000,
  ): MediaId {
    val n = ++seed
    val fingerprint = Fingerprint.ofContent(Random(n).nextBytes(bytes))
    val location = Location.Phone(Fx.VOLUME, "DCIM/Camera/", "IMG_$n.jpg", ++phoneId, Fx.STORE)
    val observation = Observation(location, FileStamp(fingerprint.size, Fx.T0, 7), fingerprint, kind, MediaSource.CAMERA, takenAt)
    val id = catalog.record(observation).mediaId!!
    catalog.updateMetadata(catalog.media(id)!!.currentVersionId, Fx.exif(takenAt, width, height, mime, durationMs, source))
    return id
  }

  /** Una copia sul NAS (privata in `alessio`, comune in `NAS`) con la data di scatto [takenAt] gia' letta. */
  protected suspend fun nasShot(
    path: String,
    takenAt: Long = Fx.SECOND0,
    width: Int = 2000,
    height: Int = 1500,
    share: String = "alessio",
    kind: MediaKind = MediaKind.PHOTO,
    mime: String? = if (kind.isVideo) "video/mp4" else "image/jpeg",
    durationMs: Long? = if (kind.isVideo) 8_000 else null,
    bytes: Int = 1_500,
  ): MediaId {
    val n = ++seed
    val fingerprint = Fingerprint.ofContent(Random(n).nextBytes(bytes))
    val location = Location.Nas(Fx.ME, share, NasPath.of(path))
    val observation = Observation(location, FileStamp(fingerprint.size, Fx.T0), fingerprint, kind, MediaSource.OTHER)
    val id = catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0).single().mediaId!!
    catalog.updateMetadata(catalog.media(id)!!.currentVersionId, Fx.exif(takenAt, width, height, mime, durationMs))
    return id
  }

  protected suspend fun versionOf(id: MediaId) = catalog.media(id)!!.currentVersionId

  protected suspend fun groupIdOf(id: MediaId): String? = db.mediaDao().get(id.value)?.groupId
}

/** Una sorgente di pixel scritta dal test: per ogni media il suo esito. Registra chi e' stato chiesto. */
internal class ScriptedSource : SignatureSource {
  val script = HashMap<MediaId, SignaturePixels>()
  val asked = ArrayList<MediaId>()
  var failWith: RuntimeException? = null

  fun photo(id: MediaId, raster: Raster) {
    script[id] = SignaturePixels.Ready(listOf(raster))
  }

  override suspend fun pixelsOf(candidate: SimilarCandidate): SignaturePixels {
    asked += candidate.mediaId
    failWith?.let { throw it }
    return script[candidate.mediaId] ?: SignaturePixels.Never
  }
}
