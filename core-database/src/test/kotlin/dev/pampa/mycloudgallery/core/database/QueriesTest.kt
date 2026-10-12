package dev.pampa.mycloudgallery.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.DAY
import dev.pampa.mycloudgallery.core.database.Db.SPACE
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.full
import dev.pampa.mycloudgallery.core.database.Db.nas
import dev.pampa.mycloudgallery.core.database.Db.obs
import dev.pampa.mycloudgallery.core.database.Db.phone
import dev.pampa.mycloudgallery.core.database.dao.TimelineDao
import dev.pampa.mycloudgallery.core.database.entity.TimelineItem
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.VectorPrecision
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Le query che serviranno (timeline, code di lavoro, impronte, vettori) e le garanzie dello schema. */
@RunWith(AndroidJUnit4::class)
class QueriesTest : CatalogTestBase() {

  private suspend fun photo(i: Int, capture: Long, source: MediaSource = MediaSource.CAMERA) =
    catalog.record(obs(phone("IMG_$i.jpg", 10L + i), full(200 + i), capture = capture, source = source)).mediaId!!

  // --- Timeline ------------------------------------------------------------------------------------

  @Test
  fun laTimelineSiPaginaAChiaveSenzaSaltiNeRipetizioni() = runBlocking<Unit> {
    // 25 media, di cui 5 con la stessa data: la chiave secondaria (media_id) li separa.
    val ids = (0 until 25).map { i -> photo(i, if (i < 5) T0 else T0 + i * 1_000L) }

    val seen = mutableListOf<TimelineItem>()
    var page = db.timelineDao().pageBefore(TimelineDao.START_SORT_TIME, TimelineDao.START_MEDIA_ID, 7)
    while (page.isNotEmpty()) {
      seen += page
      page = db.timelineDao().pageBefore(page.last().sortTime, page.last().mediaId, 7)
    }

    assertThat(seen.map { it.mediaId }).containsExactlyElementsIn(ids.map { it.value })
    assertThat(seen).isInOrder(compareByDescending<TimelineItem> { it.sortTime }.thenByDescending { it.mediaId })
    assertThat(seen.map { it.mediaId }).isEqualTo(db.timelineDao().observeFirstPage(100).first().map { it.mediaId })

    // Verso il presente da una cella qualunque (scrubber): le stesse celle, all'indietro.
    val anchor = seen[10]
    val newer = db.timelineDao().pageAfter(anchor.sortTime, anchor.mediaId, 100)
    assertThat(newer.map { it.mediaId }).isEqualTo(seen.take(10).map { it.mediaId }.reversed())
  }

  @Test
  fun nascostiEMediaIrraggiungibiliNonCompaionoInTimeline() = runBlocking<Unit> {
    val visible = photo(1, T0)
    val hidden = photo(2, T0 + 1)
    val gone = photo(3, T0 + 2)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    catalog.setPersonalFlags(visible, PersonalFlags(favorite = true))
    catalog.markVanished(phone("IMG_3.jpg", 13).key)

    val page = db.timelineDao().observeFirstPage(10).first().map { it.toEntry() }

    assertThat(page.map { it.mediaId }).containsExactly(visible)
    assertThat(page.single().favorite).isTrue()
    assertThat(db.timelineDao().observeVisibleCount().first()).isEqualTo(1)
    assertThat(gone).isNotEqualTo(visible)
  }

  @Test
  fun ilFiltroRapidoPerSorgenteUsaLaStessaPaginazione() = runBlocking<Unit> {
    photo(1, T0)
    val shot = photo(2, T0 + 1, MediaSource.SCREENSHOT)
    val page = db.timelineDao().pageBeforeForSource(MediaSource.SCREENSHOT.code, TimelineDao.START_SORT_TIME, TimelineDao.START_MEDIA_ID, 10)
    assertThat(page.map { it.mediaId }).containsExactly(shot.value)
  }

  /** La pagina della timeline scorre l'indice (sort_time, media_id): nessun ordinamento in memoria. */
  @Test
  fun laTimelineUsaLIndiceSenzaOrdinareInMemoria() = runBlocking<Unit> {
    (0 until 50).forEach { photo(it, T0 + it) }
    val parameters = Regex(":[a-zA-Z]+").findAll(TimelineDao.PAGE_BEFORE).map { it.value }.toList()
    val args: Array<Any> = parameters.map<String, Any> {
      when (it) {
        ":sortTime" -> T0 + 25
        ":mediaId" -> "~"
        ":limit" -> 10
        else -> error("Parametro inatteso $it")
      }
    }.toTypedArray()
    val sql = "EXPLAIN QUERY PLAN " + TimelineDao.PAGE_BEFORE.replace(Regex(":[a-zA-Z]+"), "?")

    val plan = db.openHelper.readableDatabase.query(sql, args).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }

