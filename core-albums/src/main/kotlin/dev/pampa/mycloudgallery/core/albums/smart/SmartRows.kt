package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.personal.AlbumDerivation
import dev.pampa.mycloudgallery.core.albums.personal.SmartRulesText
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.concurrent.ConcurrentHashMap

// LE RIGHE DEGLI ALBUM SMART (D5, D14; docs/modello-dati.md §14). Il motore legge le regole, le esclusioni e gli elementi che l'utente ha scelto
// (scritti dalla proiezione di P3 dalle voci personali) e scrive **solo** le sue righe: gli elementi `auto` di `album_item` e i suggeriti di
// `album_suggestion`. Mai una riga `manual` o `accepted`, mai le regole, mai le esclusioni.

/** Una riga automatica di `album_item`: la posizione (l'istante di scatto, cosi' "Personalizzato" li tiene in ordine di data) e quando e' entrata. */
data class AutoRow(val position: Long, val addedAt: Long) {
  /** Mai l'istante di scatto ne' di arrivo nei log (D37). */
  override fun toString(): String = "AutoRow"
}

/** Un suggerito: il codice della regola ([SmartReason.code]) e quando e' stato proposto (per "Nuove: 12"). */
data class SuggestionRow(val reason: String, val addedAt: Long) {
  override fun toString(): String = "SuggestionRow($reason)"
}

/** Tutto cio' che serve per valutare uno smart: le regole e le scelte dell'utente, e le righe che il motore ha scritto. */
class SmartAlbumState(
  val id: AlbumId,
  val rules: SmartRules,
  val frozen: Boolean,
  val exclusions: Set<MediaId>,
  /** Gli elementi che non sono del motore (`manual`, `accepted`): non diventano mai automatici ne' suggeriti. */
  val owned: Set<MediaId>,
  val auto: Map<MediaId, AutoRow>,
  val suggestions: Map<MediaId, SuggestionRow>,
) {
  override fun toString(): String = "SmartAlbumState(auto=${auto.size}, suggeriti=${suggestions.size}, frozen=$frozen)"
}

/** Le modifiche alle righe del motore: tutte insieme, in una transazione. */
class SmartRowsChange(
  val putAuto: Map<MediaId, AutoRow> = emptyMap(),
  val removeAuto: Set<MediaId> = emptySet(),
  val putSuggestions: Map<MediaId, SuggestionRow> = emptyMap(),
  val removeSuggestions: Set<MediaId> = emptySet(),
) {
  val isEmpty: Boolean get() = putAuto.isEmpty() && removeAuto.isEmpty() && putSuggestions.isEmpty() && removeSuggestions.isEmpty()

  override fun toString(): String = "SmartRowsChange(+${putAuto.size}/-${removeAuto.size} sicuri, +${putSuggestions.size}/-${removeSuggestions.size} suggeriti)"
}

interface SmartRows {
  /** Gli id degli album smart personali (congelati compresi). */
  suspend fun smartAlbums(): List<AlbumId>

  /** Lo stato di uno smart personale, o null se l'album non c'e', non e' smart o non e' personale. */
  suspend fun state(id: AlbumId): SmartAlbumState?

  /** Applica [change] alle righe del motore di [id]. Mai una riga di un altro tipo; un elemento non del motore si salta. Idempotente. */
  suspend fun apply(id: AlbumId, change: SmartRowsChange)
}

/** Le righe sul database vero (tabelle v6). */
class RoomSmartRows(private val database: GalleryDatabase) : SmartRows {

  override suspend fun smartAlbums(): List<AlbumId> =
    database.albumDao().all().filter { it.state == null && it.kind == AlbumKind.SMART.code }.mapNotNull { albumIdOrNull(it.albumId) }

  override suspend fun state(id: AlbumId): SmartAlbumState? {
    val row = database.albumDao().get(id.value)?.takeIf { it.state == null && it.kind == AlbumKind.SMART.code } ?: return null
    val ruleRows = database.albumRuleDao().rules(id.value)
    val fields = ruleRows.filter { it.rule != AlbumDerivation.EXAMPLE_RULE }.associate { "smart:${it.rule}" to it.value }
    val examples = ruleRows.filter { it.rule == AlbumDerivation.EXAMPLE_RULE }.sortedBy { it.position }.mapNotNull { it.mediaId?.let(::MediaId) }
    val items = database.albumDao().items(id.value)
    return SmartAlbumState(
      id = id,
      rules = SmartRulesText.decode(fields, examples),
      frozen = row.frozen,
      exclusions = database.albumChoiceDao().exclusions(id.value).mapTo(HashSet()) { MediaId(it.mediaId) },
      owned = items.filter { it.origin != ORIGIN_AUTO }.mapTo(HashSet()) { MediaId(it.mediaId) },
      auto = items.filter { it.origin == ORIGIN_AUTO }.associate { MediaId(it.mediaId) to AutoRow(it.position, it.addedAt) },
      suggestions = database.albumChoiceDao().suggestions(id.value).associate { MediaId(it.mediaId) to SuggestionRow(it.reason, it.addedAt) },
    )
  }

  override suspend fun apply(id: AlbumId, change: SmartRowsChange) {
    if (change.isEmpty) return
    database.transaction {
      val dao = database.albumDao()
      val choices = database.albumChoiceDao()
      // Una riga che l'utente ha scelto (manual, accepted) non si riscrive mai: la chiave e' (album, media) e un upsert la sovrascriverebbe.
      val current = dao.items(id.value).associateBy { it.mediaId }
      for (media in change.removeAuto) if (current[media.value]?.origin == ORIGIN_AUTO) dao.removeItem(id.value, media.value)
      val put = change.putAuto.filter { (media, _) -> current[media.value]?.origin.let { it == null || it == ORIGIN_AUTO } }
      if (put.isNotEmpty()) dao.upsertItems(put.map { (media, row) -> AlbumItemEntity(id.value, media.value, row.position, row.addedAt, ORIGIN_AUTO, null, null) })
      for (media in change.removeSuggestions) choices.removeSuggestion(id.value, media.value)
      val settled = current.filterValues { it.origin != ORIGIN_AUTO }.keys
      val suggest = change.putSuggestions.filter { (media, _) -> media.value !in settled }
      if (suggest.isNotEmpty()) choices.upsertSuggestions(suggest.map { (media, row) -> AlbumSuggestionEntity(id.value, media.value, row.reason, row.addedAt) })
    }
  }

  private fun albumIdOrNull(raw: String): AlbumId? = try {
    AlbumId(raw)
  } catch (e: IllegalArgumentException) {
    null
  }

  private companion object {
    const val ORIGIN_AUTO = "auto"
  }
}

/**
 * Da quando un album "ha novita'" ("Nuove: 12", E-AL7): l'istante fino a cui l'utente ha visto l'album, per dispositivo. Una cosa locale (non si
 * sincronizza: ogni dispositivo ha visto cio' che ha visto). Senza un segno l'album non ha novita': la prima apertura lo mette. :app lo lega a una
 * preferenza del dispositivo; qui c'e' la versione in memoria per le prove.
 */
interface SmartSeenMarks {
  suspend fun seenUpTo(id: AlbumId): Long?

  suspend fun markSeen(id: AlbumId, atMs: Long)
}

class InMemorySeenMarks : SmartSeenMarks {
  private val marks = ConcurrentHashMap<AlbumId, Long>()

  override suspend fun seenUpTo(id: AlbumId): Long? = marks[id]

  override suspend fun markSeen(id: AlbumId, atMs: Long) {
    marks[id] = atMs
  }
}
