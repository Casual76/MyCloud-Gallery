package dev.pampa.mycloudgallery.debugtools.eval.albums

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// LE ETICHETTE DEI VIAGGI VERI (docs/fase7-progetto.md §5.3, riga P12 del §4.4): i viaggi che l'utente indica (periodo e titolo come li
// scriverebbe), per ogni viaggio le foto che sono del viaggio e quelle che non lo sono (id locali dei media, UUID). Un file di testo,
// versionato, che sta SOLO sul telefono in `noBackupFilesDir/eval/albums/` (fuori dai backup) e si esporta con `adb`: **mai nel
// repository** (`.gitignore`, `NoPrivateAlbumsDataInGitTest`, `check.ps1`). Date, titoli e id sono privati: mai in un `toString`, in un
// log o in un resoconto. Stesso file in core-albums (test) e in :app (debug): cambia solo la riga `package`, e un test di :app
// (`AlbumsEvalSourceParityTest`) controlla che le due copie restino uguali.

/**
 * Un viaggio dell'utente: i giorni (locali, estremi compresi) e, per i viaggi che si valutano come album (F88), il titolo come lo scriverebbe
 * ("Oslo 2026"). Un viaggio senza titolo fa solo parte dell'elenco dei viaggi principali (F89).
 */
class EvalTrip(val id: String, val from: LocalDate, val to: LocalDate, val title: String?) {
  init {
    require(ID.matches(id)) { "Identificativo di viaggio non valido." }
    require(!to.isBefore(from)) { "Viaggio con i giorni rovesciati." }
    require(ChronoUnit.DAYS.between(from, to) + 1 <= MAX_DAYS) { "Viaggio troppo lungo." }
    require(title == null || (title.isNotBlank() && title.length <= MAX_TITLE && title.none { it.isISOControl() })) { "Titolo di viaggio non valido." }
  }

  /** Quanti giorni dura (almeno 1). */
  val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

  /** Mai il titolo ne' le date. */
  override fun toString(): String = "EvalTrip($id, $days giorni)"

  companion object {
    val ID = Regex("v[0-9]{2,3}")
    const val MAX_TITLE: Int = 120
    const val MAX_DAYS: Long = 366
  }
}

/**
 * Le etichette. Immutabile; ogni modifica ne da' una nuova. Per ogni viaggio: i media che l'utente dice **del viaggio** ([inTrip], la verita'
 * della misura) e quelli che dice **non del viaggio** ([notInTrip], le foto che l'album ha messo e non vanno). Un media non e' mai in entrambi.
 * [listClosed]: l'elenco dei viaggi principali non si cambia piu' (e' stato confrontato con le proposte: F89 vale solo se l'elenco e' stato
 * scritto prima di guardare).
 */
