package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.AlbumExclusionEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumRuleEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareOpEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.database.entity.HomeEstimateEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumHeadMarkEntity
import dev.pampa.mycloudgallery.core.database.entity.TripProposalEntity
import dev.pampa.mycloudgallery.core.model.CellFlags
import kotlinx.coroutines.flow.Flow

// I DAO dello schema v6 (Fase 7, D34; `docs/modello-dati.md` §14). Semplici di proposito: le scritture che devono stare insieme
// (la proiezione di un album, la fusione di un contributo, un passo del diario) le chiude chi le usa in una transazione
// (`GalleryDatabase.transaction`), come per i DAO `ai_*` della v5. Nessun titolo, cartella o luogo nei log.

/** Le regole degli album smart (`album_rule`): una riga per regola, le foto d'esempio una per posizione. */
@Dao
interface AlbumRuleDao {

  @Upsert
  suspend fun upsertAll(rules: List<AlbumRuleEntity>)

  @Query("SELECT * FROM album_rule WHERE album_id = :albumId ORDER BY rule, position")
  suspend fun rules(albumId: String): List<AlbumRuleEntity>

  /** Le righe di una regola di tutti gli album (es. `period`, per sapere quali smart coprono i giorni toccati da una scansione). */
  @Query("SELECT * FROM album_rule WHERE rule = :rule ORDER BY album_id, position")
  suspend fun ofRule(rule: String): List<AlbumRuleEntity>

  @Query("DELETE FROM album_rule WHERE album_id = :albumId")
  suspend fun clear(albumId: String)

  @Query("DELETE FROM album_rule WHERE album_id = :albumId AND rule = :rule")
  suspend fun clear(albumId: String, rule: String)
}

/** Le esclusioni e i suggeriti di un album smart (D14, D16). */
@Dao
interface AlbumChoiceDao {

  @Upsert
  suspend fun upsertExclusions(exclusions: List<AlbumExclusionEntity>)

  @Query("SELECT * FROM album_exclusion WHERE album_id = :albumId ORDER BY media_id")
  suspend fun exclusions(albumId: String): List<AlbumExclusionEntity>

  @Query("DELETE FROM album_exclusion WHERE album_id = :albumId AND media_id = :mediaId")
  suspend fun removeExclusion(albumId: String, mediaId: String)

  @Query("DELETE FROM album_exclusion WHERE album_id = :albumId")
  suspend fun clearExclusions(albumId: String)

  @Upsert
  suspend fun upsertSuggestions(suggestions: List<AlbumSuggestionEntity>)

  @Query("SELECT * FROM album_suggestion WHERE album_id = :albumId ORDER BY media_id")
  suspend fun suggestions(albumId: String): List<AlbumSuggestionEntity>

  @Query("DELETE FROM album_suggestion WHERE album_id = :albumId AND media_id = :mediaId")
  suspend fun removeSuggestion(albumId: String, mediaId: String)

  @Query("DELETE FROM album_suggestion WHERE album_id = :albumId")
  suspend fun clearSuggestions(albumId: String)

  /**
   * Quanti suggeriti **mostrabili** ha ogni album (mai un nascosto, D22) e quanti sono arrivati dopo [since] ("Nuove: 12"). Un solo
   * passaggio sulle righe di `album_suggestion`.
   */
  @Query(SUGGESTION_COUNTS_SQL)
  suspend fun suggestionCounts(since: Long): List<SuggestionCountRow>

  companion object {
    const val SUGGESTION_COUNTS_SQL: String =
      "SELECT s.album_id AS album_id, COUNT(*) AS visible_count, SUM(CASE WHEN s.added_at > :since THEN 1 ELSE 0 END) AS new_count " +
        "FROM album_suggestion s JOIN media m ON m.media_id = s.media_id LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "WHERE m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 AND (m.cell_flags & ${CellFlags.GROUP_MEMBER}) = 0 AND ${AlbumQueries.NOT_IN_HIDDEN_GROUP} " +
        "GROUP BY s.album_id"
  }
}

/** Una riga di [AlbumChoiceDao.suggestionCounts]. */
data class SuggestionCountRow(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "visible_count") val visibleCount: Int,
  @ColumnInfo(name = "new_count") val newCount: Int,
)

/**
 * La replica degli album condivisi (D6-D11; porte di P2): la definizione fusa, le voci verificate di ogni contributo e la memoria
 * dell'anti-rollback. **Tutto senza chiavi esterne**: lo scarta in blocco [forget] (una famiglia lasciata, un album chiuso e dimenticato,
 * l'azzeramento).
 */
