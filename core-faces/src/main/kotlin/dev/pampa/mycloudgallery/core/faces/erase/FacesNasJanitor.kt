package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La generazione corrente dell'account per chi cancella sul NAS (D31; la porta `FacesEraseFacts` di core-nasops, legata da `:app`): nota solo con lo
 * stato **fresco** (un giro completo dei dati personali da poco, D30) e una voce che questa app capisce; altrimenti non si sa e sul NAS non si
 * cancella niente. Accesa = la sua generazione; spenta o assente (con lo stato fresco) = nessuna generazione corrente. Le scelte fatte qui e non
 * ancora datate contano (la voce si legge dallo stato effettivo).
 */
class FacesAccountFacts(
  private val switch: FacesSwitch,
  private val settings: FacesPersonalSettings,
  private val identity: FacesEraseIdentity,
) {
  sealed interface Current {
    data class Known(val generation: FaceGeneration?) : Current {
      override fun toString(): String = "Current.Known(${generation != null})"
    }

    data object Unknown : Current
  }

  fun current(): Current {
    if (!switch.fresh.value) return Current.Unknown
    return when (val setting = settings.current()) {
      is FacesSetting.On -> Current.Known(setting.generation)
      is FacesSetting.Off, null -> Current.Known(null)
      FacesSetting.Unknown -> Current.Unknown
    }
  }

  /** D31 (b): un dispositivo **dell'account** revocato o perso nel registro accettato (nel dubbio: no). */
  fun revokedOrLost(deviceId: String): Boolean {
    val registry = identity.registry() ?: return false
    return EraseRules.revokedOrLost(registry, identity.deviceId, deviceId)
  }

  override fun toString(): String = "FacesAccountFacts"
}

/** Il resoconto di un giro di [FacesNasJanitor] (solo numeri e codici: D37). */
data class JanitorReport(
  /** File dei volti tolti in questo giro. */
  val removedFiles: Int,
  /** Generazioni non correnti trovate sul NAS (`FACES_FOREIGN_GENERATION`). */
  val foreignGenerations: Int,
  /** Cartelle di dispositivi revocati o persi della generazione corrente ancora da togliere o tolte in questo giro. */
  val revokedDevices: Int,
  /** Perche' il giro si e' fermato prima della fine; null = tutto in ordine. */
  val waiting: EraseWait?,
  /** Il giro non e' partito: stato non fresco o voce sconosciuta (nel dubbio niente). */
  val skipped: Boolean = false,
)

/**
 * La pulizia del NAS che **ogni** dispositivo dell'account fa con lo stato fresco (§2.8 "Gli altri dispositivi" punto 3, E-F9, E-F32, E-F33, E-F35):
 * le generazioni non correnti si tolgono intere (`NOT_CURRENT`: anche la propria cartella di una generazione cancellata, se c'e' ancora) e, della
 * generazione corrente, solo le cartelle dei dispositivi dell'account revocati o persi (`DEVICE_REVOKED`). Basta un dispositivo che torna a casa
 * perche' il NAS si pulisca, anche se quello che ha cancellato non ci torna piu'. `:app` la chiama dopo il giro dei dati personali (P13).
 *
 * Sempre per generazione, dispositivo e motivo: i percorsi e la regola li applica core-nasops (`FacesEraseOps`, D31). Nel dubbio (stato non fresco,
 * voce sconosciuta: E-F12) non parte.
 */
