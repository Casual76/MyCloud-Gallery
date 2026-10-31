package dev.pampa.mycloudgallery.core.widget.contract

// LA DIAGNOSTICA DEL WIDGET (docs/fase10-progetto.md §2.13, D39, C449). Solo codici e numeri: **mai** id di media o di ricordi, titoli, luoghi,
// date, descrizioni. La sezione e' `DiagSection.WIDGET` (core-common, P0) con la voce di stato `WIDGET_STATE` gia' nel catalogo; gli altri
// codici e le chiavi dell'esportazione li aggiunge P13 (feature-diagnostics e `ExportKeys`).

/** I codici del §2.13. */
object WidgetDiagCodes {
  const val STATE: String = "WIDGET_STATE"
  const val SKIPPED: String = "WIDGET_SKIPPED"
  const val BLANKED: String = "WIDGET_BLANKED"
  const val LATE: String = "WIDGET_LATE"
  const val ERROR: String = "WIDGET_ERROR"
}

/** Perche' un widget e' stato svuotato (conteggi per causa, D5, D42). */
enum class WidgetBlankCause(val code: String) {
  HIDDEN("hidden"),
  MEMORY_DROPPED("memory_dropped"),
  RESET("reset"),
}

/**
 * I numeri della sezione `WIDGET`: widget attivi per misura, minuti dall'ultimo aggiornamento, frequenza scelta, media saltati (senza miniatura
 * locale, per il blocco), svuotamenti per causa, giri periodici mancati in 7 giorni, codice dell'ultimo errore.
 */
data class WidgetDiag(
  val widgets: Map<WidgetSize, Int>,
  val minutesSinceUpdate: Long?,
  val period: WidgetPeriod,
  val skippedNoThumb: Int,
  val skippedLocked: Int,
  val blanked: Map<WidgetBlankCause, Int>,
  val lateRuns7d: Int,
  val lastError: WidgetEmpty?,
) {
  init {
    require(widgets.values.all { it >= 0 } && blanked.values.all { it >= 0 }) { "Conteggi negativi." }
    require(skippedNoThumb >= 0 && skippedLocked >= 0 && lateRuns7d >= 0) { "Conteggi negativi." }
  }
}
