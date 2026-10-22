package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import dev.pampa.mycloudgallery.core.model.CellFlags

/**
 * Le chiavi di contenuto delle celle della Libreria (Fase 6, §2.4 "Mappa delle celle"): per ogni cella dell'istantanea (visibile, non
 * nascosta, non dietro la copertina del suo gruppo: gli stessi predicati di [TimelineIndexDao]) le versioni del suo media, e di
 * ogni membro visibile del suo gruppo, con `size` e `quick_hash` da cui `MediaCatalog` ricava la chiave (`ContentKey`).
 *
 * Si legge **a pagine di `rowid`** (il "seq" di inserimento del media, [seqBound]): `ORDER BY m.rowid` e' l'ordine dell'albero della
 * tabella, quindi nessun `USE TEMP B-TREE` (`CellContentKeysTest` ne controlla il piano con `EXPLAIN QUERY PLAN`).
 *
 * Il collegamento ai nascosti si legge da `media_personal` (unione per chiave primaria), come l'indice della timeline: e' l'unico
 * flag che non deve mai essere in ritardo (D29).
 */
@Dao
interface CellContentDao {

  /** L'ultimo `rowid` di una pagina di [limit] media dopo [afterSeq] (qualunque stato), o null se non ce ne sono piu'. */
  @Query(SEQ_BOUND_SQL)
  suspend fun seqBound(afterSeq: Long, limit: Int): Long?

  /** Le versioni delle celle con `rowid` in `(afterSeq, upToSeq]`, in ordine di `rowid` (le versioni di un media, in ordine libero). */
  @Query(CELL_VERSIONS_SQL)
  suspend fun cellVersions(afterSeq: Long, upToSeq: Long): List<CellVersionRow>

  /** Le versioni delle celle [ids] (al massimo [TimelineIndexDao.MAX_IDS]): per ricalcolare dopo un [CatalogChange]. Chi non e' una cella non c'e'. */
  @Query(CELL_VERSIONS_OF_SQL)
  suspend fun cellVersionsOf(ids: List<String>): List<CellVersionRow>

  /**
   * Revisione A (VT-1, D29): le versioni dei media [ids] **di qualunque stato** (nascosti e membri di gruppo compresi), senza i membri del
   * gruppo. Solo per il viewer (testo dal vivo e lettura al volo nel viewer dei Nascosti): nessun'altra superficie la usa.
   */
  @Query(MEDIA_VERSIONS_OF_SQL)
  suspend fun mediaVersionsOf(ids: List<String>): List<CellVersionRow>

  /** Revisione A (CE-6): dei media [ids] (al massimo [TimelineIndexDao.MAX_IDS]), quelli nascosti. */
  @Query("SELECT media_id FROM media_personal WHERE hidden = 1 AND media_id IN (:ids)")
  suspend fun hiddenAmong(ids: List<String>): List<String>

  /**
   * Revisione A (PR-1, D31): le share (chiave piegata, `share_key`) in cui i media [ids] (al massimo [TimelineIndexDao.MAX_IDS]) hanno una
   * copia presente sul NAS, per i motivi della copertura (area senza indicizzatori, solo sul telefono).
   */
  @Query(
    "SELECT DISTINCT v.media_id AS media_id, l.share_key AS share_key FROM media_version v CROSS JOIN media_location l " +
      "ON l.version_id = v.version_id WHERE v.media_id IN (:ids) AND l.location_type = 'nas' AND l.state = 'present'",
  )
  suspend fun nasSharesOf(ids: List<String>): List<MediaShareRow>

  /** Revisione A (CE-6): degli id [ids], quelli assorbiti da un'unione (alias) in un media nascosto. */
  @Query("SELECT a.alias_id FROM media_alias a JOIN media_personal p ON p.media_id = a.media_id WHERE p.hidden = 1 AND a.alias_id IN (:ids)")
  suspend fun hiddenAliasesAmong(ids: List<String>): List<String>

  /** Le versioni dei membri visibili e non nascosti dei gruppi [groupIds] (al massimo [TimelineIndexDao.MAX_IDS]), copertina compresa. */
  @Query(MEMBER_VERSIONS_SQL)
  suspend fun memberVersions(groupIds: List<String>): List<CellVersionRow>

  /**
   * I media del **solo telefono** (presenza derivata `phone_only`, nascosti compresi: si calcolano, non si mostrano, D29) dal piu' recente
   * (`sort_time`, poi identita', decrescenti), con la versione corrente: la prima pagina. Per l'analisi AI dei media che non sono sul NAS
   * (§2.2, D14): la chiave di contenuto la ricava `MediaCatalog.phoneOnlyWork`.
   */
  @Query(PHONE_ONLY_FIRST_SQL)
  suspend fun phoneOnlyFirst(limit: Int): List<PhoneOnlyRow>

