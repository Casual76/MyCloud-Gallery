package dev.pampa.mycloudgallery.debugtools.probes

import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult

/**
 * SOLO build debug (Fase 5B, P0-B): i calcoli puri delle prove guidate, senza Android, per poterli provare su JVM (`FieldProbeMathTest`). Ricevono
 * righe e restituiscono numeri; i numeri vanno nel resoconto ([FieldProbeStore]).
 */
internal object FieldProbeMath {

  /** Il confronto di una fotografia di riferimento con le righe di adesso (T-09, T-16). */
  data class Diff(
    /** Righe del riferimento che non ci sono piu' (ne' visibili ne' nel cestino di sistema). */
    val missing: Int,
    /** Righe con un `_ID` che il riferimento non aveva. */
    val new: Int,
    /** Righe del riferimento nel cestino di sistema. */
    val trashed: Int,
    /** Righe del riferimento visibili adesso con lo stesso `_ID` e la stessa impronta (anche quelle mai toccate). */
    val sameIdPresent: Int,
    /** Righe nuove che hanno l'impronta di una riga del riferimento sparita: il file e' tornato con un `_ID` nuovo. */
    val otherIdBack: Int,
  )

  fun diff(baseline: List<ProbeRow>, now: List<ProbeRow>): Diff {
    val nowById = now.associateBy { it.id }
    val baseIds = baseline.mapTo(HashSet()) { it.id }
    val missingBase = baseline.filter { it.id !in nowById }
    val newRows = now.filter { it.id !in baseIds }
    val missingPrints = missingBase.map { it.fingerprint }.toMutableList()
    var otherIdBack = 0
    for (row in newRows) {
      if (missingPrints.remove(row.fingerprint)) otherIdBack++
    }
    return Diff(
      missing = missingBase.size,
      new = newRows.size,
      trashed = baseline.count { nowById[it.id]?.isTrashed == true },
      sameIdPresent = baseline.count { b -> nowById[b.id]?.let { !it.isTrashed && it.fingerprint == b.fingerprint } == true },
      otherIdBack = otherIdBack,
    )
  }

  /** I giorni che mancano alla scadenza di ciascuna riga cestinata, rispetto a [nowSec]: (minimo, massimo), o null se nessuna ha una scadenza. */
  fun expiresDays(rows: List<ProbeRow>, nowSec: Long): Pair<Long, Long>? {
    val days = rows.filter { it.isTrashed }.mapNotNull { it.dateExpiresSec }.map { Math.floorDiv(it - nowSec, SECONDS_PER_DAY) }
    return if (days.isEmpty()) null else days.min() to days.max()
  }

  /** Le prime [n] righe visibili (non nel cestino) di [rows], in ordine di `_ID`; con [photosOnly] solo foto. */
  fun pick(rows: List<ProbeRow>, n: Int, photosOnly: Boolean = false): List<ProbeRow> =
    rows.filter { !it.isTrashed && (!photosOnly || !it.isVideo) }.sortedBy { it.id }.take(n)

  /** Il codice numerico del tipo richiesto da un'altra app (T14_PICKER_MIME). */
  fun mimeCode(mime: String?): Long = when {
    mime == null -> 0L
    mime == "image/*" -> 1L
    mime == "video/*" -> 2L
    mime == "*/*" -> 3L
    else -> 9L
  }

  /** Il numero con cui un esito di riscrittura entra nel resoconto (T09_WRITE_CODE). */
  fun rewriteCode(result: RewriteResult): Long = when (result) {
    is RewriteResult.Replaced -> 0L
    RewriteResult.NotGranted -> 1L
    RewriteResult.Gone -> 2L
    RewriteResult.SourceMismatch -> 3L
    // R3 della revisione 5B: il riscrittore controlla i byte che la riga ha adesso. La prova riscrive una foto con i suoi stessi byte, quindi non dovrebbe mai succedere: se succede la foto e' cambiata fra il dialogo e la scrittura.
    RewriteResult.RowChanged -> 7L
    is RewriteResult.Failed -> if (result.restored) 4L else 5L
  }

  /** Quante righe hanno un valore nella colonna indicata da [selector] (T-16: `ORIGINAL_DOCUMENT_ID`, `DOCUMENT_ID`, `INSTANCE_ID`). */
  fun countWith(rows: List<ProbeRow>, selector: (ProbeRow) -> String?): Int = rows.count { !selector(it).isNullOrEmpty() }

  private const val SECONDS_PER_DAY = 86_400L
}
