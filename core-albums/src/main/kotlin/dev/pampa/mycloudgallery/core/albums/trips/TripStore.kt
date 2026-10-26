package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.HomeEstimateEntity
import dev.pampa.mycloudgallery.core.database.entity.TripProposalEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.DateTimeException
import java.time.LocalDate

// LA MEMORIA LOCALE DI VIAGGI ED EVENTI (docs/fase7-progetto.md D18, D34): `home_estimate` (la casa) e `trip_proposal` (le proposte), due
// tabelle **locali**: mai sincronizzate, pubblicate, esportate o scritte nei log; le toglie l'azzeramento. Questo e' l'**unico** posto dove si
// legge e si scrive la casa (Phase7ArchitectureTest cerca `home_estimate` fuori da core-database e da trips/). `internal`: le coordinate non
// escono da trips/.

/** Una proposta com'e' in memoria: titolo e luoghi fissati alla rilevazione, conteggio e copertina da ricontrollare prima di mostrarli. */
internal class StoredProposal(
  val id: TripId,
  val kind: TripKind,
  val days: LocalDayRange,
  val label: String,
  val count: Int,
  val cover: MediaId?,
  val placeIds: List<Int>,
  /** L'album nato da "Crea album": una proposta gia' album non si ripropone. */
  val album: AlbumId?,
  val detectedAtMs: Long,
) {
  fun copy(count: Int = this.count, cover: MediaId? = this.cover, album: AlbumId? = this.album): StoredProposal =
    StoredProposal(id, kind, days, label, count, cover, placeIds, album, detectedAtMs)

  override fun toString(): String = "StoredProposal(${kind.code}, $count)"
}

internal interface TripStore {
  /** La casa e i luoghi abituali di oggi, o null se non c'e' una stima. */
  suspend fun anchors(): Anchors?

  /** Conserva la stima di oggi (null = nessuna casa: la stima di prima si toglie). */
  suspend fun saveAnchors(anchors: Anchors?, estimatedAtMs: Long)

  /** Le proposte conservate, dalla piu' recente. */
  suspend fun proposals(): List<StoredProposal>

  /** Sostituisce le proposte in una transazione sola. */
  suspend fun replaceProposals(rows: List<StoredProposal>)

  suspend fun markAccepted(id: TripId, album: AlbumId)
}

internal class RoomTripStore(private val database: GalleryDatabase) : TripStore {
  private val dao = database.tripDao()

  override suspend fun anchors(): Anchors? {
    val rows = dao.home().sortedBy { it.slot }
    val home = rows.firstOrNull { it.slot == HOME_SLOT } ?: return null
    return Anchors(anchorOf(home), rows.filter { it.slot != HOME_SLOT }.map(::anchorOf), home.totalDays)
  }

  override suspend fun saveAnchors(anchors: Anchors?, estimatedAtMs: Long) {
    database.transaction {
      dao.clearHome()
      if (anchors != null) {
        dao.upsertHome(
          anchors.all.mapIndexed { i, a ->
            HomeEstimateEntity(i, if (i == HOME_SLOT) KIND_HOME else KIND_HABITUAL, a.lat, a.lon, a.radiusKm, a.days, anchors.gpsDays, estimatedAtMs)
          },
        )
      }
    }
  }

  override suspend fun proposals(): List<StoredProposal> = dao.proposals().mapNotNull(::storedOf)

  override suspend fun replaceProposals(rows: List<StoredProposal>) {
    database.transaction {
      dao.clearProposals()
      dao.upsertProposals(rows.map(::entityOf))
    }
  }

  override suspend fun markAccepted(id: TripId, album: AlbumId) {
    dao.setAlbum(id.value, album.value)
  }

  private fun anchorOf(row: HomeEstimateEntity) = Anchor(row.latitude, row.longitude, row.radiusKm, row.days)

  /** Una riga che non si capisce (un giorno impossibile, un tipo sconosciuto) non e' una proposta: si salta, mai un'eccezione. */
  private fun storedOf(row: TripProposalEntity): StoredProposal? = try {
    StoredProposal(
      id = TripId(row.tripId),
      kind = TripKind.entries.first { it.code == row.kind },
      days = LocalDayRange(dayOf(row.fromDay), dayOf(row.toDay)),
      label = row.label,
      count = row.mediaCount,
      cover = row.coverMediaId?.let { runCatching { MediaId(it) }.getOrNull() },
      placeIds = row.placeIds.split(',').mapNotNull { it.trim().toIntOrNull() },
      album = row.albumId?.let { runCatching { AlbumId(it) }.getOrNull() },
      detectedAtMs = row.detectedAt,
    )
  } catch (e: IllegalArgumentException) {
    null
  } catch (e: NoSuchElementException) {
    null
  } catch (e: DateTimeException) {
    null
  }

  private fun entityOf(p: StoredProposal) = TripProposalEntity(
    tripId = p.id.value,
    kind = p.kind.code,
    fromDay = intOf(p.days.from),
    toDay = intOf(p.days.to),
    label = p.label,
    mediaCount = p.count,
    coverMediaId = p.cover?.value,
    placeIds = p.placeIds.joinToString(","),
    albumId = p.album?.value,
    detectedAt = p.detectedAtMs,
  )

  private fun intOf(day: LocalDate): Int = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth

  private fun dayOf(value: Int): LocalDate = LocalDate.of(value / 10_000, value / 100 % 100, value % 100)

  private companion object {
    const val HOME_SLOT = 0
    const val KIND_HOME = "home"
    const val KIND_HABITUAL = "habitual"
  }
}
