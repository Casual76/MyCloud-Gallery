package dev.pampa.mycloudgallery.hidden

import android.content.Context
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.MediaStoreCursor
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.data.CatalogLocalMediaActions
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.domain.Observation
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

// Fase 7 (P9, D22): costruisce un [WorldSpec] nel catalogo VERO (Room in memoria, Robolectric) passando dalle stesse strade dell'app: le
// osservazioni di una scansione, i gruppi del catalogo, il gesto "Nascondi" di `CatalogLocalMediaActions` (che propaga ai gruppi) e, per il dato
// "a meta'", la scrittura diretta in `media_personal`. L'istantanea della Libreria e' quella vera (`CatalogTimelineSource.buildIndex`).

/** Dove sta sul NAS un media che ci sta: la share e il percorso (quello che Cartelle NAS elenca). */
internal class NasPlace(val share: String, val path: String) {
  override fun toString(): String = "NasPlace"
}

/** Un mondo costruito: lo [spec], il catalogo vero con la sua istantanea, e l'oracolo tradotto in `MediaId`. */
internal class BuiltWorld(
  val spec: WorldSpec,
  val oracle: HiddenOracle,
  val db: GalleryDatabase,
  val catalog: MediaCatalog,
  /** Il `MediaId` di ogni media, per indice dello spec. */
  val ids: List<MediaId>,
  val snapshot: ColumnarTimelineSnapshot,
  /** Dove sta sul NAS ogni media che ci sta (per indice). */
  val nas: Map<Int, NasPlace>,
) {
  private val indexOfId: Map<MediaId, Int> = ids.withIndex().associate { it.value to it.index }

  fun idsOf(indices: Collection<Int>): Set<MediaId> = indices.mapTo(LinkedHashSet()) { ids[it] }

  fun indexOf(id: MediaId): Int = indexOfId.getValue(id)

  /** Cio' che non deve mai uscire dalle superfici che usano l'istantanea, in `MediaId`. */
  val mustNeverShow: Set<MediaId> get() = idsOf(oracle.mustNeverShow)

  /** Cio' che non deve uscire dalle superfici che dopo l'istantanea chiedono ancora al catalogo: anche gli ultimi nascosti e i loro gruppi. */
  val liveMustNeverShow: Set<MediaId> get() = idsOf(oracle.liveMustNeverShow)

  val liveShowable: Set<MediaId> get() = idsOf(oracle.liveShowable)

  val liveLibraryCells: Set<MediaId> get() = idsOf(oracle.liveLibraryCells)

  /** Le celle che la Libreria deve avere, in `MediaId`. */
  val libraryCells: Set<MediaId> get() = idsOf(oracle.libraryCells)

  /** Tutti i media che si possono mostrare. */
  val showable: Set<MediaId> get() = idsOf(oracle.showable)

  val allIds: Set<MediaId> get() = ids.toSet()

  fun close() {
    db.close()
  }
}

internal object HiddenWorldBuilder {

  const val ME = "test"
  const val PRIVATE_SHARE = "test"
  const val COMMON_SHARE = "test-comune"
  private const val VOLUME = "external_primary"
  private const val STORE = "store-v1"
  private const val T0 = 1_780_000_000_000L

  /** Gli `_ID` del MediaStore e gli identificativi di file delle seconde copie (P8): lontani da quelli delle prime, per ricavare l'indice. */
  const val PHONE_ORIGINAL_BASE = 10_000L
  const val PHONE_COPY_BASE = 30_000L
  const val NAS_COPY_BASE = 5_000L

