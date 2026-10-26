package dev.pampa.mycloudgallery.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.dao.AlbumSummaryRow
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareOpEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.database.entity.HomeEstimateEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumHeadMarkEntity
import dev.pampa.mycloudgallery.core.database.entity.TripProposalEntity
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I DAO delle tabelle nuove della v6 bastano alle sequenze che i pacchetti dopo useranno (proiezione, replica degli album condivisi,
 * diario della condivisione, viaggi): scrivono, rileggono, tolgono in cascata e rifiutano cio' che il diario non deve accettare.
 */
@RunWith(AndroidJUnit4::class)
class AlbumTablesV6Test : CatalogTestBase() {

  private suspend fun media(n: Int): MediaId = catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), T0 - n)).mediaId!!

  private suspend fun album(id: String, kind: String = "manual", state: String? = null) =
    db.albumDao().upsert(AlbumEntity(id, "Titolo $id", null, T0, T0, kind = kind, state = state))

  // --- album e riepiloghi ----------------------------------------------------------------------------------------------------

  @Test
  fun iRiepiloghiContanoSoloGliElementiMostrabiliEDannoIlGiornoPiuRecente() = runBlocking<Unit> {
    val a = media(1)
    val b = media(2)
    val hidden = media(3)
    album("uno")
    album("vuoto")
    album("solo-nascosto")
    db.albumDao().upsertItems(
      listOf(
        AlbumItemEntity("uno", a.value, 1000, T0),
        AlbumItemEntity("uno", b.value, 2000, T0),
        AlbumItemEntity("uno", hidden.value, 3000, T0),
        AlbumItemEntity("solo-nascosto", hidden.value, 1000, T0),
      ),
    )
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))

    val summaries = db.albumDao().summaries().associateBy { it.albumId }

    assertThat(summaries.keys).containsExactly("uno")
    val newest = maxOf(db.mediaDao().get(a.value)!!.localDay, db.mediaDao().get(b.value)!!.localDay)
    assertThat(summaries.getValue("uno")).isEqualTo(AlbumSummaryRow("uno", 2, newest))
    // Gli stati: un album personale e uno condiviso nella stessa tabella, distinti da `state`.
    album("condiviso", state = "active")
    assertThat(db.albumDao().all().filter { it.state != null }.map { it.albumId }).containsExactly("condiviso")
    // Un album legato a uno condiviso (voce `shared`).
    db.albumDao().upsert(AlbumEntity("uno", "Titolo uno", null, T0, T0, kind = "smart", frozen = true, sharedId = "condiviso"))
    assertThat(db.albumDao().linkedTo("condiviso").map { it.albumId }).containsExactly("uno")
    assertThat(db.albumDao().get("uno")!!.frozen).isTrue()
    // Riscrivere un album non toglie i suoi elementi.
    assertThat(db.albumDao().items("uno")).hasSize(3)
    db.albumDao().delete("uno")
    assertThat(db.albumDao().items("uno")).isEmpty()
  }

  @Test
  fun iSuggeritiContanoSoloIMostrabiliEQuelliArrivatiDopoUnIstante() = runBlocking<Unit> {
    val a = media(1)
    val b = media(2)
    val hidden = media(3)
    album("smart", kind = "smart")
    db.albumChoiceDao().upsertSuggestions(
      listOf(
        AlbumSuggestionEntity("smart", a.value, "s1", T0),
        AlbumSuggestionEntity("smart", b.value, "s3", T0 + 10),
        AlbumSuggestionEntity("smart", hidden.value, "s1", T0 + 20),
      ),
    )
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))

    val counts = db.albumChoiceDao().suggestionCounts(since = T0 + 5).single()

    assertThat(counts.visibleCount).isEqualTo(2)
    assertThat(counts.newCount).isEqualTo(1)
    db.albumChoiceDao().removeSuggestion("smart", a.value)
    assertThat(db.albumChoiceDao().suggestions("smart").map { it.mediaId }).containsExactly(b.value, hidden.value)
  }

  /**
   * D22, E-L11 (Fase 7, P9): un gruppo in cui **un** membro e' nascosto e' nascosto per intero, anche se il segno e' arrivato a meta' (scritto senza passare da
   * `setPersonalFlags`, che lo propaga): la copertina visibile di quel gruppo non si conta ne' fra gli elementi ne' fra i suggeriti, e non da' il giorno piu' recente.
   */
  @Test
  fun unGruppoConUnMembroNascostoNonSiConta() = runBlocking<Unit> {
    val cover = media(1)
    val mate = media(2)
    val alone = media(3)
    catalog.group(MediaGroupKind.BURST, listOf(cover, mate), cover)
    album("uno")
    album("smart", kind = "smart")
    db.albumDao().upsertItems(listOf(AlbumItemEntity("uno", cover.value, 1000, T0), AlbumItemEntity("uno", alone.value, 2000, T0)))
    db.albumChoiceDao().upsertSuggestions(listOf(AlbumSuggestionEntity("smart", cover.value, "s1", T0), AlbumSuggestionEntity("smart", alone.value, "s1", T0)))
    assertThat(db.albumDao().summaries().single().visibleCount).isEqualTo(2)
    assertThat(db.albumChoiceDao().suggestionCounts(since = 0).single().visibleCount).isEqualTo(2)

    // Il dato arriva a meta': solo il membro non copertina e' segnato nascosto.
    db.personalDao().upsert(MediaPersonalEntity(mate.value, favorite = false, hidden = true, updatedAt = T0))

    assertThat(db.albumDao().summaries().single().visibleCount).isEqualTo(1)
    assertThat(db.albumDao().summaries().single().newestDay).isEqualTo(db.mediaDao().get(alone.value)!!.localDay)
    assertThat(db.albumChoiceDao().suggestionCounts(since = 0).single().visibleCount).isEqualTo(1)
  }

  // --- replica degli album condivisi -------------------------------------------------------------------------------------

  private fun entry(device: String, key: String, field: String = "item", contentKey: String? = null, tombstone: Boolean = false, at: Long = T0) =
    SharedAlbumEntryEntity("shared-1", device, key, field, at, "marco", contentKey, tombstone, byteArrayOf(1, 2, 3))

  @Test
  fun laReplicaDegliAlbumCondivisiSiScriveSiRifondeESiDimentica() = runBlocking<Unit> {
    val dao = db.sharedAlbumDao()
    dao.upsert(SharedAlbumEntity("shared-1", "active", "add", "alessio", folder = "Album condivisi/Oslo", updatedAtNas = T0))
    dao.upsertEntries(
      listOf(
        entry("tablet", "k1", contentKey = "c".repeat(64)),
        entry("tablet", "k2", field = "definition"),
        entry("telefono", "k3", contentKey = "c".repeat(64), tombstone = true, at = T0 + 1),
        entry("telefono", "k4", contentKey = "d".repeat(64)),
      ),
    )
    dao.putMark(SharedAlbumHeadMarkEntity("shared-1", "tablet", 3, "h".repeat(64), 9, T0))

    assertThat(dao.entries("shared-1").map { it.deviceId to it.entryKey }).containsExactly("tablet" to "k1", "tablet" to "k2", "telefono" to "k3", "telefono" to "k4").inOrder()
    // E-SH12: lo stesso media aggiunto da due dispositivi si vede dalla chiave di contenuto.
    assertThat(dao.entriesWithContentKeys("shared-1", listOf("c".repeat(64))).map { it.entryKey }).containsExactly("k1", "k3")
    assertThat(dao.entriesWithContentKeys("shared-1", listOf("c".repeat(64))).first { it.entryKey == "k3" }.tombstone).isTrue()
    assertThat(dao.entriesOf("shared-1", "telefono").first { it.entryKey == "k4" }.body.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
    // Una testa nuova: si tolgono le voci sparite del contributo e si riscrivono le altre.
    dao.removeEntries("shared-1", "tablet", dao.entryKeysOf("shared-1", "tablet") - setOf("k1"))
    assertThat(dao.entryKeysOf("shared-1", "tablet")).containsExactly("k1")
    dao.upsertEntries(listOf(entry("tablet", "k1", contentKey = "e".repeat(64), at = T0 + 9)))
    assertThat(dao.entriesOf("shared-1", "tablet").single().at).isEqualTo(T0 + 9)
    assertThat(dao.entryCount("shared-1", "telefono")).isEqualTo(2)
    // Un dispositivo revocato: i suoi contributi spariscono, quelli degli altri no.
    dao.removeDeviceEntries("telefono")
    dao.removeDeviceMarks("tablet")
    assertThat(dao.entries("shared-1").map { it.deviceId }.distinct()).containsExactly("tablet")
    assertThat(dao.marks("shared-1")).isEmpty()
    // Dimenticare l'album porta via tutto cio' che e' suo.
    dao.putMark(SharedAlbumHeadMarkEntity("shared-1", "tablet", 4, "h".repeat(64), 9, T0))
    dao.deleteEntries("shared-1")
    dao.deleteMarks("shared-1")
    dao.deleteAlbum("shared-1")
    assertThat(dao.all()).isEmpty()
    assertThat(dao.entries("shared-1")).isEmpty()
    assertThat(dao.marks("shared-1")).isEmpty()
  }

  // --- diario della condivisione -------------------------------------------------------------------------------------------

  private fun op(id: String, plan: String, createdAt: Long = T0) =
    AlbumShareOpEntity(id, "shared-1", "share", plan, "Oslo", "add", "00ff", "NAS", "Album condivisi/Oslo", "leave_out", false, createdAt, createdAt)

  private fun item(opId: String, index: Int, media: MediaId, state: String = "planned", key: String? = null) =
    AlbumShareItemEntity(opId, index, media.value, "move_in", state, true, 2_000, transferKey = key, updatedAt = T0)

  @Test
  fun ilDiarioSiScriveAtomicamenteSiRiprendeEInDueStatiNonSiDuplica() = runBlocking<Unit> {
    val m1 = media(1)
    val m2 = media(2)
    val diary = db.shareDiaryDao()
    db.withTransaction {
      diary.insertOp(op("op-b", "plan-b", createdAt = T0 + 5))
      diary.insertItems(listOf(item("op-b", 0, m1), item("op-b", 1, m2)))
      diary.insertOp(op("op-a", "plan-a"))
      diary.insertItems(listOf(item("op-a", 0, m1)))
    }

    assertThat(diary.opByPlan("plan-b")!!.opId).isEqualTo("op-b")
    assertThat(diary.openOps().map { it.opId }).containsExactly("op-a", "op-b").inOrder()
    assertThat(diary.items("op-b").map { it.itemIndex }).containsExactly(0, 1).inOrder()
    // Un passo: la chiave del trasferimento si scrive prima della chiamata, il biglietto dopo.
    diary.updateItem(item("op-b", 0, m1, "transferring", key = "k-1"))
    diary.updateItem(diary.item("op-b", 0)!!.copy(state = "in_common", ticket = "t-9", updatedAt = T0 + 1))
    val saved = diary.item("op-b", 0)!!
    assertThat(listOf(saved.state, saved.transferKey, saved.ticket)).containsExactly("in_common", "k-1", "t-9").inOrder()
    assertThat(diary.itemsWithTransferKey("k-1").map { it.opId to it.itemIndex }).containsExactly("op-b" to 0)
    assertThat(diary.countsByState().associate { it.status to it.count }).containsExactly("planned", 2, "in_common", 1)
    // Lo stesso piano non parte due volte.
    var duplicate: Throwable? = null
    try {
      diary.insertOp(op("op-c", "plan-a"))
    } catch (e: SQLiteConstraintException) {
      duplicate = e
    }
    assertThat(duplicate).isNotNull()
    // Finire un'operazione la toglie da quelle da riprendere, ma non le righe.
    diary.finish("op-a", T0 + 2)
    assertThat(diary.openOps().map { it.opId }).containsExactly("op-b")
    assertThat(diary.items("op-a")).hasSize(1)
    // Un'operazione tolta porta via i suoi elementi; un elemento di un media che non c'e' fa fallire la chiusura della transazione (chiave esterna).
    diary.deleteAll()
    assertThat(diary.items("op-b")).isEmpty()
    var orphan: Throwable? = null
    try {
      db.withTransaction {
        diary.insertOp(op("op-d", "plan-d"))
        diary.insertItems(listOf(item("op-d", 0, MediaId.random())))
      }
    } catch (e: SQLiteConstraintException) {
      orphan = e
    }
    assertThat(orphan).isNotNull()
  }

  // --- viaggi e casa ---------------------------------------------------------------------------------------------------------

  @Test
  fun leProposteSiOrdinanoDalPiuRecenteESiCollegano() = runBlocking<Unit> {
    val cover = media(1)
    val trips = db.tripDao()
    fun trip(id: String, from: Int, to: Int, cover: MediaId?) = TripProposalEntity(id.padEnd(64, '0'), "trip", from, to, "Luogo $id", 10, cover?.value, "1,2", null, T0)
    trips.upsertProposals(listOf(trip("a", 20250101, 20250105, cover), trip("b", 20260301, 20260307, null), trip("c", 20250601, 20250603, null)))

    assertThat(trips.proposals().map { it.tripId.first() }).containsExactly('b', 'c', 'a').inOrder()
    trips.setAlbum("b".padEnd(64, '0'), "album-1")
    assertThat(trips.proposal("b".padEnd(64, '0'))!!.albumId).isEqualTo("album-1")
    assertThat(trips.proposal("a".padEnd(64, '0'))!!.coverMediaId).isEqualTo(cover.value)
    trips.clearProposals()
    assertThat(trips.proposals()).isEmpty()
  }

  @Test
  fun laCasaSiScriveSiRiscriveESiToglie() = runBlocking<Unit> {
    val trips = db.tripDao()
    trips.upsertHome(listOf(HomeEstimateEntity(0, "home", 45.0, 7.0, 2.5, 80, 200, T0), HomeEstimateEntity(1, "habitual", 43.7, 10.4, 3.0, 25, 200, T0)))
    trips.upsertHome(listOf(HomeEstimateEntity(0, "home", 45.1, 7.1, 2.0, 90, 210, T0 + 1)))

    assertThat(trips.home().map { it.slot to it.days }).containsExactly(0 to 90, 1 to 25).inOrder()
    assertThat(trips.home().first().toString()).doesNotContain("45.1")
    trips.clearHome()
    assertThat(trips.home()).isEmpty()
  }
}
