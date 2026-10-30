package dev.pampa.mycloudgallery.core.memories.eval

import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// LA LIBRERIA DI PROVA PER IL TELEFONO (docs/fase9-progetto.md §5.3, riga P11 del §4.4; tools/fixtures/memories/): la stessa Libreria sintetica "di una famiglia" scritta
// come testo, con **giorni relativi al giorno della prova** (mai una data), da cui `generate_memories.py` ricava le immagini con l'EXIF. La fonte e' il codice
// Kotlin: quello che il generatore disegna e quello che la JVM misura sono la stessa cosa.
//
// Una riga per foto, colonne separate da tabulazione (la prima riga comincia con `#`):
//   n, anni_fa, giorni_fa (il giorno e' `oggi - giorni_fa giorni - anni_fa anni`, come `relative` del generatore: gli anniversari restano anniversari per qualunque oggi),
//   secondo (del giorno locale), fonte (camera | screenshot | whatsapp), tipo (foto | video | animata), documento (0/1: il riconoscimento l'ha visto), concetto, persone
//   (a;b;c o -), luogo (id finto), mossa (0/1), buia (0/1), nascosta (0/1: da nascondere a mano sul telefono per provare che non esce mai).
// Il concetto e' il seme dei vettori finti (`MemoryVectors` di prova): `u` unica, `g<N>` ripresa ripetuta del gruppo N (doppioni), `s<N>` simile ma diversa dalla foto N,
// `triste` (un concetto triste: solo una misura, Q6 (a)), `documento` (un documento sfuggito al riconoscimento), `estranea` (non c'entra), `perfetta` (nascosta).

internal object MemoriesFixtureKit {
  const val HEADER = "#n\tanni_fa\tgiorni_fa\tsecondo\tfonte\ttipo\tdocumento\tconcetto\tpersone\tluogo\tmossa\tbuia\tnascosta"

  /** Dal giorno [day] a `(anni_fa, giorni_fa)` rispetto a [today]: il giorno e' `today - giorni_fa giorni - anni_fa anni` (prima i giorni, poi gli anni). */
  fun relative(today: LocalDate, day: LocalDate): Pair<Int, Int> {
    for (years in 0..MAX_YEARS) {
      val days = ChronoUnit.DAYS.between(day.plusYears(years.toLong()), today)
      if (days in 0..365 && today.minusDays(days).minusYears(years.toLong()) == day) return years to days.toInt()
    }
    error("Giorno non rappresentabile in modo relativo.")
  }

  /** Il giorno di una riga per un [today] qualunque (la stessa formula del generatore Python). */
  fun dayOf(today: LocalDate, years: Int, days: Int): LocalDate = today.minusDays(days.toLong()).minusYears(years.toLong())

  fun rows(world: FamilyWorld): List<String> {
    val letters = FamilyBuilder.PEOPLE.mapIndexed { index, id -> id to ('a' + index) }.toMap()
    val groupNumber = HashMap<Int, Int>()
    val ids = world.shots.keys.toList().withIndex().associate { it.value to it.index + 1 }
    val out = ArrayList<String>()
    out += HEADER
    for ((id, shot) in world.shots) {
      val (years, days) = relative(world.today, shot.day)
      val concept = when {
        id in world.hiddenPerfect -> "perfetta"
        shot.dupGroup != null -> "g" + groupNumber.getOrPut(shot.dupGroup!!) { groupNumber.size + 1 }
        shot.similarTo != null -> "s" + ids.getValue(shot.similarTo!!)
        shot.fault == EvalFault.SAD -> "triste"
        shot.fault == EvalFault.SCREENSHOT_OR_DOC -> "documento"
        shot.fault == EvalFault.UNRELATED && shot.source != MediaSource.WHATSAPP -> "estranea"
        else -> "u"
      }
      val people = world.personMedia.filterValues { id in it }.keys.map { letters.getValue(it) }.sorted().joinToString(";").ifEmpty { "-" }
      out += listOf(
        ids.getValue(id), years, days, shot.secondOfDay,
        when (shot.source) { MediaSource.SCREENSHOT -> "screenshot"; MediaSource.WHATSAPP -> "whatsapp"; else -> "camera" },
        when (shot.kind) { MediaKind.VIDEO -> "video"; MediaKind.ANIMATED_IMAGE -> "animata"; else -> "foto" },
        if (CellFlags.has(shot.flags, CellFlags.DOCUMENT)) 1 else 0,
        concept, people, world.library.shot(id).place, if (shot.blurry) 1 else 0, if (shot.dark) 1 else 0, if (id in world.library.visibility.hidden) 1 else 0,
      ).joinToString("\t")
    }
    return out
  }

  /** I numeri che la JVM attende dal riferimento sulla stessa Libreria, per confrontarli con quello che l'app del telefono mostrera' (solo numeri, nessuna data). */
  fun expectations(world: FamilyWorld, run: List<EvalMemory>): List<String> {
    val numbers = MemoriesEvalMetrics.of(FamilyIdealUser.label(world, run))
    val byKind = run.groupingBy { it.kind }.eachCount().toSortedMap()
    return listOf(
      "# i numeri della JVM sulla stessa Libreria (riferimento, ultimi 45 giorni): chiave, valore",
      "ricordi\t${run.size}",
      "foto\t${numbers.photos}",
    ) + byKind.map { (kind, count) -> "ricordi_$kind\t$count" } + listOf(
      "bello\t${numbers.good}",
      "cosi_cosi\t${numbers.soSo}",
      "da_non_mostrare\t${numbers.dontShow}",
      "foto_da_togliere\t${numbers.faults}",
    ) + numbers.faultsByKind.entries.sortedBy { it.key.code }.map { (fault, count) -> "da_togliere_${MemoriesEvalReport.faultName(fault).replace(' ', '_').replace("'", "")}\t$count" }

  }

  private const val MAX_YEARS = 8
}
