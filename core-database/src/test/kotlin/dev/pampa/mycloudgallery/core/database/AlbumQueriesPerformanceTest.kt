package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.dao.AlbumQueries
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le query degli album su una Libreria da **100 mila media** (Fase 7, P1): i candidati per giorno, luogo e dispositivo girano in meno di
 * 50 ms senza `USE TEMP B-TREE` (`EXPLAIN QUERY PLAN`, senza statistiche come sul telefono e con), danno lo stesso risultato di un calcolo
 * fatto a mano sui dati, e i doppioni per parte in meno di 200 ms. Il tempo e' la mediana di cinque giri dopo due di riscaldamento:
 * su JVM (Robolectric); il dispositivo si misura a M2.
 */
@RunWith(AndroidJUnit4::class)
class AlbumQueriesPerformanceTest : CatalogTestBase() {

  private val albums get() = catalog.albums

  private class Scenario(
    val name: String,
    val query: CandidateQuery,
    val lead: AlbumQueries.Lead,
    val placeId: Int? = null,
    val index: String,
    val matches: (BigLibrary, Int) -> Boolean,
  )

  private fun plan(sql: String, args: Array<Any?>): String =
    db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
      buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")

  private fun expected(lib: BigLibrary, matches: (BigLibrary, Int) -> Boolean): List<String> =
    (0 until lib.count).filter { lib.visible[it] && matches(lib, it) }
      .sortedWith(compareBy({ lib.day[it] }, { lib.time[it] }, { lib.ids[it] })).map { lib.ids[it]!! }

  private suspend fun median(runs: Int = 5, block: suspend () -> Unit): Long {
    repeat(2) { block() }
    val times = (1..runs).map {
      val started = System.nanoTime()
      block()
      (System.nanoTime() - started) / 1_000_000
    }.sorted()
    return times[times.size / 2]
  }

  private val scenarios = listOf(
    Scenario("sette giorni", CandidateQuery(days = 20240310..20240316), AlbumQueries.Lead.DAY, index = "index_media_local_day_sort_time_media_id") { l, i ->
      l.day[i] in 20240310..20240316
    },
    Scenario("un giorno", CandidateQuery(days = 20240601..20240601), AlbumQueries.Lead.DAY, index = "index_media_local_day_sort_time_media_id") { l, i ->
      l.day[i] == 20240601
    },
    Scenario("tutta la Libreria", CandidateQuery(), AlbumQueries.Lead.DAY, index = "index_media_local_day_sort_time_media_id") { _, _ -> true },
    Scenario(
      "tre mesi, area privata, con GPS",
      CandidateQuery(days = 20240101..20240331, sources = setOf(CandidateSource.MY_PRIVATE), withGpsOnly = true),
      AlbumQueries.Lead.DAY,
      index = "index_media_local_day_sort_time_media_id",
    ) { l, i -> l.day[i] in 20240101..20240331 && l.source[i] == 1 && l.hasGps[i] },
    Scenario("un luogo", CandidateQuery(placeIds = setOf(1_010)), AlbumQueries.Lead.PLACE, placeId = 1_010, index = "index_media_place_id_local_day_sort_time_media_id") { l, i ->
      l.place[i] == 1_010
    },
    Scenario(
      "un luogo in un mese",
      CandidateQuery(placeIds = setOf(1_010), days = 20240101..20240630),
      AlbumQueries.Lead.PLACE,
      placeId = 1_010,
      index = "index_media_place_id_local_day_sort_time_media_id",
    ) { l, i -> l.place[i] == 1_010 && l.day[i] in 20240101..20240630 },
    Scenario(
      "il telefono in trenta giorni",
      CandidateQuery(originDevice = OriginDevice.PHONE, days = 20240301..20240330),
      AlbumQueries.Lead.DEVICE,
      index = "index_media_origin_device_local_day_sort_time_media_id",
    ) { l, i -> l.device[i] == OriginDevice.PHONE && l.day[i] in 20240301..20240330 },
    Scenario(
      "il tablet in tutta la Libreria",
      CandidateQuery(originDevice = OriginDevice.backup("tab")),
      AlbumQueries.Lead.DEVICE,
      index = "index_media_origin_device_local_day_sort_time_media_id",
    ) { l, i -> l.device[i] == OriginDevice.backup("tab") },
  )

