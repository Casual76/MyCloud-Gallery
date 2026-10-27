package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, riga P6 del §4.4): **le query su 20.000 volti in meno di 50 ms** (JVM, Robolectric; il dispositivo si misura sul campo). Un
 * database cifrato vero con 6.700 contenuti e 20.100 volti (CORE, WEAK e NONE), 300 persone con le loro 20.000 assegnazioni e 1.000 voci
 * `people`. Ogni tempo e' la mediana di cinque giri dopo due di riscaldamento. Le letture che l'app fa a ogni schermata e a ogni unita'
 * (gia' fatto?, vincitori di un'unita', non riconoscibili, quanti volti) devono stare sotto i 50 ms **comprese** le decifrature; la lettura
 * intera per il raggruppamento (`groupFaces`, che decifra i vettori di tutti i 20.000 volti) e quella/scrittura dell'istantanea intera si
 * misurano con il loro tetto e il numero va in `docs/decisioni.md`.
 *
 * Il piano (`EXPLAIN QUERY PLAN`) dice che le query per chiave usano l'indice e che l'ordine del raggruppamento non ordina in memoria.
 */
@RunWith(AndroidJUnit4::class)
class FacesStorePerformanceTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 8 })
  private val world by lazy { StoreWorld(File(folder.root, "faces")) }

  private suspend fun median(runs: Int = 5, block: suspend () -> Unit): Long {
    repeat(2) { block() }
    val times = (1..runs).map {
      val started = System.nanoTime()
      block()
      (System.nanoTime() - started) / 1_000_000
    }.sorted()
    return times[times.size / 2]
  }

  private fun contentKey(n: Int): ContentKey =
    MessageDigest.getInstance("SHA-256").digest("mcg-prova-prestazioni-$n".toByteArray()).let { d -> ContentKey(d.joinToString("") { "%02x".format(it.toInt() and 0xff) }) }

  /** Il contenuto [n]: da uno a cinque volti (media 3), il primo CORE, qualcuno WEAK e qualcuno NONE. 6.700 contenuti = 20.100 volti. */
  private fun bodyOf(n: Int, keys: List<ContentKey>): FaceBody {
    val count = 1 + n % 5
    val faces = (0 until count).map { i ->
      val tier = when {
        i == 0 -> FaceTier.CORE
        (n + i) % 10 == 0 -> FaceTier.NONE
        (n + i) % 4 == 0 -> FaceTier.WEAK
        else -> FaceTier.CORE
      }
      FaceFixtures.entry(i, tier, seed = n * 4 + i)
    }
    return FaceFixtures.body(keys[n], author = if (n % 7 == 0) FaceFixtures.AUTHOR_BIG else FaceFixtures.AUTHOR_SMALL, faces = faces, digest = 5_000L + n)
  }

  @Test
  fun leQuerySuVentiMilaVoltiStannoSotto50Ms() = runBlocking<Unit> {
    val store = world.ready(generation)
    val archive = store.archive
    val keys = (0 until BODIES).map(::contentKey)
    val seedStarted = System.nanoTime()
    for (n in 0 until BODIES) archive.saveBody(bodyOf(n, keys))
    println("[prestazioni volti] scrittura di $BODIES contenuti: ${(System.nanoTime() - seedStarted) / 1_000_000} ms")
    val counts = store.counts()
    println("[prestazioni volti] contenuti ${counts.bodies}, volti ${counts.faces}")
    assertThat(counts.bodies).isEqualTo(BODIES)
    assertThat(counts.faces).isEqualTo(20_100)

    val unit = keys.subList(1_000, 1_200) // un'unita' da 200 media
    val results = linkedMapOf<String, Long>()

    results["gia' fatto (200 chiavi)"] = median { assertThat(archive.analyzed(unit, 1)).hasSize(200) }
    results["non riconoscibili (200 chiavi)"] = median { archive.unrecognizable(unit) }
    results["vincitori di un'unita' (200 chiavi, con la decifratura)"] = median { assertThat(archive.winners(unit)).hasSize(200) }
    results["vincitori di una schermata (30 chiavi)"] = median { assertThat(archive.winners(keys.subList(0, 30))).hasSize(30) }
    results["quanti corpi e volti"] = median { store.counts() }

    for ((name, ms) in results) {
      println("[prestazioni volti] $name: $ms ms")
      assertWithMessage("$name: $ms ms").that(ms).isLessThan(50)
    }

    // Il piano: per chiave si usa la chiave primaria, e l'ingresso del raggruppamento non ordina in memoria (l'indice dei vincitori).
    val sql = store.database.openHelper.readableDatabase
    fun plan(query: String): String = sql.query("EXPLAIN QUERY PLAN $query").use { c ->
      buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")
    val byKey = plan("SELECT * FROM face_body WHERE is_winner = 1 AND content_key IN ('a', 'b') ORDER BY content_key")
    val ordered = plan("SELECT * FROM face_body WHERE is_winner = 1 AND space = 'x' ORDER BY content_key")
    val faces = plan("SELECT * FROM face WHERE content_key IN ('a', 'b') ORDER BY content_key, author, idx")
    println("[prestazioni volti] piano per chiave: $byKey")
    println("[prestazioni volti] piano ingresso del raggruppamento: $ordered")
    println("[prestazioni volti] piano dei volti: $faces")
    assertThat(byKey).doesNotContain("SCAN face_body")
    assertThat(ordered).contains("index_face_body_is_winner_space_content_key")
    assertThat(ordered).doesNotContain("TEMP B-TREE")
    assertThat(faces).doesNotContain("SCAN face")
    assertThat(faces).doesNotContain("TEMP B-TREE")

    // La lettura intera per il raggruppamento: la prima volta (negozio appena aperto) legge e decifra i vettori di tutti i volti; poi li tiene
    // in memoria (`GroupFaceCache`) e rilegge solo i contenuti salvati nel frattempo.
    store.close()
    val cold = (1..3).map {
      val reopened = world.ready(generation)
      val started = System.nanoTime()
      assertThat(reopened.archive.groupFaces().size).isAtLeast(17_000)
      val ms = (System.nanoTime() - started) / 1_000_000
      reopened.close()
      ms
    }.sorted()[1]
    println("[prestazioni volti] groupFaces la prima volta (tutti i volti letti e decifrati): $cold ms")
    assertWithMessage("groupFaces a freddo: $cold ms").that(cold).isLessThan(GROUP_FACES_CEILING_MS)

    val warmStore = world.ready(generation)
    warmStore.archive.groupFaces()
    val warm = median { assertThat(warmStore.archive.groupFaces().size).isAtLeast(17_000) }
    println("[prestazioni volti] groupFaces dalla seconda volta (in memoria): $warm ms")
    assertWithMessage("groupFaces a caldo: $warm ms").that(warm).isLessThan(50)

    // Dopo un'unita' di lavoro (200 contenuti rifatti) si rileggono e si decifrano solo quelli. Si misura la lettura, non le scritture.
    val incrementalTimes = (1..5).map { round ->
      for (n in 3_000 until 3_200) warmStore.archive.saveBody(FaceFixtures.body(keys[n], faces = bodyOf(n, keys).faces, digest = 90_000L + round * 1_000 + n))
      val started = System.nanoTime()
      assertThat(warmStore.archive.groupFaces().size).isAtLeast(17_000)
      (System.nanoTime() - started) / 1_000_000
    }.sorted()
    val incremental = incrementalTimes[incrementalTimes.size / 2]
    println("[prestazioni volti] groupFaces dopo 200 contenuti salvati (solo la lettura): $incremental ms")
    assertWithMessage("groupFaces dopo un'unita': $incremental ms").that(incremental).isLessThan(50)
    warmStore.close()
  }

  @Test
  fun listantaneaDi20miladAssegnazioniEleVociPeopleSiLeggonoESiScrivonoInTempiUtili() = runBlocking<Unit> {
    val store = world.ready(generation)
    val archive = store.archive
    val persons = (0 until PERSONS).map { n -> PersonId.fromBytes(ByteArray(16) { i -> ((n shr (i % 2 * 8)) + i + 1).toByte() }) }.distinct()
    assertThat(persons).hasSize(PERSONS)
    val refs = (0 until ASSIGNMENTS).map { n -> FaceRef(contentKey(n / 3), 0, n % 3, 5_000L + n / 3) }
    val state = GroupingState(
      seq = 100,
      base = 90,
      persons = persons.mapIndexed { i, id ->
        id to GroupPerson(id, PersonState.ACTIVE, null, 1, 100, refs.slice(i * 10 until i * 10 + 16).distinct().take(16))
      }.toMap(),
      assignments = refs.mapIndexed { n, ref ->
        ref to GroupAssignment(
          ref,
          FaceLocator(ref.contentKey, 0, FaceBox(100 + ref.index * 700, 1_000, 600 + ref.index * 700, 6_000)),
          persons[n % PERSONS],
          0.5f,
          AssignOrigin.AUTO,
        )
      }.toMap(),
    )
    val write = median(runs = 3) { archive.saveGrouping(state) }
    val read = median { assertThat(archive.grouping().assignments).hasSize(ASSIGNMENTS) }
    println("[prestazioni volti] istantanea di $PERSONS persone e $ASSIGNMENTS assegnazioni: scrittura $write ms, lettura $read ms")
    assertWithMessage("scrittura dell'istantanea: $write ms").that(write).isLessThan(SNAPSHOT_CEILING_MS)
    assertWithMessage("lettura dell'istantanea: $read ms").that(read).isLessThan(SNAPSHOT_CEILING_MS)

    val records = (0 until 1_000).map { n ->
      PeopleRecord(PeopleEdit.Is(FaceLocator(contentKey(n), 0, FaceBox(100, 1_000, 600, 6_000)), persons[n % PERSONS]), 1_000L + n, FaceFixtures.AUTHOR_SMALL)
    } + persons.take(100).mapIndexed { n, id -> PeopleRecord(PeopleEdit.Name(id, "Nome-Di-Prova-$n"), 2_000L + n, FaceFixtures.AUTHOR_SMALL) }
    val addMs = median(runs = 3) { archive.addPeople(records) }
    val peopleMs = median { assertThat(archive.people()).hasSize(records.size) }
    println("[prestazioni volti] ${records.size} voci people: unione ${addMs} ms, lettura $peopleMs ms")
    assertWithMessage("voci people: $peopleMs ms").that(peopleMs).isLessThan(50)
    store.close()
  }

  private companion object {
    const val BODIES = 6_700
    const val PERSONS = 300
    const val ASSIGNMENTS = 20_000

    /** Tetti larghi (JVM, Robolectric, macchina condivisa con altri agenti): quel che conta e' il numero misurato e registrato. */
    const val GROUP_FACES_CEILING_MS = 1_500L
    const val SNAPSHOT_CEILING_MS = 5_000L
  }
}
