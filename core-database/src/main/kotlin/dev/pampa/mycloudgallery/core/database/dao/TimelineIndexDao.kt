package dev.pampa.mycloudgallery.core.database.dao

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.GroupSizeRow
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.database.entity.MediaStoreCursorEntity
import dev.pampa.mycloudgallery.core.database.entity.PhoneRefRow
import dev.pampa.mycloudgallery.core.model.CellFlags

/**
 * L'indice della timeline della Fase 3 (D2 di `docs/fase3-progetto.md`): `core-library` lo costruisce
 * **una volta** con [indexCursor] (100 mila righe, nessun oggetto per riga) e poi lo aggiorna a pezzi
 * con [indexRows] seguendo `MediaCatalog.changes`.
 *
 * Nell'indice ci sono i media **visibili** (in almeno un posto raggiungibile e non nascosti, E-H1) e,
 * dei gruppi, solo chi li rappresenta (`CellFlags.GROUP_MEMBER` escluso, D20). I nascosti si leggono solo
 * con [hiddenRows], dopo lo sblocco (P10). L'ordine e' quello della timeline, dal piu' recente: lo da'
 * l'indice (`sort_time`, `media_id`) senza ordinare in memoria (`TimelineIndexTest` lo verifica con
 * `EXPLAIN QUERY PLAN`).
 *
 * Il nascosto si legge da `media_personal` (unione per chiave primaria) e non da un campo derivato: e'
 * l'unico flag che non deve mai essere in ritardo.
 */
@Dao
interface TimelineIndexDao {

  /**
   * Tutto l'indice, in ordine (`sort_time DESC, media_id DESC`), con le colonne di [COLUMNS]. Chi lo legge
   * lo chiude; non sul thread principale.
   */
  @Query(INDEX_SQL)
  fun indexCursor(): Cursor

  /**
   * Le righe di [ids] che stanno nell'indice: quelle che mancano ne sono uscite (nascoste, sparite,
   * dietro la copertina, unite). Al massimo [MAX_IDS] identita' per chiamata (limite delle variabili di
   * SQLite su API 29): `MediaCatalog.indexRows` divide da solo.
   */
  @Query("SELECT $COLUMNS $FROM WHERE m.media_id IN (:ids) AND $INDEXED ORDER BY m.sort_time DESC, m.media_id DESC")
  suspend fun indexRows(ids: List<String>): List<IndexRow>

  /** I media nascosti ancora presenti in qualche posto, dal piu' recente (raccolta Nascosti, dopo lo sblocco). */
  @Query(
    "SELECT $COLUMNS $FROM WHERE m.presence != 'none' AND COALESCE(p.hidden, 0) = 1 " +
      "ORDER BY m.sort_time DESC, m.media_id DESC",
  )
  suspend fun hiddenRows(): List<IndexRow>

  /** Quanti membri visibili ha ogni gruppo (il numero sulla copertina). */
  @Query(
    "SELECT m.group_id AS group_id, COUNT(*) AS size $FROM " +
      "WHERE m.group_id IS NOT NULL AND m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 GROUP BY m.group_id",
  )
  suspend fun groupSizes(): List<GroupSizeRow>

  /**
   * Le copie sul telefono **leggibili adesso** dei media [ids] (al massimo [MAX_IDS]): presenti, con un
   * `_ID` in cache letto con la versione attuale del MediaStore di quel volume (`media_store_cursor`).
   * Un `_ID` di un'altra versione puo' essere di un altro file (E-P22): finche' la scansione non lo
   * rilegge, il media non ha riferimento e la griglia mostra il segnaposto. Per ogni media prima le copie
   * della versione corrente.
   *
   * P11: `+l.state` (il `+` toglie la colonna dalla scelta degli indici). Senza statistiche (sul telefono nessuno
   * esegue `ANALYZE`) SQLite partiva da `media_location` con l'indice `(state, absent_since)`, cioe' da **tutte** le
   * posizioni presenti, per ogni blocco di 500 identita': su 100 mila media un blocco costava minuti. Cosi' parte dai
   * media chiesti e scende per `version_id` (`TimelineIndexTest` ne controlla il piano).
   */
  @Query(PHONE_REFS_SQL)
  suspend fun phoneRefs(ids: List<String>): List<PhoneRefRow>

