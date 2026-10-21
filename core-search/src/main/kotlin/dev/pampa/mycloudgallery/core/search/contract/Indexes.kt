package dev.pampa.mycloudgallery.core.search.contract

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate
import java.time.MonthDay

// Gli INDICI del motore (D24, D25) e il SIGNIFICATO DEI CHIP sulle celle (§2.5). Firme di P0 per P6 (indice a due passate in memoria
// e mappato, FTS4 con vocabolario dei refusi); la semantica dei filtri e' condivisa dal motore vero e da quello di riferimento,
// cosi' la parita' del §5.2 confronta i punteggi e non due letture diverse dei chip.

/** I candidati di una ricerca: posizioni dell'istantanea (crescenti), gia' filtrate dai chip. */
class CandidateSet(val positions: IntArray) {
  val size: Int get() = positions.size
}

/** I punteggi della prima passata, uno per candidato (stesso ordine di [CandidateSet.positions]). */
class ScoreBlock(size: Int) {
  val scores: FloatArray = FloatArray(size)
}

/** L'indice vettoriale (D24): prima passata int8 a 256 dimensioni sui candidati, seconda passata float16 a 768 sui migliori. */
interface VectorIndex {
  fun coarse(query: FloatArray, candidates: CandidateSet, out: ScoreBlock)

  fun rerank(query: FloatArray, rows: IntArray): FloatArray
}

/** Le celle che il testo letto ha trovato: tutte le parole (sicuro) o almeno meta' (meno probabile, D27), anche con un refuso. */
class TextMatches(val all: IntArray, val half: IntArray)

/** L'indice del testo (D25): parole sempre letterali (nessuna sintassi `MATCH` dall'utente, E-Q9); refusi sul vocabolario locale. */
interface TextIndex {
  fun match(words: List<String>, exact: List<String>, candidates: CandidateSet): TextMatches

  /** Completamenti dal vocabolario: **mai** proposti nella schermata Cerca (§2.6, D29), servono alle correzioni. */
  fun complete(prefix: String, limit: Int): List<String>
}

/**
 * Il significato dei chip sulle celle (§2.5): le date sul giorno **nell'ora del posto dello scatto** (`localDayAt`), i luoghi sugli
 * id GeoNames, i tipi su tipo, sorgente e bit della cella, la presenza sui bit di presenza. Puro: lo usano il motore vero e quello di
 * riferimento. Un chip che non e' un filtro (parola, simili) non esclude niente.
 */
object ChipMatching {

  fun accepts(chips: List<SearchChip>, snapshot: TimelineSnapshot, i: Int): Boolean = chips.all { accepts(it, snapshot, i) }

  fun accepts(chip: SearchChip, snapshot: TimelineSnapshot, i: Int): Boolean = when (chip) {
    is SearchChip.DateRange -> inRange(chip, dayOf(snapshot.localDayAt(i)))
    is SearchChip.Place -> snapshot.placeAt(i) in chip.option.ids
    is SearchChip.MediaType -> when (chip.type) {
      SearchMediaType.PHOTO -> snapshot.kindAt(i) != MediaKind.VIDEO
      SearchMediaType.VIDEO -> snapshot.kindAt(i) == MediaKind.VIDEO
      SearchMediaType.SCREENSHOT -> snapshot.sourceAt(i) == MediaSource.SCREENSHOT
      SearchMediaType.PANORAMA -> snapshot.hasFlag(i, CellFlags.PANORAMA)
      SearchMediaType.MOTION_PHOTO -> snapshot.kindAt(i) == MediaKind.MOTION_PHOTO || snapshot.hasFlag(i, CellFlags.MOTION)
      SearchMediaType.DOCUMENT -> snapshot.hasFlag(i, CellFlags.DOCUMENT)
    }
    is SearchChip.Source -> snapshot.sourceAt(i) == chip.source
    is SearchChip.Flag -> when (chip.flag) {
      SearchFlag.FAVORITE -> snapshot.hasFlag(i, CellFlags.FAVORITE)
      SearchFlag.EDITED -> snapshot.hasFlag(i, CellFlags.EDITED)
    }
    is SearchChip.Presence -> when (chip.where) {
      SearchPresence.ON_PHONE -> snapshot.hasFlag(i, CellFlags.ON_PHONE)
      SearchPresence.ON_NAS -> snapshot.hasFlag(i, CellFlags.ON_NAS)
      SearchPresence.NAS_ONLY -> snapshot.hasFlag(i, CellFlags.ON_NAS) && !snapshot.hasFlag(i, CellFlags.ON_PHONE)
    }
    is SearchChip.Word, is SearchChip.Similar -> true
  }

  /** `aaaammgg` -> data, o null per un giorno non valido (una cella senza data non entra in nessun periodo). */
  fun dayOf(localDay: Int): LocalDate? = runCatching { LocalDate.of(localDay / 10_000, localDay / 100 % 100, localDay % 100) }.getOrNull()

  private fun inRange(chip: SearchChip.DateRange, day: LocalDate?): Boolean {
    if (day == null) return false
    if (!chip.everyYear) return !day.isBefore(chip.from) && !day.isAfter(chip.to)
    val from = MonthDay.from(chip.from)
    val to = MonthDay.from(chip.to)
    val d = MonthDay.from(day)
    // "Capodanno" (31/12-1/1) attraversa la fine dell'anno.
    return if (!to.isBefore(from)) !d.isBefore(from) && !d.isAfter(to) else !d.isBefore(from) || !d.isAfter(to)
  }
}
