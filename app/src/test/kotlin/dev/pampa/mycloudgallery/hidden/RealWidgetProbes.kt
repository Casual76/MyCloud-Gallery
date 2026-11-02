package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.core.widget.plan.DefaultWidgetPlanner
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetLock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetMemories
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetThumbs
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 10 (P3, D3-D8, D41, E-H1, E-H11; 31/10): la SONDA della riga `WIDGET` del registro dei Nascosti, **sul pianificatore vero** (`DefaultWidgetPlanner`) sopra la Libreria vera del
// mondo casuale (`CatalogVisibleMedia`, `CatalogHiddenMask`, `RotatingWidgetFeed`: la stessa fonte che il widget usa sul telefono). Le porte che il pianificatore chiede fuori dalla
// Libreria sono **finte e generose**: **ogni** media ha la miniatura sul telefono (nessuna scusa per saltare un nascosto), il ricordo di oggi e' fatto di **tutti** i media del mondo
// (nascosti compresi, e con un nascosto per copertina), c'e' anche un ricordo `PERSON`. La sonda dice cosa il widget mostrerebbe: tutte le celle di tutti i piani, in tutte le
// misure, per tre widget, con il blocco spento e con il blocco acceso. Un nascosto, un membro di un gruppo con un nascosto, un ultimo-momento, un media solo sul NAS con il blocco, un
// ricordo `PERSON`: ognuno e' un fallimento. La proprieta' sul mondo da 100 mila e' `DefaultWidgetPlannerBigWorldTest` (core-widget); lo svuotamento, il registro prima del disegno e il
// processo freddo sono in `feature-widget` (`WidgetEngineTest`, `WidgetBlankTimingTest`, `GalleryWidgetReceiverTest`).

internal object RealWidgetProbes {

  /** Tre widget (come tre sulla Home) e le tre misure: ogni piano ha il suo cursore del feed, condiviso (E-W11: foto diverse). */
  private val slots = listOf(WidgetSlot(1), WidgetSlot(2), WidgetSlot(3))

  val widget = SurfaceProbe { w ->
    val visible = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
    val mask = CatalogHiddenMask(w.db)
    val feed = RotatingWidgetFeed(visible, mask)
    val everyone = w.ids
    // Anche sul telefono: ogni secondo media (gli altri, con il blocco acceso, non devono comparire: Q3 (c)).
    val onPhone: Set<MediaId> = everyone.filterIndexed { index, _ -> index % 2 == 0 }.toSet()
    val lock = FakeWidgetLock(locked = false, onPhone = onPhone)
    val memories = FakeWidgetMemories()
    val prefs = FakeWidgetPrefs()
    val planner = DefaultWidgetPlanner(
      feed = feed, memories = memories, visible = visible, mask = mask, thumbs = FakeWidgetThumbs(local = everyone.toSet()), lock = lock, prefs = prefs,
      clock = FakeWidgetClock(), ledger = InMemoryWidgetLedger(),
    )
    val shown = LinkedHashSet<MediaId>()
    val problems = ArrayList<String>()
    val rounds = w.snapshot.size / 4 + 3

    suspend fun collect(label: String, round: Int, onlyOnPhone: Boolean = false, expect: (WidgetPlan) -> String? = { null }) {
      for (slot in slots) for (size in WidgetSize.entries) {
        val plan = planner.plan(slot, size)
        shown += plan.shown
        if (onlyOnPhone && plan.shown.any { it !in onPhone }) problems += "$label: un media solo sul NAS con il blocco acceso (giro $round)"
        expect(plan)?.let { problems += "$label: $it (giro $round)" }
      }
    }

    // 1) Foto a caso, tante volte da fare piu' di un giro della Libreria.
    prefs.choice.value = WidgetChoice.PHOTOS_ONLY
    repeat(rounds) { collect("foto a caso", it) }

    // 2) Il ricordo di oggi con **tutti** i media del mondo, un nascosto per copertina se ce n'e': non deve portarne nessuno, e di titolo solo dati.
    prefs.choice.value = WidgetChoice.MEMORY_THEN_PHOTOS
    val hiddenCover = everyone.firstOrNull { it in w.liveMustNeverShow } ?: everyone.first()
    val title = WidgetTitle(yearsAgo = 1, placeIds = emptyList(), tripLabel = null, month = null)
    val memoryId = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 9)))
    for (kind in listOf(MemoryKind.ON_THIS_DAY, MemoryKind.TRIP, MemoryKind.EVENT, MemoryKind.MONTH)) {
      memories.pick = WidgetMemoryPick(memoryId, kind, title, cover = hiddenCover, members = everyone)
      repeat(3) { collect("ricordo ${kind.code}", it) }
    }

    // 3) Un ricordo `PERSON` non arriva mai al widget (D4: un nome di persona sulla Home lo vede chiunque).
    memories.pick = WidgetMemoryPick(memoryId, MemoryKind.PERSON, title, cover = everyone.first(), members = everyone)
    repeat(3) { round ->
      collect("ricordo di una persona", round) { plan -> if (plan.mode is WidgetMode.Memory) "un ricordo di una persona sul widget" else null }
    }

    // 4) Con il blocco dell'app acceso (Q3 (c)): solo media anche sul telefono, nessun titolo, nessun ricordo; con un ricordo di oggi che non deve contare.
    lock.appLocked.value = true
    prefs.choice.value = WidgetChoice.MEMORY_THEN_PHOTOS
    memories.pick = WidgetMemoryPick(memoryId, MemoryKind.ON_THIS_DAY, title, cover = hiddenCover, members = everyone)
    repeat(rounds) { round ->
      collect("blocco acceso", round, onlyOnPhone = true) { plan ->
        if (plan.mode is WidgetMode.Memory) "un ricordo (e il suo titolo) con il blocco acceso" else null
      }
    }
    lock.appLocked.value = false

    // La sonda non e' vuota per sbaglio: su una Libreria con abbastanza media il widget mostra qualcosa.
    if (shown.isEmpty() && w.liveLibraryCells.size >= MIN_FOR_SOMETHING) problems += "il widget non mostra niente su una Libreria con ${w.liveLibraryCells.size} celle"
    Probed(shown = shown, within = w.liveShowable, problems = problems.distinct())
  }

  /** Sotto questo numero di celle una Libreria puo' avere solo screenshot, documenti o animate (che il widget non mostra): non si pretende niente. */
  private const val MIN_FOR_SOMETHING = 40
}