    assertThat(plan.joinToString(" | ")).contains("index_media_sort_time_media_id")
    assertThat(plan.joinToString(" | ")).doesNotContain("TEMP B-TREE")
  }

  // --- Code di lavoro ------------------------------------------------------------------------------

  @Test
  fun laCodaDiUnaFaseVaDalPiuRecenteEIgnoraIlLavoroFatto() = runBlocking<Unit> {
    val old = photo(1, T0)
    val middle = photo(2, T0 + DAY)
    val recent = photo(3, T0 + 2 * DAY)
    val done = catalog.workStates(middle).first { it.stage == WorkStage.EMBED }
    catalog.updateWork(middle, WorkRules.succeeded(done, catalog.media(middle)!!.currentVersionId, T0))

    val queue = db.workStateDao().nextPending(WorkStage.EMBED.code, 10)

    assertThat(queue.map { it.mediaId }).containsExactly(recent.value, old.value).inOrder()
    val counts = db.workStateDao().observeCounts(WorkStage.EMBED.code).first().associate { it.status to it.count }
    assertThat(counts).containsExactly("pending", 2, "done", 1)
  }

  // --- Impronte -----------------------------------------------------------------------------------

  @Test
  fun leVersioniSiTrovanoPerImpronta() = runBlocking<Unit> {
    val id = catalog.record(obs(nas("a.jpg"), full(1))).mediaId!!
    catalog.record(obs(nas("b.jpg"), full(2)))
    val fp = full(1)

    val byQuick = db.versionDao().byQuickFingerprint(fp.size, fp.quickHash.hex)
    val byFull = db.versionDao().byFullHash(fp.fullHash!!.hex)

    assertThat(byQuick.map { it.mediaId }).containsExactly(id.value)
    assertThat(byFull.map { it.mediaId }).containsExactly(id.value)
  }

  // --- Vettori (E-I4) ------------------------------------------------------------------------------

  @Test
  fun iVettoriDiSpaziDiversiNonSiMescolanoMai() = runBlocking<Unit> {
    val id = photo(1, T0)
    val version = catalog.media(id)!!.currentVersionId
    val smaller = EmbeddingSpace(SPACE.id, 2, VectorPrecision.FLOAT32)
    val otherModel = EmbeddingSpace("mobileclip2-float-v1", 4, VectorPrecision.FLOAT32)
    catalog.putEmbedding(id, EmbeddingVector.ofFloats(SPACE, 0, floatArrayOf(1f, 2f, 3f, 4f), version))
    catalog.putEmbedding(id, EmbeddingVector.ofFloats(smaller, 0, floatArrayOf(1f, 2f), version))
    catalog.putEmbedding(id, EmbeddingVector.ofFloats(otherModel, 0, floatArrayOf(4f, 3f, 2f, 1f), version))

    val rows = db.embeddingDao().scan(SPACE.id, SPACE.dimension, SPACE.precision.code, "", -1, 100)

    assertThat(rows).hasSize(1)
    assertThat(rows.single().toDomain().space).isEqualTo(SPACE)
    assertThat(rows.single().toDomain().toFloats().toList()).containsExactly(1f, 2f, 3f, 4f).inOrder()
    assertThat(db.embeddingDao().count(smaller.id, smaller.dimension, smaller.precision.code)).isEqualTo(1)
    assertThat(db.embeddingDao().forMedia(id.value)).hasSize(3)
  }

  /** Un video ha piu' vettori (fotogrammi chiave): la scansione a chiave non ne perde nessuno. */
  @Test
  fun laScansioneDiUnoSpazioAttraversaTuttiIVettoriAPagine() = runBlocking<Unit> {
    val ids = (0 until 4).map { photo(it, T0 + it) }
    ids.forEach { id ->
      repeat(3) { frame -> catalog.putEmbedding(id, EmbeddingVector.ofFloats(SPACE, frame, floatArrayOf(frame.toFloat(), 0f, 0f, 0f), null)) }
    }

    val seen = mutableListOf<Pair<String, Int>>()
    var page = db.embeddingDao().scan(SPACE.id, SPACE.dimension, SPACE.precision.code, "", -1, 5)
    while (page.isNotEmpty()) {
      seen += page.map { it.mediaId to it.vectorIndex }
      page = db.embeddingDao().scan(SPACE.id, SPACE.dimension, SPACE.precision.code, page.last().mediaId, page.last().vectorIndex, 5)
    }

    assertThat(seen).hasSize(12)
    assertThat(seen.toSet()).hasSize(12)
  }

  // --- Garanzie dello schema -----------------------------------------------------------------------

  /** Una versione che ha ancora copie conosciute non si puo' cancellare: nessuna versione sparisce per errore. */
  @Test
  fun unaVersioneConCopieConosciuteNonSiPuoCancellare() = runBlocking<Unit> {
    val id = photo(1, T0)
    val version = catalog.media(id)!!.currentVersionId.value
    assertThrows(SQLiteConstraintException::class.java) {
      db.openHelper.writableDatabase.execSQL("DELETE FROM media_version WHERE version_id = ?", arrayOf(version))
    }
  }

  /** La versione corrente di un media non puo' essere la versione di un altro media. */
  @Test
  fun laVersioneCorrenteAppartieneSempreAlMedia() = runBlocking<Unit> {
    val a = photo(1, T0)
    val b = photo(2, T0)
    val otherVersion = catalog.media(b)!!.currentVersionId.value
    assertThrows(SQLiteConstraintException::class.java) {
      db.openHelper.writableDatabase.execSQL(
        "UPDATE media SET current_version_id = ? WHERE media_id = ?",
        arrayOf(otherVersion, a.value),
      )
    }
  }

  /** Due righe con la stessa chiave di posto (maiuscole a parte) non possono esistere (E-N7). */
  @Test
  fun laChiaveDiPostoEUnicaNelDatabase() = runBlocking<Unit> {
    catalog.record(obs(nas("Foto/A.jpg"), full(1)))
    val row = db.locationDao().byKey(nas("foto/a.JPG").key.value)!!
    assertThrows(SQLiteConstraintException::class.java) {
      runBlocking { db.locationDao().insert(row.copy(locationId = 0, nasPath = "FOTO/A.JPG")) }
    }
  }
}
