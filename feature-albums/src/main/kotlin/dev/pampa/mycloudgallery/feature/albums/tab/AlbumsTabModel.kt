package dev.pampa.mycloudgallery.feature.albums.tab

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumsTabKeys
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import java.time.LocalDate

// IL MODELLO DELLA SCHEDA ALBUM (fase grafica, G2b): una griglia di "album" tutti con la stessa forma, copertina e nome sotto, come in Galleria Samsung.
// Prima i fissi (Preferiti, Fotocamera, Video), poi tutto il resto insieme per ultima apertura. Non sa niente di Compose: lo provano i test su JVM.

/** Una voce della scheda: una copertina ([cover], null = il segnaposto), un nome e un numero. [key] e' la chiave dell'ultima apertura. */
@Immutable
sealed interface AlbumsTabEntry {
  val key: String
  val count: Int
  val cover: MediaId?

  /** Una raccolta-filtro della Libreria (Preferiti, Fotocamera, Video, Screenshot, Messaggistica): si apre nella sua griglia. */
  data class Special(val kind: SpecialAlbum, val summary: SpecialSummary) : AlbumsTabEntry {
    override val key: String get() = AlbumsTabKeys.special(kind)
    override val count: Int get() = summary.count
    override val cover: MediaId? get() = summary.cover
  }

  /** Un album: mio, condiviso o smart (con il segno dell'IA sulla copertina). Si apre nella sua pagina. */
  data class Item(val album: Album) : AlbumsTabEntry {
    override val key: String get() = AlbumsTabKeys.album(album.id)
    override val count: Int get() = album.count
    override val cover: MediaId? get() = album.cover.media
  }
}

/**
 * Quello che la scheda sa di una voce speciale, dalla sua vista dell'indice: quanti elementi, la copertina (il piu' recente: la posizione 0 della vista,
 * l'indice va dal piu' recente) e il giorno locale del piu' recente (`aaaammgg`, 0 se vuota) per l'ordine "per contenuto".
 */
@Immutable
data class SpecialSummary(val count: Int, val cover: MediaId?, val newestDay: Int) {
  companion object {
    val EMPTY: SpecialSummary = SpecialSummary(0, null, 0)

    fun of(view: TimelineView): SpecialSummary {
      if (view.size <= 0) return EMPTY
      val first = view.snapshotIndex(0)
      return SpecialSummary(view.size, view.snapshot.idAt(first), view.snapshot.localDayAt(first))
    }
  }
}

/** Lo stato della scheda Album. */
@Immutable
sealed interface AlbumsTabState {
  /**
   * [entries] nell'ordine della scheda; [pinned] quante sono le prime fisse (Preferiti, Fotocamera, Video); [notices] gli avvisi una volta sola sugli
   * album condivisi chiusi (come nell'elenco). [albumsPending]: gli album, o l'ultima apertura, non sono ancora stati letti (subito dopo l'avvio):
   * [entries] ha solo i fissi e al posto del resto la scheda disegna le copertine ferme dello scheletro, mai "Nessun album".
   */
  data class Ready(
    val entries: List<AlbumsTabEntry>,
    val pinned: Int,
    val notices: List<SharedAlbumNotice> = emptyList(),
    val albumsPending: Boolean = false,
  ) : AlbumsTabState {
    /** Quanti album (non voci speciali) ci sono: senza, la scheda invita a crearne uno. */
    val albumCount: Int get() = entries.count { it is AlbumsTabEntry.Item }
  }
}

/** L'ordine della scheda Album (richiesta dell'utente, G2b). */
object AlbumsTabOrder {

  /** I fissi in testa, in quest'ordine. Preferiti c'e' sempre (vuoto spiega come aggiungerne); gli altri due solo se hanno elementi. */
  val PINNED: List<SpecialAlbum> = listOf(SpecialAlbum.FAVORITES, SpecialAlbum.CAMERA, SpecialAlbum.VIDEOS)

  /** Le voci speciali che stanno con gli album, per ultima apertura; solo se hanno elementi. */
  val MIXED: List<SpecialAlbum> = listOf(SpecialAlbum.SCREENSHOTS, SpecialAlbum.MESSAGING)

  /**
   * Le voci della scheda:
   * 1. **Preferiti** (sempre), **Fotocamera** e **Video** (se non sono vuoti);
   * 2. poi tutti gli altri insieme, cioe' i miei album, i condivisi, gli smart, Screenshot e Messaggistica (questi due se non sono vuoti):
   *    prima quelli aperti, dal piu' recente; poi i mai aperti, dal piu' recente **per contenuto** (il giorno del media piu' recente), a pari
   *    giorno l'album aggiornato piu' di recente, infine la chiave (un ordine solo, uguale a ogni calcolo).
   *
   * Un album vuoto resta (e' dell'utente: va in fondo ai mai aperti); una voce speciale vuota no, tranne i Preferiti.
   */
  fun arrange(albums: List<Album>, specials: Map<SpecialAlbum, SpecialSummary>, openings: Map<String, Long>): Pair<List<AlbumsTabEntry>, Int> {
    val pinned = PINNED.mapNotNull { kind ->
      val summary = specials[kind] ?: SpecialSummary.EMPTY
      if (kind == SpecialAlbum.FAVORITES || summary.count > 0) AlbumsTabEntry.Special(kind, summary) else null
    }
    val rest = ArrayList<AlbumsTabEntry>(albums.size + MIXED.size)
    albums.mapTo(rest) { AlbumsTabEntry.Item(it) }
    MIXED.forEach { kind -> specials[kind]?.takeIf { it.count > 0 }?.let { rest += AlbumsTabEntry.Special(kind, it) } }
    val opened = rest.filter { it.key in openings }.sortedByDescending { openings.getValue(it.key) }
    val never = rest.filter { it.key !in openings }.sortedWith(
      compareByDescending<AlbumsTabEntry> { newestDayOf(it) }.thenByDescending { updatedAtOf(it) }.thenBy { it.key },
    )
    return (pinned + opened + never) to pinned.size
  }

  /** Il giorno del media piu' recente di una voce, `aaaammgg` (0 se non si sa: un album vuoto). */
  fun newestDayOf(entry: AlbumsTabEntry): Int = when (entry) {
    is AlbumsTabEntry.Special -> entry.summary.newestDay
    is AlbumsTabEntry.Item -> entry.album.newestDay?.let(::dayNumber) ?: 0
  }

  private fun updatedAtOf(entry: AlbumsTabEntry): Long = (entry as? AlbumsTabEntry.Item)?.album?.updatedAtNas ?: 0L

  private fun dayNumber(day: LocalDate): Int = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
}
