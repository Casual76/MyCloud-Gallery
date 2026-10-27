package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.compute.SimMedia
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.erase.Death
import java.util.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// UN GIRO DEL BANCO (docs/prova-500.md Parte F): un mondo, da 3 a 7 passi dell'utente e del sistema, ognuno con i suoi colpi; dopo ogni passo i guasti si
// tolgono e il mondo lavora finche' non ha piu' niente da fare; dopo ogni morte, a fine passo e in fondo al giro si controllano gli invarianti. Ripetibile:
// stesso seme, stessi passi, stessi colpi.

/** Chi fornisce il NAS di un giro: il finto, il simulatore SMB o il NAS vero. */
internal interface ChaosTargetFactory {
  val name: String
  val capabilities: Capabilities

  /** Il NAS di un mondo nuovo. */
  fun create(world: ChaosWorld): ChaosNas
}

internal class ChaosConfig(
  val capabilities: Capabilities = Capabilities.MEMORY,
  val minDevices: Int = 2,
  val maxDevices: Int = 3,
  val minSteps: Int = 3,
  val maxSteps: Int = 7,
  /** Quanti giri al piu' per assestarsi dopo un passo. */
  val settleTurns: Int = 10,
  val finalTurns: Int = 24,
  /** Sul NAS vero: quanto si aspetta davvero fra un giro del mondo e l'altro (gli handle di una connessione morta li chiude il NAS, non noi). */
  val settlePauseMs: Long = 0,
  /**
   * A fine giro tutti i dispositivi ripartono (la ripresa dopo un riavvio, che e' cio' che il §5.5 chiama "le riprese"): un corpo calcolato dai byte del telefono prima che il media
   * arrivasse sul NAS si rimette in coda solo al riavvio dopo (O2 del §F6), e fino ad allora gli altri dispositivi lo ricalcolano.
   */
  val restartAtEnd: Boolean = true,
  /** Piu' colpi per passo e piu' vicini (il NAS vero: ogni giro costa decine di secondi). */
  val dense: Boolean = false,
  /** Un errore introdotto apposta (solo `FacesChaosMutationTest`). */
  val mutant: Mutant? = null,
)

internal class RoundReport(
  val index: Int,
  val seed: Long,
  val violations: List<Violation>,
  val fired: List<FiredBlow>,
  val story: List<String>,
) {
  val ok: Boolean get() = violations.isEmpty()

  fun describe(): String = buildString {
    appendLine("giro $index, seme $seed: ${violations.size} violazioni, ${fired.size} interruzioni, ${fired.count { it.blow.kind == FaultKind.KILL }} morti del processo")
    story.forEach { appendLine("  $it") }
    if (fired.isNotEmpty()) appendLine("  interruzioni: " + fired.joinToString("; ") { it.toString() })
    violations.forEach { appendLine("  $it") }
  }
}

