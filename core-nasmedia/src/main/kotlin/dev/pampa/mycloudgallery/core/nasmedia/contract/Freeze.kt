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

  /**
   * R9 della revisione 4A: le share **piccole** (meno di [SMALL_SHARE] file presenti) congelano anche con la sola regola
   * percentuale, [SMALL_PERCENT] dei file (almeno [SMALL_MIN_COUNT]): sotto i 50 file un elenco vuoto della radice non le
   * svuota piu' senza allarme.
   */
  const val SMALL_SHARE: Int = 100
  const val SMALL_PERCENT: Int = 50
  const val SMALL_MIN_COUNT: Int = 2

  /** R9: la finestra di piu' giri in cui i conteggi si sommano (ora del NAS): 7 giorni. */
  const val WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

  /** La soglia per una share con [knownPresent] file presenti (arrotondata per eccesso). */
  fun threshold(knownPresent: Int): Int {
    require(knownPresent >= 0) { "Conteggio negativo." }
    val percent = (knownPresent.toLong() * MIN_PERCENT + 99) / 100
    return maxOf(MIN_COUNT.toLong(), percent).toInt()
  }

  /** La soglia che vale davvero: [threshold], o la regola percentuale delle share piccole se e' piu' bassa (R9). */
  fun effectiveThreshold(knownPresent: Int): Int {
    val base = threshold(knownPresent)
    if (knownPresent < SMALL_MIN_COUNT || knownPresent >= SMALL_SHARE) return base
    val small = maxOf(SMALL_MIN_COUNT.toLong(), (knownPresent.toLong() * SMALL_PERCENT + 99) / 100).toInt()
    return minOf(base, small)
  }
}

/**
 * La finestra di piu' giri del congelamento (R9 della revisione 4A): da [startNasMillis] (ora del NAS, inizio del primo giro
 * contato) si sommano spariti, cambiati e "non piu' media" dei giri completi accettati, e si giudicano contro la soglia di
 * [knownPresent] (i file presenti all'inizio). Dura [MassChangeRule.WINDOW_MILLIS]: 49 file a giro non restano piu' sotto la
 * soglia per sempre.
 */
data class ScanWindow(
  val startNasMillis: Long,
  val knownPresent: Int,
  val vanished: Int,
  val changed: Int,
  val notMedia: Int,
) {
  init {
    require(knownPresent >= 0 && vanished >= 0 && changed >= 0 && notMedia >= 0) { "Conteggi negativi." }
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

/**
 * I conteggi che l'utente ha controllato e confermato con "Ho controllato, continua" (la franchigia di R9 della revisione 4A), in attesa
 * del giro che li usa. [atNasMillis] e' l'ora del NAS del gesto: la franchigia vale [MassChangeRule.WINDOW_MILLIS] e non di piu'.
 */
data class FreezeAcceptance(val diff: ScanDiff, val atNasMillis: Long)

/**
 * Dove si ricorda la franchigia di un congelamento scongelato (NA-4). Il giro che la usa e' di solito in un **processo nuovo** (il lavoro
 * della notte): se restasse solo in memoria il giro rivedrebbe le stesse assenze e ricongelerebbe con lo stesso numero, e l'utente
 * dovrebbe confermare di nuovo cio' che ha gia' controllato. Vale **per quel congelamento soltanto**: la usa e la toglie il primo giro
 * completo dopo il gesto (qualunque ne sia l'esito) e scade dopo 7 giorni; i congelamenti successivi chiedono un gesto nuovo.
 * `:app` la salva su un file in `noBackupFilesDir` (tolto dall'azzeramento); senza, resta in memoria del processo (i test).
 */
interface FreezeAcceptances {
  suspend fun load(account: dev.pampa.mycloudgallery.core.model.AccountId, share: String): FreezeAcceptance?

  /** Ricorda [acceptance] per la share, o la toglie se null. */
  suspend fun save(account: dev.pampa.mycloudgallery.core.model.AccountId, share: String, acceptance: FreezeAcceptance?)
}

/**
 * R8 della revisione 4B: i file che **questo dispositivo** ha tolto dalla parte visibile di una share con le sue operazioni (eliminati nel
 * cestino del NAS, spostati in un'altra share), per `fileId`, dal **diario locale** delle operazioni: azioni confermate dall'utente su
 * questo telefono, mai dati letti dal NAS. La scansione non li conta fra gli "spariti" del congelamento (D10): un riordino grande fatto
 * dall'app non e' un ransomware, e un allarme che scatta a ogni riordino insegna a confermare senza guardare. Un file che sparisce per
 * altra via (il PC, un altro dispositivo, un ransomware) conta come prima: serve lo stesso `fileId` che il diario ricorda.
 */
fun interface OwnRemovals {
  suspend fun fileIds(share: String): Set<Long>

  companion object {
    val NONE: OwnRemovals = OwnRemovals { emptySet() }
  }
}

/**
 * D32 (Fase 5B, P14; franchigia delle versioni di P8, `VersionFranchise`): i file che **le versioni di questo dispositivo** hanno messo o
 * riportato in una share (la versione nuova pubblicata al solito percorso, l'originale riportato da "Ripristina originale", l'attuale spostato
 * in `Versioni/`), per `fileId`, dal **diario locale**: mai dati letti dal NAS. La scansione non li conta fra i "cambiati" del congelamento
 * (D10): un file cambiato al suo posto con lo **stesso** `fileId` che il diario ricorda e' opera dell'app. Un file cambiato dal PC, da un altro
 * dispositivo o da un ransomware ha un altro `fileId` (o lo stesso file senza una voce del diario) e conta come prima.
 */
fun interface OwnChanges {
  suspend fun fileIds(share: String): Set<Long>

  companion object {
    val NONE: OwnChanges = OwnChanges { emptySet() }
  }
}