  @Test
  fun iCandidatiPerGiornoLuogoEDispositivoSuCentoMilaMediaSonoVelociESenzaOrdinareInMemoria() = runBlocking<Unit> {
    val lib = BigLibrary(100_000)
    lib.insertInto(db.openHelper.writableDatabase)
    val me = Db.ME.value

    for (analyzed in listOf(false, true)) {
      if (analyzed) db.openHelper.writableDatabase.execSQL("ANALYZE")
      for (s in scenarios) {
        val first = AlbumQueries.candidateSql(s.query, s.lead, s.placeId, s.query.days?.first ?: 0, s.query.days?.last ?: AlbumQueries.LAST_DAY, me, null, 501)
        val deep = AlbumQueries.candidateSql(
          s.query, s.lead, s.placeId, s.query.days?.first ?: 0, s.query.days?.last ?: AlbumQueries.LAST_DAY, me, CandidateKey(CandidateKey.NO_PLACE, 20240315, 1L, "x"), 501,
        )
        for ((label, sql) in listOf("prima pagina" to first, "pagina con cursore" to deep)) {
          val text = plan(sql.first, sql.second)
          println("[${if (analyzed) "ANALYZE" else "senza statistiche"}] ${s.name}, $label: $text")
          assertWithMessage("${s.name}, $label, analyzed=$analyzed: $text").that(text).contains(s.index)
          assertWithMessage("${s.name}, $label, analyzed=$analyzed: $text").that(text).doesNotContain("TEMP B-TREE")
        }
      }
    }

    for (s in scenarios) {
      val want = expected(lib, s.matches)
      // Il risultato e' quello atteso, a pagine.
      val got = ArrayList<String>()
      var after: CandidateKey? = null
      do {
        val page = albums.candidates(s.query, after, 500)
        got += page.items.map { it.media.value }
        after = page.next
      } while (after != null)
      assertWithMessage(s.name).that(got.size).isEqualTo(want.size)
      assertWithMessage(s.name).that(got == want).isTrue()
      // Il tempo di una pagina da 500 (o di tutto, se ce n'e' meno).
      val millis = median { albums.candidates(s.query, null, 500) }
      println("Candidati, ${s.name}: ${want.size} in tutto, una pagina da 500 in $millis ms")
      assertWithMessage("${s.name}: $millis ms").that(millis).isLessThan(50)
      if (want.size > 500) {
        val second = albums.candidates(s.query, albums.candidates(s.query, null, 500).next, 500)
        val deepMillis = median { albums.candidates(s.query, albums.candidates(s.query, null, 500).next, 500) }
        println("Candidati, ${s.name}: la seconda pagina in $deepMillis ms (con la prima: due pagine)")
        assertThat(second.items.map { it.media.value }).containsExactlyElementsIn(want.subList(500, minOf(1_000, want.size))).inOrder()
        assertWithMessage("${s.name}, seconda pagina: $deepMillis ms").that(deepMillis).isLessThan(100)
      }
    }
    val visible = (0 until lib.count).count { lib.visible[it] }
    assertThat(albums.visibleCount()).isEqualTo(visible)
    println("visibleCount: ${median { albums.visibleCount() }} ms")
  }

  @Test
  fun lePosizioniPerParteDeiDoppioniSuCentoMilaMediaSonoSottoI200Millisecondi() = runBlocking<Unit> {
    val lib = BigLibrary(100_000)
    lib.insertInto(db.openHelper.writableDatabase)

    val plain = plan(AlbumQueries.MULTI_LOCATION_VERSIONS_SQL, arrayOf("", 500))
    println("Piano delle versioni con piu' posizioni: $plain")
    assertThat(plain).contains("COVERING INDEX index_media_location_version_id_location_type")
    assertThat(plain).doesNotContain("TEMP B-TREE")
    val copies = plan(AlbumQueries.DUPLICATE_COPIES_SQL.replace("(:versionIds)", "('a', 'b', 'c')"), emptyArray())
    println("Piano delle copie dei doppioni: $copies")
    assertThat(copies).contains("index_media_location_version_id")

    val all = albums.duplicates(limit = AlbumCatalog.MAX_DUPLICATE_PAGE)
    assertThat(all.next).isNull()
    assertThat(all.groups).hasSize(lib.duplicates)
    assertThat(all.groups.all { it.copies.size == 2 && it.fullHash != null }).isTrue()
    val millis = median { albums.duplicates(limit = AlbumCatalog.MAX_DUPLICATE_PAGE) }
    println("Doppioni identici: ${all.groups.size} gruppi su 100.000 media in $millis ms")
    assertWithMessage("Doppioni: $millis ms").that(millis).isLessThan(200)
  }

  @Test
  fun iFattiLAttestazioneEICandidatiPerIdentitaSuCentoMilaMediaRestanoSottoIlTettoDiUnaPagina() = runBlocking<Unit> {
    val lib = BigLibrary(100_000)
    lib.insertInto(db.openHelper.writableDatabase)
    val someIds = (0 until 500).map { MediaId(lib.ids[it * 150]!!) }

    val factsMillis = median { albums.facts(someIds) }
    val candidatesMillis = median { albums.candidatesOf(someIds) }
    // 1.000 elementi di un album condiviso: meta' sono nell'area comune (i media con i % 10 == 9), meta' non ci sono.
    val requests = (0 until 1_000).map { n ->
      val i = if (n % 2 == 0) (n / 2) * 10 + 9 else 200_000 + n
      CommonAttestRequest(ContentKey.of(QuickHash("%064x".format(i)), 1_000L + i), null)
    }
    val found = albums.attestInCommon(requests)
    val attestMillis = median(runs = 3) { albums.attestInCommon(requests) }
    println("facts di 500 media: $factsMillis ms; candidatesOf di 500: $candidatesMillis ms; attestazione di 1.000 elementi sull'area comune di 10.000 file: $attestMillis ms")

    assertThat(found.size).isEqualTo(500)
    assertThat(factsMillis).isLessThan(50)
    assertThat(candidatesMillis).isLessThan(50)
    assertThat(attestMillis).isLessThan(500)
  }
}
