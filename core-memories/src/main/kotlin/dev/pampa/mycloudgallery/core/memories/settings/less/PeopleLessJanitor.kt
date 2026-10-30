package dev.pampa.mycloudgallery.core.memories.settings.less

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cosa ha fatto un giro del janitor (solo numeri, D33): [dropped] scelte tolte, [moved] scelte portate sull'id di adesso, [pruned] lo scaffale e' stato ripulito. */
class SweepReport(val dropped: Int, val moved: Int, val pruned: Boolean) {
  override fun toString(): String = "SweepReport(tolte=$dropped, spostate=$moved, scaffale=$pruned)"
}

/**
 * Il janitor delle scelte sulle persone (docs/fase9-progetto.md D11, D37, §2.3 "Persone spente o cancellate", E-MEM10): tiene in ordine le voci
 * `memories_less:person:*` e lo scaffale quando Persone cambia, senza toccare il codice della Fase 8. Due compiti, in un giro ([sweep]):
 *
 *  1. **Persone spenta o in cancellazione** ([PeoplePhase.gone], da [PeopleSwitch]): **tutte** le voci `memories_less:person:*` diventano
 *     lapidi (`dropAllPeopleChoices`, anche quelle di persone che questo dispositivo non conosce: le lapidi viaggiano e le tolgono anche agli
 *     altri), poi `Memories.prune()` toglie dallo scaffale i ricordi `PERSON`. Le lapidi **restano** (sono il segno "tolto" che vince nella
 *     fusione: una scelta rimessa da un dispositivo rimasto indietro non torna a vivere);
 *  2. **Persone unite**: con Persone disponibile qui, una scelta sull'id di una persona che `MemoryPeople.resolve` dice unita a un'altra passa
 *     sull'id nuovo (vale la piu' forte fra le due, [LessLevels.stronger]) e la vecchia diventa una lapide; poi lo scaffale si ripulisce.
 *
 * **Che cosa non fa, di proposito.** Una persona che `resolve` non conosce (nascosta, dimenticata, o semplicemente non ancora arrivata su questo
 * dispositivo: la porta non le distingue) **non** fa togliere la sua scelta: una lapide scritta qui per un ritardo di sincronizzazione la
 * toglierebbe a tutto l'account (L450). Il ricordo di quella persona esce comunque dallo scaffale (`ChoiceRules.personStillShown`) e la scelta
 * si toglie dall'elenco di Impostazioni. Con [PeoplePhase.UNKNOWN] non fa niente.
 *
 * `sweep` e' idempotente (un secondo giro di fila non scrive niente) e si puo' chiamare quando si vuole: [watch] lo fa a ogni cambio di
 * stato, di disponibilita' o di scelte, e :app (P13) lo chiama anche dopo ogni giro dei volti (un'unione non cambia nessuno di quei tre).
 * Nei log solo numeri; mai id ne' nomi.
 */
class PeopleLessJanitor(
  private val settings: MemoriesPersonalSettings,
  private val people: MemoryPeople,
  private val switch: PeopleSwitch,
  private val memories: Memories,
) {

  private val lock = Mutex()

  suspend fun sweep(): SweepReport = lock.withLock {
    var dropped = 0
    var moved = 0
    if (switch.phase.value.gone) {
      dropped = settings.dropAllPeopleChoices()
    } else if (people.available.value) {
      moved = followAliases()
    }
    // Lo scaffale si ripulisce sempre: una persona nascosta o dimenticata non cambia nessuna scelta, ma i suoi ricordi devono uscire (E-MEM10).
    // La pulizia scrive solo se cambia qualcosa.
    memories.prune()
    SweepReport(dropped, moved, pruned = true)
  }

  /** Segue i cambiamenti di Persone e delle scelte in [scope] e rifa' [sweep]; un guasto si riprova al cambiamento dopo. */
  fun watch(scope: CoroutineScope): Job = scope.launch {
    combine(switch.phase, people.available, settings.choices) { phase, available, _ -> phase to available }
      .collectLatest { sweepQuietly() }
  }

  private suspend fun followAliases(): Int {
    var moved = 0
    val choices = settings.current()
    for ((person, level) in choices.people.entries.sortedBy { it.key }) {
      val resolved = people.resolve(person) ?: continue
      if (resolved == person) continue
      val merged = LessLevels.stronger(level, choices.people[resolved])
      // Prima la voce nuova, poi la lapide della vecchia: a meta' restano due scelte, mai nessuna.
      if (merged != choices.people[resolved]) settings.setLess(resolved, merged)
      settings.setLess(person, null)
      moved++
    }
    return moved
  }

  private suspend fun sweepQuietly() {
    try {
      val report = sweep()
      if (report.dropped > 0 || report.moved > 0) {
        GalleryLog.d(TAG) { "scelte sulle persone: tolte=${report.dropped}, spostate=${report.moved}" }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un guasto della lettura dei visibili lascia lo scaffale com'e' (R17): si riprova al prossimo cambiamento. Mai il messaggio (puo' avere un id).
      GalleryLog.w(TAG) { "pulizia delle scelte sulle persone non riuscita (${e.javaClass.simpleName})" }
    }
  }

  override fun toString(): String = "PeopleLessJanitor"

  private companion object {
    const val TAG = "PeopleLessJanitor"
  }
}
