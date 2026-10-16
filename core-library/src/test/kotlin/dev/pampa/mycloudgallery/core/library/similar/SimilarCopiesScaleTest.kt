package dev.pampa.mycloudgallery.core.library.similar

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.InMemorySimilarChoices
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il giro del rilevatore su una libreria grande (Fase 5A, P15; riga P15 del §4.4): **100.000 media con 1.000 gruppi di copie simili**.
 * Il catalogo e' vero (Room in memoria, Robolectric): 98.000 media sparsi come una libreria vera (scritti a blocchi con SQL, come
 * nelle prove di P1: non sono candidati), piu' 1.000 coppie di copie dello stesso scatto (scritte dal catalogo, con le loro
 * posizioni). I pixel sono finti e costano come quelli veri una volta decodificati: la decodifica (JPEG da telefono, miniature del
 * NAS) e' un costo del dispositivo, non si misura qui. Misure su JVM: il dispositivo si misura a M2.
 */
@RunWith(RobolectricTestRunner::class)
class SimilarCopiesScaleTest : SimilarCatalogTestBase() {

  /** I media di riempimento: un secondo ogni 7, ognuno da solo nel suo secondo, con la data letta dai metadati. */
  private fun bulk(count: Int) {
    val sql = db.openHelper.writableDatabase
    val random = Random(21)
    sql.beginTransaction()
    try {
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', 0)",
      )
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo) " +
          "VALUES (?, 'photo', 'camera', 'alessio', NULL, ?, 0, 0, ?, 'phone_only', 'no_nas_copy', 20251009, 1333, NULL, NULL, 0, NULL, 0)",
      )
      val metadata = sql.compileStatement(
        "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, taken_offset_min, " +
          "taken_confidence, taken_source, latitude, longitude, altitude, updated_at, mirrored, traits, place_id, place_dataset, thumb_hash) " +
          "VALUES (?, 'image/jpeg', 4000, 3000, 0, NULL, ?, 120, 'exact', 'exif_offset', NULL, NULL, NULL, 0, 0, 0, NULL, NULL, NULL)",
      )
      for (i in 0 until count) {
        val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
        val versionId = UUID(random.nextLong(), random.nextLong()).toString()
        val takenAt = Fx.SECOND0 - 7_000L * (i + 1)
        version.clearBindings()
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, 1_000L + i)
        version.bindString(4, "%064x".format(i))
        version.executeInsert()
        media.clearBindings()
        media.bindString(1, mediaId)
        media.bindString(2, versionId)
        media.bindLong(3, takenAt)
        media.executeInsert()
        metadata.clearBindings()
        metadata.bindString(1, versionId)
        metadata.bindLong(2, takenAt)
        metadata.executeInsert()
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
  }

  private val ofMedia = HashMap<MediaId, Int>()

  /** I pixel si disegnano al momento (e si buttano): 2.000 immagini di 512 x 384 insieme non starebbero nella memoria della prova. */
  private val source = SignatureSource { candidate ->
    SignaturePixels.Ready(listOf(pattern(ofMedia.getValue(candidate.mediaId).toLong() + 1)))
  }

  /** Una coppia di copie dello stesso scatto: il telefono e una copia sul NAS, in un secondo che nessun riempimento usa. */
  private suspend fun pair(k: Int) {
    // I secondi del riempimento sono multipli di 7 sotto SECOND0; questi stanno 3 s sotto un multiplo di 98 (mai uguali).
    val takenAt = Fx.SECOND0 - 3_000L - 98_000L * k
    val a = phoneShot(takenAt = takenAt)
    val b = nasShot("Cloud/c$k.jpg", takenAt = takenAt, width = 2000, height = 1500)
    ofMedia[a] = k
    ofMedia[b] = k
  }

  @Test
  fun centoMilaMediaConMilleGruppiUnGiroCompleto() = runBlocking<Unit> {
    val filler = 98_000
    val pairs = 1_000
    val seeding = System.nanoTime()
    bulk(filler)
    for (k in 0 until pairs) pair(k)
    println("SCALA semina: ${(System.nanoTime() - seeding) / 1_000_000} ms (${filler + pairs * 2} media, $pairs coppie)")
    assertThat(db.mediaDao().count()).isEqualTo(filler + pairs * 2)

    val choices = InMemorySimilarChoices()
    val detector = CatalogSimilarCopies(catalog, source, choices, pageSize = 500)

    // 1. Il primo giro: firma 2.000 candidati, confronta 1.000 coppie, scrive 1.000 gruppi.
    val first = System.nanoTime()
    val report = detector.run(30.minutes)
    val firstMs = (System.nanoTime() - first) / 1_000_000
    println("SCALA primo giro: $firstMs ms, firmati=${report.signed} gruppi=${report.grouped} in attesa=${report.pending}")
    assertThat(report.signed).isEqualTo(pairs * 2)
    assertThat(report.grouped).isEqualTo(pairs)
    assertThat(report.pending).isEqualTo(0)
    assertThat(detector.progress.value.settled).isTrue()
    assertThat(catalog.similarGroupIds(null, 5_000)).hasSize(pairs)

    // 2. Un giro senza novita' (il caso di ogni notte): legge i candidati e le firme, non scrive niente.
    val seq = catalog.changeSeq()
    val steady = System.nanoTime()
    val again = detector.run(30.minutes)
    val steadyMs = (System.nanoTime() - steady) / 1_000_000
    println("SCALA giro senza novita': $steadyMs ms, firmati=${again.signed} gruppi=${again.grouped}")
    assertThat(again.signed).isEqualTo(0)
    assertThat(again.grouped).isEqualTo(0)
    assertThat(catalog.changeSeq()).isEqualTo(seq)

    // 3. Arrivano 50 coppie nuove: il giro firma e scrive solo quelle.
    for (k in pairs until pairs + 50) pair(k)
    val incremental = System.nanoTime()
    val third = detector.run(30.minutes)
    val incrementalMs = (System.nanoTime() - incremental) / 1_000_000
    println("SCALA 50 coppie nuove: $incrementalMs ms, firmati=${third.signed} gruppi=${third.grouped}")
    assertThat(third.signed).isEqualTo(100)
    assertThat(third.grouped).isEqualTo(50)

    // 4. Il budget si rispetta: un giro con budget minimo avanza di un secondo alla volta e non rifa' niente.
    val tiny = detector.run(kotlin.time.Duration.ZERO)
    assertThat(tiny.signed).isEqualTo(0)

    // 5. Quanto costano le sole letture dei candidati (la query di P1) dentro il giro.
    val scan = System.nanoTime()
    var from = 0L
    var runs = 0
    while (true) {
      val page = catalog.similarRuns(from, 500)
      runs += page.runs.size
      from = page.nextSecond ?: break
    }
    println("SCALA lettura dei candidati: ${(System.nanoTime() - scan) / 1_000_000} ms, $runs secondi con compagni")
    assertThat(runs).isEqualTo(pairs + 50)

    // Il budget "ragionevole": un giro completo dentro il tempo di una tappa di manutenzione (5 minuti), uno senza novita' in pochi secondi.
    assertThat(firstMs).isLessThan(5 * 60 * 1_000L)
    assertThat(steadyMs).isLessThan(30_000L)
  }
}
