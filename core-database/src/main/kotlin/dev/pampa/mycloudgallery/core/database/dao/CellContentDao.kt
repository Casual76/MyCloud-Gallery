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

  /** Le versioni dei membri visibili e non nascosti dei gruppi [groupIds] (al massimo [TimelineIndexDao.MAX_IDS]), copertina compresa. */
  @Query(MEMBER_VERSIONS_SQL)
  suspend fun memberVersions(groupIds: List<String>): List<CellVersionRow>

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

    const val MEMBER_VERSIONS_SQL: String =
      "SELECT $COLUMNS FROM media m CROSS JOIN media_version v ON v.media_id = m.media_id " +
        "LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "WHERE m.group_id IN (:groupIds) AND m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 ORDER BY m.rowid"
  }
}

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
