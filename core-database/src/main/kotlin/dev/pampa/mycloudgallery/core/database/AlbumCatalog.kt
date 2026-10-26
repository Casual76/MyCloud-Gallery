package dev.pampa.mycloudgallery.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.pampa.mycloudgallery.core.database.dao.AlbumQueries
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.database.entity.CandidateRow
import dev.pampa.mycloudgallery.core.database.entity.DuplicateRow
import dev.pampa.mycloudgallery.core.database.entity.FactsNasRow
import dev.pampa.mycloudgallery.core.database.entity.FactsRow
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.enumByCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Le **letture degli album** sul catalogo (Fase 7, P1; `docs/modello-dati.md` §14.4): i candidati degli album smart e dei viaggi, i fatti
 * dei media per la condivisione, l'attestazione degli elementi condivisi (D9) e i Doppioni. Solo letture: nessuna scrive il grafo dei
 * media, nessuna annuncia [MediaCatalog.changes] (come [MediaCatalog.aiIndex]). `core-database` non vede `core-albums`: i tipi
 * restituiti hanno la forma delle porte di P0 (`AlbumCandidates`, `CatalogFacts`), campo per campo, e l'adattatore di `:app` (P13) le
 * traduce senza logica.
 *
 * **Mai un nascosto** (D22): ogni lettura filtra con `media_personal`, la fonte che non e' mai in ritardo. Nessun dato dell'utente nei
 * log: questa classe non ne scrive.
 */
class AlbumCatalog internal constructor(private val database: GalleryDatabase, private val localAccount: AccountId) {

  private val dao = database.albumQueryDao()

  // --- candidati ---------------------------------------------------------------------------------------------------------

  /**
   * Una pagina di candidati di [query], dopo il cursore [after] (null = dall'inizio), di al piu' [limit] celle. In ordine (giorno,
   * istante, id); con piu' luoghi in [CandidateQuery.placeIds] prima per luogo (id crescente) e dentro il luogo per (giorno, istante, id).
   * [CatalogCandidatePage.next] e' il cursore della pagina dopo, null all'ultima. Dall'indice, senza ordinare in memoria
   * (`AlbumQueriesPerformanceTest`): 100 mila media, una pagina in meno di 50 ms.
   */
  suspend fun candidates(query: CandidateQuery, after: CandidateKey? = null, limit: Int = CANDIDATE_PAGE): CatalogCandidatePage {
    require(limit in 1..MAX_CANDIDATE_PAGE) { "Pagina fuori misura." }
    val first = query.days?.first ?: AlbumQueries.FIRST_DAY
    val last = query.days?.last ?: AlbumQueries.LAST_DAY
    val me = localAccount.value
    val rows = ArrayList<CandidateRow>()
    val places = query.placeIds
    if (query.originDevice == null && places != null) {
      // Un luogo alla volta, in ordine: la chiave davanti all'indice e' un'uguaglianza e il giorno non si riordina in memoria.
      for (place in places.sorted()) {
        if (after != null && after.placeId != CandidateKey.NO_PLACE && place < after.placeId) continue
        val cursor = after?.takeIf { it.placeId == place }
        val from = maxOf(first, cursor?.day ?: first)
        val sql = AlbumQueries.candidates(query, AlbumQueries.Lead.PLACE, place, from, last, me, cursor, limit + 1 - rows.size)
        rows += dao.candidates(sql)
        if (rows.size > limit) break
      }
    } else {
      val lead = if (query.originDevice != null) AlbumQueries.Lead.DEVICE else AlbumQueries.Lead.DAY
      val from = maxOf(first, after?.day ?: first)
      rows += dao.candidates(AlbumQueries.candidates(query, lead, null, from, last, me, after, limit + 1))
    }
    val more = rows.size > limit
    val page = if (more) rows.subList(0, limit) else rows
    val byPlace = query.originDevice == null && places != null
    val next = if (more) page.last().let { CandidateKey(if (byPlace) it.placeId ?: CandidateKey.NO_PLACE else CandidateKey.NO_PLACE, it.localDay, it.sortTime, it.mediaId) } else null
    return CatalogCandidatePage(page.mapNotNull(::toCandidate), next)
  }

