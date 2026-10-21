package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.dao.CellContentDao
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.QuickHash
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Le chiavi di contenuto delle celle (Fase 6, P1: `cellContentKeys`, `cellContentKeysOf`): versioni correnti e precedenti, membri dei
 * gruppi, nascosti fuori, pagine a `rowid`, e la prestazione richiesta dalla riga P1 (100 mila media sotto 300 ms, senza
 * `USE TEMP B-TREE`). Database vero in memoria (Robolectric).
 */
@RunWith(AndroidJUnit4::class)
class CellContentKeysTest : CatalogTestBase() {

  private suspend fun phone(n: Int) = catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), T0 - n)).mediaId!!

  private fun key(seed: Int): ContentKey = ContentKey.of(smallFull(seed))

  private suspend fun allCells(limit: Int = 3): List<CellContent> {
    val cells = ArrayList<CellContent>()
    var after = 0L
    while (true) {
      val page = catalog.cellContentKeys(after, limit)
      cells += page.cells
      after = page.next ?: return cells
    }
  }

  @Test
  fun unaCellaHaLaChiaveDelContenutoEsattamenteComeContentKey() = runBlocking<Unit> {
    val id = phone(1)
    val cell = catalog.cellContentKeys().cells.single()
    assertThat(cell.mediaId).isEqualTo(id)
    assertThat(cell.own).containsExactly(key(1))
    assertThat(cell.members).isEmpty()
    assertThat(cell.keys).containsExactly(key(1))
  }

  @Test
  fun laVersioneCorrenteEPerPrimaPoiLePrecedenti() = runBlocking<Unit> {
    val id = phone(1)
    val parent = catalog.versionOf(id)
    val edited = smallFull(2)
    val second = catalog.addEditedVersion(id, parent, edited.fullHash!!, edited.quickHash, edited.size)!!
    val third = smallFull(3)
    catalog.addEditedVersion(id, second, third.fullHash!!, third.quickHash, third.size)!!

    val cell = catalog.cellContentKeys().cells.single()
    // La corrente (3), poi le precedenti dalla piu' recente: nessun vettore per la nuova? Vale quello di una precedente (E-A10).
    assertThat(cell.own.first()).isEqualTo(key(3))
    assertThat(cell.own).containsExactly(key(3), key(2), key(1))
    assertThat(cell.own.toSet()).hasSize(3)
  }

  @Test
  fun nascostiEMembriDiGruppoNonSonoCelleEIMembriVannoSullaCopertina() = runBlocking<Unit> {
    val cover = phone(1)
    val b = phone(2)
    val c = phone(3)
    val alone = phone(4)
    val hidden = phone(5)
    catalog.group(MediaGroupKind.BURST, listOf(cover, b, c), cover)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))

    val cells = allCells(limit = 10)
    assertThat(cells.map { it.mediaId }).containsExactly(cover, alone)
    val group = cells.single { it.mediaId == cover }
    assertThat(group.own).containsExactly(key(1))
    assertThat(group.members.map { it.mediaId }).containsExactly(b, c).inOrder()
    assertThat(group.members.map { it.keys }).containsExactly(listOf(key(2)), listOf(key(3))).inOrder()
    assertThat(group.keys).containsExactly(key(1), key(2), key(3)).inOrder()
    assertThat(cells.single { it.mediaId == alone }.members).isEmpty()

    // D29: un membro nascosto non porta mai il suo contenuto nella cella del gruppo.
    catalog.setPersonalFlags(c, PersonalFlags(hidden = true))
    val after = allCells(limit = 10).single { it.mediaId == cover }
    assertThat(after.members.map { it.mediaId }).containsExactly(b)
    assertThat(after.keys).doesNotContain(key(3))
  }

  @Test
  fun lePagineACursoreCoprononoOgniCellaUnaVoltaInOrdineDiSeq() = runBlocking<Unit> {
    val ids = (1..7).map { phone(it) }
    val first = catalog.cellContentKeys(0, 3)
    assertThat(first.cells).hasSize(3)
    assertThat(first.next).isNotNull()
    val all = allCells(limit = 3)
    assertThat(all.map { it.mediaId }).containsExactlyElementsIn(ids)
    assertThat(all.map { it.seq }).isInOrder()
    assertThat(all.map { it.seq }.toSet()).hasSize(7)
    // Una pagina grande basta, e l'ultima non ha un seguito.
    assertThat(catalog.cellContentKeys(0, 100).cells).hasSize(7)
    val end = catalog.cellContentKeys(all.last().seq, 3)
    assertThat(end.cells).isEmpty()
    assertThat(end.next).isNull()
  }

  @Test
  fun unMediaNuovoSiTrovaDopoIlCursoreEUnCambiamentoConCellContentKeysOf() = runBlocking<Unit> {
    val old = phone(1)
    val cursor = allCells().last().seq
    val fresh = phone(2)
    val news = catalog.cellContentKeys(cursor, 10).cells
    assertThat(news.map { it.mediaId }).containsExactly(fresh)

    // La cella esistente cambia (una versione nuova): si rilegge per id, come dopo un CatalogChange.
    val parent = catalog.versionOf(old)
    val edited = smallFull(9)
    catalog.addEditedVersion(old, parent, edited.fullHash!!, edited.quickHash, edited.size)
    val again = catalog.cellContentKeysOf(listOf(old, fresh, MediaId.random()))
    assertThat(again.map { it.mediaId }).containsExactly(old, fresh).inOrder()
    assertThat(again.first().own).containsExactly(key(9), key(1)).inOrder()
  }

  @Test
  fun laChiaveVelocePerAnalogiaDiContentKeyOf() {
    val random = Random(7)
    repeat(300) { i ->
      val hex = random.nextBytes(32).joinToString("") { "%02x".format(it) }
      val size = if (i == 0) 0L else random.nextLong(0, Long.MAX_VALUE)
      assertThat(FastContentKey.of(hex, size)).isEqualTo(ContentKey.of(QuickHash(hex), size))
    }
    // Un'impronta non valida non da' una chiave (la riga si salta) e la dimensione non e' negativa.
    assertThat(FastContentKey.of("zz".repeat(32), 1)).isNull()
    assertThat(FastContentKey.of("ab".repeat(31), 1)).isNull()
    assertThat(FastContentKey.of("AB".repeat(32), 1)).isNull()
    assertThat(FastContentKey.of("ab".repeat(32), -1)).isNull()
  }

  // --- Prestazioni: 100 mila media ------------------------------------------------------------------------------------

  /**
   * [count] media in SQL grezzo in una transazione: ogni 200 uno nascosto, ogni 300 uno sparito, ogni 100 una coppia copertina e
   * membro di un gruppo (1.000 gruppi). Restituisce quante celle ci si aspetta.
   */
  private fun bulkInsert(count: Int): Int {
    val sql = db.openHelper.writableDatabase
    val random = Random(42)
    var cells = 0
    sql.beginTransaction()
    try {
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', ?)",
      )
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) " +
          "VALUES (?, 'photo', 'camera', 'alessio', ?, ?, 0, 0, ?, ?, 'no_nas_copy', 20200101, 1333, NULL, NULL, ?, NULL)",
      )
      val group = sql.compileStatement("INSERT INTO media_group (group_id, kind, cover_media_id, created_at) VALUES (?, 'burst', ?, 0)")
      val hidden = sql.compileStatement("INSERT INTO media_personal (media_id, favorite, hidden, updated_at) VALUES (?, 0, 1, 0)")
      var lastCover: String? = null
      var lastGroup: String? = null
      for (i in 0 until count) {
        val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
        val versionId = UUID(random.nextLong(), random.nextLong()).toString()
        val gone = i % 300 == 211
        val cover = i % 100 == 36
        val member = i % 100 == 37
        val isHidden = i % 200 == 113
        val groupId = if (member) lastGroup else if (cover) UUID(random.nextLong(), random.nextLong()).toString() else null
        version.clearBindings()
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, 1_000L + i)
        version.bindString(4, random.nextBytes(32).joinToString("") { "%02x".format(it) })
        version.bindLong(5, 0)
        version.executeInsert()
        media.clearBindings()
        media.bindString(1, mediaId)
        if (groupId != null) media.bindString(2, groupId) else media.bindNull(2)
        media.bindString(3, versionId)
        media.bindLong(4, Db.T0 - random.nextLong(15L * 365 * Db.DAY))
        media.bindString(5, if (gone) "none" else "phone_only")
        media.bindLong(6, if (member) CellFlags.GROUP_MEMBER.toLong() else if (cover) CellFlags.GROUP_COVER.toLong() else 0L)
        media.executeInsert()
        if (cover) {
          group.clearBindings()
          group.bindString(1, groupId!!)
          group.bindString(2, mediaId)
          group.executeInsert()
          lastGroup = groupId
          lastCover = mediaId
        }
        if (isHidden) {
          hidden.clearBindings()
          hidden.bindString(1, mediaId)
          hidden.executeInsert()
        }
        if (!gone && !member && !isHidden) cells++
      }
      check(lastCover != null)
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    return cells
  }

  private fun planOf(sql: String, vararg args: Any): String {
    val flat = sql.replace(Regex(":\\w+"), "?")
    // `IN (?)` ha un solo segnaposto: basta un valore.
    return db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $flat", arrayOf<Any?>(*args)).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")
  }

  /**
   * La riga P1: `cellContentKeys` su 100 mila media sotto 300 ms. Misurato in due modi: **ogni pagina** (la lettura che P6 fa a ogni
   * cursore, in qualunque punto della tabella) sta sotto 300 ms con un margine enorme; la **lettura intera** in una chiamata sola costa
   * di piu' (Robolectric: la lettura delle 100 mila righe dal cursore, ~3 us a riga, e' da sola ~300 ms) e qui ha solo un tetto largo.
   * I numeri finiscono nel report di P1.
   */
  @Test
  fun cellContentKeysSu100MilaMediaOgniPaginaStaSotto300Millisecondi() = runBlocking<Unit> {
    val expected = bulkInsert(100_000)
    // Riscaldamento (carica le classi e riempie la cache delle pagine del database, come un'app che ha gia' letto la Libreria).
    assertThat(catalog.cellContentKeys(0, 2_000).cells).isNotEmpty()

    val whole = (1..3).map {
      var cells = 0
      val ms = measureNanoTime { cells = catalog.cellContentKeys(0, 100_000).cells.size } / 1_000_000.0
      assertThat(cells).isEqualTo(expected)
      ms
    }
    val perPage = ArrayList<Double>()
    var total = 0
    var after = 0L
    val traversal = measureNanoTime {
      while (true) {
        var page: CellContentPage? = null
        perPage += measureNanoTime { page = catalog.cellContentKeys(after, MediaCatalog.CELL_KEYS_PAGE) } / 1_000_000.0
        total += page!!.cells.size
        after = page!!.next ?: break
      }
    } / 1_000_000.0
    assertThat(total).isEqualTo(expected)
    println(
      "cellContentKeys su 100000 media ($expected celle): lettura intera in una chiamata ${whole.map { "%.0f".format(it) }} ms; " +
        "a pagine da ${MediaCatalog.CELL_KEYS_PAGE}: ${perPage.size} pagine, la piu' lenta %.0f ms, la mediana %.0f ms, tutte %.0f ms"
          .format(perPage.max(), perPage.sorted()[perPage.size / 2], traversal),
    )
    assertThat(perPage.max()).isLessThan(300.0)
    // Tetto largo per la lettura intera (la prima volta e' piu' lenta: compilazione al volo): non e' la misura della riga P1.
    assertThat(whole.min()).isLessThan(2_000.0)
  }

  /** Nessun ordinamento in memoria: `ORDER BY m.rowid` e' l'ordine dell'albero di `media`. Senza statistiche e con. */
  @Test
  fun lePagineNonOrdinanoInMemoria() = runBlocking<Unit> {
    bulkInsert(2_000)
    val queries = listOf(
      planOf(CellContentDao.SEQ_BOUND_SQL, 0, 500),
      planOf(CellContentDao.CELL_VERSIONS_SQL, 0, 500),
      planOf(CellContentDao.CELL_VERSIONS_OF_SQL, "a"),
      planOf(CellContentDao.MEMBER_VERSIONS_SQL, "a"),
    )
    db.openHelper.writableDatabase.execSQL("ANALYZE")
    val analyzed = listOf(
      planOf(CellContentDao.SEQ_BOUND_SQL, 0, 500),
      planOf(CellContentDao.CELL_VERSIONS_SQL, 0, 500),
      planOf(CellContentDao.CELL_VERSIONS_OF_SQL, "a"),
      planOf(CellContentDao.MEMBER_VERSIONS_SQL, "a"),
    )
    println("Piani delle chiavi delle celle: $queries / dopo ANALYZE: $analyzed")
    (queries + analyzed).forEach { plan -> assertThat(plan).doesNotContain("TEMP B-TREE") }
    // La lettura delle celle parte dai media (per rowid) e scende per `media_id`: mai una scansione di tutte le versioni.
    listOf(queries[1], analyzed[1]).forEach { plan ->
      assertThat(plan).contains("SEARCH m USING INTEGER PRIMARY KEY")
      assertThat(plan).contains("SEARCH v USING")
      assertThat(plan).doesNotContain("SCAN v")
    }
  }
}
