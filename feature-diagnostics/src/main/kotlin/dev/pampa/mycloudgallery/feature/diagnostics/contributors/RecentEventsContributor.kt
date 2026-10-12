package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes

/**
 * «Ultimi esiti ed errori» (docs/fase2-progetto.md §2.6): gli ultimi eventi dell'anello [DiagnosticsLog], dal piu'
 * recente. Ogni evento e' solo un codice, una gravita' e dei valori tipizzati (`DiagEvent` non ha testo libero),
 * quindi si puo' mostrare ed esportare cosi' com'e'.
 *
 * **L'ora si esporta come eta'** (`ageMs`, quanto tempo fa): sapere «tre ore fa» basta a leggere cosa e' successo,
 * e un'ora esatta del telefono e' un dato in piu' che non serve a nessuno.
 *
 * Il titolo viene dal [DiagCatalog] (un evento non porta testo); un codice che il catalogo non conosce si mostra
 * come «Evento» con il suo identificativo. La chiave `ageMs` e' riservata: un valore dell'evento con quel nome
 * viene sovrascritto, non il contrario.
 */
class RecentEventsContributor(
  private val log: DiagnosticsLog,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val limit: Int = DEFAULT_LIMIT,
) : DiagnosticsContributor {

  init {
    require(limit > 0) { "limite non valido" }
  }

  override val section: DiagSection = DiagSection.RECENT_EVENTS

  override suspend fun collect(): List<DiagItem> {
    val now = nowMillis()
    return log.snapshot().asReversed().take(limit).map { event -> itemFor(event, now) }
  }

  private fun itemFor(event: DiagEvent, now: Long): DiagItem {
    val titleRes = DiagCatalog.titleOf(event.code).takeIf { it != 0 } ?: DiagCatalog.titleOf(DiagCodes.EVT_GENERIC)
    return DiagItem(
      code = event.code,
      section = DiagSection.RECENT_EVENTS,
      severity = event.severity,
      titleRes = titleRes,
      export = event.values + (AGE_KEY to ExportValue.Dur((now - event.at).coerceAtLeast(0L))),
    )
  }

  companion object {
    const val DEFAULT_LIMIT = 50

    /** La chiave con cui l'eta' dell'evento entra nelle voci (e quindi nell'esportazione). */
    const val AGE_KEY = "ageMs"
  }
}
