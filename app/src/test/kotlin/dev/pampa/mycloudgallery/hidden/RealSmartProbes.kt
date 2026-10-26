package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate
import kotlin.time.Duration

// Fase 7 (P6, D13-D16, D22, E-AL8; integrazione): le SONDE sul codice VERO degli album smart: il motore che classifica e scrive (`RulesSmartEngine`) e l'editor
// che propone le regole (`RuleSmartNameInterpreter`), sul catalogo vero del mondo casuale. Prendono il posto della sonda di riferimento (`ReferenceProbes.smartAlbums`).
//
// L'oracolo viene dal mondo, mai dal catalogo: le celle della Libreria **adesso** (`liveLibraryCells`: non nascoste, non membri di un gruppo con un nascosto, anche
// se nascosto un attimo dopo l'istantanea), i giorni e le sorgenti dalla descrizione. Il motore non scrive mai un nascosto ne' un membro di un gruppo nascosto, non
// scrive niente fuori dal periodo, non da' mai un sicuro a uno screenshot ne' a un media con il GPS lontano, e prende tutte le celle visibili che la regola A vuole.

internal object RealSmartProbes {

  private val FIRST = LocalDate.of(2026, 3, 4)
  private val LAST = LocalDate.of(2026, 3, 10)
  private val OSLO = GeoPoint(59.9139, 10.7522)

  private fun period(): SmartPeriod = SmartPeriod.Ranges(listOf(LocalDayRange(FIRST, LAST)))

  private fun oslo(): PlaceRule = PlaceRule(listOf(RealSmartStack.OSLO_ID), OSLO, 25, "Oslo", PlaceScope.AROUND)

  /** Le celle visibili adesso con le loro proprieta' nel mondo (giorno, sorgente, GPS di Oslo e dove stanno). */
  private class Cell(val id: MediaId, val index: Int, val day: LocalDate, val screenshot: Boolean, val osloGps: Boolean, val common: Boolean, val farGps: Boolean)

  private fun cellsOf(w: BuiltWorld): List<Cell> = w.liveLibraryCells.map { id ->
    val index = w.indexOf(id)
    val m = w.spec.media[index]
    Cell(id, index, m.day, m.source == MediaSource.SCREENSHOT, index % 5 == 0 || index % 5 == 1, m.where == Where.NAS_COMMON, index % 5 == 2 || index % 5 == 3)
  }

