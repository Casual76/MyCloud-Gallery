package dev.pampa.mycloudgallery.widget

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbContext
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemories
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetThumbs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetContent
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetRefresh
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetSettings
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// LE PORTE VERE DEL WIDGET (Fase 10, P13, docs/fase10-progetto.md §1.4 "WidgetGraph", §2.1, D4, D7, D8; "Cosa serve a P13" di P2 e P3 in docs/decisioni.md). Ogni
// porta fallisce **chiusa**: un errore = nessuna miniatura, nessun media "anche sul telefono", nessun ricordo. Il pianificatore (`DefaultWidgetPlanner`) ricontrolla
// comunque tutto con il filtro finale. Mai il NAS, mai la rete: le miniature sono quelle gia' sul telefono (replica o MediaStore).

/**
 * Le miniature **locali** (D7, C431, E-W5): un media del telefono ha la sua miniatura (MediaStore); un media solo sul NAS solo se la replica ha la sua miniatura
 * preparata (`canUpgrade`: un controllo in memoria, mai una lettura dal NAS). La decodifica passa con `ThumbContext.Ahead`: la sorgente del NAS non legge mai
 * l'originale per una richiesta "non visibile" (risponde `Deferred`), quindi nemmeno un caso raro arriva alla rete.
 */
class LocalWidgetThumbs(
  private val refOf: (MediaId) -> MediaRef?,
  private val source: ThumbnailSource,
  private val counters: WidgetCounters? = null,
) : WidgetThumbs, WidgetThumbnails {

  override suspend fun local(ids: Collection<MediaId>): Set<MediaId> {
    val out = LinkedHashSet<MediaId>()
    for (id in ids) {
      val ref = try {
        refOf(id)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      } ?: continue
      val local = when (ref) {
        is MediaRef.Phone, is MediaRef.Synthetic -> true
        is MediaRef.Nas -> source.canUpgrade(ref, PROBE_PX)
      }
      if (local) out += id
    }
    counters?.skippedNoThumb(ids.size - out.size)
    return out
  }

  override suspend fun load(media: MediaId, longSidePx: Int): Bitmap? {
    val ref = refOf(media) ?: return null
    val result = source.loadResult(ref, longSidePx.coerceAtLeast(1), CancellationSignal(), ThumbContext.Ahead)
    return (result as? ThumbResult.Loaded)?.bitmap
  }

  private companion object {
    /** La taglia con cui si chiede "c'e' gia'?": quella delle celle piccole (la replica ha sempre la S, il livello Risparmio solo quella). */
    const val PROBE_PX = 200
  }
}

/**
 * Il blocco dell'app per il widget (D8, Q3 (c)): **acceso** se il blocco e' attivo nelle impostazioni, e anche finche' le impostazioni non si sono lette (null: fallire
 * chiusi). I media "anche sul telefono" sono le celle dell'istantanea con `ON_PHONE`; senza istantanea, nessuno.
 */
class SnapshotWidgetLock(
  private val settings: StateFlow<AppLockSettings?>,
  private val snapshot: StateFlow<TimelineSnapshot?>,
  private val counters: WidgetCounters? = null,
) : WidgetLock {

  override val appLocked: StateFlow<Boolean> = MappedStateFlow(settings) { it?.isEnabled ?: true }

  override suspend fun onPhone(ids: Collection<MediaId>): Set<MediaId> {
    val current = snapshot.value ?: return emptySet()
    val out = ids.filterTo(LinkedHashSet()) { id ->
      val index = current.indexOf(id)
      index >= 0 && CellFlags.has(current.flagsAt(index), CellFlags.ON_PHONE)
    }
    counters?.skippedLocked(ids.size - out.size)
    return out
  }
}

/**
 * Le scelte del widget (Q1, Q2) sopra quelle di Impostazioni -> Widget: gli stessi codici (`WidgetSettingsCodesTest` lo prova in feature-settings). Le impostazioni si
 * aprono alla prima lettura (un file di preferenze): mai sul thread principale all'avvio del processo, ma dal primo giro del widget o dalle iscrizioni in sottofondo.
 */
class SettingsWidgetPrefs(settings: () -> WidgetSettings) : WidgetPrefs {

  constructor(settings: WidgetSettings) : this({ settings })

  private val source: WidgetSettings by lazy(settings)

  override val choice: StateFlow<WidgetChoice> by lazy { MappedStateFlow(source.content) { choiceOf(it) } }

