package dev.pampa.mycloudgallery.core.database.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.pampa.mycloudgallery.core.database.CatalogBackupKind
import dev.pampa.mycloudgallery.core.database.CatalogCandidateCursor
import dev.pampa.mycloudgallery.core.database.CatalogSource
import dev.pampa.mycloudgallery.core.model.MediaSource

/**
 * Le query dinamiche del backup (Fase 5A, P1; docs/modello-dati.md §12): il testo dipende dalle sorgenti scelte e dagli
 * identificativi chiesti, quindi non possono essere costanti di un `@Query`. Sono qui, **pubbliche**, perche' i test ne guardano
 * il piano (`EXPLAIN QUERY PLAN`: indice usato, nessun `USE TEMP B-TREE`) sullo stesso testo che il catalogo esegue.
 *
 * Tutti i valori viaggiono come parametri legati: nessun testo dell'utente (percorsi, nomi) entra nell'SQL.
 */
object BackupQueries {

  /** Quanti identificativi per `IN (...)` (come [TimelineIndexDao.MAX_IDS]: SQLite su API 29 accetta 999 variabili). */
  const val MAX_IDS: Int = TimelineIndexDao.MAX_IDS

  private const val NAS_COPY_COLUMNS: String =
    "l.location_id AS location_id, v.media_id AS media_id, l.version_id AS version_id, l.share_name AS share_name, " +
      "l.nas_path AS nas_path, l.size AS size, l.modified_at AS modified_at, l.file_id AS file_id, v.full_hash AS full_hash, " +
      "l.verified_at AS verified_at, v.quick_hash AS quick_hash, 0 AS local_day"

  /** Le sorgenti tradotte in condizioni: i codici di `MediaSource` e le cartelle (volume, percorso relativo). */
  private class SourceFilter(sources: Collection<CatalogSource>) {
    val mediaSources: List<String> = buildList {
      if (CatalogSource.Camera in sources) add(MediaSource.CAMERA.code)
      if (CatalogSource.Screenshots in sources) add(MediaSource.SCREENSHOT.code)
    }
    val folders: List<CatalogSource.Folder> = sources.filterIsInstance<CatalogSource.Folder>().distinct()
    val isEmpty: Boolean get() = mediaSources.isEmpty() && folders.isEmpty()

    /** `m.source IN (...)`, o `0` se nessuna sorgente e' per `MediaSource`. */
    fun mediaClause(m: String, args: MutableList<Any?>): String {
      if (mediaSources.isEmpty()) return "0"
      args.addAll(mediaSources)
      return "$m.source IN (${mediaSources.joinToString(",") { "?" }})"
    }

    /** `(l.volume = ? AND l.relative_path = ?) OR ...`, o `0`. */
    fun folderClause(l: String, args: MutableList<Any?>): String {
      if (folders.isEmpty()) return "0"
      folders.forEach {
        args += it.volume
        args += it.relativePath
      }
      return folders.joinToString(" OR ", "(", ")") { "(+$l.volume = ? AND +$l.relative_path = ?)" }
    }
  }

