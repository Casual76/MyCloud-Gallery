package dev.pampa.mycloudgallery.core.nasops.contract

// Fase 4B (docs/fase4-progetto.md D24, §1.2 "Conferma", §6.3 "UserConfirmation costruita solo dalla UI"; formato-nas.md §9 punto 7):
// la CONFERMA dell'utente, l'unico modo di avviare un'operazione distruttiva che non venga dal diario locale.
//
// **Perche' questo file e' fatto cosi'.** Una cancellazione o uno spostamento non devono mai partire da cio' che si legge dal NAS
// (un record del cestino finto, un indice ostile, una cartella strana). Quindi:
//  - `NasOperations.start` vuole una [UserConfirmation];
//  - la [UserConfirmation] ha il costruttore `internal`: la costruisce solo questo file ([ConfirmationIssuer]);
//  - [ConfirmationIssuer] e [ConfirmationSheet] li usa solo la UI: `feature-nas`, il menu del viewer (`feature-viewer`) e `:app`,
//    **dopo un tocco** dell'utente sul foglio di riepilogo. Il resto di `core-nasops` (pianificazione, esecuzione, cestino, pulizie)
//    non li nomina mai: lo verifica `Phase4ArchitectureTest` (core-common), che fallisce se un altro file li usa;
//  - il diario ([OpsStore.create]) accetta solo un [AcceptedPlan], che esce solo da [ConfirmationGate.accept] con una conferma valida.
// Non esiste una porta di servizio: i test dei moduli usano `ConfirmationIssuer` come la UI.

/**
 * La conferma di un piano da parte dell'utente: costruita dal foglio di conferma dopo un suo tocco, non da dati. Vale per **un
 * piano** ([planHash]) e per poco tempo ([ConfirmationRules.MAX_AGE_MS], orologio monotono): non e' un permesso permanente.
 *
 * @property confirmedSteps quanti tocchi di conferma ha fatto l'utente (2 per "Elimina subito").
 */
class UserConfirmation internal constructor(
  val planHash: PlanHash,
  val issuedAtMonotonicMs: Long,
  val confirmedSteps: Int,
) {
  override fun toString(): String = "UserConfirmation(steps=$confirmedSteps)"
}

/**
 * Chi costruisce le [UserConfirmation]: la UI, dal gestore del tocco di conferma del foglio di riepilogo. Mai altro codice.
 */
object ConfirmationIssuer {

  /**
   * La conferma di [plan] dopo [confirmedSteps] tocchi dell'utente, all'istante monotono [nowMonotonicMs]
   * (`SystemClock.elapsedRealtime()`). Un numero di tocchi minore di quelli richiesti dal piano e' un errore di programmazione.
   */
  fun issue(plan: OpPlan, confirmedSteps: Int, nowMonotonicMs: Long): UserConfirmation {
    require(confirmedSteps >= plan.requiredConfirmations) { "Servono ${plan.requiredConfirmations} conferme dell'utente." }
    return UserConfirmation(plan.hash, nowMonotonicMs, confirmedSteps)
  }
}

/**
 * Il foglio di conferma come macchina a tocchi, uguale per ogni UI che lo mostra (Cartelle NAS, Eliminati di recente, menu del
 * viewer): un tocco per i piani normali, due per i definitivi ("Elimina subito", Q8). Non e' stato di Compose: la UI lo avvolge.
 */
class ConfirmationSheet(val plan: OpPlan, private val shownAtMonotonicMs: Long? = null) {

  var tapsDone: Int = 0
    private set

  /** L'istante (monotono) dell'ultimo tocco contato, o della comparsa del pannello. */
  private var lastCountedMs: Long? = shownAtMonotonicMs

  /** I tocchi che mancano ancora per avere la conferma. */
  val tapsLeft: Int get() = (plan.requiredConfirmations - tapsDone).coerceAtLeast(0)

