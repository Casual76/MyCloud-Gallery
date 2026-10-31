package dev.pampa.mycloudgallery.core.map.points

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPointSource
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il servizio dei punti della mappa (Fase 9, P3; `docs/fase9-progetto.md` §2.10 "Aggiornamento", D27, E-MAP15): tiene l'indice di adesso
 * ([index]) e lo ricostruisce **fuori dal thread principale**, su [dispatcher] (`Dispatchers.Default` in produzione, un dispatcher di prova nei test).
 *
 * - **Niente all'avvio**: costruire il servizio non legge niente; l'indice si fa alla prima [rebuild] con `OPEN` (la mappa che si apre, o la
 *   sezione Luoghi). Prima lo stato e' [GeoIndexState.Building]. Un'apertura con l'indice gia' al passo (nessun cambio dopo l'ultima lettura)
 *   non rifa niente: "a caldo".
 * - **Scambio atomico**: chi legge [index] vede sempre un indice intero, vecchio o nuovo. Durante una ricostruzione resta quello vecchio (non
 *   si torna a `Building`); lo stato cambia con una sola assegnazione di un oggetto immutabile.
 * - **Calma** (`MapPoints.REBUILD_QUIET_MS`, 2 s): `SNAPSHOT_CHANGED` aspetta [quietMs] e, se nel frattempo e' arrivata un'altra richiesta di
 *   cambio, lascia a quella il compito di ricostruire (ricostruisce solo l'ultima di una raffica). `HIDDEN_CHANGED` **non aspetta**: un
 *   media diventato nascosto deve uscire da bolle e copertine appena l'indice e' pronto (E-MAP15, D31; il filtro finale `HiddenMask` di chi
 *   mostra le copertine copre l'intervallo).
 * - **Un solo lavoro alla volta**: le ricostruzioni si mettono in fila; chi entra per ultimo controlla se l'indice e' gia' al passo.
 *   Una ricostruzione annullata non pubblica niente e non segna l'indice come al passo (la prossima `OPEN` lo rifa).
 * - **Errori** (MA-1): una lettura che fallisce lascia l'indice che c'era (valido: l'utente continua a vederlo) o, se non ce n'era, vale [GeoIndexState.Failed]
 *   (mai [GeoIndexState.Empty]: un database che non risponde non e' "nessuna foto con la posizione"); **non** segna l'indice come al passo, cosi' la prossima
 *   richiesta (anche il "Riprova" delle schermate, `rebuild(OPEN)`) ci riprova. Nel log solo numeri.
 */
class GeoMapPoints(
  private val source: MapPointSource,
  private val dispatcher: CoroutineDispatcher,
  private val quietMs: Long = MapPoints.REBUILD_QUIET_MS,
) : MapPoints {

  private val state = MutableStateFlow<GeoIndexState>(GeoIndexState.Building)
  override val index: StateFlow<GeoIndexState> = state.asStateFlow()

  private val lock = Mutex()

  /** Quante volte e' cambiato il mondo (istantanea, nascosti) da quando esiste il servizio: la "generazione" che l'indice deve aver letto. */
  private val requested = AtomicLong(0)

  /** La generazione letta dall'indice di [state]; -1 finche' non c'e' un indice. */
  @Volatile
  private var built: Long = NOTHING

  override suspend fun rebuild(reason: RebuildReason) {
    when (reason) {
      RebuildReason.OPEN -> Unit
      RebuildReason.SNAPSHOT_CHANGED -> {
        val ticket = requested.incrementAndGet()
        delay(quietMs)
        // Una richiesta piu' recente ricostruira' lei (dopo la sua calma): questa ha finito.
        if (requested.get() != ticket) return
      }
      RebuildReason.HIDDEN_CHANGED -> requested.incrementAndGet()
    }
    lock.withLock {
      val target = requested.get()
      if (built == target) return
      // Il "Riprova" dopo un errore: la schermata torna a "cerco le foto" finche' la lettura non finisce (se fallisce ancora, di nuovo `Failed`).
      if (state.value is GeoIndexState.Failed) state.value = GeoIndexState.Building
      try {
        val next = read()
        state.value = next
        built = target
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // L'indice che c'era resta (valido); senza, lo stato d'errore e non una rotella eterna ne' "nessuna foto con la posizione" (salvo che il mondo sia
        // cambiato durante la lettura, per esempio un media cancellato fra due pagine: allora la richiesta che l'ha portato ricostruisce subito dopo, e lo
        // stato resta `Building`). Un `Failed` che si ripete resta `Failed`.
        if ((state.value is GeoIndexState.Building || state.value is GeoIndexState.Failed) && requested.get() == target) state.value = GeoIndexState.Failed
        // Del guasto solo il tipo: il messaggio di un errore del database potrebbe nominare dati.
        GalleryLog.w(TAG) { "ricostruzione dell'indice dei punti fallita (${e.javaClass.simpleName})" }
      }
    }
  }

  private suspend fun read(): GeoIndexState = withContext(dispatcher) {
    val started = System.nanoTime()
    val builder = GeoPointIndexBuilder()
    source.pages().collect { builder.addAll(it) }
    val counts = source.counts()
    val index = builder.build()
    val invalid = counts.invalid + builder.invalid
    GalleryLog.d(TAG) { "indice dei punti: ${index.size} punti, $invalid scartati, ${(System.nanoTime() - started) / 1_000_000} ms" }
    if (index.size == 0) GeoIndexState.Empty(counts.withoutPosition) else GeoIndexState.Ready(index, counts.withoutPosition, invalid)
  }

  override fun toString(): String = "GeoMapPoints"

  private companion object {
    const val TAG = "MapPoints"
    const val NOTHING = -1L
  }
}
