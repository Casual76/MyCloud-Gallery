package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// Le mosse a mano per le prove mirate del banco (verificatore, difetti noti): un mondo piccolo sul NAS finto, senza giri casuali. Stesse mosse del giro
// ([ChaosRound]), ma una alla volta e con i colpi che si armano a mano.

internal object ChaosTestKit {

  /** Un mondo di [devices] dispositivi sul NAS finto, con [privateMedia] media privati, Persone accesa su tutti e i colpi spenti. */
  suspend fun world(seed: Long = 1L, devices: Int = 2, privateMedia: Int = 4, mutant: Mutant? = null, activate: Boolean = true): ChaosWorld {
    val world = ChaosWorld(seed, ChaosInjector(), mutant) { MemoryChaosNas(it.inj, it.logs, it.family.common) }
    repeat(devices) { world.device("D$it") }
    world.media.populate(privateMedia, 0)
    world.plantUntouchable()
    if (activate) {
      world.devices.first().activate()
      repeat(2) { world.devices.forEach { it.personalRound() } }
      world.devices.drop(1).forEach { it.acceptNotice() }
      world.devices.forEach { it.personalRound() }
    }
    return world
  }

  /** Un giro del mondo senza guasti: ogni dispositivo acceso fa il giro dei dati personali, la ripresa, la pulizia, il calcolo, la pubblicazione e la replica. */
  suspend fun turn(world: ChaosWorld, compute: Boolean = true) {
    for (device in world.devices) {
      if (!device.online) continue
      device.personalRound()
      if (device.switch.currentState() is FacesState.AwaitingNotice) device.acceptNotice()
      device.eraser.resume(1.hours)
      device.janitor.run(5.minutes)
      if (compute && device.isActive()) {
        device.prepareCompute()
        device.computeAll()
      }
      device.publisher.flush(FlushReason.SYNC_ROUND)
      device.replica.sync(1.hours)
    }
    world.clock.advance(60_000)
  }

  suspend fun turns(world: ChaosWorld, count: Int, compute: Boolean = true) = repeat(count) { turn(world, compute) }
}
