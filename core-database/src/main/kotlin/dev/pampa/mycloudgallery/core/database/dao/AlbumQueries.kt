package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.pampa.mycloudgallery.core.database.CandidateKey
import dev.pampa.mycloudgallery.core.database.CandidateQuery
import dev.pampa.mycloudgallery.core.database.CandidateSource
import dev.pampa.mycloudgallery.core.database.CopyAreas
import dev.pampa.mycloudgallery.core.database.entity.AliasRow
import dev.pampa.mycloudgallery.core.database.entity.CandidateRow
import dev.pampa.mycloudgallery.core.database.entity.DuplicateRow
import dev.pampa.mycloudgallery.core.database.entity.FactsNasRow
import dev.pampa.mycloudgallery.core.database.entity.FactsRow
import dev.pampa.mycloudgallery.core.database.entity.MediaDayRow
import dev.pampa.mycloudgallery.core.model.CellFlags

/**
 * Le query degli album sul catalogo (Fase 7, P1; `docs/modello-dati.md` §14.4): i **candidati** degli album smart e dei viaggi per
 * giorno, per luogo e per dispositivo d'origine (§2.2, §2.3), i **fatti** dei media per la condivisione (D12), le **posizioni per parte**
 * dei Doppioni (D25). Il testo sta qui, **pubblico**, perche' i test ne guardano il piano (`EXPLAIN QUERY PLAN`: l'indice giusto, mai
 * `USE TEMP B-TREE`) sullo stesso testo che il catalogo esegue, senza statistiche (sul telefono nessuno esegue `ANALYZE`) e con.
 *
 * **Solo celle visibili**: mai un nascosto (si legge da `media_personal`, mai da un derivato, D22), mai un media senza copie, una cella
 * per gruppo (D14). Tutti i valori viaggiono come parametri legati: nessun dato dell'utente entra nell'SQL.
 */
object AlbumQueries {

  /** Quanti identificativi per `IN (...)` (come [TimelineIndexDao.MAX_IDS]: SQLite su API 29 accetta 999 variabili). */
  const val MAX_IDS: Int = TimelineIndexDao.MAX_IDS

  /** I giorni locali `aaaammgg` estremi: "tutta la Libreria". */
  const val FIRST_DAY: Int = 0
  const val LAST_DAY: Int = 99_999_999

  /** Cella visibile: presente, non nascosta, non dietro la copertina del suo gruppo (gli stessi predicati dell'indice della timeline). */
  const val VISIBLE: String =
    "m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 AND (m.cell_flags & ${CellFlags.GROUP_MEMBER}) = 0"

  /**
   * D22, E-L11 (Fase 7, P9): un gruppo in cui **un** membro e' nascosto e' nascosto per intero, anche se il segno non e' arrivato a tutti i
   * membri (di norma lo scrive gia' ogni membro: `MediaCatalog.setPersonalFlags` lo propaga; un dato arrivato a meta' non deve far contare ne'
   * mostrare le altre copie). Per chi conta le celle degli album (`AlbumDao.summaries`, `AlbumChoiceDao.suggestionCounts`): lo stesso
   * contagio di `VisibleMedia`, scritto in SQL. Valuta il gruppo solo per i media che ne hanno uno.
   */
  const val NOT_IN_HIDDEN_GROUP: String =
    "(m.group_id IS NULL OR NOT EXISTS (SELECT 1 FROM media gm JOIN media_personal gp ON gp.media_id = gm.media_id WHERE gm.group_id = m.group_id AND gp.hidden = 1))"

  /** Le colonne di [CandidateRow]. */
  const val CANDIDATE_COLUMNS: String =
    "m.media_id AS media_id, m.local_day AS local_day, m.sort_time AS sort_time, m.kind AS kind, m.source AS source, " +
      "m.place_id AS place_id, m.origin_device AS origin_device, m.cell_flags AS cell_flags, m.copy_areas AS copy_areas, " +
      "m.owner_account AS owner_account, vm.taken_at_utc AS taken_at_utc, vm.taken_offset_min AS taken_offset_min, " +
      "vm.latitude AS latitude, vm.longitude AS longitude, vm.width AS width, vm.height AS height"

