package dev.pampa.mycloudgallery.work

import androidx.work.Constraints
import dev.pampa.mycloudgallery.core.rules.WaitReason
import java.time.Duration as JavaDuration
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * **Che cosa fare dopo un'attesa delle regole**: la regola unica per tutti i lavori di fondo (backup, calcolo AI, volti). Un lavoro che le
 * regole hanno fermato (`StageOutcome.Waiting` o `Stopped`) chiede qui se e come rimettersi in coda; prima la regola era scritta due volte
 * (`BackupWorkScheduling.delayFor`/`constraintsFor` e `AiWorkScheduling.followUpOf`) e le due copie erano gia' diverse.
 *
 * Il principio (FAB-3 e FAB-5 della review): **un'attesa che non cambia non si rimette mai in coda senza ritardo**, e un vincolo di
 * WorkManager si usa solo se misura davvero la causa dell'attesa. Il vincolo "batteria non scarica" non la misurava: per Android la batteria
 * e' scarica sotto il 15%, per le regole sotto il 20% (`RuleSet.batteryThresholdPercent`), e il risparmio energetico non c'entra con la
 * carica. Con il risparmio energetico acceso, o fra il 16% e il 19%, il vincolo era soddisfatto subito: il lavoro ripartiva, le regole
 * dicevano di nuovo "aspetta", e cosi' a ciclo continuo (il backup) o ogni minuto per ore (calcolo AI e volti).
 *
 * | Attesa | Che cosa si chiede a WorkManager | Perche' |
 * |---|---|---|
 * | `OutsideWindow` | il ritardo fino all'inizio della fascia, piu' [MIN_RETRY] | esatto: l'ora si sa |
 * | `NeedsPower` | il vincolo della carica, dopo [MIN_RETRY] | esatto: WorkManager avvisa quando si collega l'alimentazione |
 * | `BatteryLow` | il vincolo della carica, dopo [MIN_RETRY] | esatto: la regola vale solo a batteria, e la batteria risale solo in carica |
 * | `PowerSaver` | un ritardo che cresce: 15, 30, 60 minuti (al piu' [cap]) | nessun vincolo di WorkManager misura il risparmio energetico |
 * | `Thermal` | un ritardo che cresce: 5, 10, 20, 40, 60 minuti (al piu' [cap]) | come sopra: il telefono si raffredda da solo |
 * | le altre (rete, NAS, profilo, congelamento, ruolo...) | niente | nessun polling: riprendono il giro periodico, la lettura del telefono, l'apertura dell'app |
 *
 * Il numero del tentativo viaggia negli input del lavoro ([KEY_ATTEMPT]): il lavoro che aspetta di nuovo lo passa a [followUpOf] e il
 * ritardo cresce; un lavoro chiesto da un evento vero (una foto nuova, il giro periodico) riparte da zero. Pura, provata su JVM.
 */
object RuleWaits {

  /** Nessuna attesa si rimette in coda prima di cosi': un vincolo soddisfatto con le regole che dicono ancora "aspetta" non diventa un ciclo. */
  val MIN_RETRY: Duration = 1.minutes

  /** La prima riprova col telefono troppo caldo (E-A5); poi il ritardo cresce. */
  val THERMAL_RETRY: Duration = 5.minutes

  /** La prima riprova col risparmio energetico acceso; poi il ritardo cresce. */
  val POWER_SAVER_RETRY: Duration = 15.minutes

  /** La prima riprova di una richiesta dell'utente ferma dal tetto dei dati; poi il ritardo cresce fino a [MAX_RETRY]. */
  val OVER_CAP_RETRY: Duration = 30.minutes

  /** Il tetto dei ritardi che crescono: mai piu' di un risveglio l'ora per un'attesa che non cambia, mai ore prima di accorgersi che e' finita. */
  val MAX_RETRY: Duration = 1.hours

  /**
   * Il tetto per cio' che l'utente si aspetta "subito" (le foto appena scattate di "Proteggi subito", i file di "Salva sul NAS"): spento il
   * risparmio energetico ripartono entro un quarto d'ora, come dopo un Wi-Fi che non era casa (BK-2).
   */
  val PROMPT_CAP: Duration = 15.minutes

  /** Negli input del lavoro: quante attese di fila senza un vincolo esatto ha gia' fatto questa catena (0 = la prima). */
  const val KEY_ATTEMPT: String = "wait_attempt"

  /**
   * Che cosa chiedere a WorkManager dopo un'attesa.
   *
   * @property delay il ritardo iniziale del lavoro dopo: mai meno di [MIN_RETRY].
   * @property charging il lavoro dopo vuole l'alimentazione collegata.
   * @property attempt il numero da mettere negli input del lavoro dopo ([KEY_ATTEMPT]): cresce solo per le attese senza un vincolo esatto.
   */
  data class FollowUp(val delay: Duration, val charging: Boolean = false, val attempt: Int = 0) {
    /** I vincoli del lavoro dopo, sopra quelli di [base] (la rete, per i lavori che la chiedono). */
    fun constraints(base: Constraints.Builder = Constraints.Builder()): Constraints = base.setRequiresCharging(charging).build()
  }

  /**
   * Il lavoro dopo per l'attesa [wait], o null se non serve programmare niente.
   *
   * @param attempt quante attese senza vincolo esatto ha gia' fatto la catena (dagli input del lavoro, [KEY_ATTEMPT]).
   * @param cap il tetto dei ritardi che crescono: [MAX_RETRY] di norma, [PROMPT_CAP] per cio' che l'utente aspetta presto.
   */
  fun followUpOf(wait: WaitReason, now: LocalDateTime, attempt: Int = 0, cap: Duration = MAX_RETRY): FollowUp? = when (wait) {
    is WaitReason.OutsideWindow -> FollowUp(delay = JavaDuration.between(now, wait.nextStart).toMillis().coerceAtLeast(0L).milliseconds + MIN_RETRY)
    WaitReason.NeedsPower, is WaitReason.BatteryLow -> FollowUp(delay = MIN_RETRY, charging = true)
    WaitReason.PowerSaver -> growing(POWER_SAVER_RETRY, attempt, cap)
    is WaitReason.Thermal -> growing(THERMAL_RETRY, attempt, cap)
    else -> null
  }

  /**
   * XS2-3: "Analizza adesso" con il **tetto** dei dati raggiunto su una rete gia' buona: il Wi-Fi non cambia niente, quindi non si riaccoda subito. Ritenta
   * a 30 minuti, poi 60 (il tetto dei ritardi): al piu' un'ora per accorgersi del reset del ciclo o di un'impostazione cambiata.
   */
  fun overCap(attempt: Int, cap: Duration = MAX_RETRY): FollowUp = growing(OVER_CAP_RETRY, attempt, cap)

  /** [first] al primo tentativo, poi il doppio a ogni attesa di fila, fino a [cap]. */
  private fun growing(first: Duration, attempt: Int, cap: Duration): FollowUp {
    val step = attempt.coerceIn(0, MAX_DOUBLINGS)
    val delay = minOf(first * (1 shl step), cap).coerceAtLeast(MIN_RETRY)
    return FollowUp(delay = delay, attempt = step + 1)
  }

  /** Oltre, il ritardo e' comunque al tetto: il contatore non cresce all'infinito negli input del lavoro. */
  private const val MAX_DOUBLINGS = 10
}
