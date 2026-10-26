package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.LocalDate

// IL MODELLO DEGLI ALBUM SMART DI P6 (docs/fase7-progetto.md §2.2, D13-D17): cosa il motore decide per ogni candidato e le porte che lo
// legano al catalogo e alla ricerca. Tutto puro e senza Android: si prova sulla JVM con candidati in memoria.

/** Le regole forti che fanno entrare da solo un elemento (§2.2): A data e GPS, B senza GPS fra due sicuri dello stesso dispositivo, C descrizione. */
enum class SureRule(override val code: String) : StableCode {
  A("a"),
  B("b"),
  C("c"),
}

/** Perche' un elemento e' solo suggerito (S1-S5 di §2.2): il codice sta in `album_suggestion.reason`. */
enum class SmartReason(override val code: String) : StableCode {
  /** Nel periodo, senza GPS, di un altro dispositivo o di un familiare (E-AL3), o di un familiare anche col GPS giusto (E-AL17). */
  S1("s1"),

  /** Screenshot o registrazione dello schermo nel periodo (E-AL4). */
  S2("s2"),

  /** GPS fra 1 e 2 volte il raggio. */
  S3("s3"),

  /** Descrizione o foto d'esempio "meno probabili" (o "sicure" ma di un familiare). */
  S4("s4"),

  /** Nel periodo, senza GPS, dello stesso dispositivo ma fuori dalla finestra di B (E-AL11). */
  S5("s5"),
  ;

  companion object {
    fun fromCode(code: String): SmartReason? = entries.firstOrNull { it.code == code }
  }
}

/** Cosa il motore decide di un candidato. Un candidato "fuori" non ha un verdetto. */
sealed interface SmartVerdict {
  /** Entra da solo (riga `album_item` con `origin = auto`). */
  data class Sure(val rule: SureRule) : SmartVerdict

  /** Va in "Suggerite" (riga `album_suggestion`). */
  data class Suggested(val reason: SmartReason) : SmartVerdict
}

/**
 * Un candidato per il motore: il candidato del contratto di P0 piu' una cosa che il contratto non ha (P1 la chiede: `AlbumCandidate.ownedByMe`).
 * [ownedByMe] e' vero se il catalogo sa che il media e' del mio account anche quando sta nell'area comune (un mio media spostato in un album
 * condiviso e' sorgente `COMMON`, ma resta mio: puo' essere "sicuro").
 */
class SmartCandidate(val c: AlbumCandidate, val ownedByMe: Boolean) {
  val media: MediaId get() = c.media

  /** I miei media: quelli del telefono e della mia area privata, o dell'area comune ma miei (D18, D14 regola A). */
  val mine: Boolean get() = c.mine || ownedByMe

  override fun toString(): String = "SmartCandidate(${c.source.code})"
}

data class SmartCandidatePage(val items: List<SmartCandidate>, val next: CandidateCursor?)

/**
 * Cio' che il motore legge dei candidati (§2.2, §1.2 "Porte"). [page] e' il contratto `AlbumCandidates` di P0 con `ownedByMe`; le altre tre
 * sono le richieste di P1 al contratto (`places`, `candidatesOf`, `daysOf`: "nessuna e' bloccante, l'adattatore le assorbe"). Sempre e solo
 * **celle visibili** (una per gruppo): ma i gruppi con un nascosto dentro li togli chi usa i candidati (`VisibleMedia`, D22).
 */
interface SmartCandidates {
  suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): SmartCandidatePage

  /** Le celle con un luogo fra [places] (`place_id`), in ordine: per trovare il centro di un luogo e i suoi omonimi. */
  suspend fun placePage(places: Set<Int>, after: CandidateCursor?, limit: Int): SmartCandidatePage

  /** Le celle visibili fra [media], in ordine (giorno, istante, id): per i media che la ricerca ha indicato e per rivalutare un cambiamento. */
  suspend fun candidatesOf(media: Collection<MediaId>): List<SmartCandidate>

  /** I giorni locali di [media] (quelli che il catalogo conosce): gli smart da ricalcolare dopo un cambiamento. */
  suspend fun daysOf(media: Collection<MediaId>): Map<MediaId, LocalDate>

  /** La Libreria visibile (E-AL6: oltre il 20% le regole sono troppo larghe). */
  suspend fun visibleTotal(): Int
}

/** Le soglie dei capitoli di D13-D16 e di §2.2 che il motore usa oltre a [dev.pampa.mycloudgallery.core.albums.contract.SmartLimits]. */
object SmartTuning {
  /** Regola B: strettamente meno di 6 ore da ciascuno dei due sicuri. */
  const val RULE_B_WINDOW_MS: Long = 6L * 60 * 60 * 1000

  /**
   * Due giorni con foto nel luogo fanno la stessa serie se distano al piu' tanti giorni (3 = al piu' 2 giorni senza foto in mezzo, come i
   * viaggi di D19): un giorno senza foto o con la gita altrove non spezza il viaggio.
   */
  const val SERIES_GAP_DAYS: Long = 3

  /** Il raffinamento (D13) agisce su un periodo piu' lungo di cosi' (un anno, una stagione, un mese): "dal 3 al 10 agosto" non si tocca. */
  const val REFINE_MIN_DAYS: Long = 15

  /** Al piu' tante foto per trovare il centro di un luogo (la mediana delle coordinate): bastano a sceglierlo senza leggerle tutte. */
  const val CENTER_SAMPLE: Int = 2_000

  /** Pagine di candidati. */
  const val PAGE: Int = 2_000
}
