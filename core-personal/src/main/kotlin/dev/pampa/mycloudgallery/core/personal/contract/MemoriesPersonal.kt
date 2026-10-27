package dev.pampa.mycloudgallery.core.personal.contract

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.StateFlow

// I RICORDI NEI DATI PERSONALI (docs/fase9-progetto.md §1.2 "core-personal", §2.3, D10, D11, risposta Q9 (a), E-MEM8, E-MEM9, E-MEM22;
// formato-nas.md §5.5-bis A1, A2). Contratto di P0: chiavi, valori e la **sola** regola di lettura ([MemoriesRecords.read]) delle voci che
// viaggiano fra i dispositivi dello stesso account. L'implementazione vera della porta e' `SyncedMemoriesSettings` (P6, sopra
// `PersonalStore.effectiveEntries`); il fake e' in `testing/` e passa la stessa suite (`MemoriesPersonalSettingsContract`).
//
// Cosa viaggia (nell'area privata, con il giro esistente dei dati personali; nessun tipo nuovo, nessuna versione nuova del formato):
//  - `memory`/<id>/`seen` = il giorno locale della visione (`aaaammgg`);
//  - `memory`/<id>/`dismissed` = vero ("Non mostrare"); lapide = "Annulla";
//  - `setting`/`settings`/`memories_less:person:<id della persona in base32>` = `less` o `never`; lapide = scelta tolta;
//  - `setting`/`settings`/`memories_never:days:<aaaammgg>-<aaaammgg>` = vero; lapide = periodo tolto;
//  - `setting`/`settings`/`memories_people` = vero/falso (Q9 (a): assente = acceso).
// **Mai un nome, mai un titolo, mai un luogo**: gli id dei ricordi sono hash (D9), quelli delle persone casuali (F8-D22). Un valore che non si
// capisce vale "nessuna scelta" e non rovina le altre voci (un'app piu' nuova puo' scrivere forme che questa non conosce).

/** I due livelli di "Mostra meno" per una persona (D11). Il codice e' il valore della voce: non cambia mai. */
enum class MemoryLessLevel(override val code: String) : StableCode {
  /** Niente ricordi dedicati, mai copertina, mai nella notifica; puo' comparire nelle foto di gruppo (E-MEM11). */
  LESS("less"),

  /** "Mai nei ricordi": nessuna sua foto nei ricordi. */
  NEVER("never"),
  ;

  companion object {
    fun ofCode(code: String): MemoryLessLevel? = entries.firstOrNull { it.code == code }
  }
}

/**
 * Un periodo "Mai nei ricordi" (D11): giorni **locali** dello scatto da [from] a [to] compresi, nel formato `aaaammgg`; al piu'
 * [MAX_DAYS] giorni a voce (un periodo piu' lungo sono piu' voci). Nel `toString()` solo quanti giorni: le date dicono dove e quando si era.
 */
class MemoryDays(val from: Int, val to: Int) {
  init {
    val first = dateOf(from)
    val last = dateOf(to)
    require(first != null && last != null) { "Giorno non valido." }
    require(!last.isBefore(first)) { "Periodo rovesciato." }
    require(ChronoUnit.DAYS.between(first, last) + 1 <= MAX_DAYS) { "Un periodo e' al piu' di $MAX_DAYS giorni." }
  }

  val first: LocalDate get() = dateOf(from)!!
  val last: LocalDate get() = dateOf(to)!!

  /** Quanti giorni (almeno 1). */
  val count: Int get() = (ChronoUnit.DAYS.between(first, last) + 1).toInt()

  /** Se il giorno [day] (`aaaammgg`) sta nel periodo: il formato si ordina come le date. */
  operator fun contains(day: Int): Boolean = day in from..to

  fun contains(day: LocalDate): Boolean = dayOf(day) in from..to

  override fun equals(other: Any?): Boolean = other is MemoryDays && other.from == from && other.to == to

  override fun hashCode(): Int = from * 31 + to

  override fun toString(): String = "MemoryDays($count giorni)"

