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
 * Fase 9 (P10, D19, D31, riga NOTIFICATIONS del registro, F116): **la prova al contrario delle notifiche dei ricordi**. La proprieta' dei Nascosti passa solo se il filtro c'e': qui
 * lo si toglie (la visibilita' che il giorno di una notifica chiede subito prima di pubblicare e che la revoca chiede dopo non nasconde niente) e la sonda deve diventare rossa, su
 * mondi con "ultimi nascosti" (che l'istantanea della Libreria ha ancora: sono loro che escono se il filtro manca). Con il filtro, gli stessi mondi sono verdi, e la sonda ha davvero
 * pubblicato notifiche, con la miniatura e con la copertina nascosta da revocare: se questa prova passasse sempre, la sonda non proverebbe niente.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HiddenNotifyMutationTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  /** Cosa la sonda trova di sbagliato sul mondo: media che non devono comparire, fuori dal mostrabile, o frasi dei controlli. */
  private suspend fun violations(leaky: Boolean, world: BuiltWorld): List<String> {
    val probed = RealNotifyProbes.notifications(leaky).probe(world)
    val out = ArrayList<String>()
    val leaked = probed.shown.count { it in world.liveMustNeverShow }
    if (leaked > 0) out += "mostra $leaked media che non deve mostrare mai"
    probed.within?.let { within -> if (probed.shown.any { it !in within }) out += "mostra media fuori da quelli mostrabili" }
    out += probed.problems
    return out
  }

  private val worlds = listOf(WorldSpec.onlyLateHidden(), WorldSpec.random(3), WorldSpec.random(11), WorldSpec.random(21), WorldSpec.random(31))

  @Test
  fun conIlFiltro_laSondaDelleNotificheEVerdeEHaProvatoQualcosa() = runBlocking<Unit> {
    NotifyProbeStats.reset()
    for (spec in worlds) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        assertWithMessage("[$spec]").that(violations(leaky = false, world = world)).isEmpty()
      } finally {
        world.close()
      }
    }
    // La prova non e' vuota: notifiche pubblicate, con la miniatura, con una copertina nascosta da revocare.
    assertThat(NotifyProbeStats.published).isAtLeast(worlds.size)
    assertThat(NotifyProbeStats.withImage).isAtLeast(2)
    assertThat(NotifyProbeStats.coverHiddenCandidates).isAtLeast(1)
    assertThat(NotifyProbeStats.revoked).isAtLeast(1)
  }

  @Test
  fun senzaIlFiltro_laSondaDiventaRossa() = runBlocking<Unit> {
    var caught = 0
    for (spec in worlds) {
      val world = HiddenWorldBuilder.build(context, spec)
      try {
        assertThat(world.liveMustNeverShow).isNotEmpty()
        if (violations(leaky = true, world = world).isNotEmpty()) caught++
      } finally {
        world.close()
      }
    }
    assertWithMessage("la sonda delle notifiche non si accorge che il filtro manca").that(caught).isAtLeast(1)
  }

  /** Il mondo estremo: tutto nascosto. Nessuna miniatura, nessuna notifica con una foto. */
  @Test
  fun seTuttoENascosto_nonEsceNiente() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.everythingHidden())
    try {
      assertThat(violations(leaky = false, world = world)).isEmpty()
      assertThat(RealNotifyProbes.notifications().probe(world).shown).isEmpty()
    } finally {
      world.close()
    }
  }
}