internal class ChaosRound(
  private val index: Int,
  private val seed: Long,
  private val factory: ChaosTargetFactory,
  private val config: ChaosConfig,
  private val stats: ChaosStats,
) {
  private val random = Random(seed)
  private val story = ArrayList<String>()
  private val inj = ChaosInjector(stats)
  private lateinit var world: ChaosWorld
  private lateinit var verifier: ChaosVerifier
  private lateinit var snapshots: ChaosSnapshots
  private var deaths = 0
  private var nameCounter = 0

  private companion object {
    const val MAX_RESTARTS = 12
  }

  suspend fun run(): RoundReport {
    stats.rounds++
    world = ChaosWorld(seed, inj, config.mutant) { factory.create(it) }
    verifier = ChaosVerifier(world)
    snapshots = ChaosSnapshots(world, verifier)
    try {
      setup()
      val steps = config.minSteps + random.nextInt(config.maxSteps - config.minSteps + 1)
      repeat(steps) { n -> step(n) }
      finish()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      verifier.add(Invariant.BENCH, "giro", "il banco e' caduto: ${e.javaClass.simpleName}: ${e.message}\n" + e.stackTrace.take(12).joinToString("\n") { "      $it" })
    } finally {
      inj.disarm()
      runCatching { world.close() }
    }
    return RoundReport(index, seed, verifier.found.toList(), inj.fired.toList(), story + world.trace)
  }

  // --- Il mondo di partenza ---------------------------------------------------------------------------------------------------

  private var autoActivate = true

  private suspend fun setup() {
    val devices = config.minDevices + random.nextInt(config.maxDevices - config.minDevices + 1)
    world.nas.maxFilesPerRound = 1 + random.nextInt(6)
    repeat(devices) { world.device("D$it") }
    world.media.populate(privateCount = 4 + random.nextInt(7), commonCount = random.nextInt(4))
    world.plantUntouchable()
    // A volte il giro parte da Persone spenta: la prima attivazione e' allora un passo come gli altri, con i suoi colpi.
    autoActivate = random.nextInt(10) >= 3
    if (autoActivate) {
      val first = world.devices.first()
      first.activate()
      repeat(2) { world.devices.forEach { it.personalRound() } }
      world.devices.drop(1).forEach { it.acceptNotice() }
      world.devices.forEach { it.personalRound() }
    }
    story += "mondo: ${world.devices.size} dispositivi, ${world.media.nas.mediaIn(world.media.account.privateShare).size} media privati, " +
      "${world.media.nas.mediaIn(world.nas.commonShare).size} comuni, ${if (autoActivate) "Persone accesa" else "Persone spenta"}"
    verifier.check("partenza")
  }

  // --- Un passo ---------------------------------------------------------------------------------------------------------------------

  private fun online(): List<ChaosDevice> = world.devices.filter { it.online }

  private fun active(): List<ChaosDevice> = online().filter { it.isActive() }

  private fun feasible(kind: StepKind): Boolean = when (kind) {
    StepKind.COMPUTE, StepKind.COMPUTE_FLIP -> active().isNotEmpty() && (kind != StepKind.COMPUTE_FLIP || active().size >= 2 || true)
    StepKind.SYNC, StepKind.NIGHTLY, StepKind.NAME, StepKind.RESTART -> online().isNotEmpty() && (kind == StepKind.SYNC || kind == StepKind.RESTART || active().isNotEmpty())
    StepKind.ERASE -> online().any { it.switch.currentState().let { s -> s is FacesState.On || s is FacesState.AwaitingNotice } }
    StepKind.EXCLUDE -> active().isNotEmpty() || online().any { it.switch.currentState().let { s -> s is FacesState.On && !s.activeHere } }
    StepKind.REACTIVATE -> online().any { it.switch.currentState() == FacesState.Off }
    StepKind.POWER_OFF -> online().size >= 2
    StepKind.POWER_ON -> world.devices.any { !it.online }
    StepKind.RECYCLE -> config.capabilities.recycleBin
    else -> true
  }

  private suspend fun step(n: Int) {
    val kind = if (n == 0 && !autoActivate) StepKind.REACTIVATE else StepKind.pick(random, ::feasible)
    stats.step(kind.label)
    val actor = actorFor(kind)
    world.note("--- passo $n: ${kind.label} su ${actor.name}")
    val actors = if (kind == StepKind.SYNC || kind == StepKind.COMPUTE || kind == StepKind.COMPUTE_FLIP) online().map { it.index } else listOf(actor.index)
    val blows = ChaosPlan.blows(random, kind, if (kind == StepKind.COMPUTE_FLIP) listOf(actor.index) else actors, config.capabilities, config.dense)
    story += "passo $n: ${kind.label} su ${actor.name}" + if (blows.isEmpty()) "" else ", colpi: " + blows.joinToString("; ")
    inj.arm(blows)
    try {
      guard("passo $n (${kind.label})") { body(kind, actor) }
    } finally {
      inj.disarm()
    }
    val still = settle(config.settleTurns, "dopo il passo $n")
    verifier.check("passo $n (${kind.label})")
    stats.quietChecks++
    snapshots.check("passo $n (${kind.label})")
    if (random.nextInt(4) == 0) idleRestart(n, still)
  }

  private fun actorFor(kind: StepKind): ChaosDevice {
    val pool = when (kind) {
      StepKind.COMPUTE, StepKind.COMPUTE_FLIP, StepKind.NAME, StepKind.NIGHTLY -> active()
      StepKind.ERASE -> online().filter { it.switch.currentState().let { s -> s is FacesState.On || s is FacesState.AwaitingNotice } }
      StepKind.EXCLUDE -> active().ifEmpty { online() }
      StepKind.REACTIVATE -> online().filter { it.switch.currentState() == FacesState.Off }
      StepKind.POWER_OFF -> online()
      StepKind.POWER_ON -> world.devices.filter { !it.online }
      else -> online()
    }
    return pool.ifEmpty { world.devices }.let { it[random.nextInt(it.size)] }
  }

  private suspend fun body(kind: StepKind, actor: ChaosDevice) {
    when (kind) {
      StepKind.COMPUTE, StepKind.COMPUTE_FLIP -> {
        val order = listOf(actor) + active().filter { it !== actor && random.nextInt(3) == 0 }
        for (device in order) {
          if (!device.online || !device.isActive()) continue
          device.prepareCompute()
          device.computeAll()
          device.replica.sync(1.hours)
        }
      }
      StepKind.SYNC -> turn(withCompute = false)
      StepKind.NAME -> {
        repeat(1 + random.nextInt(2)) { actor.name("Persona${++nameCounter}x") }
        actor.publisher.flush(FlushReason.DEBOUNCE)
      }
      StepKind.NEW_MEDIA -> {
        repeat(1 + random.nextInt(3)) {
          when (random.nextInt(4)) {
            0 -> world.media.add(world.nas.commonShare)
            1 -> {
              val phone = world.media.phoneOnly()
              actor.sim.phone[phone.key] = phone
            }
            else -> world.media.add(world.media.account.privateShare)
          }
        }
        // Un media del telefono che arriva sul NAS (backup riuscito): passa dal telefono all'area privata.
        actor.sim.phone.values.firstOrNull { random.nextInt(3) == 0 }?.let {
          actor.sim.phone.remove(it.key)
          // Sul NAS l'anteprima c'e' (la prepara il backup e l'indice la attesta): da ora la vedono tutti i dispositivi dell'account.
          val onNas = SimMedia(it.key, it.kind, it.month, it.source, it.scene, it.hq, it.document, it.cutout, it.hidden, previewOnNas = true)
          world.media.nas.moveToNas(onNas, world.media.account.privateShare)
        }
      }
      StepKind.ERASE -> {
        val choice = if (random.nextBoolean()) PeopleAlbumsChoice.KEEP_PHOTOS else PeopleAlbumsChoice.DROP_RULE
        stats.eraseStarted++
        // Un file che non si toglie (bloccato, aperto da un altro): la cancellazione locale degli altri dispositivi non riesce la prima volta. Il gancio di D30 deve
        // rispondere "fermo" e la testa con `off|G` non esce finche' i dati ci sono (invariante 2 del §2.8).
        world.devices.filter { it !== actor && random.nextInt(3) == 0 }.forEach { it.local.failErase = 1 + random.nextInt(2) }
        actor.confirmErase(choice)
        repeat(2) {
          actor.personalRound()
          actor.eraser.resume(1.hours)
        }
      }
      StepKind.REACTIVATE -> {
        actor.activate()
        actor.personalRound()
        world.devices.filter { it !== actor && it.online }.forEach { it.personalRound() }
      }
      StepKind.POWER_OFF -> {
        actor.online = false
        world.nas.sever(actor.index)
        actor.restart()
      }
      StepKind.POWER_ON -> {
        actor.online = true
        actor.restart()
        actor.personalRound()
        actor.eraser.resume(1.hours)
        actor.janitor.run(5.minutes)
        actor.publisher.flush(FlushReason.RESUME)
      }
      StepKind.FREEZE -> world.nas.frozen = !world.nas.frozen
      StepKind.RECYCLE -> if (world.nas.recycleBin && random.nextBoolean()) world.nas.emptyRecycleBin() else world.nas.recycleBin = !world.nas.recycleBin
      StepKind.TIME -> world.clock.advance((5 + random.nextInt(86)) * 60_000L)
      StepKind.NIGHTLY -> {
        actor.grouping.nightly(10.minutes)
        actor.publisher.flush(FlushReason.MAINTENANCE_FINISHED)
      }
      StepKind.EXCLUDE -> {
        val state = actor.switch.currentState()
        if (state is FacesState.On && !state.activeHere) actor.switch.includeThisDevice() else actor.switch.excludeThisDevice()
      }
      StepKind.RESTART -> {
        actor.restart()
        actor.eraser.resume(1.hours)
      }
    }
  }

  // --- Il mondo che lavora ----------------------------------------------------------------------------------------------------------

  /** Un giro del mondo: ogni dispositivo acceso fa il giro dei dati personali, la ripresa del diario, la pulizia del NAS, il calcolo, la pubblicazione e la replica. */
  private suspend fun turn(withCompute: Boolean = true) {
    for (device in world.devices) {
      if (!device.online) continue
      guard("giro di ${device.name}") {
        device.personalRound()
        if (device.switch.currentState() is FacesState.AwaitingNotice) device.acceptNotice()
        device.eraser.resume(1.hours)
        device.janitor.run(5.minutes)
        if (withCompute && device.isActive()) {
          device.prepareCompute()
          device.computeAll()
        }
        device.publisher.flush(FlushReason.SYNC_ROUND)
        device.replica.sync(1.hours)
      }
    }
    world.clock.advance(60_000)
    if (config.settlePauseMs > 0) delay(config.settlePauseMs)
  }

  /** Senza guasti, il mondo lavora finche' non cambia piu' niente (al piu' [turns] giri). Dice se si e' fermato. */
  private suspend fun settle(turns: Int, phase: String): Boolean {
    inj.disarm()
    var last = fingerprint()
    var still = 0
    repeat(turns) {
      turn()
      val now = fingerprint()
      if (now == last) {
        still++
        if (still >= 2) return true
      } else {
        still = 0
      }
      last = now
    }
    return false
  }

  /** L'impronta del mondo: se non cambia fra due giri non c'e' piu' niente da fare. */
  private fun fingerprint(): String = buildString {
    append(world.nas.paths().filter { it.value.startsWith(".mycloudgallery/faces/") || it.value.startsWith(".mycloudgallery/tmp/") }.size).append('|')
    for (device in world.devices) {
      append(device.switch.currentState().javaClass.simpleName).append(':').append(device.journal.stored?.step?.code).append(':').append(device.journal.stored?.done)
      append(':').append(device.local.gens.size).append(':').append(device.localStore.local.value.toString().length)
      append(':').append(device.personal.dated?.atNas).append(':').append(device.publisher.pending.value)
      val g = device.generation()
      if (g != null) device.local.raw(g)?.let { append(':').append(it.toString()) }
      append(';')
    }
    append(world.heads.snapshot().size)
  }

  /**
   * La morte senza colpo (invariante 11): a lavoro finito il processo di un dispositivo riparte e riprende i lavori dal diario; il NAS (i file dei volti), i diari, i
   * dati su disco e l'outbox devono restare identici. Poi il mondo lavora ancora (se qualcosa si e' mosso lo ha detto l'impronta, non serve altro).
   */
  private suspend fun idleRestart(n: Int, still: Boolean) {
    val device = world.devices.filter { it.online }.let { if (it.isEmpty()) return else it[random.nextInt(it.size)] }
    val before = ChaosIdle.fingerprint(world)
    stats.idleRestarts++
    device.restart()
    guard("ripresa a vuoto dopo il passo $n") {
      device.eraser.resume(1.hours)
      device.publisher.flush(FlushReason.RESUME)
    }
    val after = ChaosIdle.fingerprint(world)
    // Il confronto vale solo se il mondo era fermo (se l'assestamento ha finito i giri a disposizione, il lavoro poteva essere ancora a meta').
    if (still) ChaosIdle.compare(verifier, "ripresa a vuoto dopo il passo $n", before, after)
    settle(config.settleTurns, "ripresa a vuoto")
  }

  // --- Le morti e il resto ----------------------------------------------------------------------------------------------------------

  /** Un blocco di codice sotto prova: la morte del processo ricostruisce chi e' morto; ogni altra eccezione e' una violazione del contratto. */
  private suspend fun guard(label: String, block: suspend () -> Unit) {
    try {
      block()
      // Una morte segnata ma senza eccezione (il colpo e' caduto dentro la sessione SMB, che classifica ogni eccezione): il processo e' comunque morto.
      if (inj.anyDead()) died(label)
    } catch (e: Death) {
      died(label)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      verifier.add(Invariant.CONTRACT, label, "${e.javaClass.simpleName} esce dal codice sotto prova: ${e.message}\n" + e.stackTrace.take(10).joinToString("\n") { "      $it" })
      // Dopo un'eccezione inattesa il mondo e' in uno stato sconosciuto: si riparte comunque dai dati su disco.
      world.devices.filter { inj.isDead(it.index) }.forEach { it.restart() }
    }
  }

  private fun died(label: String) {
    world.note("morte del processo ($label): ${world.devices.filter { inj.isDead(it.index) }.joinToString { it.name }}")
    deaths++
    stats.restarts++
    // Un processo che riparte puo' morire di nuovo all'avvio (un colpo sullo stato locale mentre l'interruttore si assesta): si riparte finche' tutti vivono.
    var attempts = 0
    while (world.devices.any { inj.isDead(it.index) } && attempts++ < MAX_RESTARTS) {
      for (device in world.devices) {
        if (!inj.isDead(device.index)) continue
        world.nas.sever(device.index)
        try {
          device.restart()
        } catch (e: Death) {
          // Morto all'avvio: il ciclo lo fa ripartire.
        }
      }
    }
    stats.midChecks++
    verifier.check("dopo la morte #$deaths ($label)")
  }

  // --- La fine del giro ---------------------------------------------------------------------------------------------------------------

  private suspend fun finish() {
    inj.disarm()
    world.nas.frozen = false
    world.nas.emptyRecycleBin()
    world.nas.recycleBin = false
    for (device in world.devices) {
      if (!device.online) {
        device.online = true
        device.restart()
      }
      device.personal.holdNext = 0
    }
    // Un dispositivo morto lascia la sua unita' fino alla scadenza della prenotazione (1 ora, E-I2): per finire il lavoro il tempo deve passare.
    world.clock.advance(70 * 60_000L)
    // Una notte passa: la manutenzione notturna del custode fa nascere le persone (D20) e pubblica le istantanee.
    for (device in world.devices) {
      guard("manutenzione di fine giro di ${device.name}") {
        if (device.online && device.isActive()) {
          device.grouping.nightly(10.minutes)
          device.publisher.flush(FlushReason.MAINTENANCE_FINISHED)
        }
      }
    }
    if (config.restartAtEnd) world.devices.forEach { it.restart() }
    settle(config.finalTurns, "fine")
    story += "fine: ${world.devices.joinToString { "${it.name}=${it.switch.currentState().javaClass.simpleName}" }}"
    verifier.check("rilettura finale")
    stats.quietChecks++
    snapshots.check("rilettura finale")
    snapshots.converged("rilettura finale")
    verifier.erasedAtEnd("rilettura finale")
    val (refused, requests) = world.nas.commonProbe(world.devices.first())
    if (!refused || requests != 0) verifier.add(Invariant.PLACE, "rilettura finale", "una porta dei volti sull'area comune non ha rifiutato ($refused, $requests richieste)")
    for (path in world.nas.paths()) {
      world.nas.bytes(path)?.let {
        stats.filesHashed++
        stats.bytesHashed += it.size
      }
    }
  }
}
