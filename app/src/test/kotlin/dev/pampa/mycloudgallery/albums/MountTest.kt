package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Il montaggio degli album e il suo stop (Fase 7, revisione di fine fase, I11): lo stop aspetta un montaggio in corso, e un montaggio che si accorge dello
 * stop butta cio' che ha costruito e il suo ambito invece di pubblicarlo. Prima `stop()` metteva `live` a null e annullava l'ambito senza aspettare: un
 * `build` che finiva dopo lo stop assegnava `live` con un ambito annullato.
 */
class MountTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private class Thing(val scope: CoroutineScope)

  @Test
  fun ilMontaggioSiFaUnaVoltaEIlSuccessivoRiusaIlPrimo() = runBlocking<Unit> {
    val builds = AtomicInteger()
    val mounted = ArrayList<Thing>()
    val mount = Mount<Thing>(scope, { child -> builds.incrementAndGet(); Thing(child) }, { mounted += it })

    val first = checkNotNull(mount.ensure())
    assertThat(mount.ensure()).isSameInstanceAs(first)
    assertThat(mount.current).isSameInstanceAs(first)
    assertThat(builds.get()).isEqualTo(1)
    assertThat(mounted).containsExactly(first)
  }

  @Test
  fun unaCostruzioneCheFallisceNonMontaENonFaCrollare() = runBlocking<Unit> {
    val mount = Mount<Thing>(scope, { error("famiglia non pronta") }, { throw AssertionError("non va pubblicato") })
    assertThat(mount.ensure()).isNull()
    assertThat(mount.current).isNull()
  }

  @Test
  fun loStopAspettaIlMontaggioInCorsoELoButta_I11() = runBlocking<Unit> {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var child: CoroutineScope? = null
    var published = 0
    val mount = Mount<Thing>(
      scope,
      { mountScope ->
        child = mountScope
        started.complete(Unit)
        release.await()
        Thing(mountScope)
      },
      { published++ },
    )

    val mounting = async(Dispatchers.Default) { mount.ensure() }
    started.await()
    // Lo stop parte con il montaggio ancora a meta': deve aspettarlo.
    val stopping = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { mount.stop() }
    assertThat(stopping.isCompleted).isFalse()
    release.complete(Unit)
    stopping.await()

    // Il montaggio e' finito dopo lo stop: non e' pubblicato, il suo ambito e' annullato, `live` resta vuoto.
    assertThat(mounting.await()).isNull()
    assertThat(published).isEqualTo(0)
    assertThat(mount.current).isNull()
    assertThat(checkNotNull(child).coroutineContext[Job]!!.isActive).isFalse()
    // E dopo lo stop niente si monta piu'.
    assertThat(mount.ensure()).isNull()
  }

  @Test
  fun loStopDiUnMontaggioFinitoLoToglieEDiventaDefinitivo() = runBlocking<Unit> {
    val mount = Mount<Thing>(scope, { Thing(it) }, { })
    checkNotNull(mount.ensure())

    mount.stop()

    assertThat(mount.current).isNull()
    assertThat(mount.ensure()).isNull()
  }
}
