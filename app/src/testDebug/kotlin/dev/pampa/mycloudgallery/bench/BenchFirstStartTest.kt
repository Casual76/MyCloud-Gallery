package dev.pampa.mycloudgallery.bench

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.integration.Attese
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il difetto del primo avvio (pacchetto C1): dopo un'installazione pulita la preparazione del dataset di misura cadeva con
 * `no such table: search_history` e con `database is locked`. La causa: il seminatore cancellava e riscriveva, con una seconda istanza di Room, lo
 * stesso file che l'app aveva gia' aperto nello stesso processo (vedi `BenchDatabaseFiles`).
 *
 * Qui gli ingressi sono quelli veri: un database nuovo che l'app apre per prima (come `GalleryGraph`), la cronologia di Cerca letta e scritta di
 * continuo (il flusso di Room che al primo avvio trovava il file senza tabelle, e la scrittura che trovava il file bloccato), e il seminatore vero
 * sul suo thread. Nessuna attesa a tempo: l'app lavora finche' il seminatore non ha finito.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BenchFirstStartTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val profile = BenchProfile.MIXED

  private fun media(): List<BenchRealMedia> = listOf(
    BenchRealMedia(11L, false, "image/jpeg", 4000, 3000, 0, 0L, 3_000_000L, 0L, "IMG_1.jpg"),
    BenchRealMedia(12L, false, "image/jpeg", 3000, 4000, 90, 0L, 2_500_000L, 0L, "IMG_2.jpg"),
    BenchRealMedia(13L, false, "image/png", 1080, 2400, 0, 0L, 400_000L, 0L, "Screenshot_3.png"),
    BenchRealMedia(15L, true, "video/mp4", 1920, 1080, 0, 15_000L, 8_000_000L, 0L, "VID_5.mp4"),
  )

  /** Room non lavora sul thread principale: nell'app queste letture stanno su `Dispatchers.IO`. */
  private suspend fun count(db: GalleryDatabase, table: String): Int = withContext(Dispatchers.IO) {
    db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
      c.moveToFirst()
      c.getInt(0)
    }
  }

  private fun catalog(db: GalleryDatabase) = MediaCatalog(db, AccountId.of("bench")).aiIndex

  @Test
  fun laPreparazioneNonToccaIlDatabaseCheLAppHaAperto() = runBlocking {
    // Cio' che resta di una preparazione interrotta (il processo fermato a meta'): non e' un database, e non deve contare.
    context.getDatabasePath(BenchDatabaseFiles.buildingName(profile)).apply { parentFile?.mkdirs() }.writeText("a meta'")
    File(context.getDatabasePath(BenchDatabaseFiles.buildingName(profile)).path + "-wal").writeText("a meta'")

    // Il primo avvio: l'app apre per prima il suo database (nasce con tutto lo schema) e ci scrive.
    val app = GalleryDatabase.build(context, profile.databaseName)
    val history = catalog(app)
    history.recordSearch("prima del dataset", emptyList())

    // Il seminatore vero, sul suo thread, come lo avvia `BenchDatasetProvider`.
    val seeded = CompletableDeferred<Result<BenchSeedReport>>()
    Thread({ seeded.complete(runCatching { BenchSeeder.seed(context, media(), ROWS, "1", NOW, profile, smoke = true) }) }, "bench-dataset").start()

    // Intanto l'app lavora: scrive la cronologia, la rilegge con il flusso di Room (tabella osservata: il primo giro scrive anche i trigger) e conta
    // i media. Finche' il seminatore non ha finito; almeno un giro.
    val appWork = async(Dispatchers.IO) {
      var rounds = 0
      var failure: Throwable? = null
      do {
        try {
          history.recordSearch("giro ${rounds % 7}", listOf("chip"))
          history.searchHistoryChanges().first()
          count(app, "media")
        } catch (e: Exception) {
          failure = e
        }
        rounds++
      } while (failure == null && !seeded.isCompleted)
      failure
    }

    val report = withTimeout(Attese.TETTO) { seeded.await() }
    val appFailure = withTimeout(Attese.TETTO) { appWork.await() }

    // L'app non ha visto niente: nessun `no such table`, nessun `database is locked`.
    assertThat(appFailure).isNull()
    assertThat(report.exceptionOrNull()).isNull()
    assertThat(report.getOrThrow().indexRows).isEqualTo(ROWS)
    assertThat(report.getOrThrow().phoneRefsResolved).isEqualTo(report.getOrThrow().phoneRefsAsked)

    // Il file dell'app e' rimasto dell'app: le sue righe ci sono, quelle del dataset no. Lo dice l'istanza aperta e lo dice il file riaperto.
    assertThat(history.searchHistory().map { it.text }).contains("prima del dataset")
    assertThat(count(app, "media")).isEqualTo(0)
    withContext(Dispatchers.IO) { app.close() }
    val reopened = GalleryDatabase.build(context, profile.databaseName)
    assertThat(catalog(reopened).searchHistory().map { it.text }).contains("prima del dataset")
    assertThat(count(reopened, "media")).isEqualTo(0)
    withContext(Dispatchers.IO) { reopened.close() }

    // Il dataset finito aspetta, intero, il prossimo avvio.
    assertThat(BenchDatabaseFiles.hasReady(context, profile)).isTrue()
    assertThat(context.getDatabasePath(BenchDatabaseFiles.buildingName(profile)).exists()).isFalse()

    // Il processo successivo: lo scambio avviene prima che l'app apra il database, poi l'app trova il dataset con lo schema intero.
    assertThat(BenchDatabaseFiles.promoteReady(context)).isTrue()
    assertThat(BenchDatabaseFiles.hasReady(context, profile)).isFalse()
    val next = GalleryDatabase.build(context, profile.databaseName)
    assertThat(count(next, "media")).isEqualTo(ROWS)
    assertThat(catalog(next).searchHistoryChanges().first()).isEmpty()
    catalog(next).recordSearch("dopo il dataset", emptyList())
    assertThat(catalog(next).searchHistory().map { it.text }).containsExactly("dopo il dataset")
    withContext(Dispatchers.IO) { next.close() }

    // Un avvio dopo ancora: niente che aspetta, niente si tocca.
    assertThat(BenchDatabaseFiles.promoteReady(context)).isFalse()
    val later = GalleryDatabase.build(context, profile.databaseName)
    assertThat(count(later, "media")).isEqualTo(ROWS)
    assertThat(catalog(later).searchHistory().map { it.text }).containsExactly("dopo il dataset")
    withContext(Dispatchers.IO) { later.close() }
  }

  @Test
  fun senzaUnDatasetProntoIlDatabaseDellAppNonSiTocca() = runBlocking {
    val app = GalleryDatabase.build(context, profile.databaseName)
    catalog(app).recordSearch("mia", emptyList())
    withContext(Dispatchers.IO) { app.close() }
    // Una preparazione interrotta lascia solo il file del seminatore: non e' un dataset pronto.
    context.getDatabasePath(BenchDatabaseFiles.buildingName(profile)).writeText("a meta'")

    assertThat(BenchDatabaseFiles.promoteReady(context)).isFalse()

    val again = GalleryDatabase.build(context, profile.databaseName)
    assertThat(catalog(again).searchHistory().map { it.text }).containsExactly("mia")
    withContext(Dispatchers.IO) { again.close() }
  }

  private companion object {
    /** Il dataset piccolo dello smoke: stessa strada di quello da 100 mila righe, in pochi secondi. */
    const val ROWS: Int = BenchDataset.SMOKE_ROWS
    const val NOW: Long = 1_791_936_000_000L
  }
}