  suspend fun build(context: Context, spec: WorldSpec): BuiltWorld {
    val me = AccountId.of(ME)
    val db = GalleryDatabase.build(context, name = null)
    var now = T0
    val catalog = MediaCatalog(db, me, zone = { ZoneId.of("Europe/Rome") }) { now }
    catalog.saveMediaStoreCursor(MediaStoreCursor(VOLUME, msVersion = STORE))
    catalog.registerShare(NasShare(me, PRIVATE_SHARE, NasArea.PRIVATE))
    catalog.registerShare(NasShare(me, COMMON_SHARE, NasArea.COMMON))

    val ids = ArrayList<MediaId>(spec.media.size)
    val nas = HashMap<Int, NasPlace>()
    for (m in spec.media) {
      val takenAt = m.day.atStartOfDay().plusMinutes(m.minuteOfDay.toLong()).toInstant(ZoneOffset.ofHours(2)).toEpochMilli()
      // Un contenuto proprio per ogni media (seme = mondo + indice): due media diversi non si confondono mai.
      val bytes = Random(spec.seed * 1_000 + m.index).nextBytes(1_024)
      val fingerprint = Fingerprint.ofContent(bytes)
      var id: MediaId? = null
      if (m.onPhone) {
        val where = Location.Phone(VOLUME, "DCIM/Camera/", "IMG_${spec.seed}_${m.index}.jpg", PHONE_ORIGINAL_BASE + m.index, STORE)
        id = catalog.record(Observation(where, FileStamp(fingerprint.size, T0 + m.index, 7), fingerprint, m.kind, m.source, takenAt), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId
        // Una seconda copia dalla stessa parte (P8): gli stessi byte in `Download/`, con un'altra riga del MediaStore.
        if (m.index in spec.sameSideCopies) {
          val copy = Location.Phone(VOLUME, "Download/", "IMG_${spec.seed}_${m.index} (1).jpg", PHONE_COPY_BASE + m.index, STORE)
          catalog.record(Observation(copy, FileStamp(fingerprint.size, T0 + 5_000 + m.index, 7), fingerprint, m.kind, m.source, takenAt), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
        }
      }
      if (m.onNas) {
        val share = if (m.where == Where.NAS_COMMON) COMMON_SHARE else PRIVATE_SHARE
        val path = "Foto/${m.day.year}/IMG_${spec.seed}_${m.index}.jpg"
        val where = Location.Nas(me, share, NasPath.of(path), fileId = 500L + m.index)
        val recorded = catalog.record(Observation(where, FileStamp(fingerprint.size, T0 + m.index), fingerprint, m.kind, m.source, takenAt), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
        id = checkNotNull(recorded.mediaId) { "il media ${m.index} non e' entrato nel catalogo" }
        nas[m.index] = NasPlace(share, path)
        // Una seconda copia dalla stessa parte (P8): gli stessi byte nella cartella di backup di un dispositivo della stessa share.
        if (m.index in spec.sameSideCopies) {
          val copy = Location.Nas(me, share, NasPath.of("Backup/Pixel 9/${m.day.year}/IMG_${spec.seed}_${m.index}.jpg"), fileId = NAS_COPY_BASE + m.index)
          catalog.record(Observation(copy, FileStamp(fingerprint.size, T0 + 5_000 + m.index), fingerprint, m.kind, m.source, takenAt), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
        }
      }
      val media = checkNotNull(id) { "il media ${m.index} non ha nessun posto" }
      catalog.updateMetadata(
        catalog.media(media)!!.currentVersionId,
        MediaMetadata(
          mimeType = if (m.kind.isVideo) "video/mp4" else "image/jpeg", width = 4_000, height = 3_000,
          durationMillis = if (m.kind.isVideo) 8_000L else null,
          captureTime = CaptureTime(takenAt, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
        ),
      )
      ids += media
    }

    val degraded = HashSet<GroupSpec>()
    for (group in spec.groups) {
      val members = group.members.map { ids[it] }
      val cover = ids[group.cover]
      if (group.kind == MediaGroupKind.SIMILAR_COPIES) {
        val outcome = catalog.groupSimilar(members, cover)
        // Un gruppo che il catalogo rifiuta (un membro e' gia' in un altro) diventa una raffica: il test non perde il gruppo.
        if (outcome.javaClass.simpleName.startsWith("Refused")) {
          catalog.group(MediaGroupKind.BURST, members, cover)
          degraded += group
        }
      } else {
        catalog.group(MediaGroupKind.BURST, members, cover)
      }
    }

    val source = CatalogTimelineSource(db, catalog)
    val actions = CatalogLocalMediaActions(catalog, source, repository = null)
    // Il gesto dell'utente: passa dal codice vero, che porta il nascosto a tutto il gruppo.
    if (spec.hiddenByUser.isNotEmpty()) actions.setHidden(spec.hiddenByUser.mapTo(HashSet()) { ids[it] }, true)
    if (spec.favorites.isNotEmpty()) actions.setFavorite(spec.favorites.mapTo(HashSet()) { ids[it] }, true)
    // Il dato arrivato a meta' (E-L11): una sola copia, con la sola API del catalogo e senza il gesto che espande al gruppo. Per le copie simili il
    // catalogo porta da se' il nascosto a tutto il gruppo (F5-D36); un gruppo che il catalogo ha rifiutato ed e' diventato una raffica (qui sotto) non
    // ha quella difesa, quindi la sua scrittura si espande come il gesto.
    now += 1_000
    for (index in spec.hiddenPartial) {
      val group = spec.groups.first { index in it.members }
      if (degraded.contains(group)) {
        actions.setHidden(setOf(ids[index]), true)
      } else {
        catalog.setPersonalFlags(listOf(ids[index])) { it.copy(hidden = true) }
      }
    }

    val snapshot = source.buildIndex()
    // Gli ultimi nascosti arrivano DOPO l'istantanea e senza passare dal catalogo: l'istantanea ce li ha ancora (nascosto un attimo fa, dato a meta').
    now += 1_000
    for (index in spec.lateHidden) db.personalDao().upsert(MediaPersonalEntity(ids[index].value, favorite = false, hidden = true, updatedAt = now))
    return BuiltWorld(spec, HiddenOracle(spec), db, catalog, ids, snapshot, nas)
  }
}
