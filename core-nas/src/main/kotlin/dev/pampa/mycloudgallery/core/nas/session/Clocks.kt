package dev.pampa.mycloudgallery.core.nas.session

import android.os.SystemClock
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Un orologio monotono in millisecondi (non e' un'ora: serve solo a misurare intervalli). */
fun interface MonotonicClock {
  fun nowMillis(): Long
}

/** L'orologio monotono di Android, che continua a contare anche quando il telefono dorme. */
object ElapsedRealtimeClock : MonotonicClock {
  override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}

/**
 * L'ora del NAS (`NasClock` di core-trust, formato-nas.md §6.1): l'ora letta alla NEGOTIATE SMB ancorata
 * all'orologio monotono del dispositivo. L'orologio di sistema del telefono non entra mai nel conto (E-P9).
 *
 * L'ancora la mette la sessione a ogni NEGOTIATE, **dopo** il controllo del GUID (un NAS estraneo non sposta la
 * nostra ora) e solo con SMB 3.1.1 firmato, perche' l'hash di pre-autenticazione copre anche l'ora. Istanti fuori
 * da 2020–2100 si scartano (orologio del NAS azzerato). Un salto tra due NEGOTIATE oltre [jumpThresholdMs] finisce
 * in [lastJumpMs] (per la Diagnostica: cestino e prenotazioni vanno sospesi, formato-nas.md §6.1).
 *
 * Senza un'ancora [nowMillis] lancia `NasIoException`: chi deve firmare apre prima una sessione
 * (`SmbNasSessionManager.ensureFreshClock`).
 */
class SessionNasClock(
  private val monotonic: MonotonicClock,
  private val jumpThresholdMs: Long = DEFAULT_JUMP_THRESHOLD_MS,
) : NasClock {

  private class Anchor(val nasMs: Long, val monotonicMs: Long)

  @Volatile
  private var anchor: Anchor? = null

  private val jumps = MutableStateFlow<Long?>(null)

  /** L'ultimo salto osservato (ora nuova meno ora prevista, ms), o null. */
  val lastJumpMs: StateFlow<Long?> = jumps.asStateFlow()

  /** Da quanto (ms, orologio monotono) e' stata letta l'ora del NAS; null se mai. */
  fun ageMillis(): Long? = anchor?.let { monotonic.nowMillis() - it.monotonicMs }

  /** Registra l'ora del NAS letta alla NEGOTIATE. Falso se l'istante non e' plausibile (ancora non cambiata). */
  fun anchor(nasTimeMs: Long, atMonotonicMs: Long): Boolean {
    if (nasTimeMs !in PLAUSIBLE_FROM until PLAUSIBLE_UNTIL) return false
    anchor?.let { previous ->
      val predicted = previous.nasMs + (atMonotonicMs - previous.monotonicMs)
      val jump = nasTimeMs - predicted
      if (abs(jump) > jumpThresholdMs) jumps.value = jump
    }
    anchor = Anchor(nasTimeMs, atMonotonicMs)
    return true
  }

  /** L'ora del NAS adesso, o null se non c'e' ancora un'ancora. */
  fun nowMillisOrNull(): Long? = anchor?.let { it.nasMs + (monotonic.nowMillis() - it.monotonicMs) }

  override fun nowMillis(): Long = nowMillisOrNull() ?: throw NasIoException(NasError.Unexpected(null))

  companion object {
    /** 2020-01-01T00:00:00Z. */
    const val PLAUSIBLE_FROM: Long = 1_577_836_800_000L

    /** 2100-01-01T00:00:00Z. */
    const val PLAUSIBLE_UNTIL: Long = 4_102_444_800_000L

    const val DEFAULT_JUMP_THRESHOLD_MS: Long = 5 * 60 * 1000L
  }
}
