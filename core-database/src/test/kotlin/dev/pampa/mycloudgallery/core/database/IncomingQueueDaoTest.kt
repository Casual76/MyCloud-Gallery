package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.model.MediaKind
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IN-7 della verifica funzionale 5B ("Ricevuti in coda" della pagina Backup): le query del diario degli arrivi (`incoming_item`) con lo stato del
 * loro elemento del backup (`backup_item`), i conti e il "Scarta" che toglie solo la riga. Database vero in memoria (Robolectric).
 */
@RunWith(AndroidJUnit4::class)
class IncomingQueueDaoTest : CatalogTestBase() {

  /** Un arrivo: il media che nascerebbe, l'elemento del diario nello stato [itemState] (null = nessun elemento) e la riga di `incoming_item`. */
  private suspend fun arrival(n: Int, state: String, itemState: String?, problem: String? = null, createdAt: Long = T0 + n): Long {
    val media = catalog.incomingMedia(smallFull(n), MediaKind.PHOTO, T0)
    val itemId = itemState?.let {
      db.backupDiaryDao().insert(backupItemEntity(media.mediaId, media.versionId, state = it, kind = "incoming").copy(problem = problem))
    }
    return db.incomingDao().insert(
      IncomingItemEntity(
        stageId = "stage$n",
        state = state,
        fileName = "f$n",
        displayName = "Foto $n",
        sha256 = "a".repeat(63) + n,
        size = 2_000,
        mime = "image/jpeg",
        destShare = "alessio",
        destFolder = "Ricevute",
        backupItemId = itemId,
        createdAt = createdAt,
        updatedAt = createdAt,
      ),
    )
  }

  @Test
  fun laCodaDaLeRigheNegliStatiChiestiDalPiuVecchiaConLoStatoDelloroElemento() = runBlocking<Unit> {
    val dao = db.incomingDao()
    val young = arrival(1, "queued", "planned", createdAt = T0 + 500)
    val old = arrival(2, "queued", "uploading", createdAt = T0 + 100)
    val failed = arrival(3, "failed", "failed", problem = "unreadable", createdAt = T0 + 300)
    arrival(4, "uploaded", "done")
    val orphan = arrival(5, "queued", itemState = null, createdAt = T0 + 900)

    val rows = dao.observeQueue(listOf("queued", "failed")).first()

    assertThat(rows.map { it.item.incomingId }).containsExactly(old, failed, young, orphan).inOrder()
    assertThat(rows.map { it.itemState }).containsExactly("uploading", "failed", "planned", null).inOrder()
    assertThat(rows.first { it.item.incomingId == failed }.itemProblem).isEqualTo("unreadable")
    // Un solo stato: solo quello. Gli "uploaded" non sono mai in coda.
    assertThat(dao.observeQueue(listOf("failed")).first().map { it.item.incomingId }).containsExactly(failed)
    assertThat(dao.observeQueue(listOf("gone")).first()).isEmpty()
  }

  @Test
  fun laRigaSingolaHaLoStessoStatoEUnaRigaChePiuNonCeENull() = runBlocking<Unit> {
    val dao = db.incomingDao()
    val id = arrival(1, "queued", "hashed")
    assertThat(dao.queueRow(id)!!.itemState).isEqualTo("hashed")
    assertThat(dao.queueRow(id + 100)).isNull()
  }

  @Test
  fun iContiSonoPerStatoESeguonoLeRighe() = runBlocking<Unit> {
    val dao = db.incomingDao()
    arrival(1, "queued", "planned")
    arrival(2, "failed", "failed", problem = "verify_failed")
    arrival(3, "gone", "gone")
    arrival(4, "uploaded", "done")
    assertThat(dao.countInStates(listOf("failed", "gone"))).isEqualTo(2)
    assertThat(dao.countInStates(listOf("queued"))).isEqualTo(1)
    assertThat(dao.countInStates(emptyList())).isEqualTo(0)

    val seen = async { withTimeout(5_000) { dao.observeCountInStates(listOf("failed", "gone")).first { it == 0 } } }
    delay(100)
    dao.delete(dao.inState("failed").single().incomingId)
    dao.delete(dao.inState("gone").single().incomingId)
    assertThat(seen.await()).isEqualTo(0)
  }

  @Test
  fun ilCaricamentoCheAvanzaRifaLaCodaAncheSeIncomingItemNonCambia() = runBlocking<Unit> {
    val dao = db.incomingDao()
    val id = arrival(1, "queued", "hashed")
    val seen = async { withTimeout(5_000) { dao.observeQueue(listOf("queued")).first { rows -> rows.singleOrNull()?.itemState == "uploading" } } }
    delay(100)
    val item = db.backupDiaryDao().get(dao.get(id)!!.backupItemId!!)!!
    db.backupDiaryDao().update(item.copy(state = "uploading", resumeAt = 64L * 1024 * 1024))
    assertThat(seen.await().single().item.incomingId).isEqualTo(id)
  }

  @Test
  fun scartareTogliaSoloLaRigaEMaiLElementoDelDiario() = runBlocking<Unit> {
    val dao = db.incomingDao()
    val id = arrival(1, "failed", "failed", problem = "unreadable")
    val itemId = dao.get(id)!!.backupItemId!!
    assertThat(dao.delete(id)).isEqualTo(1)
    assertThat(dao.get(id)).isNull()
    // Il diario del backup resta com'era (la riga di `incoming_item` non e' il diario del motore).
    assertThat(db.backupDiaryDao().get(itemId)).isNotNull()
    // Una riga che non c'e' piu': niente da togliere.
    assertThat(dao.delete(id)).isEqualTo(0)
  }
}