  /** Le celle visibili fra [ids] (le altre non ci sono), in ordine (giorno, istante, id): per rivalutare i media di un cambiamento. */
  suspend fun candidatesOf(ids: Collection<MediaId>): List<CatalogCandidate> =
    ids.map { it.value }.distinct().chunked(AlbumQueries.MAX_IDS).flatMap { dao.candidatesOf(it) }
      .sortedWith(compareBy<CandidateRow>({ it.localDay }, { it.sortTime }, { it.mediaId })).mapNotNull(::toCandidate)

  /** Quante celle visibili ha la Libreria: il denominatore di "regole troppo larghe" (E-AL6, oltre il 20%). */
  suspend fun visibleCount(): Int = dao.count(SimpleSQLiteQuery(AlbumQueries.VISIBLE_COUNT_SQL))

  /** I giorni locali `aaaammgg` di [ids]: gli smart da ricalcolare dopo un [MediaCatalog.changes] sono quelli che coprono questi giorni. */
  suspend fun daysOf(ids: Collection<MediaId>): Map<MediaId, Int> {
    val out = HashMap<MediaId, Int>()
    for (chunk in ids.map { it.value }.distinct().chunked(AlbumQueries.MAX_IDS)) {
      dao.daysOf(chunk).forEach { out[MediaId(it.mediaId)] = it.localDay }
    }
    return out
  }

  /**
   * Fra [ids] i media **mostrabili**: presenti in qualche posto e non nascosti (D22: la fonte che le superfici di album, Raccolte e
   * Utility usano per `VisibleMedia`). Gli id assorbiti da un'unione contano come il sopravvissuto. Un media membro di un gruppo non
   * nascosto e' mostrabile (la cella del gruppo e' un'altra domanda: i candidati).
   */
  suspend fun visibleAmong(ids: Collection<MediaId>): Set<MediaId> {
    val out = HashSet<MediaId>()
    for (chunk in ids.map { it.value }.distinct().chunked(AlbumQueries.MAX_IDS)) {
      dao.visibleAmong(chunk).mapTo(out, ::MediaId)
      dao.visibleAliasesAmong(chunk).mapTo(out, ::MediaId)
    }
    return out
  }

  private fun toCandidate(row: CandidateRow): CatalogCandidate? {
    val day = dayOf(row.localDay) ?: return null
    val me = localAccount.value
    val source = when {
      row.cellFlags and CellFlags.ON_PHONE != 0 -> CandidateSource.MY_PHONE
      CopyAreas.has(row.copyAreas, CopyAreas.PRIVATE) -> CandidateSource.MY_PRIVATE
      row.ownerAccount != null && row.ownerAccount != me -> CandidateSource.FAMILY
      else -> CandidateSource.COMMON
    }
    val offset = row.takenOffsetMinutes
    val local = if (row.takenAtUtc != null && offset != null) {
      runCatching { Instant.ofEpochMilli(row.takenAtUtc).atOffset(ZoneOffset.ofTotalSeconds(offset * SECONDS_PER_MINUTE)).toLocalTime() }.getOrNull()
    } else {
      null
    }
    val gps = if (row.latitude != null && row.longitude != null) runCatching { GeoPoint(row.latitude, row.longitude) }.getOrNull() else null
    return CatalogCandidate(
      media = MediaId(row.mediaId),
      day = day,
      takenAtUtcMs = row.sortTime,
      localTime = local,
      gps = gps,
      placeId = row.placeId,
      originDevice = row.originDevice,
      source = source,
      kind = enumByCode<MediaKind>(row.kind),
      screen = row.source == MediaSource.SCREENSHOT.code,
      pixels = (row.width ?: 0).toLong() * (row.height ?: 0),
      ownedByMe = row.ownerAccount == me,
    )
  }

  // --- fatti per la condivisione ----------------------------------------------------------------------------------------

