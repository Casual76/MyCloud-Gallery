package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.rules.DefaultMediaDescriber
import dev.pampa.mycloudgallery.core.describe.rules.ItalianDescribeWords
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import dev.pampa.mycloudgallery.core.describe.testing.FakeA11yActivity
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePeople
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePlaces
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribeText
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.ZoneId

// Fase 10 (P7, D22, D41; 31/10): la SONDA della riga `DESCRIPTIONS` del registro dei Nascosti. Le descrizioni sono una superficie: dicono il tipo, la data, il
// luogo e (con uno strumento di accessibilita') le persone e il testo letto di un media. Su ogni mondo casuale il descrittore vero (`DefaultMediaDescriber`, con i fake
// delle sue porte) descrive le celle che la Libreria mostra, come fa una griglia per il lettore di schermo (`warm` sulle celle in vista, poi `describe` per cella).
// Le porte conoscono i nomi e i testi di **tutti** i media del mondo, anche dei nascosti (con un segnale riconoscibile): una descrizione non deve mai portarne uno,
// e non deve esistere per un media che la Libreria non mostra. Le persone nascoste o senza nome non arrivano al descrittore: la porta le toglie (suite
// `DescribePortsContract`), e qui un nome del mondo e' sempre di una persona dicibile.

internal object RealDescriptionProbes {

  /** Un nome o un testo che appartiene a un media che non si deve vedere: se compare in una descrizione, e' una fuga. */
  const val SECRET: String = "SEGRETO"

  /** Il giorno dello scatto e il fuso: non contano per la prova. */
  private val zone: ZoneId = ZoneId.of("Europe/Rome")

  /** Il descrittore vero con le porte del mondo: nomi e testo di ogni media, quelli dei nascosti marcati con [SECRET]. */
  fun describerOf(w: BuiltWorld): MediaDescriber {
    val hidden = w.mustNeverShow
    val names = w.ids.withIndex().associate { (i, id) -> id to listOf(if (id in hidden) "$SECRET$i" else "Persona$i") }
    val texts = w.ids.withIndex().associate { (i, id) -> id to if (id in hidden) "$SECRET scontrino $i" else "Testo letto $i" }
    return DefaultMediaDescriber(FakeDescribePlaces(emptyMap()), FakeDescribePeople(names), FakeDescribeText(texts), ItalianDescribeWords.VALUE, FakeA11yActivity())
  }

  /**
   * Le descrizioni delle celle di [cells] (indici dell'istantanea del mondo): warm a gruppi di 40 come una griglia, poi `describe` per cella.
   * Torna gli id descritti e le violazioni (frasi senza id).
   */
  suspend fun run(w: BuiltWorld, cells: List<Int>, describer: MediaDescriber = describerOf(w), warmIds: (List<Int>) -> List<MediaId> = { c -> c.map { w.snapshot.idAt(it) } }): Probed {
    val shown = LinkedHashSet<MediaId>()
    val problems = ArrayList<String>()
    for (chunk in cells.chunked(40)) describer.warm(warmIds(chunk))
    for (cell in cells) {
      val id = w.snapshot.idAt(cell)
      val text = MediaDescribers.describeCell(describer, w.snapshot, cell, zone, DescribeSurface.IN_APP)
      if (text == null) {
        problems += "una cella della Libreria non ha una descrizione"
        continue
      }
      shown += id
      if (text.contains(SECRET)) problems += "una descrizione porta il nome o il testo di un media nascosto"
      val own = w.indexOf(id)
      if (!text.contains("Persona$own") || !text.contains("Testo letto $own")) problems += "una descrizione non ha la sua persona o il suo testo letto"
      val widget = MediaDescribers.describeCell(describer, w.snapshot, cell, zone, DescribeSurface.WIDGET).orEmpty()
      if (widget.contains("Persona") || widget.contains("Testo") || widget.contains(SECRET)) problems += "la descrizione del widget porta persone o testo letto"
      if (MediaDescribers.describeCell(describer, w.snapshot, cell, zone, DescribeSurface.NOTIFICATION).orEmpty().isNotEmpty()) {
        problems += "la descrizione di una notifica non e' vuota"
      }
    }
    return Probed(shown = shown, expected = w.libraryCells, within = w.libraryCells, problems = problems.distinct())
  }

  /** Le descrizioni: le celle che la Libreria mostra, e nient'altro. */
  val descriptions = SurfaceProbe { w -> run(w, (0 until w.snapshot.size).toList()) }
}