  /** Il motore: tre album (solo il periodo, luogo e periodo, solo il luogo) e l'anteprima, con nascosti, gruppi e "ultimi nascosti". */
  val engine = SurfaceProbe { w ->
    val stack = RealSmartStack(w)
    stack.enrich()
    val problems = ArrayList<String>()
    val shown = LinkedHashSet<MediaId>()
    val cells = cellsOf(w)
    val inPeriod = cells.filter { it.day in FIRST..LAST }

    // 1. Solo il periodo: ogni cella visibile del periodo e' un sicuro (mio) o un suggerito (uno screenshot, o di un'area comune che non e' mia); niente altro.
    val onlyPeriod = stack.createAlbum(1, SmartRules(period(), null, null))
    stack.engine.evaluate(onlyPeriod, Duration.INFINITE)
    val (sureA, sugA) = stack.written(onlyPeriod)
    shown += sureA + sugA
    if (sureA + sugA != inPeriod.map { it.id }.toSet()) problems += "solo il periodo: gli elementi e i suggeriti non sono esattamente le celle visibili del periodo"
    if (sureA.size + sugA.size != (sureA + sugA).size) problems += "solo il periodo: un media e' sia elemento sia suggerito"
    if (inPeriod.any { it.screenshot && it.id in sureA }) problems += "solo il periodo: uno screenshot e' un elemento"
    if (inPeriod.any { it.screenshot && it.id !in sugA }) problems += "solo il periodo: uno screenshot non e' fra i suggeriti"
    if (inPeriod.any { !it.screenshot && !it.common && it.id !in sureA }) problems += "solo il periodo: un media mio e' rimasto fuori dagli elementi"

    // 2. Luogo e periodo: la regola A prende tutte le celle visibili con il GPS di Oslo nel periodo; mai un GPS lontano, mai fuori periodo, mai uno screenshot.
    val placeAndPeriod = stack.createAlbum(2, SmartRules(period(), oslo(), null))
    stack.engine.evaluate(placeAndPeriod, Duration.INFINITE)
    val (sureB, sugB) = stack.written(placeAndPeriod)
    shown += sureB + sugB
    val byId = cells.associateBy { it.id }
    if (inPeriod.any { it.osloGps && !it.screenshot && !it.common && it.id !in sureB }) problems += "luogo e periodo: una cella di Oslo nel periodo non e' un elemento (regola A)"
    if (sureB.any { id -> byId[id]?.let { it.day !in FIRST..LAST || it.farGps || it.screenshot } != false }) problems += "luogo e periodo: un elemento fuori dal periodo, con il GPS lontano o uno screenshot"
    if (sugB.any { id -> byId[id]?.let { it.day !in FIRST..LAST || it.farGps } != false }) problems += "luogo e periodo: un suggerito fuori dal periodo o con il GPS lontano"

    // 3. Solo il luogo: tutte le celle visibili con il GPS di Oslo (A senza periodo), mai quelle con il GPS altrove.
    val onlyPlace = stack.createAlbum(3, SmartRules(null, oslo(), null))
    stack.engine.evaluate(onlyPlace, Duration.INFINITE)
    val (sureC, sugC) = stack.written(onlyPlace)
    shown += sureC + sugC
    if (cells.any { it.osloGps && !it.screenshot && !it.common && it.id !in sureC }) problems += "solo il luogo: una cella di Oslo non e' un elemento"
    if (sureC.any { id -> byId[id]?.let { it.farGps || it.screenshot } != false }) problems += "solo il luogo: un elemento con il GPS lontano o uno screenshot"

    // 4. L'anteprima: gli stessi numeri e miniature che si possono mostrare.
    val preview = stack.engine.preview(SmartRules(period(), oslo(), null))
    shown += preview.sample
    if (preview.confident != sureB.size || preview.suggested != sugB.size) problems += "l'anteprima dice ${preview.confident}/${preview.suggested}, il motore ha scritto ${sureB.size}/${sugB.size}"
    if (preview.sample.size > 12) problems += "l'anteprima ha ${preview.sample.size} miniature"

    // 5. Un secondo giro non scrive niente e l'incrementale su tutti i giorni da' le stesse righe.
    val before = stack.written(placeAndPeriod)
    stack.engine.evaluate(placeAndPeriod, Duration.INFINITE)
    stack.engine.reevaluate(placeAndPeriod, (0 until 16).map { LocalDate.of(2026, 3, 1).plusDays(it.toLong()) })
    if (stack.written(placeAndPeriod) != before) problems += "luogo e periodo: un secondo giro o l'incrementale cambia le righe"
    Probed(shown = shown, within = w.liveShowable, problems = problems)
  }

  /** L'editor: la proposta per "Oslo marzo 2026" (le serie di giorni dalle sole foto visibili) e l'anteprima della proposta. */
  val editor = SurfaceProbe { w ->
    val stack = RealSmartStack(w)
    stack.enrich()
    val problems = ArrayList<String>()
    val today = LocalDate.of(2026, 10, 26)
    val proposal = stack.interpreter().propose("Oslo marzo 2026", today)
    val cells = cellsOf(w)
    // I giorni con foto visibili a Oslo: "certe" (di sicuro mie) e "possibili" (anche quelle dell'area comune, che il catalogo puo' contare come mie).
    val certainDays = cells.filter { it.osloGps && !it.screenshot && !it.common }.map { it.day }.toSet()
    val possibleDays = cells.filter { it.osloGps && !it.screenshot }.map { it.day }.toSet()
    val place = proposal.rules.place
    // Il dizionario dei luoghi e' quello dell'istantanea (come Cerca): puo' conoscere un luogo di un gruppo nascosto un attimo fa, mai al contrario.
    if (place == null && cells.any { it.osloGps }) problems += "la proposta non ha il luogo ma ci sono celle visibili a Oslo"
    val series = proposal.periodSeries
    if (place != null) {
      // Ogni giorno con foto proprie visibili a Oslo e' in una serie; il primo e l'ultimo giorno di ogni serie hanno foto visibili (il resto e' il margine).
      for (day in certainDays) if (series.none { day in it }) problems += "un giorno con foto visibili di Oslo non e' in nessuna serie"
      for (range in series) {
        val core = listOf(range.from.plusDays(1), range.to.minusDays(1))
        if (core.any { it !in possibleDays }) problems += "una serie comincia o finisce su un giorno senza foto visibili di Oslo (una serie nata da un nascosto?)"
      }
      if (series.isNotEmpty() && possibleDays.isEmpty()) problems += "serie di giorni senza foto visibili di Oslo"
    }
    val preview = stack.engine.preview(proposal.rules)
    Probed(shown = preview.sample.toSet(), within = w.liveShowable, problems = problems)
  }
}
