package dev.pampa.mycloudgallery.core.memories.eval

import java.time.LocalDate
import java.time.format.DateTimeParseException

// IL FORMATO DEL FILE DEI GIUDIZI (`ricordi.mcgmem`, in `noBackupFilesDir/eval/memories/`). Testo UTF-8, una riga per voce, campi separati da
// tabulazione; la prima riga e' l'intestazione [MemoriesLabelFormat.MAGIC]. Un file rovinato da' un errore con il **numero** della riga, mai il
// contenuto (le righe sono dati privati). Stesso file in core-memories (test) e in :app (debug).
//
//   MCGMEM01
//   R <tab> <id ricordo> <tab> tipo <tab> aaaa-mm-gg (giorno simulato) <tab> <copertina> <tab> <elemento>,<elemento>,... <tab> titolo (con \t \n \\)
//   M <tab> <id ricordo> <tab> B|C|N <tab> t|s|n|-       il tocco sul ricordo e il motivo di un "Da non mostrare"
//   F <tab> <id ricordo> <tab> <id media> <tab> d|m|s|x|t    una foto che non doveva esserci, e perche'
//
// Le righe R vengono per prime (l'ordine e' quello della sessione), poi le M e le F.

object MemoriesLabelFormat {
  const val MAGIC: String = "MCGMEM01"
  const val FILE_NAME: String = "ricordi.mcgmem"

  fun serialize(labels: MemoriesLabelSet): String {
    val out = StringBuilder(MAGIC).append('\n')
    for (m in labels.run) {
      out.append("R\t").append(m.id).append('\t').append(m.kind).append('\t').append(m.day).append('\t').append(m.cover).append('\t')
        .append(m.members.joinToString(",")).append('\t').append(escape(m.title)).append('\n')
    }
    for (m in labels.run) {
      val j = labels.judgement(m.id) ?: continue
      out.append("M\t").append(m.id).append('\t').append(j.verdict.code).append('\t').append(j.why?.code ?: '-').append('\n')
    }
    for (m in labels.run) {
      for ((media, fault) in labels.faultsOf(m.id).toSortedMap()) out.append("F\t").append(m.id).append('\t').append(media).append('\t').append(fault.code).append('\n')
    }
    return out.toString()
  }

  /** Rilegge un file; un file rovinato da' un errore con il **numero** della riga, mai il contenuto. */
  fun parse(text: String): MemoriesLabelSet {
    val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    require(lines.firstOrNull() == MAGIC) { "Non e' un file di giudizi sui ricordi (intestazione)." }
    val run = ArrayList<EvalMemory>()
    val judgements = LinkedHashMap<String, EvalJudgement>()
    val faults = LinkedHashMap<String, MutableMap<String, EvalFault>>()
    lines.drop(1).forEachIndexed { index, line ->
      val n = index + 2
      try {
        val c = line.split('\t')
        when (c[0]) {
          "R" -> {
            require(c.size == 7)
            run += EvalMemory(c[1], c[2], LocalDate.parse(c[3]), c[4], c[5].split(','), unescape(c[6]))
          }
          "M" -> {
            require(c.size == 4 && c[2].length == 1 && c[3].length == 1 && c[1] !in judgements)
            val verdict = EvalVerdict.ofCode(c[2][0])!!
            val why = if (c[3] == "-") null else EvalWhy.ofCode(c[3][0])!!
            judgements[c[1]] = EvalJudgement(verdict, why)
          }
          "F" -> {
            require(c.size == 4 && EvalMemory.isMedia(c[2]) && c[3].length == 1)
            val map = faults.getOrPut(c[1]) { LinkedHashMap() }
            require(c[2] !in map)
            map[c[2]] = EvalFault.ofCode(c[3][0])!!
          }
          else -> throw IllegalArgumentException()
        }
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Riga $n del file di giudizi sui ricordi non valida.")
      } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Riga $n del file di giudizi sui ricordi non valida.")
      } catch (e: NullPointerException) {
        throw IllegalArgumentException("Riga $n del file di giudizi sui ricordi non valida.")
      } catch (e: IndexOutOfBoundsException) {
        throw IllegalArgumentException("Riga $n del file di giudizi sui ricordi non valida.")
      }
    }
    return try {
      MemoriesLabelSet(run, judgements, faults)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("Il file di giudizi sui ricordi non e' coerente.")
    }
  }

  private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

  private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        i++
        out.append(
          when (s[i]) {
            'n' -> '\n'
            't' -> '\t'
            else -> s[i]
          },
        )
      } else {
        out.append(c)
      }
      i++
    }
    return out.toString()
  }
}
