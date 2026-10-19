package dev.pampa.mycloudgallery.core.backup.contract

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

// Fase 5B (docs/fase5-progetto.md D21, §1.1, §1.2 "Conferma (5B)", §6.3; riga P0-B del §4.4): la CONFERMA dell'utente per cio' che la 5B
// fa sul telefono o con i suoi file: "Libera spazio" e "Libera subito", "Ripristina originale", le correzioni, "Salva sul NAS" dei file in
// arrivo. E' la sorella di `UserConfirmation` della 4B (core-nasops/contract/Confirmation.kt), con le stesse garanzie, e per lo stesso motivo:
// nessuna azione che toglie copie dal telefono o riscrive un file puo' partire da un dato letto dal NAS, da un'altra app o da un lavoro in
// background.
//
// **Perche' questo file e' fatto cosi'.**
//  - I servizi che toccano il telefono (`FreeSpace.verify`, `VersionKeeper.restoreOriginal`, `Corrections.apply`, `IncomingUploads.save`)
//    vogliono una [PhoneConfirmation]: la firma lo dice, e `Phase5BArchitectureTest` fallisce se la si indebolisce;
//  - la [PhoneConfirmation] ha il costruttore `internal`: la costruisce solo questo file ([PhoneConfirmationIssuer]);
//  - [PhoneConfirmationIssuer] e [PhoneConfirmationSheet] li usano solo la UI: `feature-backup`, `feature-integrations`, i fogli di
//    `feature-viewer` e `:app`, **dopo un tocco** dell'utente su un foglio di riepilogo. Il resto di `core-backup` (piano, verifica, lotti,
//    versioni, correzioni) non li nomina mai: il test di architettura fallisce se un altro file li usa. I fake in `testing/` e i test
//    non contano: stanno fuori dalla release;
//  - la conferma vale per **un piano** (l'impronta) e per poco tempo (10 minuti di orologio monotono): un piano ricalcolato, un elemento
//    in piu', un'altra cartella di destinazione vuole un altro tocco.

/**
 * L'impronta di un piano della 5B (SHA-256 di una codifica canonica di cio' che il servizio fara'): la conferma dell'utente vale per
 * **questo** piano e non per un altro. Si costruisce con [builder]: ogni piano scrive in ordine i propri elementi.
 */
