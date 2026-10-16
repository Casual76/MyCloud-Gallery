package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.Db.DAY
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.full
import dev.pampa.mycloudgallery.core.database.Db.obs
import dev.pampa.mycloudgallery.core.database.Db.phone
import dev.pampa.mycloudgallery.core.database.dao.IndexPublishDao
import dev.pampa.mycloudgallery.core.database.dao.LocationDao
import dev.pampa.mycloudgallery.core.database.dao.NasOpsDao
import dev.pampa.mycloudgallery.core.database.dao.ReplicaDao
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.database.dao.WorkStateDao
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.random.Random

/**
 * Le query della v3 su numeri veri (§4.4, riga P2 del "Fatto quando"):
 * - **20 mila media solo-NAS con META in coda non fermano la META del telefono** (prima la coda del telefono li restituiva
 *   per primi e la fase META li saltava fino a `metaMaxSkips` = 2.000 volte, poi si fermava);
 * - `EXPLAIN QUERY PLAN` di ogni query nuova: l'indice giusto e nessun ordinamento in memoria (`USE TEMP B-TREE`), senza
 *   statistiche (sul telefono nessuno esegue `ANALYZE`) e con.
 */
@RunWith(AndroidJUnit4::class)
class V3QueriesTest : CatalogTestBase() {

