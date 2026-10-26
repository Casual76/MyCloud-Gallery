package dev.pampa.mycloudgallery.hidden

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

// Fase 7 (P9/P7, D22, E-H7; integrazione 26/10): la SONDA sul codice VERO dei viaggi. Prende il posto della sonda di riferimento (`ReferenceProbes.trips`, che girava su
// `InMemoryTrips` con una storia sua): qui la rilevazione di P7 (`CatalogTrips`) gira sul catalogo vero del mondo casuale (Room in memoria, le query dei candidati di P1,
// la visibilita' di P9) e si confronta con un oracolo ricavato dalla **descrizione** del mondo, mai dal catalogo.
//
// L'oracolo (la storia e' in `RealTripsStack`): i media del 7-10 marzo sono a Oslo di giorno, gli altri a casa di sera. Una proposta ha **esattamente** i media propri
// (telefono o area privata), non screenshot, che sono celle della Libreria adesso (non nascosti, non membri di un gruppo con un nascosto, una cella per gruppo: D20, D22,
// E-L11); un giorno "lontano" e' uno con almeno una di queste celle a Oslo; c'e' un viaggio solo se ci sono almeno due giorni lontani e una casa (almeno 5 giorni a casa e
// il 15% dei giorni con GPS), e allora i suoi giorni vanno dal primo all'ultimo giorno lontano. Poi, **dopo la rilevazione**, si nasconde la copertina del viaggio (un
// dato arrivato a meta': scritto senza espandere al gruppo) e si ricontrolla: conteggio e copertina seguono l'oracolo nuovo, e un viaggio che resta senza media sparisce.

/** Quante volte la sonda ha davvero provato qualcosa (un controllo che non vede mai un viaggio non controlla niente): lo legge `RealTripsProbeTest`. */
internal object TripsProbeStats {
  var worlds = 0
  var withTrip = 0
  var coverHiddenAfter = 0
  var vanishedAfter = 0

  fun reset() {
    worlds = 0
    withTrip = 0
    coverHiddenAfter = 0
    vanishedAfter = 0
  }
}

internal object RealTripsProbes {

  private val away = setOf(7, 8, 9, 10)

  /** Cio' che l'oracolo di [world] dice dei viaggi: le celle proprie e non screenshot per giorno, e l'eventuale viaggio. */
  private class Oracle(spec: WorldSpec, oracle: HiddenOracle) {
    val cells: Map<LocalDate, List<Int>> = oracle.liveLibraryCells
      .filter { spec.media[it].where != Where.NAS_COMMON && spec.media[it].source != MediaSource.SCREENSHOT }
      .groupBy { spec.media[it].day }

    private val farDays = cells.filterKeys { it.dayOfMonth in away }.keys.sorted()
    private val homeDays = cells.keys.count { it.dayOfMonth !in away }
    val homeKnown: Boolean = homeDays >= MIN_HOME_DAYS && homeDays.toDouble() / (homeDays + farDays.size) >= HOME_SHARE

    /** I giorni del viaggio che la rilevazione deve proporre, o null. */
    val trip: LocalDayRange? = if (homeKnown && farDays.size >= 2) LocalDayRange(farDays.first(), farDays.last()) else null

    fun inRange(range: LocalDayRange): List<Int> = cells.filterKeys { it in range }.values.flatten()
  }

  /** Le violazioni di [proposals] rispetto a [oracle]; i media che le proposte mostrano (copertine) vanno in [shown], con gli id del mondo [world]. */
  private fun check(
    label: String,
    world: BuiltWorld,
    oracle: Oracle,
    proposals: List<TripProposal>,
    expected: List<LocalDayRange>,
    problems: MutableList<String>,
    shown: MutableSet<MediaId>,
    original: BuiltWorld,
  ) {
    val trips = proposals.filter { it.kind == TripKind.TRIP }
    if (proposals.any { it.kind == TripKind.EVENT }) problems += "$label: un evento in un mondo senza feste"
    if (trips.map { it.days } != expected) problems += "$label: i viaggi proposti non sono quelli attesi (${trips.size} invece di ${expected.size})"
    for (p in trips) {
      val members = oracle.inRange(p.days)
      val covers = members.map { world.ids[it] }
      if (p.count != members.size) problems += "$label: una proposta conta ${p.count} media, ne attendeva ${members.size}"
      if (p.cover !in covers) problems += "$label: la copertina di una proposta non e' una cella visibile, propria e non screenshot dei suoi giorni"
      shown += original.ids[world.indexOf(p.cover)]
    }
  }

  val trips = SurfaceProbe { original ->
    val context: Context = ApplicationProvider.getApplicationContext()
    val own = HiddenWorldBuilder.build(context, original.spec)
    RealTripsStack.build(own).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()

      // 1. La rilevazione sul mondo com'e': gli "ultimi nascosti" ci sono gia' (L'istantanea non li ha tolti: lo fanno la visibilita' e il filtro finale).
      val report = stack.trips.detect(2.minutes)
      val before = Oracle(own.spec, own.oracle)
      val expected = listOfNotNull(before.trip)
      TripsProbeStats.worlds++
      if (expected.isNotEmpty()) TripsProbeStats.withTrip++
      if (report.homeKnown != before.homeKnown) problems += "la casa risulta ${if (report.homeKnown) "stimata" else "non stimata"} contro ${before.homeKnown}"
      val detected = stack.trips.proposals.value
      if (detected.homeKnown != before.homeKnown) problems += "la sezione Viaggi dice casa=${detected.homeKnown}, l'oracolo ${before.homeKnown}"
      check("dopo la rilevazione", own, before, detected.proposals, expected, problems, shown, original)

      // 2. Dopo la rilevazione si nasconde la copertina di un viaggio (dato arrivato a meta': scritto direttamente, senza espandere al gruppo) e si ricontrolla.
      val victim = detected.proposals.firstOrNull { it.kind == TripKind.TRIP }?.cover
      if (victim != null) {
        val hiddenNow = own.indexOf(victim)
        own.db.personalDao().upsert(MediaPersonalEntity(victim.value, favorite = false, hidden = true, updatedAt = 1_780_000_100_000L))
        val extended = WorldSpec(
          own.spec.seed, own.spec.media, own.spec.groups, own.spec.hiddenByUser + hiddenNow, own.spec.hiddenPartial, own.spec.favorites, own.spec.lateHidden,
        )
        val afterWorld = Oracle(extended, HiddenOracle(extended))
        stack.trips.refresh()
        val kept = if (afterWorld.inRange(before.trip!!).isEmpty()) emptyList() else listOf(before.trip)
        TripsProbeStats.coverHiddenAfter++
        if (kept.isEmpty()) TripsProbeStats.vanishedAfter++
        check("dopo aver nascosto la copertina", own, afterWorld, stack.trips.proposals.value.proposals, kept, problems, shown, original)
        if (stack.trips.proposals.value.proposals.any { it.cover == victim }) problems += "la copertina nascosta dopo la rilevazione e' ancora la copertina"
      }

      // 3. Mai una cella di un gruppo con un nascosto, e mai un id che non esiste: lo dice anche il controllo generale su `shown`.
      Probed(shown = shown, within = original.liveShowable, problems = problems)
    }
  }

  private const val MIN_HOME_DAYS = 5
  private const val HOME_SHARE = 0.15
}
