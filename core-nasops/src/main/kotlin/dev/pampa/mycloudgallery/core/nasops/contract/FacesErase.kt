package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration

// LA CANCELLAZIONE STRETTA DEI DATI DEI VOLTI SUL NAS (docs/fase8-progetto.md §1.2 "core-nasops", §2.8 passi 8-9, D29, D31, D35, E-F34;
// formato §9 punto 6-bis). Interfaccia e null-object di P0; l'implementazione (`core-nasops/.../faces/FacesNasEraser`, sopra `NasFileOps`)
// e la pulizia dei propri file dei volti sono di P8. core-faces non vede core-nasops: arriva qui dalla sua porta `FacesEraseOps`, legata
// da :app (P13).
//
// **L'eccezione dichiarata (D31)** alla regola "nessuna azione distruttiva da dati remoti": **solo dati dei volti**, mai media, mai fuori da
// `.mycloudgallery/faces/` dell'area privata dell'account, solo per (a) una generazione che la voce `faces` dell'account dice **non
// corrente**, o il cui "Disattiva e cancella" e' stato confermato su questo dispositivo, (b) la cartella di un dispositivo **dell'account**
// revocato o perso nel registro accettato. I percorsi li costruisce `NasLayout` dalla regola (generazione, id del dispositivo), **mai**
// letti da un dato. La generazione corrente non si cancella mai; nel dubbio (stato non fresco, voce sconosciuta) non si cancella niente.

/** Perche' si cancella (D31). Il codice va nel diario e in Diagnostica. */
enum class FacesEraseReason(override val code: String) : StableCode {
  /** "Disattiva e cancella" confermato su questo dispositivo (D29): la generazione e' gia' `off` nella voce. */
  USER_CONFIRMED("user_confirmed"),

  /** Una generazione che la voce `faces` dell'account dice non corrente (E-F9, E-F32, E-F33), o la propria cartella di una tale generazione. */
  NOT_CURRENT("not_current"),

  /** La cartella di un dispositivo dell'account revocato o perso nel registro accettato (E-F35). */
  DEVICE_REVOKED("device_revoked"),
}

/** Perche' un giro si e' fermato (D35). Ogni blocco lascia tutto com'e': si riprova al giro dopo, la pagina dice il motivo. */
enum class FacesEraseStop(override val code: String) : StableCode {
  /** Niente piu' da togliere. */
  DONE("done"),

  /** Tetto del giro ([FacesEraseLimits.MAX_FILES_PER_ROUND] file o il tempo): si continua al giro dopo. */
  BUDGET("budget"),

  /** NAS spento, rete, sessione: si riprova. */
  UNREACHABLE("unreachable"),

  FROZEN("frozen"),
  EMERGENCY_STOP("emergency_stop"),
  CLOCK_JUMP("clock_jump"),
  READ_ONLY("read_only"),
  NOT_TRUSTED("not_trusted"),

  /** D34: la recinzione delle share vere (`facesSyncTestSharesOnly`): sulle share vere i volti non stanno sul NAS. */
  TEST_SHARES_ONLY("test_shares_only"),

  /** La share non e' l'area privata dichiarata dell'account, o e' l'area comune (L23): mai. */
  NOT_PRIVATE_AREA("not_private_area"),

  /** La regola di D31 non lo ammette: generazione corrente, dispositivo non revocato ne' perso, motivo sbagliato. Niente tolto. */
  NOT_ALLOWED("not_allowed"),

  /** Lo stato dell'account non e' fresco o la voce `faces` e' sconosciuta (E-F12): nel dubbio non si cancella niente. */
  UNSURE("unsure"),
}

/** L'esito di un giro: file e cartelle tolti, quanti file restano sotto il bersaglio (null se non si sa), perche' si e' fermato. */
data class EraseRoundReport(val removedFiles: Int, val removedDirs: Int, val remainingFiles: Int?, val stoppedBy: FacesEraseStop) {
  val done: Boolean get() = stoppedBy == FacesEraseStop.DONE
}

