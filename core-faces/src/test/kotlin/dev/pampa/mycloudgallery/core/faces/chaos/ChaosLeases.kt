package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.model.FaceGeneration

/**
 * Le prenotazioni vere di P3 guardate dal banco (invariante 7 del §5.5: "mai la stessa unita' a due dispositivi insieme"): ogni presa o rinnovo riuscito
 * si confronta con chi la teneva e non era ancora scaduta; una chiamata che fallisce (guasto, morte) non si registra, perche' chi chiama non sa com'e' andata.
 */
internal class ChaosLeases(private val inner: FacesLeases, private val world: ChaosWorld, private val device: ChaosDevice) : FacesLeases {
  override suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease {
    world.inj.checkAlive(device.index)
    return uncertain(generation, resource) { inner.acquire(generation, resource) }.also { world.leaseEvent(device, generation, resource, it) }
  }

  override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease {
    world.inj.checkAlive(device.index)
    return uncertain(generation, resource) { inner.renew(generation, resource, held) }.also { world.leaseEvent(device, generation, resource, it) }
  }

  override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) {
    world.inj.checkAlive(device.index)
    uncertain(generation, resource) { inner.release(generation, resource, held) }
    world.leaseEvent(device, generation, resource, held, released = true)
  }

  /** Una chiamata che non torna (guasto, morte) puo' aver cambiato la prenotazione sul NAS lo stesso (la risposta si perde): da qui non si sa piu' chi la tiene. */
  private suspend inline fun <T> uncertain(generation: FaceGeneration, resource: FacesResource, block: () -> T): T = try {
    block()
  } catch (e: Throwable) {
    world.leaseUnknown(generation, resource)
    throw e
  }
}

/** Il foglio: l'anteprima, poi "Disattiva e cancella" con la conferma costruita come la costruisce il foglio (con l'impronta e l'istante). */
internal suspend fun ChaosDevice.confirmErase(choice: PeopleAlbumsChoice = PeopleAlbumsChoice.KEEP_PHOTOS): EraseStart {
  val preview = eraser.preview()
  return eraser.erase(FacesEraseConfirmation(preview.digest, world.clock.monotonicNanos()), choice)
}
