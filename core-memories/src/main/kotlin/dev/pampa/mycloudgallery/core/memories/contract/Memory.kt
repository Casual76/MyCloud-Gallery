package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.YearMonth

// IL RICORDO (docs/fase9-progetto.md §1.2, §1.5, D9, D13, D14). Contratto di P0. Un ricordo **pubblicato** sullo scaffale ha titolo, elementi
// (al piu' 40, in ordine) e copertina **fissi**: dopo si possono solo togliere elementi diventati nascosti, eliminati o esclusi da un "Mai";
// la copertina passa al primo elemento rimasto; sotto 3 elementi il ricordo esce (D9, E-MEM5, E-MEM6). Il titolo e' fatto di **dati**: il
// testo lo compone la UI (il nome della persona si legge al momento, mai salvato, D12). Nessun `toString()` mostra date, luoghi, id o nomi.

/**
 * Il titolo come dati (D13): [yearsAgo] "Un anno fa", "3 anni fa" (anniversari e "anni fa"; null altrimenti); [days] il giorno o il periodo;
 * [placeIds] i luoghi principali (id GeoNames, al piu' [MAX_PLACES], dal piu' frequente: `PlaceNames` li dice); [tripLabel] il titolo della
 * Fase 7 di un viaggio o evento ("Oslo, marzo 2025"); [person] la persona di un ricordo `PERSON` (solo l'id); [month] il mese di "Il meglio
 * del mese".
 */
data class MemoryTitle(
  val yearsAgo: Int?,
  val days: DayRange,
  val placeIds: List<Int>,
  val tripLabel: String?,
  val person: PersonId?,
  val month: YearMonth?,
) {
  init {
    require(yearsAgo == null || yearsAgo >= 1) { "Anni fa: almeno uno." }
    require(placeIds.size <= MAX_PLACES && placeIds.all { it > 0 }) { "Luoghi non validi." }
  }

  override fun toString(): String = "MemoryTitle(anniFa=${yearsAgo != null}, luoghi=${placeIds.size})"

  companion object {
    const val MAX_PLACES: Int = 3

    /** I luoghi principali di un ricordo: i [MAX_PLACES] id non nulli piu' frequenti fra [placeIds], a pari frequenza l'id minore. */
    fun topPlaces(placeIds: Iterable<Int>): List<Int> = placeIds.filter { it > 0 }.groupingBy { it }.eachCount().entries
      .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
      .take(MAX_PLACES).map { it.key }
  }
}

/** Da dove viene un ricordo, quando serve a un'azione ("Apri l'album" di un viaggio diventato album, E-MEM13). */
sealed interface MemoryOrigin {
  data class TripAlbum(val album: AlbumId) : MemoryOrigin {
    override fun toString(): String = "MemoryOrigin.TripAlbum"
  }
}

/**
 * Un ricordo pubblicato (D9). [members] al piu' [MAX_MEMBERS], nell'ordine della presentazione; [cover] e' uno dei [members]; [publishedDay]
 * il giorno locale (`aaaammgg`) in cui e' entrato nello scaffale.
 */
data class Memory(
  val id: MemoryId,
  val key: MemoryKey,
  val title: MemoryTitle,
  val members: List<MediaId>,
  val cover: MediaId,
  val publishedDay: Int,
  val origin: MemoryOrigin? = null,
) {
  init {
    require(MemoryIds.of(key) == id) { "L'id non e' quello della chiave." }
    require(members.size in MIN_MEMBERS..MAX_MEMBERS) { "Un ricordo ha da $MIN_MEMBERS a $MAX_MEMBERS elementi." }
    require(members.distinct().size == members.size) { "Elementi ripetuti." }
    require(cover in members) { "La copertina e' uno degli elementi." }
  }

  val kind: MemoryKind get() = key.kind

  override fun toString(): String = "Memory(${kind.code}, ${members.size} elementi)"

  companion object {
    /** Sotto 3 elementi un ricordo esce dallo scaffale (D9). */
    const val MIN_MEMBERS: Int = 3

    /** Al piu' 40 elementi (D9). */
    const val MAX_MEMBERS: Int = 40
  }
}

/** Una voce della sezione Ricordi o di "Tutti i ricordi" (D14): [isNew] = non ancora visto. */
data class MemoryCard(
  val id: MemoryId,
  val kind: MemoryKind,
  val title: MemoryTitle,
  val cover: MediaId,
  val count: Int,
  val publishedDay: Int,
  val isNew: Boolean,
) {
  override fun toString(): String = "MemoryCard(${kind.code}, $count, nuovo=$isNew)"
}

/** Perche' lo scaffale e' vuoto (§1.5 `MemoriesSectionState.Empty`): un codice stabile, il testo e' della schermata. */
enum class ShelfEmptyReason(override val code: String) : StableCode {
  /** "I ricordi arrivano quando ci sono abbastanza foto" (E-MEM1). */
  NOT_ENOUGH_PHOTOS("not_enough_photos"),

  /** La Libreria non e' ancora pronta (nessuna istantanea). */
  LIBRARY_NOT_READY("library_not_ready"),
}

sealed interface ShelfState {
  data object Ready : ShelfState

  data class Empty(val reason: ShelfEmptyReason) : ShelfState

  /** "Sto preparando i ricordi": solo al primo giro (§2.5). */
  data object Working : ShelfState
}

/**
 * Lo scaffale come lo mostra la sezione (D14): [items] dal piu' recente (i non visti prima li mette la sezione), **mai** uno scartato o un
 * elemento nascosto; [unseen] quanti non visti.
 */
data class ShelfView(val items: List<MemoryCard>, val unseen: Int, val state: ShelfState) {
  override fun toString(): String = "ShelfView(${items.size}, nuovi=$unseen, $state)"

  companion object {
    val WORKING: ShelfView = ShelfView(emptyList(), 0, ShelfState.Working)
    val EMPTY: ShelfView = ShelfView(emptyList(), 0, ShelfState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS))
  }
}

/**
 * Un ricordo da mostrare (pagina, presentazione): [view] e' viva sull'istantanea visibile (un nascosto esce subito, E-MEM5, E-H8); [cover] il
 * primo elemento mostrabile fra la copertina e gli altri; [seen] se e' gia' stato visto; [personName] il nome letto **al momento** dalla porta
 * delle persone (un ricordo `PERSON`), mai salvato.
 */
class MemoryView(
  val memory: Memory,
  val view: OrderedTimelineView,
  val cover: MediaId,
  val seen: Boolean,
  val personName: String?,
) {
  override fun toString(): String = "MemoryView(${memory.kind.code}, ${view.size}, visto=$seen)"
}
