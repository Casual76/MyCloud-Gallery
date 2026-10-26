package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.FormerShare
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.database.CatalogCandidate
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbumState
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// LA LETTURA DEGLI ALBUM PERSONALI DALLE TABELLE (docs/fase7-progetto.md D21, D22, §2.1): cio' che `Albums.all` e `Albums.content` mostrano.
// I conteggi e il giorno piu' recente li da' una query sola che non conta mai un nascosto (`AlbumDao.summaries`, `media_personal`); la copertina e
// il contenuto passano da `VisibleMedia` e dal filtro finale `HiddenMask` (D22: una superficie nuova legge dalla stessa fonte delle altre,
// e controlla ancora subito prima di mostrare). Nessun titolo, id o luogo nei log (D37).

internal class AlbumReader(
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
) {

  /**
   * Quali di [ids] si possono mostrare adesso: presenti, non nascosti e non nascosti dal filtro finale. Chi non e' mostrabile (nascosto,
   * tolto, sparito) non puo' essere ne' elemento visto ne' copertina (E-H1, E-H6).
   */
  suspend fun shown(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    val candidates = visible.visible(ids)
    if (candidates.isEmpty()) return emptySet()
    return candidates - mask.hiddenAmong(candidates)
  }

  /** Gli album personali ([states] per i legami con gli album condivisi), con conteggio, copertina e regole. */
  suspend fun personal(states: Map<String, PersonalAlbumState>, me: AccountId?): List<Album> {
    val rows = database.albumDao().all().filter { it.state == null }
    if (rows.isEmpty()) return emptyList()
    val summaries = database.albumDao().summaries().associateBy { it.albumId }
    val suggested = database.albumChoiceDao().suggestionCounts(Long.MAX_VALUE).associateBy { it.albumId }
    val out = ArrayList<Album>(rows.size)
    for (row in rows) {
      val id = albumIdOrNull(row.albumId) ?: continue
      val kind = AlbumKind.fromCode(row.kind) ?: continue
      out += album(row, id, kind, summaries[row.albumId]?.visibleCount ?: 0, summaries[row.albumId]?.newestDay, suggested[row.albumId]?.visibleCount ?: 0, states[row.albumId], me)
    }
    return out
  }

  /** Un album personale solo, con le stesse regole di [personal]; null se non c'e' o non e' un album di questa versione. */
  suspend fun personalOne(albumId: String, states: Map<String, PersonalAlbumState>, me: AccountId?): Album? {
    val row = database.albumDao().get(albumId)?.takeIf { it.state == null } ?: return null
    val id = albumIdOrNull(albumId) ?: return null
    val kind = AlbumKind.fromCode(row.kind) ?: return null
    val summary = database.albumDao().summaries().firstOrNull { it.albumId == albumId }
    val suggested = database.albumChoiceDao().suggestionCounts(Long.MAX_VALUE).firstOrNull { it.albumId == albumId }?.visibleCount ?: 0
    return album(row, id, kind, summary?.visibleCount ?: 0, summary?.newestDay, suggested, states[albumId], me)
  }

  private suspend fun album(
    row: AlbumEntity,
    id: AlbumId,
    kind: AlbumKind,
    count: Int,
    newestDay: Int?,
    suggested: Int,
    state: PersonalAlbumState?,
    me: AccountId?,
  ): Album {
    val cover = if (count == 0) CoverRef.None else cover(row, kind)
    val sort = AlbumSort.fromCode(row.sort) ?: AlbumSort.defaultFor(kind)
    val day = dayOf(newestDay)
    return when (kind) {
      AlbumKind.MANUAL -> Album.Manual(id, row.title, cover, count, sort, row.updatedAt, day, formerShare(row, state, me))
      AlbumKind.SMART -> Album.Smart(id, row.title, cover, count, sort, row.updatedAt, day, rules(row.albumId), row.frozen, if (row.frozen) 0 else suggested, newSinceSeen = 0)
    }
  }

  /** Le regole di uno smart dalle righe `album_rule` (illeggibili = assenti). */
  private suspend fun rules(albumId: String): SmartRules {
    val rows = database.albumRuleDao().rules(albumId)
    val fields = rows.filter { it.rule != AlbumDerivation.EXAMPLE_RULE }.associate { "smart:${it.rule}" to it.value }
    val examples = rows.filter { it.rule == AlbumDerivation.EXAMPLE_RULE }.sortedBy { it.position }.mapNotNull { it.mediaId?.let(::MediaId) }
    return SmartRulesText.decode(fields, examples)
  }

  /** D21: la scelta se questo dispositivo la puo' mostrare e se e' ancora un elemento; altrimenti l'automatica. */
  private suspend fun cover(row: AlbumEntity, kind: AlbumKind): CoverRef {
    val chosen = row.coverMediaId?.let(::MediaId)
    if (chosen != null && isItem(row.albumId, chosen) && chosen in shown(listOf(chosen))) return CoverRef.Chosen(chosen)
    val auto = when (kind) {
      AlbumKind.MANUAL -> lastShown(row.albumId)
      AlbumKind.SMART -> bestOfBusiestDay(row.albumId)
    }
    return auto?.let { CoverRef.Auto(it) } ?: CoverRef.None
  }

  private suspend fun isItem(albumId: String, media: MediaId): Boolean = database.albumDao().itemsOfMedia(media.value).any { it.albumId == albumId }

  /**
   * Il manuale: l'ultimo aggiunto, cioe' l'elemento mostrabile con la posizione piu' alta (l'aggiunta mette in coda e una voce non porta l'ora
   * in cui e' stata aggiunta). Si legge dalla coda a pagine di [COVER_CHUNK]: di norma basta la prima, anche con migliaia di elementi.
   */
  private suspend fun lastShown(albumId: String): MediaId? {
    var offset = 0
    while (true) {
      val page = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaId>(COVER_CHUNK)
        database.openHelper.readableDatabase.query(LAST_ITEMS_SQL, arrayOf<Any?>(albumId, COVER_CHUNK, offset)).use { cursor ->
          while (cursor.moveToNext()) out += MediaId(cursor.getString(0))
        }
        out
      }
      if (page.isEmpty()) return null
      val ok = shown(page)
      page.firstOrNull { it in ok }?.let { return it }
      offset += page.size
    }
  }

  /** Lo smart: la foto con piu' pixel del giorno con piu' elementi mostrabili (a pari conteggio il giorno piu' vecchio, a pari pixel l'id minore). */
  private suspend fun bestOfBusiestDay(albumId: String): MediaId? {
    val ok = shown(database.albumDao().items(albumId).map { MediaId(it.mediaId) })
    if (ok.isEmpty()) return null
    val days = catalog.albums.daysOf(ok)
    val busiest = days.values.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
    val ofDay = ok.filter { days[it] == busiest }.sortedBy { it.value }
    if (ofDay.isEmpty()) return ok.minByOrNull { it.value }
    val best = catalog.albums.candidatesOf(ofDay).sortedWith(compareByDescending<CatalogCandidate> { it.pixels }.thenBy { it.media.value }).firstOrNull()
    return best?.media ?: ofDay.first()
  }

  /** L'album che era condiviso (D31, Q5): il creatore dopo "Smetti di condividere", o la mia copia personale. Il resto lo sa la replica. */
  private suspend fun formerShare(row: AlbumEntity, state: PersonalAlbumState?, me: AccountId?): FormerShare? {
    val shared = state?.copyOf ?: row.sharedId ?: return null
    val replica = database.sharedAlbumDao().get(shared) ?: return null
    if (SharedState.fromCode(replica.state) == SharedState.ACTIVE) return null
    val shared0 = albumIdOrNull(shared) ?: return null
    val creator = try {
      AccountId(replica.creator)
    } catch (e: IllegalArgumentException) {
      return null
    }
    return FormerShare(shared0, creator, mine = creator == me, closedAtNas = replica.closedAtNas ?: replica.updatedAtNas)
  }

  private fun albumIdOrNull(raw: String): AlbumId? = try {
    AlbumId(raw)
  } catch (e: IllegalArgumentException) {
    null
  }

  private fun dayOf(localDay: Int?): LocalDate? {
    if (localDay == null || localDay <= 0) return null
    return runCatching { LocalDate.of(localDay / 10_000, localDay / 100 % 100, localDay % 100) }.getOrNull()
  }

  private companion object {
    const val COVER_CHUNK = 64
    const val LAST_ITEMS_SQL: String = "SELECT media_id FROM album_item WHERE album_id = ? ORDER BY position DESC, COALESCE(fp, '') DESC, media_id DESC LIMIT ? OFFSET ?"
  }
}
