package dev.pampa.mycloudgallery.core.personal.contract

import dev.pampa.mycloudgallery.core.model.FacesSetting
import kotlinx.coroutines.flow.StateFlow

// LETTURA E SCRITTURA DELLA VOCE `faces` (docs/fase8-progetto.md §1.2 "core-personal", §2.1, D3, D30; riga P7 del §4.4). Il codec
// ([FacesSettingCodec]) dice come e' fatto il valore; questa porta dice **dove** si legge e si scrive e con quale regola dell'ora. Lo stesso
// modello della lettura del testo (F6-D41, revisione B): la scelta fatta qui vale subito, anche senza l'ora del NAS, e viaggia alla prima
// sincronizzazione. L'implementazione vera e' `SyncedFacesSettings` (sopra `PersonalStore.effectiveEntries`); il fake e' in `testing/`.

/**
 * La voce `faces` di Persone **per l'account**, come la vede QUESTO dispositivo (D3).
 *
 * - **Si legge lo stato "effettivo"** ([PersonalStore.effectiveEntries]): lo stato unito con sopra la scelta fatta qui ancora "da datare".
 *   Con il solo stato che si pubblica, "Attiva" fatto fuori casa (senza l'ora del NAS) non varrebbe finche' il telefono non torna a casa, e
 *   i dati dei volti si tratterebbero o no a seconda della rete: la promessa "vale subito qui" (R6 della revisione di sicurezza della Fase 6)
 *   deve reggere anche per i dati biometrici.
 * - **Voce assente o lapide = spenta** ([setting] null). Una forma che questa versione non capisce e' [FacesSetting.Unknown] (E-F12): chi
 *   legge non calcola, non pubblica e non cancella niente.
 * - **Non si riscrive cio' che non si capisce**: con [FacesSetting.Unknown] come voce corrente [set] rifiuta (un'app piu' vecchia non
 *   spegne ne' sostituisce l'attivazione fatta da un'app piu' nuova).
 */
interface FacesPersonalSettings {
  /** La voce di adesso (null = assente = spenta). Un flusso: puo' ritardare di un istante rispetto a [current]. */
  val setting: StateFlow<FacesSetting?>

  /** La stessa voce, letta in modo **sincrono** (vale subito dopo un [set], cosa che [setting] non garantisce). */
  fun current(): FacesSetting?

  /**
   * La scelta fatta qui non e' ancora arrivata agli altri dispositivi: senza l'ora del NAS la voce resta "da datare" e la prende alla prima
   * sincronizzazione. La pagina lo dice ("Sto per dirlo agli altri dispositivi").
   */
  val syncPending: StateFlow<Boolean>

  /**
   * Scrive la scelta dell'utente ([FacesSetting.On] o [FacesSetting.Off]); [FacesSetting.Unknown] non si scrive mai (`IllegalArgumentException`).
   * Vera quando la voce c'e' (anche se era gia' uguale); falsa se la voce corrente e' [FacesSetting.Unknown]: niente e' stato scritto.
   * Vale subito qui, con o senza l'ora del NAS.
   */
  suspend fun set(setting: FacesSetting): Boolean
}