@JvmInline
value class PhonePlanHash(val hex: String) {
  init {
    require(HEX_64.matches(hex)) { "Un'impronta di piano sono 64 cifre esadecimali minuscole." }
  }

  override fun toString(): String = "PhonePlanHash(${hex.take(8)}…)"

  /** Scrive in una codifica canonica (stringhe UTF-8 con lunghezza, interi a larghezza fissa): stessa sequenza, stessa impronta. */
  class Builder internal constructor(domain: String) {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    init {
      out.writeUTF("mcg-phone-plan")
      out.writeUTF(domain)
    }

    fun string(value: String?): Builder = apply { out.writeBoolean(value != null); value?.let { out.writeUTF(it) } }

    fun long(value: Long?): Builder = apply { out.writeBoolean(value != null); value?.let { out.writeLong(it) } }

    fun int(value: Int): Builder = apply { out.writeInt(value) }

    fun bool(value: Boolean): Builder = apply { out.writeBoolean(value) }

    /** Una lista: la lunghezza, poi gli elementi scritti da [write] nell'ordine dato. */
    fun <T> list(items: List<T>, write: Builder.(T) -> Unit): Builder = apply {
      out.writeInt(items.size)
      for (item in items) write(item)
    }

    fun build(): PhonePlanHash {
      out.flush()
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
      return PhonePlanHash(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
  }

  companion object {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /** Comincia l'impronta di un piano di [domain] (un nome fisso per tipo di piano: due piani di tipo diverso non hanno mai la stessa impronta). */
    fun builder(domain: String): Builder = Builder(domain)
  }
}

/**
 * Un piano della 5B che l'utente conferma: lo implementano i piani di "Libera spazio" ([FreeSpacePlan]), di "Ripristina originale"
 * ([NasVersionPlan]), delle correzioni ([CorrectionPlan]) e del salvataggio dei file in arrivo ([IncomingSavePlan]).
 */
interface ConfirmablePlan {
  /** L'impronta di tutto cio' che il piano fara': cambia se cambia un elemento, un timbro, una destinazione, un filtro. */
  val hash: PhonePlanHash

  /** I tocchi di conferma richiesti: 1 per tutto; 2, distinti, per "Libera subito" (R6 della 4B). */
  val requiredConfirmations: Int
}

/**
 * La conferma di un piano da parte dell'utente: costruita dal foglio di conferma dopo un suo tocco, non da dati. Vale per **un piano**
 * ([planHash]) e per poco tempo ([PhoneConfirmationRules.MAX_AGE_MS], orologio monotono): non e' un permesso permanente e non sopravvive
 * a un riavvio (un istante monotono nel futuro non vale).
 *
 * @property confirmedSteps quanti tocchi di conferma ha fatto l'utente (2 per "Libera subito").
 */
class PhoneConfirmation internal constructor(
  val planHash: PhonePlanHash,
  val issuedAtMonotonicMs: Long,
  val confirmedSteps: Int,
) {
  override fun toString(): String = "PhoneConfirmation(steps=$confirmedSteps)"
}

/** Chi costruisce le [PhoneConfirmation]: la UI, dal gestore del tocco di conferma del foglio di riepilogo. Mai altro codice. */
object PhoneConfirmationIssuer {

  /**
   * La conferma di [plan] dopo [confirmedSteps] tocchi dell'utente, all'istante monotono [nowMonotonicMs]
   * (`SystemClock.elapsedRealtime()`). Un numero di tocchi minore di quelli richiesti dal piano e' un errore di programmazione.
   */
  fun issue(plan: ConfirmablePlan, confirmedSteps: Int, nowMonotonicMs: Long): PhoneConfirmation {
    require(confirmedSteps >= plan.requiredConfirmations) { "Servono ${plan.requiredConfirmations} conferme dell'utente." }
    return PhoneConfirmation(plan.hash, nowMonotonicMs, confirmedSteps)
  }
}

/**
 * Il foglio di conferma come macchina a tocchi, uguale per ogni UI che lo mostra (Libera spazio, menu del viewer, ricezione): un tocco per i
 * piani normali, due per i definitivi ("Libera subito"). Non e' stato di Compose: la UI lo avvolge. R6 della 4B: in un foglio a piu' conferme
 * un tocco arrivato meno di [PhoneConfirmationRules.MIN_TAP_GAP_MS] dopo il precedente contato (un doppio tocco) **non conta**, e lo stesso
 * per il primo tocco troppo vicino alla comparsa del foglio ([shownAtMonotonicMs]): la seconda conferma vuole un secondo gesto, dopo aver
 * letto il pannello nuovo (con l'avviso sul backup esterno).
 */
class PhoneConfirmationSheet(val plan: ConfirmablePlan, private val shownAtMonotonicMs: Long? = null) {

  var tapsDone: Int = 0
    private set

  /** L'istante (monotono) dell'ultimo tocco contato, o della comparsa del pannello. */
  private var lastCountedMs: Long? = shownAtMonotonicMs

  /** I tocchi che mancano ancora per avere la conferma. */
  val tapsLeft: Int get() = (plan.requiredConfirmations - tapsDone).coerceAtLeast(0)

  /** Un tocco di conferma dell'utente. Restituisce la conferma solo quando e' l'ultimo tocco richiesto. */
  fun tap(nowMonotonicMs: Long): PhoneConfirmation? {
    val last = lastCountedMs
    val tooSoon = last != null && nowMonotonicMs - last in 0 until PhoneConfirmationRules.MIN_TAP_GAP_MS
    if (tooSoon && plan.requiredConfirmations > 1) return null
    tapsDone++
    lastCountedMs = nowMonotonicMs
    return if (tapsDone >= plan.requiredConfirmations) PhoneConfirmationIssuer.issue(plan, tapsDone, nowMonotonicMs) else null
  }

  /** L'utente ha rinunciato: il foglio ricomincia da capo, nessuna conferma resta in giro. */
  fun reset() {
    tapsDone = 0
    lastCountedMs = shownAtMonotonicMs
  }
}

/** L'esito del controllo di una conferma contro il suo piano. */
sealed interface PhoneConfirmationCheck {
  data object Valid : PhoneConfirmationCheck

  /** La conferma e' di un altro piano (il piano e' cambiato dopo il foglio). */
  data object WrongPlan : PhoneConfirmationCheck

  /** Troppo vecchia: il foglio e' rimasto aperto troppo, l'utente deve confermare di nuovo. */
  data object Expired : PhoneConfirmationCheck

  /** Un istante monotono nel futuro: non e' di questo avvio del telefono (nessuna conferma sopravvive a un riavvio). */
  data object FromTheFuture : PhoneConfirmationCheck

  data object NotEnoughSteps : PhoneConfirmationCheck
}

object PhoneConfirmationRules {

  /** Quanto vale una conferma: 10 minuti di orologio monotono (D21, D31). */
  const val MAX_AGE_MS: Long = 10L * 60 * 1000

  /** R6 della 4B: il tempo minimo fra due tocchi contati di un foglio a piu' conferme. Un doppio tocco sta sotto i 300 ms. */
  const val MIN_TAP_GAP_MS: Long = 600

  /** Controlla [confirmation] contro [plan] (il piano **ricalcolato adesso**, non quello che il foglio mostrava). */
  fun check(plan: ConfirmablePlan, confirmation: PhoneConfirmation, nowMonotonicMs: Long): PhoneConfirmationCheck = when {
    confirmation.planHash != plan.hash -> PhoneConfirmationCheck.WrongPlan
    confirmation.confirmedSteps < plan.requiredConfirmations -> PhoneConfirmationCheck.NotEnoughSteps
    confirmation.issuedAtMonotonicMs > nowMonotonicMs -> PhoneConfirmationCheck.FromTheFuture
    nowMonotonicMs - confirmation.issuedAtMonotonicMs > MAX_AGE_MS -> PhoneConfirmationCheck.Expired
    else -> PhoneConfirmationCheck.Valid
  }
}