  /** La sorgente del candidato come numero (l'ordinale di [CandidateSource]): la partizione di [CandidateSource], scritta in SQL. */
  private val SOURCE_CASE: String =
    "(CASE WHEN (m.cell_flags & ${CellFlags.ON_PHONE}) != 0 THEN ${CandidateSource.MY_PHONE.ordinal} " +
      "WHEN (m.copy_areas & ${CopyAreas.PRIVATE}) != 0 THEN ${CandidateSource.MY_PRIVATE.ordinal} " +
      "WHEN m.owner_account IS NOT NULL AND m.owner_account != ? THEN ${CandidateSource.FAMILY.ordinal} " +
      "ELSE ${CandidateSource.COMMON.ordinal} END)"

  /** Da cosa parte la query: l'indice che sceglie. */
  enum class Lead { DAY, DEVICE, PLACE }

  /**
   * Una pagina di candidati, in ordine (giorno, istante, id): dall'indice (`local_day`, `sort_time`, `media_id`) e, a seconda di [lead],
   * da quelli con [CandidateQuery.originDevice] o con il luogo [placeId] davanti: la chiave davanti e' un'uguaglianza, quindi l'ordine
   * dei giorni resta quello dell'indice e **non si ordina in memoria**.
   *
   * [from] e [to] sono i giorni (estremi compresi); [after] il cursore dentro quel luogo/dispositivo. Il chiamante alza [from] al giorno del
   * cursore: una pagina profonda non rilegge i giorni gia' dati, ma solo il giorno del cursore fino all'ultima cella vista.
   */
  fun candidates(
    query: CandidateQuery,
    lead: Lead,
    placeId: Int?,
    from: Int,
    to: Int,
    me: String,
    after: CandidateKey?,
    limit: Int,
  ): SupportSQLiteQuery = candidateSql(query, lead, placeId, from, to, me, after, limit).let { (sql, args) -> SimpleSQLiteQuery(sql, args) }

  /** Il testo e i parametri di [candidates], per i test che ne guardano il piano. */
  fun candidateSql(
    query: CandidateQuery,
    lead: Lead,
    placeId: Int?,
    from: Int,
    to: Int,
    me: String,
    after: CandidateKey?,
    limit: Int,
  ): Pair<String, Array<Any?>> {
    val args = ArrayList<Any?>()
    val sql = StringBuilder("SELECT $CANDIDATE_COLUMNS FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id ")
    // Il join con i metadati e' in ordine fisso con il GPS richiesto: una LEFT JOIN che la WHERE rende inutile potrebbe essere
    // riordinata dal pianificatore, partendo da `version_metadata` (tutta) invece che da `media` (l'indice).
    sql.append(if (query.withGpsOnly) "CROSS JOIN" else "LEFT JOIN").append(" version_metadata vm ON vm.version_id = m.current_version_id WHERE ")
    when (lead) {
      Lead.DEVICE -> {
        sql.append("m.origin_device = ? AND ")
        args += checkNotNull(query.originDevice) { "Il dispositivo manca." }
      }
      Lead.PLACE -> {
        sql.append("m.place_id = ? AND ")
        args += checkNotNull(placeId) { "Il luogo manca." }
      }
      Lead.DAY -> Unit
    }
    sql.append("m.local_day BETWEEN ? AND ? AND $VISIBLE")
    args += from
    args += to
    // I criteri che non guidano l'indice restano filtri sulle righe che l'indice da'.
    if (lead != Lead.DEVICE && query.originDevice != null) {
      sql.append(" AND m.origin_device = ?")
      args += query.originDevice
    }
    if (lead != Lead.PLACE && query.placeIds != null) {
      sql.append(" AND m.place_id IN (").append(query.placeIds.joinToString(",") { "?" }).append(')')
      args.addAll(query.placeIds)
    }
    if (query.sources != CandidateSource.ALL) {
      sql.append(" AND ").append(SOURCE_CASE).append(" IN (").append(query.sources.joinToString(",") { it.ordinal.toString() }).append(')')
      args += me
    }
    if (query.withGpsOnly) sql.append(" AND vm.latitude IS NOT NULL AND vm.longitude IS NOT NULL")
    if (after != null) {
      sql.append(" AND (m.local_day, m.sort_time, m.media_id) > (?, ?, ?)")
      args += after.day
      args += after.time
      args += after.media
    }
    sql.append(" ORDER BY m.local_day, m.sort_time, m.media_id LIMIT ?")
    args += limit
    return sql.toString() to args.toTypedArray()
  }

