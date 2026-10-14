package dev.pampa.mycloudgallery.core.nasmedia.contract

import dev.pampa.mycloudgallery.core.model.FreezeReason
import kotlinx.coroutines.flow.StateFlow

// Il CONGELAMENTO per cambiamenti di massa (docs/fase4-progetto.md D10 con la risposta Q6 dell'utente, §2.3; C11 di
// docs/sicurezza.md; E-S11, prova F9). Tipi e soglie di P0; `MassChangeGuard.judge` e la persistenza di P5.

/**
 * Le soglie di D10: in una scansione di una share, **ciascuno** fra spariti, cambiati e "non piu' media" (es. rinominati in
 * `.locked`) congela se e' almeno `max(50, 5% dei file presenti)`. Meglio fermarsi una volta di troppo.
 */
object MassChangeRule {
  const val MIN_COUNT: Int = 50
  const val MIN_PERCENT: Int = 5

  /** La soglia per una share con [knownPresent] file presenti (arrotondata per eccesso). */
  fun threshold(knownPresent: Int): Int {
    require(knownPresent >= 0) { "Conteggio negativo." }
    val percent = (knownPresent.toLong() * MIN_PERCENT + 99) / 100
    return maxOf(MIN_COUNT.toLong(), percent).toInt()
  }
}

/** I conteggi di un giro, confrontati con cio' che il catalogo sapeva prima. */
data class ScanDiff(val knownPresent: Int, val vanished: Int, val changed: Int, val notMedia: Int) {
  init {
    require(knownPresent >= 0 && vanished >= 0 && changed >= 0 && notMedia >= 0) { "Conteggi negativi." }
  }
}

/** Una share congelata: perche' (il conteggio piu' grave), da quando (ora del NAS) e i numeri visti. */
data class Freeze(
  val reason: FreezeReason,
  val sinceNasMillis: Long,
  val diff: ScanDiff,
)

/**
 * Il numero che l'utente vede e che deve riscrivere per scongelare (la "doppia conferma" di D10): il conteggio della
 * ragione del congelamento.
 */
val Freeze.headlineCount: Int
  get() = when (reason) {
    FreezeReason.VANISHED -> diff.vanished
    FreezeReason.CHANGED -> diff.changed
    FreezeReason.NOT_MEDIA -> diff.notMedia
  }

/** Il giudizio su un giro. */
sealed interface FreezeVerdict {
  data object Proceed : FreezeVerdict

  data class Freeze(val reason: FreezeReason, val diff: ScanDiff) : FreezeVerdict
}

/**
 * Il gesto dell'utente che scongela (D10): "Ho controllato, continua", con la **doppia conferma** del conteggio mostrato
 * (l'utente conferma il numero che ha visto). Si costruisce solo dalla UI (P11); un dato letto dal NAS non scongela mai.
 */
data class UserGesture(val confirmedCount: Int)

/**
 * Lo stato di congelamento di una share (salvato nel catalogo, P2 e P5). Lo leggono: il pubblicatore dell'indice
 * (`IndexWriteGate` composto da :app, D10: nessuna scrittura automatica sul NAS), i lavori (`WaitReason.Frozen`, P9),
 * la Libreria (`LibraryNasStatus.Frozen`, P7) e la Diagnostica, che lo mette in evidenza; l'avviso va nel **riepilogo
 * giornaliero** (Q6, non una notifica a se').
 *
 * Si scongela solo con [release] e un [UserGesture] il cui conteggio coincide con quello del congelamento, oppure da
 * solo quando una scansione completa torna sotto le soglie.
 */
interface FreezeState {
  val frozen: StateFlow<Freeze?>

  /** Vero se ha scongelato (gesto valido e share congelata). */
  suspend fun release(gesture: UserGesture): Boolean
}
