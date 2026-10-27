package dev.pampa.mycloudgallery.core.faces.switch

import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.personal.contract.PrePublishHook

/**
 * Il gancio prima di ogni pubblicazione dei dati personali (D30, §2.8 "Gli altri dispositivi" punto 2), legato all'interruttore: legge la voce `faces` dallo
 * stato che sta per uscire ([FacesSettingCodec.read]) e chiede a [FacesSwitch.reconcile] di togliere **prima** i dati locali di una generazione che quello
 * stato non nomina piu' accesa. Solo se hanno detto "fatto" il giro puo' pubblicare ([PrePublish.Proceed]); se non e' riuscito, quel giro non pubblica
 * ([PrePublish.Hold] con il codice stabile, mai un dato). E' cosi' che una testa personale con `off|G` (o con una generazione piu' nuova) esiste solo quando i
 * dati di `G` su questo dispositivo sono tolti: la conferma che legge "Disattiva e cancella" (passo 10).
 *
 * Lo mette nel motore di sincronizzazione P8 (il parametro `PrePublishHook` di `NasPersonalSync`); lo lega `:app` (P13).
 */
class FacesPrePublishHook(private val switch: FacesSwitch) : PrePublishHook {

  override suspend fun beforePublish(state: Map<PersonalKey, PersonalEntry>): PrePublish =
    when (val outcome = switch.reconcile(FacesSettingCodec.read(state))) {
      ReconcileOutcome.Done -> PrePublish.Proceed
      is ReconcileOutcome.Failed -> PrePublish.Hold(outcome.code)
    }
}
