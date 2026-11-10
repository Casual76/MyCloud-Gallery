package dev.pampa.mycloudgallery.feature.albums.list

import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.model.AlbumKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// "CREA" DI UN ALBUM A MANO, UNA VOLTA SOLA E SENZA CHIUDERE L'APP (K3b, SOL-10 e SOL-11). Lo usano i due controllori che aprono il foglio "Nuovo album" (la scheda Album e
// l'elenco "Tutti gli album"): la stessa regola in un posto solo.
//
// - **Un invio alla volta** (SOL-11): prima ogni tocco su "Crea" lanciava una creazione, e il foglio si chiudeva solo al ritorno: due tocchi veloci facevano due album. Ora lo stato
//   "sto salvando" si imposta come prima cosa, prima di qualunque sospensione; un altro invio mentre [saving] e' vero si rifiuta con [CreateAlbumResult.Busy]. Si toglie sempre
//   alla fine: anche su errore e su cancellazione.
// - **Memoria piena** (SOL-10): il registro dei dati personali non si scrive (`IOException`), e l'eccezione non arriva all'ambito dell'interfaccia (chiudeva l'app): e'
//   [CreateAlbumResult.NotSaved]. Niente e' stato creato (`PersonalStore.setAll` e' tutto o niente), il foglio resta aperto con il nome scritto e "Crea" si puo' ritoccare.

class ManualAlbumCreator(private val albums: Albums) {
  private val state = MutableStateFlow(false)

  /** Vero finche' una creazione e' in corso: il foglio spegne "Crea" e non si lascia chiudere. */
  val saving: StateFlow<Boolean> = state.asStateFlow()

  /** Crea un album personale. [CreateAlbumResult.Busy] se un'altra creazione e' ancora in corso (niente e' stato chiesto). */
  suspend fun create(title: String): CreateAlbumResult {
    if (!state.compareAndSet(expect = false, update = true)) return CreateAlbumResult.Busy
    try {
      val out = QuietWrites.attemptResult("nuovo album") { albums.create(title, AlbumKind.MANUAL, null) } ?: return CreateAlbumResult.NotSaved
      return when (out) {
        is CreateOutcome.Created -> CreateAlbumResult.Created(out.id)
        is CreateOutcome.Invalid -> CreateAlbumResult.InvalidTitle(out.problem)
        CreateOutcome.RulesTooLong, CreateOutcome.Unavailable -> CreateAlbumResult.Unavailable
      }
    } finally {
      state.value = false
    }
  }
}