  override val period: StateFlow<WidgetPeriod> by lazy { MappedStateFlow(source.period) { periodOf(it) } }

  override suspend fun setChoice(choice: WidgetChoice) = source.setContent(WidgetContent.ofCode(choice.code))

  override suspend fun setPeriod(period: WidgetPeriod) = source.setPeriod(WidgetRefresh.ofCode(period.code))

  companion object {
    fun choiceOf(content: WidgetContent): WidgetChoice = WidgetChoice.entries.firstOrNull { it.code == content.code } ?: WidgetChoice.DEFAULT

    fun periodOf(refresh: WidgetRefresh): WidgetPeriod = WidgetPeriod.entries.firstOrNull { it.code == refresh.code } ?: WidgetPeriod.DEFAULT
  }
}

/** L'ora per il widget: locale per le ore di quiete (D6), monotona per budget e calma (§4.3 "Ora"). */
object SystemWidgetClock : WidgetClock {
  override fun nowLocal(): LocalDateTime = LocalDateTime.now()

  override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Il ricordo di oggi per il widget (D4, come la notifica di F9-D15): dallo scaffale (mai uno scartato, mai un nascosto noto), **pubblicato oggi**, mai `PERSON`, mai un
 * titolo con una persona, mai una copertina con una persona a cui l'utente ha detto "Mostra meno" o "Mai". Il titolo passa come **dati** (anni, luoghi, viaggio, mese).
 * Ricordi non montati (processo freddo, famiglia non pronta) o un errore = null: il widget passa alle foto.
 */
class ShelfWidgetMemories(private val memories: MemoriesGraph) : WidgetMemories {

  override suspend fun today(): WidgetMemoryPick? = try {
    pick()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  private suspend fun pick(): WidgetMemoryPick? {
    val live = memories.live.value ?: return null
    val todayDay = NotifyLedger.dayOf(memories.today())
    val lessPeople = memories.memories.lessChoices.value.mapNotNull { (it.target as? LessTarget.Person)?.person }.toSet()
    val candidates = memories.memories.shelf.value.items
      .filter { it.publishedDay == todayDay && it.kind != MemoryKind.PERSON && it.title.person == null }
      .sortedWith(compareBy<MemoryCard> { rank(it.kind) }.thenBy { it.title.yearsAgo ?: Int.MAX_VALUE }.thenBy { it.id.value })
    for (card in candidates) {
      val view = withTimeoutOrNull(VIEW_WAIT_MS) { memories.memories.memory(card.id).first() } ?: continue
      if (lessPeople.isNotEmpty()) {
        val onCover = live.people.peopleIn(listOf(view.cover))[view.cover].orEmpty()
        if (onCover.any { it in lessPeople }) continue
      }
      val title = view.memory.title
      return WidgetMemoryPick(
        id = card.id,
        kind = card.kind,
        title = WidgetTitle(
          yearsAgo = title.yearsAgo,
          placeIds = title.placeIds.take(WidgetTitle.MAX_PLACES),
          tripLabel = title.tripLabel?.take(WidgetTitle.MAX_LABEL),
          month = title.month,
        ),
        cover = view.cover,
        members = (listOf(view.cover) + view.memory.members).distinct(),
      )
    }
    return null
  }

  private fun rank(kind: MemoryKind): Int = when (kind) {
    MemoryKind.TRIP, MemoryKind.EVENT -> 0
    MemoryKind.ON_THIS_DAY -> 1
    MemoryKind.MONTH -> 2
    else -> 3
  }

  private companion object {
    /** Il ricordo vivo sull'istantanea, al piu' questo: il pianificatore ha un budget di 2 s in tutto. */
    const val VIEW_WAIT_MS = 500L
  }
}

/** Un `StateFlow` che legge [source] e lo trasforma con [transform] al momento (nessuno scope, nessun lavoro in sottofondo). */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class MappedStateFlow<S, T>(private val source: StateFlow<S>, private val transform: (S) -> T) : StateFlow<T> {
  override val value: T get() = transform(source.value)

  override val replayCache: List<T> get() = listOf(value)

  override suspend fun collect(collector: FlowCollector<T>): Nothing {
    var first = true
    var last: T? = null
    source.collect { raw ->
      val mapped = transform(raw)
      if (first || mapped != last) {
        first = false
        last = mapped
        collector.emit(mapped)
      }
    }
  }
}