  /** Il numero di sequenza dei cambiamenti dell'ultima transazione del catalogo (0 se non ce ne sono state). */
  @Query("SELECT COALESCE((SELECT value FROM catalog_meta WHERE `key` = '$CHANGE_SEQ'), 0)")
  suspend fun changeSeq(): Long

  companion object {
    /** Limite prudente di identita' per [indexRows] (SQLite su API 29 accetta 999 variabili). */
    const val MAX_IDS: Int = 500

    const val CHANGE_SEQ: String = "change_seq"

    /** Testo della query di [phoneRefs]: pubblico per il test che ne controlla il piano (P11). */
    const val PHONE_REFS_SQL: String =
      "SELECT m.media_id AS media_id, l.volume AS volume, l.media_store_id AS media_store_id, md.mime_type AS mime_type " +
      "FROM media m JOIN media_version v ON v.media_id = m.media_id " +
      "JOIN media_location l ON l.version_id = v.version_id " +
      "JOIN media_store_cursor c ON c.volume = l.volume AND c.ms_version = l.media_store_version " +
      "LEFT JOIN version_metadata md ON md.version_id = v.version_id " +
      "WHERE m.media_id IN (:ids) AND l.location_type = 'phone' AND +l.state = 'present' AND l.media_store_id IS NOT NULL " +
      "ORDER BY m.media_id, CASE WHEN v.version_id = m.current_version_id THEN 0 ELSE 1 END, l.location_id"

    /** Le colonne di [indexCursor], in quest'ordine. */
    const val COLUMNS: String =
      "m.media_id AS media_id, m.sort_time AS sort_time, m.local_day AS local_day, m.kind AS kind, m.source AS source, " +
        "m.cell_flags AS cell_flags, m.aspect_milli AS aspect_milli, m.duration_ms AS duration_ms, m.place_id AS place_id, " +
        "m.thumb_hash AS thumb_hash, m.group_id AS group_id"

    private const val FROM: String = "FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id"

    /** Visibile, non nascosto, non dietro la copertina del suo gruppo. */
    private const val INDEXED: String =
      "m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 AND (m.cell_flags & ${CellFlags.GROUP_MEMBER}) = 0"

    /** Testo della query dell'indice: pubblico per il test che ne controlla il piano di esecuzione. */
    const val INDEX_SQL: String = "SELECT $COLUMNS $FROM WHERE $INDEXED ORDER BY m.sort_time DESC, m.media_id DESC"
  }
}

/** I valori singoli del catalogo (`catalog_meta`). Le scritture passano da `MediaCatalog`. */
@Dao
interface CatalogMetaDao {

  @Query("SELECT value FROM catalog_meta WHERE `key` = :key")
  suspend fun get(key: String): Long?

  @Query("INSERT OR REPLACE INTO catalog_meta (`key`, value) VALUES (:key, :value)")
  suspend fun put(key: String, value: Long)

  /**
   * Con [add], alza un contatore creandolo a 0 se manca. Due istruzioni e non un `UPSERT`: SQLite su
   * API 29 (3.22) non conosce `ON CONFLICT ... DO UPDATE`.
   */
  @Query("INSERT OR IGNORE INTO catalog_meta (`key`, value) VALUES (:key, 0)")
  suspend fun createIfMissing(key: String)

  @Query("UPDATE catalog_meta SET value = value + :delta WHERE `key` = :key")
  suspend fun add(key: String, delta: Long)
}

/** I cursori della lettura del MediaStore (`media_store_cursor`). Le scritture passano da `MediaCatalog`. */
@Dao
interface MediaStoreCursorDao {

  @Query("SELECT * FROM media_store_cursor WHERE volume = :volume")
  suspend fun get(volume: String): MediaStoreCursorEntity?

  @Query("SELECT * FROM media_store_cursor ORDER BY volume")
  suspend fun all(): List<MediaStoreCursorEntity>

  @Upsert
  suspend fun upsert(cursor: MediaStoreCursorEntity)

  @Query("DELETE FROM media_store_cursor WHERE volume = :volume")
  suspend fun delete(volume: String)
}
