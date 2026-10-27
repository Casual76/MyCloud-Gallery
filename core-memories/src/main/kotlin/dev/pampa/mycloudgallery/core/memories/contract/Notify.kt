package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.StateFlow

// LE NOTIFICHE DEI RICORDI (docs/fase9-progetto.md §1.2 "Notifiche (decisioni)", §2.6, D15-D20; risposte Q4 e Q5 (a) del 27/10). Contratto di
// P0: preferenze, registro del giorno, regole e la porta delle decisioni (pura); l'implementazione vera e' `MemoryNotifyPolicy` di P10
// (notify/), il riferimento e' `ReferenceNotifyPolicy` (testing/) con la suite `NotifyPolicyContract`. Canale, notifica e `MainActivity` sono
// di P10 in :app. **Q4**: di base al piu' 3 a settimana, nei giorni speciali (anniversari di viaggi ed eventi) fino a una al giorno, **mai piu'
// di una al giorno**. **Q5 (a)**: di serie la notifica dice solo "Un ricordo per te": niente titolo, luogo, data o foto, nemmeno con "Mostra
// tutti i contenuti" del sistema (D16). Mai il nome di una persona, in nessuna modalita'.

/** Quante notifiche (Q4). Il codice e' una preferenza locale: non cambia. */
enum class NotifyFrequency(override val code: String) : StableCode {
  /**
   * La predefinita (Q4): di base al piu' [NotifyRules.WEEKLY_CAP] in 7 giorni e mai due giorni di fila; un ricordo **speciale** (anniversario di
   * un viaggio o di un evento) arriva anche oltre il tetto, sempre al piu' uno al giorno.
   */
  FEW_PER_WEEK("few_per_week"),

  /** Al piu' una al giorno, solo quando c'e' un bel ricordo. */
  DAILY("daily"),

  /** Solo gli anniversari di viaggi ed eventi. */
  ANNIVERSARIES("anniversaries"),
  ;

  companion object {
    fun ofCode(code: String): NotifyFrequency? = entries.firstOrNull { it.code == code }
  }
}

/** Cosa si vede a telefono bloccato (D16, Q5 (a)). */
enum class LockScreenMode(override val code: String) : StableCode {
  /** La predefinita (Q5 (a)): "Un ricordo per te · Tocca per vederlo", senza titolo, luogo, data ne' foto. */
  GENERIC("generic"),

  /** Versione privata con il titolo (mai nomi) e la miniatura locale della copertina; versione pubblica generica. */
  TITLE_AND_PHOTO("title_and_photo"),
  ;

  companion object {
    fun ofCode(code: String): LockScreenMode? = entries.firstOrNull { it.code == code }
  }
}

/**
 * Le preferenze delle notifiche di QUESTO dispositivo (e profilo del tablet, E-NT10): locali, non sincronizzate (D10). [hour] a passi di 15
 * minuti, di serie le 19:00. Nel `toString()` niente ora (D33).
 */
data class NotifyPrefs(
  val enabled: Boolean = true,
  val hour: LocalTime = NotifyRules.DEFAULT_HOUR,
  val frequency: NotifyFrequency = NotifyFrequency.FEW_PER_WEEK,
  val lockScreen: LockScreenMode = LockScreenMode.GENERIC,
) {
  init {
    require(hour.second == 0 && hour.nano == 0 && hour.minute % NotifyRules.HOUR_STEP_MINUTES == 0) { "L'ora va a passi di 15 minuti." }
  }

  override fun toString(): String = "NotifyPrefs(accese=$enabled, ${frequency.code}, ${lockScreen.code})"
}

/** Un giorno con una notifica nel registro locale (`noBackupFilesDir/memories/notified`): [special] se era un anniversario. */
data class NotifyMark(val day: Int, val special: Boolean)