  /**
   * [count] media **solo-NAS** scritti in SQL grezzo (il catalogo ne scriverebbe 20 mila in minuti: qui si provano le
   * code), tutti piu' recenti di [newerThan] e con META in coda, come dopo una prima scansione del NAS.
   */
  private fun bulkNasOnly(count: Int, newerThan: Long): Set<String> {
    val sql = db.openHelper.writableDatabase
    val random = Random(7)
    val ids = HashSet<String>()
    sql.beginTransaction()
    try {
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', ?)",
      )
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) " +
          "VALUES (?, 'photo', 'other', 'alessio', NULL, ?, 0, 0, ?, 'nas_only', 'unverified', 20251009, 1000, NULL, NULL, ?, NULL)",
      )
      val location = sql.compileStatement(
        "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
          "media_store_id, media_store_version, generation, account_id, share_name, share_key, nas_path, size, modified_at, absent_since, " +
          "verified_at, last_seen_at, file_id) VALUES (?, 'nas', ?, ?, 'present', NULL, NULL, NULL, NULL, NULL, NULL, 'alessio', 'alessio', " +
          "'alessio', ?, ?, ?, NULL, NULL, ?, ?)",
      )
      val work = sql.compileStatement(
        "INSERT INTO work_state (media_id, stage, status, version_id, attempts, last_error, updated_at) VALUES (?, ?, 'pending', ?, 0, NULL, 0)",
      )
      for (i in 0 until count) {
        val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
        val versionId = UUID(random.nextLong(), random.nextLong()).toString()
        val folder = "foto/${i / 1000}"
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, 10_000L + i)
        version.bindString(4, "%064x".format(i))
        version.bindLong(5, newerThan + i)
        version.executeInsert()
        media.bindString(1, mediaId)
        media.bindString(2, versionId)
        media.bindLong(3, newerThan + 1 + i)
        media.bindLong(4, CellFlags.ON_NAS.toLong())
        media.executeInsert()
        location.bindString(1, versionId)
        location.bindString(2, "nas:alessio:alessio:$folder/img_$i.jpg")
        location.bindString(3, "nas:alessio:alessio:$folder")
        location.bindString(4, "Foto/${i / 1000}/IMG_$i.jpg")
        location.bindLong(5, 10_000L + i)
        location.bindLong(6, newerThan + i)
        location.bindLong(7, T0 + i % 100)
        location.bindLong(8, 1_000_000L + i)
        location.executeInsert()
        WorkRules.initialStages(dev.pampa.mycloudgallery.core.model.MediaKind.PHOTO).forEach { stage ->
          work.bindString(1, mediaId)
          work.bindString(2, stage.code)
          work.bindString(3, versionId)
          work.executeInsert()
        }
        ids += mediaId
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    return ids
  }

  /**
   * Il giro della fase META del telefono come lo fa `DefaultMetadataPass` (core-mediastore): a lotti, `limit` = provati +
   * lotto, chi non e' del telefono e' un salto, e dopo `metaMaxSkips` salti il giro si ferma.
   */
  @Test
  fun ventiMilaMediaSoloNasConMetaInCodaNonFermanoLaMetaDelTelefono() = runBlocking<Unit> {
    val phoneIds = (0 until 60).map { i -> catalog.record(obs(phone("IMG_$i.jpg", 10L + i), full(i), capture = T0 - DAY - i)).mediaId!! }
    val nasIds = bulkNasOnly(20_000, newerThan = T0)
    // La coda del NAS li ha davvero, e prima di tutto: senza il filtro il telefono li avrebbe visti per primi.
    assertThat(db.workStateDao().nextPendingOnNas(WorkStage.META.code, 10).map { it.mediaId }.all { it in nasIds }).isTrue()

    val maxSkips = 2_000
    val batch = 32
    val tried = LinkedHashSet<String>()
    var skips = 0
    var processed = 0
    val started = System.nanoTime()
    while (skips < maxSkips) {
      val next = db.workStateDao().nextPending(WorkStage.META.code, tried.size + batch).map { it.mediaId }.filter { it !in tried }.take(batch)
      if (next.isEmpty()) break
      for (id in next) {
        tried += id
        if (id in nasIds) {
          skips++
          continue
        }
        val state = catalog.workStates(MediaId(id)).first { it.stage == WorkStage.META }
        catalog.updateWork(MediaId(id), WorkRules.succeeded(state, state.versionId!!, now))
        processed++
      }
    }
    val millis = (System.nanoTime() - started) / 1_000_000
    println("META del telefono con 20.000 media solo-NAS in coda: $processed fatti, $skips salti, $millis ms")

    assertThat(skips).isEqualTo(0)
    assertThat(processed).isEqualTo(phoneIds.size)
    phoneIds.forEach { id -> assertThat(catalog.workStates(id).first { it.stage == WorkStage.META }.status).isEqualTo(WorkStatus.DONE) }
    // La coda del NAS resta intatta, e i fallimenti si riprovano ognuno dalla sua parte.
    assertThat(db.workStateDao().nextPendingOnNas(WorkStage.META.code, 50_000)).hasSize(nasIds.size)
    assertThat(db.workStateDao().retryable(WorkStage.META.code, Long.MAX_VALUE, 10)).isEmpty()
    // Budget largo su JVM (60 lotti che scorrono 20 mila righe dall'indice): il dispositivo si misura in P10.
    assertThat(millis).isLessThan(20_000)
  }

  private fun planOf(query: String): String {
    val sql = query.replace(Regex("IN \\(:[a-zA-Z]+\\)"), "IN ('a', 'b')").replace(Regex(":[a-zA-Z]+"), "?")
    return db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")
  }

  /** Ogni query nuova della v3 che scorre righe: l'indice atteso, nessun ordinamento in memoria. */
  @Test
  fun leQueryNuoveUsanoGliIndiciSenzaOrdinareInMemoria() = runBlocking<Unit> {
    (0 until 20).forEach { i -> catalog.record(obs(phone("IMG_$i.jpg", 10L + i), full(i), capture = T0 - i)) }
    bulkNasOnly(3_000, newerThan = T0)

    val expected = mapOf(
      "nextPending (telefono)" to (WorkStateDao.NEXT_PENDING_ON_PHONE_SQL to "index_media_sort_time_media_id"),
      "nextPendingOnNas" to (WorkStateDao.NEXT_PENDING_ON_NAS_SQL to "index_media_sort_time_media_id"),
      "retryable (telefono)" to (WorkStateDao.RETRYABLE_ON_PHONE_SQL to "index_work_state_stage_status_updated_at"),
      "retryableOnNas" to (WorkStateDao.RETRYABLE_ON_NAS_SQL to "index_work_state_stage_status_updated_at"),
      "knownInFolder" to (LocationDao.KNOWN_IN_FOLDER_SQL to "index_media_location_folder_key"),
      // Dalla v4 gli indici (account, share, last_seen_at) e (account, share, verified_at) hanno lo stesso prefisso: il conteggio
      // scorre l'uno o l'altro (stessa spesa), l'importante e' che parta dalla share e non da tutte le posizioni.
      "countPresentInShare" to (LocationDao.COUNT_PRESENT_IN_SHARE_SQL to "index_media_location_account_id_share_key_"),
      "notSeenInShareSince" to (LocationDao.NOT_SEEN_IN_SHARE_SQL to "index_media_location_account_id_share_key_last_seen_at"),
      "presentInShare" to (LocationDao.PRESENT_IN_SHARE_SQL to "index_media_location_account_id_share_key_last_seen_at"),
      "nasRefs" to (TimelineIndexDao.NAS_REFS_SQL to "index_media_location_version_id"),
      "outboxRecords" to (IndexPublishDao.OUTBOX_RECORDS_SQL to "index_index_outbox_record_share_key_seq"),
      "outboxBlobs" to (IndexPublishDao.OUTBOX_BLOBS_SQL to "index_index_outbox_blob_share_key"),
      "recordsAt" to (ReplicaDao.RECORDS_AT_SQL to "index_index_record_share_key_record_key"),
      "blobsFor" to (ReplicaDao.BLOBS_FOR_SQL to "index_replica_blob_content_key_short_cls"),
      "itemsToResume" to (NasOpsDao.ITEMS_TO_RESUME_SQL to "index_nas_op_item_state_next_attempt_at"),
      "expiredTrash" to (NasOpsDao.EXPIRED_TRASH_SQL to "index_nas_trash_entry_state_trashed_at"),
    )

    val plain = expected.mapValues { (_, query) -> planOf(query.first) }
    db.openHelper.writableDatabase.execSQL("ANALYZE")
    val analyzed = expected.mapValues { (_, query) -> planOf(query.first) }

    expected.forEach { (name, query) ->
      println("Piano di $name: ${plain.getValue(name)} / dopo ANALYZE: ${analyzed.getValue(name)}")
      listOf(plain.getValue(name), analyzed.getValue(name)).forEach { plan ->
        assertWithMessage(name).that(plan).contains(query.second)
        assertWithMessage(name).that(plan).doesNotContain("TEMP B-TREE")
      }
    }
    // I riferimenti al NAS non partono mai da tutte le posizioni presenti (come quelli del telefono, P11 della Fase 3).
    listOf(plain, analyzed).forEach { assertThat(it.getValue("nasRefs")).doesNotContain("index_media_location_state_absent_since") }
  }
}
