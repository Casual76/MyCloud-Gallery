package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import java.time.LocalDate

// IL MODELLO DEGLI ALBUM (docs/fase7-progetto.md §1.2 "core-albums", D3-D5, D9, D17, D21, D22, D31 adeguata a Q5). Contratti di P0: cio'
// che feature-albums (P10), le Raccolte (P11) e :app (P13) vedono di un album. Solo tipi di core-model e core-library: niente percorsi,
// niente NAS. Mai un titolo, un luogo o un id nei log: i `toString()` lo nascondono (D37).

/** La copertina di un album (D21). Mai un nascosto, un elemento tolto o non attestato: in quei casi e' [Auto] (E-H6). */
sealed interface CoverRef {
  val media: MediaId?

  /** Scelta dall'utente (voce `cover`) e mostrabile adesso da questo dispositivo. */
  data class Chosen(override val media: MediaId) : CoverRef {
    override fun toString(): String = "CoverRef.Chosen"
  }

  /**
   * Automatica: per i manuali l'ultimo aggiunto, per gli smart la foto con piu' pixel del giorno con piu' sicuri (D21). Anche al posto di
   * una scelta che **questo** dispositivo non puo' mostrare (nascosta, tolta, non attestata): solo per chi non la puo' mostrare.
   */
  data class Auto(override val media: MediaId) : CoverRef {
    override fun toString(): String = "CoverRef.Auto"
  }

  /** Nessun elemento mostrabile. */
  data object None : CoverRef {
    override val media: MediaId? = null
  }
}

/** Lo stato di un album condiviso (D7, voce `definition`). `CLOSED` e `DELETED` sono definitivi per quell'id (D31). */
enum class SharedState(override val code: String) : StableCode {
  ACTIVE("active"),

  /** "Smetti di condividere": per il creatore diventa personale; per gli altri decide [OthersAfterClose] (Q5). */
  CLOSED("closed"),

  /** "Elimina l'album": sparisce anche per il creatore; per gli altri decide [OthersAfterClose] (Q5). I file restano. */
  DELETED("deleted"),
  ;

  companion object {
    fun fromCode(code: String?): SharedState? = enumByCodeOrNull<SharedState>(code)
  }
}

/**
 * Da dove viene un album personale che **era** condiviso (D31 adeguata a Q5): l'album del creatore dopo "Smetti di condividere", o la
 * **copia personale** di un familiare quando chi ha chiuso ha scelto "ognuno tiene una copia" ([OthersAfterClose.KEEP_COPY]).
 * [sharedId] e' l'id dell'album condiviso; [creator] il suo creatore; [mine] se il creatore e' il mio account.
 */
data class FormerShare(val sharedId: AlbumId, val creator: AccountId, val mine: Boolean, val closedAtNas: Long) {
  override fun toString(): String = "FormerShare(mine=$mine)"
}

/**
 * Un album come lo mostrano le Raccolte e la sua pagina. [count] conta solo gli elementi **mostrabili da questo dispositivo**: senza
 * nascosti (D22, E-H5), senza elementi condivisi non attestati dal catalogo locale (D9), una cella per gruppo. [updatedAtNas] e' l'ora del
 * NAS dell'ultima modifica nota (ordine "Aggiornati di recente"), [newestDay] il giorno locale del media piu' recente ("Data dei media").
 */
sealed interface Album {
  val id: AlbumId
  val title: String
  val kind: AlbumKind
  val cover: CoverRef
  val count: Int
  val sort: AlbumSort
  val updatedAtNas: Long
  val newestDay: LocalDate?

  /** Un album manuale personale (D3): voci `ALBUM` dei dati personali, mai un file toccato. */
  data class Manual(
    override val id: AlbumId,
    override val title: String,
    override val cover: CoverRef,
    override val count: Int,
    override val sort: AlbumSort,
    override val updatedAtNas: Long,
    override val newestDay: LocalDate?,
    /** Non null se l'album era condiviso (creatore dopo "Smetti di condividere") o e' la copia personale di Q5 (b). */
    val formerShare: FormerShare? = null,
  ) : Album {
    override val kind: AlbumKind get() = AlbumKind.MANUAL

    override fun toString(): String = "Album.Manual(count=$count)"
  }

  /** Un album smart personale (D13-D16). [suggested] e [newSinceSeen] ("Nuove: 12") sono senza nascosti. */
  data class Smart(
    override val id: AlbumId,
    override val title: String,
    override val cover: CoverRef,
    override val count: Int,
    override val sort: AlbumSort,
    override val updatedAtNas: Long,
    override val newestDay: LocalDate?,
    val rules: SmartRules,
    val frozen: Boolean,
    val suggested: Int,
    val newSinceSeen: Int,
  ) : Album {
    override val kind: AlbumKind get() = AlbumKind.SMART

    override fun toString(): String = "Album.Smart(count=$count, frozen=$frozen)"
  }