  /**
   * Un tocco di conferma dell'utente. Restituisce la conferma solo quando e' l'ultimo tocco richiesto.
   *
   * R6 (revisione 4B): in un foglio a piu' conferme ("Elimina subito", definitivo) un tocco arrivato meno di
   * [ConfirmationRules.MIN_TAP_GAP_MS] dopo il tocco precedente contato (un doppio tocco, un tocco registrato due volte) **non conta**: la
   * seconda conferma vuole un secondo gesto, dopo che il pannello nuovo (con l'avviso sul backup esterno) e' comparso. Lo stesso per il
   * primo tocco troppo vicino alla comparsa del foglio ([shownAtMonotonicMs]): il secondo colpo di un doppio tocco sulla voce del menu.
   * I fogli a un tocco solo (tutto cio' che passa dal cestino del NAS, 30 giorni) non cambiano.
   */
  fun tap(nowMonotonicMs: Long): UserConfirmation? {
    val last = lastCountedMs
    val tooSoon = last != null && nowMonotonicMs - last in 0 until ConfirmationRules.MIN_TAP_GAP_MS
    if (tooSoon && plan.requiredConfirmations > 1) return null
    tapsDone++
    lastCountedMs = nowMonotonicMs
    return if (tapsDone >= plan.requiredConfirmations) ConfirmationIssuer.issue(plan, tapsDone, nowMonotonicMs) else null
  }

  /** L'utente ha rinunciato: il foglio ricomincia da capo, nessuna conferma resta in giro. */
  fun reset() {
    tapsDone = 0
    lastCountedMs = shownAtMonotonicMs
  }
}

/** L'esito del controllo di una conferma contro il suo piano. */
sealed interface ConfirmationCheck {
  data object Valid : ConfirmationCheck

  /** La conferma e' di un altro piano (il piano e' cambiato dopo il foglio). */
  data object WrongPlan : ConfirmationCheck

  /** Troppo vecchia: il foglio e' rimasto aperto troppo, l'utente deve confermare di nuovo. */
  data object Expired : ConfirmationCheck

  /** Un istante monotono nel futuro: non e' di questo avvio del telefono (nessuna conferma sopravvive a un riavvio). */
  data object FromTheFuture : ConfirmationCheck

  data object NotEnoughSteps : ConfirmationCheck
}

object ConfirmationRules {

  /** Quanto vale una conferma: 10 minuti di orologio monotono. */
  const val MAX_AGE_MS: Long = 10L * 60 * 1000

  /**
   * R6: il tempo minimo fra due tocchi contati di un foglio a piu' conferme (e fra la comparsa di un foglio e il suo primo tocco). Un
   * doppio tocco sta sotto i 300 ms; un secondo gesto voluto, dopo aver letto il pannello nuovo, sta sopra.
   */
  const val MIN_TAP_GAP_MS: Long = 600

  fun check(plan: OpPlan, confirmation: UserConfirmation, nowMonotonicMs: Long): ConfirmationCheck = when {
    confirmation.planHash != plan.hash -> ConfirmationCheck.WrongPlan
    confirmation.confirmedSteps < plan.requiredConfirmations -> ConfirmationCheck.NotEnoughSteps
    confirmation.issuedAtMonotonicMs > nowMonotonicMs -> ConfirmationCheck.FromTheFuture
    nowMonotonicMs - confirmation.issuedAtMonotonicMs > MAX_AGE_MS -> ConfirmationCheck.Expired
    else -> ConfirmationCheck.Valid
  }
}

/**
 * Un piano con la conferma dell'utente verificata: l'unico ingresso del diario ([OpsStore.create]). Il costruttore e' `internal` e lo
 * chiama solo [ConfirmationGate.accept].
 */
class AcceptedPlan internal constructor(val plan: OpPlan, val acceptedAtMonotonicMs: Long) {
  override fun toString(): String = "AcceptedPlan($plan)"
}

sealed interface AcceptOutcome {
  data class Accepted(val accepted: AcceptedPlan) : AcceptOutcome

  data class Rejected(val check: ConfirmationCheck) : AcceptOutcome
}

object ConfirmationGate {

  /** Verifica [confirmation] contro [plan]; solo se vale esce un [AcceptedPlan]. */
  fun accept(plan: OpPlan, confirmation: UserConfirmation, nowMonotonicMs: Long): AcceptOutcome =
    when (val check = ConfirmationRules.check(plan, confirmation, nowMonotonicMs)) {
      ConfirmationCheck.Valid -> AcceptOutcome.Accepted(AcceptedPlan(plan, nowMonotonicMs))
      else -> AcceptOutcome.Rejected(check)
    }
}
