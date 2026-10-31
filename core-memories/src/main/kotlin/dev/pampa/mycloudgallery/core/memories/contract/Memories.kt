package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// IL SERVIZIO DEI RICORDI (docs/fase9-progetto.md §1.2 "Servizio", §2.1, §2.3, D3, D4, D8-D11, D19). Contratto di P0; l'implementazione vera e'
// di P6 (plan/, shelf/, settings/), l'esecutore di riferimento e' `InMemoryMemories` (testing/) con la suite `MemoriesContract`.
// Tutto locale: nessuna rete, nessun NAS (D4); "oggi" dalla porta `MemoryClock` (D36); le scelte dai dati personali (D10, D11).

/** Perche' si fa un giro (D4): cambia solo il budget, mai le regole. */
enum class RefreshReason(override val code: String) : StableCode {
  /** Di notte, dentro `LibraryMaintenanceWorker` (in carica): budget 60 s, misure dei pixel comprese. */
  NIGHTLY("nightly"),

  /** All'ora della notifica, o all'apertura dell'app se la notte non c'e' stata: budget 5 s, niente pixel col risparmio energetico. */
  LIGHT("light"),

  /** All'apertura di Raccolte, solo se il giorno e' cambiato. */
  OPEN("open"),
}

/** I budget di D4 (regole-attivita.md §6: `memoriesNightlyBudget`, `memoriesLightBudget`). */
object RefreshBudgets {
  const val NIGHTLY_SECONDS: Long = 60
  const val LIGHT_SECONDS: Long = 5
}

/** Perche' un candidato o una foto e' rimasto fuori (D5, D7): **solo conteggi** per motivo, mai gli id (Diagnostica `MEMORY_DROPPED`). */
enum class DropReason(override val code: String) : StableCode {
  SCREENSHOT("screenshot"),
  DOCUMENT("document"),
  ANIMATED("animated"),
  CUTOUT("cutout"),
  UNREADABLE("unreadable"),
  DATE_ESTIMATED("date_estimated"),
  NEVER_DAYS("never_days"),
  NEVER_PERSON("never_person"),
  DUPLICATE("duplicate"),
  CAPPED("capped"),
  WHATSAPP_SHARE("whatsapp_share"),
  NO_COVER("no_cover"),
  TOO_FEW("too_few"),
}

/**
 * L'esito di un giro (solo numeri, D33): [published] ricordi nuovi, [trimmed] elementi tolti da ricordi gia' pubblicati (nascosti, eliminati,
 * "Mai"), [removed] ricordi usciti (sotto 3, oltre 30 giorni o 60 ricordi, persone tolte), [shelfReset] lo scaffale era rovinato e si e'
 * rigenerato (`MEMORY_SHELF_RESET`), [incomplete] il budget e' finito prima, [withoutVectors] i doppioni sono stati cercati senza vettori
 * (`MEMORY_NO_VECTORS`), [dropped] esclusi per motivo.
 */
data class RefreshReport(
  val published: Int,
  val trimmed: Int,
  val removed: Int,
  val shelfReset: Boolean,
  val incomplete: Boolean,
  val withoutVectors: Boolean,
  val dropped: Map<DropReason, Int>,
) {
  companion object {
    val NOTHING: RefreshReport = RefreshReport(0, 0, 0, shelfReset = false, incomplete = false, withoutVectors = false, dropped = emptyMap())
  }
}

/**
 * I due livelli di "Mostra meno" (D11): gli stessi codici di `MemoryLessLevel` dei dati personali (core-personal), ripetuti qui perche' chi mostra
 * i ricordi (feature-memories) non vede i dati personali.
 */
enum class LessLevel(override val code: String) : StableCode {
  /** Niente ricordi dedicati, mai copertina, mai nella notifica; puo' comparire nelle foto di gruppo. */
  LESS("less"),

  /** "Mai nei ricordi": nessuna sua foto (per un periodo: nessuna foto di quei giorni). */
  NEVER("never"),
}

/** Su chi o su cosa vale "Mostra meno"/"Mai nei ricordi" (D11). */
sealed interface LessTarget {
  data class Person(val person: PersonId) : LessTarget {
    override fun toString(): String = "LessTarget.Person"
  }

  /** Un periodo di giorni locali, al piu' 366 (per un periodo vale solo "Mai"). */
  data class Days(val range: DayRange) : LessTarget {
    init {
      require(range.days <= MAX_DAYS) { "Un periodo e' al piu' di $MAX_DAYS giorni." }
    }

