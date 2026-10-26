package dev.pampa.mycloudgallery.hidden

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.duplicates.CatalogIdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.CatalogSimilarShots
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalStatus
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 7 (P8, D22, C27): i mondi casuali dei Nascosti hanno **davvero** dei doppioni da provare. Senza, la sonda dei Doppioni passerebbe a vuoto:
 * qui si controlla che, sul codice vero e sul catalogo vero, i mondi abbiano gruppi identici da pulire, scatti simili, e che una pagina con i
 * gruppi letti *prima* di un nascosto lo avrebbe davvero incluso (cosi' il filtro del piano e' provato, non assunto).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HiddenDuplicatesSurfaceTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test
  fun iMondiCasualiHannoDoppioniIdenticiSimiliEGruppiLettiPrimaDiUnNascosto() = runBlocking<Unit> {
    var groups = 0
    var cleanable = 0
    var staleHidden = 0
    var clusters = 0
    var withCopies = 0
    for (seed in 1L..SEEDS) {
      val spec = WorldSpec.random(seed)
      if (spec.sameSideCopies.isNotEmpty()) withCopies++
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val visible = CatalogVisibleMedia(world.db, MutableStateFlow(world.snapshot))
        val scan = CatalogIdenticalDuplicates(world.catalog, visible).scan()
        groups += scan.proposals.size
        cleanable += scan.proposals.count { it.status == IdenticalStatus.CLEANABLE }
        // Le seconde copie sono dalla stessa parte: ogni gruppo trovato e' di un media che ne ha una.
        scan.proposals.forEach { assertThat(world.indexOf(it.group.media)).isIn(spec.sameSideCopies) }
        staleHidden += CatalogIdenticalDuplicates(world.catalog, HiddenProbes.everythingVisible(world)).scan().proposals
          .count { it.group.media in world.liveMustNeverShow }
        val similar = CatalogSimilarShots(world.catalog, visible, CatalogHiddenMask(world.db), vectors = HiddenProbes.sameVector)
        var before: Int? = null
        do {
          val batch = similar.scan(before, 1.hours)
          clusters += batch.clusters.size
          before = batch.nextBeforeDay
        } while (before != null)
      } finally {
        world.close()
      }
    }
    assertThat(withCopies).isEqualTo(SEEDS.toInt())
    assertThat(groups).isGreaterThan(SEEDS.toInt())
    assertThat(cleanable).isGreaterThan(0)
    assertThat(staleHidden).isGreaterThan(0)
    assertThat(clusters).isGreaterThan(0)
  }

  private companion object {
    const val SEEDS = 12L
  }
}