  /**
   * I candidati del backup (`MediaCatalog.backupCandidates`): una pagina per `sort_time` decrescente, dall'indice
   * (`backup_todo`, `sort_time`, `media_id`): si scorrono **solo** i candidati, mai l'intera libreria, e non si ordina in memoria.
   * Per ogni media si sceglie la prima riga del telefono (la piu' vecchia per identita') di una versione da proteggere
   * (originale o corrente) che sia **presente**, con un `_ID` valido per la versione attuale del MediaStore (E-P22), in una
   * sorgente scelta e senza una copia privata verificata della versione. Null se non ci sono sorgenti.
   */
  fun candidates(
    me: String,
    sources: Collection<CatalogSource>,
    kind: CatalogBackupKind,
    after: CatalogCandidateCursor?,
    limit: Int,
  ): SupportSQLiteQuery? {
    val filter = SourceFilter(sources)
    if (filter.isEmpty) return null
    val args = ArrayList<Any?>()
    val sql = StringBuilder()
    sql.append(
      "SELECT m.media_id AS media_id, m.sort_time AS sort_time, m.kind AS kind, m.source AS source, m.local_day AS local_day, " +
        "m.cell_flags AS cell_flags, v.version_id AS version_id, v.size AS version_size, v.quick_hash AS quick_hash, " +
        "(v.kind = 'original') AS is_original, l.volume AS volume, l.media_store_id AS media_store_id, " +
        "l.media_store_version AS media_store_version, l.relative_path AS relative_path, l.display_name AS display_name, " +
        "l.size AS stamp_size, l.modified_at AS stamp_modified_at, l.generation AS stamp_generation, " +
        "(md.taken_at_utc IS NULL OR md.taken_confidence = 'estimated') AS date_estimated " +
        "FROM media m JOIN media_location l ON l.location_id = (" +
        "SELECT MIN(l2.location_id) FROM media_version v2 CROSS JOIN media_location l2 ON l2.version_id = v2.version_id " +
        "WHERE v2.media_id = m.media_id AND (v2.version_id = m.current_version_id OR v2.kind = 'original') " +
        "AND +l2.location_type = 'phone' AND +l2.state = 'present' AND l2.media_store_id IS NOT NULL " +
        "AND EXISTS (SELECT 1 FROM media_store_cursor c WHERE c.volume = l2.volume AND c.ms_version = l2.media_store_version) ",
    )
    val media = filter.mediaClause("m", args)
    val folders = filter.folderClause("l2", args)
    sql.append("AND ($media OR $folders) ")
    sql.append(
      "AND NOT EXISTS (SELECT 1 FROM media_location n JOIN nas_share s ON s.account_id = n.account_id AND s.share_key = n.share_key " +
        "WHERE n.version_id = v2.version_id AND +n.location_type = 'nas' AND +n.state = 'present' AND +n.verified_at IS NOT NULL " +
        "AND v2.full_hash IS NOT NULL AND +n.account_id = ? AND s.area = 'private')) " +
        "JOIN media_version v ON v.version_id = l.version_id LEFT JOIN version_metadata md ON md.version_id = v.version_id " +
        "WHERE m.backup_todo = 1 AND m.kind ${if (kind == CatalogBackupKind.VIDEOS) "= 'video'" else "!= 'video'"} ",
    )
    args += me
    if (after != null) {
      sql.append("AND m.sort_time <= ? AND (m.sort_time < ? OR m.media_id < ?) ")
      args += after.sortTime
      args += after.sortTime
      args += after.mediaId.value
    }
    sql.append("ORDER BY m.sort_time DESC, m.media_id DESC LIMIT ?")
    args += limit
    return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
  }

  /** I conteggi per stato di sicurezza dei media propri delle sorgenti (`MediaCatalog.safetyCounts`). Null se non ci sono sorgenti. */
  fun safetyByStatus(me: String, sources: Collection<CatalogSource>): SupportSQLiteQuery? {
    val filter = SourceFilter(sources)
    if (filter.isEmpty) return null
    val args = ArrayList<Any?>()
    val where = inSources(filter, args)
    args.add(0, me)
    return SimpleSQLiteQuery(
      "SELECT m.safety AS status, COUNT(*) AS count FROM media m WHERE m.owner_account = ? AND $where GROUP BY m.safety",
      args.toTypedArray(),
    )
  }

  /** I problemi del backup per codice dei media propri delle sorgenti (`MediaCatalog.safetyCounts`). Null se non ci sono sorgenti. */
  fun problemsByCode(me: String, sources: Collection<CatalogSource>): SupportSQLiteQuery? {
    val filter = SourceFilter(sources)
    if (filter.isEmpty) return null
    val args = ArrayList<Any?>()
    val where = inSources(filter, args)
    args.add(0, me)
    return SimpleSQLiteQuery(
      "SELECT p.code AS code, COUNT(*) AS count FROM backup_problem p JOIN media m ON m.media_id = p.media_id " +
        "WHERE m.owner_account = ? AND $where GROUP BY p.code",
      args.toTypedArray(),
    )
  }

