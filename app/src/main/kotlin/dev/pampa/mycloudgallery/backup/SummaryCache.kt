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

  private class Entry(val key: String, val at: Long, val summary: BackupSummary)

  private var entry: Entry? = null

  /** Il riepilogo in cache se vale ancora per [key] all'ora [now], altrimenti null (da ricalcolare). */
  fun fresh(key: String, now: Long): BackupSummary? {
    val cached = entry ?: return null
    if (cached.key != key) return null
    return cached.summary.takeIf { !it.comparing || now - cached.at < refreshMs }
  }

  fun put(key: String, now: Long, summary: BackupSummary) {
    entry = Entry(key, now, summary)
  }

  /** L'ultimo calcolato, anche vecchio (per una stima che non può aspettare). */
  fun last(): BackupSummary? = entry?.summary

  /** Fra quanti ms un riepilogo provvisorio scade (almeno 1); null se quello in cache è definitivo o non c'è. */
  fun recheckInMs(now: Long): Long? {
    val cached = entry ?: return null
    if (!cached.summary.comparing) return null
    return (refreshMs - (now - cached.at)).coerceAtLeast(1L)
  }
}
