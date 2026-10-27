package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// L'IDENTITA' DEI RICORDI (docs/fase9-progetto.md §1.2 "core-memories", D3, D9, E-MEM8, E-MEM9). Contratto di P0. Un ricordo ha un id
// **deterministico**: lo stesso ricordo generato su due dispositivi dello stesso account (ognuno dalla sua Libreria, D10) ha lo stesso id, cosi'
// "visto" e "Non mostrare" valgono ovunque senza sincronizzare i contenuti. L'id e' un hash: non rivela giorno, viaggio o persona.
// **Nessun tipo di questo file mostra date, giorni o id nel `toString()`** (D33): dicono dove si era e quando.

/** I tipi di ricordo (D3). Il codice entra nell'id ([MemoryIds]) e nello scaffale: non cambia mai. */
enum class MemoryKind(override val code: String) : StableCode {
  /** "N anni fa oggi": i media di un giorno di un anno passato. */
  ON_THIS_DAY("otd"),

  /** Un viaggio della Fase 7 (anniversario o riscoperta). */
  TRIP("trip"),

  /** Un evento della Fase 7 (molte foto in poche ore): anniversario. */
  EVENT("event"),

  /** Una persona con un nome (Q9 (a), solo con Persone attiva qui). */
  PERSON("person"),

  /** "Il meglio del mese" (dal 1° al 7, per il mese prima). */
  MONTH("month"),
  ;

  companion object {
    fun ofCode(code: String): MemoryKind? = entries.firstOrNull { it.code == code }
  }
}

/**
 * Un intervallo di giorni **locali dello scatto** ([from] e [to] compresi). E' di questo modulo perche' core-memories non vede core-albums
 * (dove sta `LocalDayRange`). Nel `toString()` solo quanti giorni.
 */
data class DayRange(val from: LocalDate, val to: LocalDate) {
  init {
    require(!to.isBefore(from)) { "Intervallo di giorni rovesciato." }
  }

  operator fun contains(day: LocalDate): Boolean = !day.isBefore(from) && !day.isAfter(to)

  /** Quanti giorni (almeno 1). */
  val days: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()

  override fun toString(): String = "DayRange($days giorni)"

  companion object {
    fun of(day: LocalDate): DayRange = DayRange(day, day)
  }
}

/** La finestra di un ricordo di una persona (D3): un anno con abbastanza foto o, altrimenti, "negli ultimi mesi". */
sealed interface PersonWindow {
  data class Year(val year: Int) : PersonWindow {
    override fun toString(): String = "PersonWindow.Year"
  }

  data object Recent : PersonWindow
}

/**
 * La chiave di un ricordo (D9): cio' che lo rende **lo stesso** su ogni dispositivo. Il testo ([text]) entra nell'hash dell'id e nello
 * scaffale; nei log mai (`toString()` dice solo il tipo).
 */
sealed interface MemoryKey {
  val kind: MemoryKind

  /** La forma testuale della chiave: `otd:<aaaa-mm-gg>`, `trip:<inizio>:<fine>`, `event:<giorno>:<quarto d'ora>`, `person:<id>:<anno|recenti>`, `month:<aaaa-mm>`. */
  val text: String

  /** "N anni fa oggi": [day] e' il giorno d'origine (un 29 febbraio resta 29 febbraio anche se esce il 28, E-MEM3). */
  data class OnThisDay(val day: LocalDate) : MemoryKey {
    override val kind: MemoryKind get() = MemoryKind.ON_THIS_DAY
    override val text: String get() = "otd:$day"

    override fun toString(): String = "MemoryKey(otd)"
  }

  data class Trip(val days: DayRange) : MemoryKey {
    override val kind: MemoryKind get() = MemoryKind.TRIP
    override val text: String get() = "trip:${days.from}:${days.to}"

    override fun toString(): String = "MemoryKey(trip)"
  }

  /** Un evento: il giorno e il quarto d'ora locale del primo scatto (0..95). */
  data class Event(val day: LocalDate, val startQuarter: Int) : MemoryKey {
    init {
      require(startQuarter in 0 until 96) { "Quarto d'ora fuori intervallo." }
    }

    override val kind: MemoryKind get() = MemoryKind.EVENT
    override val text: String get() = "event:$day:$startQuarter"

    override fun toString(): String = "MemoryKey(event)"
  }

  /** Una persona: solo l'id casuale (in base32 nel testo), mai il nome (D11, D12). */
  data class Person(val person: PersonId, val window: PersonWindow) : MemoryKey {
    override val kind: MemoryKind get() = MemoryKind.PERSON
    override val text: String
      get() = "person:${person.base32()}:" + when (window) {
        is PersonWindow.Year -> window.year.toString()
        PersonWindow.Recent -> "recenti"
      }

    override fun toString(): String = "MemoryKey(person)"
  }

  data class Month(val month: YearMonth) : MemoryKey {
    override val kind: MemoryKind get() = MemoryKind.MONTH
    override val text: String get() = "month:$month"

    override fun toString(): String = "MemoryKey(month)"
  }
}

/**
 * L'id di un ricordo (D9): i primi 128 bit di SHA-256("mcg-memory\0" ‖ tipo ‖ "\0" ‖ chiave), in base32 di Crockford minuscolo, 26
 * caratteri, forma canonica. E' anche il soggetto delle voci `memory` dei dati personali (`MemoriesRecords.isMemoryId` lo accetta: lo prova
 * `ContractTypesTest`). Mai nei log.
 */
@JvmInline
value class MemoryId(val value: String) {
  init {
    require(MemoryIds.isCanonical(value)) { "Id di ricordo non valido." }
  }

  override fun toString(): String = "MemoryId"
}

object MemoryIds {
  const val CHARS: Int = 26

  private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

  fun of(key: MemoryKey): MemoryId {
    val md = MessageDigest.getInstance("SHA-256")
    md.update("mcg-memory\u0000${key.kind.code}\u0000${key.text}".toByteArray(Charsets.UTF_8))
    return MemoryId(encode(md.digest().copyOf(16)))
  }

  /** 26 caratteri dell'alfabeto di Crockford minuscolo, con gli ultimi due bit a zero (128 bit esatti: un solo modo di scriverli). */
  fun isCanonical(text: String): Boolean {
    if (text.length != CHARS) return false
    for (c in text) if (ALPHABET.indexOf(c) < 0) return false
    return ALPHABET.indexOf(text.last()) and 0x3 == 0
  }

  private fun encode(bytes: ByteArray): String {
    val out = StringBuilder(CHARS)
    var buffer = 0
    var bits = 0
    for (b in bytes) {
      buffer = (buffer shl 8) or (b.toInt() and 0xff)
      bits += 8
      while (bits >= 5) {
        out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
        bits -= 5
      }
      buffer = buffer and 0xff
    }
    if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    return out.toString()
  }
}
