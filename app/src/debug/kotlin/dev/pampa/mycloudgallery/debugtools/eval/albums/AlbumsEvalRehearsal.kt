package dev.pampa.mycloudgallery.debugtools.eval.albums

import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * SOLO debug (Fase 7, P12): la **prova generale** dello strumento sui viaggi sintetici (F87 con lo strumento al posto del PC). Il generatore dei viaggi sintetici
 * (`tools/fixtures/trips/generate_trips.py`) scrive per ogni storia `viaggi-sintetici.tsv`: gli album della storia con i loro membri (per nome di file) e i numeri
 * attesi dalla JVM; `adb` lo porta in `noBackupFilesDir/eval/albums/import/`. Qui la verita' e' nota, quindi la corsa e' automatica (l'utente ideale): si
 * creano i viaggi, si segnano come "del viaggio" i membri trovati nel catalogo, si crea ogni album (in memoria) e ogni altra foto che l'album ha messo si giudica
 * "non del viaggio". Poi si confrontano i numeri con quelli attesi. Le etichette stanno in una cartella a parte (`prova/`): non si mescolano mai a quelle
 * dei viaggi veri. Dati sintetici: nessun dato personale.
 */
class SyntheticExpected(val truth: Int, val sure: Int, val right: Int, val suggested: Int, val covered: Int) {
  override fun toString(): String = "SyntheticExpected"
}

class SyntheticAlbum(
  val index: Int,
  val title: String,
  val from: LocalDate,
  val to: LocalDate,
  /** I numeri della JVM solo per le foto del telefono (`mcg-prova`), se il file li ha. */
  val phone: SyntheticExpected?,
  /** I numeri della JVM con anche il tablet e i familiari, se il file li ha. */
  val full: SyntheticExpected?,
  val files: Set<String>,
) {
  override fun toString(): String = "SyntheticAlbum($index, ${files.size} file)"
}

object SyntheticImport {
  const val MAGIC: String = "#MCGSINT1"

  /** Gli album del file, nell'ordine; un file che non e' nel formato da' `IllegalArgumentException` con il numero di riga (mai il contenuto). */
  fun parse(text: String): List<SyntheticAlbum> {
    val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    require(lines.firstOrNull()?.startsWith(MAGIC) == true) { "Non e' un file di viaggi sintetici (intestazione)." }
    class Draft(val index: Int, val title: String, val from: LocalDate, val to: LocalDate, val phone: SyntheticExpected?, val full: SyntheticExpected?) {
      val files = LinkedHashSet<String>()
    }
    val albums = LinkedHashMap<Int, Draft>()
    lines.drop(1).forEachIndexed { i, line ->
      val n = i + 2
      val c = line.split('\t')
      try {
        when (c[0]) {
          "A" -> {
            require(c.size == 15)
            val index = c[1].toInt()
            albums[index] = Draft(index, c[2], LocalDate.parse(c[3]), LocalDate.parse(c[4]), expected(c.subList(5, 10)), expected(c.subList(10, 15)))
          }
          "M" -> {
            require(c.size == 3 && c[2].isNotEmpty())
            requireNotNull(albums[c[1].toInt()]).files += c[2]
          }
          else -> throw IllegalArgumentException()
        }
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Riga $n del file di viaggi sintetici non valida.")
      } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Riga $n del file di viaggi sintetici non valida.")
      }
    }
    return albums.values.map { SyntheticAlbum(it.index, it.title, it.from, it.to, it.phone, it.full, it.files) }
  }

  private fun expected(c: List<String>): SyntheticExpected? =
    if (c.any { it == "-" }) null else SyntheticExpected(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt())
}

class AlbumsEvalRehearsal(private val env: AlbumsEvalEnvironment, private val store: AlbumsEvalStore) {

  /** Le etichette e la corsa della prova generale (a parte da quelle dei viaggi veri). */
  val session: AlbumsEvalSession = AlbumsEvalSession(env, store)

  class Outcome(val text: String, val reportName: String, val albums: Int, val allEqual: Boolean)

  /** Quanti album ha il file portato dentro (0 se non c'e' o non si capisce), per la schermata. */
  fun albumsInImport(): Int = try {
    store.readImport()?.let { SyntheticImport.parse(it).size } ?: 0
  } catch (e: IllegalArgumentException) {
    0
  }

