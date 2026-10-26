package dev.pampa.mycloudgallery.debugtools.eval.albums

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.hidden.BuiltWorld
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import dev.pampa.mycloudgallery.hidden.RealTripsStack
import dev.pampa.mycloudgallery.hidden.WorldSpec
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La porta dello strumento sopra il **catalogo vero** (Room in memoria, Robolectric; i mondi casuali di P9): niente nascosti (D22) in nessuna fase, i nomi dei
 * file della prova generale ritrovati sulle tabelle vere, e soprattutto **sola lettura**: dopo la cieca, la corsa, i mancanti e la rilevazione dei viaggi, **nessuna
 * tabella della Libreria ha una riga in piu' o in meno**. Mondi sintetici: nessuna foto vera.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AppAlbumsEvalEnvironmentTest {

  @get:Rule val tmp = TemporaryFolder()

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private val today: LocalDate = LocalDate.of(2026, 10, 22)

  private fun envOver(world: BuiltWorld): AppAlbumsEvalEnvironment {
    val places = FakePlaces()
    return AppAlbumsEvalEnvironment(
      catalog = { world.catalog },
      database = world.db,
      visible = CatalogVisibleMedia(world.db, MutableStateFlow(world.snapshot)),
      mask = CatalogHiddenMask(world.db),
      geocoder = places,
      names = places,
      thumbnail = { null },
      context = context,
      search = NoSmartContentSearch,
      todayPort = Today { today },
    )
  }

  /** Quante righe ha ogni tabella della Libreria: la fotografia che prima e dopo deve essere identica. */
  private fun rowCounts(db: GalleryDatabase): Map<String, Long> {
    val sql = db.openHelper.readableDatabase
    val tables = ArrayList<String>()
    sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'").use { while (it.moveToNext()) tables += it.getString(0) }
    return tables.sorted().associateWith { table -> sql.query("SELECT COUNT(*) FROM \"$table\"").use { it.moveToFirst(); it.getLong(0) } }
  }

  @Test
  fun iNascostiNonEsconoNeDallaCiecaNeDaiSicuri() = runBlocking<Unit> {
    var cellsSeen = 0
    for (seed in 1L..8L) {
      val world = HiddenWorldBuilder.build(context, WorldSpec.random(seed, size = 120))
      try {
        val env = envOver(world)
        val cells = env.cellsOfDays(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31))
        cellsSeen += cells.size
        val ids = cells.map { it.id }.toSet()
        assertWithMessage("seed $seed: un nascosto nella cieca").that(ids.filter { it in world.liveMustNeverShow.map { m -> m.value } }).isEmpty()
        assertThat(ids.all { id -> id in world.liveShowable.map { it.value } }).isTrue()
        // Le celle per id: stessa regola, e un id che non c'e' manca.
        val byId = env.cellsOf(world.allIds.map { it.value } + "00000000-0000-4000-8000-000000000000")
        assertThat(byId.map { it.id }.filter { it in world.liveMustNeverShow.map { m -> m.value } }).isEmpty()
        assertThat(byId.map { it.id }.toSet()).isEqualTo(ids)
      } finally {
        world.close()
      }
    }
    assertThat(cellsSeen).isGreaterThan(100)
  }

  @Test
  fun iNomiDeiFileSiRitrovanoSulCatalogoVero() = runBlocking<Unit> {
    val spec = WorldSpec.random(3, size = 120)
    val world = HiddenWorldBuilder.build(context, spec)
    try {
      val env = envOver(world)
      val onPhone = spec.media.filter { it.onPhone }
      assertThat(onPhone).isNotEmpty()
      val names = onPhone.map { "IMG_${spec.seed}_${it.index}.jpg" }.toSet() + "non-c-e.jpg"
      val found = env.mediaByFileName(names)
      assertThat(found.keys).containsExactlyElementsIn(onPhone.map { "IMG_${spec.seed}_${it.index}.jpg" })
      for (m in onPhone) assertThat(found.getValue("IMG_${spec.seed}_${m.index}.jpg")).isEqualTo(world.ids[m.index])
      assertThat(env.mediaByFileName(emptySet())).isEmpty()
    } finally {
      world.close()
    }
  }

  @Test
  fun loStrumentoNonScriveNellaLibreria() = runBlocking<Unit> {
    var worldsWithTrip = 0
    var blindWithRun = 0
    for (seed in 1L..12L) {
      val stack = RealTripsStack.build(HiddenWorldBuilder.build(context, WorldSpec.random(seed, size = 120)))
      stack.use {
        val world = stack.w
        val before = rowCounts(world.db)
        val env = envOver(world)
        val session = AlbumsEvalSession(env, AlbumsEvalStore(File(tmp.root, "albums-$seed"))).also { it.load() }

        // La cieca, la corsa, i giudizi e i mancanti su un viaggio del mondo (i giorni 7-10 del mese).
        val trip = session.addTrip("Marzo 2026", LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 11))
        val blind = session.blindCells(trip.id)
        assertThat(blind.map { it.id }.filter { it in world.liveMustNeverShow.map { m -> m.value } }).isEmpty()
        if (blind.isNotEmpty()) {
          session.setInTrip(trip.id, blind.take(5).map { it.id }, true)
          val run = session.runTrip(trip.id)
          assertThat((run.sure + run.suggested).filter { it in world.liveMustNeverShow.map { m -> m.value } }).isEmpty()
          session.pending(trip.id).sure.take(3).forEach { session.setVerdict(trip.id, it, Verdict.RIGHT) }
          session.missingCells(trip.id)
          session.cellsOf(run.sure.take(10))
          session.saveF88Report()
          blindWithRun++
        }

        // La rilevazione dei viaggi: nel database di lavoro in memoria, non in quello della Libreria.
        val view = env.tripProposals()
        assertThat(view.available).isTrue()
        if (view.proposals.any { it.isTrip }) worldsWithTrip++

        assertWithMessage("seed $seed: la Libreria e' cambiata").that(rowCounts(world.db)).isEqualTo(before)
      }
    }
    // Le proposte sono quelle di `CatalogTrips` vero: nella maggior parte dei mondi (con il GPS di Oslo dal 7 al 10 marzo) c'e' un viaggio.
    assertThat(worldsWithTrip).isAtLeast(8)
    assertThat(blindWithRun).isAtLeast(8)
  }
}
