package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceItemEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceRunEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **LS-15** della verifica funzionale 5B: `RoomFreeSpaceDiary.update` rileggeva **tutti** gli elementi del giro a ogni scrittura (migliaia di
 * righe, per cambiarne una). `FreeSpaceDao.itemsAt` legge solo gli indici chiesti, dalla chiave primaria (`run_id`, `item_index`): nessuna
 * colonna nuova e nessun indice nuovo, lo schema v4 e' quello di prima.
 */
@RunWith(AndroidJUnit4::class)
class FreeSpaceItemsAtTest : CatalogTestBase() {

  private suspend fun newRun(items: Int, planHash: String): Long {
    val media = catalog.record(phoneObservation("IMG_1.jpg", 1, smallFull(1), T0 - 1)).mediaId!!
    val version = catalog.versionOf(media)
    val run = db.freeSpaceDao().insertRun(
      FreeSpaceRunEntity(
        state = "verifying", filter = "all", excludeRecentDays = 30, planHash = planHash, deleteMode = "android_trash", itemCount = items,
        totalBytes = items * 2_000L, batchesTotal = 0, batchesDone = 0, createdAt = T0, updatedAt = T0,
      ),
    )
    db.freeSpaceDao().insertItems(
      (0 until items).map { index ->
        FreeSpaceItemEntity(
          runId = run, itemIndex = index, state = "planned", mediaId = media.value, versionId = version.value, phoneLocationId = 1, nasLocationId = null,
          size = 2_000, verifiedSize = null, verifiedModifiedAt = null, verifiedFileId = null, batchIndex = null, reason = null, updatedAt = T0,
        )
      },
    )
    return run
  }

  @Test
  fun itemsAt_daSoloGliElementiChiestiDelGiroInOrdine() = runBlocking<Unit> {
    val first = newRun(items = 20, planHash = "uno")
    val second = newRun(items = 20, planHash = "due")

    val rows = db.freeSpaceDao().itemsAt(first, listOf(15, 3, 7, 99))

    // Gli indici che non ci sono si ignorano (chi scrive li rifiuta con "Elemento sconosciuto."), l'ordine e' quello degli indici, e le righe
    // dello stesso indice di un altro giro non entrano.
    assertThat(rows.map { it.runId to it.itemIndex }).containsExactly(first to 3, first to 7, first to 15).inOrder()
    assertThat(db.freeSpaceDao().itemsAt(second, listOf(3)).single().runId).isEqualTo(second)
    assertThat(db.freeSpaceDao().itemsAt(first, emptyList())).isEmpty()
    // Quello che legge e' quello che legge `items`, riga per riga.
    assertThat(rows).containsExactlyElementsIn(db.freeSpaceDao().items(first).filter { it.itemIndex in setOf(3, 7, 15) }).inOrder()
  }

  @Test
  fun itemsAt_usaLaChiavePrimariaENonScorreIlGiro() = runBlocking<Unit> {
    newRun(items = 50, planHash = "uno")
    val plan = db.openHelper.readableDatabase
      .query("EXPLAIN QUERY PLAN SELECT * FROM free_space_item WHERE run_id = ? AND item_index IN (?, ?, ?) ORDER BY item_index", arrayOf<Any>(1L, 3, 7, 15))
      .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) } }
      .joinToString(" | ")

    // Una ricerca per indice su (run_id, item_index) e basta: ne' una scansione della tabella ne' un ordinamento a parte.
    assertThat(plan).contains("SEARCH free_space_item USING INDEX")
    assertThat(plan).doesNotContain("SCAN")
    assertThat(plan).doesNotContain("TEMP B-TREE")
  }
}
