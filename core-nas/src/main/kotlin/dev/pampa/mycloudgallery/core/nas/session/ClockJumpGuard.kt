package dev.pampa.mycloudgallery.core.nas.session

import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Il salto dell'ora del NAS (D32, E-N17) **oltre la vita del processo** (R7 della revisione 4A).
 *
 * [SessionNasClock] vede un salto solo fra due ancore dello stesso processo, e lo dimentica con lui: se l'ora del NAS torna
 * indietro mentre l'app e' chiusa (l'NTP sistemato, la batteria dell'orologio) il processo nuovo prende l'ora nuova come
 * prima ancora e riparte a scrivere. Qui ogni ancora si confronta con l'**ultima ancora salvata** ([Store], un file in
 * `noBackupFilesDir` scelto da `:app`), anche di un processo di prima:
 *
 * - **stesso avvio del telefono** ([Reference.bootId] uguale e orologio monotono che non e' tornato indietro): l'ora prevista
 *   e' l'ancora salvata piu' il tempo monotono trascorso, con la soglia di D32 (5 minuti);
 * - **un riavvio in mezzo** (o l'avvio non noto): l'unico riferimento che attraversa un riavvio e' l'orologio del telefono, che
 *   qui serve solo come metro largo: soglia di 5 minuti piu' un decimillesimo dell'intervallo (circa 9 s al giorno), per la
 *   deriva dell'orologio del NAS. L'ora del telefono non entra mai in una decisione sui file (E-P9): decide solo "sospendere".
 *
 * Un salto oltre la soglia **sospende** prenotazioni, pubblicazione dell'indice e, nella 4B, pulizia del cestino e dei propri
 * file ([blocked]), e la sospensione si salva: resta anche dopo un riavvio dell'app. Finisce solo con il **gesto
 * dell'utente** ([accept], dalla Diagnostica) o dopo **24 ore di ora stabile** del NAS (ancore successive senza un altro salto,
 * contate in ora del NAS da quella che ha mostrato il salto). Un salto nuovo durante l'attesa la fa ripartire.
 *
 * Meglio fermarsi una volta di troppo: un orologio del telefono spostato a mano fra due avvii puo' sospendere le scritture
 * per un giorno (o fino al gesto), mai il contrario.
 */
class ClockJumpGuard(
  private val store: Store,
  private val thresholdMs: Long = SessionNasClock.DEFAULT_JUMP_THRESHOLD_MS,
  private val stableMs: Long = STABLE_MS,
) {

  /** Il riferimento del telefono al momento di un'ancora. */
  data class Reference(
    /** L'orologio monotono dall'avvio del telefono (`SystemClock.elapsedRealtime`), lo stesso dell'ancora. */
    val elapsedMs: Long,
    /** Il numero dell'avvio del telefono (`Settings.Global.BOOT_COUNT`), null se non si sa. */
    val bootId: Long?,
    /** L'orologio del telefono allo stesso istante: solo metro largo fra due avvii. */
    val wallMs: Long,
  )

  /** Cio' che si salva: l'ultima ancora con il suo riferimento e il salto che sta sospendendo le scritture. */
  data class Saved(
    val nasMs: Long,
    val elapsedMs: Long,
    val bootId: Long?,
    val wallMs: Long,
    /** Il salto in corso (ora vista meno ora prevista, ms); null = nessuna sospensione. */
    val jumpMs: Long? = null,
    /** L'ora del NAS da cui si contano le 24 ore di ora stabile. */
    val stableSinceNasMs: Long? = null,
  )

  /** Dove si salva (`:app`: un file in `noBackupFilesDir`, escluso dal backup e tolto dall'azzeramento). */
  interface Store {
    fun load(): Saved?

    fun save(saved: Saved)
  }

  private val lock = Any()
  private var loaded = false
  private var state: Saved? = null
  private val jumps = MutableStateFlow<Long?>(null)

  /** Il salto che sospende le scritture adesso (ms), o null: per la Diagnostica e il cancello delle scritture. */
  val jump: StateFlow<Long?> = jumps.asStateFlow()

  /** Vero se le scritture automatiche sul NAS vanno sospese (D32). Legge lo stato salvato alla prima chiamata. */
  val blocked: Boolean get() = synchronized(lock) { current()?.jumpMs != null }

  /** Un'ancora nuova ([nasMs], ora del NAS) presa con il riferimento [ref]. */
  fun onAnchor(nasMs: Long, ref: Reference) {
    synchronized(lock) {
      val previous = current()
      var jumpMs = previous?.jumpMs
      var since = previous?.stableSinceNasMs
      if (previous != null) {
        val sameBoot = previous.bootId != null && previous.bootId == ref.bootId && ref.elapsedMs >= previous.elapsedMs
        val predicted: Long
        val tolerance: Long
        if (sameBoot) {
          predicted = previous.nasMs + (ref.elapsedMs - previous.elapsedMs)
          tolerance = thresholdMs
        } else {
          val gap = ref.wallMs - previous.wallMs
          predicted = previous.nasMs + gap
          tolerance = thresholdMs + abs(gap) / DRIFT_DIVISOR
        }
        val observed = nasMs - predicted
        if (abs(observed) > tolerance) {
          jumpMs = observed
          since = nasMs
        } else if (jumpMs != null && since != null && nasMs - since >= stableMs) {
          jumpMs = null
          since = null
        }
      }
      write(Saved(nasMs, ref.elapsedMs, ref.bootId, ref.wallMs, jumpMs, since))
    }
  }

  /** Il gesto dell'utente ("L'ora del NAS e' giusta, continua"): la sospensione finisce. Falso se non c'era. */
  fun accept(): Boolean = synchronized(lock) {
    val saved = current() ?: return false
    if (saved.jumpMs == null) return false
    write(saved.copy(jumpMs = null, stableSinceNasMs = null))
    true
  }

  private fun current(): Saved? {
    if (!loaded) {
      state = runCatching { store.load() }.getOrNull()
      loaded = true
      jumps.value = state?.jumpMs
    }
    return state
  }

  private fun write(next: Saved) {
    state = next
    jumps.value = next.jumpMs
    // Un salvataggio fallito non toglie la sospensione in memoria: si riprova all'ancora dopo.
    runCatching { store.save(next) }
  }

  companion object {
    /** D32: 24 ore di ora stabile. */
    const val STABLE_MS: Long = 24L * 60 * 60 * 1000

    /** La deriva ammessa fra due avvii: un decimillesimo dell'intervallo (circa 9 s al giorno). */
    const val DRIFT_DIVISOR: Long = 10_000
  }
}

/** Uno [ClockJumpGuard.Store] in memoria (test, o prima che `:app` ne dia uno su file). */
class InMemoryClockJumpStore(@Volatile var saved: ClockJumpGuard.Saved? = null) : ClockJumpGuard.Store {
  override fun load(): ClockJumpGuard.Saved? = saved

  override fun save(saved: ClockJumpGuard.Saved) {
    this.saved = saved
  }
}
