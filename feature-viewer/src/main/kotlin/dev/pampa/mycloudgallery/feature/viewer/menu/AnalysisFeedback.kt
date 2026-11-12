package dev.pampa.mycloudgallery.feature.viewer.menu

import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// XS-6 (Pacchetto XF): lo stato dell'analisi che il visore osserva e il riscontro "analisi finita" sono **legati al media e alla visita** in
// cui sono nati. Solo la logica dello stato: il disegno (capsula, voce del menu, aptica) non cambia. Prima, `produceState` rilanciava il
// producer al cambio di foto ma teneva il valore del media di prima fino alla prima emissione del nuovo, e `askedAnalysis` ricordava un solo
// id fra una visita e l'altra: un tocco di completamento poteva nascere dallo stato di un'altra foto o da una richiesta di una visita
// precedente.

/** Lo stato dell'analisi osservato **per** [id]: un valore di un altro media non vale per [id]. */
internal class AnalysisObserved(val id: MediaId, val state: MediaAnalysis)

/** Lo stato di [observed] per il media aperto adesso [current]: quello di un altro media (o niente) vale "non si sa ancora". */
internal fun observedFor(current: MediaId, observed: AnalysisObserved?): MediaAnalysis =
  if (observed != null && observed.id == current) observed.state else MediaAnalysis.UNKNOWN

/**
 * Lo stato di [id] come flusso **legato a [id]**: parte da "non si sa ancora" (mai dal valore di una foto di prima) e ogni emissione porta con
 * se' l'id per cui e' nata. Il visore lo rilancia a ogni cambio di foto.
 */
internal fun ViewerAnalysis.observeBound(id: MediaId): Flow<AnalysisObserved> = flow {
  emit(AnalysisObserved(id, MediaAnalysis.UNKNOWN))
  var lastVersion: Long? = null
  observe(id).collect { state ->
    // XS2-5: al cambio di **versione** dello stesso media il binding si azzera ("non si sa") prima del nuovo stato: niente stato della versione di prima.
    val version = state.version
    if (lastVersion != null && version != null && version != lastVersion) emit(AnalysisObserved(id, MediaAnalysis.UNKNOWN))
    if (version != null) lastVersion = version
    emit(AnalysisObserved(id, state))
  }
}

/**
 * Il riscontro di fine analisi (un tocco aptico) vale **solo per la richiesta fatta in questa visita**: chi tocca "Analizza adesso" su una
 * foto e poi passa a un'altra non ottiene un riscontro tardivo tornando indietro, e uno stato "pronto" visto per una foto che nessuno ha
 * chiesto da qui non da' nessun riscontro.
 */
internal class AnalysisFeedback {
  private var askedFor: MediaId? = null
  private var askedVersion: Long? = null

  /** L'utente ha chiesto l'analisi di [id] (da questa visita), per la versione [version] che il visore vedeva (null = non nota). */
  fun asked(id: MediaId, version: Long? = null) {
    askedFor = id
    askedVersion = version
  }

  /** Il visore e' arrivato su [now]: la richiesta fatta su un'altra foto finisce con la visita a quella foto. */
  fun moved(now: MediaId) {
    if (askedFor != now) askedFor = null
  }

  /** Lo stato osservato di [id] e' [kind]: vero **una volta sola** se e' la fine dell'analisi chiesta in questa visita. */
  fun completed(id: MediaId, kind: MediaAnalysisKind, version: Long? = null): Boolean {
    if (askedFor != id) return false
    // XS2-5: una richiesta fatta per una versione non da' riscontro per un'altra: se la versione e' cambiata la richiesta e' finita senza riscontro.
    val asked = askedVersion
    if (asked != null && version != null && version != asked) {
      askedFor = null
      return false
    }
    if (kind != MediaAnalysisKind.READY) return false
    askedFor = null
    return true
  }
}
