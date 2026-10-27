package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlin.time.Duration.Companion.minutes

// Le mosse comuni delle prove di "Disattiva e cancella" (Fase 8, P8): accendere Persone su piu' dispositivi con dati, nomi e ricerche recenti;
// confermare dal foglio; far girare il mondo finche' la cancellazione arriva in fondo. Solo nomi inventati.

/** Un mondo con [names] dispositivi dell'account, Persone accesa da [names].first(), l'avviso visto dagli altri, dati e nomi su ognuno. */
internal suspend fun EraseWorld.activeFamily(vararg names: String, invariants: EraseInvariants? = null): List<EraseDevice> {
  val devices = names.map { device(it) }
  devices.first().activate()
  repeat(2) { devices.forEach { it.personalRound() } }
  devices.drop(1).forEach { it.acceptNotice() }
  devices.forEach { it.personalRound() }
  val generation = devices.first().generation()
  devices.forEachIndexed { index, device ->
    repeat(2) { n ->
      check(device.beginCompute()) { "${device.name} non calcola" }
      check(device.endCompute(index * 10 + n))
    }
    val name = "Persona${index}x"
    check(device.name(index, name))
    invariants?.named(generation, name, device)
    device.publish()
  }
  return devices
}

/** La generazione accesa adesso su questo dispositivo. */
internal fun EraseDevice.generation(): FaceGeneration = when (val state = switch.currentState()) {
  is FacesState.On -> state.generation
  is FacesState.AwaitingNotice -> state.generation
  is FacesState.Erasing -> state.generation
  else -> error("Persone non e' accesa su $name ($state)")
}

/** Il foglio: l'anteprima, poi "Disattiva e cancella" con la conferma costruita come la costruisce il foglio (con l'impronta e l'istante). */
internal suspend fun EraseDevice.confirmErase(choice: PeopleAlbumsChoice = PeopleAlbumsChoice.KEEP_PHOTOS): EraseStart {
  val preview = eraser.preview()
  return eraser.erase(FacesEraseConfirmation(preview.digest, world.clock.monotonic), choice)
}

/** Un giro del mondo: ogni dispositivo acceso fa il giro dei dati personali, la pulizia del NAS, la ripresa del diario e pubblica i suoi volti. */
internal suspend fun EraseWorld.turn(invariants: EraseInvariants? = null) {
  for (device in devices) {
    if (!device.online) continue
    device.personalRound()
    invariants?.check("giro ${device.name}")
    device.resume()
    invariants?.check("ripresa ${device.name}")
    device.janitor.run(5.minutes)
    invariants?.check("pulizia ${device.name}")
    device.publish()
    invariants?.check("pubblicazione ${device.name}")
  }
  clock.advanceMillis(60_000)
}

/** Fa girare il mondo finche' la cancellazione di [eraser] e' finita (al piu' [turns] giri). Vero se e' finita. */
internal suspend fun EraseWorld.settle(eraser: EraseDevice, turns: Int = 12, invariants: EraseInvariants? = null): Boolean {
  repeat(turns) {
    if (eraser.journal.stored?.done == true) return true
    turn(invariants)
  }
  return eraser.journal.stored?.done == true
}
