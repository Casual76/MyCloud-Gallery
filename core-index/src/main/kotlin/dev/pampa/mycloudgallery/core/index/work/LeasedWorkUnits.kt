package dev.pampa.mycloudgallery.core.index.work

import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitLease
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AcquireResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * Le unita' di lavoro con prenotazioni (D13; formato-nas.md §5.6 M4; docs/fase4-progetto.md §2.1 "Unita' di lavoro").
 *
 * - Un'unita' e' (share, passaggio, mese di scatto, parte); le parti dividono per 200 **tutti** i media del mese ordinati
 *   per chiave di contenuto ([WorkSource]): i confini non si spostano quando il lavoro avanza, quindi due dispositivi
 *   chiamano con lo stesso nome lo stesso gruppo di media.
 * - [next] rilegge le teste della share ([PublishedClasses.refresh]), toglie cio' che qualcuno ha gia' pubblicato, sceglie
 *   la prima unita' con lavoro (prima i mesi e le parti con media visibili, poi dal mese piu' recente) e la prenota per
 *   1 ora. `Busy`/`Lost` → la successiva; `Contended` → senza prenotazione (si conta). Con la scrittura bloccata (D31, D10,
 *   D32, E-I19) o senza un registro che ci fa scrivere: senza prenotazione ([UnitLease.Unleased]), in solo locale.
 * - [keepAlive] ogni 20 minuti: generazione nuova; se nel frattempo gli altri hanno pubblicato una parte dei media, la
 *   restituisce senza di loro; se un altro ha preso l'unita' (la nostra era scaduta) → [KeepAliveResult.Lost].
 * - [finish] rilascia (generazione con durata 0), ma **solo se niente dell'unita' aspetta ancora nell'outbox**
 *   ([unpublished]): un'unita' interrotta con lavoro non ancora pubblicato (testa rimandata di 5 minuti, NAS
 *   irraggiungibile) resta prenotata fino alla scadenza, cosi' un altro dispositivo non rifa' cio' che e' gia' pronto qui.
 */