@Dao
interface SharedAlbumDao {

  @Upsert
  suspend fun upsert(album: SharedAlbumEntity)

  @Query("SELECT * FROM shared_album WHERE album_id = :albumId")
  suspend fun get(albumId: String): SharedAlbumEntity?

  @Query("SELECT * FROM shared_album ORDER BY album_id")
  suspend fun all(): List<SharedAlbumEntity>

  @Query("SELECT * FROM shared_album ORDER BY album_id")
  fun observeAll(): Flow<List<SharedAlbumEntity>>

  // --- voci dei contributi ---

  @Upsert
  suspend fun upsertEntries(entries: List<SharedAlbumEntryEntity>)

  /** Tutte le voci verificate di un album, di tutti i dispositivi: l'ingresso di `AlbumFusion`. */
  @Query("SELECT * FROM shared_album_entry WHERE album_id = :albumId ORDER BY device_id, entry_key")
  suspend fun entries(albumId: String): List<SharedAlbumEntryEntity>

  @Query("SELECT * FROM shared_album_entry WHERE album_id = :albumId AND device_id = :deviceId ORDER BY entry_key")
  suspend fun entriesOf(albumId: String, deviceId: String): List<SharedAlbumEntryEntity>

  /** Le voci `item` (vive e lapidi) con queste chiavi di contenuto: due familiari che aggiungono lo stesso media (E-SH12). */
  @Query("SELECT * FROM shared_album_entry WHERE album_id = :albumId AND content_key IN (:contentKeys) ORDER BY device_id, entry_key")
  suspend fun entriesWithContentKeys(albumId: String, contentKeys: List<String>): List<SharedAlbumEntryEntity>

  /** Le chiavi di tutte le voci di un contributo, per toglierne le sparite dopo una testa nuova. */
  @Query("SELECT entry_key FROM shared_album_entry WHERE album_id = :albumId AND device_id = :deviceId ORDER BY entry_key")
  suspend fun entryKeysOf(albumId: String, deviceId: String): List<String>

  @Query("DELETE FROM shared_album_entry WHERE album_id = :albumId AND device_id = :deviceId AND entry_key IN (:entryKeys)")
  suspend fun removeEntries(albumId: String, deviceId: String, entryKeys: List<String>)

  /** Un dispositivo revocato o perso: i suoi contributi spariscono (i file restano sul NAS, `formato-nas.md` §4.6, E-SH20). */
  @Query("DELETE FROM shared_album_entry WHERE device_id = :deviceId")
  suspend fun removeDeviceEntries(deviceId: String)

  @Query("SELECT COUNT(*) FROM shared_album_entry WHERE album_id = :albumId AND device_id = :deviceId")
  suspend fun entryCount(albumId: String, deviceId: String): Int

  // --- anti-rollback ---

  @Upsert
  suspend fun putMark(mark: SharedAlbumHeadMarkEntity)

  @Query("SELECT * FROM shared_album_head_mark WHERE album_id = :albumId AND device_id = :deviceId")
  suspend fun mark(albumId: String, deviceId: String): SharedAlbumHeadMarkEntity?

  @Query("SELECT * FROM shared_album_head_mark WHERE album_id = :albumId ORDER BY device_id")
  suspend fun marks(albumId: String): List<SharedAlbumHeadMarkEntity>

  @Query("DELETE FROM shared_album_head_mark WHERE device_id = :deviceId")
  suspend fun removeDeviceMarks(deviceId: String)

  // --- dimenticare ---

  @Query("DELETE FROM shared_album WHERE album_id = :albumId")
  suspend fun deleteAlbum(albumId: String)

  @Query("DELETE FROM shared_album_entry WHERE album_id = :albumId")
  suspend fun deleteEntries(albumId: String)

  @Query("DELETE FROM shared_album_head_mark WHERE album_id = :albumId")
  suspend fun deleteMarks(albumId: String)

  @Query("DELETE FROM shared_album")
  suspend fun deleteAllAlbums()

  @Query("DELETE FROM shared_album_entry")
  suspend fun deleteAllEntries()

  @Query("DELETE FROM shared_album_head_mark")
  suspend fun deleteAllMarks()
}

/**
 * Il diario della condivisione (D29, §2.5; contratto `ShareDiary` di core-albums, che P4 implementa sopra questo DAO). **Locale**.
 * Ogni scrittura e' atomica da sola; [insertOp] e [insertItems] vanno nella stessa transazione (`ShareDiary.create`).
 */
@Dao
interface ShareDiaryDao {