  companion object {
    const val MAX_DAYS: Int = 366

    /** Il giorno `aaaammgg` di [date]. */
    fun dayOf(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

    /** La data di un giorno `aaaammgg`, o null se non e' una data (anni dal 1900 al 2999). */
    fun dateOf(day: Int): LocalDate? {
      val year = day / 10_000
      if (year !in 1900..2999) return null
      return try {
        LocalDate.of(year, (day / 100) % 100, day % 100)
      } catch (_: DateTimeException) {
        null
      }
    }

    fun of(first: LocalDate, last: LocalDate): MemoryDays = MemoryDays(dayOf(first), dayOf(last))

    /** Il periodo dei due giorni, o null se non e' un periodo valido (date, ordine, lunghezza). */
    fun parse(from: Int, to: Int): MemoryDays? = try {
      MemoryDays(from, to)
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}

/**
 * Le scelte dell'utente sui ricordi, come le vede QUESTO dispositivo (stato unito + scelte "da datare", E-MEM22). [seen]: id del ricordo ->
 * giorno locale dell'ultima visione; [dismissed]: ricordi "Non mostrare"; [people]: persona -> livello; [neverDays]: periodi "Mai";
 * [peopleMemories]: "Ricordi con le persone" (Q9 (a), di serie acceso). Nel `toString()` solo i conteggi.
 */
data class MemoriesChoices(
  val seen: Map<String, Int> = emptyMap(),
  val dismissed: Set<String> = emptySet(),
  val people: Map<PersonId, MemoryLessLevel> = emptyMap(),
  val neverDays: Set<MemoryDays> = emptySet(),
  val peopleMemories: Boolean = true,
) {
  /** Se il giorno [day] (`aaaammgg`) e' dentro un periodo "Mai" (vale l'unione, E-MEM12). */
  fun neverOn(day: Int): Boolean = neverDays.any { day in it }

  override fun toString(): String =
    "MemoriesChoices(visti=${seen.size}, scartati=${dismissed.size}, persone=${people.size}, periodi=${neverDays.size}, persone=$peopleMemories)"

  companion object {
    val NONE: MemoriesChoices = MemoriesChoices()
  }
}

/**
 * Le chiavi, i valori e la lettura delle voci dei ricordi (D10, D11). E' la sola regola di lettura: la usano `SyncedMemoriesSettings` (P6),
 * il fake e i test, cosi' un valore strano vale "nessuna scelta" in un posto solo.
 */
object MemoriesRecords {
  /** Un id di ricordo: 128 bit in base32 di Crockford minuscolo, 26 caratteri, forma canonica (D9). */
  const val MEMORY_ID_CHARS: Int = 26

  fun isMemoryId(text: String): Boolean = text.length == MEMORY_ID_CHARS && Base32.isCanonical(text, 16)

  fun seenKey(memoryId: String): PersonalKey = PersonalKey(PersonalKind.MEMORY, checkedId(memoryId), PersonalFields.MEMORY_SEEN)

  fun dismissedKey(memoryId: String): PersonalKey = PersonalKey(PersonalKind.MEMORY, checkedId(memoryId), PersonalFields.MEMORY_DISMISSED)

  fun lessKey(person: PersonId): PersonalKey = setting(PersonalFields.memoriesLessPerson(person.base32()))

  fun neverKey(days: MemoryDays): PersonalKey = setting(PersonalFields.memoriesNeverDays(days.from, days.to))

  val peopleKey: PersonalKey = setting(PersonalFields.SETTING_MEMORIES_PEOPLE)

  /** Il valore di "visto" il giorno [day] (`aaaammgg`). */
  fun seenValue(day: Int): PersonalValue {
    require(MemoryDays.dateOf(day) != null) { "Giorno non valido." }
    return PersonalValue.Number(day.toLong())
  }

  fun lessValue(level: MemoryLessLevel?): PersonalValue = if (level == null) PersonalValue.Removed else PersonalValue.Text(level.code)

  /** Vero se [key] e' una voce dei ricordi (per chi conta o ripulisce: il janitor delle persone, l'azzeramento dei test). */
  fun isMemoriesKey(key: PersonalKey): Boolean = when (key.kind) {
    PersonalKind.MEMORY -> key.field == PersonalFields.MEMORY_SEEN || key.field == PersonalFields.MEMORY_DISMISSED
    PersonalKind.SETTING -> key.subject == PersonalFields.SETTINGS_SUBJECT && (
      key.field == PersonalFields.SETTING_MEMORIES_PEOPLE ||
        key.field.startsWith("${PersonalFields.SETTING_MEMORIES_LESS}:") ||
        key.field.startsWith("${PersonalFields.SETTING_MEMORIES_NEVER}:")
      )
    else -> false
  }

  /** Le scelte di [entries] (lo stato "effettivo" del dispositivo). Ogni voce che non si capisce vale "nessuna scelta". */
  fun read(entries: Map<PersonalKey, PersonalEntry>): MemoriesChoices {
    val seen = HashMap<String, Int>()
    val dismissed = HashSet<String>()
    val people = HashMap<PersonId, MemoryLessLevel>()
    val never = HashSet<MemoryDays>()
    var peopleMemories = true
    for ((key, entry) in entries) {
      val value = entry.value
      when (key.kind) {
        PersonalKind.MEMORY -> {
          if (!isMemoryId(key.subject)) continue
          when (key.field) {
            PersonalFields.MEMORY_SEEN -> {
              val day = (value as? PersonalValue.Number)?.value ?: continue
              if (day in 0..Int.MAX_VALUE.toLong() && MemoryDays.dateOf(day.toInt()) != null) seen[key.subject] = day.toInt()
            }
            PersonalFields.MEMORY_DISMISSED -> if ((value as? PersonalValue.Flag)?.on == true) dismissed += key.subject
          }
        }
        PersonalKind.SETTING -> {
          if (key.subject != PersonalFields.SETTINGS_SUBJECT) continue
          val field = key.field
          if (field == PersonalFields.SETTING_MEMORIES_PEOPLE) {
            (value as? PersonalValue.Flag)?.let { peopleMemories = it.on }
            continue
          }
          PersonalFields.memoriesLessPersonOf(field)?.let { b32 ->
            val person = PersonId.fromBase32(b32) ?: return@let
            val level = (value as? PersonalValue.Text)?.let { MemoryLessLevel.ofCode(it.text) } ?: return@let
            people[person] = level
          }
          PersonalFields.memoriesNeverDaysOf(field)?.let { (from, to) ->
            if ((value as? PersonalValue.Flag)?.on != true) return@let
            MemoryDays.parse(from, to)?.let { never += it }
          }
        }
        else -> Unit
      }
    }
    return MemoriesChoices(seen, dismissed, people, never, peopleMemories)
  }

  private fun setting(field: String) = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, field)

  private fun checkedId(memoryId: String): String {
    require(isMemoryId(memoryId)) { "Id di ricordo non valido." }
    return memoryId
  }
}

/**
 * Le scelte sui ricordi dell'account, come le vede QUESTO dispositivo (D10, D11, Q9). Lo stesso modello della voce `faces` e della lettura del
 * testo: **si legge lo stato "effettivo"** (anche le scelte fatte qui senza l'ora del NAS, E-MEM22), si scrive con `PersonalStore.set` (la
 * scelta vale subito qui e viaggia alla prima sincronizzazione), fra due dispositivi vince l'ultima scrittura per ora del NAS (`PersonalMerge`).
 * Nessuna di queste firme prende un nome, un titolo o un luogo.
 */
interface MemoriesPersonalSettings {
  /** Le scelte di adesso. Un flusso: puo' ritardare di un istante rispetto a [current]. */
  val choices: StateFlow<MemoriesChoices>

  /** Le stesse scelte, lette in modo sincrono (valgono subito dopo una scrittura). */
  fun current(): MemoriesChoices

  /**
   * "Visto" il giorno [day] (`aaaammgg`, giorno locale del dispositivo, D36): si scrive solo se cambia il giorno (un giorno piu' vecchio di
   * quello che c'e' non torna indietro), cosi' aprire due volte lo stesso ricordo non fa crescere il registro.
   */
  suspend fun markSeen(memoryId: String, day: Int)

  /** "Non mostrare questo ricordo" ([dismissed] vero) o "Annulla" (falso: la lapide). */
  suspend fun setDismissed(memoryId: String, dismissed: Boolean)

  /** "Mostra meno" o "Mai nei ricordi" per [person]; null = scelta tolta (lapide). */
  suspend fun setLess(person: PersonId, level: MemoryLessLevel?)

  /** "Non ricordarmi questo periodo" ([never] vero) o la scelta tolta (falso: la lapide). */
  suspend fun setNeverDays(days: MemoryDays, never: Boolean)

  /** "Ricordi con le persone" (Q9). */
  suspend fun setPeopleMemories(on: Boolean)

  /**
   * D11, D37: Persone spenta o cancellata ("Disattiva e cancella"): **tutte** le voci `memories_less:person:*` diventano lapidi, anche quelle
   * di persone che questo dispositivo non conosce. Risponde quante voci ha tolto. La chiama `PeopleLessJanitor` di core-memories (P8).
   */
  suspend fun dropAllPeopleChoices(): Int
}