  /** Quante celle visibili ha la Libreria (E-AL6: oltre il 20% le regole sono troppo larghe). */
  const val VISIBLE_COUNT_SQL: String =
    "SELECT COUNT(*) FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id WHERE $VISIBLE"

  /** I candidati con queste identita' (al piu' [MAX_IDS]), senza ordine: li ordina chi chiama. */
  const val CANDIDATES_OF_SQL: String =
    "SELECT $CANDIDATE_COLUMNS FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id " +
      "LEFT JOIN version_metadata vm ON vm.version_id = m.current_version_id WHERE m.media_id IN (:ids) AND $VISIBLE"

  /** I giorni locali di queste identita' (il ricalcolo incrementale degli smart sa quali giorni ha toccato un cambiamento). */
  const val DAYS_OF_SQL: String = "SELECT media_id, local_day FROM media WHERE media_id IN (:ids)"

  // --- fatti per la condivisione (D12) ---

  const val FACTS_SQL: String =
    "SELECT m.media_id AS media_id, m.kind AS kind, m.owner_account AS owner_account, m.cell_flags AS cell_flags, " +
      "COALESCE(p.hidden, 0) AS hidden, v.version_id AS version_id, v.size AS size, v.quick_hash AS quick_hash, v.full_hash AS full_hash " +
      "FROM media m JOIN media_version v ON v.version_id = m.current_version_id " +
      "LEFT JOIN media_personal p ON p.media_id = m.media_id WHERE m.media_id IN (:ids)"

  /** Le copie presenti sul NAS della versione corrente: dai media chiesti, giu' per `version_id` (indice delle posizioni). */
  const val FACTS_NAS_SQL: String =
    "SELECT m.media_id AS media_id, l.share_name AS share_name, l.nas_path AS nas_path, s.area AS area, l.location_id AS location_id " +
      "FROM media m JOIN media_location l ON l.version_id = m.current_version_id " +
      "LEFT JOIN nas_share s ON s.account_id = l.account_id AND s.share_key = l.share_key " +
      "WHERE m.media_id IN (:ids) AND l.location_type = 'nas' AND +l.state = 'present' ORDER BY m.media_id, l.location_id"

  /** Gli id assorbiti da un'unione fra quelli chiesti, con il sopravvissuto. */
  const val ALIASES_SQL: String = "SELECT alias_id AS alias_id, media_id AS media_id FROM media_alias WHERE alias_id IN (:ids)"

  /** Fra gli id assorbiti da un'unione quelli il cui sopravvissuto e' presente e non nascosto. */
  const val VISIBLE_ALIASES_SQL: String =
    "SELECT a.alias_id FROM media_alias a JOIN media m ON m.media_id = a.media_id LEFT JOIN media_personal p ON p.media_id = m.media_id " +
      "WHERE a.alias_id IN (:ids) AND m.presence != 'none' AND COALESCE(p.hidden, 0) = 0"

  /** Fra queste identita' quelle presenti e non nascoste (membri di un gruppo compresi). */
  const val VISIBLE_AMONG_SQL: String =
    "SELECT m.media_id FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id " +
      "WHERE m.media_id IN (:ids) AND m.presence != 'none' AND COALESCE(p.hidden, 0) = 0"

  // --- attestazione (D9) ---