  @Insert
  suspend fun insertOp(op: AlbumShareOpEntity)

  @Insert
  suspend fun insertItems(items: List<AlbumShareItemEntity>)

  @Query("SELECT * FROM album_share_op WHERE plan_hash = :planHash")
  suspend fun opByPlan(planHash: String): AlbumShareOpEntity?

  @Query("SELECT * FROM album_share_op WHERE op_id = :opId")
  suspend fun op(opId: String): AlbumShareOpEntity?

  /** Le operazioni non finite, le piu' vecchie prima: quelle che [ShareRunner.resumeAll] riprende. */
  @Query("SELECT * FROM album_share_op WHERE finished = 0 ORDER BY created_at, op_id")
  suspend fun openOps(): List<AlbumShareOpEntity>

  /**
   * Le ultime operazioni **chiuse**, le piu' recenti prima (revisione E, SH-2: la striscia "N da riprovare" e il tocco "Riprova" le leggono dal diario,
   * cosi' sopravvivono alla morte del processo; [ShareDiary.closedOps]).
   */
  @Query("SELECT * FROM album_share_op WHERE finished = 1 ORDER BY updated_at DESC, op_id DESC LIMIT :limit")
  suspend fun closedOps(limit: Int): List<AlbumShareOpEntity>

  @Query("SELECT * FROM album_share_item WHERE op_id = :opId ORDER BY item_index")
  suspend fun items(opId: String): List<AlbumShareItemEntity>

  @Query("SELECT * FROM album_share_item WHERE op_id = :opId AND item_index = :index")
  suspend fun item(opId: String, index: Int): AlbumShareItemEntity?

  /** Salva il passo nuovo di un elemento (la riga c'e' gia': la crea [insertItems]). */
  @Update
  suspend fun updateItem(item: AlbumShareItemEntity)

  @Query("UPDATE album_share_op SET finished = 1, updated_at = :now WHERE op_id = :opId")
  suspend fun finish(opId: String, now: Long)

  /** L'elemento che ha avviato un trasferimento con questa chiave (la chiave si salva prima della chiamata alla porta). */
  @Query("SELECT * FROM album_share_item WHERE transfer_key = :transferKey ORDER BY op_id, item_index")
  suspend fun itemsWithTransferKey(transferKey: String): List<AlbumShareItemEntity>

  /** Quanti elementi ci sono per stato (Diagnostica: solo numeri). */
  @Query("SELECT state AS status, COUNT(*) AS count FROM album_share_item GROUP BY state")
  suspend fun countsByState(): List<ShareStateCountRow>

  @Query("DELETE FROM album_share_op")
  suspend fun deleteAll()
}

/** Un conteggio per stato del diario ([ShareDiaryDao.countsByState]). */
data class ShareStateCountRow(
  @ColumnInfo(name = "status") val status: String,
  @ColumnInfo(name = "count") val count: Int,
)

/** Le proposte di viaggio (D19, D20) e la "casa" locale (D18). La "casa" la usa solo `core-albums/.../trips/`. */
@Dao
interface TripDao {

  @Upsert
  suspend fun upsertProposals(proposals: List<TripProposalEntity>)

  /** Dal piu' recente (`to_day` decrescente): l'ordine della sezione Viaggi. */
  @Query("SELECT * FROM trip_proposal ORDER BY to_day DESC, from_day DESC, trip_id")
  suspend fun proposals(): List<TripProposalEntity>

  @Query("SELECT * FROM trip_proposal ORDER BY to_day DESC, from_day DESC, trip_id")
  fun observeProposals(): Flow<List<TripProposalEntity>>

  @Query("SELECT * FROM trip_proposal WHERE trip_id = :tripId")
  suspend fun proposal(tripId: String): TripProposalEntity?

  @Query("DELETE FROM trip_proposal WHERE trip_id = :tripId")
  suspend fun deleteProposal(tripId: String)

  /** "Crea album": la proposta e' diventata l'album [albumId] e non si ripropone. */
  @Query("UPDATE trip_proposal SET album_id = :albumId WHERE trip_id = :tripId")
  suspend fun setAlbum(tripId: String, albumId: String)

  @Query("DELETE FROM trip_proposal")
  suspend fun clearProposals()

  // --- casa (solo trips/) ---

  @Upsert
  suspend fun upsertHome(places: List<HomeEstimateEntity>)

  @Query("SELECT * FROM home_estimate ORDER BY slot")
  suspend fun home(): List<HomeEstimateEntity>

  @Query("DELETE FROM home_estimate")
  suspend fun clearHome()
}