class AlbumsLabelSet(
  val trips: List<EvalTrip> = emptyList(),
  val inTrip: Map<String, Set<String>> = emptyMap(),
  val notInTrip: Map<String, Set<String>> = emptyMap(),
  val listClosed: Boolean = false,
) {
  init {
    require(trips.map { it.id }.distinct().size == trips.size) { "Identificativi di viaggio doppi." }
    val ids = trips.map { it.id }.toSet()
    require(inTrip.keys.all { it in ids } && notInTrip.keys.all { it in ids }) { "Etichette di un viaggio che non c'e'." }
    require(inTrip.values.all { it.all(AlbumsLabelFormat::isMedia) } && notInTrip.values.all { it.all(AlbumsLabelFormat::isMedia) }) { "Media non valido." }
    require(ids.all { id -> inTrip[id].orEmpty().none { it in notInTrip[id].orEmpty() } }) { "Un media e' sia del viaggio sia no." }
  }

  fun trip(id: String): EvalTrip? = trips.firstOrNull { it.id == id }

  fun inTripOf(id: String): Set<String> = inTrip[id].orEmpty()

  fun notInTripOf(id: String): Set<String> = notInTrip[id].orEmpty()

  /** I viaggi da valutare come album (F88): quelli con un titolo. */
  val titled: List<EvalTrip> get() = trips.filter { it.title != null }

  /** Il prossimo identificativo libero: `v01`, `v02`, ... */
  fun nextId(): String {
    val taken = trips.map { it.id }.toSet()
    return generateSequence(1) { it + 1 }.map { "v%02d".format(it) }.first { it !in taken }
  }

  /**
   * Aggiunge (o sostituisce) un viaggio. Dopo il confronto con le proposte ([listClosed]) un viaggio nuovo non si aggiunge piu' e uno dell'elenco
   * non si cambia: si puo' solo dare un titolo a un viaggio che c'e' gia' (per valutarlo come album, che non dipende dalle proposte).
   */
  fun withTrip(trip: EvalTrip): AlbumsLabelSet {
    val existing = trip(trip.id)
    require(trips.size < MAX_TRIPS || existing != null) { "Al piu' $MAX_TRIPS viaggi." }
    if (listClosed) {
      require(existing != null && existing.from == trip.from && existing.to == trip.to) { "L'elenco dei viaggi e' chiuso." }
    }
    return AlbumsLabelSet(trips.filter { it.id != trip.id } + trip, inTrip, notInTrip, listClosed).sorted()
  }

  /** Toglie un viaggio e le sue etichette. Non dopo il confronto con le proposte: non si toglie il viaggio che non e' stato trovato. */
  fun withoutTrip(id: String): AlbumsLabelSet {
    require(!listClosed) { "L'elenco dei viaggi e' chiuso." }
    return AlbumsLabelSet(trips.filter { it.id != id }, inTrip - id, notInTrip - id, listClosed)
  }

  /** Segna [media] come del viaggio ([on] vero) o toglie il segno. Se era "non del viaggio" smette di esserlo. */
  fun withInTrip(id: String, media: String, on: Boolean): AlbumsLabelSet = withInTrip(id, setOf(media), on)

  fun withInTrip(id: String, media: Set<String>, on: Boolean): AlbumsLabelSet {
    requireTrip(id, media)
    val now = if (on) inTripOf(id) + media else inTripOf(id) - media
    val others = if (on) notInTripOf(id) - media else notInTripOf(id)
    return AlbumsLabelSet(trips, inTrip + (id to now), notInTrip + (id to others), listClosed)
  }

  /** Segna [media] come "non del viaggio" ([on] vero) o toglie il segno. Se era "del viaggio" smette di esserlo. */
  fun withNotInTrip(id: String, media: String, on: Boolean): AlbumsLabelSet = withNotInTrip(id, setOf(media), on)

  fun withNotInTrip(id: String, media: Set<String>, on: Boolean): AlbumsLabelSet {
    requireTrip(id, media)
    val now = if (on) notInTripOf(id) + media else notInTripOf(id) - media
    val others = if (on) inTripOf(id) - media else inTripOf(id)
    return AlbumsLabelSet(trips, inTrip + (id to others), notInTrip + (id to now), listClosed)
  }

  /** Chiude l'elenco dei viaggi principali: dopo aver guardato le proposte non si cambia piu'. */
  fun closed(): AlbumsLabelSet = if (listClosed) this else AlbumsLabelSet(trips, inTrip, notInTrip, listClosed = true)

  private fun requireTrip(id: String, media: Set<String>) {
    require(trip(id) != null) { "Viaggio sconosciuto." }
    require(media.all(AlbumsLabelFormat::isMedia)) { "Media non valido." }
  }

  private fun sorted(): AlbumsLabelSet = AlbumsLabelSet(trips.sortedBy { it.id }, inTrip, notInTrip, listClosed)

  /** Solo conteggi. */
  override fun toString(): String =
    "AlbumsLabelSet(${trips.size} viaggi, ${inTrip.values.sumOf { it.size }} del viaggio, ${notInTrip.values.sumOf { it.size }} non del viaggio, chiuso=$listClosed)"

  companion object {
    const val MAX_TRIPS: Int = 60
  }
}
