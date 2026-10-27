package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import java.util.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "I sei invarianti del §2.8 sono provati come proprieta'" (riga P8 del §4.4). Storie a caso di 2-4 dispositivi dello stesso account: attivazioni e
 * riattivazioni, avvisi visti, calcoli (anche a meta' durante una cancellazione), nomi, pubblicazioni, giri dei dati personali, "Disattiva e cancella"
 * da un dispositivo qualunque, riprese del diario, pulizie del NAS, dispositivi che si spengono e tornano, morti del processo, congelamento, cestino
 * di rete, il tempo che passa. Dopo **ogni** passo: [EraseInvariants.check]. Alla fine tutti tornano a casa e il mondo gira: ogni cancellazione
 * arriva in fondo ([EraseInvariants.final]).
 */
class EraseInvariantsPropertyTest {

  /** Quanto le storie hanno esercitato davvero (una proprieta' che non cancella mai niente non prova niente). */
  private var started = 0
  private var finished = 0
  private var withDeviceAway = 0
  private var restartsDuringErase = 0

  @Test
  fun proprietaSuStorieACaso() = runTest {
    repeat(STORIES) { story -> runStory(story) }
    assertWithMessage("cancellazioni partite").that(started).isAtLeast(STORIES)
    assertWithMessage("cancellazioni finite").that(finished).isAtLeast(STORIES / 2)
    assertWithMessage("cancellazioni con un dispositivo lontano").that(withDeviceAway).isAtLeast(STORIES / 4)
    assertWithMessage("morti del processo durante una cancellazione").that(restartsDuringErase).isAtLeast(STORIES / 4)
  }

  private suspend fun runStory(seed: Int) {
    val random = Random(seed * 7919L + 13)
    val world = EraseWorld()
    val invariants = EraseInvariants(world)
    world.maxFilesPerRound = 1 + random.nextInt(6)
    try {
      val devices = world.activeFamily(*Array(2 + random.nextInt(3)) { "D$it" }, invariants = invariants)
      var n = 100
      repeat(STEPS) { step ->
        val device = devices[random.nextInt(devices.size)]
        val label = "storia $seed, passo $step, ${device.name}"
        when (random.nextInt(16)) {
          0 -> device.online = !device.online
          1 -> {
            if (device.journal.stored?.done == false) restartsDuringErase++
            device.restart()
          }
          2 -> if (random.nextInt(3) == 0) world.frozen = !world.frozen
          3 -> device.beginCompute()
          4 -> device.endCompute(n++)
          5 -> {
            val name = "Nome${n}x"
            if (device.name(n++, name)) invariants.named(device.generation(), name, device)
          }
          6 -> device.publish()
          7, 8 -> device.personalRound()
          9 -> if (device.switch.currentState().let { it is FacesState.On || it is FacesState.AwaitingNotice }) {
            val outcome = device.confirmErase(if (random.nextBoolean()) PeopleAlbumsChoice.KEEP_PHOTOS else PeopleAlbumsChoice.DROP_RULE)
            if (outcome == dev.pampa.mycloudgallery.core.faces.contract.EraseStart.Started) {
              started++
              if (devices.any { !it.online }) withDeviceAway++
            }
          }
          10 -> device.resume()
          11 -> device.janitor.run(5.minutes)
          12 -> when (device.switch.currentState()) {
            FacesState.Off -> device.activate()
            is FacesState.AwaitingNotice -> device.acceptNotice()
            else -> Unit
          }
          13 -> world.clock.advanceMillis(random.nextInt(20) * 60_000L)
          14 -> if (random.nextInt(4) == 0) world.recycleBin = !world.recycleBin
          else -> device.holdNext = random.nextInt(2)
        }
        invariants.check(label)
      }
      // Tutti a casa, niente congelamento: il mondo gira finche' ogni cancellazione e' finita.
      world.frozen = false
      world.binCopies.clear()
      devices.forEach {
        it.online = true
        it.holdNext = 0
      }
      repeat(15) { world.turn(invariants) }
      for (device in devices) {
        val journal = device.journal.stored ?: continue
        invariants.final("storia $seed, fine ${device.name}", device)
        assertWithMessage("storia $seed: la cancellazione di ${device.name} non arriva in fondo con tutti a casa").that(journal.done).isTrue()
        finished++
      }
    } finally {
      world.close()
    }
  }

  private companion object {
    const val STORIES = 60
    const val STEPS = 120
  }
}
