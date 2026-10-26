package dev.pampa.mycloudgallery.core.library.visibility

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Fase 7 (P9, D22): le letture del catalogo con cui si decide, per un insieme di media, "nascosto adesso" e "visibile adesso". Sola lettura,
// SQL a blocchi su tabelle stabili (`media`, `media_alias`, `media_personal`): `core-database` e' di P1 e non si tocca da qui. Il nascosto si
// legge sempre da `media_personal` (come l'indice della timeline e il filtro della ricerca): l'unico flag che non deve mai essere in ritardo.

/** Quanti id per interrogazione: il limite delle variabili di SQLite su API 29 e' 999 (come `TimelineIndexDao.MAX_IDS`). */
internal const val VISIBILITY_CHUNK: Int = 500

/**
 * L'esito di una domanda sul catalogo per un insieme di id.
 *
 * - [target]: per ogni id **noto** il media che lo rappresenta adesso (se stesso, o il sopravvissuto se e' stato unito a un altro); un id
 *   che il catalogo non conosce non c'e'.
 * - [known]: i media di destinazione che esistono davvero nella tabella `media` (un alias puo' puntare a un media sparito).
 * - [present]: i media di destinazione presenti in almeno un posto (`presence != 'none'`).
 * - [hidden]: i media di destinazione nascosti **o** membri di un gruppo in cui qualcuno e' nascosto. Il nascosto e' contagioso nei gruppi
 *   (F5-D36): di norma lo scrive gia' ogni membro (`setPersonalFlags` lo propaga), ma un dato arrivato a meta' (una sola copia nascosta, scritta
 *   senza passare di li') non deve far comparire le altre.
 */
internal class VisibilityAnswer(
  val target: Map<MediaId, String>,
  val known: Set<String>,
  val present: Set<String>,
  val hidden: Set<String>,
) {

  /** [id] e' nascosto adesso. Fallire chiusi: un id sconosciuto, o il cui media non c'e' piu', conta come nascosto. */
  fun isHidden(id: MediaId): Boolean {
    val media = target[id] ?: return true
    return media !in known || media in hidden
  }

  /** [id] si puo' mostrare adesso: noto, presente in un posto, non nascosto, non in un gruppo con un nascosto. */
  fun isVisible(id: MediaId): Boolean {
    val media = target[id] ?: return false
    return media in known && media in present && media !in hidden
  }
}

/** Le letture di [VisibilityAnswer] sul catalogo vero. */
internal class VisibilityQueries(private val database: GalleryDatabase) {

  suspend fun answer(ids: Collection<MediaId>): VisibilityAnswer =
    withContext(Dispatchers.IO) { answerNow(database.openHelper.readableDatabase, ids) }

  private fun answerNow(db: SupportSQLiteDatabase, ids: Collection<MediaId>): VisibilityAnswer {
    val asked = ids.map { it.value }.distinct()
    val group = HashMap<String, String?>()
    val presence = HashMap<String, String>()
    readMedia(db, asked, group, presence)

    // Gli id che non sono piu' media a se' ma sono stati uniti a un altro: il nascosto vale per chi li ha assorbiti.
    val target = HashMap<MediaId, String>()
    for (id in asked) if (id in group) target[MediaId(id)] = id
    for (chunk in asked.filter { it !in group }.chunked(VISIBILITY_CHUNK)) {
      query(db, "SELECT alias_id, media_id FROM media_alias WHERE alias_id IN (${marks(chunk)})", chunk) { c ->
        target[MediaId(c.getString(0))] = c.getString(1)
      }
    }
    readMedia(db, target.values.filter { it !in group }.distinct(), group, presence)

    val known = target.values.filterTo(LinkedHashSet()) { it in group }
    val hidden = HashSet<String>()
    for (chunk in known.toList().chunked(VISIBILITY_CHUNK)) {
      query(db, "SELECT media_id FROM media_personal WHERE hidden = 1 AND media_id IN (${marks(chunk)})", chunk) { c -> hidden += c.getString(0) }
    }
    // Un gruppo in cui qualcuno e' nascosto nasconde tutti i suoi membri.
    val hiddenGroups = HashSet<String>()
    val groups = known.mapNotNull { group[it] }.distinct()
    for (chunk in groups.chunked(VISIBILITY_CHUNK)) {
      query(
        db,
        "SELECT DISTINCT m.group_id FROM media m JOIN media_personal p ON p.media_id = m.media_id " +
          "WHERE p.hidden = 1 AND m.group_id IN (${marks(chunk)})",
        chunk,
      ) { c -> hiddenGroups += c.getString(0) }
    }
    for (media in known) {
      val g = group[media]
      if (g != null && g in hiddenGroups) hidden += media
    }
    val present = known.filterTo(HashSet()) { presence[it] != NO_PRESENCE }
    return VisibilityAnswer(target, known, present, hidden)
  }

  /** Legge `group_id` e `presence` dei media [media] (solo quelli che esistono). */
  private fun readMedia(db: SupportSQLiteDatabase, media: List<String>, groups: MutableMap<String, String?>, presence: MutableMap<String, String>) {
    for (chunk in media.chunked(VISIBILITY_CHUNK)) {
      query(db, "SELECT media_id, group_id, presence FROM media WHERE media_id IN (${marks(chunk)})", chunk) { c ->
        val id = c.getString(0)
        groups[id] = if (c.isNull(1)) null else c.getString(1)
        presence[id] = c.getString(2)
      }
    }
  }

  private companion object {
    /** Il valore di `media.presence` di un media che non sta in nessun posto raggiungibile. */
    const val NO_PRESENCE: String = "none"
  }
}

internal fun marks(items: Collection<*>): String = items.joinToString(",") { "?" }

internal inline fun query(db: SupportSQLiteDatabase, sql: String, args: Collection<String>, read: (Cursor) -> Unit) {
  db.query(sql, args.toTypedArray<Any?>()).use { c -> while (c.moveToNext()) read(c) }
}
