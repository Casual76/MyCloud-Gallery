package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.faces.compute.FacesSignals
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.erase.FacesLocalLeftovers
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.personal.contract.PrePublishHook
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagnosticsRecorder
import kotlinx.coroutines.CancellationException

// I PICCOLI LEGAMI DELLE PERSONE CON LA DIAGNOSTICA, I RESTI LOCALI E IL GANCIO DEI DATI PERSONALI (Fase 8, P13; docs/fase8-progetto.md §1.4, §2.8, §2.10, D30,
// D37). Solo codici: mai un nome, un id, una chiave, un riquadro, un vettore o una generazione.

/**
 * I segnali una tantum del calcolo e dell'archivio (`FACES_KEY_LOST`, `FACES_ENGINE_FAILED`...) nella Diagnostica: solo il codice, e solo se il catalogo lo
 * conosce (un codice che il catalogo non ha non si registra: la prova di P13 vuole ogni codice di `FacesDiag.ALL` nel catalogo).
 */
class FacesSignalsToDiagnostics(private val events: DiagnosticsRecorder) : FacesSignals {
  override fun report(code: String, count: Int) {
    if (count <= 0) return
    val diag = DiagCode(code)
    if (DiagCatalog.entry(diag) != null) events.code(diag)
  }
}

/**
 * I resti locali dei volti oltre al database e alla chiave (§2.8 passo 6): le cache in memoria (copertine ritagliate), i modelli estratti e i segni dei
 * lavori. Al meglio: la chiave distrutta rende illeggibile ogni resto; un modello che non si toglie si ritenta alla cancellazione dopo.
 */
class AppFacesLeftovers(
  private val memory: () -> Unit,
  private val models: suspend () -> Boolean,
  private val marks: suspend () -> Boolean,
) : FacesLocalLeftovers {
  override suspend fun clearMemory() = memory()

  override suspend fun removeModels(): Boolean = models()

  override suspend fun clearWorkMarks(): Boolean = marks()
}

/**
 * Il gancio prima di ogni pubblicazione dei dati personali (D30), che nasce **con il processo** (le persone si montano dopo, quando la famiglia e'
 * pronta). Montate: decide l'interruttore ([FacesLive.hook]: i dati di una generazione che la voce non nomina piu' accesa si tolgono **prima**, e se non
 * si riesce quel giro non pubblica). Non ancora montate: se sul disco ci sono dati dei volti non si puo' dire di averli tolti, quindi non si pubblica
 * (`Hold`); senza dati dei volti non c'e' niente da togliere e si pubblica. Cosi' una testa personale con `off|G` esiste solo dopo la cancellazione.
 *
 * I7 (correzioni della Fase 8): se il montaggio e' **fallito** (non solo "non ancora"), i dati personali non restano fermi per tutto il processo: escono
 * **senza la voce `faces`** ([PrePublish.ProceedWithout], codice [MOUNT_FAILED] nella Diagnostica). La testa cosi' non contiene la voce, quindi non vale
 * come conferma di una cancellazione (`EraseRules.confirms` vuole la voce) e non dice che questo dispositivo tratti una generazione. L'ordine di C131
 * non cambia: prima del montaggio (riuscito o fallito) nessuna testa con la voce `faces` esce con un database dei volti sul disco.
 */
class FacesPrePublish(private val graph: () -> FacesGraph?, private val stores: FacesStores) : PrePublishHook {
  override suspend fun beforePublish(state: Map<PersonalKey, PersonalEntry>): PrePublish {
    val faces = graph()
    val live = faces?.live?.value
    if (live != null) return live.hook.beforePublish(state)
    return try {
      when {
        stores.generationsOnDisk().isEmpty() -> PrePublish.Proceed
        faces?.mountFailed == true -> PrePublish.ProceedWithout(setOf(FacesSettingCodec.KEY), MOUNT_FAILED)
        else -> PrePublish.Hold(NOT_READY)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      PrePublish.Hold(NOT_READY)
    }
  }

  companion object {
    /** Il codice stabile del giro fermato perche' le persone non sono ancora montate (mai un dato). */
    const val NOT_READY: String = "FACES_ERASE_PENDING"

    /** I7: il codice stabile dei giri che pubblicano senza la voce `faces` perche' il montaggio e' fallito (mai un dato). */
    const val MOUNT_FAILED: String = FacesDiag.FACES_MOUNT_FAILED
  }
}