/** Il registro locale delle notifiche (D15, E-NT5): gli ultimi [NotifyRules.LEDGER_DAYS] giorni. */
data class NotifyLedger(val marks: List<NotifyMark> = emptyList()) {
  fun notifiedOn(day: LocalDate): Boolean = marks.any { it.day == dayOf(day) }

  /** Quante notifiche nei giorni da [from] a [to] compresi. */
  fun countIn(from: LocalDate, to: LocalDate): Int = marks.count { it.day in dayOf(from)..dayOf(to) }

  /** Il registro con [mark] in piu' e senza i giorni piu' vecchi di [NotifyRules.LEDGER_DAYS] rispetto a [today]. */
  fun with(mark: NotifyMark, today: LocalDate): NotifyLedger {
    val oldest = dayOf(today.minusDays(NotifyRules.LEDGER_DAYS - 1L))
    return NotifyLedger((marks.filter { it.day != mark.day } + mark).filter { it.day >= oldest }.sortedBy { it.day })
  }

  override fun toString(): String = "NotifyLedger(${marks.size})"

  companion object {
    fun dayOf(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
  }
}

/** Le regole fisse delle notifiche (D15, D17, D20, Q4). */
object NotifyRules {
  val DEFAULT_HOUR: LocalTime = LocalTime.of(19, 0)
  const val HOUR_STEP_MINUTES: Int = 15

  /** La finestra di consegna (D20): da H a H+2 h e, in ritardo, fino a H+3 h; oltre, quel giorno si salta (E-NT3). */
  const val WINDOW_MINUTES: Long = 120
  const val SKIP_AFTER_MINUTES: Long = 180

  /** Oltre H+1 h la consegna conta come in ritardo (`NOTIFY_LATE`, decide il piano B di D20). */
  const val LATE_AFTER_MINUTES: Long = 60

  /** Q4: di base al piu' 3 in 7 giorni, mai due giorni di fila; mai piu' di una al giorno. */
  const val WEEKLY_CAP: Int = 3
  const val WEEK_DAYS: Long = 7
  const val LEDGER_DAYS: Int = 14

  /** Il canale, l'id e l'azione fissa (D15, D17): l'intent non porta extra, dati ne' id. */
  const val CHANNEL_ID: String = "memories"
  const val NOTIFICATION_ID: Int = 1009
  const val OPEN_ACTION: String = "dev.pampa.mycloudgallery.action.OPEN_MEMORIES"

  /** Un ricordo speciale per Q4: l'anniversario di un viaggio o di un evento (non una riscoperta). */
  fun isSpecial(memory: Memory): Boolean =
    (memory.kind == MemoryKind.TRIP || memory.kind == MemoryKind.EVENT) && memory.title.yearsAgo != null
}

/**
 * Un ricordo che potrebbe diventare la notifica di oggi: [seen] e [dismissed] dalle scelte, [visibleMembers] quanti elementi restano dopo
 * `HiddenMask` (ricontrollo di D19), [lessPerson] per un ricordo `PERSON` la cui persona ha "Mostra meno" o "Mai".
 */
data class NotifyCandidate(
  val memory: Memory,
  val seen: Boolean,
  val dismissed: Boolean,
  val visibleMembers: Int,
  val lessPerson: Boolean,
) {
  override fun toString(): String = "NotifyCandidate(${memory.kind.code}, visto=$seen, $visibleMembers)"
}

/**
 * Un testo della notifica come **id di stringa** con argomenti (D16): [numbers] solo numeri (anni, id GeoNames di un luogo, anno e mese),
 * [label] solo il titolo di un viaggio della Fase 7 in "Titolo e foto"; **mai** testo dell'utente nella modalita' generica, mai un nome.
 */
data class TextRef(val key: String, val numbers: List<Int> = emptyList(), val label: String? = null) {
  override fun toString(): String = "TextRef($key, ${numbers.size}, etichetta=${label != null})"
}

/** Gli id di stringa delle notifiche (P10 li lega alle risorse `strings_memories_notify.xml`). */
object NotifyTexts {
  const val GENERIC_TITLE: String = "memories_notify_generic_title"
  const val GENERIC_TEXT: String = "memories_notify_generic_text"
  const val TITLED_TEXT: String = "memories_notify_titled_text"
  const val TITLE_YEARS: String = "memories_notify_title_years"
  const val TITLE_YEARS_PLACE: String = "memories_notify_title_years_place"
  const val TITLE_TRIP: String = "memories_notify_title_trip"
  const val TITLE_MONTH: String = "memories_notify_title_month"