/** Le generazioni in `faces/` dell'area privata (nomi validi; il resto si ignora e si conta), o perche' non si sono potute elencare. */
sealed interface FacesGenerations {
  data class Listed(val generations: List<FaceGeneration>, val ignoredNames: Int, val truncated: Boolean) : FacesGenerations {
    override fun toString(): String = "FacesGenerations.Listed(${generations.size}, ignorati=$ignoredNames, troncato=$truncated)"
  }

  data class Stopped(val stop: FacesEraseStop) : FacesGenerations
}

/**
 * La verifica dopo la cancellazione (§2.8 passo 9): la cartella della generazione c'e' ancora (un dispositivo che pubblicava prima di
 * saperlo: si toglie di nuovo) e il cestino di rete ne tiene una copia (E-F34: "svuotalo dal pannello del NAS", L96).
 */
sealed interface FacesEraseCheck {
  data class Checked(val generationPresent: Boolean, val recycleBinCopy: Boolean) : FacesEraseCheck

  data class Stopped(val stop: FacesEraseStop) : FacesEraseCheck
}

/**
 * Cosa sa chi cancella (D31, D30), legato da :app: la generazione **corrente** secondo la voce `faces` dell'account (stato fresco, anche le
 * scelte fatte qui e non ancora datate) e se un dispositivo e' revocato o perso nel registro accettato. [Unknown][FacesCurrent.Unknown]
 * = niente si cancella per un dato remoto.
 */
interface FacesEraseFacts {
  suspend fun current(): FacesCurrent

  suspend fun revokedOrLost(deviceId: String): Boolean
}

/** La generazione corrente dell'account (D3): attiva con [generation], o spenta ([generation] null); o non si sa ([Unknown]). */
sealed interface FacesCurrent {
  data class Known(val generation: FaceGeneration?) : FacesCurrent {
    override fun toString(): String = "FacesCurrent.Known(${generation != null})"
  }

  data object Unknown : FacesCurrent
}

/**
 * La cancellazione stretta sotto `faces/` (D31). Ogni chiamata lavora solo sull'area privata [share] dell'account (mai l'area comune:
 * [FacesEraseStop.NOT_PRIVATE_AREA]), solo dietro il cancello (D35: congelamento, emergenza, salto dell'ora, fiducia, sola lettura,
 * recinzione D34), dal fondo, file per file, poi le cartelle vuote, al piu' [FacesEraseLimits.MAX_FILES_PER_ROUND] file per giro.
 * **Idempotente**: un giro interrotto riprende dal giro dopo; togliere cio' che non c'e' e' [FacesEraseStop.DONE].
 * - [eraseGeneration]: l'**intera** `faces/<G>/` (le cartelle di tutti i dispositivi dell'account e le prenotazioni) per
 *   [FacesEraseReason.USER_CONFIRMED] o [FacesEraseReason.NOT_CURRENT]; mai la generazione corrente.
 * - [eraseDevice]: `faces/<G>/<dispositivo>/` per [FacesEraseReason.DEVICE_REVOKED] (dispositivo revocato o perso) o
 *   [FacesEraseReason.NOT_CURRENT] (generazione non corrente).
 */
interface FacesNasEraser {
  suspend fun listGenerations(share: String): FacesGenerations

  suspend fun eraseGeneration(share: String, generation: FaceGeneration, reason: FacesEraseReason, budget: Duration): EraseRoundReport

  suspend fun eraseDevice(share: String, generation: FaceGeneration, deviceId: String, reason: FacesEraseReason, budget: Duration): EraseRoundReport

  suspend fun verify(share: String, generation: FaceGeneration): FacesEraseCheck
}

/** I numeri della cancellazione sul NAS (§2.8 passo 8). */
object FacesEraseLimits {
  /** File tolti al piu' per giro (il resto al giro dopo). */
  const val MAX_FILES_PER_ROUND: Int = 20_000

  /** I nomi delle cartelle di cestino di rete riconosciute nella radice dell'area privata (E-F34, E-N16). */
  val RECYCLE_BIN_DIRS: List<String> = listOf(".recycle", ".Recycle Bin", "\$RECYCLE.BIN")
}