  /** La pagina dopo il cursore ([sortTime], [mediaId]) di [phoneOnlyFirst]. */
  @Query(PHONE_ONLY_AFTER_SQL)
  suspend fun phoneOnlyAfter(sortTime: Long, mediaId: String, limit: Int): List<PhoneOnlyRow>

  companion object {
    private const val INDEXED: String =
      "m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 AND (m.cell_flags & ${CellFlags.GROUP_MEMBER}) = 0"

    private const val COLUMNS: String =
      "m.rowid AS seq, m.media_id AS media_id, m.group_id AS group_id, " +
        "CASE WHEN v.version_id = m.current_version_id THEN 9223372036854775807 ELSE v.created_at END AS version_rank, " +
        "v.size AS size, v.quick_hash AS quick_hash"

    /** Testo della pagina: pubblico per il test che ne controlla il piano. */
    const val SEQ_BOUND_SQL: String =
      "SELECT MAX(rowid) FROM (SELECT rowid FROM media WHERE rowid > :afterSeq ORDER BY rowid LIMIT :limit)"

    /**
     * Testo della lettura delle celle: pubblico per il test che ne controlla il piano. Join in ordine fisso (`CROSS JOIN`): senza
     * statistiche (sul telefono nessuno esegue `ANALYZE`) il pianificatore potrebbe partire da un'altra tabella.
     */
    const val CELL_VERSIONS_SQL: String =
      "SELECT $COLUMNS FROM media m CROSS JOIN media_version v ON v.media_id = m.media_id " +
        "LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "WHERE m.rowid > :afterSeq AND m.rowid <= :upToSeq AND $INDEXED ORDER BY m.rowid"

    const val CELL_VERSIONS_OF_SQL: String =
      "SELECT $COLUMNS FROM media m CROSS JOIN media_version v ON v.media_id = m.media_id " +
        "LEFT JOIN media_personal p ON p.media_id = m.media_id WHERE m.media_id IN (:ids) AND $INDEXED ORDER BY m.rowid"

    private const val PHONE_ONLY_COLUMNS: String =
      "SELECT m.media_id AS media_id, m.current_version_id AS version_id, m.kind AS kind, m.source AS source, m.sort_time AS sort_time, " +
        "m.local_day AS local_day, v.size AS size, v.quick_hash AS quick_hash " +
        "FROM media m JOIN media_version v ON v.version_id = m.current_version_id WHERE m.presence = 'phone_only'"

    const val PHONE_ONLY_FIRST_SQL: String = "$PHONE_ONLY_COLUMNS ORDER BY m.sort_time DESC, m.media_id DESC LIMIT :limit"

    const val PHONE_ONLY_AFTER_SQL: String =
      "$PHONE_ONLY_COLUMNS AND (m.sort_time < :sortTime OR (m.sort_time = :sortTime AND m.media_id < :mediaId)) " +
        "ORDER BY m.sort_time DESC, m.media_id DESC LIMIT :limit"

    const val MEDIA_VERSIONS_OF_SQL: String =
      "SELECT $COLUMNS FROM media m CROSS JOIN media_version v ON v.media_id = m.media_id WHERE m.media_id IN (:ids) ORDER BY m.rowid"

    const val MEMBER_VERSIONS_SQL: String =
      "SELECT $COLUMNS FROM media m CROSS JOIN media_version v ON v.media_id = m.media_id " +
        "LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "WHERE m.group_id IN (:groupIds) AND m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 ORDER BY m.rowid"
  }
}

/** Una share del NAS in cui un media ha una copia presente ([CellContentDao.nasSharesOf]). */
data class MediaShareRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "share_key") val shareKey: String?,
)

/** Una versione di un media con cio' che serve per la chiave di contenuto. */
data class CellVersionRow(
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "group_id") val groupId: String?,
  /** `Long.MAX_VALUE` per la versione corrente, altrimenti il `created_at` della versione: ordinando in modo decrescente la corrente e' la prima. */
  @ColumnInfo(name = "version_rank") val rank: Long,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "quick_hash") val quickHash: String,
)

/** Un media del solo telefono con la sua versione corrente ([CellContentDao.phoneOnlyFirst]). */
data class PhoneOnlyRow(
  @ColumnInfo(name = "media_id") val mediaId: String,
  @ColumnInfo(name = "version_id") val versionId: String,
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "sort_time") val sortTime: Long,
  @ColumnInfo(name = "local_day") val localDay: Int,
  @ColumnInfo(name = "size") val size: Long,
  @ColumnInfo(name = "quick_hash") val quickHash: String,
)
