package dev.pampa.mycloudgallery.debugtools.eval.memories

import java.time.LocalDate

// IL RICORDO COME LO VEDE LA VALUTAZIONE (docs/fase9-progetto.md §5.3, riga P11 del §4.4): un ricordo generato "come se oggi fosse" un giorno
// del passato recente, con i soli dati che servono a giudicarlo: id, tipo, giorno simulato, titolo gia' composto, copertina ed elementi in
// ordine. Codice puro, senza dipendenze dal progetto: LO STESSO FILE sta in `core-memories/src/test/.../eval/` (provato con la Libreria
// sintetica "di una famiglia") e in `app/src/debug/.../debugtools/eval/memories/` (lo strumento sul telefono); cambia solo la riga `package`, e
// un test di :app (`MemoriesEvalSourceParityTest`) controlla che le due copie restino uguali.
//
// I dati veri (titolo, giorno, id dei media) sono **privati**: mai in un `toString()`, in un log o in un resoconto, mai nel repository.

/** I tipi di ricordo con i loro codici (gli stessi di `MemoryKind.code`: qui sono testo perche' il file e' puro e sta anche in :app). */
object MemoriesEvalKinds {
  val CODES: List<String> = listOf("otd", "trip", "event", "person", "month")

  fun isKind(code: String): Boolean = code in CODES

  /** Il nome per chi guarda lo strumento (mai un titolo vero). */
  fun label(code: String): String = when (code) {
    "otd" -> "anni fa"
    "trip" -> "viaggio"
    "event" -> "evento"
    "person" -> "persona"
    "month" -> "il mese"
    else -> "ricordo"
  }
}

/**
 * Un ricordo da giudicare. [id] e' il `MemoryId` (26 caratteri, hash: non rivela il giorno ma e' privato lo stesso), [day] il giorno simulato in cui
 * e' nato, [members] gli elementi nell'ordine della presentazione (da 3 a 40, id locali dei media in forma di UUID), [cover] uno di loro, [title] il
 * titolo come lo vedrebbe l'utente (con date e luoghi veri: resta sul telefono).
 */
class EvalMemory(
  val id: String,
  val kind: String,
  val day: LocalDate,
  val cover: String,
  val members: List<String>,
  val title: String,
) {
  init {
    require(ID.matches(id)) { "Identificativo di ricordo non valido." }
    require(MemoriesEvalKinds.isKind(kind)) { "Tipo di ricordo sconosciuto." }
    require(members.size in MIN_MEMBERS..MAX_MEMBERS) { "Un ricordo ha da $MIN_MEMBERS a $MAX_MEMBERS elementi." }
    require(members.all(::isMedia) && members.distinct().size == members.size) { "Elementi non validi." }
    require(cover in members) { "La copertina e' uno degli elementi." }
    require(title.length <= MAX_TITLE && title.none { it.isISOControl() }) { "Titolo non valido." }
  }

  /** Mai il titolo, il giorno o l'id. */
  override fun toString(): String = "EvalMemory($kind, ${members.size} elementi)"

  companion object {
    val ID = Regex("[0-9a-hjkmnp-tv-z]{26}")
    const val MIN_MEMBERS: Int = 3
    const val MAX_MEMBERS: Int = 40
    const val MAX_TITLE: Int = 200

    private val MEDIA = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Un id di media e' un UUID minuscolo (`MediaId`). */
    fun isMedia(id: String): Boolean = MEDIA.matches(id)
  }
}

/** Quali ricordi si giudicano: gli ultimi 45 giorni, un massimo per sessione, la scelta senza orologio (stessa Libreria e stesso giorno = stessi ricordi). */
object MemoriesEvalDays {
  /** Quanti giorni indietro si simula "oggi" (§5.3: gli ultimi 45 giorni, circa 40-60 ricordi). */
  const val WINDOW: Int = 45

  /** Al piu' tanti ricordi in una sessione (circa 25 secondi l'uno: 25 minuti). */
  const val MAX_JUDGED: Int = 60

  /** I giorni da simulare, dal piu' vecchio a [today] compreso. */
  fun window(today: LocalDate, days: Int = WINDOW): List<LocalDate> {
    require(days in 1..MAX_WINDOW) { "Finestra non valida." }
    return (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
  }

  /**
   * I ricordi da giudicare: ognuno una volta (per id), al piu' [cap]. Se sono di piu', restano quelli con l'id piu' piccolo (l'id e' un hash: e' una
   * scelta a caso, uguale a ogni corsa e senza orologio). Poi in ordine di giorno e, a parita', di id.
   */
  fun select(memories: List<EvalMemory>, cap: Int = MAX_JUDGED): List<EvalMemory> {
    require(cap >= 1) { "Tetto non valido." }
    return memories.distinctBy { it.id }.sortedBy { it.id }.take(cap).sortedWith(compareBy<EvalMemory> { it.day }.thenBy { it.id })
  }

  private const val MAX_WINDOW = 120
}
