package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CandidatePage
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.MediaFacts
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.database.AlbumCatalog
import dev.pampa.mycloudgallery.core.database.AttachMovedOutcome
import dev.pampa.mycloudgallery.core.database.CandidateKey
import dev.pampa.mycloudgallery.core.database.CandidateQuery
import dev.pampa.mycloudgallery.core.database.CandidateSource
import dev.pampa.mycloudgallery.core.database.CatalogCandidate
import dev.pampa.mycloudgallery.core.database.CatalogPlace
import dev.pampa.mycloudgallery.core.database.CommonAttestRequest
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// LE PORTE DEGLI ALBUM SUL CATALOGO LOCALE (Fase 7, P13; docs/decisioni.md "Fase 7 P1", tabella "Le porte di P0 che toccano core-database"). core-database
// non vede core-albums: i tipi del catalogo hanno la forma dei contratti, campo per campo, e queste classi sono traduzioni **senza logica** (prima
// stavano nei test di :app: `AlbumCatalogFacts` e `WorldCandidates`). **Solo il catalogo di chi legge**, mai il NAS (D9, E-S11).

/** `CatalogFacts` sopra le letture di P1 ([AlbumCatalog]): fatti per il piano della condivisione (D12) e attestazione nell'area comune (D9). */
class AlbumCatalogFacts(private val albums: suspend () -> AlbumCatalog?) : CatalogFacts {
  override suspend fun facts(media: Collection<MediaId>): Map<MediaId, MediaFacts> {
    val catalog = albums() ?: return emptyMap()
    return catalog.facts(media).mapValues { (_, f) ->
      MediaFacts(
        media = f.media,
        place = when (f.place) {
          CatalogPlace.PHONE_ONLY -> MediaPlace.PHONE_ONLY
          CatalogPlace.MY_PRIVATE -> MediaPlace.MY_PRIVATE
          CatalogPlace.COMMON -> MediaPlace.COMMON
          CatalogPlace.OTHERS_PRIVATE -> MediaPlace.OTHERS_PRIVATE
        },
        mine = f.mine, hidden = f.hidden, isVideo = f.isVideo, size = f.size, contentKey = f.contentKey, version = f.version,
        nas = f.nas?.let { NasItemRef(it.share, SharePath.parse(it.path)) }, onPhone = f.onPhone, sha256 = f.sha256,
      )
    }
  }

  override suspend fun attest(items: Collection<AttestRequest>): Map<ContentKey, MediaId> =
    albums()?.attestInCommon(items.map { CommonAttestRequest(it.contentKey, it.sha256) }).orEmpty()
}

/**
 * `AlbumCandidates` sopra `MediaCatalog.albums.candidates` (P1): giorni come `aaaammgg`, sorgenti con gli stessi codici di [SmartSource], il cursore
 * come i quattro campi di [CandidateKey] in testo (opaco per chi lo usa). Un filtro con un insieme di media ([CandidateFilter.media]) legge solo
 * quelli (`candidatesOf`), una pagina sola, con gli altri criteri applicati qui. Solo celle visibili (le query di P1 tolgono i nascosti; il
 * contagio dei gruppi lo applica chi mostra, con `VisibleMedia`).
 */
class CatalogAlbumCandidates(private val albums: suspend () -> AlbumCatalog?) : AlbumCandidates {

  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage {
    val catalog = albums() ?: return CandidatePage(emptyList(), null)
    val sources = filter.sources.mapNotNullTo(HashSet()) { s -> CandidateSource.entries.firstOrNull { it.code == s.code } }
    val days = filter.days?.let { dayInt(it.from)..dayInt(it.to) }
    val chosen = filter.media
    if (chosen != null) {
      if (after != null || chosen.isEmpty()) return CandidatePage(emptyList(), null)
      val items = catalog.candidatesOf(chosen)
        .filter { c -> c.source in sources && (days == null || dayInt(c.day) in days) && (!filter.withGpsOnly || c.gps != null) }
        .sortedWith(compareBy({ it.day }, { it.takenAtUtcMs }, { it.media.value }))
        .map { it.toCandidate() }
      return CandidatePage(items, null)
    }
    val query = CandidateQuery(days = days, sources = sources, withGpsOnly = filter.withGpsOnly)
    val page = catalog.candidates(query, after?.let(::keyOf), limit)
    return CandidatePage(page.items.map { it.toCandidate() }, page.next?.let { CandidateCursor("${it.placeId}|${it.day}|${it.time}|${it.media}") })
  }

  override suspend fun visibleTotal(): Int = albums()?.visibleCount() ?: 0

  private fun keyOf(cursor: CandidateCursor): CandidateKey? {
    val parts = cursor.value.split('|')
    if (parts.size != 4) return null
    return CandidateKey(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2].toLongOrNull() ?: return null, parts[3])
  }

  private fun CatalogCandidate.toCandidate() = AlbumCandidate(
    media, day, takenAtUtcMs, localTime, gps, placeId ?: 0, originDevice, SmartSource.entries.first { it.code == source.code }, kind, screen, pixels,
  )

  private fun dayInt(d: LocalDate): Int = d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth
}