  /**
   * Le versioni con una copia **presente nell'area comune** (`nas_share.area = 'common'`): il catalogo di chi legge, mai il NAS da solo.
   * Si legge a cursore (puo' essere tutta l'area comune): dalle share comuni, giu' per l'indice `(account_id, share_key, ...)` delle
   * posizioni, poi alla versione.
   */
  const val COMMON_VERSIONS_SQL: String =
    "SELECT v.media_id, v.size, v.quick_hash, v.full_hash FROM nas_share s CROSS JOIN media_location l " +
      "ON l.account_id = s.account_id AND l.share_key = s.share_key CROSS JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE s.area = 'common' AND l.location_type = 'nas' AND +l.state = 'present'"

  // --- Doppioni (D25) ---

  /**
   * Le versioni con **due o piu' posizioni dello stesso tipo** (telefono o NAS; di qualunque stato: un sovrainsieme, le assenti sono
   * poche), in ordine di `version_id`, dopo il cursore. Solo l'indice (`version_id`, `location_type`), che da solo contiene tutto cio' che
   * serve: nessuna lettura della tabella e nessun ordinamento, e una versione sul telefono **e** sul NAS (di gran lunga il caso piu'
   * comune) non conta.
   */
  const val MULTI_LOCATION_VERSIONS_SQL: String =
    "SELECT version_id FROM media_location WHERE version_id > ? GROUP BY version_id " +
      "HAVING SUM(location_type = 'phone') > 1 OR SUM(location_type = 'nas') > 1 ORDER BY version_id LIMIT ?"

  /**
   * Le copie presenti di queste versioni, di media visibili (presenti, non nascosti), con l'area della share. Nessun ordine: i gruppi li
   * ordina `AlbumCatalog.duplicates` (pochi, e l'ordine di `location_id` e' quello d'arrivo).
   */
  const val DUPLICATE_COPIES_SQL: String =
    "SELECT l.location_id AS location_id, l.version_id AS version_id, v.media_id AS media_id, l.location_type AS location_type, " +
      "l.share_name AS share_name, l.nas_path AS nas_path, l.relative_path AS relative_path, l.display_name AS display_name, " +
      "l.size AS size, l.modified_at AS modified_at, l.verified_at AS verified_at, l.state AS state, v.full_hash AS full_hash, " +
      "s.area AS area FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "JOIN media m ON m.media_id = v.media_id LEFT JOIN media_personal p ON p.media_id = m.media_id " +
      "LEFT JOIN nas_share s ON s.account_id = l.account_id AND s.share_key = l.share_key " +
      "WHERE l.version_id IN (:versionIds) AND +l.state = 'present' AND m.presence != 'none' AND COALESCE(p.hidden, 0) = 0"
}

/** Le query degli album ([AlbumQueries]); le traduce `AlbumCatalog`. */
@Dao
interface AlbumQueryDao {

  @RawQuery
  suspend fun candidates(query: SupportSQLiteQuery): List<CandidateRow>

  @RawQuery
  suspend fun count(query: SupportSQLiteQuery): Int

  @RawQuery
  suspend fun versions(query: SupportSQLiteQuery): List<String>

  @Query(AlbumQueries.CANDIDATES_OF_SQL)
  suspend fun candidatesOf(ids: List<String>): List<CandidateRow>

  @Query(AlbumQueries.DAYS_OF_SQL)
  suspend fun daysOf(ids: List<String>): List<MediaDayRow>

  @Query(AlbumQueries.FACTS_SQL)
  suspend fun facts(ids: List<String>): List<FactsRow>

  @Query(AlbumQueries.FACTS_NAS_SQL)
  suspend fun factsNas(ids: List<String>): List<FactsNasRow>

  @Query(AlbumQueries.ALIASES_SQL)
  suspend fun aliases(ids: List<String>): List<AliasRow>

  @Query(AlbumQueries.VISIBLE_ALIASES_SQL)
  suspend fun visibleAliasesAmong(ids: List<String>): List<String>

  @Query(AlbumQueries.VISIBLE_AMONG_SQL)
  suspend fun visibleAmong(ids: List<String>): List<String>

  @Query(AlbumQueries.DUPLICATE_COPIES_SQL)
  suspend fun duplicateCopies(versionIds: List<String>): List<DuplicateRow>
}
