package dev.pampa.mycloudgallery.core.faces.store

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures.AUTHOR_BIG
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures.AUTHOR_SMALL
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures.bigEndian
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures.float32
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures.indexOf
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures.littleEndian
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, D14, C39): **nessun vettore, nessun landmark e nessun nome in chiaro nel file**. Si seminano valori riconoscibili (nomi
 * lunghi e unici, vettori diversi per ogni volto), si legge ogni byte dei file del database (`faces-<G>.db`, `-wal` con le scritture non
 * ancora riportate nel file principale, `-shm`) e si cerca ogni valore seminato in tutte le forme in cui potrebbe comparire: UTF-8, UTF-16
 * little e big endian per i nomi; float16 big e little endian e float32 per i vettori; i landmark come numeri. Prima di fidarsi dell'esito
 * "non c'e'" si prova lo stesso cercatore su un database **in chiaro** con gli stessi valori: li deve trovare tutti (altrimenti non
 * proverebbe niente). Poi: la chiave distrutta rende i dati illeggibili, un blocco spostato o alterato non si apre, una chiave sbagliata
 * non legge niente e non lancia.
 */
@RunWith(AndroidJUnit4::class)
class FacesStoreEncryptionTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 9 })

  // Nomi inventati, lunghi e unici (nessuna persona vera).
  private val names = listOf("Nome-Seminato-Aurora-7731", "Nome-Seminato-Bacchus-4410", "Nome-Seminato-Cirrus-9085")

  private fun forms(name: String): List<ByteArray> = listOf(
    name.toByteArray(Charsets.UTF_8),
    name.toByteArray(Charsets.UTF_16LE),
    name.toByteArray(Charsets.UTF_16BE),
  )

  /** Ogni forma in cui [halves] puo' stare in un file: float16 big/little endian e float32 big/little endian, a finestre da 8 (4) valori. */
  private fun vectorForms(halves: ShortArray): List<ByteArray> = buildList {
    for (from in listOf(0, 40, 120)) {
      add(bigEndian(halves, from, 8))
      add(littleEndian(halves, from, 8))
      add(float32(halves, from, 4, bigEndian = true))
      add(float32(halves, from, 4, bigEndian = false))
    }
  }

  private fun landmarkForms(landmarks: ShortArray): List<ByteArray> = listOf(bigEndian(landmarks, 0, 10), littleEndian(landmarks, 0, 10))

  private fun person(n: Int) = PersonId.fromBytes(ByteArray(16) { (n * 16 + it).toByte() })

  /** Un archivio con tre persone con un nome e quindici volti (CORE e WEAK), tutto con valori seminati. */
  private suspend fun seeded(): Triple<FacesStore, List<ByteArray>, List<ByteArray>> {
    val store = world.ready(generation)
    val vectors = mutableListOf<ByteArray>()
    val landmarks = mutableListOf<ByteArray>()
    for (n in 1..5) {
      val faces = (0 until 3).map { i ->
        FaceFixtures.entry(i, if (i == 2) FaceTier.WEAK else FaceTier.CORE, seed = n * 10 + i)
      }
      faces.forEach {
        vectors += vectorForms(it.vector!!)
        landmarks += landmarkForms(it.landmarks)
      }
      store.archive.saveBody(FaceFixtures.body(FaceFixtures.key(n), faces = faces))
    }
    names.forEachIndexed { i, name ->
      store.archive.addPeople(listOf(PeopleRecord(PeopleEdit.Name(person(i + 1), name), atNas = 1_000L + i, author = AUTHOR_SMALL)))
    }
    return Triple(store, vectors, landmarks)
  }

  @Test
  fun nelFileNonCeNessunVettoreLandmarkONomeInChiaro() = runBlocking<Unit> {
    val (store, vectors, landmarks) = seeded()

    // Con il database ancora aperto: il file principale e il -wal con le scritture appena fatte.
    val live = world.bytesOf(generation)
    assertThat(live).isNotEmpty()
    assertThat(world.filesOf(generation).any { it.name.endsWith("-wal") }).isTrue()
    assertAbsent(live, names.flatMap(::forms), "nome")
    assertAbsent(live, vectors, "vettore")
    assertAbsent(live, landmarks, "landmark")

    // Dopo la chiusura (il -wal riportato nel file principale).
    store.close()
    val closed = world.bytesOf(generation)
    assertThat(closed.sumOf { it.size }).isGreaterThan(8_192)
    assertAbsent(closed, names.flatMap(::forms), "nome")
    assertAbsent(closed, vectors, "vettore")
    assertAbsent(closed, landmarks, "landmark")
  }

  @Test
  fun ilCercatoreTrovaInveceTuttiIValoriSeInChiaro() = runBlocking<Unit> {
    // Il controllo positivo: lo stesso database con gli stessi valori in chiaro (un archivio senza cifratura) li mostra tutti, in ogni forma.
    val (store, vectors, landmarks) = seeded()
    store.close()
    val plain = File(folder.root, "chiaro.db")
    SQLiteDatabase.openOrCreateDatabase(plain, null).use { db ->
      db.execSQL("CREATE TABLE nomi (utf8 TEXT, utf16le BLOB, utf16be BLOB)")
      names.forEach { db.execSQL("INSERT INTO nomi VALUES (?, ?, ?)", arrayOf(it, it.toByteArray(Charsets.UTF_16LE), it.toByteArray(Charsets.UTF_16BE))) }
      db.execSQL("CREATE TABLE volti (be16 BLOB, le16 BLOB, f32be BLOB, f32le BLOB, lm_be BLOB, lm_le BLOB)")
      for (n in 1..5) for (i in 0 until 3) {
        val seed = n * 10 + i
        val v = FaceFixtures.vector(seed)
        val lm = FaceFixtures.landmarks(seed)
        db.execSQL(
          "INSERT INTO volti VALUES (?, ?, ?, ?, ?, ?)",
          arrayOf<Any>(
            bigEndian(v, 0, v.size), littleEndian(v, 0, v.size), float32(v, 0, v.size, bigEndian = true), float32(v, 0, v.size, bigEndian = false),
            bigEndian(lm, 0, 10), littleEndian(lm, 0, 10),
          ),
        )
      }
    }
    val bytes = plain.readBytes()
    for (needle in names.flatMap(::forms) + vectors + landmarks) {
      assertWithMessage("il cercatore non trova un valore che e' in chiaro: non prova niente").that(indexOf(bytes, needle)).isAtLeast(0)
    }
  }

  @Test
  fun conLaChiaveDistruttaIDatiSonoIllegibiliAncheSeIlFileResta() = runBlocking<Unit> {
    val (store, _, _) = seeded()
    val g = generation
    val key = FaceFixtures.key(1)
    assertThat(store.archive.winners(listOf(key))).hasSize(1)
    store.close()
    val before = world.mainFile(g).readBytes()

    // La chiave dei volti si distrugge e il file resta (un blocco della flash, un file non tolto al primo colpo).
    assertThat(world.keys.destroy(g)).isTrue()
    assertThat(world.mainFile(g).readBytes()).isEqualTo(before)

    // Chiunque apra quel file con una chiave nuova (o con la stessa chiave ricreata) non legge un solo valore e non crolla.
    assertThat(world.keys.create(g)).isTrue()
    val newKey = world.keys.open(g)!!
    val db = dev.pampa.mycloudgallery.core.database.faces.FacesDatabase.build(world.context, world.mainFile(g))
    val intruder = FacesStore(g, db, newKey)
    assertThat(intruder.archive.winners((1..5).map { FaceFixtures.key(it) })).isEmpty()
    assertThat(intruder.archive.groupFaces()).isEmpty()
    assertThat(intruder.archive.people()).isEmpty()
    // Le righe in chiaro (riquadri, livelli) restano, ma senza vettori e senza nomi non servono a ricostruire niente.
    assertThat(intruder.counts().faces).isEqualTo(15)
    intruder.close()
  }

  @Test
  fun unBloccoSpostatoOAlteratoONonDellaSuaRigaNonSiApreEIlResto() = runBlocking<Unit> {
    val store = world.ready(generation)
    val a = FaceFixtures.body(FaceFixtures.key(1), faces = listOf(FaceFixtures.entry(0, FaceTier.CORE, seed = 1)))
    val b = FaceFixtures.body(FaceFixtures.key(2), faces = listOf(FaceFixtures.entry(0, FaceTier.CORE, seed = 2)))
    val c = FaceFixtures.body(FaceFixtures.key(3), faces = listOf(FaceFixtures.entry(0, FaceTier.CORE, seed = 3)))
    listOf(a, b, c).forEach { store.archive.saveBody(it) }
    val keys = listOf(a, b, c).map { it.contentKey }
    assertThat(store.archive.winners(keys)).hasSize(3)
    val sql = store.database.openHelper.writableDatabase

    // Il blocco di A messo nella riga di B (e viceversa): il `aad` lega il blocco alla sua riga, nessuno dei due si apre piu'.
    val secretOf = { key: String -> sql.query("SELECT secret FROM face_body WHERE content_key = ?", arrayOf(key)).use { it.moveToFirst(); it.getBlob(0) } }
    val secretA = secretOf(a.contentKey.hex)
    val secretB = secretOf(b.contentKey.hex)
    sql.execSQL("UPDATE face_body SET secret = ? WHERE content_key = ?", arrayOf<Any>(secretB, a.contentKey.hex))
    sql.execSQL("UPDATE face_body SET secret = ? WHERE content_key = ?", arrayOf<Any>(secretA, b.contentKey.hex))
    assertThat(store.archive.winners(keys).keys).containsExactly(c.contentKey)

    // Rimessi a posto tornano a leggersi.
    sql.execSQL("UPDATE face_body SET secret = ? WHERE content_key = ?", arrayOf<Any>(secretA, a.contentKey.hex))
    sql.execSQL("UPDATE face_body SET secret = ? WHERE content_key = ?", arrayOf<Any>(secretB, b.contentKey.hex))
    assertThat(store.archive.winners(keys)).hasSize(3)

    // Un byte alterato nel blocco di C, la ricetta alterata nella riga di B, il numero di volti alterato in quella di A.
    val tampered = secretOf(c.contentKey.hex).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    sql.execSQL("UPDATE face_body SET secret = ? WHERE content_key = ?", arrayOf<Any>(tampered, c.contentKey.hex))
    sql.execSQL("UPDATE face_body SET recipe = 9 WHERE content_key = ?", arrayOf<Any>(b.contentKey.hex))
    sql.execSQL("UPDATE face_body SET face_count = 2 WHERE content_key = ?", arrayOf<Any>(a.contentKey.hex))
    assertThat(store.archive.winners(keys)).isEmpty()
    assertThat(store.archive.groupFaces()).isEmpty()
    // "Gia' fatto" guarda le colonne in chiaro, non il blocco: e' la ricetta scritta nella riga; il contenuto si rifa' quando serve (e si riscrive).
    store.archive.saveBody(a)
    assertThat(store.archive.winners(listOf(a.contentKey))).hasSize(1)
    store.close()
  }

  @Test
  fun ilNomeDiUnaPersonaNonSiSpostaInUnAltraVoce() = runBlocking<Unit> {
    val store = world.ready(generation)
    val p1 = person(1)
    val p2 = person(2)
    store.archive.addPeople(
      listOf(
        PeopleRecord(PeopleEdit.Name(p1, names[0]), atNas = 10, author = AUTHOR_SMALL),
        PeopleRecord(PeopleEdit.Name(p2, names[1]), atNas = 10, author = AUTHOR_BIG),
      ),
    )
    assertThat(store.archive.people()).hasSize(2)
    val sql = store.database.openHelper.writableDatabase
    // Il nome di p1 messo nella riga di p2: non si apre (l'`aad` ha il soggetto), la riga si salta; l'altra resta.
    val sealed1 = sql.query("SELECT sealed_value FROM people_record WHERE subject = ?", arrayOf(p1.base32())).use { it.moveToFirst(); it.getBlob(0) }
    sql.execSQL("UPDATE people_record SET sealed_value = ? WHERE subject = ?", arrayOf<Any>(sealed1, p2.base32()))
    val kept = store.archive.people().values.map { it.edit }
    assertThat(kept).containsExactly(PeopleEdit.Name(p1, names[0]))
    // Un nome scritto in chiaro nella colonna `value` non lo abbiamo scritto noi: non vale.
    sql.execSQL("UPDATE people_record SET value = ?, sealed_value = NULL WHERE subject = ?", arrayOf<Any>(names[1], p2.base32()))
    assertThat(store.archive.people().values.map { it.edit }).containsExactly(PeopleEdit.Name(p1, names[0]))
    // Una voce nuova per quella chiave la rimette a posto.
    store.archive.addPeople(listOf(PeopleRecord(PeopleEdit.Name(p2, names[2]), atNas = 20, author = AUTHOR_SMALL)))
    assertThat(store.archive.people().values.map { it.edit }).containsExactly(PeopleEdit.Name(p1, names[0]), PeopleEdit.Name(p2, names[2]))
    store.close()
  }
}

private fun assertAbsent(files: List<ByteArray>, needles: List<ByteArray>, what: String) {
  for (needle in needles) {
    for (file in files) {
      assertWithMessage("un $what seminato e' in chiaro nel file").that(indexOf(file, needle)).isEqualTo(-1)
    }
  }
}