  /**
   * I fatti dei media [ids] (D12, §2.5 passo 1): dove stanno, di chi sono, se sono nascosti, quanto pesano, la chiave di contenuto e il
   * file del NAS da cui si parte. Un media sconosciuto manca dalla mappa; un id assorbito da un'unione si risolve (la chiave della mappa
   * e' l'id **chiesto**, [CatalogMediaFacts.media] quello vero). Anche i nascosti: li filtra il piano (Q3), non questa lettura.
   */
  suspend fun facts(ids: Collection<MediaId>): Map<MediaId, CatalogMediaFacts> {
    val requested = ids.distinct()
    val aliasOf = HashMap<String, String>()
    for (chunk in requested.map { it.value }.chunked(AlbumQueries.MAX_IDS)) dao.aliases(chunk).forEach { aliasOf[it.aliasId] = it.mediaId }
    val real = requested.map { aliasOf[it.value] ?: it.value }.distinct()
    val rows = HashMap<String, FactsRow>()
    val nas = HashMap<String, MutableList<FactsNasRow>>()
    for (chunk in real.chunked(AlbumQueries.MAX_IDS)) {
      dao.facts(chunk).forEach { rows[it.mediaId] = it }
      dao.factsNas(chunk).forEach { nas.getOrPut(it.mediaId) { ArrayList() } += it }
    }
    val me = localAccount.value
    val out = LinkedHashMap<MediaId, CatalogMediaFacts>()
    for (request in requested) {
      val id = aliasOf[request.value] ?: request.value
      val row = rows[id] ?: continue
      val copies = nas[id].orEmpty()
      val common = copies.firstOrNull { it.area == AREA_COMMON }
      val mine = copies.firstOrNull { it.area == AREA_PRIVATE }
      val chosen = common ?: mine ?: copies.firstOrNull()
      val place = when {
        common != null -> CatalogPlace.COMMON
        mine != null -> CatalogPlace.MY_PRIVATE
        copies.isNotEmpty() -> CatalogPlace.OTHERS_PRIVATE
        else -> CatalogPlace.PHONE_ONLY
      }
      out[request] = CatalogMediaFacts(
        requested = request,
        media = MediaId(id),
        place = place,
        mine = row.ownerAccount == me,
        hidden = row.hidden,
        isVideo = row.kind == MediaKind.VIDEO.code,
        size = row.size,
        contentKey = FastContentKey.of(row.quickHash, row.size),
        version = VersionId(row.versionId),
        nas = chosen?.let { CatalogNasFile(it.shareName, it.nasPath) },
        onPhone = row.cellFlags and CellFlags.ON_PHONE != 0,
      )
    }
    return out
  }

  /**
   * D9, l'attestazione: per ogni richiesta il media del catalogo **di chi legge** che ha, **nell'area comune**, una copia presente con la
   * stessa chiave di contenuto (e lo stesso hash completo se entrambi lo hanno). Un elemento che rimanda a un file privato, sparito o
   * diverso non c'e' nella mappa: non si mostra, non si conta e **non avvia nulla**. Con due media con la stessa chiave vince l'id piu'
   * basso (una sola risposta). Legge le versioni dell'area comune a cursore, senza tenerle in memoria: costa quanto l'area comune.
   */
  suspend fun attestInCommon(requests: Collection<CommonAttestRequest>): Map<ContentKey, MediaId> {
    if (requests.isEmpty()) return emptyMap()
    val wanted = requests.groupBy { it.contentKey }
    val found = HashMap<ContentKey, String>()
    withContext(Dispatchers.IO) {
      database.openHelper.readableDatabase.query(SimpleSQLiteQuery(AlbumQueries.COMMON_VERSIONS_SQL)).use { cursor ->
        while (cursor.moveToNext()) {
          val key = FastContentKey.of(cursor.getString(2), cursor.getLong(1)) ?: continue
          val asked = wanted[key] ?: continue
          val full = if (cursor.isNull(3)) null else cursor.getString(3)
          if (asked.none { it.fullHash == null || full == null || it.fullHash.hex == full }) continue
          val media = cursor.getString(0)
          val known = found[key]
          if (known == null || media < known) found[key] = media
        }
      }
    }
    return found.mapValues { MediaId(it.value) }
  }

  // --- Doppioni -------------------------------------------------------------------------------------------------------------

