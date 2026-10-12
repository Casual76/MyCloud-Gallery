package dev.pampa.mycloudgallery.feature.diagnostics.aggregate

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tutto cio' che la Diagnostica sa in un istante: le voci di ogni sezione e quanti eventi ricorda l'anello.
 *
 * @property sections le voci per sezione, dalle piu' gravi; una sezione senza voci non c'e'.
 * @property recentEvents quanti eventi contiene il [DiagnosticsLog] (il dettaglio e' nella sezione `RECENT_EVENTS`).
 * @property unavailable le sezioni in cui un contributore non ha risposto (rotto o troppo lento).
 */
class DiagnosticsSnapshot(
  val sections: Map<DiagSection, List<DiagItem>>,
  val recentEvents: Int,
  val unavailable: Set<DiagSection> = emptySet(),
) {
  /** La gravita' peggiore della sezione, o null se e' vuota. */
  fun worst(section: DiagSection): DiagSeverity? = sections[section]?.maxOfOrNull { it.severity }

  /** Quante voci sono almeno [DiagSeverity.WARNING]. */
  val attentionCount: Int get() = sections.values.sumOf { items -> items.count { it.severity >= DiagSeverity.WARNING } }

  val errorCount: Int get() = sections.values.sumOf { items -> items.count { it.severity == DiagSeverity.ERROR } }

  companion object {
    val EMPTY = DiagnosticsSnapshot(emptyMap(), recentEvents = 0)
  }
}

/**
 * Interroga ogni [DiagnosticsContributor] e ne mette insieme le voci (docs/fase2-progetto.md §2.6).
 *
 *  - **In parallelo**, ciascuno con un tetto di tempo ([timeout]): `collect()` non deve toccare i dischi del NAS
 *    (E-R15), ma uno che si pianta non deve tenere ferma la schermata.
 *  - **Un contributore rotto non toglie gli altri**, e non sparisce in silenzio: al suo posto compare una voce
 *    `DIAG_SECTION_UNAVAILABLE` nella sua sezione. Una diagnostica che tace su cio' che non ha potuto leggere
 *    mente per omissione.
 *  - Le voci di una sezione stanno dalla piu' grave alla meno grave; a pari gravita' per codice, cosi' l'ordine
 *    non dipende da chi ha risposto per primo.
 *  - Un codice che il catalogo non conosce passa lo stesso: lo mostra col titolo del suo contributore.
 */
class DiagnosticsAggregator(
  private val contributors: Collection<DiagnosticsContributor>,
  private val log: DiagnosticsLog,
  private val timeout: Duration = DEFAULT_TIMEOUT,
) {

  suspend fun collect(): DiagnosticsSnapshot = coroutineScope {
    val results = contributors.map { contributor -> async { contributor to read(contributor) } }.map { it.await() }

    val bySection = LinkedHashMap<DiagSection, MutableList<DiagItem>>()
    val unavailable = LinkedHashSet<DiagSection>()
    for ((contributor, items) in results) {
      if (items == null) {
        unavailable += contributor.section
        bySection.getOrPut(contributor.section) { mutableListOf() } += unavailableItem(contributor.section)
        continue
      }
      for (item in items) bySection.getOrPut(item.section) { mutableListOf() } += item
    }

    val ordered = DiagSection.entries
      .filter { it in bySection }
      .associateWith { section ->
        bySection.getValue(section).sortedWith(
          compareByDescending<DiagItem> { it.severity }.thenBy { it.code.id },
        )
      }
    DiagnosticsSnapshot(ordered, recentEvents = log.snapshot().size, unavailable = unavailable)
  }

  /** Le voci del contributore, o null se non ha risposto (eccezione o tempo scaduto). */
  private suspend fun read(contributor: DiagnosticsContributor): List<DiagItem>? = try {
    withTimeoutOrNull(timeout) { contributor.collect() }
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  private fun unavailableItem(section: DiagSection) = DiagItem(
    code = DiagCodes.DIAG_SECTION_UNAVAILABLE,
    section = section,
    severity = DiagSeverity.WARNING,
    titleRes = R.string.diag_diag_section_unavailable_title,
  )

  companion object {
    val DEFAULT_TIMEOUT: Duration = 10.seconds
  }
}