    override fun toString(): String = "LessTarget.Days(${range.days})"

    companion object {
      const val MAX_DAYS: Int = 366
    }
  }
}

/**
 * Una scelta "Mostra meno"/"Mai" (Impostazioni -> Ricordi e notifiche, "Persone e periodi nascosti dai ricordi"): [personName] e' letto al
 * momento dalla porta (null se Persone non e' attiva qui: "Una persona"), mai salvato.
 */
data class LessChoice(val target: LessTarget, val level: LessLevel, val personName: String?) {
  override fun toString(): String = "LessChoice($target, ${level.code})"
}

/** Lo stato dei ricordi su questo dispositivo, per la Diagnostica (`MEMORY_STATE`): numeri e codici. */
sealed interface MemoriesStatus {
  /** [lastDay] il giorno (`aaaammgg`) dell'ultimo giro riuscito, 0 = mai. */
  data class Idle(val lastDay: Int, val onShelf: Int, val unseen: Int) : MemoriesStatus

  data object Working : MemoriesStatus

  /** [code] un codice di `MemoriesDiag`. */
  data class Problem(val code: String) : MemoriesStatus
}

/**
 * I ricordi (D3, D4, D9-D11). **Ogni superficie che li mostra filtra ancora con `HiddenMask` subito prima** (D31): il servizio toglie i
 * nascosti a ogni giro e a ogni lettura, ma un media nascosto un attimo fa non deve comparire.
 */
interface Memories {
  /** Lo scaffale come lo mostra la sezione: mai scartati, mai nascosti noti all'ultimo giro. */
  val shelf: StateFlow<ShelfView>

  /**
   * Il ricordo [id] da mostrare, vivo sull'istantanea visibile; null se non c'e' (mai pubblicato, scartato, uscito o sotto 3 elementi).
   *
   * **"Non lo so ancora" non e' "non c'e'"** (RP-1, RP-2 del 31/10): finche' la Libreria non ha l'istantanea (e, per la facciata di `:app`, finche' i ricordi
   * non sono montati) il flusso **non emette niente**; `null` vuol dire solo "non c'e'", e solo a ricordi montati e Libreria letta. Chi lo legge deve saper
   * aspettare (le schermate restano in caricamento) o mettere un tetto al suo attendere (la notifica).
   */
  fun memory(id: MemoryId): Flow<MemoryView?>

  /**
   * Un giro (D4, §2.1): pulisce lo scaffale, pianifica i ricordi di oggi, sceglie le foto, pubblica al piu' due ricordi nuovi al giorno (piu'
   * quello del mese), scrive lo scaffale in modo atomico. Entro [budget] (orologio monotono): finito il tempo, quello che c'e' resta valido.
   */
  suspend fun refresh(reason: RefreshReason, budget: Duration): RefreshReport

  /**
   * Solo la pulizia dello scaffale (§2.1, passo 7), senza pianificare ne' scegliere: a ogni cambio dei nascosti (P13 la lega allo stesso
   * segnale di `MemoryNotifier.revalidate()`, D19) e di "Mai". Toglie elementi nascosti, eliminati o esclusi, ricordi sotto 3 elementi o di
   * persone tolte; un guasto della lettura dei visibili si propaga e lo scaffale resta com'era (R17).
   */
  suspend fun prune(): RefreshReport

  /** "Visto": dalla pagina o dalla presentazione, mai dalla sola sezione (D10). Viaggia con il giorno locale. */
  suspend fun markSeen(id: MemoryId)

  /** "Non mostrare questo ricordo" ([dismissed] vero) o "Annulla" (falso, entro 10 s). Vale subito e su tutti i dispositivi (E-MEM9). */
  suspend fun dismiss(id: MemoryId, dismissed: Boolean = true)

  /**
   * "Mostra meno"/"Mai nei ricordi" ([level]; null = togli). Per un periodo vale solo [LessLevel.NEVER]. Effetto immediato: lo
   * scaffale si ripulisce subito (D9, D11) e chi mostra la notifica la rivaluta (D19).
   */
  suspend fun setLess(target: LessTarget, level: LessLevel?)

  /** Le scelte "Mostra meno"/"Mai" di adesso (persone e periodi). */
  val lessChoices: StateFlow<List<LessChoice>>

  /** "Ricordi con le persone" (Q9 (a), di serie acceso): vale per l'account. */
  val peopleMemories: StateFlow<Boolean>

  suspend fun setPeopleMemories(on: Boolean)

  val status: StateFlow<MemoriesStatus>
}