  /** Il media appartiene a una sorgente: per `MediaSource`, o con una posizione del telefono in una delle cartelle. */
  private fun inSources(filter: SourceFilter, args: MutableList<Any?>): String {
    val media = filter.mediaClause("m", args)
    if (filter.folders.isEmpty()) return "($media)"
    val folders = filter.folderClause("l", args)
    return "($media OR EXISTS (SELECT 1 FROM media_version v JOIN media_location l ON l.version_id = v.version_id " +
      "WHERE v.media_id = m.media_id AND l.location_type = 'phone' AND $folders))"
  }

  /**
   * D8, le copie private con la stessa dimensione (e lo stesso `qh1`, se noto) dell'**area privata** [shareKey] del proprietario
   * [me], in qualunque cartella (`MediaCatalog.privateCopies`). Dall'indice (`size`, `quick_hash`) di `media_version`. Mai
   * un'altra area privata (l'account e la share sono quelli chiesti), mai l'area comune.
   */
  fun privateCopies(me: String, shareKey: String, size: Long, quickHash: String?, limit: Int): SupportSQLiteQuery {
    val args = ArrayList<Any?>()
    args += size
    if (quickHash != null) args += quickHash
    args += me
    args += shareKey
    args += limit
    return SimpleSQLiteQuery(
      // CROSS JOIN: si parte sempre dalle versioni con quella dimensione (indice `size`, `quick_hash`) e si scende per `version_id`;
      // senza statistiche il piano partirebbe dalle 100.000 copie della share. I `+` tolgono gli altri indici dalla scelta.
      "SELECT $NAS_COPY_COLUMNS FROM media_version v CROSS JOIN media_location l ON l.version_id = v.version_id " +
        "WHERE v.size = ? ${if (quickHash != null) "AND v.quick_hash = ? " else ""}" +
        "AND +l.location_type = 'nas' AND +l.state = 'present' AND +l.account_id = ? AND +l.share_key = ? " +
        "AND EXISTS (SELECT 1 FROM nas_share s WHERE s.account_id = l.account_id AND s.share_key = l.share_key AND s.area = 'private') " +
        "LIMIT ?",
      args.toTypedArray(),
    )
  }

  /** Le copie private di [shareKey] mai rilette, con l'hash della versione noto: dall'indice (`account_id`, `share_key`, `verified_at`). */
  fun unverified(me: String, shareKey: String, limit: Int): SupportSQLiteQuery = SimpleSQLiteQuery(UNVERIFIED_SQL, arrayOf(me, shareKey, limit))

  /** La riverifica a rotazione: le copie private di [shareKey] gia' verificate, dalla verifica piu' vecchia, dall'indice di `verified_at`. */
  fun rotation(me: String, shareKey: String, limit: Int): SupportSQLiteQuery = SimpleSQLiteQuery(ROTATION_SQL, arrayOf(me, shareKey, limit))

  /** Le copie del NAS dell'account di versioni senza hash completo con la fase `FULL_HASH` in coda (le piu' vecchie prima). */
  fun fullHash(me: String, limit: Int): SupportSQLiteQuery = SimpleSQLiteQuery(FULL_HASH_SQL, arrayOf(me, limit))

  /** Le copie private di [shareKey] dei media [ids] (al massimo [MAX_IDS]): la verifica sul momento. */
  fun onTheSpot(me: String, shareKey: String, ids: List<String>, limit: Int): SupportSQLiteQuery {
    require(ids.isNotEmpty() && ids.size <= MAX_IDS) { "Da 1 a $MAX_IDS identificativi per una sola query." }
    // I parametri sono nell'ordine del testo: gli identificativi, l'account, la share, il tetto.
    return SimpleSQLiteQuery(
      "SELECT $NAS_COPY_COLUMNS FROM media_version v CROSS JOIN media_location l ON l.version_id = v.version_id " +
        "WHERE v.media_id IN (${ids.joinToString(",") { "?" }}) AND +l.account_id = ? AND +l.share_key = ? " +
        "AND +l.location_type = 'nas' AND +l.state = 'present' LIMIT ?",
      (ids + listOf(me, shareKey)).toTypedArray<Any?>() + limit,
    )
  }

