package dev.pampa.mycloudgallery.core.memories.eval

import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import java.time.LocalDate
import java.util.UUID

// Ricordi inventati per le prove dei file puri (etichette, formato, metriche): id veri di `MemoryIds` (deterministici), elementi UUID da un numero,
// nessun dato vero.

/** L'id dell'ennesimo ricordo di prova (un "anni fa" di un giorno inventato). */
internal fun sampleMemoryId(n: Int): String = MemoryIds.of(MemoryKey.OnThisDay(LocalDate.of(2020, 1, 1).plusDays(n.toLong()))).value

/** L'ennesimo media di prova. */
internal fun sampleMedia(n: Int): String = UUID(0x5a00_0000_0000_0000L, n.toLong()).toString()

/** Un ricordo di prova di [size] elementi (da `first`), tipo [kind]; la copertina e' il primo. */
internal fun sampleMemory(n: Int, size: Int = 10, kind: String = "otd", first: Int = n * 100, title: String = "Titolo di prova $n"): EvalMemory {
  val members = List(size) { sampleMedia(first + it) }
  return EvalMemory(sampleMemoryId(n), kind, LocalDate.of(2026, 9, 1).plusDays(n.toLong()), members.first(), members, title)
}
