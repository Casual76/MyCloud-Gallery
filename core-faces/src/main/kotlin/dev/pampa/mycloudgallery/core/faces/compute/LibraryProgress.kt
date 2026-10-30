package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import kotlinx.coroutines.CancellationException

/**
 * L'AVANZAMENTO DELLA LIBRERIA (correzioni C, CA-1; docs/review/funzionale-8.md §2.2): "Analizzate 3.400 foto su 12.000", non "40 su 200". Prima ogni media
 * diceva `Running(fatti dell'unita', media dell'unita')`: una percentuale a dente di sega (da 0 a 100% a ogni unita' da 200 media, 100% durante il
 * raggruppamento e la pubblicazione) e un "su 200" per una libreria da 12.000 foto.
 *
 * La libreria si conta **una volta per tappa** ([count], con [FacesBacklog.tally]: costa una lettura del catalogo, L242), non a ogni media, e solo quando c'e'
 * davvero un'unita' da lavorare (una tappa che si ferma subito non paga la lettura). I fatti di partenza sono i media gia' analizzati in tappe di prima; da li'
 * cresce di uno a ogni media finito (analizzato o "non supportato": entrambi sono nell'archivio). Il totale non si abbassa e i fatti non lo superano mai: un media
 * arrivato dopo il conteggio non fa passare la percentuale oltre il 100%.
 *
 * Una per tappa: [FacesComputeEngine.runAll] ne crea una e la passa a tutti gli ambiti (privata, comune, solo telefono), cosi' il totale e' della libreria intera.
 */
internal class LibraryProgress {
  private var counted = false
  private var total = 0
  private var doneAtStart = 0
  private var advanced = 0

  /**
   * Conta la libreria, la prima volta che serve ([source] la legge); le altre chiamate non fanno niente. Un conteggio che fallisce (catalogo non leggibile) lascia
   * il totale a zero: la pagina dice "sto analizzando" senza numeri, mai numeri sbagliati. Non e' un errore della tappa.
   */
  suspend fun count(source: suspend () -> FacesTally) {
    if (counted) return
    counted = true
    val tally = try {
      source()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    total = tally.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    doneAtStart = tally.done.coerceAtMost(total.toLong()).toInt()
  }

  /** Un media in piu' e' finito (analizzato o "non supportato"). */
  fun advance() {
    advanced++
  }

  /** Lo stato di adesso, nella [phase] indicata. Totale zero = "non so quanti": la pagina non mostra la percentuale. */
  fun running(phase: FacesPhase): FacesStatus.Running = FacesStatus.Running(minOf(doneAtStart + advanced, total), total, phase)
}