/**
 * La porta di completamento degli spostamenti (D29, §2.5 passo 5; `MoveCompletionSink` di P4): **lo stesso** media della sorgente prende la
 * posizione della destinazione nel catalogo (`MediaCatalog.attachMoved`, idempotente, una transazione), **prima** che la sorgente vada nel cestino.
 * Al posto di `CatalogMoveCompletions` (campo A) in `NasOpsConfig`: una porta sola, vincolante (P4, decisione 1).
 *
 * - una sorgente che il catalogo non conosce (un file mai scansionato, o gia' collegato a un giro prima) non ha niente da tenere legato: fatto;
 * - `Attached`/`AlreadyAttached`: fatto;
 * - la destinazione e' gia' un altro media, o i byte non tornano con la versione (dimensione, hash): **errore**, l'elemento resta in `PUBLISHED`
 *   con la sorgente al suo posto e il motore riprova (la scansione nel frattempo unisce o aggiorna il catalogo). Mai la sorgente nel cestino prima
 *   che il catalogo conosca la destinazione (L74).
 */
class CatalogMoveCompletionSink(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: AccountId,
) : MoveCompletionSink {
  override suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256) {
    val c = catalog() ?: throw IllegalStateException("Catalogo non pronto.")
    val from = Location.Nas(account, source.share, NasPath.of(source.path.value))
    val to = Location.Nas(account, dest.share, NasPath.of(dest.path.value), stamp.fileId)
    val info = c.nasLocationAt(from)
    val media = info?.let { c.mediaIdOfLocation(it.locationId) }
    if (info == null || media == null || info.state == LocationState.ABSENT) {
      // La sorgente non e' (piu') nel catalogo: se la destinazione e' gia' collegata, e' un secondo giro; altrimenti non c'e' niente da tenere legato.
      return
    }
    when (c.attachMoved(media, to, stamp.toFileStamp(), from = from.key, sha256 = sha256)) {
      AttachMovedOutcome.Attached, AttachMovedOutcome.AlreadyAttached, AttachMovedOutcome.UnknownMedia -> Unit
      AttachMovedOutcome.DestinationOfOtherMedia -> throw IllegalStateException("Destinazione di un altro media: si riprova dopo la scansione.")
      AttachMovedOutcome.SizeMismatch, AttachMovedOutcome.ContentMismatch -> throw IllegalStateException("Byte diversi dalla versione del catalogo.")
    }
  }
}

/**
 * Le cartelle dell'area comune viste dal catalogo locale (D39, E-SH16): i media che hanno un file presente sotto una cartella, e le cartelle in cui
 * stanno i file di certi media. Sola lettura del database: mai il NAS, mai un'azione.
 */
class CatalogCommonFolders(private val database: GalleryDatabase) {

  /** I media con un file presente (o non raggiungibile adesso) dentro [folder], sottocartelle comprese. */
  suspend fun contents(folder: ShareFolder): Set<MediaId> = withContext(Dispatchers.IO) {
    val prefix = folder.path.value.trimEnd('/') + "/"
    val out = LinkedHashSet<MediaId>()
    database.openHelper.readableDatabase.query(CONTENTS_SQL, arrayOf<Any?>(Location.Nas.shareKeyOf(folder.share), prefix.length, prefix)).use { c ->
      while (c.moveToNext()) runCatching { MediaId(c.getString(0)) }.getOrNull()?.let(out::add)
    }
    out
  }

  /** Le cartelle (relative alla share) in cui stanno i file presenti di [media] nella share [share]. */
  suspend fun foldersOf(share: String, media: Collection<MediaId>): Map<MediaId, Set<SharePath>> = withContext(Dispatchers.IO) {
    val out = HashMap<MediaId, MutableSet<SharePath>>()
    for (chunk in media.distinct().chunked(CHUNK)) {
      val marks = chunk.joinToString(",") { "?" }
      val args: Array<Any?> = (listOf<Any?>(Location.Nas.shareKeyOf(share)) + chunk.map { it.value }).toTypedArray()
      database.openHelper.readableDatabase.query(FOLDERS_SQL.replace("(:ids)", "($marks)"), args).use { c ->
        while (c.moveToNext()) {
          val id = runCatching { MediaId(c.getString(0)) }.getOrNull() ?: continue
          val parent = runCatching { SharePath.parse(c.getString(1)).parent() }.getOrNull() ?: continue
          out.getOrPut(id) { LinkedHashSet() } += parent
        }
      }
    }
    out
  }

  private companion object {
    const val CHUNK = 500
    const val CONTENTS_SQL: String =
      "SELECT DISTINCT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
        "WHERE l.location_type = 'nas' AND l.share_key = ? AND l.state IN ('present', 'unavailable') " +
        "AND lower(substr(l.nas_path, 1, ?)) = lower(?) ORDER BY v.media_id"
    const val FOLDERS_SQL: String =
      "SELECT v.media_id, l.nas_path FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
        "WHERE l.location_type = 'nas' AND l.share_key = ? AND l.state = 'present' AND v.media_id IN (:ids)"
  }
}
