package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary

/**
 * La cache del riepilogo del primo backup (§2.11): costa circa due query per candidato (13 mila media), quindi si calcola una volta e si tiene.
 * Pura: l'ora monotona la dà chi la usa, così si prova senza aspettare minuti.
 *
 * Un riepilogo vale finché **non cambia la chiave** (le sorgenti, il confronto con il NAS, i media al sicuro e in coda: BK-8) e, se è
 * **provvisorio** ("sto ancora confrontando"), al massimo [refreshMs]. Il provvisorio ha un'ora in cui varrà la pena ricontrollare
 * ([recheckInMs]): BK-13, senza, uno stato "sto cercando…" calcolato una volta e rimasto in cache per un minuto non diventava mai "assestato"
 * se nessun altro evento arrivava.
 */
internal class SummaryCache(private val refreshMs: Long) {

  companion object {
    /**
     * Fase I (12/11; BS-1, BF-3): la chiave di un riepilogo. Oltre a sorgenti, confronto delle copie simili finito o no e conteggi del backup (BK-8),
     * **l'account, l'area privata e se la sua scansione e' finita**: un riepilogo di un'altra area (l'admin ne assegna una nuova, mai scandita) non vale,
     * e la fine della scansione rifa' subito numeri e cancello insieme (prima restavano quelli di prima fino a un minuto, con i tasti spenti). Resta in
     * memoria, mai nei log.
     */
    fun keyOf(
      sourceIds: Collection<String>,
      similarSettled: Boolean,
      counts: dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts,
      account: String?,
      share: String?,
      scanned: Boolean,
    ): String = listOf(
      sourceIds.sorted().joinToString(","), similarSettled, counts.safe, counts.noNasCopy, counts.unverified, account.orEmpty(), share.orEmpty(), scanned,
    ).joinToString("|")
  }

  private class Entry(val key: String, val at: Long, val summary: BackupSummary)

  private var entry: Entry? = null

  /** BS2-3: cresce a ogni [clear] (le parti cambiano o si svuotano): un calcolo partito prima non entra in cache e non si restituisce. */
  private var generation: Long = 0L

  /** La generazione di adesso: chi calcola la prende **prima** di leggere le parti e la ripassa a [summaryOf]. */
  @Synchronized
  fun generation(): Long = generation

  /**
   * Fase I (BS2-3): il riepilogo per [key] dalla cache o calcolato con [compute], **solo se vale ancora** per la fotografia di [generation]. Fra la lettura
   * delle parti e la fine del calcolo (secondi) `start` puo' ricostruirle per un'altra area e svuotare la cache: allora il calcolo vecchio **non** entra in
   * cache (il controllo e la scrittura sono una cosa sola, sotto il lucchetto di questo oggetto) e non si restituisce: null, chi chiama dice «sto contando»
   * e ricalcola al giro dopo. [stillCurrent] (le parti di adesso sono ancora quelle lette) si controlla prima di scrivere e prima di restituire.
   */
  suspend fun summaryOf(key: String, now: Long, generation: Long, stillCurrent: () -> Boolean, compute: suspend () -> BackupSummary): BackupSummary? {
    fresh(key, now)?.let { cached -> return cached.takeIf { current(generation) && stillCurrent() } }
    val computed = compute()
    if (!stillCurrent()) return null
    return computed.takeIf { putIfCurrent(generation, key, now, computed) }
  }

  @Synchronized
  private fun current(generation: Long): Boolean = this.generation == generation

  @Synchronized
  private fun putIfCurrent(generation: Long, key: String, now: Long, summary: BackupSummary): Boolean {
    if (this.generation != generation) return false
    entry = Entry(key, now, summary)
    return true
  }

  /** Il riepilogo in cache se vale ancora per [key] all'ora [now], altrimenti null (da ricalcolare). */
  @Synchronized
  fun fresh(key: String, now: Long): BackupSummary? {
    val cached = entry ?: return null
    if (cached.key != key) return null
    return cached.summary.takeIf { !it.comparing || now - cached.at < refreshMs }
  }

  @Synchronized
  fun put(key: String, now: Long, summary: BackupSummary) {
    entry = Entry(key, now, summary)
  }

  /** L'ultimo calcolato, anche vecchio (per una stima che non può aspettare). */
  @Synchronized
  fun last(): BackupSummary? = entry?.summary

  /**
   * Fase I (BS-1, BS2-3): le parti del backup sono cambiate (un'altra area, un altro account, il NAS che va e viene): niente di prima vale, nemmeno un
   * calcolo ancora in corso ([generation]).
   */
  @Synchronized
  fun clear() {
    entry = null
    generation++
  }

  /** Fra quanti ms un riepilogo provvisorio scade (almeno 1); null se quello in cache è definitivo o non c'è. */
  @Synchronized
  fun recheckInMs(now: Long): Long? {
    val cached = entry ?: return null
    if (!cached.summary.comparing) return null
    return (refreshMs - (now - cached.at)).coerceAtLeast(1L)
  }
}
