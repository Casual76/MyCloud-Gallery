package dev.pampa.mycloudgallery.core.albums.upkeep

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope

// QUANDO SI CERCANO I VIAGGI (Fase 7, revisione di fine fase; docs/fase7-progetto.md §2.3 "Quando", E-AL16; VG-1). Il progetto dice "una volta per
// notte **e dopo un primo backup o una prima scansione**"; `:app` cercava solo di notte, in carica, e fino ad allora la sezione Viaggi diceva "Servono
// foto con la posizione per riconoscere i viaggi" a chi aveva migliaia di foto con il GPS. Qui il secondo caso:
//  - **mai cercati** ([TripProposals.searched] falso): si cerca appena il catalogo e' stato fermo per [quiet] (la prima scansione non e' piu' in
//    corso; cercare sul primo gruppo di foto darebbe una casa stimata su poche foto);
//  - **cercati senza trovare una casa**: puo' essere perche' le posizioni delle foto (META) non erano ancora lette. Se il catalogo e' cambiato da
//    allora e sono passati [retry], si cerca di nuovo; cosi' la sezione smette di dire "servono foto con la posizione" appena le foto ce l'hanno;
//  - con una casa nota basta la notte.
// Una ricerca interrotta dal budget (`incomplete`) non vale come fatta. Mai coordinate, casa o luoghi qui dentro: solo lo stato e un resoconto di numeri.

class TripUpkeep(
  scope: CoroutineScope,
  private val trips: Trips,
  private val clocks: AlbumClocks,
  /** Il resoconto di una ricerca (Diagnostica: solo numeri). */
  private val onReport: (TripReport) -> Unit = {},
  private val onError: (Exception) -> Unit = {},
  quiet: Duration = QUIET,
  private val retry: Duration = RETRY_WITHOUT_HOME,
  private val budget: Duration = BUDGET,
) {
  private val debouncer = Debouncer(scope, quiet) { checkNow() }

  @Volatile private var dirty = false
  @Volatile private var lastMs: Long? = null

  /** Il catalogo e' cambiato (o gli album si sono appena montati): fra poco, a catalogo fermo, si guarda se serve cercare. */
  fun poke() {
    dirty = true
    debouncer.poke()
  }

  /** Cerca adesso se serve. Vero se ha cercato. Per i test e per [poke]. */
  suspend fun checkNow(): Boolean {
    if (!due(trips.proposals.value)) return false
    dirty = false
    lastMs = clocks.monotonicMs()
    try {
      onReport(trips.detect(budget))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      onError(e)
    }
    return true
  }

  private fun due(proposals: TripProposals): Boolean = when {
    !proposals.searched -> true
    proposals.homeKnown -> false
    else -> dirty && lastMs?.let { clocks.monotonicMs() - it >= retry.inWholeMilliseconds } != false
  }

  companion object {
    /** Il catalogo fermo per tanto: la scansione non sta piu' arrivando. */
    val QUIET: Duration = 1.minutes

    /** Senza una casa, una nuova ricerca non prima di tanto dalla precedente (le posizioni delle foto arrivano da META, a poco a poco). */
    val RETRY_WITHOUT_HOME: Duration = 10.minutes

    /** Il tempo di una ricerca (la notte ne ha uno suo): sulle Librerie grandi bastano pochi secondi (F91). */
    val BUDGET: Duration = 2.minutes
  }
}