  /**
   * I **doppioni identici** (D25, §2.1.1): le versioni di media visibili (presenti, non nascosti) con **almeno due copie presenti dalla
   * stessa parte** ([DuplicatePart]: due sul telefono, o due sul NAS, area privata e comune insieme), in ordine di `version_id`, dopo il
   * cursore [after]. Al piu' [limit] gruppi per pagina; [DuplicatePage.next] e' il cursore della pagina dopo, null alla fine. Una versione con
   * due copie sul telefono e due sul NAS da' due gruppi. Dall'indice delle posizioni per versione, senza ordinare: Libreria da 100 mila
   * media in meno di 200 ms (`AlbumQueriesPerformanceTest`).
   */
  suspend fun duplicates(after: String? = null, limit: Int = DUPLICATE_PAGE): DuplicatePage {
    require(limit in 1..MAX_DUPLICATE_PAGE) { "Pagina fuori misura." }
    val groups = ArrayList<DuplicateGroup>()
    var cursor = after.orEmpty()
    while (true) {
      val versions = dao.versions(SimpleSQLiteQuery(AlbumQueries.MULTI_LOCATION_VERSIONS_SQL, arrayOf<Any?>(cursor, DUPLICATE_SCAN_BATCH)))
      if (versions.isEmpty()) return DuplicatePage(groups, null)
      val copies = versions.chunked(AlbumQueries.MAX_IDS).flatMap { dao.duplicateCopies(it) }.groupBy { it.versionId }
      for (version in versions) {
        copies[version]?.let { groups += groupsOf(it) }
        if (groups.size >= limit) return DuplicatePage(groups, version)
      }
      if (versions.size < DUPLICATE_SCAN_BATCH) return DuplicatePage(groups, null)
      cursor = versions.last()
    }
  }

  private fun groupsOf(rows: List<DuplicateRow>): List<DuplicateGroup> {
    val first = rows.first()
    return rows.groupBy { it.locationType }.mapNotNull { (type, same) ->
      if (same.size < 2) return@mapNotNull null
      DuplicateGroup(
        media = MediaId(first.mediaId),
        version = VersionId(first.versionId),
        part = if (type == PHONE_TYPE) DuplicatePart.PHONE else DuplicatePart.NAS,
        fullHash = first.fullHash?.let(::Sha256),
        copies = same.sortedBy { it.locationId }.map(::toCopy),
      )
    }.sortedBy { it.part.ordinal }
  }

  private fun toCopy(row: DuplicateRow): DuplicateCopy {
    val phone = row.locationType == PHONE_TYPE
    return DuplicateCopy(
      location = LocationId(row.locationId),
      place = when {
        phone -> CopyPlace.PHONE
        row.area == AREA_PRIVATE -> CopyPlace.MY_PRIVATE
        row.area == AREA_COMMON -> CopyPlace.COMMON
        else -> CopyPlace.OTHERS
      },
      share = if (phone) null else row.shareName,
      path = if (phone) row.relativePath.orEmpty() + row.displayName.orEmpty() else row.nasPath.orEmpty(),
      size = row.size,
      modifiedAt = row.modifiedAt,
      verifiedAt = row.verifiedAt,
      state = enumByCode<LocationState>(row.state),
    )
  }

  companion object {
    const val CANDIDATE_PAGE: Int = 500
    const val MAX_CANDIDATE_PAGE: Int = 5_000

    /** Quanti gruppi per pagina di [duplicates] e quante versioni si guardano per volta. */
    const val DUPLICATE_PAGE: Int = 500
    const val MAX_DUPLICATE_PAGE: Int = 5_000
    private const val DUPLICATE_SCAN_BATCH: Int = TimelineIndexDao.MAX_IDS

    private const val SECONDS_PER_MINUTE = 60
    private const val AREA_PRIVATE = "private"
    private const val AREA_COMMON = "common"
    private const val PHONE_TYPE = "phone"

    /** `aaaammgg` come data, o null se non lo e' (un giorno mai calcolato vale 0). */
    internal fun dayOf(localDay: Int): LocalDate? {
      if (localDay <= 0) return null
      return runCatching { LocalDate.of(localDay / 10_000, localDay / 100 % 100, localDay % 100) }.getOrNull()
    }
  }
}