class FacesNasJanitor(
  private val nas: FacesEraseOps,
  private val facts: FacesAccountFacts,
  private val identity: FacesEraseIdentity,
  private val heads: PersonalHeadsView,
  private val clock: FacesClock,
) {
  private val mutex = Mutex()

  /** Le cartelle dei revocati gia' tolte in questo processo (generazione, dispositivo): non si rielencano a ogni giro. */
  private val cleared = HashSet<Pair<FaceGeneration, String>>()

  suspend fun run(budget: Duration): JanitorReport = mutex.withLock {
    val current = when (val c = facts.current()) {
      is FacesAccountFacts.Current.Known -> c.generation
      FacesAccountFacts.Current.Unknown -> return@withLock JanitorReport(0, 0, 0, null, skipped = true)
    }
    val deadline = clock.monotonicNanos() + budget.inWholeNanoseconds.coerceAtLeast(0)
    fun remaining(): Duration = (deadline - clock.monotonicNanos()).coerceAtLeast(0).nanoseconds
    var removed = 0
    var waiting: EraseWait? = null
    val listed = attempt { nas.generations() } ?: return@withLock JanitorReport(0, 0, 0, EraseWait.NAS)
    val foreign = listed.filter { it != current }
    // E-F9: una generazione che una testa recente dice ancora accesa aspetta (la voce che vince non e' ancora arrivata a tutti).
    val claims = attempt { heads.latestValueOf(FacesSettingCodec.KEY) } ?: return@withLock JanitorReport(0, foreign.size, 0, EraseWait.NAS)
    val nasNow = clock.nasNowMillis()
    loop@ for (generation in foreign) {
      // Senza nessuna testa nota non si sa chi dice cosa: si aspetta che le teste arrivino.
      if (claims.isEmpty() || EraseRules.recentlyClaimed(claims, generation, nasNow)) continue
      when (val r = rounds(remaining = ::remaining, deadline = deadline) { nas.eraseGeneration(generation, NasEraseReason.NOT_CURRENT, remaining()) }) {
        is Outcome.Done -> removed += r.removed
        is Outcome.Stopped -> {
          removed += r.removed
          waiting = r.wait
          if (r.wait != null) break@loop
        }
      }
    }
    var revoked = 0
    if (current != null && waiting == null) {
      val barred = attempt { identity.registry()?.let { EraseRules.barredOfAccount(it, identity.deviceId) } }.orEmpty()
      for (device in barred) {
        if ((current to device) in cleared) continue
        revoked++
        when (val r = rounds(remaining = ::remaining, deadline = deadline) { nas.eraseDevice(current, device, NasEraseReason.DEVICE_REVOKED, remaining()) }) {
          is Outcome.Done -> {
            removed += r.removed
            cleared += current to device
          }
          is Outcome.Stopped -> {
            removed += r.removed
            waiting = r.wait
            if (r.wait != null) break
          }
        }
      }
    }
    JanitorReport(removed, foreign.size, revoked, waiting)
  }

  private sealed interface Outcome {
    class Done(val removed: Int) : Outcome

    /** Fermo: [wait] il motivo (null = tempo finito o "non ammesso", che non e' un'attesa). */
    class Stopped(val removed: Int, val wait: EraseWait?) : Outcome
  }

  private suspend fun rounds(remaining: () -> Duration, deadline: Long, round: suspend () -> NasEraseRound): Outcome {
    var removed = 0
    repeat(MAX_ROUNDS) {
      when (val r = attempt { round() } ?: NasEraseRound.Waiting(EraseWait.NAS)) {
        is NasEraseRound.Done -> return Outcome.Done(removed + r.removedFiles)
        is NasEraseRound.More -> {
          removed += r.removedFiles
          if (clock.monotonicNanos() >= deadline || r.removedFiles == 0 || remaining() == Duration.ZERO) return Outcome.Stopped(removed, null)
        }
        is NasEraseRound.Waiting -> return Outcome.Stopped(removed, r.reason)
        NasEraseRound.NotAllowed -> return Outcome.Stopped(removed, null)
        NasEraseRound.Unsure -> return Outcome.Stopped(removed, EraseWait.NAS)
      }
    }
    return Outcome.Stopped(removed, null)
  }

  private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  override fun toString(): String = "FacesNasJanitor"

  private companion object {
    const val MAX_ROUNDS = 64
  }
}
