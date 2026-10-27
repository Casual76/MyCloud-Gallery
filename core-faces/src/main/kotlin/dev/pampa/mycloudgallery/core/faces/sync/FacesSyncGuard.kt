package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.registry.DeviceEntry
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.Locale

// IL FILTRO DELLA GENERAZIONE E I CANCELLI DI sync/ (Fase 8, P3; docs/fase8-progetto.md D11, D30, D34, Q5, L23). Prima di leggere o scrivere
// un solo byte sotto `faces/`, e di nuovo prima di pubblicare una testa:
// - registro accettato e questo dispositivo approvato (un dismesso, revocato o perso non scrive e non legge dati dei volti);
// - la share e' l'area privata dichiarata, **mai** l'area comune del registro (L23);
// - la recinzione delle share vere (D34, `FeatureFlags.facesSyncTestSharesOnly`, accesa anche in release fino al si' dell'utente, Q5): sulle
//   share vere niente sul NAS, ne' letture ne' scritture; sulle share di prova si';
// - Persone attiva per l'account e attiva QUI (`On(G, ACTIVE)`: niente in attesa dell'avviso, esclusione, cancellazione, voce sconosciuta);
// - lo stato **fresco** (D30): un giro completo dei dati personali in questo processo da meno di 15 minuti che dica `On(G)`. Senza, la
//   generazione corrente non e' certa: nessun dato dei volti si legge ne' si pubblica, e una generazione non corrente non si legge mai.

/** Che cosa si puo' fare adesso con i dati dei volti sul NAS. */
internal sealed interface FacesGate {
  /** Si puo', nella generazione [generation], come [me] dell'account [account] (minuscolo). */
  class Open(val generation: FaceGeneration, val registry: RegistryState, val me: DeviceEntry, val account: String) : FacesGate {
    override fun toString(): String = "FacesGate.Open"
  }

  /** Non si puo': [stop] per il resoconto della replica, [block] per il pubblicatore (null = non e' un cancello delle scritture). */
  data class Closed(val stop: FacesSyncStop, val block: FacesWriteBlock?) : FacesGate
}

internal class FacesSyncGuard(
  private val identity: FacesSyncIdentity,
  private val switch: FacesSwitch,
  private val share: () -> String,
  private val flags: FeatureFlags,
) {

  fun check(): FacesGate {
    val registry = identity.registry() ?: return FacesGate.Closed(FacesSyncStop.UNAVAILABLE, FacesWriteBlock.NOT_TRUSTED)
    val me = registry.registry.device(identity.deviceId)?.takeIf { it.status == DeviceStatus.APPROVED }
      ?: return FacesGate.Closed(FacesSyncStop.UNAVAILABLE, FacesWriteBlock.NOT_TRUSTED)
    val area = share()
    // L23: un'area privata uguale all'area comune non e' un'area privata. I dati dei volti non entrano mai nell'area comune.
    if (registry.registry.config.commonShare.equals(area, ignoreCase = true)) {
      return FacesGate.Closed(FacesSyncStop.UNAVAILABLE, FacesWriteBlock.NO_PRIVATE_AREA)
    }
    // D34, Q5: sulle share vere niente sul NAS finche' l'utente non dice di si'.
    if (!flags.facesSyncMayUse(area)) return FacesGate.Closed(FacesSyncStop.LOCAL_ONLY, FacesWriteBlock.TEST_SHARES_ONLY)
    val state = switch.currentState()
    if (state !is FacesState.On) return FacesGate.Closed(FacesSyncStop.NOT_ACTIVE, null)
    when (state.here) {
      HereState.ACTIVE -> Unit
      HereState.LOCAL_ONLY -> return FacesGate.Closed(FacesSyncStop.LOCAL_ONLY, FacesWriteBlock.TEST_SHARES_ONLY)
      HereState.EXCLUDED -> return FacesGate.Closed(FacesSyncStop.NOT_ACTIVE, null)
    }
    if (!switch.fresh.value) return FacesGate.Closed(FacesSyncStop.NOT_FRESH, null)
    return FacesGate.Open(state.generation, registry, me, me.nasUser.lowercase(Locale.ROOT))
  }

  /** Vero se il cancello aperto [open] vale ancora: stessa generazione, stato ancora fresco (la generazione che cambia a meta' ferma tutto). */
  fun stillOpen(open: FacesGate.Open): Boolean = (check() as? FacesGate.Open)?.generation == open.generation

  companion object {
    /** I dispositivi i cui dati dei volti si leggono: approvati o dismessi **dell'account** [account] (D11). */
    fun accountDevices(registry: RegistryState, account: String): List<String> = registry.registry.devices
      .filter { (it.status == DeviceStatus.APPROVED || it.status == DeviceStatus.RETIRED) && TextRules.sameAccount(it.nasUser, account) }
      .map { it.id }
      .sorted()

    /** I dispositivi revocati o persi (di qualunque account): nessun loro dato vale, qualunque data dichiari (formato §4.6). */
    fun barred(registry: RegistryState): Set<String> = registry.registry.devices.filter { it.status.isBarred }.mapTo(LinkedHashSet()) { it.id }
  }
}
