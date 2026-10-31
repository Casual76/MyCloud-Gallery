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
 * Fase 10 (P7, D22, D41): la sonda delle descrizioni **prova davvero qualcosa**. Il registro la fa girare su 40 mondi casuali; qui su una dozzina e al
 * contrario: se la superficie descrivesse anche i media che la Libreria non mostra (i nascosti e i loro gruppi), la sonda lo vedrebbe, e il segnale
 * dei nascosti, se arrivasse in una descrizione, sarebbe una fuga.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RealDescriptionsProbeTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test
  fun laSondaVedeLeCelleVereENonTrovaFughe() = runBlocking<Unit> {
    for (seed in 1L..12L) {
      val world = HiddenWorldBuilder.build(context, WorldSpec.random(seed, size = 120))
      try {
        val probed = RealDescriptionProbes.descriptions.probe(world)
        assertWithMessage("[$seed]").that(probed.problems).isEmpty()
        assertThat(probed.shown).isEqualTo(world.libraryCells)
        assertThat(probed.shown.none { it in world.mustNeverShow }).isTrue()
        assertThat(world.mustNeverShow).isNotEmpty() // il mondo ha davvero nascosti da non nominare
      } finally {
        world.close()
      }
    }
  }

  @Test
  fun alContrarioUnaSuperficieChePescaDaTuttiIMediaPerdeEIlSegnaleEUnaFuga() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.random(3, size = 120))
    try {
      val describer = RealDescriptionProbes.describerOf(world)
      val hiddenId = world.mustNeverShow.first()
      // Se qualcuno chiedesse a `warm` anche un nascosto, il suo nome entrerebbe in cache: la descrizione di quel media (che la Libreria non mostra) lo direbbe.
      describer.warm(listOf(hiddenId))
      val hiddenFacts = dev.pampa.mycloudgallery.core.describe.contract.MediaFacts(
        dev.pampa.mycloudgallery.core.model.MediaKind.PHOTO, 20260301, null, null, null, 0, 1, false, null,
      )
      val text = describer.describe(hiddenId, hiddenFacts, dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface.IN_APP)
      assertThat(text).contains(RealDescriptionProbes.SECRET)
      // ... ed e' proprio quello che la sonda, sulle celle vere, non trova mai: le celle vere non comprendono quel media.
      assertThat(RealDescriptionProbes.run(world, (0 until world.snapshot.size).toList(), describer).problems).isEmpty()
      assertThat(world.libraryCells).doesNotContain(hiddenId)
    } finally {
      world.close()
    }
  }

  @Test
  fun neiCasiEstremiSiComportaComeDeve() = runBlocking<Unit> {
    for (spec in listOf(WorldSpec.everythingHidden(), WorldSpec.nothingHidden(), WorldSpec.onlyLateHidden())) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        val probed = RealDescriptionProbes.descriptions.probe(world)
        assertWithMessage(spec.toString()).that(probed.problems).isEmpty()
        assertThat(probed.shown).isEqualTo(world.libraryCells)
      } finally {
        world.close()
      }
    }
  }
}
