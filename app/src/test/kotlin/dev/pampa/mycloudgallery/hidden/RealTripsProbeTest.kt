package dev.pampa.mycloudgallery.hidden

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 7 (P7, D22, E-H7): la sonda dei viaggi sul codice vero **prova davvero qualcosa**. Il registro la fa girare su 40 mondi casuali; qui la si fa
 * girare su una dozzina e si controlla che nella maggior parte dei mondi l'oracolo si aspetti un viaggio (e quindi la sonda lo abbia cercato fra le
 * proposte, con i conteggi e le copertine), che dopo aver nascosto la copertina la sonda abbia ricontrollato, e che ogni tanto un viaggio resti senza
 * media e sparisca. Un viaggio che non c'e' mai renderebbe la sonda un controllo che passa sempre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RealTripsProbeTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test
  fun laSondaDeiViaggiVedeViaggiVeriENonPassaSempre() = runBlocking<Unit> {
    TripsProbeStats.reset()
    val problems = ArrayList<String>()
    for (seed in 1L..12L) {
      val world = HiddenWorldBuilder.build(context, WorldSpec.random(seed, size = 120))
      try {
        val probed = RealTripsProbes.trips.probe(world)
        problems += probed.problems.map { "[$seed] $it" }
        assertThat(probed.shown.all { it in world.liveShowable }).isTrue()
      } finally {
        world.close()
      }
    }
    assertWithMessage("la sonda dei viaggi ha trovato violazioni").that(problems).isEmpty()
    assertThat(TripsProbeStats.worlds).isEqualTo(12)
    assertThat(TripsProbeStats.withTrip).isAtLeast(8)
    assertThat(TripsProbeStats.coverHiddenAfter).isAtLeast(8)
  }

  /** Il caso peggiore e quello opposto: tutto nascosto non ha viaggi ne' casa, niente nascosto li ha tutti. */
  @Test
  fun neiCasiEstremiSiComportaComeDeve() = runBlocking<Unit> {
    for (spec in listOf(WorldSpec.everythingHidden(), WorldSpec.nothingHidden(), WorldSpec.onlyLateHidden())) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val probed = RealTripsProbes.trips.probe(world)
        assertWithMessage(spec.toString()).that(probed.problems).isEmpty()
        assertThat(probed.shown.none { it in world.liveMustNeverShow }).isTrue()
      } finally {
        world.close()
      }
    }
  }
}
