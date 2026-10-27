package dev.pampa.mycloudgallery.core.faces.switch

import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.model.FacesSetting
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// LA FRESCHEZZA DELLO STATO (docs/fase8-progetto.md D30, §2.8 "Gli altri dispositivi"; `FacesSwitch.fresh`). Nessun dato dei volti si pubblica
// se questo processo non ha fatto da poco un giro **completo** dei dati personali e se la voce `faces` non e' cambiata da allora: una testa dei
// volti della generazione G esce solo con uno stato che, meno di un quarto d'ora fa, diceva "attiva, generazione G". E' cio' che impedisce a un
// dispositivo tornato dopo giorni di ripopolare il NAS con una generazione che l'account ha gia' cancellato (E-F32, E-F33).

/**
 * Lo stato di freschezza di UN interruttore. [completed] la accende (un giro completo dei dati personali in questo processo, con la voce di
 * quel momento); smette di valere dopo [freshMillis] **sull'orologio monotono che conta anche il sonno del telefono** ([FacesClock], di norma
 * `elapsedRealtime`) o appena la voce di adesso non e' piu' quella vista dal giro. Una scelta fatta qui (un'attivazione) la spegne: finche' un
 * giro non la rilegge dal registro, la voce nuova non e' confermata.
 */
internal class FacesFreshness(
  private val clock: FacesClock,
  private val freshMillis: Long,
  private val currentSetting: () -> FacesSetting?,
) {
  private class Round(val atNanos: Long, val setting: FacesSetting?)

  @Volatile private var round: Round? = null
  private val ticks = MutableStateFlow(0L)

  /** Un giro completo dei dati personali e' appena finito in questo processo: la voce che ha visto e' quella di adesso. */
  fun completed() {
    round = Round(clock.monotonicNanos(), currentSetting())
    tick()
  }

  /** Dimentica il giro (fermata dell'interruttore). */
  fun clear() {
    round = null
    tick()
  }

  /** Qualcosa e' cambiato (la voce, il tempo): chi guarda [flow] rilegge. */
  fun tick() {
    ticks.update { it + 1 }
  }

  /** Vero adesso, calcolato adesso: mai un valore in cache che il sonno del telefono ha reso vecchio. */
  fun isFresh(): Boolean {
    val seen = round ?: return false
    val age = clock.monotonicNanos() - seen.atNanos
    if (age < 0 || age >= freshMillis * NANOS_PER_MILLI) return false
    return seen.setting == currentSetting()
  }

  /** Quanti millisecondi mancano alla scadenza del giro, o null se non c'e' un giro valido (serve a chi spegne il flusso al momento giusto). */
  fun millisLeft(): Long? {
    val seen = round ?: return null
    val left = freshMillis - (clock.monotonicNanos() - seen.atNanos) / NANOS_PER_MILLI
    return if (left > 0) left else null
  }

  val flow: StateFlow<Boolean> = ReadThroughFlow(ticks) { isFresh() }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
  }
}

/**
 * Un [StateFlow] il cui valore si **calcola a ogni lettura**: `.value` e' sempre quello di adesso (anche dopo ore di sonno, quando nessun
 * timer e' scattato), e chi raccoglie riceve il valore nuovo a ogni [trigger]. Non e' una cache: e' una vista.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class ReadThroughFlow<T>(private val trigger: StateFlow<*>, private val read: () -> T) : StateFlow<T> {
  override val value: T get() = read()

  override val replayCache: List<T> get() = listOf(read())

  override suspend fun collect(collector: FlowCollector<T>): Nothing {
    trigger.map { read() }.distinctUntilChanged().collect(collector)
    throw IllegalStateException("Un StateFlow non finisce.")
  }
}
