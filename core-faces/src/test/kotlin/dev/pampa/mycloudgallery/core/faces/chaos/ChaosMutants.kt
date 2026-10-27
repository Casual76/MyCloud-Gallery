package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// GLI ERRORI INTRODOTTI APPOSTA (docs/prova-500.md Parte F, §F7): ognuno rompe una promessa di sicurezza della Fase 8 in un punto preciso del dispositivo
// del banco, e `FacesChaosMutationTest` vuole che il banco diventi rosso con l'invariante giusto (e che lo stack sano, sugli stessi semi, resti verde). Il
// codice di produzione non si tocca: ogni errore e' un decoratore o un'opzione dei pezzi del banco che riproduce l'**effetto** dell'errore sul codice vero.

internal enum class Mutant(val label: String, val expected: Invariant) {
  /** Il pubblicatore, la replica e le prenotazioni non guardano piu' la freschezza dello stato (D30): pubblicano anche con lo stato scaduto. */
  PUBLISH_WITHOUT_FRESH_STATE("pubblicare senza lo stato fresco", Invariant.PLACE),

  /** Il gancio prima di pubblicare i dati personali (D30) non cancella piu' niente: la testa con `off|G` esce con i dati di `G` ancora li'. */
  ANNOUNCE_BEFORE_ERASING("dichiarare la cancellazione prima di farla", Invariant.ERASED),

  /** La cancellazione locale toglie i file del database prima di distruggere la chiave (§2.8 passo 6). */
  FILES_BEFORE_KEY("togliere i file prima della chiave", Invariant.ERASED),

  /** La cancellazione sul NAS toglie, insieme ai file dei volti, un file fuori da `faces/` (un media). */
  DELETE_OUTSIDE_FACES("cancellare fuori da faces/", Invariant.OUTSIDE),
}

/** L'interruttore come lo vede chi pubblica quando la freschezza non si guarda piu': sempre fresco; tutto il resto e' quello vero. */
internal class AlwaysFreshSwitch(private val inner: FacesSwitch) : FacesSwitch by inner {
  override val fresh: StateFlow<Boolean> = MutableStateFlow(true)
}