  /**
   * Un album condiviso nell'area comune (D6-D11), mio o di un familiare. [mine] = il mio account e' il creatore o l'erede. [kind] e'
   * `SMART` solo per il creatore di un album smart condiviso (D17: le regole restano sue, [smartRules]); per gli altri e' `MANUAL`.
   * [toShare] ("Da condividere", D17) e [incoming] ("In arrivo": elementi miei ancora in viaggio, D9) contano solo sui miei dispositivi.
   */
  data class Shared(
    override val id: AlbumId,
    override val title: String,
    override val kind: AlbumKind,
    override val cover: CoverRef,
    override val count: Int,
    override val sort: AlbumSort,
    override val updatedAtNas: Long,
    override val newestDay: LocalDate?,
    val level: ShareLevel,
    val creator: AccountId,
    val heir: AccountId?,
    val mine: Boolean,
    val state: SharedState,
    val smartRules: SmartRules? = null,
    val toShare: Int = 0,
    val incoming: Int = 0,
  ) : Album {
    override fun toString(): String = "Album.Shared(count=$count, level=${level.code}, mine=$mine, state=${state.code})"
  }
}

/** L'ordine dell'**elenco** degli album (D21, impostazione sincronizzata `albums_sort`). */
enum class AlbumListSort(override val code: String) : StableCode {
  /** "Personalizzato": trascinamento (voce `order` di ogni album). */
  CUSTOM("custom"),

  /** "Aggiornati di recente" (predefinito, P0: D21 non lo fissa). */
  RECENT("recent"),

  /** "Nome" (ordine italiano, senza maiuscole ne' accenti). */
  NAME("name"),

  /** "Data dei media": il media piu' recente dell'album. */
  MEDIA_DATE("media_date"),
  ;

  companion object {
    val DEFAULT: AlbumListSort = RECENT

    fun fromCode(code: String?): AlbumListSort = enumByCodeOrNull<AlbumListSort>(code) ?: DEFAULT
  }
}

/** L'elenco degli album nell'ordine [sort] (Raccolte → Album, "Tutti gli album"). Senza gli album condivisi lasciati (E-SH21). */
data class AlbumList(val albums: List<Album>, val sort: AlbumListSort) {
  /** I miei (manuali) e i condivisi insieme, come la sezione "Album" (D33). */
  val manualAndShared: List<Album> get() = albums.filter { it !is Album.Smart }

  val smart: List<Album.Smart> get() = albums.filterIsInstance<Album.Smart>()

  fun byId(id: AlbumId): Album? = albums.firstOrNull { it.id == id }

  override fun toString(): String = "AlbumList(${albums.size}, ${sort.code})"

  companion object {
    val EMPTY: AlbumList = AlbumList(emptyList(), AlbumListSort.DEFAULT)
  }
}

/**
 * Il contenuto di un album (la sua pagina): [items] nell'ordine dell'album, **mai** un nascosto (le viste nascono dall'istantanea, che
 * non li ha, e dal filtro finale). [suggested] ("Suggerite", D14) solo per gli smart non congelati; [toShare] ("Da condividere", D17)
 * solo per il creatore di uno smart condiviso; [incoming] quanti miei elementi sono ancora in viaggio verso l'area comune (D9).
 */
data class AlbumContent(
  val album: Album,
  val items: OrderedTimelineView,
  val suggested: OrderedTimelineView? = null,
  val toShare: OrderedTimelineView? = null,
  val incoming: Int = 0,
) {
  override fun toString(): String = "AlbumContent(${items.size} elementi)"
}

/** Perche' un titolo d'album non va bene (D7, §8.4 del formato, E-SH22). La cartella si corregge da sola: il titolo no. */
enum class TitleProblem { EMPTY, TOO_LONG, FORBIDDEN_CHARS }

/**
 * Le regole del titolo, uguali per album personali e condivisi (un album nato personale si condivide senza cambiare titolo): da 1 a
 * [MAX_CHARS] caratteri dopo aver tolto gli spazi ai bordi, nessun carattere di controllo ne' di direzione del testo (il codec ostile di
 * P2 li rifiuterebbe). Caratteri vietati nei nomi di file, punti finali e nomi riservati di Windows **sono ammessi** nel titolo: la
 * cartella usa un nome corretto (E-SH22, `TextRules` di core-trust, P4).
 */
object AlbumTitles {
  const val MAX_CHARS: Int = 64

  private val BIDI = setOf(0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069)

  /** Il problema di [title], o null se va bene. */
  fun problem(title: String): TitleProblem? {
    val t = title.trim()
    if (t.isEmpty()) return TitleProblem.EMPTY
    if (t.codePointCount(0, t.length) > MAX_CHARS) return TitleProblem.TOO_LONG
    var i = 0
    while (i < t.length) {
      val cp = t.codePointAt(i)
      if (Character.getType(cp) == Character.CONTROL.toInt() || cp in BIDI || cp == 0xFEFF) return TitleProblem.FORBIDDEN_CHARS
      i += Character.charCount(cp)
    }
    return null
  }

  /** Il titolo come si salva (spazi ai bordi tolti); va chiamato solo se [problem] e' null. */
  fun normalized(title: String): String = title.trim()
}
