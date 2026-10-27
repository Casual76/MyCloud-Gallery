package dev.pampa.mycloudgallery.feature.memories.contract

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.YearMonth

// Gli STATI delle schermate dei ricordi (docs/fase9-progetto.md §1.5, D13, D14, D18, D34). Contratto di P0: la Fase 9 decide COSA si mostra e
// QUANDO, il filone grafico decide COME. P9 (sezione, pagina, "Tutti i ricordi", presentazione, fogli, Impostazioni) e P10 (la parte delle
// notifiche della pagina delle impostazioni) scrivono ViewModel e composable con i soli componenti G2 (G3 la presentazione); il filone grafico
// rifinisce senza toccare questi tipi. Solo dati gia' pronti da mostrare, **mai un nascosto** (le viste e le copertine vengono da core-memories,
// che filtra con `VisibleMedia` e `HiddenMask`; la superficie applica ancora `HiddenMask` subito prima, D31). Risposte del 27/10: **Q6 (a)**
// nessun filtro automatico (la pagina non ha la sua riga), **Q3** musica nessuna di serie, canzone propria o pacchetto di musica libera, **Q4**
// 2-3 notifiche a settimana di base, **Q5 (a)** schermata di blocco generica, **Q9 (a)** ricordi con le persone. Mai nomi o id nei log (D33).

/** Raccolte -> Ricordi (§1.5 `MemoriesSectionState`, D14): fino a 6 voci, prima le non viste; "Tutti i ricordi"; il segno "nuovo". */
sealed interface MemoriesSectionState {
  data class Ready(val cards: List<MemoryCard>, val unseen: Int) : MemoriesSectionState {
    init {
      require(cards.size <= MAX_CARDS) { "Al piu' $MAX_CARDS voci nella sezione." }
    }

    override fun toString(): String = "MemoriesSectionState.Ready(${cards.size}, nuovi=$unseen)"

    companion object {
      const val MAX_CARDS: Int = 6
    }
  }

  /** "I ricordi arrivano quando ci sono abbastanza foto" (E-MEM1). */
  data class Empty(val reason: ShelfEmptyReason) : MemoriesSectionState

  /** "Sto preparando i ricordi": solo al primo giro. */
  data object Working : MemoriesSectionState
}

/** Le azioni della pagina del ricordo (§2.4): il menu le mostra solo se ci sono. */
enum class MemoryAction(override val code: String) : StableCode {
  PLAY("play"),

  /** "Mostra meno <nome>" (solo un ricordo `PERSON`, solo con Persone attiva). */
  LESS_PERSON("less_person"),

  /** "Non ricordarmi questo periodo". */
  NEVER_DAYS("never_days"),

  /** "Non mostrare questo ricordo" (con "Annulla" per 10 s). */
  DISMISS("dismiss"),

  /** "Apri l'album" di un viaggio diventato album (E-MEM13). */
  OPEN_ALBUM("open_album"),
}

/**
 * La testa della pagina: il titolo come dati (la UI compone "Un anno fa", "Oslo, marzo 2025", "Con Anna"), [personName] letto al momento (null
 * se non e' un ricordo `PERSON` o Persone non e' attiva: "Una persona"), [placeNames] i nomi dei luoghi gia' risolti da `PlaceNames`.
 */
data class MemoryHeader(
  val id: MemoryId,
  val kind: MemoryKind,
  val title: MemoryTitle,
  val personName: String?,
  val placeNames: List<String>,
) {
  override fun toString(): String = "MemoryHeader(${kind.code}, nome=${personName != null})"
}

/** La pagina del ricordo (§1.5 `MemoryPageState`, §2.4). */
sealed interface MemoryPageState {
  data object Loading : MemoryPageState

  /** [view] la griglia viva (il viewer si apre su questa vista); [cover] la copertina mostrabile; aprire la pagina segna "visto" (D10). */
  class Ready(
    val header: MemoryHeader,
    val view: OrderedTimelineView,
    val cover: MediaId,
    val actions: Set<MemoryAction>,
    val origin: MemoryOrigin?,
  ) : MemoryPageState {
    override fun toString(): String = "MemoryPageState.Ready(${header.kind.code}, ${view.size}, ${actions.size} azioni)"
  }

  /** "Questo ricordo non c'e' piu'" (sotto 3 elementi, scartato, D19): ritorno alla pagina Ricordi. */
  data object Gone : MemoryPageState

  data object NotFound : MemoryPageState
}

/** Un mese di "Tutti i ricordi" (lo scaffale per mese, §2.5). */
data class MemoriesMonth(val month: YearMonth, val cards: List<MemoryCard>) {
  override fun toString(): String = "MemoriesMonth(${cards.size})"
}