  val GENERIC: Set<String> = setOf(GENERIC_TITLE, GENERIC_TEXT)
}

/** Il contenuto (D16): versione privata ([title], [text], [image]) e pubblica ([publicTitle], [publicText]), sempre generica. */
data class NotifyContent(
  val title: TextRef,
  val text: TextRef,
  val image: MediaId?,
  val publicTitle: TextRef,
  val publicText: TextRef,
) {
  override fun toString(): String = "NotifyContent(${title.key}, immagine=${image != null})"
}

/** La decisione all'ora della notifica (D20): consegnare per il giorno [slotDay] (in ritardo oltre H+1 h) o aspettare il prossimo orario. */
sealed interface SlotDecision {
  data class Deliver(val slotDay: LocalDate, val late: Boolean) : SlotDecision {
    override fun toString(): String = "SlotDecision.Deliver(late=$late)"
  }

  /** [skipped]: l'ultimo orario e' passato da oltre H+3 h e quel giorno si salta (E-NT3). */
  data class Wait(val next: LocalDateTime, val skipped: Boolean) : SlotDecision {
    override fun toString(): String = "SlotDecision.Wait(skipped=$skipped)"
  }
}

/**
 * Le decisioni delle notifiche (D15, D16, D20, Q4): **pure**, senza orologio (l'ora arriva dalla porta `MemoryClock` di chi chiama) e senza
 * Android. Le usa `MemoryNotifyWorker` di P10 e, per il widget della Fase 10, chi sceglie il ricordo di oggi.
 */
interface MemoryNotifyDecisions {
  /** Il prossimo orario dopo [now] (ora locale), o null con le notifiche spente: il ritardo del lavoro unico `memories-notify`. */
  fun nextSlot(now: LocalDateTime, prefs: NotifyPrefs): LocalDateTime?

  /** All'esecuzione del lavoro: consegnare (l'ultimo orario e' da al piu' 3 ore) o aspettare. */
  fun slot(now: LocalDateTime, prefs: NotifyPrefs): SlotDecision

  /**
   * Il ricordo di oggi, o null (E-NT6): pubblicato oggi, non visto, non scartato, con almeno 8 elementi visibili, mai `PERSON` con "Mostra meno";
   * mai se l'utente ha gia' aperto i Ricordi oggi o se oggi c'e' gia' stata una notifica; la frequenza di Q4; priorita' di D15 (anniversario di
   * un viaggio o evento, "anni fa" dal piu' recente, il mese, la persona, la riscoperta), a parita' l'id.
   */
  fun choose(candidates: List<NotifyCandidate>, prefs: NotifyPrefs, ledger: NotifyLedger, today: LocalDate, openedToday: Boolean): MemoryId?

  /** Il contenuto per la modalita' [mode] (D16): la generica non ha niente del ricordo; mai nomi; la versione pubblica e' sempre generica. */
  fun content(memory: Memory, mode: LockScreenMode): NotifyContent
}

/** Le preferenze locali delle notifiche (P10, DataStore in `noBackupFilesDir`). */
interface NotifySettings {
  val prefs: StateFlow<NotifyPrefs>

  suspend fun set(prefs: NotifyPrefs)
}

/** Il registro locale del giorno (P10): si scrive **prima** di pubblicare la notifica (E-NT5), in modo atomico. */
interface NotifyLedgerStore {
  suspend fun read(): NotifyLedger

  suspend fun record(mark: NotifyMark, today: LocalDate)

  suspend fun clear()
}
