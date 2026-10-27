package dev.pampa.mycloudgallery.core.database.faces

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.faces.dao.FaceBodyDao
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceBodyEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesOutboxEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupAssignmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupExemplarEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupPersonEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupStateEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PeopleRecordEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * I DAO del database dei volti (Fase 8, P6; `docs/modello-dati.md` §15), senza la cifratura (che e' di `store/` in core-faces): i blocchi
 * sigillati qui sono byte qualunque. Il vincitore fra gli autori si rifa' nella stessa transazione che scrive il corpo; il "non supportato"
 * non si abbassa; l'istantanea si sostituisce intera o per niente; l'outbox rimette in coda con un id nuovo. E le query per chiave su
 * **20.000 volti** in meno di 50 ms, con i piani che dicono che usano gli indici e non ordinano in memoria.
 */
@RunWith(AndroidJUnit4::class)
class FacesDaoTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var db: FacesDatabase

  @Before
  fun setUp() {
    db = FacesDatabase.build(context, File(folder.root, "faces-dao.db")).also { it.openNow() }
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun key(n: Int): String =
    MessageDigest.getInstance("SHA-256").digest("mcg-dao-$n".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun body(key: String, author: String, recipe: Int, space: String = "sface_2021dec_v1", faces: Int = 1, digest: Long = recipe * 10L) =
    FaceBodyEntity(key, author, false, space, "yunet_2023mar", recipe, "analysis", digest, faces, ByteArray(40) { it.toByte() })

  private fun face(key: String, author: String, index: Int, tier: String = "core") =
    FaceEntity(key, author, index, 0, 100 * index, 10, 100 * index + 50, 60, 200, tier, 0, 120, 0, 100)

  private suspend fun save(key: String, author: String, recipe: Int, tiers: List<String> = listOf("core")) =
    db.bodyDao().replaceBody(body(key, author, recipe, faces = tiers.size), tiers.mapIndexed { i, t -> face(key, author, i, t) })

  private fun winnersOf(key: String): List<String> =
    db.openHelper.readableDatabase.query("SELECT author FROM face_body WHERE content_key = ? AND is_winner = 1", arrayOf(key)).use { c ->
      buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

  // --- il vincitore e le sostituzioni --------------------------------------------------------------------------------------------

  @Test
  fun ilVincitoreEUnoSoloEFollaRegolaDelContratto() = runBlocking<Unit> {
    val k = key(1)
    save(k, "m", 1)
    assertThat(winnersOf(k)).containsExactly("m")
    save(k, "b", 1) // a pari ricetta l'autore minore
    assertThat(winnersOf(k)).containsExactly("b")
    save(k, "z", 2) // la ricetta piu' alta
    assertThat(winnersOf(k)).containsExactly("z")
    save(k, "z", 1) // lo stesso autore sostituisce: ora tutte a ricetta 1, vince "b"
    assertThat(winnersOf(k)).containsExactly("b")
    save(k, "b", 3)
    assertThat(winnersOf(k)).containsExactly("b")
    assertThat(db.bodyDao().bodiesOf(k).map { it.author }).containsExactly("b", "m", "z").inOrder()
    // Idempotente: lo stesso salvataggio due volte non cambia niente.
    save(k, "b", 3)
    assertThat(db.bodyDao().bodyCount()).isEqualTo(3)
    assertThat(db.bodyDao().winners(listOf(k, key(2))).map { it.author }).containsExactly("b")
  }

  @Test
  fun sostituireUnCorpoToglieIVecchiVoltiENonLasciaOrfani() = runBlocking<Unit> {
    val k = key(1)
    save(k, "a", 1, listOf("core", "weak", "none"))
    assertThat(db.bodyDao().faceCount()).isEqualTo(3)
    save(k, "a", 2, listOf("core"))
    assertThat(db.bodyDao().faceCount()).isEqualTo(1)
    save(k, "a", 2, emptyList()) // "analizzato, nessun volto"
    assertThat(db.bodyDao().faceCount()).isEqualTo(0)
    assertThat(db.bodyDao().bodyCount()).isEqualTo(1)
    db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
  }

  @Test
  fun unaScritturaCheFallisceANettaNonLasciaUnCorpoSenzaVolti() = runBlocking<Unit> {
    val k = key(1)
    save(k, "a", 1, listOf("core", "core"))
    // I volti sono per un altro corpo (la chiave esterna li rifiuta): la transazione torna indietro e il corpo di prima c'e' intero.
    val result = runCatching {
      db.bodyDao().replaceBody(body(k, "a", 2, faces = 1), listOf(face(k, "altro", 0)))
    }
    assertThat(result.isFailure).isTrue()
    assertThat(db.bodyDao().bodiesOf(k).single().recipe).isEqualTo(1)
    assertThat(db.bodyDao().faceCount()).isEqualTo(2)
  }

  @Test
  fun giaFattoENonSupportatoEiNonRiconoscibili() = runBlocking<Unit> {
    save(key(1), "a", 2, listOf("core", "none", "none"))
    save(key(2), "a", 1, listOf("core"))
    save(key(2), "b", 3, listOf("none")) // vincente: un solo non riconoscibile
    db.bodyDao().markUnsupported(key(3), 4)
    db.bodyDao().markUnsupported(key(3), 2) // non abbassa
    val keys = (1..4).map(::key)
    assertThat(db.bodyDao().analyzedKeys(keys, 2)).containsExactly(key(1), key(2))
    assertThat(db.bodyDao().analyzedKeys(keys, 3)).containsExactly(key(2))
    assertThat(db.bodyDao().unsupportedKeys(keys, 4)).containsExactly(key(3))
    assertThat(db.bodyDao().unsupportedKeys(keys, 5)).isEmpty()
    val none = db.bodyDao().countByTier(keys, "none").associate { it.contentKey to it.count }
    assertThat(none).containsExactly(key(1), 2, key(2), 1)
    assertThat(db.bodyDao().unsupportedRecipe(key(3))).isEqualTo(4)
  }

  @Test
  fun ingressoDelRaggruppamentoSoloVincentiNelloSpazioCorrenteInOrdineDiChiave() = runBlocking<Unit> {
    val keys = (1..6).map(::key)
    save(keys[0], "a", 1, listOf("core", "weak", "none"))
    save(keys[1], "a", 1, listOf("core"))
    save(keys[1], "b", 1, listOf("core", "core")) // perde con "a" a pari ricetta
    db.bodyDao().replaceBody(body(keys[2], "a", 1, space = "spazio_futuro"), listOf(face(keys[2], "a", 0)))
    val winners = db.bodyDao().winnersInSpace("sface_2021dec_v1")
    // I vincenti sono due (la chiave 3 e' di un altro spazio), nell'ordine delle chiavi, che sono impronte: non l'ordine di scrittura.
    val inOrder = listOf(keys[0], keys[1]).sorted()
    assertThat(winners.map { it.contentKey }).containsExactlyElementsIn(inOrder).inOrder()
    val faces = db.bodyDao().searchableFaces("sface_2021dec_v1", "none")
    val expected = inOrder.flatMap { k -> if (k == keys[0]) listOf(k to 0, k to 1) else listOf(k to 0) }
    assertThat(faces.map { it.contentKey to it.index }).containsExactlyElementsIn(expected).inOrder()
  }

  // --- l'istantanea ----------------------------------------------------------------------------------------------------------------

  private fun person(n: Int) = "%08d-0000-4000-8000-000000000000".format(n)

  private fun assignment(k: String, index: Int, personId: String) =
    GroupAssignmentEntity(k, 0, index, 1, null, null, 1, 2, 3, 4, personId, 0.5, "auto")

  @Test
  fun lIstantaneaSiSostituisceInteraOPerNiente() = runBlocking<Unit> {
    val dao = db.groupingDao()
    val first = GroupStateEntity(1, 1, 0, 0)
    dao.replaceAll(
      first,
      listOf(GroupPersonEntity(person(1), "active", null, 1, 1), GroupPersonEntity(person(2), "active", null, 1, 1)),
      listOf(GroupExemplarEntity(person(1), 0, key(1), 0, 0, 1)),
      listOf(assignment(key(1), 0, person(1)), assignment(key(1), 1, person(2))),
    )
    assertThat(dao.persons()).hasSize(2)
    assertThat(dao.assignmentCount(person(1))).isEqualTo(1)
    // Una seconda istantanea con un'assegnazione a una persona che non c'e': la chiave esterna la rifiuta e la prima resta com'era.
    val failure = runCatching {
      dao.replaceAll(
        GroupStateEntity(1, 9, 0, 0),
        listOf(GroupPersonEntity(person(3), "active", null, 1, 9)),
        emptyList(),
        listOf(assignment(key(2), 0, person(4))),
      )
    }
    assertThat(failure.isFailure).isTrue()
    assertThat(dao.state()!!.seq).isEqualTo(1)
    assertThat(dao.persons().map { it.personId }).containsExactly(person(1), person(2))
    assertThat(dao.assignments()).hasSize(2)
    assertThat(dao.exemplars()).hasSize(1)
    // Una buona sostituisce tutto.
    dao.replaceAll(GroupStateEntity(1, 5, 2, 7), listOf(GroupPersonEntity(person(3), "merged", person(1), 1, 5)), emptyList(), emptyList())
    assertThat(dao.persons().map { it.personId }).containsExactly(person(3))
    assertThat(dao.state()!!.unassignedAtBirths).isEqualTo(7)
    assertThat(dao.assignments()).isEmpty()
  }

  // --- voci people, outbox ------------------------------------------------------------------------------------------------------

  @Test
  fun leVociPeopleStannoPerChiaveEIlNomeESoloUnBlocco() = runBlocking<Unit> {
    val dao = db.peopleDao()
    dao.upsertAll(
      listOf(
        PeopleRecordEntity(person(1), "name", null, byteArrayOf(9, 9, 9), 10, "dev-a"),
        PeopleRecordEntity(person(1), "hidden", "true", null, null, "dev-a"),
      ),
    )
    dao.upsertAll(listOf(PeopleRecordEntity(person(1), "name", null, byteArrayOf(7), 20, "dev-b")))
    assertThat(dao.count()).isEqualTo(2)
    val name = dao.get(person(1), "name")!!
    assertThat(name.sealedValue).isEqualTo(byteArrayOf(7))
    assertThat(name.value).isNull()
    assertThat(name.atNas).isEqualTo(20)
    assertThat(dao.get(person(1), "hidden")!!.atNas).isNull()
    assertThat(dao.get(person(2), "name")).isNull()
  }

  @Test
  fun lOutboxRimetteInCodaConUnIdNuovoESenzaDuplicati() = runBlocking<Unit> {
    val dao = db.outboxDao()
    val a = dao.stage(FacesOutboxEntity(kind = "faces", ref = "a"))
    val b = dao.stage(FacesOutboxEntity(kind = "faces", ref = "b"))
    val a2 = dao.stage(FacesOutboxEntity(kind = "faces", ref = "a"))
    assertThat(a2).isGreaterThan(b)
    assertThat(b).isGreaterThan(a)
    assertThat(dao.count()).isEqualTo(2)
    assertThat(dao.pending(10).map { it.ref }).containsExactly("b", "a").inOrder()
    dao.remove(listOf(a, b)) // la `a` letta all'inizio non c'e' piu': non toglie quella rimessa in coda
    assertThat(dao.pending(10).map { it.entryId }).containsExactly(a2)
    // Gli id non si riusano mai (AUTOINCREMENT), nemmeno dopo aver svuotato.
    dao.clear()
    assertThat(dao.stage(FacesOutboxEntity(kind = "faces", ref = "z"))).isGreaterThan(a2)
  }

  // --- 20.000 volti ------------------------------------------------------------------------------------------------------------

  private fun seedTwentyThousand(): List<String> {
    val keys = (0 until BODIES).map(::key)
    val sql = db.openHelper.writableDatabase
    sql.beginTransaction()
    try {
      val bodyInsert = sql.compileStatement("INSERT INTO face_body VALUES (?, ?, 1, 'sface_2021dec_v1', 'yunet_2023mar', 1, 'analysis', ?, ?, ?)")
      val faceInsert = sql.compileStatement("INSERT INTO face VALUES (?, ?, ?, 0, ?, 10, ?, 60, 200, ?, 0, 120, 0, 100)")
      for (n in 0 until BODIES) {
        val count = 1 + n % 5
        bodyInsert.bindString(1, keys[n])
        bodyInsert.bindString(2, "dev-a")
        bodyInsert.bindLong(3, n.toLong())
        bodyInsert.bindLong(4, count.toLong())
        bodyInsert.bindBlob(5, ByteArray(count * 277 + 30) { it.toByte() })
        bodyInsert.executeInsert()
        for (i in 0 until count) {
          faceInsert.bindString(1, keys[n])
          faceInsert.bindString(2, "dev-a")
          faceInsert.bindLong(3, i.toLong())
          faceInsert.bindLong(4, 100L * i)
          faceInsert.bindLong(5, 100L * i + 50)
          faceInsert.bindString(6, if (i > 0 && (n + i) % 10 == 0) "none" else if (i > 0 && (n + i) % 4 == 0) "weak" else "core")
          faceInsert.executeInsert()
        }
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    return keys
  }

  private suspend fun median(runs: Int = 5, block: suspend () -> Unit): Long {
    repeat(2) { block() }
    val times = (1..runs).map {
      val started = System.nanoTime()
      block()
      (System.nanoTime() - started) / 1_000_000
    }.sorted()
    return times[times.size / 2]
  }

  @Test
  fun leQueryPerChiaveSuVentiMilaVoltiStannoSotto50MsEUsanoGliIndici() = runBlocking<Unit> {
    val keys = seedTwentyThousand()
    val dao = db.bodyDao()
    assertThat(dao.bodyCount()).isEqualTo(BODIES)
    assertThat(dao.faceCount()).isEqualTo(20_100)
    val unit = keys.subList(2_000, 2_000 + FaceBodyDao.IN_LIMIT.coerceAtMost(200))

    val timings = linkedMapOf(
      "vincitori di 200 chiavi" to median { assertThat(dao.winners(unit)).hasSize(200) },
      "volti di 200 chiavi" to median { assertThat(dao.facesOf(unit)).isNotEmpty() },
      "gia' fatto di 200 chiavi" to median { assertThat(dao.analyzedKeys(unit, 1)).hasSize(200) },
      "non riconoscibili di 200 chiavi" to median { dao.countByTier(unit, "none") },
      "vincitori di 500 chiavi" to median { assertThat(dao.winners(keys.subList(0, 500))).hasSize(500) },
      "quanti volti" to median { dao.faceCount() },
    )
    for ((name, ms) in timings) {
      println("[prestazioni database dei volti] $name: $ms ms")
      assertWithMessage("$name: $ms ms").that(ms).isLessThan(50)
    }
    // Le due letture intere per il raggruppamento (6.700 blocchi sigillati da ~0,9 KB e 18.700 righe di volti): leggono 6 MB di byte e
    // costano di piu'; per questo l'archivio le fa una volta sola e poi tiene i volti in memoria (`GroupFaceCache`).
    val wholeBodies = median { assertThat(dao.winnersInSpace("sface_2021dec_v1")).hasSize(BODIES) }
    val wholeFaces = median { assertThat(dao.searchableFaces("sface_2021dec_v1", "none").size).isAtLeast(18_000) }
    println("[prestazioni database dei volti] lettura intera, corpi con i blocchi: $wholeBodies ms; volti con un vettore: $wholeFaces ms")
    assertWithMessage("corpi: $wholeBodies ms").that(wholeBodies).isLessThan(1_000)
    assertWithMessage("volti: $wholeFaces ms").that(wholeFaces).isLessThan(1_000)

    val sql = db.openHelper.readableDatabase
    fun plan(query: String): String = sql.query("EXPLAIN QUERY PLAN $query").use { c ->
      buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")
    for (analyzed in listOf(false, true)) {
      if (analyzed) sql.execSQL("ANALYZE")
      val checks = mapOf(
        "SELECT * FROM face_body WHERE is_winner = 1 AND content_key IN ('a', 'b') ORDER BY content_key" to "sqlite_autoindex_face_body",
        "SELECT * FROM face_body WHERE is_winner = 1 AND space = 'x' ORDER BY content_key" to "index_face_body_is_winner_space_content_key",
        "SELECT DISTINCT content_key FROM face_body WHERE content_key IN ('a', 'b') AND recipe >= 1" to "sqlite_autoindex_face_body",
        "SELECT * FROM face WHERE content_key IN ('a', 'b') ORDER BY content_key, author, idx" to "sqlite_autoindex_face",
        "SELECT f.content_key, COUNT(*) FROM face f JOIN face_body b ON b.content_key = f.content_key AND b.author = f.author " +
          "WHERE b.is_winner = 1 AND f.tier = 'none' AND f.content_key IN ('a', 'b') GROUP BY f.content_key ORDER BY f.content_key" to "sqlite_autoindex_face",
      )
      for ((query, index) in checks) {
        val text = plan(query)
        println("[piano analyzed=$analyzed] $query -> $text")
        assertWithMessage("analyzed=$analyzed, $query: $text").that(text).contains(index)
        assertWithMessage("analyzed=$analyzed, $query: $text").that(text).doesNotContain("TEMP B-TREE")
      }
    }
  }

  private companion object {
    const val BODIES = 6_700
  }
}
