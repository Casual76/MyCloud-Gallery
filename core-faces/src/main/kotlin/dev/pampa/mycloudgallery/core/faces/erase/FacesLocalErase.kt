package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.compute.FacesSignals
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.SearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.store.LocalErase
import dev.pampa.mycloudgallery.core.faces.switch.FacesLocalDataEraser
import dev.pampa.mycloudgallery.core.faces.switch.LocalEraseWhy
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// LA CANCELLAZIONE LOCALE DEI VOLTI E IL RECINTO DELLE APERTURE (Fase 8, P8; docs/fase8-progetto.md §2.8 passo 6, D14, D29, D30; E-F8, E-F32).
// Una sola operazione per tutte le strade che tolgono i dati locali: "Disattiva e cancella" (passo 6), l'esclusione di questo dispositivo e la
// generazione non piu' corrente (l'interruttore di P7, porta `FacesLocalDataEraser`), il gancio prima della pubblicazione dei dati personali (D30).
// Sempre la chiave del Keystore per prima (`FacesStores.eraseLocal`), poi i file; le cache in memoria; i modelli estratti quando Persone non
// serve piu' qui.

/**
 * Il recinto delle aperture (D29, invariante 1 del §2.8): una generazione si apre (archivio, negozio) **solo** se questo dispositivo la tratta adesso
 * ([FacesState.activeHere] con quella generazione) e nessuna cancellazione la nomina ([block]); e mai mentre la si cancella (un lucchetto solo per
 * le aperture e le cancellazioni). Senza, un calcolo o una replica che ha guardato lo stato un attimo prima della cancellazione potrebbe riaprire
 * il negozio dopo e far rinascere `faces-<G>.db` con una chiave nuova. `:app` (P13) passa da qui ogni apertura dei dati dei volti
 * ([archives], [open]).
 */
class FacesEraseFence(private val switch: FacesSwitch) {
  private val mutex = Mutex()

  @Volatile private var blocked: Set<FaceGeneration> = emptySet()

  /** Le generazioni che una cancellazione nomina (le scrive il diario di "Disattiva e cancella"). */
  fun block(generations: Collection<FaceGeneration>) {
    blocked = generations.toSet()
  }

  /** Vero se adesso [generation] si puo' aprire. */
  fun allows(generation: FaceGeneration): Boolean {
    val state = switch.currentState()
    return state.activeHere && state.generationOrNull == generation && generation !in blocked
  }

  /** Apre con [opener] solo se [allows]; altrimenti null (nessun negozio nasce o si riapre). */
  suspend fun <T : Any> open(generation: FaceGeneration, opener: suspend () -> T?): T? = mutex.withLock {
    if (!allows(generation)) null else opener()
  }

  /** Le porte degli archivi (replica, pubblicatore, persone) dietro il recinto. */
  fun archives(inner: FacesArchives): FacesArchives = FacesArchives { generation -> open<FacesArchive>(generation) { inner.open(generation) } }

  /** Una cancellazione: nessuna apertura nel frattempo. */
  internal suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

  override fun toString(): String = "FacesEraseFence"
}

/**
 * Toglie i dati locali di una generazione: prima le ricerche recenti con i nomi di quella generazione ([history]: i nomi si leggono finche' i dati
 * ci sono; vale anche sugli altri dispositivi, che cancellano dal gancio di D30), poi la chiave, poi `faces-<G>.db` con i compagni
 * ([FacesLocalData.erase]), le cache in memoria, e (salvo un cambio di generazione) i modelli estratti e i segni dei lavori. **Vero solo se non resta niente del database e della chiave**: i resti
 * secondari (un modello che non si toglie) si ritentano alla volta dopo, non tengono ferma la cancellazione ne' il gancio di D30. Idempotente, non
 * si interrompe a meta' (`NonCancellable`), mai un'eccezione per un caso previsto. E' la `FacesLocalDataEraser` che l'interruttore di P7 vuole.
 *
 * Correzioni D (R2): i nomi si leggono **fino a tre volte** (un Keystore che non risponde un attimo non fa perdere i nomi: poi la chiave si distrugge e non si
 * leggono piu'), si tolgono per nome **e per id** della persona, e se i nomi non si leggono lo stesso si toglie ogni ricerca nata da un chip "Persona"; se
 * la cronologia resta com'era la Diagnostica lo dice ([signals]: `FACES_HISTORY_KEPT`; L190 esteso agli altri dispositivi).
 */
class FacesLocalErase(
  private val data: FacesLocalData,
  private val fence: FacesEraseFence,
  private val leftovers: FacesLocalLeftovers = FacesLocalLeftovers.NONE,
  private val history: SearchHistoryPurge? = null,
  private val signals: FacesSignals = FacesSignals.NONE,
) : FacesLocalDataEraser {

  override suspend fun erase(generation: FaceGeneration, why: LocalEraseWhy): Boolean =
    withContext(NonCancellable) { fence.exclusive { eraseUnlocked(generation, why) } }

  /** R1: tutto cio' che e' sul disco (anche una generazione che nessuno ricorda); null se adesso non si legge. */
  override suspend fun generationsOnDisk(): List<FaceGeneration>? = attempt { data.generationsOnDisk() }

  /** La cancellazione con il lucchetto del recinto gia' preso. */
  internal suspend fun eraseUnlocked(generation: FaceGeneration, why: LocalEraseWhy): Boolean {
    // Le ricerche recenti con i nomi: al meglio (i dati biometrici non aspettano la cronologia), ma con piu' tentativi e un segno se resta com'era.
    if (history != null) purgeHistory(history, generation)
    val erased = attempt { data.erase(generation) } ?: LocalErase(keyDestroyed = false, filesGone = false)
    attempt { leftovers.clearMemory() }
    if (why != LocalEraseWhy.GENERATION_CHANGED) {
      attempt { leftovers.removeModels() }
      attempt { leftovers.clearWorkMarks() }
    }
    return erased.done && attempt { !data.hasData(generation) } == true
  }

  /**
   * R2: i nomi e le persone di [generation] si leggono fino a [ATTEMPTS] volte e si tolgono dalla cronologia per nome e per id. Se non si leggono, ogni ricerca con
   * un chip "Persona" si toglie lo stesso. Se la cronologia non si e' potuta ripulire (nomi illeggibili o la porta che non risponde) lo dice la Diagnostica.
   */
  private suspend fun purgeHistory(history: SearchHistoryPurge, generation: FaceGeneration) {
    var records: Map<PeopleKey, PeopleRecord>? = null
    for (i in 1..ATTEMPTS) {
      records = attempt { data.people(generation) }
      if (records != null) break
    }
    if (records == null) {
      (1..ATTEMPTS).any { attempt { history.purgePersonSearches() } != null }
      signals.report(FacesDiag.FACES_HISTORY_KEPT, 1)
      return
    }
    val names = EraseRules.names(records.values)
    val persons = EraseRules.persons(records.values)
    if (names.isEmpty() && persons.isEmpty()) return
    if ((1..ATTEMPTS).none { attempt { history.purge(names, persons) } != null }) signals.report(FacesDiag.FACES_HISTORY_KEPT, 1)
  }

  private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  override fun toString(): String = "FacesLocalErase"

  private companion object {
    /** I tentativi per leggere i nomi e per ripulire la cronologia (come `DefaultFacesEraser.ATTEMPTS`). */
    const val ATTEMPTS = 3
  }
}