class LeasedWorkUnits(
  private val identity: IndexIdentity,
  private val source: WorkSource,
  private val published: PublishedClasses,
  private val leases: WorkLeases,
  private val rules: IndexWriteRules,
  /** Vero se qualcuna di queste chiavi aspetta ancora nell'outbox (`OutboxIndexPublisher.hasPending`). */
  private val unpublished: (suspend (share: String, keys: Set<ContentKey>) -> Boolean)? = null,
  /** Mesi guardati al massimo per chiamata (con migliaia di mesi gia' finiti non si scorre tutto l'archivio). */
  private val maxMonthsPerCall: Int = 48,
  /**
   * La famiglia di decoder di questo dispositivo (`DecoderFamily.ofDevice()`), se nota. R5 della revisione 4A: un altro
   * dispositivo che dichiara "la tua famiglia ha fallito" non ferma questo dal provarci una volta da se' (D14 resta: un
   * fallimento dichiarato da questo stesso dispositivo, o di un'altra famiglia, vale come prima). Null = come prima.
   */
  private val ownDecoderFamily: String? = null,
  /**
   * XS-2 (Pacchetto XF): al piu' cosi' tanti mesi **con media prioritari** ([UnitPriority.visible]: sullo schermo, o chiesti dall'utente con
   * "Analizza adesso") stanno davanti nella finestra di [maxMonthsPerCall]; il resto della finestra e' **sempre** dei mesi ordinari, nell'ordine
   * di sempre. Cosi' dei mesi urgenti che non si possono servire (anteprime non ancora pronte, media di un altro dispositivo) non riempiono la
   * finestra e il lavoro ordinario avanza a ogni tappa. Di serie come [maxMonthsPerCall] (nessun tetto in piu': il comportamento di prima).
   */
  private val maxPriorityMonthsPerCall: Int = maxMonthsPerCall,
) : WorkUnits {

  /** Contatori per la Diagnostica (`INDEX_LEASES_CONTENDED` e affini): solo numeri. */
  class Stats {
    val contended = AtomicInteger()
    val busy = AtomicInteger()
    val unleased = AtomicInteger()
    val refreshFailures = AtomicInteger()
    val lost = AtomicInteger()

    /** Prenotazioni lasciate scadere invece di rilasciarle: lavoro dell'unita' ancora nell'outbox. */
    val kept = AtomicInteger()
  }

  val stats = Stats()

  private val lock = Any()

  /** Le unita' prese da questo processo e non ancora finite: non si prendono due volte. */
  private val active = HashMap<WorkUnitKey, UnitClaim>()

  override suspend fun next(share: String, pass: WorkPass, priority: UnitPriority): UnitClaim? {
    val visible = priority.visible.value
    if (published.refresh(share) is RemoteResult.Failed) stats.refreshFailures.incrementAndGet()
    val months = LinkedHashSet<String>()
    if (visible.isNotEmpty()) months += source.monthsOf(share, pass, visible).take(maxPriorityMonthsPerCall)
    months += source.months(share, pass)
    val leasing = rules.blockFor(share) == null && identity.writableRegistry() != null
    for (month in months.take(maxMonthsPerCall)) {
      val all = source.monthItems(share, pass, month).sortedBy { it.contentKey.hex }
      val progress = published.progress(share, all.filter { it.missing.isNotEmpty() }.map { it.contentKey })
      val candidates = all.chunked(TrustLimits.WORK_UNIT_MAX_ITEMS).withIndex().mapNotNull { (part, items) ->
        val key = WorkUnitKey(share, pass, month, part)
        if (synchronized(lock) { key in active }) return@mapNotNull null
        val pending = items.mapNotNull { trim(it, progress) }
        if (pending.isEmpty()) null else Candidate(key, pending, pending.any { it.contentKey.short in visible })
      }.sortedWith(compareByDescending<Candidate> { it.visible }.thenBy { it.key.part })
      for (candidate in candidates) {
        val lease = if (!leasing) {
          stats.unleased.incrementAndGet()
          UnitLease.Unleased
        } else {
          when (val result = leases.acquire(share, candidate.key.resource)) {
            is AcquireResult.Acquired -> UnitLease.Held(result.generation, result.until)
            is AcquireResult.Busy, AcquireResult.Lost -> {
              stats.busy.incrementAndGet()
              null
            }
            AcquireResult.Contended -> {
              stats.contended.incrementAndGet()
              UnitLease.Unleased
            }
          }
        } ?: continue
        val claim = UnitClaim(candidate.key, candidate.items, lease)
        synchronized(lock) { active[claim.key] = claim }
        return claim
      }
    }
    return null
  }

  override suspend fun keepAlive(claim: UnitClaim): KeepAliveResult {
    val share = claim.key.share
    if (claim.lease is UnitLease.Unleased) return KeepAliveResult.Renewed(claim)
    if (rules.blockFor(share) != null || identity.writableRegistry() == null) {
      // Scritture sospese a meta' unita' (salto dell'ora, congelamento): si finisce senza prenotazione.
      return KeepAliveResult.Renewed(remember(claim.copy(lease = UnitLease.Unleased)))
    }
    return when (val result = leases.acquire(share, claim.key.resource)) {
      is AcquireResult.Acquired -> {
        if (published.refresh(share) is RemoteResult.Failed) stats.refreshFailures.incrementAndGet()
        val progress = published.progress(share, claim.items.map { it.contentKey })
        val remaining = claim.items.mapNotNull { trim(it, progress) }
        if (remaining.isEmpty()) {
          // Gli altri hanno finito tutto: si lascia l'unita' (e la prenotazione appena rinnovata).
          leases.release(share, claim.key.resource)
          forget(claim.key)
          KeepAliveResult.Lost
        } else {
          KeepAliveResult.Renewed(remember(UnitClaim(claim.key, remaining, UnitLease.Held(result.generation, result.until))))
        }
      }
      is AcquireResult.Busy, AcquireResult.Lost -> {
        stats.lost.incrementAndGet()
        forget(claim.key)
        KeepAliveResult.Lost
      }
      AcquireResult.Contended -> {
        stats.contended.incrementAndGet()
        KeepAliveResult.Renewed(remember(claim.copy(lease = UnitLease.Unleased)))
      }
    }
  }

  override suspend fun finish(claim: UnitClaim, outcome: UnitOutcome) {
    forget(claim.key)
    if (claim.lease !is UnitLease.Held) return
    val waiting = unpublished?.invoke(claim.key.share, claim.items.mapTo(HashSet()) { it.contentKey }) ?: false
    if (waiting) {
      stats.kept.incrementAndGet()
      return
    }
    leases.release(claim.key.share, claim.key.resource)
  }

  private class Candidate(val key: WorkUnitKey, val items: List<WorkItem>, val visible: Boolean)

  /** Toglie dal media le classi gia' pubblicate da qualcuno (D13) e aggiunge i decoder che hanno fallito (D14). */
  private fun trim(item: WorkItem, progress: Map<ContentKey, PublishedProgress>): WorkItem? {
    if (item.missing.isEmpty()) return null
    val p = progress[item.contentKey] ?: return item
    val missing = item.missing - p.classes
    if (missing.isEmpty()) return null
    val me = identity.deviceId
    val failed = p.failedDecoders.filterTo(HashSet()) { family ->
      family != ownDecoderFamily || me in p.failedBy[family].orEmpty()
    }
    return item.copy(missing = missing, failedDecoders = item.failedDecoders + failed)
  }

  private fun remember(claim: UnitClaim): UnitClaim {
    synchronized(lock) { active[claim.key] = claim }
    return claim
  }

  private fun forget(key: WorkUnitKey) {
    synchronized(lock) { active.remove(key) }
  }
}