  /**
   * D9: i media propri con la sola copia nell'area **comune** (`only_common_copy`), una riga per versione da proteggere senza
   * copia privata (esistente) e con una copia comune presente (la piu' vecchia per identita'). [ids] null = tutti.
   */
  fun onlyCommonCopies(me: String, ids: List<String>?, limit: Int): SupportSQLiteQuery {
    require(ids == null || ids.size <= MAX_IDS) { "Troppi identificativi per una sola query." }
    val args = ArrayList<Any?>()
    args += me
    if (ids != null) args.addAll(ids)
    args += me
    args += limit
    return SimpleSQLiteQuery(
      "SELECT l.location_id AS location_id, m.media_id AS media_id, l.version_id AS version_id, l.share_name AS share_name, " +
        "l.nas_path AS nas_path, l.size AS size, l.modified_at AS modified_at, l.file_id AS file_id, v.full_hash AS full_hash, " +
        "l.verified_at AS verified_at, v.quick_hash AS quick_hash, m.local_day AS local_day " +
        "FROM media m JOIN media_version v ON v.media_id = m.media_id AND (v.version_id = m.current_version_id OR v.kind = 'original') " +
        "JOIN media_location l ON l.location_id = (SELECT MIN(c.location_id) FROM media_location c " +
        "JOIN nas_share cs ON cs.account_id = c.account_id AND cs.share_key = c.share_key " +
        "WHERE c.version_id = v.version_id AND c.location_type = 'nas' AND c.state = 'present' AND cs.area = 'common') " +
        "WHERE m.safety = 'only_common_copy' AND m.owner_account = ? " +
        (if (ids != null) "AND m.media_id IN (${ids.joinToString(",") { "?" }}) " else "") +
        "AND NOT EXISTS (SELECT 1 FROM media_location p JOIN nas_share ps ON ps.account_id = p.account_id AND ps.share_key = p.share_key " +
        "WHERE p.version_id = v.version_id AND p.location_type = 'nas' AND p.state != 'absent' AND ps.area = 'private' " +
        "AND p.account_id = ?) LIMIT ?",
      args.toTypedArray(),
    )
  }

  /**
   * Le copie private di una share mai rilette, con l'hash della versione noto. **Mai** quelle la cui rilettura non e' tornata (segnate
   * non integre sullo stesso timbro: `nas_copy_mismatch`) ne' quelle di un media con un problema che chiede l'utente: la stessa copia
   * rovinata non si rilegge a ogni giro.
   */
  const val UNVERIFIED_SQL: String =
    "SELECT $NAS_COPY_COLUMNS FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.account_id = ? AND l.share_key = ? AND l.verified_at IS NULL AND l.location_type = 'nas' AND l.state = 'present' " +
      "AND v.full_hash IS NOT NULL " +
      "AND NOT EXISTS (SELECT 1 FROM nas_copy_mismatch x WHERE x.location_id = l.location_id AND x.size = l.size AND x.modified_at = l.modified_at) " +
      "AND NOT EXISTS (SELECT 1 FROM backup_problem bp WHERE bp.media_id = v.media_id AND bp.marks_cell = 1) " +
      "ORDER BY l.location_id LIMIT ?"

  const val ROTATION_SQL: String =
    "SELECT $NAS_COPY_COLUMNS FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.account_id = ? AND l.share_key = ? AND l.verified_at IS NOT NULL AND l.location_type = 'nas' AND l.state = 'present' " +
      "ORDER BY l.verified_at, l.location_id LIMIT ?"

  const val FULL_HASH_SQL: String =
    "SELECT $NAS_COPY_COLUMNS FROM work_state w CROSS JOIN media m ON m.media_id = w.media_id " +
      "CROSS JOIN media_version v ON v.version_id = m.current_version_id CROSS JOIN media_location l ON l.version_id = v.version_id " +
      "WHERE w.stage = 'full_hash' AND w.status = 'pending' AND v.full_hash IS NULL AND +l.location_type = 'nas' " +
      "AND +l.state = 'present' AND +l.account_id = ? ORDER BY w.updated_at LIMIT ?"
}