/** "Tutti i ricordi" (§1.5 `MemoriesListState`). */
sealed interface MemoriesListState {
  data class Ready(val byMonth: List<MemoriesMonth>) : MemoriesListState {
    override fun toString(): String = "MemoriesListState.Ready(${byMonth.size} mesi)"
  }

  data object Empty : MemoriesListState
}

/** Un elemento della presentazione: una foto (4 s) o un video (fotogramma di copertina, o un clip muto se del telefono, D13, E-MEM17). */
sealed interface PlayerItem {
  val media: MediaId

  data class Photo(override val media: MediaId) : PlayerItem {
    override fun toString(): String = "PlayerItem.Photo"
  }

  data class Video(override val media: MediaId, val clip: ClipSource?) : PlayerItem {
    override fun toString(): String = "PlayerItem.Video(clip=${clip != null})"
  }
}

/** La musica della presentazione in questo momento (Q3): muta di serie, la scelta che suona, o la scelta che non c'e' piu' (avviso). */
enum class PlayerMusic(override val code: String) : StableCode {
  MUTED("muted"),
  PLAYING("playing"),
  MISSING("missing"),
}

/**
 * La presentazione (§1.5 `PlayerState`, D13): [reducedMotion] nessun movimento, solo dissolvenze; [talkBack] niente avanzamento automatico,
 * ogni foto con la sua descrizione. Precaricate le due successive ([next]); mai un'attesa sul NAS.
 */
sealed interface PlayerState {
  val reducedMotion: Boolean
  val talkBack: Boolean

  data class Playing(
    val index: Int,
    val item: PlayerItem,
    val next: List<PlayerItem>,
    val music: PlayerMusic,
    override val reducedMotion: Boolean,
    override val talkBack: Boolean,
  ) : PlayerState {
    init {
      require(next.size <= PRELOAD) { "Si precaricano al piu' $PRELOAD immagini." }
    }

    override fun toString(): String = "PlayerState.Playing($index, ${music.code})"

    companion object {
      const val PRELOAD: Int = 2
    }
  }

  data class Paused(val index: Int, val item: PlayerItem, override val reducedMotion: Boolean, override val talkBack: Boolean) : PlayerState {
    override fun toString(): String = "PlayerState.Paused($index)"
  }

  /** La carta finale con le miniature e "Rivedi". */
  data class Ended(val count: Int, override val reducedMotion: Boolean, override val talkBack: Boolean) : PlayerState
}

/** I fogli "Mostra meno" e "Mai nei ricordi" (§1.5 `LessSheetState`, D11). */
sealed interface LessSheetState {
  /** "Mostra meno <nome>": [level] la scelta di adesso (null = nessuna). */
  data class Person(val person: PersonId, val name: String?, val level: LessLevel?) : LessSheetState {
    override fun toString(): String = "LessSheetState.Person(${level?.code})"
  }

  /** "Non ricordarmi questo periodo": la scelta dei giorni, al piu' 366. */
  data class Days(val range: DayRange) : LessSheetState

  /** Fatto: "Annulla" per 10 s. */
  data class Done(val choice: LessChoice) : LessSheetState

  data object Undone : LessSheetState
}

/** Lo stato del permesso e del canale delle notifiche (D18, E-NT1, E-NT2). */
enum class NotifyPermission(override val code: String) : StableCode {
  GRANTED("granted"),

  /** Negato ma Android lo puo' ancora chiedere: "Consenti" da un tocco. */
  ASKABLE("askable"),

  /** Negato per sempre: "Apri le impostazioni di Android". */
  DENIED("denied"),

  /** Il canale "Ricordi" e' spento dalle impostazioni di Android. */
  CHANNEL_OFF("channel_off"),
}

/** "Ricordi con le persone" (Q9 (a)): la riga c'e' solo con Persone attiva su questo dispositivo. */
enum class PeopleMemoriesRow(override val code: String) : StableCode {
  HIDDEN("hidden"),
  ON("on"),
  OFF("off"),
}

/**
 * Impostazioni -> Ricordi e notifiche (§1.5 `MemoriesSettingsState`, §2.6). Q6 (a): **nessuna riga del filtro automatico**. Q3: la musica
 * ([music], [tracks] del pacchetto di musica libera con il loro stato).
 */
data class MemoriesSettingsState(
  val people: PeopleMemoriesRow,
  val notify: NotifyPrefs,
  val permission: NotifyPermission,
  val lessChoices: List<LessChoice>,
  val music: MusicChoice,
  val tracks: Map<String, MusicTrackState>,
) {
  override fun toString(): String = "MemoriesSettingsState(${people.code}, $notify, ${permission.code}, ${lessChoices.size} scelte)"
}