  /** Null se il file non c'e'. Un file rovinato lancia `IllegalArgumentException` (con il numero di riga). */
  suspend fun run(): Outcome? {
    val albums = SyntheticImport.parse(store.readImport() ?: return null)
    if (albums.isEmpty()) return null
    store.wipe()
    session.load()
    val found = HashMap<String, MediaId>()
    for (chunk in albums.flatMap { it.files }.distinct().chunked(NAMES_PER_QUERY)) found += env.mediaByFileName(chunk.toSet())

    val rows = ArrayList<Row>()
    for (album in albums) {
      val trip = session.addTrip(album.title, album.from, album.to)
      val members = album.files.mapNotNull { found[it]?.value }
      if (members.isEmpty()) {
        // Nessuna foto di questo album e' nella Libreria (non caricata, non ancora scansionata): niente da misurare.
        rows += Row(album, 0, TripMetrics.ZERO, null, 0)
        continue
      }
      session.setInTrip(trip.id, members, true)
      val run = session.runTrip(trip.id)
      // L'utente ideale: la verita' e' nota, ogni altra foto messa dall'album e' "non del viaggio".
      val pending = session.pending(trip.id)
      for (media in pending.sure + pending.suggested) session.setVerdict(trip.id, media, Verdict.WRONG)
      val metrics = checkNotNull(session.metrics(trip.id))
      val variant = if (members.size == album.files.size) album.full else album.phone
      rows += Row(album, members.size, metrics, variant, run.series)
    }
    val text = format(rows)
    val name = store.saveReport(text)
    return Outcome(text, name, albums.size, rows.all { it.same })
  }

  private class Row(val album: SyntheticAlbum, val found: Int, val m: TripMetrics, val expected: SyntheticExpected?, val series: Int) {
    val same: Boolean
      get() = expected != null && expected.sure == m.sure && expected.right == m.sureRight && expected.suggested == m.suggested && expected.covered == m.covered && expected.truth == m.truth
  }

  private fun format(rows: List<Row>): String {
    val out = StringBuilder()
    val listed = rows.sumOf { it.album.files.size }
    val found = rows.sumOf { it.found }
    out.append("Prova generale sui viaggi sintetici: ${rows.size} album, $found foto trovate nel catalogo su $listed elencate\n")
    out.append("Numeri attesi: ${if (found == listed) "completo (anche tablet e familiari)" else "solo telefono (mcg-prova)"}\n")
    out.append("album | trovate/elencate | foto del viaggio | sicuri (atteso) | giusti (atteso) | suggeriti (atteso) | coperte (atteso) | serie | esito\n")
    out.append("---|---|---|---|---|---|---|---|---\n")
    for (r in rows) {
      val e = r.expected
      fun cell(value: Int, expected: Int?): String = if (expected == null) "$value (-)" else "$value ($expected)"
      out.append("${r.album.index} | ${r.found}/${r.album.files.size} | ${r.m.truth} | ${cell(r.m.sure, e?.sure)} | ${cell(r.m.sureRight, e?.right)} | ")
      out.append("${cell(r.m.suggested, e?.suggested)} | ${cell(r.m.covered, e?.covered)} | ${r.series} | ")
      out.append(if (e == null) "senza atteso" else if (r.same) "uguale" else "diverso: sicuri ${signed(r.m.sure - e.sure)}, coperte ${signed(r.m.covered - e.covered)}").append('\n')
    }
    val total = AlbumsEvalMetrics.total(rows.map { it.m })
    val expectedTotal = rows.mapNotNull { it.expected }.takeIf { it.size == rows.size }
    out.append('\n')
    out.append("Misurato: precisione ${AlbumsEvalReport.pct(total.precision)}, copertura ${AlbumsEvalReport.pct(total.coverage)} ")
    out.append("(soglie sintetiche: ${AlbumsEvalReport.pct(SYNTHETIC_PRECISION)} e ${AlbumsEvalReport.pct(SYNTHETIC_COVERAGE)}): ")
    out.append(if (total.precision >= SYNTHETIC_PRECISION && total.coverage >= SYNTHETIC_COVERAGE) "RAGGIUNTE" else "NON raggiunte").append('\n')
    if (expectedTotal != null) {
      val sure = expectedTotal.sumOf { it.sure }
      val right = expectedTotal.sumOf { it.right }
      val truth = expectedTotal.sumOf { it.truth }
      val covered = expectedTotal.sumOf { it.covered }
      out.append("Atteso dalla JVM: precisione ${AlbumsEvalReport.pct(right.toDouble() / sure)}, copertura ${AlbumsEvalReport.pct(covered.toDouble() / truth)}\n")
    }
    out.append("Album uguali alla JVM: ${rows.count { it.same }} su ${rows.size}\n")
    return out.toString()
  }

  private fun signed(v: Int): String = if (v > 0) "+$v" else "$v"

  private companion object {
    const val NAMES_PER_QUERY = 400
    const val SYNTHETIC_PRECISION = 0.99
    const val SYNTHETIC_COVERAGE = 0.95
  }
}
